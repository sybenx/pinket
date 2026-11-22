package com.wisp.app.repo

import android.util.Log
import android.util.LruCache
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One "Spam" or "Not spam" vote by the user. */
data class SpamVote(
    val type: Type,
    /** Author pubkey, text-shape hash or link domain, depending on [type]. */
    val key: String,
    val spam: Boolean,
    /** What to show in the review list: the author's name, or the start of the post. */
    val label: String? = null,
    /** Id of the spam report published for this vote (author votes only). */
    val reportId: String? = null,
    val createdAt: Long = System.currentTimeMillis() / 1000
) {
    enum class Type(val db: String) {
        AUTHOR("author"), TEXT("text"), DOMAIN("domain");

        companion object {
            fun fromDb(value: String): Type? = entries.firstOrNull { it.db == value }
        }
    }
}

/**
 * Immutable in-memory view of the user's votes. Lookups are plain hash map
 * reads: safe on the main thread and inside locks.
 */
class SpamVotesSnapshot(votes: List<SpamVote>) {
    /** Newest first; within one vote the person comes before the post's text and links. */
    val all: List<SpamVote> = votes.sortedWith(
        compareByDescending<SpamVote> { it.createdAt }.thenBy { it.type.ordinal }.thenBy { it.key }
    )
    private val authors = HashMap<String, Boolean>()
    private val texts = HashMap<String, Boolean>()
    private val domains = HashMap<String, Boolean>()

    init {
        for (v in votes) when (v.type) {
            SpamVote.Type.AUTHOR -> authors[v.key] = v.spam
            SpamVote.Type.TEXT -> texts[v.key] = v.spam
            SpamVote.Type.DOMAIN -> domains[v.key] = v.spam
        }
    }

    val isEmpty: Boolean get() = authors.isEmpty() && texts.isEmpty() && domains.isEmpty()

    /** True when a post's fingerprint could change the answer, so it is worth computing. */
    val hasFingerprintVotes: Boolean get() = texts.isNotEmpty() || domains.isNotEmpty()

    fun authorVote(pubkey: String): ReplyGate.OwnVote? = authors[pubkey]?.toOwnVote()

    /**
     * The user's say on a post by [pubkey] with [fingerprint] (null when not
     * computed yet). A vote on the author beats a vote on what the post looks
     * like; among fingerprint votes "Not spam" beats "Spam".
     */
    fun voteFor(pubkey: String, fingerprint: PostFingerprint?): ReplyGate.OwnVote? {
        authors[pubkey]?.let { return it.toOwnVote() }
        if (fingerprint == null || !hasFingerprintVotes) return null
        var spam = false
        fingerprint.textHash?.let { h ->
            when (texts[h]) {
                false -> return ReplyGate.OwnVote.NOT_SPAM
                true -> spam = true
                null -> {}
            }
        }
        for (d in fingerprint.domains) {
            when (domains[d]) {
                false -> return ReplyGate.OwnVote.NOT_SPAM
                true -> spam = true
                null -> {}
            }
        }
        return if (spam) ReplyGate.OwnVote.SPAM else null
    }

    private fun Boolean.toOwnVote() = if (this) ReplyGate.OwnVote.SPAM else ReplyGate.OwnVote.NOT_SPAM

    companion object {
        val EMPTY = SpamVotesSnapshot(emptyList())

        /**
         * Rows a "Spam" vote on one post writes: the author, the text shape,
         * and every uncommon link domain.
         */
        fun spamRows(pubkey: String, fingerprint: PostFingerprint, authorLabel: String?, postLabel: String?, now: Long): List<SpamVote> {
            val rows = ArrayList<SpamVote>()
            rows.add(SpamVote(SpamVote.Type.AUTHOR, pubkey, true, authorLabel, null, now))
            fingerprint.textHash?.let { rows.add(SpamVote(SpamVote.Type.TEXT, it, true, postLabel, null, now)) }
            for (d in fingerprint.domains) rows.add(SpamVote(SpamVote.Type.DOMAIN, d, true, d, null, now))
            return rows
        }
    }
}

/**
 * The user's own spam votes for the current account: SQLite for keeping,
 * [snapshot] for reading, [version] to hear about changes.
 */
class SpamVotesRepository(
    private val socialGraphDb: SocialGraphDb,
    private val safetyPrefs: SafetyPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var generation = 0

    @Volatile var snapshot: SpamVotesSnapshot = SpamVotesSnapshot.EMPTY
        private set

    private val _version = MutableStateFlow(0)
    /** Bumped whenever the votes are loaded or changed. */
    val version: StateFlow<Int> = _version

    private val _votes = MutableStateFlow<List<SpamVote>>(emptyList())
    /** Newest first, for the review list. */
    val votes: StateFlow<List<SpamVote>> = _votes

    // Fingerprints by post id, filled off the main thread and read anywhere.
    private val fingerprints = LruCache<String, PostFingerprint>(4000)

    init {
        loadAsync()
    }

    /** Call after the account (and so the database file and prefs) changed. */
    fun reload() {
        generation++
        publish(SpamVotesSnapshot.EMPTY)
        fingerprints.evictAll()
        loadAsync()
    }

    private fun loadAsync() {
        val gen = ++generation
        scope.launch {
            try {
                // One time: the old "Not spam" list becomes author votes.
                val legacy = safetyPrefs.takeLegacySpamSafelist()
                if (legacy.isNotEmpty()) {
                    val existing = socialGraphDb.getSpamVotes()
                        .filter { it.type == SpamVote.Type.AUTHOR }.map { it.key }.toSet()
                    socialGraphDb.putSpamVotes(
                        legacy.filter { it !in existing }.map { SpamVote(SpamVote.Type.AUTHOR, it, false) }
                    )
                    safetyPrefs.clearLegacySpamSafelist()
                }
                val loaded = SpamVotesSnapshot(socialGraphDb.getSpamVotes())
                if (gen == generation) publish(loaded)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load spam votes", e)
            }
        }
    }

    private fun publish(s: SpamVotesSnapshot) {
        snapshot = s
        _votes.value = s.all
        _version.value = _version.value + 1
    }

    /** Fingerprint of [event] if it was computed already. Never computes. */
    fun cachedFingerprint(eventId: String): PostFingerprint? = fingerprints.get(eventId)

    /** Computes and caches the fingerprint. Regex work: call off the main thread. */
    fun fingerprint(event: NostrEvent): PostFingerprint {
        fingerprints.get(event.id)?.let { return it }
        val fp = PostFingerprint.of(event.content)
        fingerprints.put(event.id, fp)
        return fp
    }

    /** The user's say on [event], from memory only. */
    fun voteFor(event: NostrEvent): ReplyGate.OwnVote? {
        val s = snapshot
        return s.voteFor(event.pubkey, if (s.hasFingerprintVotes) fingerprints.get(event.id) else null)
    }

    /** "Spam" on one post: the author, its text shape and its link domains. */
    suspend fun voteSpam(event: NostrEvent, authorLabel: String?) = withContext(Dispatchers.IO) {
        val fp = fingerprint(event)
        val rows = SpamVotesSnapshot.spamRows(event.pubkey, fp, authorLabel, snippet(event.content), nowSec())
        socialGraphDb.putSpamVotes(rows)
        publish(SpamVotesSnapshot(socialGraphDb.getSpamVotes()))
    }

    /**
     * "Not spam" on one post: the author is trusted from now on, and earlier
     * "Spam" votes on what this post looks like are taken back.
     * Returns the ids of published reports that should be withdrawn.
     */
    suspend fun voteNotSpam(event: NostrEvent, authorLabel: String?): List<String> = withContext(Dispatchers.IO) {
        val fp = fingerprint(event)
        val current = socialGraphDb.getSpamVotes()
        val withdrawn = current
            .filter { it.type == SpamVote.Type.AUTHOR && it.key == event.pubkey && it.spam }
            .mapNotNull { it.reportId }
        val stale = current.filter { v ->
            v.spam && ((v.type == SpamVote.Type.TEXT && v.key == fp.textHash) ||
                (v.type == SpamVote.Type.DOMAIN && v.key in fp.domains))
        }
        socialGraphDb.deleteSpamVotes(stale.map { it.type to it.key })
        socialGraphDb.putSpamVotes(listOf(SpamVote(SpamVote.Type.AUTHOR, event.pubkey, false, authorLabel, null, nowSec())))
        publish(SpamVotesSnapshot(socialGraphDb.getSpamVotes()))
        withdrawn
    }

    /** Remembers the report that was published for a "Spam" vote on [pubkey]. */
    suspend fun setReportId(pubkey: String, reportId: String) = withContext(Dispatchers.IO) {
        val row = socialGraphDb.getSpamVotes()
            .firstOrNull { it.type == SpamVote.Type.AUTHOR && it.key == pubkey && it.spam } ?: return@withContext
        socialGraphDb.putSpamVotes(listOf(row.copy(reportId = reportId)))
        publish(SpamVotesSnapshot(socialGraphDb.getSpamVotes()))
    }

    /**
     * Takes one vote back. Undoing a vote on a person also undoes what the
     * same tap recorded about the post (its text and links), so the post
     * comes back in one step; those can also be undone on their own.
     * Returns the vote as it was stored (with its report id), or null.
     */
    suspend fun undo(type: SpamVote.Type, key: String): SpamVote? = withContext(Dispatchers.IO) {
        val all = socialGraphDb.getSpamVotes()
        val row = all.firstOrNull { it.type == type && it.key == key }
        socialGraphDb.deleteSpamVotes(undoKeys(all, type, key))
        publish(SpamVotesSnapshot(socialGraphDb.getSpamVotes()))
        row
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "SpamVotes"

        /** Which rows an undo of ([type], [key]) removes. Pure, for tests. */
        fun undoKeys(all: List<SpamVote>, type: SpamVote.Type, key: String): List<Pair<SpamVote.Type, String>> {
            val row = all.firstOrNull { it.type == type && it.key == key } ?: return listOf(type to key)
            if (type != SpamVote.Type.AUTHOR) return listOf(type to key)
            return all.filter {
                it === row || (it.type != SpamVote.Type.AUTHOR && it.spam == row.spam && it.createdAt == row.createdAt)
            }.map { it.type to it.key }
        }

        fun snippet(content: String): String {
            val oneLine = content.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length <= 80) oneLine else oneLine.take(80).trimEnd() + "…"
        }
    }
}

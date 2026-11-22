package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.EmojiSet
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.InterestRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * People sets (30000), bookmark sets (30003), interest sets (30015), emoji sets (30030) and your emoji list (10030).
 * Every write goes through [ListWriter]: one entry (or the few the user changed) on your newest version, nothing else
 * touched. Buttons flip at once through the writer's pending overlay, which the repositories lay over what they show.
 * "Delete" hides a set on this device ([ListWriter.hideSet]); nothing is published for it, never a deletion request.
 * Sets made in another app change only when the user edits that set here (explicit).
 */
class ListCrudManager(
    private val relayPool: RelayPool,
    private val subManager: SubscriptionManager,
    private val eventRepo: EventRepository,
    private val listRepo: ListRepository,
    private val interestRepo: InterestRepository,
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val metadataFetcher: MetadataFetcher,
    private val outboxRouter: OutboxRouter?,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext,
    private val listWriter: ListWriter,
    private val getOwnListStore: () -> OwnListStore?,
    private val getUserPubkey: () -> String?
) {
    // -- shared --

    /**
     * Create a set named [name] (its d is [slug] of it). A set with that d that is hidden here comes back instead;
     * any other one with that d is refused (the writer shows the toast). [then] runs once the set is there.
     */
    private fun createSet(kind: Int, name: String, isPrivate: Boolean, then: suspend (d: String) -> Unit = {}) {
        val title = name.trim()
        if (title.isEmpty()) return
        val d = slug(title)
        scope.launch {
            when (listWriter.createSet(kind, d, title, private = isPrivate)) {
                is ListResult.Sent, ListResult.Unchanged, ListResult.Pending -> then(d)
                else -> Unit // the writer already showed why
            }
        }
    }

    // -- Follow Set (kind 30000) --

    fun createList(name: String, isPrivate: Boolean = false) = createSet(Nip51.KIND_FOLLOW_SET, name, isPrivate)

    fun addToList(dTag: String, pubkey: String) = setListMember(dTag, pubkey, wanted = true)

    fun removeFromList(dTag: String, pubkey: String) = setListMember(dTag, pubkey, wanted = false)

    /** Make [pubkey] a member of your people set [dTag] or not; the user picked that set by name. */
    fun setListMember(dTag: String, pubkey: String, wanted: Boolean) {
        if (getUserPubkey() == null) return
        val edit = if (wanted) ListEdit.add("p", pubkey) else ListEdit.remove("p", pubkey)
        // Public or private is read from the newest version when it is written, never from what this device cached.
        listWriter.submitEdit(Nip51.KIND_FOLLOW_SET, listOf(edit), d = dTag, place = ListPlace.AS_SET, expected = true, explicit = true)
    }

    /** Hide the people set here (no deletion request). */
    fun deleteList(dTag: String) {
        listWriter.hideSet(Nip51.KIND_FOLLOW_SET, dTag)
        getUserPubkey()?.let { listRepo.deselect(it, dTag) }
    }

    fun fetchUserLists(pubkey: String) {
        val subId = "user-lists-${pubkey.take(8)}"
        val filter = Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(pubkey), limit = 50)
        relayPool.sendToAll(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
        }
    }

    // -- Bookmark Set (kind 30003) --

    fun createBookmarkSet(name: String, isPrivate: Boolean = false) = createSet(Nip51.KIND_BOOKMARK_SET, name, isPrivate)

    fun addNoteToBookmarkSet(dTag: String, eventId: String) = setNoteInBookmarkSet(dTag, eventId, wanted = true)

    fun removeNoteFromBookmarkSet(dTag: String, eventId: String) = setNoteInBookmarkSet(dTag, eventId, wanted = false)

    /** Put a note in your bookmark set [dTag] or take it out (a new one carries a relay hint when one is known). */
    fun setNoteInBookmarkSet(dTag: String, eventId: String, wanted: Boolean) {
        if (getUserPubkey() == null) return
        val edit = if (wanted) {
            val hint = eventRepo.getRelayHintsForEvents(setOf(eventId))[eventId]
            ListEdit(if (hint.isNullOrBlank()) listOf("e", eventId) else listOf("e", eventId, hint), ListEdit.Op.ADD)
        } else ListEdit.remove("e", eventId)
        // Public or private is read from the newest version when it is written, never from what this device cached.
        listWriter.submitEdit(Nip51.KIND_BOOKMARK_SET, listOf(edit), d = dTag, place = ListPlace.AS_SET, expected = true, explicit = true)
    }

    /** Hide the bookmark set here (no deletion request). */
    fun deleteBookmarkSet(dTag: String) = listWriter.hideSet(Nip51.KIND_BOOKMARK_SET, dTag)

    fun fetchBookmarkSetEvents(dTag: String) {
        val myPubkey = getUserPubkey() ?: return
        val set = bookmarkSetRepo.getSet(myPubkey, dTag) ?: return
        val ids = set.eventIds.toList()
        if (ids.isEmpty()) return
        val missing = ids.filter { eventRepo.getEvent(it) == null }
        if (missing.isEmpty()) return
        val subId = "fetch-bkset-${dTag.take(8)}"
        val filter = Filter(ids = missing)
        relayPool.sendToTopRelays(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            eventRepo.bumpEventCacheVersion()
            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    // -- Interest Set (kind 30015) --

    fun followHashtag(hashtag: String, dTag: String) = setHashtags(listOf(hashtag), dTag, wanted = true)

    fun unfollowHashtag(hashtag: String, dTag: String) = setHashtags(listOf(hashtag), dTag, wanted = false)

    /** Follow or unfollow [hashtags] in your interest set [dTag] (picked by the user) in one write. */
    fun setHashtags(hashtags: Collection<String>, dTag: String, wanted: Boolean) {
        val lower = hashtags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (lower.isEmpty()) return
        val latest = getOwnListStore()?.latest(Nip51.KIND_INTEREST_SET, dTag)
        val edits = hashtagEdits(latest?.tags.orEmpty(), lower, wanted)
        if (edits.isEmpty()) return
        listWriter.submitEdit(Nip51.KIND_INTEREST_SET, edits, d = dTag, expected = true, explicit = true)
    }

    /**
     * Follow [hashtags] in the set [dTag] the app picks by itself (the default "Interests" set, onboarding topics).
     * Written as one event; the set is started with [setTitle] only when you have none with that d anywhere. A set
     * hidden here with that d is shown again first, so the follow is visible.
     */
    fun followHashtags(hashtags: Set<String>, dTag: String = DEFAULT_INTEREST_D, setTitle: String? = DEFAULT_INTEREST_TITLE) {
        val lower = hashtags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (lower.isEmpty()) return
        if (listWriter.isHidden(Nip51.KIND_INTEREST_SET, dTag)) listWriter.unhideSet(Nip51.KIND_INTEREST_SET, dTag)
        val latest = getOwnListStore()?.latest(Nip51.KIND_INTEREST_SET, dTag)
        val edits = hashtagEdits(latest?.tags.orEmpty(), lower, wanted = true)
        if (edits.isEmpty()) return
        listWriter.submitEdit(
            Nip51.KIND_INTEREST_SET, edits, d = dTag,
            title = setTitle ?: interestRepo.getSet(dTag)?.name?.takeIf { it != dTag }
        )
    }

    fun createInterestSet(name: String) = createSet(Nip51.KIND_INTEREST_SET, name, isPrivate = false)

    /** Rename: only the title changes, the d stays. */
    fun renameInterestSet(dTag: String, newName: String) {
        val title = newName.trim()
        if (title.isEmpty()) return
        listWriter.submitEdit(Nip51.KIND_INTEREST_SET, listOf(ListEdit.set("title", title)), d = dTag, expected = true, explicit = true)
    }

    /** Hide the interest set here (no deletion request). */
    fun deleteInterestSet(dTag: String) = listWriter.hideSet(Nip51.KIND_INTEREST_SET, dTag)

    // -- Custom Emoji: sets (30030) and your emoji list (10030) --

    fun createEmojiSet(name: String, emojis: List<CustomEmoji>) =
        createSet(Nip30.KIND_EMOJI_SET, name, isPrivate = false) { d ->
            val adds = emojis.filter { it.shortcode.isNotBlank() && it.url.isNotBlank() }
                .distinctBy { it.shortcode }
                .map { ListEdit.add("emoji", it.shortcode, it.url) }
            if (adds.isNotEmpty()) listWriter.edit(Nip30.KIND_EMOJI_SET, adds, d = d, expected = true, explicit = true)
        }

    /** Write what the edit dialog changed: [opened] is the set as it opened, [title]/[emojis] how it closed. */
    fun updateEmojiSet(opened: EmojiSet, title: String, emojis: List<CustomEmoji>) {
        val edits = emojiSetEdits(opened.title, opened.emojis, title, emojis)
        if (edits.isEmpty()) return
        listWriter.submitEdit(Nip30.KIND_EMOJI_SET, edits, d = opened.dTag, expected = true, explicit = true)
    }

    /** Hide the emoji set here (no deletion request). */
    fun deleteEmojiSet(dTag: String) = listWriter.hideSet(Nip30.KIND_EMOJI_SET, dTag)

    /** Add an image emoji to your emoji list; a shortcode already there gets the new image in its place. */
    fun addDirectEmoji(emoji: CustomEmoji) {
        val code = emoji.shortcode.trim()
        val url = emoji.url.trim()
        if (code.isEmpty() || url.isEmpty()) return
        listWriter.submitEdit(Nip30.KIND_USER_EMOJI_LIST, listOf(ListEdit.set("emoji", code, url)), expected = emojiListShown())
    }

    /** Take image emojis listed directly out of your emoji list; the sets it uses (`a` entries) are never touched. */
    fun removeDirectEmojis(emojis: List<CustomEmoji>) {
        val edits = emojis.map { it.shortcode }.distinct().map { ListEdit.remove("emoji", it) }
        if (edits.isEmpty()) return
        listWriter.submitEdit(Nip30.KIND_USER_EMOJI_LIST, edits, expected = emojiListShown())
    }

    fun addSetToEmojiList(pubkey: String, dTag: String) = setEmojiSetUsed(pubkey, dTag, wanted = true)

    fun removeSetFromEmojiList(pubkey: String, dTag: String) = setEmojiSetUsed(pubkey, dTag, wanted = false)

    /** Use an emoji set (anyone's) in your emoji list or stop using it. */
    fun setEmojiSetUsed(pubkey: String, dTag: String, wanted: Boolean) {
        val ref = Nip30.buildSetReference(pubkey, dTag)
        val edit = if (wanted) ListEdit.add("a", ref) else ListEdit.remove("a", ref)
        listWriter.submitEdit(Nip30.KIND_USER_EMOJI_LIST, listOf(edit), expected = emojiListShown())
    }

    /** The app shows an emoji list of yours (loaded from a version), so none found must not start a new one. */
    private fun emojiListShown(): Boolean = (customEmojiRepo.userEmojiList.value?.createdAt ?: 0L) > 0L

    fun fetchEmojiSets() {
        val refs = customEmojiRepo.getSetReferences()
        if (refs.isEmpty()) return
        val parsed = refs.mapNotNull { ref ->
            Nip30.parseSetReference(ref)?.let { (_, pubkey, dTag) -> pubkey to dTag }
        }
        if (parsed.isEmpty()) return
        // Send per-author via outbox routing to hit each author's write relays,
        // with a fallback to read+top relays for authors without known relay lists.
        val subIds = mutableListOf<String>()
        for ((pubkey, dTag) in parsed) {
            val filter = Filter(kinds = listOf(Nip30.KIND_EMOJI_SET), authors = listOf(pubkey), dTags = listOf(dTag), limit = 1)
            val subId = "emoji-set-${pubkey.take(8)}"
            subIds.add(subId)
            val targeted = outboxRouter?.subscribeToUserWriteRelays(subId, pubkey, filter)
            if (targeted == null || targeted.isEmpty()) {
                val msg = ClientMessage.req(subId, filter)
                relayPool.sendToReadRelays(msg)
                relayPool.sendToTopRelays(msg, maxRelays = 5)
            }
        }
        // Wait for results then clean up — don't close on first EOSE since that
        // may be a relay with 0 results while slower relays have the data.
        scope.launch {
            delay(4_000)
            for (subId in subIds) subManager.closeSubscription(subId)
        }
    }

    // -- one-time move of what this device knew before the shared writer --

    /**
     * Once per account: record as made here every set of yours this device already shows that looks like this app's
     * ([migratedMadeHere]: only from a version the store holds, never from what the old code cached), and turn the
     * old deletion marks of your sets into hidden sets (nothing is published).
     * People and bookmark sets this device still shows stay shown; interest and emoji sets marked deleted are hidden
     * (they were not shown, or only by mistake).
     */
    fun migrateSets() {
        val store = getOwnListStore() ?: return
        val me = getUserPubkey() ?: return
        if (store.pubkeyHex != me.lowercase()) return
        if (!store.firstTime(MIGRATION_SETS)) return
        fun mark(kind: Int, d: String) {
            val key = OwnListStore.key(kind, d)
            if (store.madeHere(key)) return
            val private = migratedMadeHere(kind, d, store.latest(key)) ?: return
            store.markMadeHere(key, private)
        }
        val people = listRepo.getAllListsForUser(me)
        val bookmarks = bookmarkSetRepo.getAllSetsForUser(me)
        for (fs in people) mark(Nip51.KIND_FOLLOW_SET, fs.dTag)
        for (bs in bookmarks) mark(Nip51.KIND_BOOKMARK_SET, bs.dTag)
        for (set in interestRepo.sets.value) interestRepo.getLoadedSet(set.dTag)?.let { mark(Nip51.KIND_INTEREST_SET, it.dTag) }
        for (set in customEmojiRepo.ownSets.value) mark(Nip30.KIND_EMOJI_SET, set.dTag)

        val shownPeople = people.map { it.dTag }.toSet()
        val shownBookmarks = bookmarks.map { it.dTag }.toSet()
        for ((kind, d) in deletedSetsToHide(eventRepo.deletedEventsRepo?.deletedAddresses().orEmpty(), me, shownPeople, shownBookmarks)) {
            store.hideSet(kind, d)
        }
        listWriter.refreshOverlay()
    }

    companion object {
        const val DEFAULT_INTEREST_D = "interests"
        const val DEFAULT_INTEREST_TITLE = "Interests"
        private const val MIGRATION_SETS = "sets-made-here-v1"

        /** The set kinds this manager writes. */
        val SET_KINDS: Set<Int> = setOf(Nip51.KIND_FOLLOW_SET, Nip51.KIND_BOOKMARK_SET, Nip51.KIND_INTEREST_SET, Nip30.KIND_EMOJI_SET)

        /** Kinds whose pending changes the repositories lay over what they show: the sets and the emoji list. */
        val OVERLAY_KINDS: Set<Int> = SET_KINDS + Nip30.KIND_USER_EMOJI_LIST

        /** Entry tag names of each set kind (a public one of these means the set keeps its entries in public). */
        val ENTRY_NAMES: Map<Int, Set<String>> = mapOf(
            Nip51.KIND_FOLLOW_SET to setOf("p"),
            Nip51.KIND_BOOKMARK_SET to setOf("e", "a", "t"),
            Nip51.KIND_INTEREST_SET to setOf("t"),
            Nip30.KIND_EMOJI_SET to setOf("emoji")
        )

        private val SLUG = Regex("^[a-z0-9_-]+$")

        /** The d this app gives a set named [name]. */
        fun slug(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9-_]"), "-")

        /**
         * Where a new entry of a set goes, from your newest version first: public entries mean public; a private
         * part (non-blank content) means private. Then the made-here record (private or public), then an empty set
         * with a public title is public. Without a version: the record, else private only when the set shows as
         * private. (Writes from the set screens use [ListPlace.AS_SET], which the writer decides the same way on the
         * version it changes.)
         */
        fun placeOf(
            madeHere: Boolean,
            madeHerePrivate: Boolean,
            latest: NostrEvent?,
            loadedPrivate: Boolean?,
            entryNames: Set<String>
        ): ListPlace {
            val recorded = if (madeHerePrivate) ListPlace.PRIVATE else ListPlace.PUBLIC
            if (latest != null) {
                val publicEntries = latest.tags.any { it.size >= 2 && it[0] in entryNames }
                return when {
                    publicEntries -> ListPlace.PUBLIC
                    latest.content.isNotBlank() -> ListPlace.PRIVATE
                    madeHere -> recorded
                    latest.tags.any { it.size >= 2 && (it[0] == "title" || it[0] == "name") } -> ListPlace.PUBLIC
                    loadedPrivate == true -> ListPlace.PRIVATE
                    else -> ListPlace.PUBLIC
                }
            }
            if (madeHere) return recorded
            return if (loadedPrivate == true) ListPlace.PRIVATE else ListPlace.PUBLIC
        }

        /**
         * What the one-time move records for your set [kind]:[d]: null (record nothing) unless the store holds a
         * version of it that looks like this app's own (a slug d and only tags this app writes); else whether it is
         * private, read from that version. Never from what the old code cached (that could not always decrypt).
         */
        fun migratedMadeHere(kind: Int, d: String, latest: NostrEvent?): Boolean? {
            if (latest == null || latest.kind != kind || !SLUG.matches(d)) return null
            if (!ListWriter.looksMadeHere(null, latest)) return null
            return placeOf(false, false, latest, null, ENTRY_NAMES[kind].orEmpty()) == ListPlace.PRIVATE
        }

        /**
         * Hashtag changes for one set: adds only for tags not there in any spelling; removes every spelling found
         * (a set from another app may hold `Bitcoin`), plus the lowercase one. [tags]: your newest version's tags.
         */
        fun hashtagEdits(tags: List<List<String>>, lower: List<String>, wanted: Boolean): List<ListEdit> {
            val spelled = tags.filter { it.size >= 2 && it[0] == "t" }.groupBy({ it[1].lowercase() }, { it[1] })
            return if (wanted) {
                lower.filter { spelled[it].isNullOrEmpty() }.map { ListEdit.add("t", it) }
            } else {
                lower.flatMap { l -> (spelled[l].orEmpty() + l).distinct().map { ListEdit.remove("t", it) } }
            }
        }

        /**
         * What the emoji set dialog changed: removed shortcodes, added ones, a new image for a shortcode kept, and the
         * title. The order of emojis is not a change (it cannot be written without rewriting the set).
         */
        fun emojiSetEdits(openedTitle: String, opened: List<CustomEmoji>, title: String, emojis: List<CustomEmoji>): List<ListEdit> {
            val before = opened.associateBy { it.shortcode }
            val after = LinkedHashMap<String, CustomEmoji>()
            for (e in emojis) if (e.shortcode.isNotBlank() && e.url.isNotBlank()) after.putIfAbsent(e.shortcode, e)
            val edits = mutableListOf<ListEdit>()
            for (code in before.keys) if (code !in after) edits += ListEdit.remove("emoji", code)
            for ((code, e) in after) {
                val was = before[code]
                when {
                    was == null -> edits += ListEdit.add("emoji", code, e.url)
                    was.url != e.url -> edits += ListEdit.set("emoji", code, e.url)
                }
            }
            val t = title.trim()
            if (t.isNotEmpty() && t != openedTitle) edits += ListEdit.set("title", t)
            return edits
        }

        /**
         * Your sets the old deletion marks ([addresses], "kind:pubkey:d") should hide now: interest and emoji sets
         * always; people and bookmark sets only when this device no longer shows them.
         */
        fun deletedSetsToHide(addresses: Set<String>, me: String, shownPeople: Set<String>, shownBookmarks: Set<String>): List<Pair<Int, String>> =
            addresses.mapNotNull { coord ->
                val parts = coord.split(":", limit = 3)
                if (parts.size != 3 || !parts[1].equals(me, ignoreCase = true)) return@mapNotNull null
                val kind = parts[0].toIntOrNull() ?: return@mapNotNull null
                val d = parts[2]
                when (kind) {
                    Nip51.KIND_INTEREST_SET -> kind to d
                    // Emoji sets are left to the dated path (your deletion requests, read at each start): a set
                    // deleted once and made again under the same name is newer than its request and stays shown.
                    Nip51.KIND_FOLLOW_SET -> if (d in shownPeople) null else kind to d
                    Nip51.KIND_BOOKMARK_SET -> if (d in shownBookmarks) null else kind to d
                    else -> null
                }
            }.distinct()
    }
}

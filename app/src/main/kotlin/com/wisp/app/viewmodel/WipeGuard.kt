package com.wisp.app.viewmodel

import android.util.Log
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.KeptCopy
import com.wisp.app.repo.ListDrop
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * One of your lists lost entries in another app: [missing] of them, noticed [noticedAt] (seconds).
 * [title]: a set's name (its title, else its d); null for the other lists.
 */
data class WipeNotice(val kind: Int, val d: String?, val title: String?, val missing: Int, val noticedAt: Long) {
    val key: String get() = OwnListStore.key(kind, d)
}

/**
 * The wipe guard. [OwnListStore] keeps the fuller version when a version from a relay may have lost entries; this
 * reads both private parts, decides whether it is a drop (ListDrop), and offers what is missing: always the kept
 * copy's entries that the newest version has in neither part, less those taken out here on purpose. Put back goes
 * through [ListWriter.putBack] (only adds); Leave it forgets the kept copy. Nothing else is ever published.
 */
class WipeGuard(
    private val account: () -> ListAccount?,
    private val writer: ListWriter,
    /** Where [requestRefresh] runs: the account scope. */
    private val scope: CoroutineScope,
    private val work: CoroutineContext = Dispatchers.Default
) {
    private val _notices = MutableStateFlow<List<WipeNotice>>(emptyList())
    /** Open notices, oldest first. Show one at a time. */
    val notices: StateFlow<List<WipeNotice>> = _notices

    private val _busy = MutableStateFlow<String?>(null)
    /** The key of the list being put back right now, or null. */
    val busy: StateFlow<String?> = _busy

    private val lock = Mutex()
    private val again = AtomicBoolean(false)
    @Volatile private var running: Job? = null

    /** Decrypted private parts by event id (only successful reads). */
    private val privCache = ConcurrentHashMap<String, List<List<String>>>()

    /** Check the kept copies again soon (a list version was loaded, the signer arrived, the account changed). */
    fun requestRefresh() {
        again.set(true)
        if (running?.isActive == true) return
        running = scope.launch {
            while (again.getAndSet(false)) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "wipe guard check failed", e)
                }
            }
        }
    }

    /** Signed out or switching: show nothing until the next check. */
    fun clear() {
        _notices.value = emptyList()
        privCache.clear()
    }

    /** Decide every kept copy that can be read now, forget what is no longer missing, and publish the notices. */
    suspend fun refresh(): Unit = withContext(work) {
        lock.withLock {
            val acct = account()
            if (acct == null) {
                _notices.value = emptyList()
                return@withLock
            }
            val store = acct.store
            val out = mutableListOf<WipeNotice>()
            for (key in store.keptKeys()) {
                val kept = store.kept(key) ?: continue
                val check = check(acct, key, kept) ?: continue
                if (!kept.open) {
                    if (ListDrop.isDrop(check.missing, check.keptPriv, check.curPriv)) store.openKept(key)
                    else {
                        store.forgetKept(key)
                        continue
                    }
                }
                if (check.missing.size == 0) {
                    store.forgetKept(key)
                    continue
                }
                out += WipeNotice(check.current.kind, dOf(check.current), titleOf(check), check.missing.size, kept.at)
            }
            if (account()?.pubkeyHex != acct.pubkeyHex) return@withLock
            _notices.value = out.sortedWith(compareBy<WipeNotice> { it.noticedAt }.thenBy { it.key })
        }
    }

    /**
     * Put the missing entries of [notice]'s list back, each into the part it was in. On success nothing is missing
     * any more and the kept copy is forgotten; refused or offline, the notice stays (ListWriter shows why).
     */
    suspend fun putBack(notice: WipeNotice): ListResult {
        val acct = account() ?: return ListResult.Refused(ListResult.Reason.NOT_SENT, "no account")
        val key = notice.key
        _busy.value = key
        try {
            val missing = withContext(work) {
                lock.withLock {
                    val kept = acct.store.kept(key) ?: return@withLock null
                    check(acct, key, kept)?.missing
                }
            }
            if (missing == null) {
                refresh()
                return ListResult.Unchanged
            }
            val r = if (missing.size == 0) ListResult.Unchanged
            else writer.putBack(notice.kind, notice.d, missing.public, missing.private, acct)
            // Checked against the newest version (after a write, the one just signed): the kept copy is forgotten when
            // nothing is missing any more, and stays offered for whatever could not be put back.
            refresh()
            return r
        } finally {
            _busy.value = null
        }
    }

    /** Forget the kept copy of [notice]'s list. Nothing is published. */
    suspend fun leaveIt(notice: WipeNotice) {
        val acct = account() ?: return
        acct.store.forgetKept(notice.key)
        refresh()
    }

    // ---------------------------------------------------------------- internals

    private class Check(
        val current: NostrEvent,
        val keptPriv: List<List<String>>,
        val curPriv: List<List<String>>,
        val missing: ListDrop.Missing
    )

    /** Kept copy against the newest version, or null when that cannot be told yet (no version, a part unreadable). */
    private suspend fun check(acct: ListAccount, key: String, kept: KeptCopy): Check? {
        val old = try { NostrEvent.fromJson(kept.event) } catch (_: Exception) { null } ?: return null
        val cur = acct.store.latest(key) ?: return null
        if (cur.kind != old.kind) return null
        val keptPriv = privateOf(acct, old) ?: return null
        val curPriv = privateOf(acct, cur) ?: return null
        val missing = ListDrop.missing(old.tags, keptPriv, cur.tags, curPriv, kept.excluded.toSet())
        return Check(cur, keptPriv, curPriv, missing)
    }

    /** The decrypted private part (empty when there is none), or null when it cannot be read now. */
    private suspend fun privateOf(acct: ListAccount, e: NostrEvent): List<List<String>>? {
        if (!ListDrop.hasPrivatePart(e.kind) || e.content.isBlank()) return emptyList()
        privCache[e.id]?.let { return it }
        val me = acct.pubkeyHex
        val plain = try {
            if (e.content.contains("?iv=")) acct.signer.nip04Decrypt(e.content, me) else acct.signer.nip44Decrypt(e.content, me)
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            null
        } ?: return null
        val tags = ListWriter.parsePrivate(plain) ?: return null
        privCache[e.id] = tags
        return tags
    }

    private fun dOf(e: NostrEvent): String? =
        if (OwnListStore.isAddressable(e.kind)) e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "" else null

    private fun titleOf(c: Check): String? {
        val d = dOf(c.current) ?: return null
        fun title(tags: List<List<String>>) = tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)?.takeIf { it.isNotBlank() }
        return title(c.current.tags) ?: title(c.curPriv) ?: d
    }

    companion object {
        private const val TAG = "WipeGuard"
    }
}

package com.wisp.app.viewmodel

import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ListWrite
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig
import com.wisp.app.repo.OwnListStore
import com.wisp.app.repo.PendingChange

/**
 * Editors that change many rows and then save (the relay editor, the media server list) never replace a whole list:
 * what they save is the set of per-entry changes between what the editor started from and what the user ended with,
 * which ListWriter applies to the newest version found. Pure functions, so they can be tested on the JVM.
 */
object RelayListEdits {

    /** NIP-65 marker for a relay used for [read] and/or [write] (both, or neither: no marker). */
    fun marker(read: Boolean, write: Boolean): String? = when {
        read && write -> null
        read -> "read"
        write -> "write"
        else -> null
    }

    /** The `r` tag the relay list holds for [c]. */
    fun rTag(c: RelayConfig): List<String> = listOfNotNull("r", c.url, marker(c.read, c.write))

    /**
     * Changes to your relay list (10002) between [start] and [end]: a relay taken out is removed (every copy), a new
     * relay or one whose read/write use changed is set in place (or appended), with its marker. Relays neither list
     * shows (hidden by the app's address filter) are never touched.
     */
    fun relayList(start: List<RelayConfig>, end: List<RelayConfig>): List<ListEdit> {
        val before = start.associateBy { ListWriter.norm(it.url) }
        val after = end.associateBy { ListWriter.norm(it.url) }
        val out = mutableListOf<ListEdit>()
        for ((k, c) in before) if (k !in after) out += ListEdit.remove("r", c.url)
        for ((k, c) in after) {
            val b = before[k]
            if (b == null || marker(b.read, b.write) != marker(c.read, c.write)) out += ListEdit(rTag(c), ListEdit.Op.SET)
        }
        return out
    }

    /**
     * Changes to a list of addresses ([name] tags: `relay` for DM, search, blocked and favourite relays and relay
     * sets, `server` for media servers) between [start] and [end], compared in one spelling. A removed address is
     * taken out under every tag name [startTags] holds it with (some apps write `r` instead of `relay`).
     */
    fun addressList(name: String, start: List<String>, end: List<String>, startTags: List<List<String>> = emptyList()): List<ListEdit> {
        val before = start.associateBy { ListWriter.norm(it) }
        val after = end.associateBy { ListWriter.norm(it) }
        val out = mutableListOf<ListEdit>()
        for ((k, url) in before) {
            if (k in after) continue
            val names = startTags.filter { it.size >= 2 && ListWriter.norm(it[1]) == k && (it[0] == name || it[0] == "r") }
                .map { it[0] }.distinct()
            if (names.isEmpty()) out += ListEdit.remove(name, url)
            else for (n in names) out += ListEdit.remove(n, url)
        }
        for ((k, url) in after) if (k !in before) out += ListEdit.add(name, url)
        return out
    }

    /**
     * Changes to your media server list (10063) between [startTags] (your newest version, pending changes laid over)
     * and [end] (the servers the screen shows). Both sides are read the way the screen reads them
     * ([Blossom.parseServerList]), so a server entry the app cannot read (another app's spelling, no https) is in
     * neither and is never touched. A server taken out goes out in the spelling your list holds it in.
     */
    fun serverList(startTags: List<List<String>>, end: List<String>): List<ListEdit> {
        val start = serversOf(startTags)
        return addressList("server", start, end).flatMap { e ->
            if (e.op != ListEdit.Op.REMOVE) return@flatMap listOf(e)
            val k = ListWriter.norm(e.entry[1])
            val held = startTags.filter { it.size >= 2 && it[0] == "server" && serversOf(listOf(it)).any { s -> ListWriter.norm(s) == k } }
                .map { it[1] }.distinct()
            if (held.isEmpty()) listOf(e) else held.map { ListEdit.remove("server", it) }
        }
    }

    /** The servers the media server screen shows for these tags. */
    fun serversOf(tags: List<List<String>>): List<String> = Blossom.parseServerList(event(Blossom.KIND_SERVER_LIST, tags))

    /** The lists edited on a settings screen whose rows are the app's own copy (relays, DM, search, blocked, media). */
    val EDITOR_KINDS: Set<Int> = setOf(10002, 10050, 10006, 10007, Blossom.KIND_SERVER_LIST)

    /**
     * The editor's rows for [kind] were loaded from your newest version this device knows (or none is known), so
     * the difference between that version and the rows is what the user changed. False when the rows come from
     * something else (an older version, the app's defaults): the editor must load the newest version first.
     */
    fun inStep(store: OwnListStore, kind: Int): Boolean {
        val latest = store.latest(kind) ?: return true
        return store.projected(OwnListStore.key(kind)) == latest.id
    }

    /** [tags] with the [pending] changes laid over them, in order (what the list will be once they are written). */
    fun withPending(tags: List<List<String>>, pending: List<PendingChange>): List<List<String>> =
        pending.fold(tags) { t, p ->
            ListWrite.apply(t, when {
                p.replace -> ListWrite.Change(set = p.entry)
                p.wanted -> ListWrite.Change(add = p.entry)
                else -> ListWrite.Change(remove = p.entry)
            })
        }

    /** [event] with your pending changes to its list laid over its tags (for the projection: a kept change stays shown). */
    fun overlaid(event: NostrEvent, store: OwnListStore?): NostrEvent {
        val key = OwnListStore.keyOrNull(event) ?: return event
        val pending = store?.pending(key).orEmpty()
        return if (pending.isEmpty()) event else event.copy(tags = withPending(event.tags, pending))
    }

    /**
     * The tags of what the editor for [kind] starts from: your newest version this device knows, with your pending
     * changes to it laid over. Empty when none is known.
     */
    fun startTags(store: OwnListStore, kind: Int): List<List<String>> =
        withPending(store.latest(kind)?.tags.orEmpty(), store.pending(kind))

    /** The relays the editor shows for relay list tags (the same reading the app uses when it loads your list). */
    fun relaysOf(tags: List<List<String>>): List<RelayConfig> = Nip65.parseRelayList(event(10002, tags))

    /** The addresses the editor shows for a DM, search, blocked or favourite relay list, or a relay set. */
    fun addressesOf(kind: Int, tags: List<List<String>>): List<String> = Nip51.parseRelaySet(event(kind, tags))

    private fun event(kind: Int, tags: List<List<String>>) = NostrEvent("", "", 0, kind, tags, "", "")
}

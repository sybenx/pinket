package com.wisp.app

import com.wisp.app.repo.OwnListStore
import com.wisp.app.viewmodel.SocialActionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ListWriter is the only place allowed to sign a replaceable list or a set: your profile (0), follows (3), every kind
 * from 10000 to 19999 and from 30000 to 39999, except the few that are not lists and that the app signs elsewhere
 * ([SIGN_ALLOWED]: your status, drafts). No deletion request (kind 5) anywhere may name a kind from 30000 to 39999
 * other than the posts in SocialActionManager.DELETABLE_ADDRESSABLE: sets are hidden on the device, never deleted.
 *
 * This scans app/src/main/kotlin for `signEvent(...)`, `NostrEvent.create(...)` and `NostrEvent.createUnsigned(...)`
 * calls, with the kind written as a number or as a constant whose value is known (named or positional argument), and
 * for the deletion tag builders of Nip09. It fails on
 * - a list kind signed outside viewmodel/ListWriter.kt;
 * - a kind it cannot work out (a variable or parameter), since that could be a list;
 * - a kind 5 whose tags name a set kind, or whose tags are anything but exactly one Nip09 builder call (written in
 *   the call, or a `val` on one line that is never changed afterwards), or whose kind it cannot work out;
 * - a deletion tag builder called with a set kind, or with a kind it cannot work out.
 *
 * Only what the scanner cannot work out may be excused: a comment on the site's line or up to [WINDOW] lines above
 * it, `// list-write-exception: <why>`, with a real reason ([MIN_REASON] characters at least). One comment covers one
 * line: the first line with a site at or below it. A list kind or a set named in a deletion request is never excused.
 * A comment that excuses nothing, or gives no reason, fails too.
 */
class NoDirectListSignTest {

    @Test
    fun noListKindIsSignedOutsideListWriter() {
        val files = mainSources()
        assertTrue("no sources found", files.size > 50)
        val audit = audit(files)
        assertEquals(setOf(10000), audit.consts["KIND_MUTE_LIST"])
        assertEquals(
            "List kinds must be signed only by ListWriter:\n" + audit.signs.joinToString("\n"),
            emptyList<String>(), audit.signs
        )
    }

    @Test
    fun noDeletionRequestNamesASet() {
        val audit = audit(mainSources())
        assertEquals(
            "Sets are hidden here, never deleted:\n" + audit.deletions.joinToString("\n"),
            emptyList<String>(), audit.deletions
        )
    }

    @Test
    fun everyExceptionSaysWhyAndIsNeeded() {
        val audit = audit(mainSources())
        assertEquals(
            "Exceptions must give a reason and excuse a site:\n" + audit.badMarkers.joinToString("\n"),
            emptyList<String>(), audit.badMarkers
        )
    }

    @Test
    fun theScanCoversEveryListTheAppStoresAndTheDeleteGuardRefusesThem() {
        for (k in OwnListStore.STORED_KINDS) {
            assertTrue("$k is a list kind for the scan", isListKind(k))
            assertFalse("deleteEvent must refuse $k", SocialActionManager.mayDelete(k))
        }
        for (k in listOf(10004, 10010, 10015, 30001, 30004, 30005, 30063, 39089, 39092)) {
            assertTrue(isListKind(k))
            assertFalse("deleteEvent must refuse $k", SocialActionManager.mayDelete(k))
        }
        for (k in SocialActionManager.DELETABLE_ADDRESSABLE) {
            assertTrue(k in ADDRESSABLE)
            assertTrue(SocialActionManager.mayDelete(k))
            assertFalse(k in OwnListStore.STORED_KINDS)
        }
        for (k in listOf(1, 6, 7, 1068, 1111, 20, 21, 22, 9802)) assertTrue(SocialActionManager.mayDelete(k))
        assertFalse(SocialActionManager.mayDelete(0))
        assertFalse(SocialActionManager.mayDelete(3))
        assertTrue(SIGN_ALLOWED.none { it in OwnListStore.STORED_KINDS })
    }

    @Test
    fun anyReplaceableOrAddressableKindIsAList() {
        val sample = """
            val a = s.signEvent(kind = 10015, content = "", tags = t)
            val b = s.signEvent(kind = 30001, content = "", tags = t)
            val c = s.signEvent(kind = 39089, content = "", tags = t)
            val d = s.signEvent(kind = 30023, content = "", tags = t)
            val e = s.signEvent(kind = 30315, content = "", tags = t)
            val f = s.signEvent(kind = Nip37.KIND_DRAFT, content = "", tags = t)
            val g = s.signEvent(kind = 1, content = "", tags = t)
            val h = s.signEvent(kind = 20242, content = "", tags = t)
            const val KIND_DRAFT = 31234
        """.trimIndent()
        assertEquals(listOf(1, 2, 3, 4), audit(mapOf("x.kt" to sample)).signs.map { lineOf(it) })
    }

    @Test
    fun theScannerSeesTheForms() {
        val sample = """
            const val KIND_FOLLOWS = 3
            const val KIND_MUTE_LIST = 10000
            const val KIND_PIN_LIST = 10001
            const val KIND_DM_RELAYS = 10050
            override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>): NostrEvent
            val a = s.signEvent(kind = 3, content = "", tags = tags)
            val b = signer.signEvent(
                kind = Nip51.KIND_MUTE_LIST,
                content = encrypted,
                tags = emptyList()
            )
            val c = s.signEvent(com.wisp.app.nostr.Nip51.KIND_PIN_LIST, "", tags)
            val d = NostrEvent.create(privkey = k, pubkey = p, kind = 10003, content = "", tags = t)
            val e = s.signEvent(kind = 30315, content = "", tags = tags)
            val f = s.signEvent(kind = Nip51.KIND_DM_RELAYS, content = "", tags = tags)
            val g = s.signEvent(kind = 31, content = "", tags = tags)
            val h = signer.signEvent(kind = KIND_FOLLOWS, content = "", tags = tags)
            val i = NostrEvent.create(k, p, 10003, "", t)
            val j = s.signEvent(kind = (10000), content = "", tags = t)
            val l = s.signEvent(kind = 10_001, content = "", tags = t)
            val m = NostrEvent.create(k, p, 1, "", listOf(listOf("p", x)))
            val n = NostrEvent.createUnsigned(pk, 10002, "", t)
            val o = s.signEvent(kind = someKind, content = "", tags = t)
        """.trimIndent()
        val consts = constants(listOf(sample))
        val sites = signSites(sample)
        assertEquals(
            listOf("3", "Nip51.KIND_MUTE_LIST", "com.wisp.app.nostr.Nip51.KIND_PIN_LIST", "10003", "30315", "Nip51.KIND_DM_RELAYS", "31",
                "KIND_FOLLOWS", "10003", "(10000)", "10_001", "1", "10002", "someKind"),
            sites.map { it.kind }
        )
        assertEquals(
            listOf(true, true, true, true, false, true, false, true, true, true, true, false, true, null),
            sites.map { s -> values(s.kind, consts)?.let { v -> v.any { isListKind(it) } } }
        )
        assertEquals(listOf(6, 7, 12, 13, 15, 17, 18, 19, 20, 22, 23), audit(mapOf("x.kt" to sample)).signs.map { lineOf(it) })

        val profileSample = """
            const val KIND = 30078
            val p = s.signEvent(kind = 0, content = content)
            val q = signer.signEvent(kind = KIND, content = encrypted, tags = tags)
            val r = s.signEvent(Nip78.KIND, encrypted, tags)
        """.trimIndent()
        assertEquals(listOf(2, 3, 4), audit(mapOf("x.kt" to profileSample)).signs.map { lineOf(it) })
        // ListWriter itself may sign them.
        assertEquals(emptyList<String>(), audit(mapOf(LIST_WRITER to profileSample)).signs)

        val excused = """
            // list-write-exception: replyKind is a reply or a comment, never a list.
            val a = signer.signEvent(kind = replyKind, content = "", tags = t)
            // list-write-exception: too short
            val b = signer.signEvent(kind = replyKind, content = "", tags = t)
            // list-write-exception: this comment sits above nothing that needs it.
            val c = signer.signEvent(kind = 1, content = "", tags = t)
            // list-write-exception: a list kind can never be excused by a comment.
            val d = signer.signEvent(kind = 10003, content = "", tags = t)
            // list-write-exception: covers only the very next line with a sign call.
            val e = signer.signEvent(kind = otherKind, content = "", tags = t)
            val f = signer.signEvent(kind = otherKind, content = "", tags = t)
            // list-write-exception: a deletion naming a set is never excused here.
            val g = signer.signEvent(kind = 5, content = "", tags = Nip09.buildAddressableDeletionTags(30000, pk, d))
            // list-write-exception: one comment, one line: the sign and its builder on it.
            val h = signer.signEvent(kind = 5, content = "", tags = Nip09.buildDeletionTags(id, kind))
        """.trimIndent()
        val ex = audit(mapOf("x.kt" to excused))
        assertEquals(listOf(4, 8, 11), ex.signs.map { lineOf(it) })
        assertEquals(listOf(13, 13), ex.deletions.map { lineOf(it) })
        assertEquals(listOf(3, 5, 7, 12), ex.badMarkers.map { lineOf(it) })

        val deletions = """
            const val KIND_DRAFT = 31234
            const val KIND_SET = 30003
            val t1 = Nip09.buildDeletionTags(id, 7)
            val ok = s.signEvent(kind = 5, content = "", tags = t1)
            val ok2 = s.signEvent(kind = 5, content = "", tags = Nip09.buildDeletionTags(ids, 1059))
            val bad = s.signEvent(kind = 5, content = "", tags = Nip09.buildAddressableDeletionTags(30000, pk, d))
            val t2 = Nip09.buildAddressableDeletionTags(KIND_SET, pk, d)
            val bad2 = signer.signEvent(5, "", t2)
            val t3 = Nip09.buildDeletionTags(id, kind)
            val bad3 = s.signEvent(kind = 5, content = "", tags = t3)
            val bad4 = s.signEvent(kind = 5, content = "", tags = listOf(listOf("a", coord)))
            val tDraft = Nip09.buildAddressableDeletionTags(Nip37.KIND_DRAFT, pk, d)
            val okDraft = signer.signEvent(kind = 5, content = "", tags = tDraft)
            val mix1 = s.signEvent(kind = 5, content = "", tags = Nip09.buildDeletionTags(id, 1) + listOf(listOf("a", "30000:pk:d")))
            val t4 = Nip09.buildDeletionTags(id, 1).toMutableList()
            val mix2 = s.signEvent(kind = 5, content = "", tags = t4)
            val t5 = Nip09.buildDeletionTags(id, 1)
            t5 += listOf("a", coord)
            val mix3 = s.signEvent(kind = 5, content = "", tags = t5)
            val t6 = Nip09.buildDeletionTags(id,
                1) + extra
            val mix4 = s.signEvent(kind = 5, content = "", tags = t6)
            fun other() {
                val t7 = Nip09.buildDeletionTags(id, 1)
            }
            fun deletes() {
                val badElsewhere = s.signEvent(kind = 5, content = "", tags = t7)
            }
            fun buildAddressableDeletionTags(kind: Int, pubkey: String, dTag: String): List<List<String>> = emptyList()
        """.trimIndent()
        val del = audit(mapOf("x.kt" to deletions))
        assertEquals(emptyList<String>(), del.signs)
        assertEquals(listOf(6, 6, 7, 8, 9, 10, 11, 14, 16, 19, 22, 27), del.deletions.map { lineOf(it) })
        // The same in ListWriter: no file may name a set in a deletion request.
        assertEquals(12, audit(mapOf(LIST_WRITER to deletions)).deletions.size)
    }

    // ---- the scan ----

    private class Site(val line: Int, val start: Int, val kind: String, val tags: String?)

    private class Audit(val consts: Map<String, Set<Int>>, val signs: List<String>, val deletions: List<String>, val badMarkers: List<String>)

    /** Every finding in [files] (path to text), each one "path:line what". */
    private fun audit(files: Map<String, String>): Audit {
        val consts = constants(files.values)
        val signs = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val badMarkers = mutableListOf<String>()
        for ((rel, text) in files) {
            val lines = text.lines()
            val markers = markers(lines)
            val sites = signSites(text)
            val builders = builderKinds(text)
            val siteLines = (sites.map { it.line } + builders.map { it.first }).toSortedSet()
            // One comment covers one line: the first line holding a site at or below it (at most WINDOW lines down).
            val target = markers.keys.associateWith { m -> siteLines.firstOrNull { it in m..(m + WINDOW) } }
            val used = HashSet<Int>()
            /**
             * True when a good marker covers [line] (and marks it used). Only for what this check cannot work out:
             * a list kind or a set in a deletion request is reported without asking.
             */
            fun excused(line: Int): Boolean {
                val m = markers.keys.firstOrNull { target[it] == line && markers[it]!!.length >= MIN_REASON } ?: return false
                used += m
                return true
            }
            for (site in sites) {
                val v = values(site.kind, consts)
                if (rel != LIST_WRITER) {
                    when {
                        v == null -> if (!excused(site.line)) signs += "$rel:${site.line} signs a kind this check cannot work out (${site.kind})"
                        v.any { isListKind(it) } -> signs += "$rel:${site.line} signs list kind ${site.kind}"
                    }
                }
                if (v != null && 5 in v) {
                    val named = deletedKinds(text, site)
                    val namedValues = named?.map { values(it, consts) }
                    when {
                        named == null || namedValues == null ->
                            if (!excused(site.line)) deletions += "$rel:${site.line} sends a deletion request whose tags this check cannot work out"
                        namedValues.any { it == null } ->
                            if (!excused(site.line)) deletions += "$rel:${site.line} sends a deletion request naming a kind this check cannot work out (${named.joinToString()})"
                        namedValues.any { vs -> vs!!.any { namesASet(it) } } ->
                            deletions += "$rel:${site.line} sends a deletion request naming kind ${named.joinToString()}"
                    }
                }
            }
            for ((line, kind) in builders) {
                val v = values(kind, consts)
                when {
                    v == null -> if (!excused(line)) deletions += "$rel:$line builds deletion tags for a kind this check cannot work out ($kind)"
                    v.any { namesASet(it) } -> deletions += "$rel:$line builds deletion tags for kind $kind"
                }
            }
            for ((line, reason) in markers.toSortedMap()) {
                when {
                    reason.length < MIN_REASON -> badMarkers += "$rel:$line gives no real reason"
                    line !in used -> badMarkers += "$rel:$line excuses nothing"
                }
            }
        }
        return Audit(consts, signs.sortedWith(BY_PLACE), deletions.sortedWith(BY_PLACE), badMarkers.sortedWith(BY_PLACE))
    }

    /** Line number (1-based) to reason, for every `// list-write-exception:` comment. */
    private fun markers(lines: List<String>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for ((i, l) in lines.withIndex()) MARKER.find(l)?.let { out[i + 1] = it.groupValues[1].trim() }
        return out
    }

    private fun lineOf(finding: String): Int = finding.substringAfter(':').substringBefore(' ').toInt()

    /** Every `const val NAME = <number>` in [texts]: name to the numbers it stands for (names repeat across objects). */
    private fun constants(texts: Collection<String>): Map<String, Set<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        for (t in texts) for (m in CONST.findAll(t)) {
            val v = m.groupValues[2].replace("_", "").toIntOrNull() ?: continue
            out.getOrPut(m.groupValues[1]) { HashSet() }.add(v)
        }
        return out
    }

    /** The numbers a kind expression stands for, or null when it is not a number or a known constant. */
    private fun values(kind: String, consts: Map<String, Set<Int>>): Set<Int>? {
        val k = normalize(kind)
        return k.toIntOrNull()?.let { setOf(it) } ?: consts[k]
    }

    /** Every sign call in [text], with its kind and tags expressions. */
    private fun signSites(text: String): List<Site> {
        val out = mutableListOf<Site>()
        for (m in CALL.findAll(text)) {
            // A declaration (`fun signEvent(kind: Int, ...)`) is not a call.
            if (isDeclaration(text, m.range.first)) continue
            val args = argsOf(text, m.range.last) ?: continue
            val parts = topLevelArgs(args)
            if (parts.isEmpty()) continue
            // signEvent(kind, content, tags); NostrEvent.create(privkey, pubkey, kind, content, tags);
            // NostrEvent.createUnsigned(pubkey, kind, content, tags).
            val (kindAt, tagsAt) = when {
                m.value.startsWith("signEvent") -> 0 to 2
                m.value.contains("createUnsigned") -> 1 to 3
                else -> 2 to 4
            }
            val kind = argument(parts, "kind", kindAt) ?: continue
            val tags = argument(parts, "tags", tagsAt)
            out += Site(lineAt(text, m.range.first), m.range.first, kind, tags)
        }
        return out
    }

    /** The argument named [name], or the positional one at [at] (unless that one is named). */
    private fun argument(parts: List<String>, name: String, at: Int): String? {
        val named = parts.firstOrNull { Regex("""^\s*$name\s*=(?!=)""").containsMatchIn(it) }
        val raw = named?.substringAfter('=') ?: parts.getOrNull(at)?.takeUnless { NAMED_ARG.containsMatchIn(it) }
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * The kind expression a kind 5 at [site] names: its tags argument must be exactly one Nip09 builder call, written
     * there or as a `val` on one line in the same function that is never changed after it. Null otherwise.
     */
    private fun deletedKinds(text: String, site: Site): List<String>? {
        val tags = site.tags ?: return null
        val expr = if (IDENT.matches(tags)) declaredOnce(text, site, tags) ?: return null else tags
        return singleBuilderKind(expr)?.let { listOf(it) }
    }

    /** The right side of `val [name] = ...` (one line) in the function around [site], when nothing changes it after. */
    private fun declaredOnce(text: String, site: Site, name: String): String? {
        val before = text.substring(0, site.start)
        val fnStart = Regex("""\bfun\s""").findAll(before).lastOrNull()?.range?.first ?: 0
        val body = before.substring(fnStart)
        val n = Regex.escape(name)
        if (Regex("""\bvar\s+$n\b""").containsMatchIn(body)) return null
        val decl = Regex("""\bval\s+$n\s*(?::[^=\n]+)?=(?!=)([^\n]*)""").findAll(body).lastOrNull() ?: return null
        val after = body.substring(decl.range.last + 1)
        if (Regex("""\b$n\s*(?:\+=|-=|=(?!=)|\.(?:add|addAll|plus|set|remove|clear)\b|\[)""").containsMatchIn(after)) return null
        return decl.groupValues[1].trim()
    }

    /** The kind argument when [expr] is exactly one Nip09 builder call and nothing else. */
    private fun singleBuilderKind(expr: String): String? {
        val e = expr.trim()
        val m = BUILDER_ONLY.find(e) ?: return null
        if (closingParen(e, m.range.last) != e.length - 1) return null
        return builderKind(e, m)
    }

    /** The index of the parenthesis that closes the one at [open], or null. */
    private fun closingParen(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return null
    }

    /** (line, kind expression) for every call of a Nip09 deletion tag builder in [text]. */
    private fun builderKinds(text: String): List<Pair<Int, String>> =
        BUILDER.findAll(text).filterNot { isDeclaration(text, it.range.first) }
            .mapNotNull { m -> builderKind(text, m)?.let { lineAt(text, m.range.first) to it } }.toList()

    /** buildDeletionTags(ids, kind); buildAddressableDeletionTags(kind, pubkey, d). */
    private fun builderKind(text: String, m: MatchResult): String? {
        val parts = topLevelArgs(argsOf(text, m.range.last) ?: return null)
        return argument(parts, "kind", if (m.value.contains("buildAddressable")) 0 else 1)
    }

    private fun isDeclaration(text: String, at: Int) = text.substring(maxOf(0, at - 4), at) == "fun "

    private fun lineAt(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    /** The text between the parenthesis at [open] and its match. */
    private fun argsOf(text: String, open: Int): String? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return null
    }

    /** The arguments, split on commas outside brackets. */
    private fun topLevelArgs(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var from = 0
        for ((i, c) in args.withIndex()) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { out += args.substring(from, i).trim(); from = i + 1 }
            }
        }
        val last = args.substring(from).trim()
        if (last.isNotEmpty()) out += last
        return out
    }

    /** `(10000)` is 10000, `10_001` is 10001, `Nip51.KIND_MUTE_LIST` is KIND_MUTE_LIST. */
    private fun normalize(kind: String): String {
        val k = kind.trim().trim('(', ')').trim()
        return if (k.matches(Regex("[0-9_]+"))) k.replace("_", "") else k.substringAfterLast('.')
    }

    /** Every .kt file under app/src/main/kotlin, keyed by its path below that folder. */
    private fun mainSources(): Map<String, String> {
        val candidates = listOf("src/main/kotlin", "app/src/main/kotlin").map { File(it) }
        val root = (candidates.firstOrNull { it.isDirectory } ?: error("main sources not found from ${File(".").absolutePath}")).absoluteFile
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    private companion object {
        const val LIST_WRITER = "com/wisp/app/viewmodel/ListWriter.kt"
        /** How many lines above a site its exception comment may sit. */
        const val WINDOW = 2
        const val MIN_REASON = 20
        val CALL = Regex("""\b(?:signEvent|NostrEvent\.create|NostrEvent\.createUnsigned)\s*\(""")
        val BUILDER = Regex("""\b(?:buildDeletionTags|buildAddressableDeletionTags)\s*\(""")
        val NAMED_ARG = Regex("""^\s*\w+\s*=(?!=)""")
        val IDENT = Regex("""[A-Za-z_]\w*""")
        val MARKER = Regex("""//\s*list-write-exception:(.*)$""")
        val CONST = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*Int\s*)?=\s*([0-9_]+)\b""")
        val BY_PLACE = compareBy<String>({ it.substringBefore(':') }, { it.substringAfter(':').substringBefore(' ').toIntOrNull() ?: 0 })
        /** `Nip09.buildX(...)` at the very start of an expression (any qualifier). */
        val BUILDER_ONLY = Regex("""^(?:[\w.]*\.)?(?:buildDeletionTags|buildAddressableDeletionTags)\s*\(""")
        /**
         * Replaceable and addressable kinds that are not lists, signed outside ListWriter: your status (30315) and
         * drafts (31234). Anything else in 0, 3, 10000-19999 and 30000-39999 is a list here.
         */
        // 10133 (payment addresses) is written by the profile editor's own one-entry code, not by ListWriter yet.
        val SIGN_ALLOWED = setOf(30315, 31234, 10133)
        /** Addressable kinds: sets live here, so a deletion request may name only the posts among them. */
        val ADDRESSABLE = 30000..39999

        fun isListKind(k: Int): Boolean =
            (k == 0 || k == 3 || k in 10000..19999 || k in ADDRESSABLE) && k !in SIGN_ALLOWED

        /** A kind a deletion request may never name: any addressable kind but the posts the app lets you take back. */
        fun namesASet(k: Int): Boolean = k in ADDRESSABLE && k !in SocialActionManager.DELETABLE_ADDRESSABLE
    }
}

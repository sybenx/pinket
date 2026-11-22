package com.wisp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ListWriter is the only place allowed to sign follows (3), mutes (10000), pins (10001) and bookmarks (10003), and the
 * automatic DM relay list (10050) in StartupCoordinator goes through it too. This scans app/src/main/kotlin and fails
 * on any `signEvent(...)` or `NostrEvent.create(...)` call for those kinds outside viewmodel/ListWriter.kt, written as
 * a number or as any constant whose value is one of them (named or positional kind argument).
 */
class NoDirectListSignTest {

    @Test
    fun noListKindIsSignedOutsideListWriter() {
        val root = sourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no sources found under $root", files.size > 50)
        val texts = files.associateWith { it.readText() }
        // A constant name stands for its number wherever it is declared (KIND_FOLLOWS = 3, KIND_MUTE_LIST, ...).
        val consts = constants(texts.values)
        assertEquals(setOf(10000), consts["KIND_MUTE_LIST"])
        val found = mutableListOf<String>()
        for ((f, text) in texts) {
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel == LIST_WRITER) continue
            for ((line, kind) in signedKinds(text)) {
                if (isListKind(rel, kind, consts)) found += "$rel:$line signs kind $kind"
            }
        }
        assertEquals("List kinds must be signed only by ListWriter:\n" + found.joinToString("\n"), emptyList<String>(), found)
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
            val e = s.signEvent(kind = 30023, content = "", tags = tags)
            val f = s.signEvent(kind = Nip51.KIND_DM_RELAYS, content = "", tags = tags)
            val g = s.signEvent(kind = 31, content = "", tags = tags)
            val h = signer.signEvent(kind = KIND_FOLLOWS, content = "", tags = tags)
            val i = NostrEvent.create(k, p, 10003, "", t)
            val j = s.signEvent(kind = (10000), content = "", tags = t)
            val l = s.signEvent(kind = 10_001, content = "", tags = t)
            val m = NostrEvent.create(k, p, 1, "", listOf(listOf("p", x)))
        """.trimIndent()
        val consts = constants(listOf(sample))
        val kinds = signedKinds(sample).map { it.second }
        assertEquals(
            listOf("3", "Nip51.KIND_MUTE_LIST", "com.wisp.app.nostr.Nip51.KIND_PIN_LIST", "10003", "30023", "Nip51.KIND_DM_RELAYS", "31",
                "KIND_FOLLOWS", "10003", "(10000)", "10_001", "1"),
            kinds
        )
        assertEquals(
            listOf(true, true, true, true, false, false, false, true, true, true, true, false),
            kinds.map { isListKind("x.kt", it, consts) }
        )
        assertTrue(isListKind(STARTUP, "Nip51.KIND_DM_RELAYS", consts))
        assertTrue(isListKind(STARTUP, "10050", consts))
    }

    private fun isListKind(rel: String, kind: String, consts: Map<String, Set<Int>>): Boolean {
        val k = normalize(kind)
        val values = k.toIntOrNull()?.let { setOf(it) } ?: consts[k].orEmpty()
        return values.any { it in LIST_KINDS } || (rel == STARTUP && values.any { it in DM_RELAY_KINDS })
    }

    /** Every `const val NAME = <number>` in [texts]: name to the numbers it stands for (names repeat across objects). */
    private fun constants(texts: Collection<String>): Map<String, Set<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        for (t in texts) for (m in CONST.findAll(t)) {
            val v = m.groupValues[2].replace("_", "").toIntOrNull() ?: continue
            out.getOrPut(m.groupValues[1]) { HashSet() }.add(v)
        }
        return out
    }

    /** (line, kind expression) for every sign call in [text]. */
    private fun signedKinds(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (m in CALL.findAll(text)) {
            // A declaration (`fun signEvent(kind: Int, ...)`) is not a call.
            if (text.substring(maxOf(0, m.range.first - 4), m.range.first) == "fun ") continue
            val open = m.range.last
            val args = argsOf(text, open) ?: continue
            val parts = topLevelArgs(args)
            if (parts.isEmpty()) continue
            val isCreate = !m.value.startsWith("signEvent")
            val named = parts.firstOrNull { NAMED_ARG_KIND.containsMatchIn(it) }?.substringAfter('=')
            // signEvent(kind, content, tags); NostrEvent.create(privkey, pubkey, kind, content, tags).
            val kind = (named ?: parts.getOrNull(if (isCreate) 2 else 0)?.takeUnless { '=' in it })?.trim()
            if (kind.isNullOrBlank()) continue
            out += text.substring(0, m.range.first).count { it == '\n' } + 1 to kind
        }
        return out
    }

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

    private fun sourceRoot(): File {
        val candidates = listOf("src/main/kotlin", "app/src/main/kotlin").map { File(it) }
        val base = candidates.firstOrNull { it.isDirectory } ?: error("main sources not found from ${File(".").absolutePath}")
        return base.absoluteFile
    }

    private companion object {
        const val LIST_WRITER = "com/wisp/app/viewmodel/ListWriter.kt"
        const val STARTUP = "com/wisp/app/viewmodel/StartupCoordinator.kt"
        val CALL = Regex("""\b(?:signEvent|NostrEvent\.create)\s*\(""")
        val NAMED_ARG_KIND = Regex("""^\s*kind\s*=""")
        val CONST = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*Int\s*)?=\s*([0-9_]+)\b""")
        val LIST_KINDS = setOf(3, 10000, 10001, 10003)
        val DM_RELAY_KINDS = setOf(10050)
    }
}

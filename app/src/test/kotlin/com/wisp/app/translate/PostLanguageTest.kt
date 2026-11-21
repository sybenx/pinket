package com.wisp.app.translate

import com.wisp.app.translate.PostLanguage.Guess
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLanguageTest {
    @Test fun tagFirst() {
        assertEquals("es", PostLanguage.tagged(listOf(listOf("L", "ISO-639-1"), listOf("l", "es", "ISO-639-1"))))
        assertEquals("pt", PostLanguage.tagged(listOf(listOf("l", "PT", "ISO-639-1"))))
        assertEquals("he", PostLanguage.tagged(listOf(listOf("l", "iw", "ISO-639-1"))))
        assertNull(PostLanguage.tagged(listOf(listOf("l", "spam", "ugc"))))
        assertNull(PostLanguage.tagged(listOf(listOf("l", "es", "other"))))
    }

    @Test fun normalizesOldCodes() {
        assertEquals("he", PostLanguage.normalize("iw"))
        assertEquals("id", PostLanguage.normalize("in"))
        assertEquals("yi", PostLanguage.normalize("ji"))
        assertEquals("pt", PostLanguage.normalize("pt-BR"))
        assertEquals("zh", PostLanguage.normalize("zh_TW"))
    }

    @Test fun cleansEverythingButWords() {
        val t = "Hoy probé #nostr con @fiatjaf 🚀🔥 :pepe: mira https://example.com/x?y=1 y nostr:npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
        assertEquals("Hoy probé con mira y", PostLanguage.clean(t))
        assertEquals("ver", PostLanguage.clean("ver note1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"))
        assertFalse(PostLanguage.guessable(PostLanguage.clean("gm 🌅 #nostr https://x.com")))
        assertTrue(PostLanguage.guessable("Hoy probé el nuevo cliente"))
    }

    @Test fun guessesOnlyWhenClearlyAhead() {
        assertEquals("es", PostLanguage.decide(listOf("es" to 0.92f, "pt" to 0.05f)).lang)
        val unsure = PostLanguage.decide(listOf("es" to 0.5f, "pt" to 0.4f, "gl" to 0.1f, "und" to 0.9f))
        assertNull(unsure.lang); assertEquals(listOf("es", "pt", "gl"), unsure.likeliest)
        assertNull(PostLanguage.decide(listOf("und" to 1f)).lang)
        // High but not far enough ahead.
        assertNull(PostLanguage.decide(listOf("es" to 0.65f, "pt" to 0.45f)).lang)
        assertEquals("he", PostLanguage.decide(listOf("iw" to 0.9f)).lang)
    }

    @Test fun protectsAndRestores() {
        val t = "Mira https://example.com/a #nostr @jo :pepe: y nostr:note1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
        val (p, kept) = PostLanguage.protect(t)
        assertEquals("Mira ⟦0⟧ ⟦1⟧ ⟦2⟧ ⟦3⟧ y ⟦4⟧", p)
        // A translator that spaced one out and dropped another.
        val back = PostLanguage.restore("Look ⟦ 0 ⟧ ⟦1⟧ ⟦2⟧ ⟦3⟧ and", kept)
        assertEquals("Look https://example.com/a #nostr @jo :pepe: and nostr:note1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m", back)
    }

    @Test fun translatesLineByLineKeepingBreaks() = runTest {
        val seen = ArrayList<String>()
        val out = PostLanguage.translateProtected("Hola #nostr\n\nmira\nhttps://x.com/a") { line -> seen += line; line.uppercase() }
        assertEquals("HOLA #nostr\n\nMIRA\nhttps://x.com/a", out)
        // Blank lines and lines that are only a link are not sent.
        assertEquals(listOf("Hola ⟦0⟧", "mira"), seen)
        assertNull(PostLanguage.translateProtected("uno\ndos") { if (it == "dos") null else it })
    }

    private val supported = { c: String -> c in setOf("es", "pt", "gl", "en", "fr", "de") }

    @Test fun planForSureGuess() {
        val reads = setOf("en")
        assertEquals(TranslationPlan.Auto("es"), TranslationPlan.decide(Guess.known("es"), reads, true, supported) { true })
        assertEquals(TranslationPlan.Hint("es"), TranslationPlan.decide(Guess.known("es"), reads, true, supported) { false })
        // Auto-translate off: a hint even when the model is there.
        assertEquals(TranslationPlan.Hint("es"), TranslationPlan.decide(Guess.known("es"), reads, false, supported) { true })
        assertEquals(TranslationPlan.Nothing, TranslationPlan.decide(Guess.known("en"), reads, true, supported) { true })
        assertEquals(TranslationPlan.Nothing, TranslationPlan.decide(Guess.known("xx"), reads, true, supported) { true })
    }

    @Test fun planForUnsureGuess() {
        assertEquals(TranslationPlan.Pick(listOf("es", "pt")), TranslationPlan.decide(Guess(null, listOf("es", "pt", "xx")), setOf("en"), true, supported) { true })
        // One of the likeliest is a language you read: nothing.
        assertEquals(TranslationPlan.Nothing, TranslationPlan.decide(Guess(null, listOf("es", "en")), setOf("en"), true, supported) { true })
        assertEquals(TranslationPlan.Nothing, TranslationPlan.decide(Guess.NONE, setOf("en"), true, supported) { true })
    }

    @Test fun guessOrder() = runTest {
        val detect: suspend (String) -> List<Pair<String, Float>> = { listOf("fr" to 0.9f) }
        assertEquals("es", TranslationPlan.guess("es", "de", "Bonjour tout le monde ici", detect).lang)
        assertEquals("de", TranslationPlan.guess(null, "de", "Bonjour tout le monde ici", detect).lang)
        assertEquals("fr", TranslationPlan.guess(null, null, "Bonjour tout le monde ici", detect).lang)
        // Too short once cleaned: no guess, and the detector isn't asked.
        var asked = false
        val g = TranslationPlan.guess(null, null, "gm #nostr https://x.com 🌅") { asked = true; emptyList() }
        assertNull(g.lang); assertTrue(g.likeliest.isEmpty()); assertFalse(asked)
    }

    @Test fun authorMemoryRoundTrips() {
        val a = AuthorLanguages()
        a.set("pk1", "es"); a.set("pk2", "iw")
        val b = AuthorLanguages(a.toJson())
        assertEquals("es", b.get("pk1")); assertEquals("he", b.get("pk2")); assertNull(b.get("pk3"))
        assertNull(AuthorLanguages("not json").get("pk1"))
    }

    @Test fun cacheKeepsTheLast() {
        val c = TranslationCache(max = 3)
        for (i in 1..5) c.put("id$i", TranslationCache.Entry("t$i", "es"))
        assertEquals(3, c.size()); assertNull(c.get("id1")); assertEquals("t5", c.get("id5")!!.text)
        val d = TranslationCache(max = 3).apply { load(c.toJson()) }
        assertEquals(TranslationCache.Entry("t4", "es"), d.get("id4"))
    }

    @Test fun serverAddress() {
        assertEquals(ServerAddress.Result.Ok("https://lt.example.com"), ServerAddress.parse(" https://lt.example.com/ "))
        assertEquals(ServerAddress.Result.Ok("https://lt.example.com:5000"), ServerAddress.parse("lt.example.com:5000"))
        assertEquals(ServerAddress.Result.PlainHttp, ServerAddress.parse("http://lt.example.com"))
        assertEquals(ServerAddress.Result.Empty, ServerAddress.parse("  "))
    }
}

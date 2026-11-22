package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileContentTest {
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun edits(
        name: String = "Alice", about: String = "hi", picture: String = "",
        nip05: String = "", banner: String = "", lud16: String = ""
    ) = ProfileContent.editorEdits(name, about, picture, nip05, banner, lud16)

    @Test fun unknownFieldsSurvive() {
        val base = """{"name":"old","website":"https://a.example","bot":false,"pronouns":"she/her","nested":{"x":1}}"""
        val out = parse(ProfileContent.merge(base, edits()))
        assertEquals("https://a.example", out["website"]!!.jsonPrimitive.content)
        assertEquals("false", out["bot"]!!.jsonPrimitive.content)
        assertEquals("she/her", out["pronouns"]!!.jsonPrimitive.content)
        assertEquals(parse("""{"x":1}"""), out["nested"])
        assertEquals("Alice", out["name"]!!.jsonPrimitive.content)
        assertEquals("Alice", out["display_name"]!!.jsonPrimitive.content)
        assertEquals("hi", out["about"]!!.jsonPrimitive.content)
    }

    @Test fun blankedFieldsAreRemoved() {
        val base = """{"name":"a","display_name":"A","about":"x","picture":"p","nip05":"n","banner":"b","lud16":"l","website":"w"}"""
        val out = parse(ProfileContent.merge(base, edits(name = " ", about = "", lud16 = "")))
        for (key in ProfileContent.EDITED_KEYS) assertFalse(key, key in out)
        assertEquals("w", out["website"]!!.jsonPrimitive.content)
    }

    @Test fun noOrBadBaseBehavesLikeOldBuilder() {
        val expected = parse("""{"display_name":"Alice","name":"Alice","about":"hi","lud16":"a@b.c"}""")
        assertEquals(expected, parse(ProfileContent.merge(null, edits(lud16 = "a@b.c"))))
        assertEquals(expected, parse(ProfileContent.merge("not json", edits(lud16 = "a@b.c"))))
        assertEquals(expected, parse(ProfileContent.merge("[1,2]", edits(lud16 = "a@b.c"))))
        assertEquals(expected, parse(ProfileContent.merge("", edits(lud16 = "a@b.c"))))
    }

    @Test fun editedValuesOverwrite() {
        val base = """{"lud16":"old@x.y","about":"old"}"""
        val out = parse(ProfileContent.merge(base, edits(lud16 = "new@x.y", about = "new")))
        assertEquals("new@x.y", out["lud16"]!!.jsonPrimitive.content)
        assertEquals("new", out["about"]!!.jsonPrimitive.content)
        assertTrue("display_name" in out)
    }
}

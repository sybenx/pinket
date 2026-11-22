package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * marble-content-fixtures/fixtures/lists/vectors.json (list-writes.md), copied to resources/lists (see FROM.txt).
 * Same checks as Marble's ListWriteVectorsTest, plus: planMany with the one change gives the same answer as plan.
 * Ids are short stand-ins, so signatures are never verified here.
 */
class ListWriteVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(javaClass.getResource("/lists/vectors.json")!!.readText()).jsonObject
    private val me = v.str("me")!!
    private val now = v["now"]!!.jsonPrimitive.long

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun tags(a: JsonArray?): List<List<String>> = a?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList()
    private fun strs(o: JsonObject, k: String): List<String>? = (o[k] as? JsonArray)?.map { it.jsonPrimitive.content }
    private fun event(o: JsonObject) = NostrEvent(
        id = o.str("id")!!,
        pubkey = o.str("pubkey")!!,
        created_at = o["created_at"]!!.jsonPrimitive.long,
        kind = o["kind"]!!.jsonPrimitive.int,
        tags = tags(o["tags"]!!.jsonArray),
        content = o.str("content") ?: "",
        sig = ""
    )
    private fun ver(o: JsonObject) = ListWrite.Version(o["kind"]!!.jsonPrimitive.int, o["created_at"]!!.jsonPrimitive.long, o.str("content") ?: "", tags(o["tags"]!!.jsonArray))

    private class Case(val found: List<NostrEvent>, val kind: Int, val d: String?, val change: ListWrite.Change, val expected: Boolean, val title: String?)

    private fun case(c: JsonObject): Case {
        val ch = c["change"]!!.jsonObject
        return Case(
            found = c["found"]!!.jsonArray.map { event(it.jsonObject) },
            kind = c["kind"]!!.jsonPrimitive.int,
            d = c.str("d")?.ifEmpty { null },
            change = ListWrite.Change(strs(ch, "add"), strs(ch, "remove")),
            expected = c["expected"]!!.jsonPrimitive.content.toBoolean(),
            title = c.str("title")?.ifEmpty { null }
        )
    }

    // maxAhead = null: the shared vectors are the house rule alone. Pinket's extra refusal of a version dated more
    // than 600 s ahead is on by default and is checked in clockBehindIsRefusedByDefault and ListWriteManyTest.
    private fun plan(c: JsonObject): ListWrite.Plan = case(c).let { x -> ListWrite.plan(x.found, me, x.kind, x.d, x.change, x.expected, now, x.title, maxAhead = null) }
    private fun planMany(c: JsonObject): ListWrite.Plan = case(c).let { x -> ListWrite.planMany(x.found, me, x.kind, x.d, listOf(x.change), x.expected, now, x.title, maxAhead = null) }

    private fun each(k: String, f: (JsonObject) -> Unit) {
        val a = v[k]!!.jsonArray
        assertTrue("$k is empty", a.isNotEmpty())
        a.forEach { f(it.jsonObject) }
    }

    @Test fun allVectorsPresent() {
        assertEquals(11, v["writes"]!!.jsonArray.size)
        assertEquals(3, v["nothing"]!!.jsonArray.size)
        assertEquals(2, v["refuse"]!!.jsonArray.size)
        assertEquals(10, v["guard"]!!.jsonArray.size)
    }

    @Test fun writes() = each("writes") { c ->
        val name = c.str("name")!!
        val p = plan(c) as? ListWrite.Plan.Write ?: error("$name: ${plan(c)}")
        val w = c["write"]!!.jsonObject
        assertEquals(name, tags(w["tags"]!!.jsonArray), p.tags)
        assertEquals(name, w.str("content"), p.content)
        assertEquals(name, w["created_at"]!!.jsonPrimitive.long, p.createdAt)
        // What it makes passes the last check too.
        p.old?.let { o -> assertNull(name, ListWrite.guard(ListWrite.version(o), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags))) }
        // planMany with the one change makes the same list, and guardMany passes it.
        val m = planMany(c)
        assertEquals(name, p, m)
        p.old?.let { o ->
            val ch = case(c).change
            assertNull(name, ListWrite.guardMany(ListWrite.version(o), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags), ListWrite.touched(listOf(ch)), if (ch.remove != null) 1 else 0))
        }
    }

    @Test fun clockBehindIsRefusedByDefault() {
        val c = v["writes"]!!.jsonArray.map { it.jsonObject }.single { it.str("name")!!.startsWith("a clock behind the list") }
        val x = case(c)
        assertEquals(ListWrite.Plan.Refuse("dated ahead"), ListWrite.plan(x.found, me, x.kind, x.d, x.change, x.expected, now, x.title))
        assertEquals(ListWrite.Plan.Refuse("dated ahead"), ListWrite.planMany(x.found, me, x.kind, x.d, listOf(x.change), x.expected, now, x.title))
    }

    @Test fun nothing() = each("nothing") { c ->
        assertEquals(c.str("name"), ListWrite.Plan.Nothing, plan(c))
        assertEquals(c.str("name"), ListWrite.Plan.Nothing, planMany(c))
    }

    @Test fun refuse() = each("refuse") { c ->
        val why = c.str("why")
        val p = plan(c)
        assertTrue("${c.str("name")}: $p", p is ListWrite.Plan.Refuse && p.why == why)
        val m = planMany(c)
        assertTrue("${c.str("name")} (planMany): $m", m is ListWrite.Plan.Refuse && m.why == why)
    }

    @Test fun guard() = each("guard") { c ->
        assertEquals(c.str("name"), c.str("why"), ListWrite.guard(ver(c["old"]!!.jsonObject), ver(c["next"]!!.jsonObject)))
    }
}

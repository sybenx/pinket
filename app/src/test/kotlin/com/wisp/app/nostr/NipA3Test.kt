package com.wisp.app.nostr

import com.wisp.app.util.MoneroTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NipA3Test {
    private val xmr = MoneroTest.STANDARD
    private val xmr2 = MoneroTest.SUBADDRESS

    @Test fun parsesPaytoTagsOnly() {
        val tags = listOf(
            listOf("payto", "bitcoin", "bc1qxyz"),
            listOf("payto", "MONERO", " $xmr "),
            listOf("payto", "nano"),               // too short
            listOf("payto", "", "x"),              // empty type
            listOf("t", "monero"),
            listOf("payto", "unknowntype", "abc", "extra")
        )
        assertEquals(
            listOf(
                NipA3.PayTarget("bitcoin", "bc1qxyz"),
                NipA3.PayTarget("monero", xmr),
                NipA3.PayTarget("unknowntype", "abc")
            ),
            NipA3.parseTargets(tags)
        )
    }

    @Test fun moneroAddressAcceptsXmrAliasAndSkipsInvalid() {
        assertEquals(xmr, NipA3.moneroAddress(listOf(listOf("payto", "xmr", xmr))))
        assertEquals(xmr2, NipA3.moneroAddress(listOf(
            listOf("payto", "monero", "not-an-address"),
            listOf("payto", "monero", xmr2)
        )))
        assertNull(NipA3.moneroAddress(listOf(listOf("payto", "bitcoin", "bc1q"))))
    }

    @Test fun changingMoneroKeepsEverythingElseInOrder() {
        val tags = listOf(
            listOf("payto", "bitcoin", "bc1q"),
            listOf("payto", "monero", xmr),
            listOf("alt", "something"),
            listOf("payto", "nano", "nano_1")
        )
        assertEquals(
            listOf(
                listOf("payto", "bitcoin", "bc1q"),
                listOf("payto", "monero", xmr2),
                listOf("alt", "something"),
                listOf("payto", "nano", "nano_1")
            ),
            NipA3.withMoneroAddress(tags, xmr2)
        )
    }

    @Test fun clearingRemovesOnlyMoneroEntries() {
        val tags = listOf(
            listOf("payto", "monero", xmr),
            listOf("payto", "bitcoin", "bc1q"),
            listOf("payto", "xmr", xmr2)
        )
        assertEquals(listOf(listOf("payto", "bitcoin", "bc1q")), NipA3.withMoneroAddress(tags, null))
    }

    @Test fun changingEditsOnlyTheShownEntry() {
        val tags = listOf(
            listOf("payto", "monero", "not-an-address"),
            listOf("payto", "XMR", xmr2, "extra"),
            listOf("payto", "bitcoin", "bc1q"),
            listOf("payto", "monero", xmr)
        )
        // the first valid entry is edited in place: type made lowercase, extra kept;
        // the invalid entry and the second address stay as they were
        assertEquals(
            listOf(
                listOf("payto", "monero", "not-an-address"),
                listOf("payto", "monero", xmr, "extra"),
                listOf("payto", "bitcoin", "bc1q"),
                listOf("payto", "monero", xmr)
            ),
            NipA3.withMoneroAddress(tags, xmr)
        )
    }

    @Test fun addingAppendsWhenOnlyInvalidEntries() {
        val tags = listOf(listOf("payto", "monero", "not-an-address"))
        assertEquals(tags + listOf(listOf("payto", "monero", xmr)), NipA3.withMoneroAddress(tags, xmr))
    }

    @Test fun addingAppendsWhenAbsent() {
        val tags = listOf(listOf("payto", "bitcoin", "bc1q"))
        assertEquals(tags + listOf(listOf("payto", "monero", xmr)), NipA3.withMoneroAddress(tags, xmr))
    }

    @Test fun planPublishesNothingWithoutChange() {
        // no list and nothing to add
        assertNull(NipA3.planMoneroUpdate(null, null))
        // same address already listed
        val tags = listOf(listOf("payto", "bitcoin", "bc1q"), listOf("payto", "monero", xmr))
        assertNull(NipA3.planMoneroUpdate(tags, xmr))
        // clearing when there was no monero entry
        assertNull(NipA3.planMoneroUpdate(listOf(listOf("payto", "bitcoin", "bc1q")), null))
        // unchanged address never rewrites unusual lists (alias, extras, two entries)
        assertNull(NipA3.planMoneroUpdate(listOf(listOf("payto", "xmr", xmr)), xmr))
        assertNull(NipA3.planMoneroUpdate(listOf(listOf("payto", "monero", xmr, "extra")), xmr))
        assertNull(NipA3.planMoneroUpdate(listOf(listOf("payto", "monero", xmr), listOf("payto", "monero", xmr2)), xmr))
    }

    @Test fun planCreatesAddsChangesAndRemoves() {
        assertEquals(listOf(listOf("payto", "monero", xmr)), NipA3.planMoneroUpdate(null, xmr))
        val tags = listOf(listOf("payto", "bitcoin", "bc1q"), listOf("payto", "monero", xmr))
        assertEquals(
            listOf(listOf("payto", "bitcoin", "bc1q"), listOf("payto", "monero", xmr2)),
            NipA3.planMoneroUpdate(tags, xmr2)
        )
        assertEquals(listOf(listOf("payto", "bitcoin", "bc1q")), NipA3.planMoneroUpdate(tags, null))
        // only entry removed: publish an empty list rather than leave the old address up
        assertEquals(emptyList<List<String>>(), NipA3.planMoneroUpdate(listOf(listOf("payto", "monero", xmr)), null))
        // changing an xmr alias entry writes the lowercase canonical type
        assertEquals(
            listOf(listOf("payto", "monero", xmr2)),
            NipA3.planMoneroUpdate(listOf(listOf("payto", "xmr", xmr)), xmr2)
        )
        // clearing removes every Monero entry so no other address takes its place
        assertEquals(
            listOf(listOf("payto", "bitcoin", "bc1q")),
            NipA3.planMoneroUpdate(listOf(listOf("payto", "monero", xmr), listOf("payto", "bitcoin", "bc1q"), listOf("payto", "xmr", xmr2)), null)
        )
    }
}

package com.wisp.app.repo

import android.content.SharedPreferences
import com.wisp.app.nostr.NipA3
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.util.MoneroTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayTargetRepositoryTest {
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { pending[key] = null }
            override fun clear() = also { }
            override fun commit(): Boolean { values.putAll(pending); return true }
            override fun apply() { commit() }
        }
    }

    private val pk = "a".repeat(64)
    private val xmr = MoneroTest.STANDARD

    private fun event(createdAt: Long, tags: List<List<String>>, kind: Int = NipA3.KIND_PAYMENT_TARGETS) =
        NostrEvent(id = "%064x".format(createdAt), pubkey = pk, created_at = createdAt, kind = kind,
            tags = tags, content = "", sig = "0".repeat(128))

    @Test fun newestEventWins() {
        val repo = PayTargetRepository(FakePrefs(), FakePrefs()) { 1_000L }
        assertNotNull(repo.updateFromEvent(event(100, listOf(listOf("payto", "monero", xmr)))))
        assertEquals(xmr, repo.moneroAddress(pk))
        // older event ignored
        assertNull(repo.updateFromEvent(event(50, emptyList())))
        assertEquals(xmr, repo.moneroAddress(pk))
        // newer event without monero clears it
        assertNotNull(repo.updateFromEvent(event(200, listOf(listOf("payto", "bitcoin", "bc1q")))))
        assertNull(repo.moneroAddress(pk))
        assertEquals(listOf(listOf("payto", "bitcoin", "bc1q")), repo.get(pk)!!.tags)
        repo.close()
    }

    @Test fun ignoresOtherKinds() {
        val repo = PayTargetRepository(FakePrefs(), FakePrefs()) { 1_000L }
        assertNull(repo.updateFromEvent(event(100, listOf(listOf("payto", "monero", xmr)), kind = 0)))
        assertNull(repo.get(pk))
        repo.close()
    }

    @Test fun lookupMarkerExpires() {
        var now = 1_000_000L
        val repo = PayTargetRepository(FakePrefs(), FakePrefs()) { now }
        assertTrue(repo.needsLookup(pk))
        repo.markChecked(pk)
        assertFalse(repo.needsLookup(pk))
        // nothing found: asks again after the shorter not-found window
        now += PayTargetRepository.NOT_FOUND_TTL_SEC + 1
        assertTrue(repo.needsLookup(pk))
        // receiving an event also counts as a lookup, and a found list stays fresh longer
        repo.updateFromEvent(event(5, emptyList()))
        assertFalse(repo.needsLookup(pk))
        now += PayTargetRepository.NOT_FOUND_TTL_SEC + 1
        assertFalse(repo.needsLookup(pk))
        now += PayTargetRepository.LOOKUP_TTL_SEC
        assertTrue(repo.needsLookup(pk))
        repo.close()
    }

    @Test fun payOptionsListsWhatTheAuthorAccepts() {
        val profile = ProfileData(pk, null, null, null, null, null, null, lud16 = "a@b.c", updatedAt = 1)
        val noLn = profile.copy(lud16 = " ")
        val list = PayTargetList(pk, listOf(listOf("payto", "monero", xmr)), "", 1)

        val both = PayOptions.of(profile, list)
        assertEquals(listOf(PayMethod.BITCOIN, PayMethod.MONERO), both.methods)
        assertTrue(both.hasChoice)
        assertEquals(PayMethod.MONERO, both.initialMethod(PayMethod.MONERO))
        assertEquals(PayMethod.BITCOIN, both.initialMethod(PayMethod.BITCOIN))
        assertEquals(PayMethod.BITCOIN, both.initialMethod(null))

        val moneroOnly = PayOptions.of(noLn, list)
        assertEquals(listOf(PayMethod.MONERO), moneroOnly.methods)
        assertFalse(moneroOnly.hasChoice)
        assertEquals(PayMethod.MONERO, moneroOnly.initialMethod(PayMethod.BITCOIN))

        val btcOnly = PayOptions.of(profile, null)
        assertEquals(PayMethod.BITCOIN, btcOnly.initialMethod(PayMethod.MONERO))

        val invalidXmr = PayOptions.of(null, PayTargetList(pk, listOf(listOf("payto", "monero", "nope")), "", 1))
        assertTrue(invalidXmr.isEmpty)
        assertNull(PayOptions.NONE.initialMethod(PayMethod.MONERO))
    }

    @Test fun payMethodKeysRoundTrip() {
        for (m in PayMethod.entries) assertEquals(m, PayMethod.fromKey(m.key))
        assertNull(PayMethod.fromKey("dogecoin"))
        assertNull(PayMethod.fromKey(null))
    }
}

package com.wisp.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class MoneroTest {
    companion object {
        val STANDARD = "4" + "AbCdEfGh12".repeat(9) + "xyzW"      // 95
        val SUBADDRESS = "8" + "AbCdEfGh12".repeat(9) + "xyzW"    // 95
        val INTEGRATED = "4" + "AbCdEfGh12".repeat(10) + "xyzWq"  // 106
    }

    private lateinit var savedLocale: Locale

    @Before fun saveLocale() { savedLocale = Locale.getDefault() }
    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    @Test fun fixtureLengths() {
        assertEquals(95, STANDARD.length)
        assertEquals(95, SUBADDRESS.length)
        assertEquals(106, INTEGRATED.length)
    }

    @Test fun acceptsValidShapes() {
        assertTrue(Monero.isValidAddress(STANDARD))
        assertTrue(Monero.isValidAddress(SUBADDRESS))
        assertTrue(Monero.isValidAddress(INTEGRATED))
    }

    @Test fun rejectsBadShapes() {
        assertFalse(Monero.isValidAddress(null))
        assertFalse(Monero.isValidAddress(""))
        assertFalse(Monero.isValidAddress(STANDARD.dropLast(1)))
        assertFalse(Monero.isValidAddress(STANDARD + "a"))
        // wrong first character
        assertFalse(Monero.isValidAddress("5" + STANDARD.drop(1)))
        // integrated addresses only start with 4
        assertFalse(Monero.isValidAddress("8" + INTEGRATED.drop(1)))
        // characters outside base58: 0, O, I, l
        for (bad in listOf('0', 'O', 'I', 'l', ' ', '+')) {
            assertFalse("char $bad", Monero.isValidAddress(STANDARD.dropLast(1) + bad))
        }
        // surrounding whitespace is not silently accepted; normalize first
        assertFalse(Monero.isValidAddress(" $STANDARD"))
        assertTrue(Monero.isValidAddress(Monero.normalizeAddress("  $STANDARD\n")))
    }

    @Test fun normalizeAddress() {
        assertNull(Monero.normalizeAddress("   "))
        assertEquals("abc", Monero.normalizeAddress(" abc "))
    }

    @Test fun shortAddress() {
        assertEquals("4AbC…xyzW", Monero.shortAddress(STANDARD))
        assertEquals("short", Monero.shortAddress("short"))
    }

    @Test fun formatsPlainDecimals() {
        assertEquals("0.005", Monero.formatAmount(BigDecimal("0.005")))
        assertEquals("1", Monero.formatAmount(BigDecimal("1")))
        assertEquals("1", Monero.formatAmount(BigDecimal("1.000")))
        assertEquals("0.1", Monero.formatAmount(BigDecimal("0.10")))
        assertEquals("100", Monero.formatAmount(BigDecimal("100")))
        assertEquals("0.000000000001", Monero.formatAmount(BigDecimal("1E-12")))
        assertEquals("1000000", Monero.formatAmount(BigDecimal("1000000.00")))
    }

    @Test fun formatsDoublesInCommaLocale() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("0.005", Monero.formatAmount(0.005))
        assertEquals("1", Monero.formatAmount(1.0))
        assertEquals("0.1", Monero.formatAmount(0.1))
        assertEquals("1234.5", Monero.formatAmount(1234.5))
    }

    @Test fun paymentUriInCommaLocale() {
        Locale.setDefault(Locale("fr", "FR"))
        assertEquals("monero:$STANDARD?tx_amount=0.005", Monero.paymentUri(STANDARD, BigDecimal("0.005")))
        assertEquals("monero:$STANDARD?tx_amount=1", Monero.paymentUri(STANDARD, BigDecimal("1")))
        assertEquals("monero:$STANDARD?tx_amount=0.1", Monero.paymentUri(STANDARD, BigDecimal("0.10")))
        assertEquals("monero:$STANDARD?tx_amount=0.1", Monero.paymentUri(STANDARD, Monero.parseAmount("0,1")))
        assertEquals("monero:$STANDARD", Monero.paymentUri(STANDARD, null))
    }

    @Test fun parsesTypedAmounts() {
        assertEquals(BigDecimal("0.02"), Monero.parseAmount("0.02"))
        assertEquals(BigDecimal("0.02"), Monero.parseAmount("0,02"))
        assertEquals(BigDecimal(".5"), Monero.parseAmount(".5"))
        assertEquals(BigDecimal("3"), Monero.parseAmount(" 3 "))
        assertNull(Monero.parseAmount(""))
        assertNull(Monero.parseAmount("."))
        assertNull(Monero.parseAmount("0"))
        assertNull(Monero.parseAmount("0.000"))
        assertNull(Monero.parseAmount("-1"))
        assertNull(Monero.parseAmount("1e3"))
        assertNull(Monero.parseAmount("1,000.5"))
        assertNull(Monero.parseAmount("1.2.3"))
        assertNull(Monero.parseAmount("abc"))
        // finer than one piconero
        assertNull(Monero.parseAmount("0.0000000000001"))
        assertEquals(BigDecimal("0.000000000001"), Monero.parseAmount("0.000000000001"))
    }

    @Test fun presets() {
        assertEquals(listOf("0.005", "0.02", "0.05"), Monero.PRESET_AMOUNTS.map(Monero::formatAmount))
    }
}

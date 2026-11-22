package com.wisp.app.util

import java.math.BigDecimal
import java.util.Locale

/**
 * Monero helpers for tipping by hand-off: Pinket holds no Monero wallet, it only
 * checks addresses and builds the `monero:` payment link the phone's wallet opens.
 */
object Monero {
    const val URI_SCHEME = "monero"

    /** Amount presets for the send sheet, in XMR. */
    val PRESET_AMOUNTS: List<BigDecimal> = listOf("0.005", "0.02", "0.05").map(::BigDecimal)

    /** Smallest unit is one piconero (1e-12 XMR). */
    const val MAX_DECIMALS = 12

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val STANDARD_LENGTH = 95
    private const val INTEGRATED_LENGTH = 106

    /**
     * Mainnet address shape check: base58 characters only, and either 95 characters
     * starting with `4` (standard) or `8` (subaddress), or 106 characters starting
     * with `4` (integrated). No checksum check; the wallet does that.
     */
    fun isValidAddress(address: String?): Boolean {
        if (address == null) return false
        if (address.any { it !in BASE58 }) return false
        return when (address.length) {
            STANDARD_LENGTH -> address[0] == '4' || address[0] == '8'
            INTEGRATED_LENGTH -> address[0] == '4'
            else -> false
        }
    }

    /** Trims what a person typed or pasted; returns null when the result is empty. */
    fun normalizeAddress(input: String): String? = input.trim().ifEmpty { null }

    /** "4AdU…Mj9q": first 4 and last 4 characters. Short strings come back unchanged. */
    fun shortAddress(address: String): String =
        if (address.length <= 10) address else "${address.take(4)}…${address.takeLast(4)}"

    /**
     * Parses an amount someone typed. Accepts a dot or a comma as the decimal mark,
     * no grouping, no exponent. Returns null for empty, zero, negative, malformed or
     * finer-than-piconero input.
     */
    fun parseAmount(input: String): BigDecimal? {
        val text = input.trim().replace(',', '.')
        if (text.isEmpty() || !text.matches(Regex("""\d*\.?\d*""")) || text == ".") return null
        val value = try { BigDecimal(text) } catch (_: NumberFormatException) { return null }
        if (value.signum() <= 0) return null
        if (value.stripTrailingZeros().scale() > MAX_DECIMALS) return null
        return value
    }

    /**
     * Plain decimal for links and display: dot as the decimal mark, no grouping, no
     * exponent, trailing zeros trimmed ("0.020" -> "0.02", "1.0" -> "1").
     */
    fun formatAmount(amount: BigDecimal): String {
        val stripped = amount.stripTrailingZeros()
        return if (stripped.signum() == 0) "0" else stripped.toPlainString()
    }

    /** Same as [formatAmount] for a Double, rounded to piconero precision using Locale.US. */
    fun formatAmount(amount: Double): String =
        formatAmount(BigDecimal(String.format(Locale.US, "%.${MAX_DECIMALS}f", amount)))

    /** `monero:<address>?tx_amount=<amount>`; the amount part is left out when null. */
    fun paymentUri(address: String, amount: BigDecimal?): String =
        if (amount == null) "$URI_SCHEME:$address"
        else "$URI_SCHEME:$address?tx_amount=${formatAmount(amount)}"
}

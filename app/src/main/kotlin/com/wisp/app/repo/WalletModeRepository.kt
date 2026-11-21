package com.wisp.app.repo

import android.content.Context

/**
 * Which wallet backs zaps and the wallet screen. Nostr Wallet Connect is the
 * only kind left: the built-in Spark wallet was removed. Builds that predate
 * that removal may still have "SPARK" stored, which [WalletModeRepository.getMode]
 * migrates.
 */
enum class WalletMode { NONE, NWC }

enum class BalanceUnit { BITCOIN, LIGHTNING, SATS }

class WalletModeRepository(private val context: Context, pubkeyHex: String? = null) {

    private var ownerPubkey: String? = pubkeyHex

    private var prefs = context.getSharedPreferences(
        prefsName(pubkeyHex),
        Context.MODE_PRIVATE
    )

    fun reload(pubkeyHex: String?) {
        ownerPubkey = pubkeyHex
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    }

    /**
     * Delete the encrypted prefs file the removed Spark wallet kept for this
     * account (its recovery phrase and backup flag). Called on logout, which
     * used to wipe that phrase; nothing reads the file any more.
     */
    fun clearLegacySparkData() {
        val name = ownerPubkey?.let { "wisp_spark_$it" } ?: "wisp_spark"
        try { context.deleteSharedPreferences(name) } catch (_: Exception) { }
    }

    /**
     * The stored wallet mode. A mode this build no longer knows (the removed
     * "SPARK" mode) falls back to NWC when an NWC connection is saved for this
     * account, otherwise to [WalletMode.NONE], and the fallback is written back
     * so it only happens once.
     */
    fun getMode(hasNwcConnection: Boolean): WalletMode {
        val name = prefs.getString("wallet_mode", null) ?: return WalletMode.NONE
        WalletMode.entries.firstOrNull { it.name == name }?.let { return it }
        val fallback = if (hasNwcConnection) WalletMode.NWC else WalletMode.NONE
        setMode(fallback)
        return fallback
    }

    fun setMode(mode: WalletMode) {
        prefs.edit().putString("wallet_mode", mode.name).apply()
    }

    fun getBalanceUnit(): BalanceUnit {
        val name = prefs.getString("balance_unit", null) ?: return BalanceUnit.SATS
        return try { BalanceUnit.valueOf(name) } catch (_: Exception) { BalanceUnit.SATS }
    }

    fun setBalanceUnit(unit: BalanceUnit) {
        prefs.edit().putString("balance_unit", unit.name).apply()
    }

    companion object {
        private fun prefsName(pubkeyHex: String?) =
            if (pubkeyHex != null) "wisp_wallet_mode_$pubkeyHex" else "wisp_wallet_mode"
    }
}

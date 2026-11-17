package com.wisp.app.repo

import android.content.Context
import com.wisp.app.nostr.DittoTrending
import com.wisp.app.nostr.TrendingWindow
import com.wisp.app.relay.RelayConfig

/** Device-wide trending settings: which Ditto relay to ask, and the last picked ranking. */
class TrendingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    fun getRelay(): String =
        prefs.getString(KEY_RELAY, null)?.takeIf { it.isNotBlank() } ?: DittoTrending.DEFAULT_RELAY

    /** Saves [url] if it is a valid relay URL; a blank value restores the default. */
    fun setRelay(url: String): Boolean {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty()) {
            prefs.edit().remove(KEY_RELAY).apply()
            return true
        }
        if (!RelayConfig.isValidUrl(clean)) return false
        prefs.edit().putString(KEY_RELAY, clean).apply()
        return true
    }

    fun getWindow(): TrendingWindow = TrendingWindow.fromSaved(prefs.getString(KEY_WINDOW, null))
    fun setWindow(window: TrendingWindow) = prefs.edit().putString(KEY_WINDOW, window.name).apply()

    companion object {
        private const val KEY_RELAY = "trending_relay"
        private const val KEY_WINDOW = "trending_window"
    }
}

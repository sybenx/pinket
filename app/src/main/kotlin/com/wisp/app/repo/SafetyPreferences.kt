package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SafetyPreferences(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _spamFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_SPAM_FILTER, true))
    val spamFilterEnabled: StateFlow<Boolean> = _spamFilterEnabled

    private val _muteSignalEnabled = MutableStateFlow(prefs.getBoolean(KEY_MUTE_SIGNAL, true))
    /** Hide replies from people 3+ of your follows publicly mute (see ReplyGate). */
    val muteSignalEnabled: StateFlow<Boolean> = _muteSignalEnabled

    private val _followSignalEnabled = MutableStateFlow(prefs.getBoolean(KEY_FOLLOW_SIGNAL, true))
    /** Skip the spam model for people 3+ of your follows follow (see ReplyGate). */
    val followSignalEnabled: StateFlow<Boolean> = _followSignalEnabled

    private val _wotFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_WOT_FILTER, false))
    val wotFilterEnabled: StateFlow<Boolean> = _wotFilterEnabled

    private var safelistSet =
        HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
    private val _spamSafelist = MutableStateFlow<Set<String>>(safelistSet.toSet())
    val spamSafelist: StateFlow<Set<String>> = _spamSafelist

    fun setSpamFilterEnabled(enabled: Boolean) {
        _spamFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SPAM_FILTER, enabled).apply()
    }

    fun setMuteSignalEnabled(enabled: Boolean) {
        _muteSignalEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MUTE_SIGNAL, enabled).apply()
    }

    fun setFollowSignalEnabled(enabled: Boolean) {
        _followSignalEnabled.value = enabled
        prefs.edit().putBoolean(KEY_FOLLOW_SIGNAL, enabled).apply()
    }

    fun setWotFilterEnabled(enabled: Boolean) {
        _wotFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_WOT_FILTER, enabled).apply()
    }

    fun isSpamSafelisted(pubkey: String): Boolean = safelistSet.contains(pubkey)

    fun addToSpamSafelist(pubkey: String) {
        safelistSet.add(pubkey)
        _spamSafelist.value = safelistSet.toSet()
        prefs.edit().putStringSet(KEY_SPAM_SAFELIST, safelistSet.toSet()).apply()
    }

    fun removeFromSpamSafelist(pubkey: String) {
        safelistSet.remove(pubkey)
        _spamSafelist.value = safelistSet.toSet()
        prefs.edit().putStringSet(KEY_SPAM_SAFELIST, safelistSet.toSet()).apply()
    }

    /** Re-point to the new account's prefs file and refresh all StateFlows. */
    fun reload(newPubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(newPubkeyHex), Context.MODE_PRIVATE)
        _spamFilterEnabled.value = prefs.getBoolean(KEY_SPAM_FILTER, true)
        _muteSignalEnabled.value = prefs.getBoolean(KEY_MUTE_SIGNAL, true)
        _followSignalEnabled.value = prefs.getBoolean(KEY_FOLLOW_SIGNAL, true)
        _wotFilterEnabled.value = prefs.getBoolean(KEY_WOT_FILTER, false)
        safelistSet = HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
        _spamSafelist.value = safelistSet.toSet()
    }

    companion object {
        private const val KEY_SPAM_FILTER = "spam_filter_enabled"
        private const val KEY_MUTE_SIGNAL = "spam_mute_signal_enabled"
        private const val KEY_FOLLOW_SIGNAL = "spam_follow_signal_enabled"
        private const val KEY_WOT_FILTER = "wot_filter_enabled"
        private const val KEY_SPAM_SAFELIST = "spam_safelist"

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_safety_$pubkeyHex" else "wisp_safety"
    }
}

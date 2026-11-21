package com.wisp.app.translate

import android.content.Context
import android.content.res.Resources
import android.os.LocaleList

/**
 * Translation settings kept on this phone: languages you read besides the phone's, your own LibreTranslate
 * (address and optional key), and the language picked for an author whose posts were hard to tell.
 */
class TranslationPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("translation", Context.MODE_PRIVATE)

    /** Languages you added (Settings, Interface, Translation). */
    fun addedLanguages(): Set<String> =
        prefs.getStringSet(KEY_READS, emptySet())!!.map(PostLanguage::normalize).toSet()

    fun setAddedLanguages(codes: Set<String>) =
        prefs.edit().putStringSet(KEY_READS, codes.map(PostLanguage::normalize).toSet()).apply()

    /** The phone's languages (and the app's, if set) plus the ones you added: posts in these aren't translated. */
    fun reads(): Set<String> = (phoneLanguages() + addedLanguages()).toSet()

    fun serverUrl(): String = prefs.getString(KEY_URL, "") ?: ""
    fun serverKey(): String = prefs.getString(KEY_KEY, "") ?: ""
    fun setServerUrl(url: String) = prefs.edit().putString(KEY_URL, url).apply()
    fun setServerKey(key: String) = prefs.edit().putString(KEY_KEY, key.trim()).apply()

    fun authorLanguagesJson(): String? = prefs.getString(KEY_AUTHORS, null)
    fun setAuthorLanguagesJson(json: String) = prefs.edit().putString(KEY_AUTHORS, json).apply()

    companion object {
        private const val KEY_READS = "reads"
        private const val KEY_URL = "server_url"
        private const val KEY_KEY = "server_key"
        private const val KEY_AUTHORS = "author_languages"

        fun phoneLanguages(): List<String> {
            val out = LinkedHashSet<String>()
            for (list in listOf(LocaleList.getDefault(), Resources.getSystem().configuration.locales)) {
                for (i in 0 until list.size()) list[i]?.language?.takeIf { it.isNotEmpty() }?.let { out += PostLanguage.normalize(it) }
            }
            return out.toList()
        }

        /** What posts are translated into: the phone's first language. */
        fun target(): String =
            LocaleList.getDefault()[0]?.language?.takeIf { it.isNotEmpty() }?.let(PostLanguage::normalize) ?: "en"
    }
}

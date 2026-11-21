package com.wisp.app.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Translation on the phone (ML Kit). A language's model (~30 MB) is downloaded once, and only after a tap;
 * after that nothing leaves the phone.
 */
object OnDeviceTranslator {
    private val identifier by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.05f).build()
        )
    }

    fun supported(code: String): Boolean = TranslateLanguage.fromLanguageTag(code) != null

    /** Every language it can translate, sorted by name, for "Other…". */
    fun all(): List<String> = TranslateLanguage.getAllLanguages().map(PostLanguage::normalize).distinct().sortedBy { name(it) }

    /** "Spanish", in the phone's language. */
    fun name(code: String): String =
        Locale(code).getDisplayLanguage(Locale.getDefault()).ifEmpty { code }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    /** The likeliest languages of [cleaned] (already stripped of links and the like), with confidence. */
    suspend fun candidates(cleaned: String): List<Pair<String, Float>> =
        identifier.identifyPossibleLanguages(cleaned).await()
            .map { PostLanguage.normalize(it.languageTag) to it.confidence }

    /** Languages whose models are on the phone. */
    suspend fun downloaded(): Set<String> =
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { PostLanguage.normalize(it.language) }.toSet()

    /** Whether [from] → [target] works with what's on the phone (English is always there). */
    fun ready(from: String, target: String, have: Set<String>): Boolean =
        listOf(from, target).all { it == TranslateLanguage.ENGLISH || it in have }

    /** [text] from [from] into [target]; null if it can't. Downloads the model only when [download] is true. */
    suspend fun translate(text: String, from: String, target: String, download: Boolean): String? {
        val src = TranslateLanguage.fromLanguageTag(from) ?: return null
        val dst = TranslateLanguage.fromLanguageTag(target) ?: return null
        if (src == dst) return null
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
        )
        try {
            if (download) {
                // A Task<Void>: success has a null value, so only an exception means it failed.
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } else if (!ready(from, target, downloaded())) {
                return null
            }
            return PostLanguage.translateProtected(text) { line -> translator.translate(line).await() }
        } finally {
            translator.close()
        }
    }
}

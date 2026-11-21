package com.wisp.app.translate

import com.wisp.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Your own LibreTranslate, if you set one. Tried first; down, slow or wrong, the phone translates instead and
 * the server isn't asked again for ten minutes.
 */
object LibreTranslateClient {
    sealed interface Status {
        data object Working : Status
        /** The check against /languages failed. */
        data class NotWorking(val why: String) : Status
        /** A translation failed: resting for ten minutes. */
        data class Resting(val why: String) : Status
        /** A plain http address was entered. */
        data object HttpsOnly : Status
    }

    private val _status = MutableStateFlow<Status?>(null)
    /** How the last request went, for the setting to show. */
    val status: StateFlow<Status?> = _status

    @Volatile private var waitUntil = 0L

    private val http by lazy {
        HttpClientFactory.getGeneralClient().newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
    }
    private val checkHttp by lazy {
        HttpClientFactory.getGeneralClient().newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    }
    private val jsonType = "application/json".toMediaType()

    fun usable(url: String): Boolean = url.isNotEmpty() && System.currentTimeMillis() >= waitUntil

    /** A new address or key: forget the last failure. */
    fun reset() { waitUntil = 0; _status.value = null }

    fun refuseHttp() { _status.value = Status.HttpsOnly }

    private fun body(key: String, vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(
        pairs.toMap() + (if (key.isNotEmpty()) mapOf("api_key" to JsonPrimitive(key)) else emptyMap())
    ).toString().toRequestBody(jsonType)

    /** One text from [source] (or "auto") into [target]; null when off, resting, down or wrong. */
    suspend fun translate(url: String, key: String, text: String, source: String, target: String): String? =
        withContext(Dispatchers.IO) {
            if (!usable(url)) return@withContext null
            try {
                val req = Request.Builder().url("$url/translate").post(
                    body(key, "q" to JsonPrimitive(text), "source" to JsonPrimitive(source),
                        "target" to JsonPrimitive(target), "format" to JsonPrimitive("text"))
                ).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext fail("answered ${r.code}")
                    val out = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["translatedText"]
                        ?.jsonPrimitive?.contentOrNull
                    if (out.isNullOrBlank()) return@withContext fail("answered something else")
                    _status.value = Status.Working
                    out
                }
            } catch (e: Exception) {
                fail(e.message ?: "no answer")
            }
        }

    /** The server's guess at a text's language, likeliest first, confidence 0 to 1; null when off or down. */
    suspend fun detect(url: String, key: String, text: String): List<Pair<String, Float>>? =
        withContext(Dispatchers.IO) {
            if (!usable(url)) return@withContext null
            try {
                val req = Request.Builder().url("$url/detect").post(body(key, "q" to JsonPrimitive(text))).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    (Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray)?.mapNotNull { x ->
                        val o = x as? JsonObject ?: return@mapNotNull null
                        val lang = o["language"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        PostLanguage.normalize(lang) to ((o["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f) / 100f)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Is it there? Asked once you've entered an address. */
    suspend fun check(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext false
        try {
            checkHttp.newCall(Request.Builder().url("$url/languages").build()).execute().use { r ->
                val ok = r.isSuccessful && runCatching {
                    Json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray
                }.isSuccess
                _status.value = if (ok) Status.Working else Status.NotWorking("answered ${r.code}")
                if (ok) waitUntil = 0
                ok
            }
        } catch (e: Exception) {
            _status.value = Status.NotWorking(e.message ?: "no answer")
            false
        }
    }

    private fun fail(why: String): String? {
        waitUntil = System.currentTimeMillis() + 10 * 60_000L
        _status.value = Status.Resting(why)
        return null
    }
}

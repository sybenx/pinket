package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Builds kind 0 content when the profile editor saves. */
object ProfileContent {
    private val json = Json { ignoreUnknownKeys = true }

    /** Keys the profile editor owns. Anything else in the existing content is kept. */
    val EDITED_KEYS = listOf("display_name", "name", "about", "picture", "nip05", "banner", "lud16")

    /**
     * Starts from [baseContent] (the newest existing kind 0 content, may be null or
     * malformed) and overwrites only [EDITED_KEYS]. A blank or missing value removes
     * that key, as the old fixed-field builder did by leaving it out.
     */
    fun merge(baseContent: String?, edits: Map<String, String?>): String {
        val base: Map<String, kotlinx.serialization.json.JsonElement> = baseContent
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: emptyMap()
        val out = LinkedHashMap(base)
        for (key in EDITED_KEYS) {
            val value = edits[key]
            if (value.isNullOrBlank()) out.remove(key) else out[key] = JsonPrimitive(value)
        }
        return JsonObject(out).toString()
    }

    /**
     * The editor's fields as merge edits. The single name field is written to both
     * `display_name` and `name`, as before.
     */
    fun editorEdits(
        name: String,
        about: String,
        picture: String,
        nip05: String,
        banner: String,
        lud16: String
    ): Map<String, String?> = mapOf(
        "display_name" to name,
        "name" to name,
        "about" to about,
        "picture" to picture,
        "nip05" to nip05,
        "banner" to banner,
        "lud16" to lud16
    )
}

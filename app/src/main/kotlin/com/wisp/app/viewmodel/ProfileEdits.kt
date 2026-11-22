package com.wisp.app.viewmodel

import com.wisp.app.nostr.ProfileData

/** What the profile editor shows: one name field, the about text, images and addresses. */
data class ProfileForm(
    val name: String = "",
    val about: String = "",
    val picture: String = "",
    val nip05: String = "",
    val banner: String = "",
    val lud16: String = ""
) {
    companion object {
        /** The form for a profile (empty when there is none). The name field shows the display name, else the name. */
        fun of(p: ProfileData?): ProfileForm = if (p == null) ProfileForm() else ProfileForm(
            name = p.displayName?.ifBlank { null } ?: p.name ?: "",
            about = p.about ?: "",
            picture = p.picture ?: "",
            nip05 = p.nip05 ?: "",
            banner = p.banner ?: "",
            lud16 = p.lud16 ?: ""
        )
    }
}

/**
 * The profile fields the user changed in the editor, and only those, for [ListWriter.setProfileText] (a blank value
 * takes that field out). The writer applies them to your newest profile, so every other field stays as it is there.
 */
object ProfileEdits {

    /**
     * The profile fields the one name field stands for: the ones that hold the shown name (display name and/or
     * name). A profile with neither gets both, as the editor always wrote them.
     */
    fun nameKeys(start: ProfileData?): List<String> {
        val shown = ProfileForm.of(start).name
        if (start == null || shown.isBlank()) return listOf("display_name", "name")
        return buildList {
            if (start.displayName == shown) add("display_name")
            if (start.name == shown) add("name")
        }.ifEmpty { listOf("display_name", "name") }
    }

    /** Field to new value (blank: take it out) for every field of [form] that differs from what [start] showed. */
    fun changes(start: ProfileData?, form: ProfileForm): Map<String, String?> {
        val was = ProfileForm.of(start)
        val out = LinkedHashMap<String, String?>()
        if (form.name != was.name) for (k in nameKeys(start)) out[k] = form.name
        if (form.about != was.about) out["about"] = form.about
        if (form.picture != was.picture) out["picture"] = form.picture
        if (form.nip05 != was.nip05) out["nip05"] = form.nip05
        if (form.banner != was.banner) out["banner"] = form.banner
        if (form.lud16 != was.lud16) out["lud16"] = form.lud16
        return out
    }

    /**
     * The form after a newer profile [next] arrived while it showed [typed], having opened with [start]: a field the
     * user has not touched follows [next], a field the user changed keeps what was typed.
     */
    fun rebase(start: ProfileData?, typed: ProfileForm, next: ProfileData?): ProfileForm {
        val was = ProfileForm.of(start)
        val now = ProfileForm.of(next)
        fun pick(t: String, w: String, n: String) = if (t == w) n else t
        return ProfileForm(
            name = pick(typed.name, was.name, now.name),
            about = pick(typed.about, was.about, now.about),
            picture = pick(typed.picture, was.picture, now.picture),
            nip05 = pick(typed.nip05, was.nip05, now.nip05),
            banner = pick(typed.banner, was.banner, now.banner),
            lud16 = pick(typed.lud16, was.lud16, now.lud16)
        )
    }
}

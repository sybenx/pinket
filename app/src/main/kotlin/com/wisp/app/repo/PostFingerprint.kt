package com.wisp.app.repo

import java.security.MessageDigest

/**
 * What one post "looks like", so a spam vote on it can match the next post of
 * the same kind even when the author, the numbers or the mentions differ.
 *
 * - [domains]: hosts the post links to, minus hosts that carry everybody's
 *   media or links (a vote on those would hide far too much).
 * - [textHash]: hash of the text with links, mentions, numbers and whitespace
 *   normalized. Null when too little text is left to mean anything.
 *
 * Pure and allocation-heavy (regex passes): compute it off the main thread.
 */
data class PostFingerprint(val domains: Set<String>, val textHash: String?) {
    val isEmpty: Boolean get() = domains.isEmpty() && textHash == null

    companion object {
        val EMPTY = PostFingerprint(emptySet(), null)

        /** Fewer letters than this after normalizing and no text hash is made ("gm", "nice"). */
        const val MIN_TEXT_LETTERS = 16
        /** And at least this many words. */
        const val MIN_TEXT_WORDS = 3

        private val URL = Regex("""(?i)\b(?:https?|wss?)://[^\s<>"')\]]+""")
        private val BARE_DOMAIN_URL = Regex("""(?i)\bwww\.[a-z0-9-]+(?:\.[a-z0-9-]+)+[^\s<>"')\]]*""")
        private val NOSTR_REF = Regex("""(?i)\b(?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{20,}""")
        private val INDEX_MENTION = Regex("""#\[\d+]""")
        private val AT_NAME = Regex("""(?<![\w@])@[\w.-]{2,}""")
        private val NUMBER = Regex("""\d+(?:[.,:]\d+)*""")
        private val SPACE = Regex("""\s+""")

        private const val LINK = "\u0001"
        private const val MENTION = "\u0002"

        /**
         * Hosts whose links say nothing about the post: media hosts, big
         * platforms and link-preview sites. Matched on the host and on any
         * parent domain ("image.nostr.build" matches "nostr.build").
         */
        private val COMMON_HOSTS = setOf(
            "nostr.build", "void.cat", "nostrcheck.me", "primal.net", "blossom.band",
            "satellite.earth", "nostr.download", "imgprxy.stacker.news", "imgur.com",
            "giphy.com", "tenor.com", "youtube.com", "youtu.be", "twitter.com", "x.com",
            "t.co", "github.com", "wikipedia.org", "reddit.com", "instagram.com",
            "facebook.com", "tiktok.com", "twitch.tv", "spotify.com", "apple.com",
            "google.com", "substack.com", "medium.com", "njump.me", "nostr.com",
            "habla.news", "yakihonne.com", "zap.stream", "fountain.fm", "wavlake.com",
            "stacker.news", "archive.org", "archive.ph", "amazon.com", "t.me"
        )

        fun of(content: String): PostFingerprint {
            if (content.isBlank()) return EMPTY
            val domains = HashSet<String>()
            for (m in URL.findAll(content)) hostOf(m.value)?.let { if (!isCommonHost(it)) domains.add(it) }
            for (m in BARE_DOMAIN_URL.findAll(content)) hostOf("http://" + m.value)?.let { if (!isCommonHost(it)) domains.add(it) }
            return PostFingerprint(domains, textHash(content))
        }

        /** The normalized text the hash is made from. Exposed for tests. */
        fun normalizeText(content: String): String {
            var s = content.lowercase()
            s = URL.replace(s, LINK)
            s = BARE_DOMAIN_URL.replace(s, LINK)
            s = NOSTR_REF.replace(s, MENTION)
            s = INDEX_MENTION.replace(s, MENTION)
            s = AT_NAME.replace(s, MENTION)
            s = NUMBER.replace(s, "0")
            s = SPACE.replace(s, " ").trim()
            return s
        }

        fun textHash(content: String): String? {
            val normalized = normalizeText(content)
            val letters = normalized.count { it.isLetter() }
            if (letters < MIN_TEXT_LETTERS) return null
            val words = normalized.split(' ').count { w -> w.any { it.isLetter() } }
            if (words < MIN_TEXT_WORDS) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(24)
            for (i in 0 until 12) sb.append(String.format("%02x", digest[i]))
            return sb.toString()
        }

        /** Lowercased host without port, credentials or a leading "www.". Null when there is none. */
        fun hostOf(url: String): String? {
            val afterScheme = url.substringAfter("://", url)
            var host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
            host = host.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
            if (host.startsWith("www.")) host = host.substring(4)
            if (host.isEmpty() || !host.contains('.')) return null
            if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
            return host
        }

        fun isCommonHost(host: String): Boolean {
            var h = host
            while (true) {
                if (h in COMMON_HOSTS) return true
                val dot = h.indexOf('.')
                if (dot < 0) return false
                h = h.substring(dot + 1)
            }
        }
    }
}

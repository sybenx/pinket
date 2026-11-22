package com.wisp.app.ui.component

import android.content.Context
import android.util.LruCache
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.TranslationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fills, ahead of time, the same caches a post row reads on its first composition, so a row that
 * scrolls into view is composed at its final height (see RowStability.kt for the rule).
 *
 * Call [warm] for posts that are about to scroll into view. It is cheap on the calling thread (one
 * cache lookup to dedupe, then everything moves to background dispatchers), idempotent, and safe to
 * call repeatedly from the main thread.
 */
object RowWarmup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Posts already warmed (by id and settings), so repeated calls are no-ops. */
    private val warmedPosts = LruCache<String, Boolean>(3000)

    /** Work in flight, by "kind:url", so two posts sharing a link or image don't fetch it twice. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Translation looks waiting or running; beyond this a fast fling skips them (the row still looks). */
    private val pendingLooks = AtomicInteger(0)
    private const val MAX_PENDING_LOOKS = 8

    /** Quoted posts already in memory are warmed one level deep, no further. */
    private const val MAX_QUOTE_DEPTH = 1

    /**
     * Warm everything [event]'s row will read when composed.
     *
     * @param context any context (the application context is kept, never the activity).
     * @param event the event the row renders (for a repost, the reposted note, not the repost wrapper).
     * @param eventRepo the repository the row's quote cards use (PostCard's `eventRepo`); null skips quotes.
     * @param translationRepo the app's translation repository (`LocalTranslationRepository`); null skips it.
     * @param autoLoadMedia `LocalMediaSettings.current.autoLoadMedia`; when false no images are downloaded.
     * @param autoTranslate the value the row gets as PostCard's `autoTranslate`.
     */
    fun warm(
        context: Context,
        event: NostrEvent,
        eventRepo: EventRepository?,
        translationRepo: TranslationRepository?,
        autoLoadMedia: Boolean,
        autoTranslate: Boolean
    ) {
        val key = "${event.id}:$autoLoadMedia:$autoTranslate:${eventRepo != null}:${translationRepo != null}"
        if (warmedPosts.get(key) != null) return
        // Only counts as done when the translation look could start; a skipped one is retried
        // the next time this post comes up (the media work below is deduped on its own).
        val lookSkipped = translationRepo != null && event.kind != 30023 &&
            pendingLooks.get() >= MAX_PENDING_LOOKS
        if (!lookSkipped) warmedPosts.put(key, true)
        val appContext = context.applicationContext

        // Same call and condition as PostCard's LaunchedEffect, so the state is decided before the row shows
        if (translationRepo != null && event.kind != 30023 && pendingLooks.get() < MAX_PENDING_LOOKS) {
            pendingLooks.incrementAndGet()
            scope.launch {
                try {
                    translationRepo.lookAt(event, autoTranslate)
                } catch (_: Exception) {
                } finally {
                    pendingLooks.decrementAndGet()
                }
            }
        }

        if (event.kind == 30023) return // an article row shows its cover card, not parsed content
        scope.launch {
            try {
                warmContent(appContext, event, eventRepo, autoLoadMedia, depth = 0)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun warmContent(
        context: Context,
        event: NostrEvent,
        eventRepo: EventRepository?,
        autoLoadMedia: Boolean,
        depth: Int
    ) {
        // The row's own parser, with the row's own inputs (custom emoji only splits text, so it is left out)
        val segments = parseContent(event.content.trimEnd('\n', '\r'), emptyMap(), parseImetaTags(event.tags))
        for (segment in segments) {
            when (segment) {
                is ContentSegment.ImageSegment -> if (autoLoadMedia) warmImage(context, segment.meta)
                is ContentSegment.UnknownMediaSegment -> warmUnknown(context, segment.meta, autoLoadMedia)
                is ContentSegment.LinkSegment -> warmLink(context, segment.url, autoLoadMedia)
                is ContentSegment.NostrNoteSegment -> if (eventRepo != null) {
                    val quoted = eventRepo.peekEvent(segment.eventId)
                    if (quoted == null) {
                        // The same fetch QuotedNote's effect makes (on the main thread, as there)
                        withContext(Dispatchers.Main) {
                            eventRepo.requestQuotedEvent(segment.eventId, segment.relayHints)
                        }
                    } else if (depth < MAX_QUOTE_DEPTH) {
                        warmContent(context, quoted, eventRepo, autoLoadMedia, depth + 1)
                    }
                }
                is ContentSegment.NostrAddressableSegment -> if (eventRepo != null) {
                    warmAddress(context, segment, eventRepo, autoLoadMedia, depth)
                }
                else -> {}
            }
        }
    }

    private suspend fun warmAddress(
        context: Context,
        segment: ContentSegment.NostrAddressableSegment,
        eventRepo: EventRepository,
        autoLoadMedia: Boolean,
        depth: Int
    ) {
        val kind = segment.kind ?: return
        val author = segment.author ?: return
        // Only the kinds RichContent renders as cards
        if (kind !in WARMED_ADDRESS_KINDS) return
        val found = eventRepo.peekAddressableEvent(kind, author, segment.dTag)
        if (found == null) {
            withContext(Dispatchers.Main) {
                if (eventRepo.findAddressableEvent(kind, author, segment.dTag) == null) {
                    eventRepo.requestAddressableEvent(kind, author, segment.dTag, segment.relays)
                }
            }
        } else if ((kind == 0 || kind == 1) && depth < MAX_QUOTE_DEPTH) {
            warmContent(context, found, eventRepo, autoLoadMedia, depth + 1)
        }
    }

    /** An image with no imeta size and no measured ratio: download it display-sized and record its ratio. */
    private fun warmImage(context: Context, meta: MediaMeta) {
        val url = meta.url
        if (parseDimRatio(meta.dimension) != null || measuredAspectRatios.get(url) != null) return
        enqueueImage(context, url, recordRatio = true)
    }

    private suspend fun warmUnknown(context: Context, meta: MediaMeta, autoLoadMedia: Boolean) {
        val url = meta.url
        val type = contentTypeCache.get(url) ?: run {
            val flight = "probe:$url"
            if (!inFlight.add(flight)) return
            try {
                withContext(Dispatchers.IO) { probeContentType(url) }
            } finally {
                inFlight.remove(flight)
            }
        }
        // Anything not classified as video or audio is shown as an image, as UnknownMediaContent does
        val kind = classifyMediaType(type)
        if (kind != "video" && kind != "audio" && autoLoadMedia) warmImage(context, meta)
    }

    private suspend fun warmLink(context: Context, url: String, autoLoadMedia: Boolean) {
        val og = ogCache.get(url) ?: run {
            val flight = "og:$url"
            if (!inFlight.add(flight)) return
            try {
                fetchOgData(url)
            } finally {
                inFlight.remove(flight)
            }
        } ?: return
        val image = og.image ?: return
        if (autoLoadMedia) enqueueImage(context, image, recordRatio = false)
    }

    private fun enqueueImage(context: Context, url: String, recordRatio: Boolean) {
        val flight = "img:$url"
        if (!inFlight.add(flight)) return
        val width = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val request = ImageRequest.Builder(context)
            .data(url)
            // Display-sized, not original size: the row draws it at most screen-wide
            .size(Size(Dimension(width), Dimension.Undefined))
            .listener(object : ImageRequest.Listener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    val image = result.image
                    if (recordRatio && image.width > 0 && image.height > 0) {
                        measuredAspectRatios.put(url, image.width.toFloat() / image.height)
                    }
                    inFlight.remove(flight)
                }

                override fun onError(request: ImageRequest, result: ErrorResult) {
                    inFlight.remove(flight)
                }

                override fun onCancel(request: ImageRequest) {
                    inFlight.remove(flight)
                }
            })
            .build()
        SingletonImageLoader.get(context).enqueue(request)
    }

    private val WARMED_ADDRESS_KINDS = setOf(0, 1, 30023, 30311, 30030, 36787)
}

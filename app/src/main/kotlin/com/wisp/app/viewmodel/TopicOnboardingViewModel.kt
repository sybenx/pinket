package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.DittoTrending
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.TrendingPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI state for the onboarding topics step. Loads trending hashtags from the
 * Ditto trend label (kind 1985, `#t`) on the trending relay and tracks the
 * user's selections. Search suggestions come from the trending hashtags plus
 * a small built-in list, since Ditto has no "all hashtags" set. Publishing is delegated to the existing
 * FeedViewModel.followHashtag / createInterestSet APIs once the user
 * continues — this class only owns the pick-list UI state.
 */
class TopicOnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _popularTopics = MutableStateFlow<List<String>>(emptyList())
    val popularTopics: StateFlow<List<String>> = _popularTopics

    private val _loadingPopular = MutableStateFlow(true)
    val loadingPopular: StateFlow<Boolean> = _loadingPopular

    private var allTopics: List<String> = BUILT_IN_TOPICS

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val _selectedTopics = MutableStateFlow<Set<String>>(emptySet())
    val selectedTopics: StateFlow<Set<String>> = _selectedTopics

    private var loadJob: Job? = null

    fun load(relayPool: RelayPool) {
        if (loadJob != null) return
        loadJob = viewModelScope.launch { loadTrendingTopics(relayPool) }
    }

    private suspend fun loadTrendingTopics(relayPool: RelayPool) {
        val relayUrl = TrendingPreferences(getApplication()).getRelay()
        try {
            val filter = DittoTrending.labelFilter(relayUrl, "#t")
            relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(SUB_ID, filter))

            val events = mutableListOf<NostrEvent>()
            collectUntilEose(relayPool, SUB_ID, 1, 6_000) { event -> events.add(event) }

            val trending = DittoTrending.newestLabel(events, "#t", relayUrl)
                ?.let { DittoTrending.parseHashtags(it) } ?: emptyList()
            _popularTopics.value = trending.ifEmpty { BUILT_IN_TOPICS.take(20) }
            allTopics = (trending + BUILT_IN_TOPICS).distinct()
            filterSuggestions(_query.value)
        } catch (e: Exception) {
            Log.e(TAG, "loadTrendingTopics failed: ${e.message}")
            _popularTopics.value = BUILT_IN_TOPICS.take(20)
        } finally {
            _loadingPopular.value = false
        }
    }

    private suspend fun collectUntilEose(
        relayPool: RelayPool,
        subId: String,
        expectedEose: Int,
        timeoutMs: Long,
        onEvent: (NostrEvent) -> Unit
    ) {
        val done = CompletableDeferred<Unit>()
        var eoseCount = 0

        val collectJob = viewModelScope.launch {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == subId) onEvent(relayEvent.event)
            }
        }
        val eoseJob = viewModelScope.launch {
            relayPool.eoseSignals.collect { id ->
                if (id == subId) {
                    eoseCount++
                    if (eoseCount >= expectedEose) done.complete(Unit)
                }
            }
        }

        withTimeoutOrNull(timeoutMs) { done.await() }
        collectJob.cancel()
        eoseJob.cancel()
        relayPool.closeOnAllRelays(subId)
    }

    fun updateQuery(q: String) {
        _query.value = q
        filterSuggestions(q)
    }

    private fun filterSuggestions(q: String) {
        val trimmed = q.trim().lowercase().removePrefix("#")
        if (trimmed.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        val selected = _selectedTopics.value
        _suggestions.value = allTopics.asSequence()
            .filter { it.contains(trimmed) && it !in selected }
            .sortedWith(compareBy({ !it.startsWith(trimmed) }, { it.length }, { it }))
            .take(20)
            .toList()
    }

    fun toggleTopic(topic: String) {
        val clean = topic.trim().lowercase().removePrefix("#")
        if (clean.isEmpty()) return
        val current = _selectedTopics.value
        _selectedTopics.value = if (clean in current) current - clean else current + clean
        filterSuggestions(_query.value)
    }

    fun addCustomTopic() {
        val clean = _query.value.trim().lowercase().removePrefix("#")
        if (clean.isEmpty()) return
        _selectedTopics.value = _selectedTopics.value + clean
        _query.value = ""
        _suggestions.value = emptyList()
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        _popularTopics.value = emptyList()
        _loadingPopular.value = true
        allTopics = BUILT_IN_TOPICS
        _query.value = ""
        _suggestions.value = emptyList()
        _selectedTopics.value = emptySet()
    }

    companion object {
        private const val TAG = "TopicOnboarding"
        private const val SUB_ID = "topics-trending"

        /** Shown when the trending relay has nothing, and used for search suggestions. */
        private val BUILT_IN_TOPICS = listOf(
            "nostr", "bitcoin", "art", "photography", "music", "technology", "science",
            "news", "books", "gaming", "food", "travel", "nature", "memes", "programming",
            "privacy", "foss", "linux", "android", "ai", "movies", "sports", "fitness",
            "health", "history", "philosophy", "politics", "economics", "design", "writing",
            "poetry", "cats", "dogs", "gardening", "cooking", "coffee", "running", "hiking",
            "space", "selfhosting", "lightning", "zaps", "grownostr", "introductions",
            "plebchain", "foodstr", "photostr", "artstr", "musicstr", "devstr"
        )
    }
}

package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.repo.ExtendedNetworkCache
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.SafetyPreferences
import com.wisp.app.repo.SpamVote
import com.wisp.app.ui.component.ProfilePicture
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.wisp.app.ui.theme.wispSwitchColors
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SafetyFiltersTab(
    safetyPrefs: SafetyPreferences,
    spamVotes: StateFlow<List<SpamVote>>? = null,
    onUndoSpamVote: (SpamVote) -> Unit = {},
    profileRepo: ProfileRepository? = null,
    profileVersion: StateFlow<Int>? = null,
    fetchProfile: (String) -> Unit = {},
    cachedNetwork: StateFlow<ExtendedNetworkCache?>,
    isNetworkReady: () -> Boolean,
    onNavigateToSocialGraph: () -> Unit,
    onWotToggled: () -> Unit = {}
) {
    val spamEnabled by safetyPrefs.spamFilterEnabled.collectAsState()
    val wotEnabled by safetyPrefs.wotFilterEnabled.collectAsState()
    val muteSignalEnabled by safetyPrefs.muteSignalEnabled.collectAsState()
    val followSignalEnabled by safetyPrefs.followSignalEnabled.collectAsState()
    val network by cachedNetwork.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.safety_spam_filter_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = spamEnabled,
                onCheckedChange = { safetyPrefs.setSpamFilterEnabled(it) },
                colors = wispSwitchColors()
            )
        }
        Text(
            text = stringResource(R.string.safety_spam_filter_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(12.dp))
        SpamSubToggle(
            title = stringResource(R.string.safety_mute_signal_title),
            description = stringResource(R.string.safety_mute_signal_description),
            checked = muteSignalEnabled,
            enabled = spamEnabled,
            onCheckedChange = { safetyPrefs.setMuteSignalEnabled(it) }
        )
        Spacer(Modifier.height(12.dp))
        SpamSubToggle(
            title = stringResource(R.string.safety_follow_signal_title),
            description = stringResource(R.string.safety_follow_signal_description),
            checked = followSignalEnabled,
            enabled = spamEnabled,
            onCheckedChange = { safetyPrefs.setFollowSignalEnabled(it) }
        )

        if (spamVotes != null) {
            SpamVotesList(spamVotes, onUndoSpamVote, profileRepo, profileVersion, fetchProfile)
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.safety_wot_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = wotEnabled,
                onCheckedChange = {
                    safetyPrefs.setWotFilterEnabled(it)
                    onWotToggled()
                },
                colors = wispSwitchColors()
            )
        }
        Text(
            text = stringResource(R.string.safety_wot_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(12.dp))

        val cache = network
        val ready = isNetworkReady()
        if (cache != null && ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.safety_wot_ready, cache.stats.qualifiedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val ageHours = (System.currentTimeMillis() / 1000 - cache.computedAtEpoch) / 3600
                    val ageText = if (ageHours < 1) "less than an hour"
                    else if (ageHours < 24) "$ageHours hours"
                    else "${ageHours / 24} days"
                    Text(
                        text = stringResource(R.string.safety_wot_last_computed, ageText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToSocialGraph) {
                Text(stringResource(R.string.safety_wot_recompute))
            }
        } else if (cache != null && !ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.safety_wot_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (wotEnabled) {
                Text(
                    text = stringResource(R.string.safety_wot_stale_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToSocialGraph) {
                Text(stringResource(R.string.safety_wot_recompute))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.safety_wot_not_computed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (wotEnabled) {
                Text(
                    text = stringResource(R.string.safety_wot_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToSocialGraph) {
                Text(stringResource(R.string.safety_wot_compute_now))
            }
        }
    }
}

/** The user's own "Spam" / "Not spam" votes, newest first, each with a way to take it back. */
@Composable
private fun SpamVotesList(
    spamVotes: StateFlow<List<SpamVote>>,
    onUndo: (SpamVote) -> Unit,
    profileRepo: ProfileRepository?,
    profileVersion: StateFlow<Int>?,
    fetchProfile: (String) -> Unit
) {
    val votes by spamVotes.collectAsState()
    if (votes.isEmpty()) return
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) votes else votes.take(SPAM_VOTES_COLLAPSED)
    val version = profileVersion?.collectAsState()?.value ?: 0

    LaunchedEffect(shown) {
        if (profileRepo == null) return@LaunchedEffect
        for (v in shown) {
            if (v.type == SpamVote.Type.AUTHOR && !profileRepo.has(v.key)) fetchProfile(v.key)
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.safety_spam_votes_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp)
    )
    for (vote in shown) {
        val profile = if (vote.type == SpamVote.Type.AUTHOR) {
            remember(vote.key, version) { profileRepo?.get(vote.key) }
        } else null
        val what = when (vote.type) {
            SpamVote.Type.AUTHOR -> profile?.displayString ?: vote.label
                ?: stringResource(R.string.safety_spam_votes_unnamed)
            SpamVote.Type.TEXT -> stringResource(R.string.safety_spam_votes_posts_like, vote.label ?: "")
            SpamVote.Type.DOMAIN -> stringResource(R.string.safety_spam_votes_links_to, vote.key)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp)
        ) {
            if (vote.type == SpamVote.Type.AUTHOR) {
                ProfilePicture(url = profile?.picture, size = 32)
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = what,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(if (vote.spam) R.string.btn_spam else R.string.thread_not_spam),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onUndo(vote) }) {
                Text(stringResource(R.string.safety_spam_votes_undo))
            }
        }
    }
    if (votes.size > SPAM_VOTES_COLLAPSED) {
        TextButton(
            onClick = { showAll = !showAll },
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Text(
                if (showAll) stringResource(R.string.safety_spam_votes_show_fewer)
                else stringResource(R.string.safety_spam_votes_show_all, votes.size)
            )
        }
    }
}

private const val SPAM_VOTES_COLLAPSED = 5

@Composable
private fun SpamSubToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val descColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = wispSwitchColors()
        )
    }
}

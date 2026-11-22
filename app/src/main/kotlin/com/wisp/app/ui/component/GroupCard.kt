package com.wisp.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.Nip29
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.GroupPreview

/** Last preview seen per "relay|group" in this process, so the next composition starts complete. */
private val groupPreviewMemo = android.util.LruCache<String, GroupPreview>(500)
private val MEMBERS_ROW_HEIGHT = 20.dp

@Composable
fun GroupCard(
    relayUrl: String,
    groupId: String,
    initialMetadata: Nip29.GroupMetadata? = null,
    initialMembers: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    onFetchPreview: (suspend (String, String) -> GroupPreview?)? = null,
    eventRepo: EventRepository? = null
) {
    val memoKey = "$relayUrl|$groupId"
    // Seeded from the last preview this process saw, so a card scrolled back into view starts complete.
    val memo = remember(memoKey) { groupPreviewMemo.get(memoKey) }
    var metadata by remember(relayUrl, groupId) { mutableStateOf(initialMetadata ?: memo?.metadata) }
    var members by remember(relayUrl, groupId) {
        mutableStateOf(initialMembers.ifEmpty { memo?.members ?: emptyList() })
    }
    // Decided at composition and kept: the members line is reserved unless a finished preview said
    // there are none, and the "about" line shows only if it was known from the start.
    val reserveMembers = remember(memoKey) { members.isNotEmpty() || (memo == null && onFetchPreview != null) }
    val aboutAtStart = remember(memoKey) { !metadata?.about.isNullOrEmpty() }

    LaunchedEffect(relayUrl, groupId) {
        if ((metadata == null || members.isEmpty()) && onFetchPreview != null) {
            val preview = onFetchPreview(relayUrl, groupId)
            if (preview == null) {
                // Remember the miss too, so the next composition doesn't reserve a line for nothing
                if (groupPreviewMemo.get(memoKey) == null) groupPreviewMemo.put(memoKey, GroupPreview(null, emptyList()))
                return@LaunchedEffect
            }
            groupPreviewMemo.put(memoKey, preview)
            if (metadata == null) metadata = preview.metadata
            if (members.isEmpty()) members = preview.members
        }
    }

    val host = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilePicture(url = metadata?.picture, size = 48)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = host.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metadata?.name ?: groupId,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    metadata?.about?.takeIf { it.isNotEmpty() && aboutAtStart }?.let { about ->
                        Text(
                            text = about,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            if (members.isEmpty() && reserveMembers) {
                // Members not known yet: keep their line's height so they fill it when they arrive
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(MEMBERS_ROW_HEIGHT))
            } else if (members.isNotEmpty() && reserveMembers) {
                Spacer(Modifier.height(8.dp))
                val displayed = members.take(6)
                val overflow = members.size - displayed.size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(MEMBERS_ROW_HEIGHT)
                ) {
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .width(((displayed.size - 1) * 14 + 20).dp)
                    ) {
                        displayed.forEachIndexed { index, pubkey ->
                            val picture = remember(pubkey) { eventRepo?.getProfileData(pubkey)?.picture }
                            Box(modifier = Modifier.offset(x = (index * 14).dp)) {
                                ProfilePicture(url = picture, size = 20)
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (overflow > 0) "and $overflow more in this chat room"
                               else "${members.size} in this chat room",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

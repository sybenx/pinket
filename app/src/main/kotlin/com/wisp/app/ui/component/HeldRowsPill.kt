package com.wisp.app.ui.component

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.ui.util.StableListHolder

/**
 * Shown over a list while rows are waiting for their spot to leave the screen (see
 * [StableListHolder]). Tapping it puts them in now, since the reader asked.
 */
@Composable
fun BoxScope.HeldRowsPill(
    holder: StableListHolder<*>?,
    label: String,
    modifier: Modifier = Modifier
) {
    if (holder == null || holder.held <= 0) return
    Surface(
        onClick = { holder.syncNow() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

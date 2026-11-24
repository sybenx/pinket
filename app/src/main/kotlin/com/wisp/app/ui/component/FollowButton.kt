package com.wisp.app.ui.component

import com.wisp.app.ui.theme.PinketInk
import com.wisp.app.ui.theme.TinBrush
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.ui.theme.cornerRim

@Composable
fun FollowButton(
    isFollowing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isFollowing) {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text("Following")
        }
    } else {
        // A plain Box rather than Button, so the corner rim sits on the
        // visible pill and not on Button's larger touch area.
        // Lantern look: a tin pill on a dark screen, dark ink on a light one.
        val lantern = WispThemeColors.lantern
        val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Box(
            modifier = modifier
                .clip(CircleShape)
                .then(
                    if (lantern && onDark) Modifier.background(TinBrush)
                    else if (lantern) Modifier.background(MaterialTheme.colorScheme.onSurface)
                    else Modifier
                        .background(MaterialTheme.colorScheme.primary)
                        .cornerRim(WispThemeColors.hintColor, MaterialTheme.colorScheme.primary)
                )
                .clickable(role = Role.Button, onClick = onClick)
                .defaultMinSize(minWidth = 58.dp, minHeight = 40.dp)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Follow",
                style = MaterialTheme.typography.labelLarge,
                color = if (lantern && onDark) PinketInk
                    else if (lantern) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

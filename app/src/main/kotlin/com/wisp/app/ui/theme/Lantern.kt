@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.wisp.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wisp.app.R

// The lantern look: the icon's parts carried into the app. Text and icons take
// the tin's warm off-white, names and titles are set in Caslon, and the opal
// light marks the few things that are alive (compose, unread, new posts,
// refreshing). Surfaces stay neutral gray.

/** Libre Caslon Text (SIL OFL), for names, titles and the wordmark. */
val PinketSerif = FontFamily(
    // One variable file per style: the weight has to be asked for by axis.
    Font(
        R.font.libre_caslon_text, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.libre_caslon_text, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
    Font(
        R.font.libre_caslon_text_italic, FontWeight.Normal, FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    )
)

/** A text style set in the serif, bold: names and titles. */
fun TextStyle.serifName(): TextStyle = copy(fontFamily = PinketSerif, fontWeight = FontWeight.Bold)

/** A text style set in the serif, italic: times and status lines. */
fun TextStyle.serifAside(): TextStyle =
    copy(fontFamily = PinketSerif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)

// Tin: the lantern body's off-white, and the dark ink that stands in for it on a light screen.
internal val TinDark = Color(0xFFE9E3DA)
internal val TinDimDark = Color(0xFFA39C92)
internal val TinLight = Color(0xFF1F1B17)
internal val TinDimLight = Color(0xFF6B645C)
private val TinRing = Color(0xFFD6CFC5)
private val IronRing = Color(0xFF2A2622)
private val Glass = Color(0xFF17110E)

/** Fill for a tin button on a dark screen. */
val TinBrush: Brush = Brush.linearGradient(
    0f to Color(0xFFEDE7DE), 0.55f to Color(0xFFD6CFC5), 1f to Color(0xFFA39C92)
)

private val LightStops = arrayOf(
    0f to Color(0xFFFFF3DA),
    0.22f to Color(0xFFFFC36A),
    0.48f to Color(0xFFFF8A3D),
    0.78f to Color(0xFFFF4F9A),
    1f to Color(0xFF2ED3C6)
)

// The eye carries an icon, so its middle is gold instead of near-white.
private val EyeStops = arrayOf(
    0f to Color(0xFFFFD08A),
    0.26f to Color(0xFFFFB055),
    0.5f to Color(0xFFFF8A3D),
    0.8f to Color(0xFFFF4F9A),
    1f to Color(0xFF2ED3C6)
)

private val GlowStops = arrayOf(
    0f to Color(0x59FF8A3D),
    0.5f to Color(0x26FF4F9A),
    1f to Color(0x00FF4F9A)
)

@Composable
private fun onDarkScreen(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The lantern's light as a dot. The soft glow around it is only drawn on a
 * dark screen; on a light one it turns muddy.
 */
@Composable
fun LanternLight(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    val showGlow = glow && onDarkScreen()
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        if (showGlow) {
            drawCircle(Brush.radialGradient(*GlowStops, center = center, radius = r * 2.2f), radius = r * 2.2f)
        }
        val core = Offset(this.size.width * 0.46f, this.size.height * 0.44f)
        drawCircle(Brush.radialGradient(*LightStops, center = core, radius = r * 1.08f), radius = r)
    }
}

/** The light, slowly breathing: shown while something is loading. */
@Composable
fun LanternLightPulsing(size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lanternPulse")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "lanternPulseT"
    )
    LanternLight(
        size,
        modifier.graphicsLayer {
            val s = 0.82f + 0.18f * t
            scaleX = s
            scaleY = s
            alpha = 0.75f + 0.25f * t
        }
    )
}

/**
 * The compose button as the lantern's eye: a tin ring, dark glass, and the
 * light inside carrying [icon].
 */
@Composable
fun LanternEyeButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val dark = onDarkScreen()
    Box(
        modifier = modifier
            .size(60.dp)
            .drawBehind {
                if (dark) {
                    val r = size.minDimension / 2f
                    drawCircle(Brush.radialGradient(*GlowStops, center = center, radius = r * 1.7f), radius = r * 1.7f)
                }
            }
            .clip(CircleShape)
            .background(Glass)
            .border(3.dp, if (dark) TinRing else IronRing, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Brush.radialGradient(*EyeStops), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, tint = PinketInk, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Pull-to-refresh as the light alone: it comes down with the pull and
 * breathes while the feed refreshes. [fraction] is how far the pull has gone
 * (1 = far enough to refresh).
 */
@Composable
fun LanternRefreshIndicator(fraction: () -> Float, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.graphicsLayer {
            val f = fraction().coerceIn(0f, 1.3f)
            translationY = (f * 52f - 30f) * density
            alpha = f.coerceIn(0f, 1f)
        },
        contentAlignment = Alignment.Center
    ) {
        if (isRefreshing) LanternLightPulsing(26.dp) else LanternLight(26.dp)
    }
}

/** An unlit lantern over a short line: an empty list. */
@Composable
fun UnlitLantern(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(220.dp)
        )
        androidx.compose.material3.Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = PinketSerif, fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { translationY = -28f * density }
        )
    }
}

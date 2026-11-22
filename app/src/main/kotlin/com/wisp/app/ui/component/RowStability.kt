package com.wisp.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/*
 * Row stability helpers. The rule they serve: a post row's height is decided when the row is
 * composed and does not change while it stays composed, unless the user tapped inside it. Data that
 * arrives late fills the space that is already there; the full layout is used the next time the
 * row is composed.
 */

/** Height of the one-line quote card used both while loading and when the quote arrives late. */
internal val QUOTE_STUB_HEIGHT = 48.dp

/** The outer frame of a one-line quote card (same border and spacing as the full quote cards). */
@Composable
internal fun QuoteStubCard(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        content()
    }
}

/** A single fixed-height line: [leading] then [text], ellipsized. */
@Composable
internal fun QuoteStubLine(
    text: AnnotatedString,
    leading: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(QUOTE_STUB_HEIGHT)
            .padding(horizontal = 14.dp)
    ) {
        leading()
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Loading note..." and friends, at the fixed stub height. */
@Composable
internal fun QuoteLoadingLine(label: String) {
    QuoteStubLine(text = AnnotatedString(label)) {
        CircularProgressIndicator(
            modifier = Modifier
                .width(14.dp)
                .height(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * What a quote shows when its data arrived after the row was composed: the author's picture, the
 * author's name (or a title) and the start of the text, on one line of the loading card's height.
 */
@Composable
internal fun QuoteArrivedLine(pictureUrl: String?, name: String?, text: String) {
    val nameColor = MaterialTheme.colorScheme.onSurface
    val annotated = buildAnnotatedString {
        if (!name.isNullOrBlank()) {
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold)) { append(name.trim()) }
            if (text.isNotBlank()) append("  ")
        }
        append(text)
    }
    QuoteStubLine(text = annotated) {
        ProfilePicture(url = pictureUrl, size = 20)
    }
}

/** One line of a post's text for a stub: whitespace collapsed, bounded. */
internal fun stubSnippet(content: String): String =
    content.replace(stubWhitespace, " ").trim().take(240)

private val stubWhitespace = Regex("""\s+""")

/**
 * Long text with a "Show more" cap, decided in one measure pass: the content is measured without a
 * height limit, and when it is taller than [cap] (and not [expanded]) the row is laid out as the cap
 * plus the [toggle] right away, instead of growing a button one frame later.
 */
@Composable
internal fun CappedContent(
    cap: Dp,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    fade: @Composable () -> Unit,
    toggle: @Composable () -> Unit
) {
    Layout(
        contents = listOf(
            { Box { content() } },
            { Box { fade() } },
            { Box { toggle() } }
        ),
        modifier = modifier
    ) { measurables, constraints ->
        val capPx = cap.roundToPx()
        val contentPlaceable = measurables[0].first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val exceeds = contentPlaceable.height > capPx
        val collapsed = exceeds && !expanded
        val bodyHeight = if (collapsed) capPx else contentPlaceable.height
        // With the button, take the full width so it centers in the row (as it did in the Column)
        val width = if (exceeds && constraints.hasBoundedWidth) constraints.maxWidth
            else constraints.constrainWidth(contentPlaceable.width)
        val togglePlaceable = if (exceeds) {
            measurables[2].first().measure(Constraints(maxWidth = constraints.maxWidth))
        } else null
        val fadePlaceable = if (collapsed) {
            val fadeWidth = contentPlaceable.width
            measurables[1].first().measure(Constraints(minWidth = fadeWidth, maxWidth = fadeWidth))
        } else null
        val height = constraints.constrainHeight(bodyHeight + (togglePlaceable?.height ?: 0))
        layout(width, height) {
            if (collapsed) {
                contentPlaceable.placeWithLayer(0, 0) {
                    clip = true
                    shape = TopClipShape(capPx.toFloat())
                }
            } else {
                contentPlaceable.place(0, 0)
            }
            fadePlaceable?.place(0, bodyHeight - fadePlaceable.height)
            togglePlaceable?.place((width - togglePlaceable.width) / 2, bodyHeight)
        }
    }
}

/** Clips a layer to its top [heightPx] pixels. */
private data class TopClipShape(private val heightPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, minOf(size.height, heightPx)))
}

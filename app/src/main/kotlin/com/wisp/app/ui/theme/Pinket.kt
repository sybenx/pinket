package com.wisp.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

// Pinket brand palette. Neutrals are pure gray (R=G=B) in every slot; the
// accent only ever lands on "on" roles and primary, never on a fill.
//
// material3's contentColorFor matches by color value, so no two background
// roles may share a value: the container roles sit one step off the
// surface ramp, or text on a plain gray pill would pick up the accent.

internal val PinketInk = Color(0xFF141414)

val PinketDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF4F9A),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF2D2D2D),
    onPrimaryContainer = Color(0xFFFF4F9A),
    inversePrimary = Color(0xFFC2185B),
    secondary = Color(0xFF2ED3C6),
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFF2D2D2D),
    onSecondaryContainer = Color(0xFFFF4F9A),
    tertiary = Color(0xFFFF8A3D),
    onTertiary = Color(0xFF141414),
    tertiaryContainer = Color(0xFF2D2D2D),
    onTertiaryContainer = Color(0xFFFF8A3D),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1C1C1C),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF2D2D2D),
    onErrorContainer = Color(0xFFFF6961),
    outline = Color(0xFF383838),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333333),
    surfaceContainer = Color(0xFF202020),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    surfaceContainerLow = Color(0xFF1C1C1C),
    surfaceContainerLowest = Color(0xFF060606),
    surfaceDim = Color(0xFF0A0A0A)
)

val PinketLight: ColorScheme = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E4),
    onPrimaryContainer = Color(0xFFC2185B),
    inversePrimary = Color(0xFFFF4F9A),
    secondary = Color(0xFF00756B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFFC2185B),
    tertiary = Color(0xFFA84B0C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E4E4),
    onTertiaryContainer = Color(0xFFA84B0C),
    background = Color(0xFFECECEC),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF616161),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2C2C2C),
    inverseOnSurface = Color(0xFFF0F0F0),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE4E4E4),
    onErrorContainer = Color(0xFFC4251C),
    outline = Color(0xFFCCCCCC),
    outlineVariant = Color(0xFFDCDCDC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE9E9E9),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFECECEC)
)

val PinketShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

private val OpalStopsDark = listOf(Color(0xFFFF8A3D), Color(0xFFFF4F9A), Color(0xFF2ED3C6))
private val OpalStopsLight = listOf(Color(0xFFA84B0C), Color(0xFFC2185B), Color(0xFF00756B))

// Allocated once per mode; callers share these instances. Explicit stops hold
// pink through the middle and keep the pink to turquoise blend short, so the
// muddy sRGB midpoint never spans a whole wordmark glyph.
private fun opalBrush(stops: List<Color>): Brush {
    val (orange, pink, turquoise) = stops
    return Brush.horizontalGradient(
        0f to orange,
        0.42f to pink,
        0.60f to pink,
        0.74f to turquoise,
        1f to turquoise
    )
}

private val OpalBrushDark: Brush = opalBrush(OpalStopsDark)
private val OpalBrushLight: Brush = opalBrush(OpalStopsLight)

/** The Pinket signature gradient: orange, pink, turquoise, left to right. */
object PinketOpal {
    val stopsDark: List<Color> get() = OpalStopsDark
    val stopsLight: List<Color> get() = OpalStopsLight

    fun stops(dark: Boolean): List<Color> = if (dark) OpalStopsDark else OpalStopsLight

    fun brush(dark: Boolean): Brush = if (dark) OpalBrushDark else OpalBrushLight
}

// Declared after the opal brushes: top-level vals initialize in file order.
/** Pinket extra colors (paid has no ColorScheme role). */
internal val PinketWispDark = WispColors(
    backgroundColor = Color(0xFF0A0A0A),
    zapColor = Color(0xFFFF8A3D),
    repostColor = Color(0xFF2ED3C6),
    bookmarkColor = Color(0xFFFF4F9A),
    paidColor = Color(0xFFFFC36A),
    signatureBrush = PinketOpal.brush(dark = true)
)

internal val PinketWispLight = WispColors(
    backgroundColor = Color(0xFFECECEC),
    zapColor = Color(0xFFA84B0C),
    repostColor = Color(0xFF00756B),
    bookmarkColor = Color(0xFFC2185B),
    paidColor = Color(0xFF8A6100),
    signatureBrush = PinketOpal.brush(dark = false)
)

private fun contrast(fg: Color, bg: Color): Double =
    ColorUtils.calculateContrast(fg.copy(alpha = 1f).toArgb(), bg.copy(alpha = 1f).toArgb())

/** #141414 ink when it reads better on [c] than white does, otherwise white. */
fun inkOrWhite(c: Color): Color =
    if (contrast(PinketInk, c) > contrast(Color.White, c)) PinketInk else Color.White

/**
 * Steps [c]'s HSL lightness in 0.02 steps (at most 40) until it reaches
 * [ratio] contrast against [bg]. Darkens on a light [bg], lightens on a dark one.
 */
fun ensureContrast(c: Color, bg: Color, ratio: Double): Color {
    if (contrast(c, bg) >= ratio) return c
    val darken = bg.luminance() >= 0.5f
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(c.copy(alpha = 1f).toArgb(), hsl)
    var out = c
    repeat(40) {
        hsl[2] = (hsl[2] + if (darken) -0.02f else 0.02f).coerceIn(0f, 1f)
        out = Color(ColorUtils.HSLToColor(hsl))
        if (contrast(out, bg) >= ratio) return out
    }
    return out
}

/**
 * Full 36-role scheme for a named preset (nord, dracula and the rest). The
 * preset keeps its own background, surfaces, text, outline and accents; every
 * other role is derived from them so nothing falls back to the M3 baseline.
 * [otherModePrimary] is the same preset's primary in the opposite mode
 * (used for inversePrimary).
 */
fun deriveScheme(colors: ThemeColors, dark: Boolean, otherModePrimary: Color): ColorScheme {
    val bg = colors.background
    val surface = colors.surface
    val variant = colors.surfaceVariant
    val onSurface = colors.onSurface
    val highest = lerp(variant, onSurface, 0.06f)
    val primary = colors.primary
    val secondary = colors.secondary
    val tertiary = colors.secondary
    val lowest = if (dark) lerp(bg, Color.Black, 0.3f) else lerp(surface, Color.White, 0.6f)
    val bright = if (dark) lerp(variant, onSurface, 0.10f) else lerp(surface, Color.White, 0.6f)
    val args = DerivedArgs(
        primary = primary,
        onPrimary = inkOrWhite(primary),
        container = lerp(variant, onSurface, 0.09f),
        onPrimaryContainer = primary,
        inversePrimary = otherModePrimary,
        secondary = secondary,
        onSecondary = inkOrWhite(secondary),
        onSecondaryContainer = primary,
        tertiary = tertiary,
        onTertiary = inkOrWhite(tertiary),
        onTertiaryContainer = tertiary,
        background = bg,
        onBackground = colors.onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = variant,
        onSurfaceVariant = colors.onSurfaceVariant,
        onErrorContainer = if (dark) Color(0xFFFF6961) else Color(0xFFC4251C),
        outline = colors.outline,
        outlineVariant = lerp(colors.outline, surface, 0.5f),
        surfaceBright = bright,
        surfaceContainer = lerp(surface, variant, 0.5f),
        surfaceContainerHigh = variant,
        surfaceContainerLowest = lowest
    )
    return if (dark) args.toDark() else args.toLight()
}

private class DerivedArgs(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val surfaceBright: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerLowest: Color
) {
    fun toDark(): ColorScheme = darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = container,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = container,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = Color.Transparent,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        error = Color(0xFFFF3B30),
        onError = Color.White,
        errorContainer = container,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = container,
        surfaceContainerLow = surface,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = background
    )

    fun toLight(): ColorScheme = lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = container,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = container,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = Color.Transparent,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        error = Color(0xFFFF3B30),
        onError = Color.White,
        errorContainer = container,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = container,
        surfaceContainerLow = surface,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = background
    )
}

/**
 * The "custom" theme: Pinket with the user's accent on primary and the
 * primary-derived "on" roles. Light mode darkens the accent until it reads
 * on the light background.
 */
fun customScheme(accent: Color, dark: Boolean): ColorScheme {
    val lightPrimary = ensureContrast(accent, Color(0xFFECECEC), 4.5)
    val primary = if (dark) accent else lightPrimary
    val base = if (dark) PinketDark else PinketLight
    return base.copy(
        primary = primary,
        onPrimary = inkOrWhite(primary),
        secondary = primary,
        onSecondary = inkOrWhite(primary),
        tertiary = primary,
        onTertiary = inkOrWhite(primary),
        onTertiaryContainer = primary,
        onPrimaryContainer = primary,
        onSecondaryContainer = primary,
        inversePrimary = if (dark) lightPrimary else accent
    )
}

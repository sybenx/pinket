package com.wisp.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

val LocalWispColors = androidx.compose.runtime.staticCompositionLocalOf {
    WispColors(
        backgroundColor = Color.Unspecified,
        zapColor = Color.Unspecified,
        repostColor = Color.Unspecified,
        bookmarkColor = Color.Unspecified,
        paidColor = Color.Unspecified,
        signatureBrush = SolidColor(Color.Unspecified),
        hintColor = Color.Unspecified
    )
}

data class WispColors(
    val backgroundColor: Color,
    val zapColor: Color,
    val repostColor: Color,
    val bookmarkColor: Color,
    val paidColor: Color,
    /** Opal gradient on the pinket theme, solid primary on every other theme. */
    val signatureBrush: Brush,
    val hintColor: Color
)

object WispThemeColors {
    val backgroundColor: Color @Composable get() = LocalWispColors.current.backgroundColor
    val zapColor: Color @Composable get() = LocalWispColors.current.zapColor
    val repostColor: Color @Composable get() = LocalWispColors.current.repostColor
    val bookmarkColor: Color @Composable get() = LocalWispColors.current.bookmarkColor
    val paidColor: Color @Composable get() = LocalWispColors.current.paidColor
    /** Fill for accent-filled shapes. */
    val signatureBrush: Brush @Composable get() = LocalWispColors.current.signatureBrush
    /** Small marks and corner lines (verified, liked, unread): pink on the default accent, the accent otherwise. */
    val hintColor: Color @Composable get() = LocalWispColors.current.hintColor
}

@Composable
fun wispSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    val thumb = if (ColorUtils.calculateContrast(Color.White.toArgb(), scheme.primary.copy(alpha = 1f).toArgb()) >= 2.0) {
        Color.White
    } else {
        scheme.onPrimary
    }
    return SwitchDefaults.colors(
        checkedThumbColor = thumb,
        checkedTrackColor = scheme.primary,
        checkedBorderColor = Color.Transparent,
        checkedIconColor = scheme.primary,
        uncheckedThumbColor = scheme.onSurfaceVariant,
        uncheckedTrackColor = scheme.surfaceVariant,
        uncheckedBorderColor = scheme.outline
    )
}

private val WispTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp)
)

private val WispTypographyLarge = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    bodySmall = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 13.sp)
)

@Composable
fun WispTheme(
    isDarkTheme: Boolean = true,
    accentColor: Color = PinketOrange,
    isLargeText: Boolean = false,
    themeName: String = "custom",
    content: @Composable () -> Unit
) {
    // "custom" is the neutral gray base with the user's accent; every other
    // preset derives a full 36-role scheme from its own colors, so nothing
    // falls back to the M3 baseline. Unknown names resolve to custom.
    val preset = remember(themeName) { Themes.getTheme(themeName) }
    val colorScheme = remember(preset, isDarkTheme, accentColor) {
        when (preset.name) {
            "custom" -> customScheme(accentColor, isDarkTheme)
            else -> {
                if (isDarkTheme) {
                    deriveScheme(preset.dark, dark = true, otherModePrimary = preset.light.primary)
                } else {
                    deriveScheme(preset.light, dark = false, otherModePrimary = preset.dark.primary)
                }
            }
        }
    }

    val wispColors = remember(preset, isDarkTheme, colorScheme, accentColor) {
        val pinket = if (isDarkTheme) PinketWispDark else PinketWispLight
        when (preset.name) {
            // Zap and bookmark follow the accent, repost is green.
            // The two-tone look belongs to the default accent; a chosen
            // accent stays one flat color.
            "custom" -> {
                val twoTone = accentColor.toArgb() == PinketOrange.toArgb()
                pinket.copy(
                    zapColor = colorScheme.primary,
                    repostColor = if (isDarkTheme) Color(0xFF4CAF50) else Color(0xFF2E7D32),
                    bookmarkColor = colorScheme.primary,
                    paidColor = if (isDarkTheme) Color(0xFFFFD54F) else Color(0xFFC9A000),
                    signatureBrush = SolidColor(colorScheme.primary),
                    hintColor = if (twoTone) PinketTwoTone.hint(isDarkTheme) else colorScheme.primary
                )
            }
            else -> {
                val colors = if (isDarkTheme) preset.dark else preset.light
                WispColors(
                    backgroundColor = colors.background,
                    zapColor = colors.zapColor,
                    repostColor = colors.repostColor,
                    bookmarkColor = colors.bookmarkColor,
                    paidColor = colors.paidColor,
                    signatureBrush = SolidColor(colorScheme.primary),
                    hintColor = colorScheme.primary
                )
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (isLargeText) WispTypographyLarge else WispTypography,
        content = {
            CompositionLocalProvider(LocalWispColors provides wispColors) {
                content()
            }
        }
    )
}

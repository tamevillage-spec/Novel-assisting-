package com.novelassist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF3B2A1E)
private val Paper = Color(0xFFFBF6EE)
private val Amber = Color(0xFFB8611A)
private val Leaf = Color(0xFF4F6B3A)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF3A1800),
    secondary = Leaf,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7EBC4),
    onSecondaryContainer = Color(0xFF142105),
    tertiary = Color(0xFF6B5778),
    tertiaryContainer = Color(0xFFF2DAFF),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1E4D3),
    onSurfaceVariant = Color(0xFF52443A),
    surfaceContainer = Color(0xFFF5EBDD),
    surfaceContainerHigh = Color(0xFFEFE3D2),
    outline = Color(0xFF85736A),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF552100),
    primaryContainer = Color(0xFF7A3400),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFBBCFA9),
    secondaryContainer = Color(0xFF384D26),
    onSecondaryContainer = Color(0xFFD7EBC4),
    tertiaryContainer = Color(0xFF52405F),
    background = Color(0xFF1C1510),
    onBackground = Color(0xFFEEE0D5),
    surface = Color(0xFF1C1510),
    onSurface = Color(0xFFEEE0D5),
    surfaceVariant = Color(0xFF52443A),
    onSurfaceVariant = Color(0xFFD7C3B5),
    surfaceContainer = Color(0xFF2A211A),
    surfaceContainerHigh = Color(0xFF342A22),
    outline = Color(0xFFA08D81),
)

private val Base = Typography()

private val AppTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

/** The manuscript itself is set in a book-like serif. */
val ManuscriptStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 30.sp)

@Composable
fun NovelAssistTheme(textScale: Float, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val density = LocalDensity.current
    // The author's text size setting scales every piece of text in the app.
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}

package com.ledgerflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class LedgerPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val tint: Color,
    val emeraldPrimary: Color,
    val emeraldStrong: Color,
    val emeraldSoft: Color,
    val ink: Color,
    val muted: Color,
    val roseOutflow: Color,
    val roseSoft: Color,
    val skySync: Color,
    val skySoft: Color,
    val amberWarn: Color,
    val amberSoft: Color,
    val indigoSoft: Color,
    val indigoStrong: Color,
    val outflowBar: Color,
    val calcOpContainer: Color,
    val heatmapLevels: List<Color>,
)

val LightLedgerPalette = LedgerPalette(
    isDark = false,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE2E8F0),
    tint = Color(0xFFF1F5F9),
    emeraldPrimary = Color(0xFF059669),
    emeraldStrong = Color(0xFF047857),
    emeraldSoft = Color(0xFFECFDF5),
    ink = Color(0xFF0F172A),
    muted = Color(0xFF64748B),
    roseOutflow = Color(0xFFE11D48),
    roseSoft = Color(0xFFFFF1F2),
    skySync = Color(0xFF0284C7),
    skySoft = Color(0xFFF0F9FF),
    amberWarn = Color(0xFFD97706),
    amberSoft = Color(0xFFFFFBEB),
    indigoSoft = Color(0xFFEEF2FF),
    indigoStrong = Color(0xFF4338CA),
    outflowBar = Color(0xFFCBD5E1),
    calcOpContainer = Color(0xFFE0E7FF),
    heatmapLevels = listOf(
        Color(0xFFF1F5F9),
        Color(0xFFD1FAE5),
        Color(0xFF6EE7B7),
        Color(0xFF10B981),
        Color(0xFF047857),
    ),
)

val DarkLedgerPalette = LedgerPalette(
    isDark = true,
    background = Color(0xFF0B1220),
    surface = Color(0xFF131C2E),
    border = Color(0xFF243147),
    tint = Color(0xFF1B263B),
    emeraldPrimary = Color(0xFF10B981),
    emeraldStrong = Color(0xFF34D399),
    emeraldSoft = Color(0xFF0D2F27),
    ink = Color(0xFFF1F5F9),
    muted = Color(0xFF94A3B8),
    roseOutflow = Color(0xFFFB7185),
    roseSoft = Color(0xFF3B1622),
    skySync = Color(0xFF38BDF8),
    skySoft = Color(0xFF102A43),
    amberWarn = Color(0xFFFBBF24),
    amberSoft = Color(0xFF3A280E),
    indigoSoft = Color(0xFF1E224A),
    indigoStrong = Color(0xFFA5B4FC),
    outflowBar = Color(0xFF475569),
    calcOpContainer = Color(0xFF1E2B48),
    heatmapLevels = listOf(
        Color(0xFF1B263B),
        Color(0xFF064E3B),
        Color(0xFF065F46),
        Color(0xFF059669),
        Color(0xFF10B981),
    ),
)

val LocalLedgerPalette = staticCompositionLocalOf { LightLedgerPalette }

val LedgerThemeColors: LedgerPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current

val AlabasterBg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.background

val SurfaceCard: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.surface

val BorderSubtle: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.border

val SlateTint: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.tint

val EmeraldPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.emeraldPrimary

val EmeraldDark: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.emeraldStrong

val EmeraldSoft: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.emeraldSoft

val SlateInk: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.ink

val MutedSlate: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.muted

val RoseOutflow: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.roseOutflow

val RoseSoft: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.roseSoft

val SkySync: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.skySync

val SkySoft: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.skySoft

val AmberWarn: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.amberWarn

val AmberSoft: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerPalette.current.amberSoft

val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

private val LightColors = lightColorScheme(
    primary = LightLedgerPalette.emeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = LightLedgerPalette.emeraldSoft,
    onPrimaryContainer = LightLedgerPalette.emeraldStrong,
    secondary = LightLedgerPalette.ink,
    onSecondary = Color.White,
    tertiary = LightLedgerPalette.skySync,
    onTertiary = Color.White,
    background = LightLedgerPalette.background,
    onBackground = LightLedgerPalette.ink,
    surface = LightLedgerPalette.surface,
    onSurface = LightLedgerPalette.ink,
    surfaceVariant = LightLedgerPalette.tint,
    onSurfaceVariant = LightLedgerPalette.muted,
    surfaceContainerHigh = LightLedgerPalette.surface,
    surfaceContainer = LightLedgerPalette.surface,
    surfaceContainerLow = LightLedgerPalette.background,
    inverseSurface = LightLedgerPalette.ink,
    inverseOnSurface = Color.White,
    outline = LightLedgerPalette.border,
    error = LightLedgerPalette.roseOutflow,
    errorContainer = LightLedgerPalette.roseSoft,
)

private val DarkColors = darkColorScheme(
    primary = DarkLedgerPalette.emeraldPrimary,
    onPrimary = Color(0xFF042419),
    primaryContainer = DarkLedgerPalette.emeraldSoft,
    onPrimaryContainer = DarkLedgerPalette.emeraldStrong,
    secondary = DarkLedgerPalette.ink,
    onSecondary = DarkLedgerPalette.background,
    tertiary = DarkLedgerPalette.skySync,
    onTertiary = DarkLedgerPalette.background,
    background = DarkLedgerPalette.background,
    onBackground = DarkLedgerPalette.ink,
    surface = DarkLedgerPalette.surface,
    onSurface = DarkLedgerPalette.ink,
    surfaceVariant = DarkLedgerPalette.tint,
    onSurfaceVariant = DarkLedgerPalette.muted,
    surfaceContainerHigh = DarkLedgerPalette.surface,
    surfaceContainer = DarkLedgerPalette.surface,
    surfaceContainerLow = DarkLedgerPalette.background,
    inverseSurface = DarkLedgerPalette.tint,
    inverseOnSurface = DarkLedgerPalette.ink,
    outline = DarkLedgerPalette.border,
    error = DarkLedgerPalette.roseOutflow,
    errorContainer = DarkLedgerPalette.roseSoft,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun resolveIsDarkTheme(themeMode: String): Boolean = when (themeMode.uppercase()) {
    "DARK" -> true
    "LIGHT" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun LedgerFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkLedgerPalette else LightLedgerPalette
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalLedgerPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

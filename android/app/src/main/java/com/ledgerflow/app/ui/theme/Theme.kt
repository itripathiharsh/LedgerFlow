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

object ThemeModes {
    const val SYSTEM = "SYSTEM"
    const val LIGHT = "LIGHT"
    const val FLOW_DARK = "FLOW_DARK"
    const val PREMIUM_DARK = "PREMIUM_DARK"

    fun normalize(raw: String?): String = when (raw?.trim()?.uppercase()) {
        LIGHT -> LIGHT
        FLOW_DARK, "DARK", "GREEN_DARK" -> FLOW_DARK
        PREMIUM_DARK, "BLACK", "BLACK_DARK", "AMOLED" -> PREMIUM_DARK
        else -> SYSTEM
    }

    fun displayLabel(mode: String): String = when (normalize(mode)) {
        LIGHT -> "Light"
        FLOW_DARK -> "Flow Dark"
        PREMIUM_DARK -> "Premium Black"
        else -> "System"
    }
}

@Immutable
data class LedgerPalette(
    val isDark: Boolean,
    val isBlack: Boolean,
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
    isBlack = false,
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

// Flow Mode: Emerald-tinted Slate Dark
val FlowDarkLedgerPalette = LedgerPalette(
    isDark = true,
    isBlack = false,
    background = Color(0xFF071310),
    surface = Color(0xFF0F201B),
    border = Color(0xFF1D3830),
    tint = Color(0xFF152B24),
    emeraldPrimary = Color(0xFF10B981),
    emeraldStrong = Color(0xFF34D399),
    emeraldSoft = Color(0xFF0D382B),
    ink = Color(0xFFECFDF5),
    muted = Color(0xFF8FB3A5),
    roseOutflow = Color(0xFFFB7185),
    roseSoft = Color(0xFF3B1622),
    skySync = Color(0xFF38BDF8),
    skySoft = Color(0xFF0E293D),
    amberWarn = Color(0xFFFBBF24),
    amberSoft = Color(0xFF3A280E),
    indigoSoft = Color(0xFF182A36),
    indigoStrong = Color(0xFFA5B4FC),
    outflowBar = Color(0xFF3E5E53),
    calcOpContainer = Color(0xFF17362C),
    heatmapLevels = listOf(
        Color(0xFF152B24),
        Color(0xFF064E3B),
        Color(0xFF065F46),
        Color(0xFF059669),
        Color(0xFF10B981),
    ),
)

// Premium Black: Pure OLED Matte Black
val BlackDarkLedgerPalette = LedgerPalette(
    isDark = true,
    isBlack = true,
    background = Color(0xFF000000),
    surface = Color(0xFF0D0D11),
    border = Color(0xFF23232C),
    tint = Color(0xFF16161D),
    emeraldPrimary = Color(0xFF10B981),
    emeraldStrong = Color(0xFF34D399),
    emeraldSoft = Color(0xFF08221A),
    ink = Color(0xFFFAFAFA),
    muted = Color(0xFF9CA3AF),
    roseOutflow = Color(0xFFFB7185),
    roseSoft = Color(0xFF2D0F18),
    skySync = Color(0xFF38BDF8),
    skySoft = Color(0xFF0B1E2E),
    amberWarn = Color(0xFFFBBF24),
    amberSoft = Color(0xFF2B1C08),
    indigoSoft = Color(0xFF161830),
    indigoStrong = Color(0xFFA5B4FC),
    outflowBar = Color(0xFF3F3F46),
    calcOpContainer = Color(0xFF191924),
    heatmapLevels = listOf(
        Color(0xFF16161D),
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

private fun buildColorScheme(p: LedgerPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.emeraldPrimary,
        onPrimary = Color(0xFF042419),
        primaryContainer = p.emeraldSoft,
        onPrimaryContainer = p.emeraldStrong,
        secondary = p.ink,
        onSecondary = p.background,
        secondaryContainer = p.emeraldSoft,
        onSecondaryContainer = p.emeraldStrong,
        tertiary = p.skySync,
        onTertiary = p.background,
        tertiaryContainer = p.skySoft,
        onTertiaryContainer = p.skySync,
        background = p.background,
        onBackground = p.ink,
        surface = p.surface,
        onSurface = p.ink,
        surfaceVariant = p.tint,
        onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.background,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.tint,
        inverseSurface = p.tint,
        inverseOnSurface = p.ink,
        outline = p.border,
        outlineVariant = p.border,
        error = p.roseOutflow,
        onError = Color.White,
        errorContainer = p.roseSoft,
        onErrorContainer = p.roseOutflow,
    )
} else {
    lightColorScheme(
        primary = p.emeraldPrimary,
        onPrimary = Color.White,
        primaryContainer = p.emeraldSoft,
        onPrimaryContainer = p.emeraldStrong,
        secondary = p.ink,
        onSecondary = Color.White,
        secondaryContainer = p.emeraldSoft,
        onSecondaryContainer = p.emeraldStrong,
        tertiary = p.skySync,
        onTertiary = Color.White,
        tertiaryContainer = p.skySoft,
        onTertiaryContainer = p.skySync,
        background = p.background,
        onBackground = p.ink,
        surface = p.surface,
        onSurface = p.ink,
        surfaceVariant = p.tint,
        onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.background,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.tint,
        inverseSurface = p.ink,
        inverseOnSurface = Color.White,
        outline = p.border,
        outlineVariant = p.border,
        error = p.roseOutflow,
        onError = Color.White,
        errorContainer = p.roseSoft,
        onErrorContainer = p.roseOutflow,
    )
}

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
fun resolveLedgerPalette(themeMode: String): LedgerPalette {
    val systemDark = isSystemInDarkTheme()
    return when (ThemeModes.normalize(themeMode)) {
        ThemeModes.LIGHT -> LightLedgerPalette
        ThemeModes.FLOW_DARK -> FlowDarkLedgerPalette
        ThemeModes.PREMIUM_DARK -> BlackDarkLedgerPalette
        else -> if (systemDark) FlowDarkLedgerPalette else LightLedgerPalette
    }
}

@Composable
fun resolveIsDarkTheme(themeMode: String): Boolean = resolveLedgerPalette(themeMode).isDark

@Composable
fun LedgerFlowTheme(
    palette: LedgerPalette = LightLedgerPalette,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLedgerPalette provides palette) {
        MaterialTheme(
            colorScheme = buildColorScheme(palette),
            typography = AppTypography,
            content = content,
        )
    }
}

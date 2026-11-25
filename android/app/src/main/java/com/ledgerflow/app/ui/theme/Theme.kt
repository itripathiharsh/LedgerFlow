package com.ledgerflow.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AlabasterBg = Color(0xFFF8FAFC)
val SurfaceCard = Color(0xFFFFFFFF)
val BorderSubtle = Color(0xFFE2E8F0)
val SlateTint = Color(0xFFF1F5F9)

val EmeraldPrimary = Color(0xFF059669)
val EmeraldDark = Color(0xFF047857)
val EmeraldSoft = Color(0xFFECFDF5)

val SlateInk = Color(0xFF0F172A)
val MutedSlate = Color(0xFF64748B)

val RoseOutflow = Color(0xFFE11D48)
val RoseSoft = Color(0xFFFFF1F2)

val SkySync = Color(0xFF0284C7)
val SkySoft = Color(0xFFF0F9FF)

val AmberWarn = Color(0xFFD97706)
val AmberSoft = Color(0xFFFFFBEB)

val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

private val LightColors = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = EmeraldSoft,
    onPrimaryContainer = EmeraldDark,
    secondary = SlateInk,
    onSecondary = Color.White,
    tertiary = SkySync,
    onTertiary = Color.White,
    background = AlabasterBg,
    onBackground = SlateInk,
    surface = SurfaceCard,
    onSurface = SlateInk,
    surfaceVariant = SlateTint,
    onSurfaceVariant = MutedSlate,
    inverseSurface = SlateInk,
    inverseOnSurface = Color.White,
    outline = BorderSubtle,
    error = RoseOutflow,
    errorContainer = RoseSoft,
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
fun LedgerFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}

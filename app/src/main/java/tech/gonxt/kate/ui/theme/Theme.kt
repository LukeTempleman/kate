package tech.gonxt.kate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Moneypenny design system v2: warm near-black base, soft red accent,
// rounded geometry, mono display type. (Names kept from the cyan era so the
// accent swap stays a one-file change.)
object KateColors {
    val Base = Color(0xFF0D0709)
    val Surface = Color(0xFF161014)
    val SurfaceBright = Color(0xFF201619)
    val Line = Color(0xFF2E2226)
    val Cyan = Color(0xFFFF6B7A) // soft red — primary accent
    val CyanDim = Color(0xFF9E4552)
    val CyanGlow = Color(0x33FF6B7A)
    val Text = Color(0xFFF7E9EB)
    val TextDim = Color(0xFF9C8A8E)
    val Danger = Color(0xFFFFB65C) // warnings shift to amber; red is the brand now
}

private val KateColorScheme = darkColorScheme(
    primary = KateColors.Cyan,
    onPrimary = KateColors.Base,
    secondary = KateColors.CyanDim,
    onSecondary = KateColors.Text,
    background = KateColors.Base,
    onBackground = KateColors.Text,
    surface = KateColors.Surface,
    onSurface = KateColors.Text,
    surfaceVariant = KateColors.SurfaceBright,
    onSurfaceVariant = KateColors.TextDim,
    outline = KateColors.Line,
    error = KateColors.Danger,
)

val Mono = FontFamily.Monospace

private val KateTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Light, fontSize = 44.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 1.sp),
)

@Composable
fun KateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KateColorScheme,
        typography = KateTypography,
        content = content,
    )
}

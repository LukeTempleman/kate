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

// Kate design system — locked in Iteration 1:
// near-black base, single electric-cyan accent, thin-line geometry, mono display type.
object KateColors {
    val Base = Color(0xFF050A0D)
    val Surface = Color(0xFF0A1116)
    val SurfaceBright = Color(0xFF0E1A21)
    val Line = Color(0xFF17262E)
    val Cyan = Color(0xFF00E5FF)
    val CyanDim = Color(0xFF0E7A8A)
    val CyanGlow = Color(0x3300E5FF)
    val Text = Color(0xFFD9F6FB)
    val TextDim = Color(0xFF6E8C96)
    val Danger = Color(0xFFFF5470)
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
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Light, fontSize = 44.sp, letterSpacing = 2.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 1.5.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 2.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 2.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 2.sp),
)

@Composable
fun KateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KateColorScheme,
        typography = KateTypography,
        content = content,
    )
}

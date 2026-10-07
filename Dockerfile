package com.lifetrack.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.lifetrack.R

@Immutable
data class LifePalette(
    val food: Color,
    val protein: Color,
    val fat: Color,
    val carbs: Color,
    val income: Color,
    val expense: Color,
    val warning: Color,
    val hero: Color,
    val onHero: Color,
)

val LocalLifePalette = staticCompositionLocalOf {
    LifePalette(
        Color(0xFFD4714D),
        Color(0xFF348A78),
        Color(0xFFC49037),
        Color(0xFF9A749C),
        Color(0xFF17815D),
        Color(0xFFCA6254),
        Color(0xFFB78332),
        Color(0xFF095F49),
        Color(0xFFF3F9ED),
    )
}
val lifeColors: LifePalette
    @Composable get() = LocalLifePalette.current

@OptIn(ExperimentalTextApi::class)
private val Manrope =
    FontFamily(
        Font(
            R.font.manrope,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.manrope,
            FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.manrope,
            FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.manrope,
            FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = Manrope,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = (-0.3).sp,
    )

@Composable
fun LifeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette =
        if (dark)
            LifePalette(
                Color(0xFFEFAC8C),
                Color(0xFF79C4AC),
                Color(0xFFE0B977),
                Color(0xFFC9ADD2),
                Color(0xFF8DCEAF),
                Color(0xFFEDA18F),
                Color(0xFFE0B977),
                Color(0xFF14513F),
                Color(0xFFF5FAED),
            )
        else LocalLifePalette.current
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xFF9ED9AE),
                onPrimary = Color(0xFF163D26),
                primaryContainer = Color(0xFF253E30),
                onPrimaryContainer = Color(0xFFCBE9D2),
                secondary = Color(0xFFBEBFB4),
                onSecondary = Color(0xFF272C27),
                secondaryContainer = Color(0xFF30352F),
                onSecondaryContainer = Color(0xFFE4E7DA),
                background = Color(0xFF191D1A),
                surface = Color(0xFF232823),
                surfaceVariant = Color(0xFF323932),
                surfaceContainer = Color(0xFF272D27),
                onSurface = Color(0xFFF0F1E8),
                onSurfaceVariant = Color(0xFFA8AEA3),
                outline = Color(0xFF707A6D),
                outlineVariant = Color(0xFF343D33),
                error = palette.expense,
            )
        else
            lightColorScheme(
                primary = Color(0xFF096346),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFE2EDDF),
                onPrimaryContainer = Color(0xFF145637),
                secondary = Color(0xFF69745F),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFEDEDE4),
                onSecondaryContainer = Color(0xFF414C3B),
                background = Color(0xFFF8F7F2),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFEFEEE6),
                surfaceContainer = Color(0xFFF1F0E9),
                onSurface = Color(0xFF20271F),
                onSurfaceVariant = Color(0xFF85897E),
                outline = Color(0xFFA5AB9F),
                outlineVariant = Color(0xFFE6E8DF),
                error = palette.expense,
            )
    CompositionLocalProvider(LocalLifePalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            shapes =
                Shapes(
                    extraSmall = RoundedCornerShape(8.dp),
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(16.dp),
                    large = RoundedCornerShape(20.dp),
                    extraLarge = RoundedCornerShape(24.dp),
                ),
            typography =
                Typography(
                    displayLarge = style(48, 54, FontWeight.SemiBold),
                    displayMedium = style(44, 50, FontWeight.SemiBold),
                    displaySmall = style(40, 46, FontWeight.SemiBold),
                    headlineLarge = style(32, 39, FontWeight.SemiBold),
                    headlineMedium = style(30, 37, FontWeight.SemiBold),
                    headlineSmall = style(26, 33, FontWeight.SemiBold),
                    titleLarge = style(21, 28, FontWeight.SemiBold),
                    titleMedium = style(17, 24, FontWeight.Medium),
                    titleSmall = style(15, 22, FontWeight.Medium),
                    bodyLarge = style(16, 24),
                    bodyMedium = style(15, 22),
                    bodySmall = style(13, 19),
                    labelLarge = style(14, 20, FontWeight.SemiBold),
                    labelMedium = style(12, 17, FontWeight.Medium),
                    labelSmall = style(11, 16, FontWeight.Medium),
                ),
            content = content,
        )
    }
}

package pro.noodle.mobile.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every palette defines the full role set. Previously the alternative themes only set 3–6 colours
 * and silently inherited Material baseline purple for containers, outlines and "on" colours,
 * which produced purple chips on the blue theme and low-contrast text on the light one.
 */
private fun darkPalette(
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
    tertiary: Long, tertiaryContainer: Long,
    background: Long, surface: Long, surfaceVariant: Long, surfaceHigh: Long, outline: Long,
): ColorScheme = darkColorScheme(
    primary = Color(primary), onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color(0xFF04140E),
    secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary), onTertiary = Color(0xFF201600),
    tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(0xFFFFE7A8),
    background = Color(background), onBackground = Color(0xFFF1F2F6),
    surface = Color(surface), onSurface = Color(0xFFF1F2F6),
    surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(0xFFB6BDCB),
    surfaceContainerLowest = Color(background), surfaceContainerLow = Color(surface),
    surfaceContainer = Color(surface), surfaceContainerHigh = Color(surfaceVariant), surfaceContainerHighest = Color(surfaceHigh),
    outline = Color(outline), outlineVariant = Color(surfaceHigh),
    error = Color(0xFFFF6B7A), onError = Color(0xFF2A0008), errorContainer = Color(0xFF4A1019), onErrorContainer = Color(0xFFFFD9DD),
    inverseSurface = Color(0xFFE6E7EC), inverseOnSurface = Color(0xFF16181D), inversePrimary = Color(primaryContainer),
    scrim = Color(0xFF000000),
)

private val NoodleNeon = darkPalette(
    0xFF9C6BFF, 0xFFFFFFFF, 0xFF2D1A55, 0xFFEADDFF,
    0xFF4FE3C1, 0xFF0D3328, 0xFFB7F5E4,
    0xFFFFC857, 0xFF3D2E08,
    0xFF0A0C13, 0xFF11151E, 0xFF1A202B, 0xFF242B38, 0xFF3A4252,
)
private val BlueGlass = darkPalette(
    0xFF7AAEFF, 0xFF00224D, 0xFF12305E, 0xFFD7E5FF,
    0xFF7DE0C3, 0xFF0F3A30, 0xFFC1F5E6,
    0xFFFFC46B, 0xFF3B2A06,
    0xFF0C1018, 0xFF131925, 0xFF1C2433, 0xFF263041, 0xFF3B4659,
)
private val EmeraldPulse = darkPalette(
    0xFF5EDBA4, 0xFF00311E, 0xFF0F3D2A, 0xFFC6F7DE,
    0xFF9AD4FF, 0xFF15324A, 0xFFD3EBFF,
    0xFFF5C76A, 0xFF3A2C08,
    0xFF0A1310, 0xFF111C18, 0xFF192722, 0xFF22332C, 0xFF365047,
)
private val GraphitePro = darkPalette(
    0xFFC5CFE0, 0xFF1B2230, 0xFF2E3647, 0xFFE3E9F4,
    0xFF8FD8C6, 0xFF1D3530, 0xFFCDEFE6,
    0xFFE9C27A, 0xFF392C10,
    0xFF0D0F12, 0xFF15181D, 0xFF1F2329, 0xFF292E36, 0xFF41474F,
)
private val AuroraLight = lightColorScheme(
    primary = Color(0xFF5B3FD1), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF1B0B5C),
    secondary = Color(0xFF00876C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9F2E4), onSecondaryContainer = Color(0xFF00382B),
    tertiary = Color(0xFF9A6A00), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE3A8), onTertiaryContainer = Color(0xFF301F00),
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF15171C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFE8EBF2), onSurfaceVariant = Color(0xFF4A5060),
    surfaceContainer = Color(0xFFF0F2F7), surfaceContainerHigh = Color(0xFFE9ECF3), surfaceContainerHighest = Color(0xFFE2E6EE),
    outline = Color(0xFF7A8091), outlineVariant = Color(0xFFCCD1DC),
    error = Color(0xFFC62839), onError = Color(0xFFFFFFFF),
)

private val NoodleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val NoodleTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun NoodleTheme(name: String = "noodle_neon", content: @Composable () -> Unit) {
    val colors = when (name) {
        "blue_glass" -> BlueGlass
        "emerald_pulse" -> EmeraldPulse
        "graphite_pro" -> GraphitePro
        "aurora_light" -> AuroraLight
        else -> NoodleNeon
    }
    MaterialTheme(colorScheme = colors, shapes = NoodleShapes, typography = NoodleTypography, content = content)
}

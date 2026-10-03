package pro.noodle.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NoodleNeon = darkColorScheme(
    primary = Color(0xFF9C4DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2C174A),
    onPrimaryContainer = Color(0xFFEEDCFF),
    secondary = Color(0xFF16D98C),
    onSecondary = Color(0xFF041B12),
    secondaryContainer = Color(0xFF0B3024),
    onSecondaryContainer = Color(0xFFB8F6DB),
    tertiary = Color(0xFFE3B341),
    onTertiary = Color(0xFF231A03),
    tertiaryContainer = Color(0xFF3A2D0A),
    onTertiaryContainer = Color(0xFFFFE8A3),
    background = Color(0xFF090B10),
    onBackground = Color(0xFFF2F2F6),
    surface = Color(0xFF10141B),
    onSurface = Color(0xFFF2F2F6),
    surfaceVariant = Color(0xFF171D26),
    onSurfaceVariant = Color(0xFFB8C0CE),
    outline = Color(0xFF343C49),
)

private val BlueGlass = darkColorScheme(
    primary = Color(0xFF70A7FF), secondary = Color(0xFF7DE0C3),
    background = Color(0xFF101218), surface = Color(0xFF171A22), surfaceVariant = Color(0xFF202530),
)
private val EmeraldPulse = darkColorScheme(
    primary = Color(0xFF63D6A1), secondary = Color(0xFF9DE4C7),
    background = Color(0xFF0D1512), surface = Color(0xFF14201B), surfaceVariant = Color(0xFF1C2B24),
)
private val GraphitePro = darkColorScheme(
    primary = Color(0xFFB7C3D8), secondary = Color(0xFF9EA8B8),
    background = Color(0xFF0E1013), surface = Color(0xFF171A1F), surfaceVariant = Color(0xFF22262D),
)
private val AuroraLight = lightColorScheme(
    primary = Color(0xFF365F9C), secondary = Color(0xFF287861),
    background = Color(0xFFF7F9FC), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE8EDF5),
)

@Composable
fun NoodleTheme(name: String = "noodle_neon", content: @Composable () -> Unit) {
    val colors = when (name) {
        "blue_glass" -> BlueGlass
        "emerald_pulse" -> EmeraldPulse
        "graphite_pro" -> GraphitePro
        "aurora_light" -> AuroraLight
        else -> NoodleNeon
    }
    MaterialTheme(colorScheme = colors, content = content)
}

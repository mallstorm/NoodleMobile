package pro.noodle.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
fun NoodleTheme(name: String = "blue_glass", content: @Composable () -> Unit) {
    val colors = when (name) {
        "emerald_pulse" -> EmeraldPulse
        "graphite_pro" -> GraphitePro
        "aurora_light" -> AuroraLight
        else -> BlueGlass
    }
    MaterialTheme(colorScheme = colors, content = content)
}

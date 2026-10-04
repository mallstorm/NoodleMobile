package pro.noodle.mobile.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-level chrome state shared with the root Scaffold.
 * The portrait bottom navigation stays visible during normal playback, but must disappear
 * while the player owns the whole screen. Landscape is handled independently by MainActivity.
 */
object PlayerChromeState {
    var fullscreen by mutableStateOf(false)
}

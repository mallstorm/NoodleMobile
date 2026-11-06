package pro.noodle.mobile.ui.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** "Recreate this source" request coming from a notification; consumed once by SNP Studio. */
object StudioRequest {
    data class Pending(val url: String, val query: String, val autoStart: Boolean = true)
    var pending by mutableStateOf<Pending?>(null)
}

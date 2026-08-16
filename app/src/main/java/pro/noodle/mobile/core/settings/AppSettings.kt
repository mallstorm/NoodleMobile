package pro.noodle.mobile.core.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.noodleSettingsStore by preferencesDataStore("noodle_settings")

data class AppSettings(
    val theme: String = "blue_glass",
    val parallelDownloads: Int = 2,
    val backgroundMonitoring: Boolean = false,
    val monitoringIntervalHours: Int = 24,
    val rememberPlaybackPosition: Boolean = true,
)

class AppSettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val parallelDownloads = intPreferencesKey("parallel_downloads")
        val backgroundMonitoring = booleanPreferencesKey("background_monitoring")
        val monitoringIntervalHours = intPreferencesKey("monitoring_interval_hours")
        val rememberPlaybackPosition = booleanPreferencesKey("remember_playback_position")
    }
    val settings: Flow<AppSettings> = context.noodleSettingsStore.data.map { p -> AppSettings(
        theme = p[Keys.theme] ?: "blue_glass",
        parallelDownloads = (p[Keys.parallelDownloads] ?: 2).coerceIn(1, 3),
        backgroundMonitoring = p[Keys.backgroundMonitoring] ?: false,
        monitoringIntervalHours = (p[Keys.monitoringIntervalHours] ?: 24).coerceIn(6,168),
        rememberPlaybackPosition = p[Keys.rememberPlaybackPosition] ?: true,
    ) }
    suspend fun setTheme(value: String) = context.noodleSettingsStore.edit { it[Keys.theme] = value }
    suspend fun setParallelDownloads(value: Int) = context.noodleSettingsStore.edit { it[Keys.parallelDownloads] = value.coerceIn(1,3) }
    suspend fun setBackgroundMonitoring(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.backgroundMonitoring] = value }
    suspend fun setMonitoringIntervalHours(value: Int) = context.noodleSettingsStore.edit { it[Keys.monitoringIntervalHours] = value.coerceIn(6,168) }
    suspend fun setRememberPlaybackPosition(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.rememberPlaybackPosition] = value }
}

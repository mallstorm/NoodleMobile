package pro.noodle.mobile.core.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.noodleSettingsStore by preferencesDataStore("noodle_settings")

data class AppSettings(
    val theme: String = "noodle_neon",
    val uiScalePercent: Int = 100,
    val parallelDownloads: Int = 2,
    val backgroundMonitoring: Boolean = false,
    val monitoringIntervalHours: Int = 24,
    val rememberPlaybackPosition: Boolean = true,
    val lockMode: String = "none",
    val lockSalt: String = "",
    val lockHash: String = "",
)

class AppSettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val uiScalePercent = intPreferencesKey("ui_scale_percent")
        val parallelDownloads = intPreferencesKey("parallel_downloads")
        val backgroundMonitoring = booleanPreferencesKey("background_monitoring")
        val monitoringIntervalHours = intPreferencesKey("monitoring_interval_hours")
        val rememberPlaybackPosition = booleanPreferencesKey("remember_playback_position")
        val lockMode = stringPreferencesKey("lock_mode")
        val lockSalt = stringPreferencesKey("lock_salt")
        val lockHash = stringPreferencesKey("lock_hash")
    }

    val settings: Flow<AppSettings> = context.noodleSettingsStore.data.map { p ->
        AppSettings(
            theme = p[Keys.theme] ?: "noodle_neon",
            uiScalePercent = (p[Keys.uiScalePercent] ?: 100).let { raw ->
                when {
                    raw >= 95 -> 100
                    raw >= 85 -> 90
                    else -> 80
                }
            },
            parallelDownloads = (p[Keys.parallelDownloads] ?: 2).coerceIn(1, 3),
            backgroundMonitoring = p[Keys.backgroundMonitoring] ?: false,
            monitoringIntervalHours = (p[Keys.monitoringIntervalHours] ?: 24).coerceIn(6, 168),
            rememberPlaybackPosition = p[Keys.rememberPlaybackPosition] ?: true,
            lockMode = p[Keys.lockMode] ?: "none",
            lockSalt = p[Keys.lockSalt] ?: "",
            lockHash = p[Keys.lockHash] ?: "",
        )
    }

    suspend fun setTheme(value: String) = context.noodleSettingsStore.edit { it[Keys.theme] = value }
    suspend fun setUiScalePercent(value: Int) = context.noodleSettingsStore.edit {
        it[Keys.uiScalePercent] = when {
            value >= 95 -> 100
            value >= 85 -> 90
            else -> 80
        }
    }
    suspend fun setParallelDownloads(value: Int) = context.noodleSettingsStore.edit { it[Keys.parallelDownloads] = value.coerceIn(1, 3) }
    suspend fun setBackgroundMonitoring(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.backgroundMonitoring] = value }
    suspend fun setMonitoringIntervalHours(value: Int) = context.noodleSettingsStore.edit { it[Keys.monitoringIntervalHours] = value.coerceIn(6, 168) }
    suspend fun setRememberPlaybackPosition(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.rememberPlaybackPosition] = value }
    suspend fun setAppLock(mode: String, salt: String = "", hash: String = "") = context.noodleSettingsStore.edit {
        it[Keys.lockMode] = mode
        if (salt.isBlank()) it.remove(Keys.lockSalt) else it[Keys.lockSalt] = salt
        if (hash.isBlank()) it.remove(Keys.lockHash) else it[Keys.lockHash] = hash
    }
}

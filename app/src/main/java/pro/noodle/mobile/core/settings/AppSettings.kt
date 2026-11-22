package pro.noodle.mobile.core.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.noodleSettingsStore by preferencesDataStore("noodle_settings")

data class AppSettings(
    val theme: String = "salad_sand",
    val uiScalePercent: Int = 100,
    val parallelDownloads: Int = 2,
    val backgroundMonitoring: Boolean = true,
    val monitoringIntervalHours: Int = 24,
    val rememberPlaybackPosition: Boolean = true,
    val relatedVideosColumns: Int = 2,
    val lockMode: String = "none",
    val lockSalt: String = "",
    val lockHash: String = "",
    val followedSources: Set<String> = emptySet(),
    val downloadSelectMode: Boolean = false,
    val downloadFolderUri: String = "",
    val showSourceWarnings: Boolean = false,
    val notifyNewVideos: Boolean = true,
)

/** Масштаб интерфейса: 85–100 % с шагом 5. */
fun snapScale(raw: Int): Int = ((raw.coerceIn(85, 100) + 2) / 5 * 5).coerceIn(85, 100)

class AppSettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_skin2")
        val uiScalePercent = intPreferencesKey("ui_scale_percent")
        val parallelDownloads = intPreferencesKey("parallel_downloads")
        val backgroundMonitoring = booleanPreferencesKey("background_monitoring")
        val monitoringIntervalHours = intPreferencesKey("monitoring_interval_hours")
        val rememberPlaybackPosition = booleanPreferencesKey("remember_playback_position")
        val relatedVideosColumns = intPreferencesKey("related_videos_columns")
        val lockMode = stringPreferencesKey("lock_mode")
        val lockSalt = stringPreferencesKey("lock_salt")
        val lockHash = stringPreferencesKey("lock_hash")
        val followedSources = stringSetPreferencesKey("followed_sources")
        val downloadSelectMode = booleanPreferencesKey("download_select_mode")
        val downloadFolderUri = stringPreferencesKey("download_folder_uri")
        val showSourceWarnings = booleanPreferencesKey("show_source_warnings")
        val notifyNewVideos = booleanPreferencesKey("notify_new_videos")
    }

    val settings: Flow<AppSettings> = context.noodleSettingsStore.data.map { p ->
        AppSettings(
            theme = p[Keys.theme] ?: "salad_sand",
            uiScalePercent = snapScale(p[Keys.uiScalePercent] ?: 100),
            parallelDownloads = (p[Keys.parallelDownloads] ?: 2).coerceIn(1, 10),
            backgroundMonitoring = p[Keys.backgroundMonitoring] ?: true,
            monitoringIntervalHours = (p[Keys.monitoringIntervalHours] ?: 24).coerceIn(2, 46),
            rememberPlaybackPosition = p[Keys.rememberPlaybackPosition] ?: true,
            relatedVideosColumns = (p[Keys.relatedVideosColumns] ?: 2).let { if (it == 1) 1 else 2 },
            lockMode = p[Keys.lockMode] ?: "none",
            lockSalt = p[Keys.lockSalt] ?: "",
            lockHash = p[Keys.lockHash] ?: "",
            followedSources = p[Keys.followedSources] ?: emptySet(),
            downloadSelectMode = p[Keys.downloadSelectMode] ?: false,
            downloadFolderUri = p[Keys.downloadFolderUri] ?: "",
            showSourceWarnings = p[Keys.showSourceWarnings] ?: false,
            notifyNewVideos = p[Keys.notifyNewVideos] ?: true,
        )
    }

    suspend fun setTheme(value: String) = context.noodleSettingsStore.edit { it[Keys.theme] = value }
    suspend fun setUiScalePercent(value: Int) = context.noodleSettingsStore.edit {
        it[Keys.uiScalePercent] = snapScale(value)
    }
    suspend fun setParallelDownloads(value: Int) = context.noodleSettingsStore.edit { it[Keys.parallelDownloads] = value.coerceIn(1, 10) }
    suspend fun setNotifyNewVideos(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.notifyNewVideos] = value }
    suspend fun setShowSourceWarnings(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.showSourceWarnings] = value }
    suspend fun setDownloadSelectMode(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.downloadSelectMode] = value }
    suspend fun setDownloadFolderUri(value: String) = context.noodleSettingsStore.edit { it[Keys.downloadFolderUri] = value }
    suspend fun setBackgroundMonitoring(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.backgroundMonitoring] = value }
    suspend fun setMonitoringIntervalHours(value: Int) = context.noodleSettingsStore.edit { it[Keys.monitoringIntervalHours] = value.coerceIn(2, 46) }
    suspend fun setRememberPlaybackPosition(value: Boolean) = context.noodleSettingsStore.edit { it[Keys.rememberPlaybackPosition] = value }
    suspend fun setRelatedVideosColumns(value: Int) = context.noodleSettingsStore.edit {
        it[Keys.relatedVideosColumns] = if (value == 1) 1 else 2
    }
    suspend fun setAppLock(mode: String, salt: String = "", hash: String = "") = context.noodleSettingsStore.edit {
        it[Keys.lockMode] = mode
        if (salt.isBlank()) it.remove(Keys.lockSalt) else it[Keys.lockSalt] = salt
        if (hash.isBlank()) it.remove(Keys.lockHash) else it[Keys.lockHash] = hash
    }
    suspend fun setSourceFollowed(sourceKey: String, followed: Boolean) = context.noodleSettingsStore.edit { p ->
        val next = (p[Keys.followedSources] ?: emptySet()).toMutableSet()
        if (followed) next += sourceKey else next -= sourceKey
        p[Keys.followedSources] = next
    }
}

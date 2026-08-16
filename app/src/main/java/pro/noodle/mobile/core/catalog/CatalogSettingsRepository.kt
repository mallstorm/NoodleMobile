package pro.noodle.mobile.core.catalog

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.noodle.mobile.core.settings.noodleSettingsStore

data class CatalogSettings(val indexUrl:String="", val intervalHours:Int=24)

class CatalogSettingsRepository(private val context: Context) {
    private val urlKey=stringPreferencesKey("source_catalog_index_url")
    private val intervalKey=intPreferencesKey("source_catalog_interval_hours")
    val settings:Flow<CatalogSettings> = context.noodleSettingsStore.data.map { p -> CatalogSettings(p[urlKey].orEmpty(),(p[intervalKey]?:24).coerceIn(1,168)) }
    suspend fun setIndexUrl(value:String)=context.noodleSettingsStore.edit{it[urlKey]=value.trim()}
    suspend fun setIntervalHours(value:Int)=context.noodleSettingsStore.edit{it[intervalKey]=value.coerceIn(1,168)}
}

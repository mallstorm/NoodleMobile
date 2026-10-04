package pro.noodle.mobile.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-owned source enable/disable state. It intentionally lives outside signed/bundled SNP JSON,
 * so an app/SNP update cannot silently re-enable a source the user disabled.
 *
 * forceEnabledSourceIds lets the user explicitly enable a Studio/custom SNP whose adapter.json
 * deliberately ships with enabled=false. soloSourceId is a reversible test mode: search uses only
 * that source without destroying the user's existing enable/disable choices.
 */
class SourceOverridesRepository(private val context: Context) {
    private val disabledKey = stringSetPreferencesKey("disabled_source_ids")
    private val forceEnabledKey = stringSetPreferencesKey("force_enabled_source_ids")
    private val soloKey = stringPreferencesKey("solo_source_id")

    val disabledSourceIds: Flow<Set<String>> = context.noodleSettingsStore.data.map { prefs ->
        prefs[disabledKey].orEmpty().map { it.lowercase() }.toSet()
    }

    val forceEnabledSourceIds: Flow<Set<String>> = context.noodleSettingsStore.data.map { prefs ->
        prefs[forceEnabledKey].orEmpty().map { it.lowercase() }.toSet()
    }

    val soloSourceId: Flow<String> = context.noodleSettingsStore.data.map { prefs ->
        prefs[soloKey].orEmpty().trim().lowercase()
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        val id = sourceId.trim().lowercase()
        if (id.isBlank()) return
        context.noodleSettingsStore.edit { prefs ->
            val disabled = prefs[disabledKey].orEmpty().toMutableSet()
            val forced = prefs[forceEnabledKey].orEmpty().toMutableSet()
            if (enabled) {
                disabled.remove(id)
                forced.add(id)
            } else {
                disabled.add(id)
                forced.remove(id)
                if (prefs[soloKey].orEmpty().equals(id, true)) prefs.remove(soloKey)
            }
            prefs[disabledKey] = disabled
            prefs[forceEnabledKey] = forced
        }
    }

    suspend fun clear(sourceId: String) {
        val id = sourceId.trim().lowercase()
        if (id.isBlank()) return
        context.noodleSettingsStore.edit { prefs ->
            val disabled = prefs[disabledKey].orEmpty().toMutableSet().apply { remove(id) }
            val forced = prefs[forceEnabledKey].orEmpty().toMutableSet().apply { remove(id) }
            prefs[disabledKey] = disabled
            prefs[forceEnabledKey] = forced
            if (prefs[soloKey].orEmpty().equals(id, true)) prefs.remove(soloKey)
        }
    }

    suspend fun setSoloSource(sourceId: String?) {
        val id = sourceId.orEmpty().trim().lowercase()
        context.noodleSettingsStore.edit { prefs ->
            if (id.isBlank()) prefs.remove(soloKey) else prefs[soloKey] = id
        }
    }
}

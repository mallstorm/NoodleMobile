package pro.noodle.mobile.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-owned source enable/disable state. It intentionally lives outside signed/bundled SNP JSON,
 * so an app/SNP update cannot silently re-enable a source the user disabled.
 */
class SourceOverridesRepository(private val context: Context) {
    private val disabledKey = stringSetPreferencesKey("disabled_source_ids")

    val disabledSourceIds: Flow<Set<String>> = context.noodleSettingsStore.data.map { prefs ->
        prefs[disabledKey].orEmpty().map { it.lowercase() }.toSet()
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        val id = sourceId.trim().lowercase()
        if (id.isBlank()) return
        context.noodleSettingsStore.edit { prefs ->
            val next = prefs[disabledKey].orEmpty().toMutableSet()
            if (enabled) next.remove(id) else next.add(id)
            prefs[disabledKey] = next
        }
    }
}

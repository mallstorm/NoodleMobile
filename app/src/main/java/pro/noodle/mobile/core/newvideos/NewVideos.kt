package pro.noodle.mobile.core.newvideos

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.settings.AppSettingsRepository
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.AdapterProbeService
import pro.noodle.mobile.core.source.RuntimeAdapterRepository

data class NewEntry(val video: VideoItem, val seenAtMs: Long)

/**
 * Лента «Новое»: приложение периодически сканирует включённые источники и запоминает, какие ролики
 * видит впервые. Первое сканирование источника — «база» (все его текущие ролики считаются старыми),
 * дальше каждый незнакомый ролик попадает в ленту со временем обнаружения.
 */
class NewVideosStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("noodle_new_videos", Context.MODE_PRIVATE)

    @Synchronized
    fun entries(): List<NewEntry> {
        val raw = prefs.getString(KEY_ITEMS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NewEntry(
                    VideoItem(
                        id = o.optString("id"), remoteId = o.optString("remoteId"), watchUrl = o.optString("watchUrl"),
                        title = o.optString("title"), thumbnailUrl = o.optString("thumb"), duration = o.optString("duration"),
                        views = o.optString("views"), sourceName = o.optString("source"), sourceBaseUrl = o.optString("base"),
                        sourceEngine = o.optString("engine"), playerPageUrl = o.optString("watchUrl"), favoriteKey = o.optString("fav"),
                    ),
                    o.optLong("seen"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveEntries(list: List<NewEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val v = e.video
            arr.put(JSONObject().put("id", v.id).put("remoteId", v.remoteId).put("watchUrl", v.watchUrl).put("title", v.title)
                .put("thumb", v.thumbnailUrl).put("duration", v.duration).put("views", v.views).put("source", v.sourceName)
                .put("base", v.sourceBaseUrl).put("engine", v.sourceEngine).put("fav", v.favoriteKey).put("seen", e.seenAtMs))
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    @Synchronized fun seen(): MutableSet<String> = prefs.getString(KEY_SEEN, "").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableSet()
    @Synchronized fun saveSeen(set: Collection<String>) { prefs.edit().putString(KEY_SEEN, set.toList().takeLast(MAX_SEEN).joinToString("\n")).apply() }
    fun hasBaseline(sourceId: String) = prefs.getBoolean("base_$sourceId", false)
    fun setBaseline(sourceId: String) { prefs.edit().putBoolean("base_$sourceId", true).apply() }
    var lastScanMs: Long
        get() = prefs.getLong(KEY_LAST, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST, v).apply() }
    var lastSources: Int
        get() = prefs.getInt(KEY_SRC, 0)
        set(v) { prefs.edit().putInt(KEY_SRC, v).apply() }

    private companion object {
        const val KEY_ITEMS = "items"; const val KEY_SEEN = "seen"; const val KEY_LAST = "last_scan"; const val KEY_SRC = "last_sources"
        const val MAX_SEEN = 12_000
    }
}

class NewVideosScanner(private val context: Context) {
    private val store = NewVideosStore(context)

    /** Возвращает число обнаруженных новых роликов. */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val settings = AppSettingsRepository(context).settings.first()
        val repo = RuntimeAdapterRepository(context)
        val overrides = SourceOverridesRepository(context)
        val disabled = overrides.disabledSourceIds.first()
        val forced = overrides.forceEnabledSourceIds.first()
        val probe = AdapterProbeService(context, repo)
        var adapters = repo.listAll().filter { r ->
            val id = r.definition.id.lowercase()
            r.compatibility.compatible && r.packageInfo.valid && (r.definition.enabled || id in forced) && id !in disabled
        }.map { it.definition }
        val followed = adapters.filter { it.id in settings.followedSources }
        if (followed.isNotEmpty()) adapters = followed

        val gate = Semaphore(3)
        val results = coroutineScope {
            adapters.map { d ->
                async {
                    gate.withPermit {
                        d to (withTimeoutOrNull(40_000L) {
                            runCatching { probe.searchOne(d, d.healthQuery.ifBlank { "video" }, 40).first }.getOrDefault(emptyList())
                        } ?: emptyList())
                    }
                }
            }.awaitAll()
        }

        val now = System.currentTimeMillis()
        val seen = store.seen()
        val entries = store.entries().toMutableList()
        var found = 0
        for ((d, items) in results) {
            if (items.isEmpty()) continue
            val baseline = store.hasBaseline(d.id)
            for (v in items) {
                val key = (d.id + "|" + v.watchUrl).lowercase()
                if (!seen.add(key)) continue
                if (baseline) { entries += NewEntry(v, now); found++ }
            }
            store.setBaseline(d.id)
        }
        // Храним неделю и не больше 500 записей.
        val kept = entries.filter { now - it.seenAtMs <= 7L * 24 * 3600_000 }.sortedByDescending { it.seenAtMs }.take(500)
        store.saveEntries(kept)
        store.saveSeen(seen)
        store.lastScanMs = now
        store.lastSources = results.count { it.second.isNotEmpty() }
        found
    }
}

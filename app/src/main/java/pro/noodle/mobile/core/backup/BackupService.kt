package pro.noodle.mobile.core.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.settings.noodleSettingsStore
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.storage.UserMediaRepository
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupResult(val ok: Boolean, val message: String)

/**
 * Резервная копия одним файлом (.zip): избранное, история, пользовательские адаптеры, настройки
 * (включая включённые/выключенные источники), правила «не то видео». Учётные данные нейросетей не сохраняются.
 */
class BackupService(context: Context) {
    private val app = context.applicationContext
    private val user = UserMediaRepository(app)
    private val adapters = RuntimeAdapterRepository(app)

    suspend fun export(target: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val favorites = user.favorites().first()
            val history = user.history().first()
            val settings = app.noodleSettingsStore.data.first().asMap()
            val custom = adapters.listAll().filter { !it.bundled }
            val out = app.contentResolver.openOutputStream(target, "wt") ?: error("Не удалось открыть файл для записи")
            ZipOutputStream(out.buffered()).use { zip ->
                fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                put("manifest.json", JSONObject().put("format", 1).put("app", BuildIdentity.VERSION_NAME).put("created", System.currentTimeMillis()).toString(2).toByteArray())
                put("favorites.json", JSONArray().also { a -> favorites.forEach { a.put(videoJson(it)) } }.toString().toByteArray())
                put("history.json", JSONArray().also { a -> history.forEach { h ->
                    a.put(JSONObject().put("video", videoJson(h.video)).put("pos", h.positionMs).put("dur", h.durationMs).put("at", h.updatedAt))
                } }.toString().toByteArray())
                put("settings.json", settingsJson(settings).toString().toByteArray())
                for (name in listOf("noodle_stream_rules")) {
                    val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    val o = JSONObject(); all.forEach { (k, v) -> if (v is String) o.put(k, v) }
                    put("prefs/$name.json", o.toString().toByteArray())
                }
                for (a in custom) {
                    val tmp = File(app.cacheDir, "bk-${a.definition.id}.zip")
                    if (adapters.exportAdapter(a.definition.id, tmp)) put("adapters/${a.definition.id}.zip", tmp.readBytes())
                    tmp.delete()
                }
            }
            BackupResult(true, "Копия сохранена: избранное ${favorites.size}, история ${history.size}, адаптеры ${custom.size}.")
        }.getOrElse { BackupResult(false, "Не удалось сохранить копию: ${it.message ?: it.javaClass.simpleName}") }
    }

    suspend fun restore(source: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val entries = LinkedHashMap<String, ByteArray>()
            var total = 0L
            val input = app.contentResolver.openInputStream(source) ?: error("Не удалось открыть файл")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val buf = ByteArrayOutputStream()
                    zip.copyTo(buf)
                    total += buf.size()
                    if (total > 128L * 1024 * 1024) error("Файл слишком большой")
                    entries[e.name] = buf.toByteArray()
                }
            }
            val manifest = entries["manifest.json"]?.let { JSONObject(String(it)) } ?: return@runCatching BackupResult(false, "Это не резервная копия Noodle (нет manifest.json).")
            if (manifest.optInt("format") != 1) return@runCatching BackupResult(false, "Неподдерживаемый формат копии.")

            var fav = 0; var hist = 0; var ad = 0; var cfg = 0
            entries["favorites.json"]?.let { raw ->
                val arr = JSONArray(String(raw))
                for (i in 0 until arr.length()) { user.addFavorite(videoFrom(arr.getJSONObject(i))); fav++ }
            }
            entries["history.json"]?.let { raw ->
                val arr = JSONArray(String(raw))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    user.saveHistory(videoFrom(o.getJSONObject("video")), o.optLong("pos"), o.optLong("dur")); hist++
                }
            }
            entries.filterKeys { it.startsWith("adapters/") && it.endsWith(".zip") }.forEach { (name, bytes) ->
                val tmp = File(app.cacheDir, "restore-${name.substringAfterLast('/')}")
                tmp.writeBytes(bytes)
                if (adapters.importCustomZip(tmp).success) ad++
                tmp.delete()
            }
            entries["prefs/noodle_stream_rules.json"]?.let { raw ->
                val o = JSONObject(String(raw))
                val ed = app.getSharedPreferences("noodle_stream_rules", Context.MODE_PRIVATE).edit()
                o.keys().forEach { k -> ed.putString(k, o.optString(k)) }
                ed.apply()
            }
            entries["settings.json"]?.let { raw -> cfg = applySettings(JSONArray(String(raw))) }
            BackupResult(true, "Восстановлено: избранное $fav, история $hist, адаптеры $ad, настроек $cfg. Перезапустите приложение, чтобы все параметры применились.")
        }.getOrElse { BackupResult(false, "Не удалось восстановить: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun videoJson(v: VideoItem) = JSONObject()
        .put("id", v.id).put("remoteId", v.remoteId).put("watchUrl", v.watchUrl).put("title", v.title)
        .put("thumb", v.thumbnailUrl).put("duration", v.duration).put("views", v.views)
        .put("source", v.sourceName).put("base", v.sourceBaseUrl).put("engine", v.sourceEngine)
        .put("player", v.playerPageUrl).put("fav", v.favoriteKey).put("note", v.note)
        .put("tags", JSONArray(v.tags)).put("favTs", v.favoriteTimestamp)

    private fun videoFrom(o: JSONObject) = VideoItem(
        id = o.optString("id"), remoteId = o.optString("remoteId"), watchUrl = o.optString("watchUrl"), title = o.optString("title"),
        thumbnailUrl = o.optString("thumb"), duration = o.optString("duration"), views = o.optString("views"),
        sourceName = o.optString("source"), sourceBaseUrl = o.optString("base"), sourceEngine = o.optString("engine"),
        playerPageUrl = o.optString("player"), favoriteKey = o.optString("fav"), note = o.optString("note"),
        tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        favoriteTimestamp = o.optLong("favTs"),
    )

    private fun settingsJson(map: Map<Preferences.Key<*>, Any>): JSONArray {
        val arr = JSONArray()
        map.forEach { (k, v) ->
            val o = JSONObject().put("k", k.name)
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            arr.put(o)
        }
        return arr
    }

    private suspend fun applySettings(arr: JSONArray): Int {
        var n = 0
        app.noodleSettingsStore.edit { p ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("k")
                when (o.optString("t")) {
                    "b" -> p[booleanPreferencesKey(name)] = o.getBoolean("v")
                    "i" -> p[intPreferencesKey(name)] = o.getInt("v")
                    "l" -> p[longPreferencesKey(name)] = o.getLong("v")
                    "f" -> p[floatPreferencesKey(name)] = o.getDouble("v").toFloat()
                    "s" -> p[stringPreferencesKey(name)] = o.getString("v")
                    "ss" -> p[stringSetPreferencesKey(name)] = o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> continue
                }
                n++
            }
        }
        return n
    }
}

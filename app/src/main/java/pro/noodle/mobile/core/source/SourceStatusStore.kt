package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.json.*
import java.io.File

class SourceStatusStore(private val file: File) {
    fun get(adapterId: String, field: String): String {
        val root = readRoot()
        val item = root.obj(adapterId.lowercase()) ?: return ""
        return item.string(field).orEmpty()
    }

    fun getLong(adapterId: String, field: String): Long {
        val item = readRoot().obj(adapterId.lowercase()) ?: return 0L
        return (item.values[field] as? JsonValue.Num)?.value?.toLong() ?: 0L
    }

    fun getInt(adapterId: String, field: String): Int = getLong(adapterId, field).toInt()

    fun put(adapterId: String, values: Map<String, JsonValue>) {
        file.parentFile?.mkdirs()
        val root = LinkedHashMap(readRoot().values)
        val old = (root[adapterId.lowercase()] as? JsonValue.Obj)?.values.orEmpty()
        val merged = LinkedHashMap(old)
        merged.putAll(values)
        merged["updated_at"] = JsonValue.Num(System.currentTimeMillis().toDouble())
        root[adapterId.lowercase()] = JsonValue.Obj(merged)
        file.writeText(JsonValue.Obj(root).toJson(pretty = true), Charsets.UTF_8)
    }

    private fun readRoot(): JsonValue.Obj = runCatching {
        if (!file.isFile) JsonValue.Obj(linkedMapOf()) else MiniJson.parse(file.readText(Charsets.UTF_8)) as? JsonValue.Obj ?: JsonValue.Obj(linkedMapOf())
    }.getOrDefault(JsonValue.Obj(linkedMapOf()))
}

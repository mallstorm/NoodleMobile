package pro.noodle.mobile.ui.player

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class AsrTranscriptCache(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "ru-dubbing-transcripts").apply { mkdirs() }

    fun load(
        mediaKey: String,
        startMs: Long,
        endMs: Long,
        model: String,
    ): List<AsrSegment>? {
        val file = fileFor(mediaKey, startMs, endMs, model)
        if (!file.isFile) return null
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        AsrSegment(
                            startMs = item.getLong("startMs"),
                            endMs = item.getLong("endMs"),
                            text = item.getString("text"),
                            speaker = item.optString("speaker"),
                        )
                    )
                }
            }
        }.getOrNull()
    }

    fun save(
        mediaKey: String,
        startMs: Long,
        endMs: Long,
        model: String,
        segments: List<AsrSegment>,
    ) {
        runCatching {
            val array = JSONArray()
            segments.forEach { segment ->
                array.put(
                    JSONObject()
                        .put("startMs", segment.startMs)
                        .put("endMs", segment.endMs)
                        .put("text", segment.text)
                        .put("speaker", segment.speaker)
                )
            }
            val target = fileFor(mediaKey, startMs, endMs, model)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        }
    }

    private fun fileFor(mediaKey: String, startMs: Long, endMs: Long, model: String): File {
        val raw = "$mediaKey|$startMs|$endMs|$model"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }
}

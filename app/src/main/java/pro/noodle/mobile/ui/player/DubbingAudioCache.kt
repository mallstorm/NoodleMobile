package pro.noodle.mobile.ui.player

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Persistent, content-addressed cache for synthesized Russian ASR speech segments. */
internal class DubbingAudioCache(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    fun targetFile(
        mediaKey: String,
        model: String,
        voiceName: String,
        segment: AsrSegment,
        russianText: String,
    ): File {
        val raw = buildString {
            append(CACHE_VERSION).append('|')
            append(mediaKey).append('|')
            append(model).append('|')
            append(voiceName).append('|')
            append(segment.startMs).append('|')
            append(segment.endMs).append('|')
            append(segment.speaker).append('|')
            append(segment.text).append('|')
            append(russianText)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.wav")
    }

    fun existing(target: File): File? = target
        .takeIf { it.isFile && it.length() >= MIN_VALID_BYTES }
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    fun tempFile(target: File): File =
        File(directory, "${target.name}.${System.nanoTime()}.part")

    fun commit(temp: File, target: File): File {
        require(temp.isFile && temp.length() >= MIN_VALID_BYTES) { "RU_TTS_RENDER_EMPTY" }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    fun prune() {
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .forEach { it.delete() }

        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("wav", true) }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return

        for (file in files) {
            if (total <= TARGET_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private companion object {
        const val DIRECTORY = "ru-dubbing-audio"
        const val CACHE_VERSION = "v2"
        const val MIN_VALID_BYTES = 256L
        const val MAX_CACHE_BYTES = 192L * 1024L * 1024L
        const val TARGET_CACHE_BYTES = 128L * 1024L * 1024L
    }
}

package pro.noodle.mobile.ui.player

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Builds one clock-aligned PCM WAV timeline for a speech-first dubbing chunk.
 *
 * Each prepared TTS file is placed at the ASR segment's absolute media time. Silence is retained
 * between phrases, overlaps are mixed with saturation, and short fades prevent hard PCM clicks.
 */
internal class DubbingTimelineAssembler private constructor(
    private val directory: File,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }
    )

    internal constructor(directory: File, createDirectory: Boolean) : this(
        directory.apply { if (createDirectory) mkdirs() }
    )

    data class SpeechFile(
        val segment: AsrSegment,
        val file: File,
    )

    fun assemble(
        mediaKey: String,
        chunkStartMs: Long,
        chunkEndMs: Long,
        speech: List<SpeechFile>,
    ): File {
        require(chunkEndMs > chunkStartMs) { "RU_TIMELINE_INVALID_RANGE" }
        require(speech.isNotEmpty()) { "RU_TIMELINE_EMPTY" }

        val ordered = speech
            .filter { it.file.isFile }
            .sortedBy { it.segment.startMs }
        require(ordered.isNotEmpty()) { "RU_TIMELINE_NO_AUDIO" }

        val target = targetFile(mediaKey, chunkStartMs, chunkEndMs, ordered)
        existing(target)?.let { return it }

        val sources = ordered.map { item -> item to readWave(item.file) }
        val format = sources.first().second.format
        require(format.audioFormat == PCM_FORMAT) { "RU_TIMELINE_PCM_REQUIRED" }
        require(format.bitsPerSample == 16) { "RU_TIMELINE_16BIT_REQUIRED" }
        require(format.channels in 1..2) { "RU_TIMELINE_CHANNELS_UNSUPPORTED" }
        require(format.sampleRate in 8_000..96_000) { "RU_TIMELINE_RATE_UNSUPPORTED" }
        sources.forEach { (_, wave) ->
            require(wave.format == format) { "RU_TIMELINE_FORMAT_MISMATCH" }
        }

        val durationMs = chunkEndMs - chunkStartMs
        val totalFrames = ((durationMs * format.sampleRate) / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(MAX_TIMELINE_FRAMES.toLong())
            .toInt()
        val totalSamples = Math.multiplyExact(totalFrames, format.channels)
        val mix = IntArray(totalSamples)

        for ((item, wave) in sources) {
            mixSource(
                mix = mix,
                outputFrames = totalFrames,
                format = format,
                chunkStartMs = chunkStartMs,
                chunkEndMs = chunkEndMs,
                segment = item.segment,
                wave = wave,
            )
        }

        val temp = File(directory, "${target.name}.${System.nanoTime()}.part")
        try {
            writeWave(temp, format, mix, totalFrames)
            require(temp.length() >= MIN_VALID_BYTES) { "RU_TIMELINE_WRITE_EMPTY" }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            prune()
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun mixSource(
        mix: IntArray,
        outputFrames: Int,
        format: WaveFormat,
        chunkStartMs: Long,
        chunkEndMs: Long,
        segment: AsrSegment,
        wave: WaveData,
    ) {
        val visibleStartMs = maxOf(segment.startMs, chunkStartMs)
        val visibleEndMs = minOf(segment.endMs, chunkEndMs)
        if (visibleEndMs <= visibleStartMs) return

        val sourceSkipMs = (visibleStartMs - segment.startMs).coerceAtLeast(0L)
        val outputOffsetMs = visibleStartMs - chunkStartMs
        val sourceStartFrame = ((sourceSkipMs * format.sampleRate) / 1_000L).toInt()
        val outputStartFrame = ((outputOffsetMs * format.sampleRate) / 1_000L).toInt()
        val segmentFrames = (((visibleEndMs - visibleStartMs) * format.sampleRate) / 1_000L).toInt()
        val sourceFrames = wave.pcm.size / format.blockAlign
        val frames = minOf(
            segmentFrames,
            sourceFrames - sourceStartFrame,
            outputFrames - outputStartFrame,
        )
        if (frames <= 0) return

        val fadeFrames = minOf((format.sampleRate * FADE_MS / 1_000), frames / 2)
        for (frame in 0 until frames) {
            val gain = when {
                fadeFrames <= 0 -> 1f
                frame < fadeFrames -> frame.toFloat() / fadeFrames.toFloat()
                frame >= frames - fadeFrames -> (frames - 1 - frame).coerceAtLeast(0).toFloat() / fadeFrames.toFloat()
                else -> 1f
            }
            val sourceFrame = sourceStartFrame + frame
            val outputFrame = outputStartFrame + frame
            for (channel in 0 until format.channels) {
                val sourceByte = sourceFrame * format.blockAlign + channel * 2
                val sample = littleEndianShort(wave.pcm, sourceByte)
                val sampleIndex = outputFrame * format.channels + channel
                val mixed = mix[sampleIndex] + (sample * gain).roundToInt()
                mix[sampleIndex] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            }
        }
    }

    private fun readWave(file: File): WaveData {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 44L) { "RU_TIMELINE_WAV_TOO_SMALL" }
            require(readFourCc(input) == "RIFF") { "RU_TIMELINE_RIFF_REQUIRED" }
            input.skipBytes(4)
            require(readFourCc(input) == "WAVE") { "RU_TIMELINE_WAVE_REQUIRED" }

            var format: WaveFormat? = null
            var pcm: ByteArray? = null
            while (input.filePointer + 8L <= input.length()) {
                val chunkId = readFourCc(input)
                val chunkSize = readLeInt(input).toLong() and 0xffffffffL
                val chunkStart = input.filePointer
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "RU_TIMELINE_FMT_INVALID" }
                        val audioFormat = readLeShort(input)
                        val channels = readLeShort(input)
                        val sampleRate = readLeInt(input)
                        input.skipBytes(6) // byteRate + blockAlign are recomputed and validated below.
                        val bitsPerSample = readLeShort(input)
                        val blockAlign = channels * (bitsPerSample / 8)
                        require(blockAlign > 0) { "RU_TIMELINE_BLOCK_ALIGN_INVALID" }
                        format = WaveFormat(audioFormat, channels, sampleRate, bitsPerSample, blockAlign)
                    }
                    "data" -> {
                        require(chunkSize <= MAX_SOURCE_BYTES) { "RU_TIMELINE_WAV_TOO_LARGE" }
                        pcm = ByteArray(chunkSize.toInt()).also { input.readFully(it) }
                    }
                }
                val next = chunkStart + chunkSize + (chunkSize and 1L)
                if (next > input.length()) break
                input.seek(next)
                if (format != null && pcm != null) break
            }
            return WaveData(
                format = requireNotNull(format) { "RU_TIMELINE_FMT_MISSING" },
                pcm = requireNotNull(pcm) { "RU_TIMELINE_DATA_MISSING" },
            )
        }
    }

    private fun writeWave(file: File, format: WaveFormat, samples: IntArray, frames: Int) {
        val dataBytes = Math.multiplyExact(Math.multiplyExact(frames, format.channels), 2)
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.writeBytes("RIFF")
            writeLeInt(output, 36 + dataBytes)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeLeInt(output, 16)
            writeLeShort(output, PCM_FORMAT)
            writeLeShort(output, format.channels)
            writeLeInt(output, format.sampleRate)
            writeLeInt(output, format.sampleRate * format.blockAlign)
            writeLeShort(output, format.blockAlign)
            writeLeShort(output, 16)
            output.writeBytes("data")
            writeLeInt(output, dataBytes)
            for (sample in samples) {
                writeLeShort(output, sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            }
        }
    }

    private fun targetFile(
        mediaKey: String,
        chunkStartMs: Long,
        chunkEndMs: Long,
        speech: List<SpeechFile>,
    ): File {
        val raw = buildString {
            append(CACHE_VERSION).append('|')
            append(mediaKey).append('|').append(chunkStartMs).append('|').append(chunkEndMs)
            speech.forEach { item ->
                append('|').append(item.segment.startMs)
                append(':').append(item.segment.endMs)
                append(':').append(item.file.name)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.wav")
    }

    private fun existing(file: File): File? = file
        .takeIf { it.isFile && it.length() >= MIN_VALID_BYTES }
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    private fun prune() {
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .forEach { it.delete() }
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files) {
            if (total <= TARGET_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun readFourCc(input: RandomAccessFile): String =
        ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)

    private fun readLeShort(input: RandomAccessFile): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun readLeInt(input: RandomAccessFile): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        val c = input.readUnsignedByte()
        val d = input.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    private fun writeLeShort(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLeInt(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xff
        val hi = bytes[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private data class WaveData(
        val format: WaveFormat,
        val pcm: ByteArray,
    )

    private data class WaveFormat(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
    )

    private companion object {
        const val DIRECTORY = "ru-dubbing-timeline"
        const val CACHE_VERSION = "v1"
        const val PCM_FORMAT = 1
        const val FADE_MS = 8
        const val MIN_VALID_BYTES = 256L
        const val MAX_SOURCE_BYTES = 32L * 1024L * 1024L
        const val MAX_TIMELINE_FRAMES = 96_000 * 30
        const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        const val TARGET_CACHE_BYTES = 160L * 1024L * 1024L
    }
}

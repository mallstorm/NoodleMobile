package pro.noodle.mobile.ui.player

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Builds clock-aligned PCM WAV timeline chunks for speech-first Russian dubbing.
 *
 * Every output chunk uses one canonical PCM format (24 kHz, mono, 16-bit). Prepared TTS files are
 * resampled/downmixed into that format, which lets Media3 queue adjacent chunks without decoder
 * reconfiguration at every boundary. Empty speech windows are valid and become pure-silence chunks.
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

        val ordered = speech
            .filter { it.file.isFile }
            .sortedBy { it.segment.startMs }
        val target = targetFile(mediaKey, chunkStartMs, chunkEndMs, ordered)
        existing(target)?.let { return it }

        val sources = ordered.map { item -> item to readWave(item.file) }
        sources.forEach { (_, wave) -> validateSourceFormat(wave.format) }

        val format = OUTPUT_FORMAT
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
                outputFormat = format,
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

    private fun validateSourceFormat(format: WaveFormat) {
        require(format.audioFormat == PCM_FORMAT) { "RU_TIMELINE_PCM_REQUIRED" }
        require(format.bitsPerSample == 16) { "RU_TIMELINE_16BIT_REQUIRED" }
        require(format.channels in 1..2) { "RU_TIMELINE_CHANNELS_UNSUPPORTED" }
        require(format.sampleRate in 8_000..96_000) { "RU_TIMELINE_RATE_UNSUPPORTED" }
    }

    private fun mixSource(
        mix: IntArray,
        outputFrames: Int,
        outputFormat: WaveFormat,
        chunkStartMs: Long,
        chunkEndMs: Long,
        segment: AsrSegment,
        wave: WaveData,
    ) {
        val visibleStartMs = maxOf(segment.startMs, chunkStartMs)
        val visibleEndMs = minOf(segment.endMs, chunkEndMs)
        if (visibleEndMs <= visibleStartMs) return

        val sourceFormat = wave.format
        val sourceFrames = wave.pcm.size / sourceFormat.blockAlign
        if (sourceFrames <= 0) return

        val sourceSkipMs = (visibleStartMs - segment.startMs).coerceAtLeast(0L)
        val outputOffsetMs = visibleStartMs - chunkStartMs
        val sourceStartFrame = ((sourceSkipMs * sourceFormat.sampleRate) / 1_000L).toInt()
        val outputStartFrame = ((outputOffsetMs * outputFormat.sampleRate) / 1_000L).toInt()
        val segmentOutputFrames = (((visibleEndMs - visibleStartMs) * outputFormat.sampleRate) / 1_000L)
            .toInt()
        val sourceFramesRemaining = (sourceFrames - sourceStartFrame).coerceAtLeast(0)
        val maxOutputFramesFromSource = (
            sourceFramesRemaining.toLong() * outputFormat.sampleRate / sourceFormat.sampleRate
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val frames = minOf(
            segmentOutputFrames,
            maxOutputFramesFromSource,
            outputFrames - outputStartFrame,
        )
        if (frames <= 0) return

        val fadeFrames = minOf((outputFormat.sampleRate * FADE_MS / 1_000), frames / 2)
        val sourceStep = sourceFormat.sampleRate.toDouble() / outputFormat.sampleRate.toDouble()
        for (frame in 0 until frames) {
            val gain = when {
                fadeFrames <= 0 -> 1f
                frame < fadeFrames -> frame.toFloat() / fadeFrames.toFloat()
                frame >= frames - fadeFrames ->
                    (frames - 1 - frame).coerceAtLeast(0).toFloat() / fadeFrames.toFloat()
                else -> 1f
            }

            val sourcePosition = sourceStartFrame.toDouble() + frame.toDouble() * sourceStep
            val sample = interpolatedMonoSample(wave, sourcePosition)
            val sampleIndex = outputStartFrame + frame
            val mixed = mix[sampleIndex] + (sample * gain).roundToInt()
            mix[sampleIndex] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }

    private fun interpolatedMonoSample(wave: WaveData, position: Double): Float {
        val sourceFrames = wave.pcm.size / wave.format.blockAlign
        if (sourceFrames <= 0) return 0f
        val left = position.toInt().coerceIn(0, sourceFrames - 1)
        val right = (left + 1).coerceAtMost(sourceFrames - 1)
        val fraction = (position - left.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val leftSample = monoSampleAt(wave, left)
        val rightSample = monoSampleAt(wave, right)
        return leftSample + (rightSample - leftSample) * fraction
    }

    private fun monoSampleAt(wave: WaveData, frame: Int): Float {
        val format = wave.format
        var sum = 0
        for (channel in 0 until format.channels) {
            val sourceByte = frame * format.blockAlign + channel * 2
            sum += littleEndianShort(wave.pcm, sourceByte)
        }
        return sum.toFloat() / format.channels.toFloat()
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
                        input.skipBytes(6)
                        val bitsPerSample = readLeShort(input)
                        val blockAlign = channels * (bitsPerSample / 8)
                        require(blockAlign > 0) { "RU_TIMELINE_BLOCK_ALIGN_INVALID" }
                        format = WaveFormat(
                            audioFormat = audioFormat,
                            channels = channels,
                            sampleRate = sampleRate,
                            bitsPerSample = bitsPerSample,
                            blockAlign = blockAlign,
                        )
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
                writeLeShort(
                    output,
                    sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
                )
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
            append('|').append(OUTPUT_SAMPLE_RATE).append('|').append(OUTPUT_CHANNELS)
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
        if (offset < 0 || offset + 1 >= bytes.size) return 0
        val low = bytes[offset].toInt() and 0xff
        val high = bytes[offset + 1].toInt()
        return ((high shl 8) or low).toShort().toInt()
    }

    private data class WaveFormat(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
    )

    private data class WaveData(
        val format: WaveFormat,
        val pcm: ByteArray,
    )

    private companion object {
        const val DIRECTORY = "ru-dubbing-timeline"
        const val CACHE_VERSION = "v2-gapless-24k-mono"
        const val PCM_FORMAT = 1
        const val OUTPUT_SAMPLE_RATE = 24_000
        const val OUTPUT_CHANNELS = 1
        const val OUTPUT_BITS_PER_SAMPLE = 16
        val OUTPUT_FORMAT = WaveFormat(
            audioFormat = PCM_FORMAT,
            channels = OUTPUT_CHANNELS,
            sampleRate = OUTPUT_SAMPLE_RATE,
            bitsPerSample = OUTPUT_BITS_PER_SAMPLE,
            blockAlign = OUTPUT_CHANNELS * (OUTPUT_BITS_PER_SAMPLE / 8),
        )
        const val FADE_MS = 12
        const val MIN_VALID_BYTES = 256L
        const val MAX_SOURCE_BYTES = 16L * 1024L * 1024L
        const val MAX_TIMELINE_FRAMES = 96_000 * 90
        const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        const val TARGET_CACHE_BYTES = 192L * 1024L * 1024L
    }
}

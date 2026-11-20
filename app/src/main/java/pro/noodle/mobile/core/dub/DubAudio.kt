package pro.noodle.mobile.core.dub

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val out = ByteArrayOutputStream()
        fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun i16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); i32(36 + pcm.size * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size * 2); out.write(data.array())
        return out.toByteArray()
    }

    /** Возвращает (частота, моно 16-бит). Поддерживает PCM 16 бит, 1–2 канала. */
    fun decode(bytes: ByteArray): Pair<Int, ShortArray>? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var rate = 0; var ch = 1; var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); var size = bb.getInt(pos + 4)
            if (id == "fmt ") { ch = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
            if (id == "data") {
                if (bits != 16 || rate <= 0) return null
                if (size < 0 || pos + 8 + size > bytes.size) size = bytes.size - pos - 8
                val n = size / 2 / ch
                val out = ShortArray(n)
                for (i in 0 until n) {
                    var sum = 0
                    for (c in 0 until ch) sum += bb.getShort(pos + 8 + (i * ch + c) * 2)
                    out[i] = (sum / ch).toShort()
                }
                return rate to out
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    fun decode(file: File) = runCatching { decode(file.readBytes()) }.getOrNull()
}

/** Декодирует кусок аудиодорожки видео (по URL) в PCM 16 кГц моно — для распознавания речи. */
object DubAudio {
    const val RATE = 16000

    fun decode(url: String, headers: Map<String, String>, startMs: Long, durationMs: Long): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(url, headers)
            var track = -1; var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val endUs = (startMs + durationMs) * 1000
            val startUs = startMs * 1000
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            val deadline = System.currentTimeMillis() + 60_000
            val raw = ArrayList<ShortArray>()
            var total = 0
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t > endUs) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(idx, 0, size, t, 0); extractor.advance() }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    out >= 0 -> {
                        val buf = codec.getOutputBuffer(out)!!
                        if (info.size > 0 && info.presentationTimeUs >= startUs - 50_000) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val arr = ShortArray(sb.remaining()); sb.get(arr)
                            raw.add(arr); total += arr.size
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(out, false)
                    }
                }
            }
            if (total == 0 || ch < 1) return null
            // моно + децимация усреднением до 16 кГц
            val mono = ShortArray(total / ch)
            var k = 0; var mi = 0; var acc = 0
            for (a in raw) for (s in a) {
                // накопление по каналам
                acc += s; k++
                if (k == ch) { mono[mi++] = (acc / ch).toShort(); acc = 0; k = 0 }
            }
            val ratio = sr.toDouble() / RATE
            val n = (mi / ratio).toInt()
            val res = ShortArray(n)
            for (i in 0 until n) {
                val a = (i * ratio).toInt(); val b = minOf(mi, ((i + 1) * ratio).toInt().coerceAtLeast(a + 1))
                var sum = 0L
                for (j in a until b) sum += mono[j]
                res[i] = (sum / (b - a)).toShort()
            }
            return res
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
    }

    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var s = 0.0
        for (v in pcm) s += v.toDouble() * v
        return sqrt(s / pcm.size)
    }

    /** Оценка пола говорящего по основному тону (автокорреляция): true — низкий (мужской) голос. */
    fun isMale(pcm: ShortArray, fromSample: Int, toSample: Int): Boolean? {
        val a = fromSample.coerceIn(0, pcm.size); val b = minOf(toSample, a + RATE * 2, pcm.size)
        val frame = 640; val hop = 320
        val f0s = ArrayList<Double>()
        var p = a
        while (p + frame + 240 < b) {
            var energy = 0.0
            for (i in 0 until frame) energy += pcm[p + i].toDouble() * pcm[p + i]
            if (sqrt(energy / frame) > 500) {
                var bestLag = 0; var best = 0.0
                for (lag in 53..228) {
                    var c = 0.0; var e2 = 0.0
                    for (i in 0 until frame) { c += pcm[p + i].toDouble() * pcm[p + i + lag]; e2 += pcm[p + i + lag].toDouble() * pcm[p + i + lag] }
                    val r = c / (sqrt(energy * e2) + 1e-9)
                    if (r > best) { best = r; bestLag = lag }
                }
                if (best > 0.4 && bestLag > 0) f0s.add(RATE.toDouble() / bestLag)
            }
            p += hop
        }
        if (f0s.size < 3) return null
        f0s.sort()
        return f0s[f0s.size / 2] < 165.0
    }
}

package pro.noodle.mobile.core.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS stream as ONE ordinary file instead of Media3's private offline cache:
 * picks the best variant, fetches the segments (4 in parallel), decrypts AES-128 and appends them in
 * order. TS/fMP4 output is then remuxed to MP4 so the file opens in any gallery and seeks properly.
 */
class HlsFileDownloader(private val client: OkHttpClient, private val referer: String) {

    class Failure(val code: String, message: String) : Exception(message)

    data class Segment(val url: String, val seq: Long, val key: KeyInfo?, val rangeStart: Long, val rangeLen: Long)
    data class KeyInfo(val uri: String, val iv: ByteArray?)
    data class Plan(val segments: List<Segment>, val initUrl: String, val initStart: Long, val initLen: Long, val fmp4: Boolean, val durationSec: Double)

    private val keyCache = HashMap<String, ByteArray>()

    fun plan(url: String, maxHeight: Int): Plan {
        var mediaUrl = url
        var body = text(url)
        if (!body.trimStart().startsWith("#EXTM3U")) throw Failure("HLS_NOT_PLAYLIST", "Сервер вернул не HLS-плейлист.")
        if (body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines()
            class V(val uri: String, val bw: Long, val h: Int, val audio: String)
            val variants = ArrayList<V>()
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                val a = attrs(lines[i])
                variants += V(uri, a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0, a["AUDIO"].orEmpty())
            }
            if (variants.isEmpty()) throw Failure("HLS_NO_VARIANTS", "В мастер-плейлисте нет вариантов.")
            val limit = if (maxHeight in 1..9999) maxHeight else Int.MAX_VALUE
            val within = variants.filter { it.h <= limit || it.h == 0 }
            val chosen = (if (within.isNotEmpty()) within else listOf(variants.minByOrNull { it.h }!!)).maxByOrNull { it.bw * 1L + it.h * 1_000_000_000L }!!
            if (chosen.audio.isNotBlank() && lines.any { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") && it.contains("GROUP-ID=\"${chosen.audio}\"") && it.contains("URI=") }) {
                throw Failure("HLS_SEPARATE_AUDIO", "У потока звук лежит отдельно — как файл его собрать нельзя, используйте офлайн-режим.")
            }
            mediaUrl = resolve(url, chosen.uri)
            body = text(mediaUrl)
            if (!body.trimStart().startsWith("#EXTM3U")) throw Failure("HLS_VARIANT_UNAVAILABLE", "Выбранный вариант качества недоступен.")
        }
        val segs = ArrayList<Segment>()
        var seq = 0L
        var key: KeyInfo? = null
        var initUrl = ""; var initStart = -1L; var initLen = -1L
        var pendingStart = -1L; var pendingLen = -1L
        var lastEnd = 0L
        var duration = 0.0
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim(); i++
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(l)
                    val method = a["METHOD"].orEmpty()
                    key = when {
                        method == "NONE" || method.isBlank() -> null
                        method == "AES-128" -> KeyInfo(resolve(mediaUrl, a["URI"].orEmpty()), a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let(::hex))
                        else -> throw Failure("HLS_DRM", "Поток защищён ($method) — скачать как файл нельзя.")
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(l)
                    initUrl = resolve(mediaUrl, a["URI"].orEmpty())
                    a["BYTERANGE"]?.let { r -> initLen = r.substringBefore('@').toLongOrNull() ?: -1; initStart = r.substringAfter('@', "0").toLongOrNull() ?: 0 }
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> {
                    val r = l.substringAfter(':')
                    pendingLen = r.substringBefore('@').trim().toLongOrNull() ?: -1
                    pendingStart = r.substringAfter('@', "").trim().toLongOrNull() ?: lastEnd
                }
                l.startsWith("#EXTINF:") -> {
                    duration += l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    // The URI is the next non-tag line; BYTERANGE may sit between.
                    while (i < lines.size && (lines[i].isBlank() || (lines[i].startsWith("#") && !lines[i].startsWith("#EXT-X-BYTERANGE")))) i++
                    if (i < lines.size && lines[i].startsWith("#EXT-X-BYTERANGE:")) {
                        val r = lines[i].substringAfter(':')
                        pendingLen = r.substringBefore('@').trim().toLongOrNull() ?: -1
                        pendingStart = r.substringAfter('@', "").trim().toLongOrNull() ?: lastEnd
                        i++
                    }
                    if (i >= lines.size) break
                    val uri = lines[i].trim(); i++
                    segs += Segment(resolve(mediaUrl, uri), seq + segs.size, key, pendingStart, pendingLen)
                    if (pendingLen > 0) lastEnd = pendingStart + pendingLen
                    pendingStart = -1; pendingLen = -1
                }
            }
        }
        if (segs.isEmpty()) throw Failure("HLS_NO_SEGMENTS", "В плейлисте нет сегментов.")
        if (!body.contains("#EXT-X-ENDLIST") && !body.contains("PLAYLIST-TYPE:VOD")) throw Failure("HLS_LIVE", "Это прямой эфир — файл собрать нельзя.")
        val path = segs.first().url.substringBefore('?').lowercase()
        val fmp4 = initUrl.isNotBlank() || path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".cmfv")
        return Plan(segs, initUrl, initStart, initLen, fmp4, duration)
    }

    /** Appends segments to [out] in order. [onChunk] receives (next segment index, bytes written this run). */
    suspend fun run(plan: Plan, out: OutputStream, startIndex: Int, stopped: () -> Boolean, onChunk: suspend (Int, Long) -> Unit): Long = coroutineScope {
        var written = 0L
        if (startIndex == 0 && plan.initUrl.isNotBlank()) {
            val init = fetch(plan.initUrl, plan.initStart, plan.initLen)
            out.write(init); written += init.size
        }
        var idx = startIndex
        while (idx < plan.segments.size) {
            if (stopped()) throw java.util.concurrent.CancellationException("stopped")
            val end = minOf(idx + PARALLEL, plan.segments.size)
            val batch = (idx until end).map { n -> async(Dispatchers.IO) { segment(plan.segments[n]) } }
            for (job in batch) { val data = job.await(); out.write(data); written += data.size }
            idx = end
            onChunk(idx, written)
        }
        written
    }

    private suspend fun segment(s: Segment): ByteArray {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val raw = fetch(s.url, s.rangeStart, s.rangeLen)
                val k = s.key ?: return raw
                val keyBytes = synchronized(keyCache) { keyCache[k.uri] } ?: fetch(k.uri, -1, -1).also {
                    if (it.size != 16) throw Failure("HLS_BAD_KEY", "Ключ шифрования имеет неверный размер.")
                    synchronized(keyCache) { keyCache[k.uri] = it }
                }
                val iv = k.iv?.let { padIv(it) } ?: ByteArray(16).also { b -> var v = s.seq; for (j in 15 downTo 8) { b[j] = (v and 0xFF).toByte(); v = v shr 8 } }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(raw)
            } catch (e: Failure) { throw e } catch (e: Exception) { last = e; delay(400L * (attempt + 1)) }
        }
        throw Failure("HLS_SEGMENT_FAILED", "Сегмент не загрузился: ${last?.message ?: last?.javaClass?.simpleName}")
    }

    private fun fetch(url: String, start: Long, len: Long): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", BuildIdentity.USER_AGENT).header("Accept", "*/*")
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (start >= 0 && len > 0) b.header("Range", "bytes=$start-${start + len - 1}")
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("empty body")
            if (body.contentLength() > MAX_SEGMENT) throw java.io.IOException("segment too large")
            return body.bytes()
        }
    }

    private fun text(url: String): String = String(fetch(url, -1, -1), Charsets.UTF_8)
    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    private fun attrs(line: String): Map<String, String> =
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun padIv(iv: ByteArray): ByteArray = if (iv.size >= 16) iv.copyOfRange(iv.size - 16, iv.size) else ByteArray(16 - iv.size) + iv

    companion object {
        private const val PARALLEL = 4
        private const val MAX_SEGMENT = 64L * 1024 * 1024
    }
}

/** TS / fragmented MP4 → regular MP4 without re-encoding (extractor + muxer). */
object MediaRemuxer {
    fun toMp4(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(input.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            var maxBuf = 4 shl 20
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                map[i] = muxer.addTrack(f)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxBuf = maxOf(maxBuf, minOf(f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 32 shl 20))
            }
            if (map.isEmpty()) { muxer.release(); muxer = null; output.delete(); return false }
            muxer.start()
            val buf = ByteBuffer.allocate(maxBuf)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val track = map[extractor.sampleTrackIndex]
                if (track != null) {
                    val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), flags)
                    muxer.writeSampleData(track, buf, info)
                }
                extractor.advance()
            }
            muxer.stop(); muxer.release(); muxer = null
            output.length() > 0
        } catch (_: Exception) {
            runCatching { muxer?.release() }
            output.delete()
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}

package pro.noodle.mobile.core.resolver

import okhttp3.OkHttpClient
import okhttp3.Request
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.model.MediaSource
import java.net.URI

/** What a cheap network probe learned about one candidate stream. */
data class StreamFacts(
    val url: String,
    val ok: Boolean = false,
    val status: Int = 0,
    val contentType: String = "",
    val bytes: Long = -1,
    val durationSec: Double = -1.0,
    /** mp4 | webm | hls | dash | html | unknown */
    val kind: String = "unknown",
    val live: Boolean = false,
    val encryption: String = "",
    val height: Int = 0,
    val note: String = "",
) {
    fun brief(): String = buildString {
        append(kind)
        if (!ok) append(" FAIL").append(if (status > 0) " HTTP $status" else "")
        if (durationSec >= 0) append(" ").append(DurationText.format(durationSec))
        if (bytes >= 0) append(" ").append(humanBytes(bytes))
        if (height > 0) append(" ${height}p")
        if (live) append(" live")
        if (encryption.isNotBlank()) append(" enc=$encryption")
        if (note.isNotBlank()) append(" ($note)")
    }

    companion object {
        fun humanBytes(v: Long): String = when {
            v >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(v / (1024.0 * 1024 * 1024))
            v >= 1024L * 1024 -> "%.1f МБ".format(v / (1024.0 * 1024))
            v >= 1024 -> "%.0f КБ".format(v / 1024.0)
            else -> "$v Б"
        }
    }
}

/**
 * Cheap, bounded probe: a few ranged GETs per candidate. Tells a real 40-minute video from a
 * 5-second hover preview without downloading it: MP4 duration comes from the `mvhd` box, HLS
 * duration is the sum of EXTINF, DASH from mediaPresentationDuration.
 */
class StreamProbe(private val client: OkHttpClient, private val referer: String) {

    fun probe(source: MediaSource): StreamFacts = runCatching { probeUnsafe(source) }
        .getOrElse { StreamFacts(source.url, ok = false, note = (it.message ?: it.javaClass.simpleName).take(60)) }

    private fun probeUnsafe(source: MediaSource): StreamFacts {
        val url = source.url
        val lower = url.substringBefore('?').lowercase()
        return when {
            source.protocol.equals("hls", true) || lower.endsWith(".m3u8") -> probeHls(url)
            source.protocol.equals("dash", true) || lower.endsWith(".mpd") -> probeDash(url)
            else -> probeProgressive(url)
        }
    }

    // ------------------------------------------------------------------ progressive

    private class Chunk(val code: Int, val bytes: ByteArray, val contentType: String, val total: Long, val rangeHonored: Boolean)

    private fun range(url: String, start: Long, endInclusive: Long): Chunk? {
        val want = (endInclusive - start + 1).toInt().coerceAtLeast(1)
        val req = builder(url).header("Range", "bytes=$start-$endInclusive").build()
        client.newCall(req).execute().use { r ->
            val ct = r.header("Content-Type").orEmpty()
            val cr = r.header("Content-Range").orEmpty()
            val honored = r.code == 206
            val total = when {
                cr.contains('/') -> cr.substringAfter('/').trim().toLongOrNull() ?: -1L
                r.code == 200 -> r.body?.contentLength() ?: -1L
                else -> -1L
            }
            if (r.code !in 200..299) return Chunk(r.code, ByteArray(0), ct, total, honored)
            // A server that ignores Range answers 200 with the whole file: read only what we need.
            if (!honored && start > 0) return Chunk(r.code, ByteArray(0), ct, total, false)
            val body = r.body ?: return Chunk(r.code, ByteArray(0), ct, total, honored)
            val out = java.io.ByteArrayOutputStream(want)
            val buf = ByteArray(8192)
            val input = body.byteStream()
            while (out.size() < want) {
                val n = input.read(buf, 0, minOf(buf.size, want - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return Chunk(r.code, out.toByteArray(), ct, total, honored)
        }
    }

    private fun probeProgressive(url: String): StreamFacts {
        val first = range(url, 0, 8191) ?: return StreamFacts(url, note = "no response")
        if (first.code !in 200..299) return StreamFacts(url, ok = false, status = first.code, contentType = first.contentType)
        val ct = first.contentType.lowercase()
        val head = first.bytes
        val headText = String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1)
        if (headText.startsWith("#EXTM3U")) return probeHls(url)
        if (ct.contains("html") || ct.contains("json") || ct.startsWith("text/") || headText.trimStart().startsWith("<")) {
            return StreamFacts(url, ok = false, status = first.code, contentType = first.contentType, kind = "html", note = "не видео")
        }
        val isMp4 = head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_BRANDS
        val isWebm = head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        val kind = when { isMp4 -> "mp4"; isWebm -> "webm"; ct.contains("mp4") -> "mp4"; ct.contains("webm") -> "webm"; else -> "unknown" }
        var duration = -1.0
        if (isMp4 && (first.rangeHonored || first.total in 0..8192)) {
            duration = Mp4Duration.read(head, first.total) { off, len -> range(url, off, off + len - 1)?.takeIf { it.code in 200..299 }?.bytes }
        }
        return StreamFacts(url, ok = true, status = first.code, contentType = first.contentType, bytes = first.total, durationSec = duration, kind = kind)
    }

    // ------------------------------------------------------------------ HLS / DASH

    private fun text(url: String, limit: Int = 2 * 1024 * 1024): Pair<Int, String> {
        client.newCall(builder(url).build()).execute().use { r ->
            if (!r.isSuccessful) return r.code to ""
            val src = r.body?.source() ?: return r.code to ""
            src.request(limit.toLong())
            val n = minOf(src.buffer.size, limit.toLong())
            return r.code to src.buffer.readString(n, Charsets.UTF_8)
        }
    }

    fun probeHls(url: String): StreamFacts {
        val (code, body) = text(url)
        if (!body.trimStart().startsWith("#EXTM3U")) return StreamFacts(url, ok = false, status = code, kind = "hls", note = if (code in 200..299) "не плейлист" else "")
        var mediaUrl = url
        var mediaBody = body
        var height = 0
        if (body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines()
            var best: Triple<Long, Int, String>? = null
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val h = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (best == null || bw > best.first) best = Triple(bw, h, uri)
            }
            val chosen = best ?: return StreamFacts(url, ok = true, status = code, kind = "hls", note = "мастер без вариантов")
            height = chosen.second
            mediaUrl = resolve(url, chosen.third)
            val (c2, b2) = text(mediaUrl)
            if (!b2.trimStart().startsWith("#EXTM3U")) return StreamFacts(url, ok = false, status = c2, kind = "hls", height = height, note = "вариант недоступен")
            mediaBody = b2
        }
        var total = 0.0
        var segments = 0
        var firstSegment = ""
        var encryption = ""
        val lines = mediaBody.lines()
        for (i in lines.indices) {
            val l = lines[i].trim()
            when {
                l.startsWith("#EXTINF:") -> {
                    total += l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    segments++
                    if (firstSegment.isBlank()) firstSegment = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim().orEmpty()
                }
                l.startsWith("#EXT-X-KEY:") -> {
                    val m = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1).orEmpty()
                    if (m.isNotBlank() && m != "NONE") encryption = m
                }
            }
        }
        val live = !mediaBody.contains("#EXT-X-ENDLIST") && !mediaBody.contains("PLAYLIST-TYPE:VOD")
        var segOk = true
        var status = code
        if (firstSegment.isNotBlank()) {
            val chunk = runCatching { range(resolve(mediaUrl, firstSegment), 0, 1023) }.getOrNull()
            segOk = chunk != null && chunk.code in 200..299
            if (chunk != null) status = chunk.code
        }
        return StreamFacts(url, ok = segOk && segments > 0, status = status, kind = "hls", durationSec = if (segments > 0) total else -1.0, live = live, encryption = encryption, height = height, note = if (segments == 0) "нет сегментов" else "")
    }

    private fun probeDash(url: String): StreamFacts {
        val (code, body) = text(url)
        if (!body.contains("<MPD", true)) return StreamFacts(url, ok = false, status = code, kind = "dash")
        val iso = Regex("mediaPresentationDuration=\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
        val seconds = DurationText.seconds(iso).toDouble()
        val live = body.contains("type=\"dynamic\"", true)
        return StreamFacts(url, ok = true, status = code, kind = "dash", durationSec = if (seconds > 0) seconds else -1.0, live = live)
    }

    // ------------------------------------------------------------------ helpers

    private fun builder(url: String): Request.Builder = Request.Builder().url(url)
        .header("Accept", "video/*,application/vnd.apple.mpegurl,application/dash+xml,*/*;q=0.8")
        // Тот же User-Agent, что у плеера и у загрузки страницы: подпись CDN бывает привязана к нему.
        .header("User-Agent", pro.noodle.mobile.core.version.BuildIdentity.USER_AGENT)
        .apply { if (referer.isNotBlank()) header("Referer", referer) }

    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

    companion object {
        private val MP4_BRANDS = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "styp")
    }
}

/** Reads the movie duration from an MP4 by hopping over top-level boxes with ranged requests. */
object Mp4Duration {
    fun read(head: ByteArray, total: Long, fetch: (Long, Int) -> ByteArray?): Double {
        var offset = 0L
        var hops = 0
        while (hops++ < 14 && (total < 0 || offset < total)) {
            val hdr = slice(head, offset, 16) ?: fetch(offset, 16) ?: return -1.0
            if (hdr.size < 8) return -1.0
            var size = u32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && hdr.size >= 16) { size = u64(hdr, 8); header = 16 } else if (size == 0L) { size = if (total > 0) total - offset else return -1.0 }
            if (type == "moov") {
                val inner = slice(head, offset + header, 160) ?: fetch(offset + header, 160) ?: return -1.0
                return mvhd(inner)
            }
            if (size < 8) return -1.0
            offset += size
        }
        return -1.0
    }

    private fun slice(buf: ByteArray, from: Long, len: Int): ByteArray? =
        if (from >= 0 && from + len <= buf.size) buf.copyOfRange(from.toInt(), (from + len).toInt()) else null

    private fun mvhd(b: ByteArray): Double {
        val idx = indexOf(b, "mvhd")
        if (idx < 0) return -1.0
        val c = idx + 4
        if (c + 24 > b.size) return -1.0
        val version = b[c].toInt() and 0xFF
        return if (version == 1) {
            if (c + 32 > b.size) return -1.0
            val ts = u32(b, c + 20); val dur = u64(b, c + 24)
            if (ts > 0) dur.toDouble() / ts else -1.0
        } else {
            val ts = u32(b, c + 12); val dur = u32(b, c + 16)
            if (ts > 0) dur.toDouble() / ts else -1.0
        }
    }

    private fun indexOf(b: ByteArray, s: String): Int {
        val p = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..b.size - p.size) {
            for (j in p.indices) if (b[i + j] != p[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
}

/** Duration parsing for card badges (`12:34`, `1:02:03`, `12 мин`) and page metadata (`PT1H2M3S`). */
object DurationText {
    private val iso = Regex("P(?:T)?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?", RegexOption.IGNORE_CASE)
    private val clock = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])")
    private val words = Regex("(\\d+)\\s*(h|ч|m|min|мин|s|sec|сек)(?!\\p{L})", RegexOption.IGNORE_CASE)

    fun seconds(text: String): Int {
        val t = text.trim()
        if (t.isBlank()) return 0
        if (t.startsWith("PT", true) || t.startsWith("P0", true)) {
            iso.matchEntire(t)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mi = m.groupValues[2].toIntOrNull() ?: 0
                val s = m.groupValues[3].toDoubleOrNull()?.toInt() ?: 0
                return h * 3600 + mi * 60 + s
            }
        }
        clock.find(t)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val c = m.groupValues[3]
            return if (c.isBlank()) a * 60 + b else a * 3600 + b * 60 + c.toInt()
        }
        var total = 0
        var any = false
        words.findAll(t).forEach { m ->
            any = true
            val n = m.groupValues[1].toInt()
            total += when (m.groupValues[2].lowercase()) { "h", "ч" -> n * 3600; "m", "min", "мин" -> n * 60; else -> n }
        }
        if (any) return total
        return t.toIntOrNull()?.takeIf { it in 1..86_400 } ?: 0
    }

    /** Duration advertised by the video page itself (og:video:duration, itemprop, JSON-LD). */
    fun fromPage(html: String): Int {
        val og = EngineParsing.first(html, "<meta[^>]+(?:property|name)=[\"'](?:og:video:duration|video:duration)[\"'][^>]+content=[\"']([^\"']+)[\"']")
            .ifBlank { EngineParsing.first(html, "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"'](?:og:video:duration|video:duration)[\"']") }
        if (og.isNotBlank()) seconds(og).takeIf { it > 0 }?.let { return it }
        val item = EngineParsing.first(html, "<meta[^>]+itemprop=[\"']duration[\"'][^>]+content=[\"']([^\"']+)[\"']")
            .ifBlank { EngineParsing.first(html, "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+itemprop=[\"']duration[\"']") }
        if (item.isNotBlank()) seconds(item).takeIf { it > 0 }?.let { return it }
        val ld = EngineParsing.first(html, "[\"']duration[\"']\\s*:\\s*[\"'](PT[^\"']+)[\"']")
        if (ld.isNotBlank()) seconds(ld).takeIf { it > 0 }?.let { return it }
        return 0
    }

    fun format(sec: Double): String {
        val t = sec.toInt().coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

package pro.noodle.mobile.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import java.io.File
import java.net.URI

/**
 * Собственная иконка каждого сайта (apple-touch-icon / favicon). Ищется один раз в <link rel="icon"> главной
 * страницы, затем /favicon.ico; результат хранится на диске и в памяти, чтобы не ходить на сайт повторно.
 */
object SourceIcons {
    private val memory = LruCache<String, Bitmap>(48)
    private val misses = HashSet<String>()
    private val linkRe = Regex("<link\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private const val TARGET = 256

    suspend fun load(context: Context, baseUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("").removePrefix("www.")
        if (host.isBlank()) return@withContext null
        memory.get(host)?.let { return@withContext it }
        val file = File(File(context.applicationContext.filesDir, "source_icons").apply { mkdirs() }, "$host.v2.png")
        if (file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { memory.put(host, it); return@withContext it }
        }
        if (synchronized(misses) { host in misses }) return@withContext null
        val bmp = withTimeoutOrNull(20_000) { download(baseUrl, host) }
        if (bmp == null) {
            synchronized(misses) { misses += host }
            return@withContext null
        }
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        memory.put(host, bmp)
        bmp
    }

    private suspend fun download(baseUrl: String, host: String): Bitmap? {
        val http = SecureHttpClient()
        val base = baseUrl.trimEnd('/').ifBlank { "https://$host" }
        val key = "icon-$host"
        val candidates = LinkedHashMap<String, Int>() // url -> priority (higher first)
        val home = http.fetchText(base, NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = 1_500_000, maxRedirects = 5), key)
        val pageUrl = home.finalUrl.ifBlank { base }
        if (home.success) {
            // Достаточно <head>; ищем теги <link rel=...icon...>
            val head = home.text.take(200_000)
            for (m in linkRe.findAll(head)) {
                val tag = m.value
                val rel = EngineParsing.attr(tag, "rel").lowercase()
                if ("icon" !in rel) continue
                val href = EngineParsing.attr(tag, "href")
                if (href.isBlank() || href.startsWith("data:")) continue
                val abs = EngineParsing.absolute(href, pageUrl)
                if (abs.endsWith(".svg", true)) continue
                val size = EngineParsing.attr(tag, "sizes").substringBefore('x').toIntOrNull() ?: 0
                val priority = (if ("apple-touch" in rel) 1000 else 0) + size.coerceAtMost(512) + (if ("shortcut" in rel) 1 else 0)
                candidates.putIfAbsent(abs, priority)
            }
        }
        val origin = runCatching { URI(pageUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault(base)
        // Web App Manifest обычно содержит самые крупные иконки (192/512 px).
        if (home.success) {
            val manifestHref = linkRe.findAll(home.text.take(200_000)).map { it.value }
                .firstOrNull { EngineParsing.attr(it, "rel").equals("manifest", true) }
                ?.let { EngineParsing.attr(it, "href") }.orEmpty()
            if (manifestHref.isNotBlank()) {
                val mUrl = EngineParsing.absolute(manifestHref, pageUrl)
                val m = http.fetchText(mUrl, NetworkPolicy(emptyList(), maxResponseBytes = 300_000, maxRedirects = 3), key)
                if (m.success) runCatching {
                    val arr = org.json.JSONObject(m.text).optJSONArray("icons")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val src = o.optString("src")
                        if (src.isBlank() || src.endsWith(".svg", true)) continue
                        val size = o.optString("sizes").substringBefore('x').substringBefore(' ').toIntOrNull() ?: 0
                        candidates.putIfAbsent(EngineParsing.absolute(src, m.finalUrl.ifBlank { mUrl }), 800 + size.coerceAtMost(512))
                    }
                }
            }
        }
        candidates.putIfAbsent("$origin/apple-touch-icon.png", 900)
        candidates.putIfAbsent("$origin/apple-touch-icon-precomposed.png", 890)
        candidates.putIfAbsent("$origin/favicon.ico", 1)
        // Скачиваем лучшие кандидаты и берём самый крупный по пикселям — тогда иконка получается чёткой.
        var best: Bitmap? = null
        for ((url, _) in candidates.entries.sortedByDescending { it.value }.take(8)) {
            val r = http.fetchBytes(url, NetworkPolicy(emptyList(), maxResponseBytes = 900_000, maxRedirects = 4), key, accept = "image/png,image/x-icon,image/vnd.microsoft.icon,image/*;q=0.8,*/*;q=0.3", referer = "$origin/")
            if (!r.success || r.bytes.size < 80) continue
            val bmp = decode(r.bytes) ?: continue
            if (best == null || bmp.width > best.width) best = bmp
            if (best.width >= 180) break
        }
        return best
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET && bounds.outHeight / (sample * 2) >= TARGET) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (raw.width < 16) return null
        return if (raw.width > TARGET) Bitmap.createScaledBitmap(raw, TARGET, (raw.height * TARGET / raw.width).coerceAtLeast(1), true) else raw
    }
}

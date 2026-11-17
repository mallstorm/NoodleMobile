package pro.noodle.mobile.core.media

import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient

data class ThumbnailBytes(val bytes: ByteArray?, val status: Int = 0, val error: String = "") {
    val ok: Boolean get() = bytes != null && bytes.isNotEmpty()
}

/**
 * Covers live on image CDNs, not on the site host, so they are fetched under "any public HTTPS
 * host" (UrlPolicy still blocks private/loopback/non-HTTPS) with the Referer the CDN expects.
 * Several Referer variants are tried because hot-link protection differs from site to site.
 */
object ThumbnailFetcher {
    private const val ACCEPT = "image/avif,image/webp,image/apng,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.3"

    suspend fun fetch(
        http: SecureHttpClient,
        rawUrl: String,
        siteBase: String,
        pageUrl: String,
        sourceKey: String,
        allowCookies: Boolean,
        maxBytes: Int = 3 * 1024 * 1024,
    ): ThumbnailBytes {
        val url = normalize(rawUrl, siteBase)
        if (url.isBlank()) return ThumbnailBytes(null, error = "empty")
        val siteReferer = siteBase.trimEnd('/').takeIf { it.isNotBlank() }?.plus("/").orEmpty()
        val referers = listOf(siteReferer, pageUrl, "").distinct()
        var last = ThumbnailBytes(null, error = "unreachable")
        for (ref in referers) {
            val r = http.fetchBytes(
                url = url,
                policy = NetworkPolicy(emptyList(), allowCookies = allowCookies, maxResponseBytes = maxBytes),
                sourceKey = sourceKey,
                accept = ACCEPT,
                referer = ref,
            )
            if (r.success && r.bytes.isNotEmpty() && looksLikeImage(r.bytes, r.contentType)) return ThumbnailBytes(r.bytes, r.statusCode)
            last = ThumbnailBytes(null, r.statusCode, r.errorCode.ifBlank { if (r.success) "NOT_IMAGE" else "FAILED" })
            if (r.statusCode == 404 || r.statusCode == 410) break
        }
        return last
    }

    suspend fun fetch(http: SecureHttpClient, video: VideoItem, siteBase: String, sourceKey: String, allowCookies: Boolean, maxBytes: Int = 3 * 1024 * 1024): ThumbnailBytes =
        fetch(http, video.thumbnailUrl, siteBase.ifBlank { video.sourceBaseUrl }, video.watchUrl, sourceKey, allowCookies, maxBytes)

    fun normalize(raw: String, base: String): String {
        var u = raw.trim().replace("&amp;", "&")
        if (u.isBlank() || u.startsWith("data:", true)) return ""
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("/")) u = base.trimEnd('/') + u
        if (u.startsWith("http://", true)) u = "https://" + u.substring(7)
        return if (u.startsWith("https://", true)) u else ""
    }

    private fun looksLikeImage(b: ByteArray, contentType: String): Boolean {
        if (b.size < 12) return false
        val ct = contentType.lowercase()
        if (ct.startsWith("text/") || ct.contains("html") || ct.contains("json")) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()
        val riff = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte()
        val isoBmff = String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"
        return jpeg || png || gif || riff || isoBmff || ct.startsWith("image/") && !ct.contains("svg")
    }
}

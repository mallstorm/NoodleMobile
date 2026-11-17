package pro.noodle.mobile.core.engine.runtime

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object EngineParsing {
    fun htmlDecode(value: String): String {
        var s = value.replace("\\/", "/")
        val named = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ")
        s = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);").replace(s) { m ->
            val token = m.groupValues[1]
            when {
                token.startsWith("#x", true) -> token.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                token.startsWith("#") -> token.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> named[token.lowercase()]
            } ?: m.value
        }
        return s.trim()
    }

    fun stripTags(value: String): String = htmlDecode(
        Regex("<[^>]+>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(value, " ")
    ).replace(Regex("\\s+"), " ").trim()

    fun first(input: String, pattern: String, group: Int = 1): String =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(input)?.groupValues?.getOrNull(group)?.trim().orEmpty()

    fun absolute(raw: String, baseUrl: String): String {
        val value = htmlDecode(raw)
        if (value.isBlank()) return ""
        return try {
            val base = URI(baseUrl)
            when {
                value.startsWith("//") -> "${base.scheme ?: "https"}:$value"
                else -> base.resolve(value).toString()
            }
        } catch (_: Exception) {
            value.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty()
        }
    }

    private val previewClip = Regex("(?:/videopreview/|/previews?/|[_./-](?:preview|trailer|teaser|sprite|thumbs?)(?:\\d*s|_\\d+)?[_./-][^/]*\\.(?:webm|mp4)|preview\\d*s?\\.(?:webm|mp4))", RegexOption.IGNORE_CASE)

    /** Hover/preview clips (a few seconds long) are not the video itself. */
    fun isPreviewClip(url: String): Boolean = previewClip.containsMatchIn(url.substringBefore('?'))


    /** Detect hover preview URLs by their owning markup, not by broad text proximity. */
    fun isPreviewMarkup(html: String, urlRange: IntRange): Boolean {
        val offset = urlRange.first
        if (offset !in html.indices) return false
        val prefix = html.substring((offset - 110).coerceAtLeast(0), offset)
        val previewField = Regex(
            """(?:data-(?:video-)?(?:preview|trailer|teaser|hover)|(?:preview|trailer|teaser)(?:_?url|_?src)?)\s*[:=]\s*["']?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        if (previewField.containsMatchIn(prefix)) return true

        val open = html.lastIndexOf('<', offset)
        val end = if (open >= 0) html.indexOf('>', open) else -1
        if (open >= 0 && end >= offset && offset - open < 900) {
            val tag = html.substring(open, end + 1)
            val mediaTag = Regex("""^<(?:video|source|div|a)\b""", RegexOption.IGNORE_CASE)
            if (mediaTag.containsMatchIn(tag)) {
                val previewMarker = Regex("""(?:preview|trailer|teaser|hover|thumb)""", RegexOption.IGNORE_CASE)
                if (previewMarker.containsMatchIn(tag) &&
                    (tag.contains("data-", true) || tag.startsWith("<video", true) || tag.startsWith("<source", true))
                ) return true
                if (tag.startsWith("<video", true) && hasBooleanAttr(tag, "muted") && hasBooleanAttr(tag, "loop")) return true
            }
        }
        val videoOpen = html.lastIndexOf("<video", offset, ignoreCase = true)
        val videoClose = html.lastIndexOf("</video", offset, ignoreCase = true)
        if (videoOpen > videoClose && offset - videoOpen < 1600) {
            val tagEnd = html.indexOf('>', videoOpen)
            if (tagEnd in videoOpen until offset) {
                val tag = html.substring(videoOpen, tagEnd + 1)
                if (hasBooleanAttr(tag, "muted") && hasBooleanAttr(tag, "loop")) return true
            }
        }
        return false
    }

    /** A five-second stream is a teaser if the search card advertises a full-length video. */
    fun isPreviewDuration(expectedDuration: String, actualMs: Long): Boolean =
        isPreviewDurationSec(pro.noodle.mobile.core.resolver.DurationText.seconds(expectedDuration), actualMs)

    /**
     * True when the opened stream is a hover/teaser clip rather than the video itself.
     * Card says ≥30 s → <9 s, or <40 % of a ≥1 min card. Card says nothing ("HD", blank) → <15 s.
     * Card itself says <30 s → never (the video really is short).
     */
    fun isPreviewDurationSec(expectedSec: Int, actualMs: Long): Boolean {
        if (actualMs <= 0L) return false
        val a = actualMs / 1000.0
        return when {
            expectedSec in 1..29 -> false
            expectedSec >= 30 -> a < 9.0 || (expectedSec >= 60 && a < expectedSec * 0.4)
            else -> a < 15.0
        }
    }

    fun encodedQuery(value: String): String = URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
    fun urlDecode(value: String): String = try { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) } catch (_: Exception) { value }

    fun attr(tag: String, name: String): String {
        val q = Regex("\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(tag)
        if (q != null) return htmlDecode(q.groupValues[2])
        return Regex("\\b${Regex.escape(name)}\\s*=\\s*([^\\s>]+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.getOrNull(1)?.let(::htmlDecode).orEmpty()
    }

    fun hasBooleanAttr(tag: String, name: String): Boolean = Regex("(?:^|\\s)${Regex.escape(name)}(?:\\s|=|/?>)", RegexOption.IGNORE_CASE).containsMatchIn(tag)

    fun protocol(url: String, mime: String = ""): String {
        val u = url.lowercase()
        val m = mime.lowercase()
        return when {
            u.contains(".m3u8") || m.contains("mpegurl") -> "hls"
            u.contains(".mpd") || m.contains("dash") -> "dash"
            else -> "http"
        }
    }

    fun inferLabel(url: String, mime: String = "", declared: String = ""): String {
        if (declared.isNotBlank()) return htmlDecode(declared)
        val quality = Regex("(?:^|[^0-9])(2160|1440|1080|720|480|360|240)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
        if (!quality.isNullOrBlank()) return "${quality}p"
        return when (protocol(url, mime)) {
            "hls" -> "HLS"
            "dash" -> "DASH"
            else -> mime.trim().ifBlank { "Auto" }
        }
    }

    fun titleFromHtml(html: String): String {
        val og = Regex("<meta[^>]+(?:property|name)=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:title[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.getOrNull(1)
        if (!og.isNullOrBlank()) return htmlDecode(og)
        return stripTags(first(html, "<title[^>]*>([\\s\\S]*?)</title>"))
    }

    fun imageFromHtml(html: String, baseUrl: String): String {
        val raw = first(html, "<meta[^>]+(?:property|name)=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']")
            .ifBlank { first(html, "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:image[\"']") }
        return absolute(raw, baseUrl)
    }
}

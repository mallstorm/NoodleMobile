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

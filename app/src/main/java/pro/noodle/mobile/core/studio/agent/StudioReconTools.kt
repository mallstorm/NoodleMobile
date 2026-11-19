package pro.noodle.mobile.core.studio.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.engine.impl.AutoCards
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.studio.StudioEngineKnowledge
import pro.noodle.mobile.core.studio.auto.CardLearner
import java.net.URI

/**
 * Инструменты разведки для AI-агента Studio: «рентген» страницы одним вызовом, произвольные HTTP-запросы
 * (POST/заголовки — для AJAX-плееров и JSON API) и пакетная проверка адресов.
 */
internal class StudioReconTools(private val http: SecureHttpClient) {
    private fun policy(maxBytes: Int = 3 * 1024 * 1024) = NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = maxBytes, maxRedirects = 5)
    private fun key(url: String) = "studio-agent-" + runCatching { URI(url).host.orEmpty() }.getOrDefault("x")

    private fun absolute(raw: String, base: String): String =
        pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(raw.replace("&amp;", "&").replace("\\/", "/"), base)

    // ------------------------------------------------------------------------------------------------ http_request
    suspend fun httpRequest(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (!url.startsWith("http", true)) return "ERROR: args.url must be an absolute http(s) URL"
        val headers = args.optJSONObject("headers")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty().toMutableMap()
        val referer = args.optString("referer").trim()
        if (referer.isNotBlank()) headers["Referer"] = referer
        val form = args.optJSONObject("form")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
        val method = args.optString("method", if (form.isNotEmpty() || args.has("body")) "POST" else "GET")
        val res = withTimeoutOrNull(30_000) {
            http.requestText(url, policy(), key(url), method, headers, form, args.optString("body"), args.optString("content_type"))
        } ?: return "ERROR: timeout 30s"
        val head = "HTTP ${res.statusCode} • ${method.uppercase()} • final=${res.finalUrl} • type=${res.contentType} • ${res.text.length} chars" +
            (if (!res.success) " • ${res.errorCode} ${res.errorMessage}" else "")
        if (res.text.isBlank()) return head
        val grep = args.optString("grep").trim()
        if (grep.isNotBlank()) {
            val re = runCatching { Regex(grep, RegexOption.IGNORE_CASE) }.getOrElse { return "ERROR: bad grep regex: ${it.message}" }
            val ctx = args.optInt("context", 220).coerceIn(40, 600)
            val hits = re.findAll(res.text).take(10).toList()
            if (hits.isEmpty()) return "$head\nNO MATCH for /$grep/"
            return head + "\n" + hits.mapIndexed { i, m ->
                "--- match ${i + 1} @${m.range.first} ---\n" + res.text.substring((m.range.first - ctx).coerceAtLeast(0), (m.range.last + ctx).coerceAtMost(res.text.length))
            }.joinToString("\n")
        }
        val max = args.optInt("max_chars", 6000).coerceIn(500, 12000)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val body = runCatching { if (res.text.trimStart().startsWith("{") || res.text.trimStart().startsWith("[")) JSONObject.wrap(org.json.JSONTokener(res.text).nextValue()).toString() else null }.getOrNull() ?: res.text
        return "$head\n" + body.drop(offset).take(max) + if (offset + max < body.length) "\n...[more: offset=${offset + max}]" else ""
    }

    // ------------------------------------------------------------------------------------------------ probe_urls
    suspend fun probeUrls(args: JSONObject): String {
        val arr = args.optJSONArray("urls") ?: return "ERROR: args.urls must be an array of absolute URLs (max 12)"
        val urls = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.startsWith("http", true) }.distinct().take(12)
        if (urls.isEmpty()) return "ERROR: no valid URLs"
        val titleRe = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rows = coroutineScope {
            urls.map { u ->
                async(Dispatchers.IO) {
                    val r = withTimeoutOrNull(20_000) { http.requestText(u, policy(600_000), key(u)) }
                    val line = if (r == null) "timeout" else {
                        val title = titleRe.find(r.text)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(70)
                        "HTTP ${r.statusCode} ${r.contentType.substringBefore(';')} ${r.text.length}ch" +
                            (if (r.finalUrl != u) " → ${r.finalUrl.take(100)}" else "") +
                            (if (title != null) " • «$title»" else if (r.text.isNotBlank() && !r.contentType.contains("html")) " • ${r.text.take(90).replace('\n', ' ')}" else "") +
                            (if (!r.success && r.statusCode == 0) " • ${r.errorCode} ${r.errorMessage.take(80)}" else "")
                    }
                    "- $u\n    $line"
                }
            }.awaitAll()
        }
        return rows.joinToString("\n")
    }

    // ------------------------------------------------------------------------------------------------ recon_site
    private val playerWords = Regex(
        "new\\s+Playerjs|Playerjs\\s*\\(|jwplayer\\s*\\(|videojs\\s*\\(|new\\s+Uppod|Uppod\\s*\\(|new\\s+Plyr|Clappr|dashjs|new\\s+Hls\\s*\\(|kodik|alloha|collaps|videocdn|hdvb|turbo\\.to|vibix|cdnvideohub|bazon|apivb|rezka|ashdi|tortuga|fembed|streamtape|doodstream|mixdrop|voe\\.sx|filemoon|vidmoly|sibnet|ok\\.ru/videoembed|vk\\.com/video_ext|rutube\\.ru/play/embed|youtube\\.com/embed|player\\.vimeo",
        RegexOption.IGNORE_CASE,
    )
    private val ajaxRe = Regex("(?:/engine/ajax/[^\"'\\s)<>]+|\\$\\.(?:post|get|ajax)\\(\\s*[\"'][^\"']+|fetch\\(\\s*[\"'][^\"']+|url\\s*:\\s*[\"'][^\"']*(?:ajax|api|player|embed|video|get)[^\"']*)", RegexOption.IGNORE_CASE)
    private val dataUrlRe = Regex("data-(?:src|url|file|video|player|iframe|embed|link|href|id|news_id|post|vid)\\s*=\\s*[\"']([^\"']{3,200})[\"']", RegexOption.IGNORE_CASE)
    private val wallWords = listOf("pro", "премиум", "premium", "только для", "авторизуй", "войдите", "подписк", "vip", "платн", "trailer", "трейлер", "превью", "age", "18+", "cloudflare", "captcha", "verify you are human")

    suspend fun recon(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (!url.startsWith("http", true)) return "ERROR: args.url must be an absolute http(s) URL (home page, listing or a watch page)"
        val res = withTimeoutOrNull(25_000) { http.fetchText(url, policy(), key(url)) } ?: return "ERROR: timeout 25s"
        val html = res.text
        val pageUrl = res.finalUrl.ifBlank { url }
        val o = StringBuilder("RECON HTTP ${res.statusCode} • final=$pageUrl • ${html.length} chars" + if (!res.success) " • ${res.errorCode}: ${res.errorMessage}" else "").append('\n')
        if (html.isBlank()) return o.toString()
        if (pro.noodle.mobile.core.network.CookieChallenge.looksLikeChallenge(html)) o.appendLine("!! JS/COOKIE CHALLENGE page, not the real content (set allow_cookies:true, allow_javascript:true; sniff_page works through it).")
        fun meta(name: String) = Regex("<meta[^>]+(?:name|property)\\s*=\\s*[\"']$name[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.trim()?.take(100)
        o.appendLine("TITLE: $title | generator=${meta("generator")} | og:type=${meta("og:type")} | lang=${Regex("<html[^>]+lang=[\"']([^\"']+)").find(html)?.groupValues?.get(1)}")
        val matches = StudioEngineKnowledge.detect(html, pageUrl)
        o.appendLine("ENGINE FINGERPRINT: " + StudioEngineKnowledge.summary(matches).ifBlank { "unknown (custom site)" })
        Regex("dle_[a-z_]+\\s*=\\s*['\"][^'\"]{0,60}['\"]|news_id\\s*[=:]\\s*['\"]?\\d+").findAll(html).map { it.value }.distinct().take(6).toList()
            .takeIf { it.isNotEmpty() }?.let { o.appendLine("DLE VARS: ${it.joinToString(" ; ")}") }
        Regex("<link[^>]+rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)").find(html)?.let { o.appendLine("CANONICAL: ${it.groupValues[1]}") }

        val scripts = Regex("<script[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).map { absolute(it.groupValues[1], pageUrl) }.distinct().toList()
        o.appendLine("SCRIPTS (${scripts.size}): " + scripts.filterNot { Regex("jquery|bootstrap|fonts\\.|google|yandex|metrika|analytics|gtag|swiper|fancybox", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(14).joinToString(" , ") { it.removePrefix("https://").take(90) })
        val frames = Regex("<(?:iframe|embed)\\b[^>]*?(?:src|data-src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).map { absolute(it.groupValues[1], pageUrl) }.filter { it.startsWith("http") }.distinct().take(8).toList()
        o.appendLine("IFRAMES: " + frames.joinToString(" , ").ifBlank { "none in static HTML (player is probably injected by JS/AJAX — see AJAX and PLAYER HITS, then sniff_page)" })
        val sources = Regex("<(?:source|video)\\b[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).map { absolute(it.groupValues[1], pageUrl) }.distinct().take(5).toList()
        if (sources.isNotEmpty()) o.appendLine("<video>/<source>: " + sources.joinToString(" , "))
        val hits = playerWords.findAll(html).map { m -> html.substring((m.range.first - 60).coerceAtLeast(0), (m.range.last + 120).coerceAtMost(html.length)).replace(Regex("\\s+"), " ") }.distinctBy { it.take(50) }.take(7).toList()
        o.appendLine("PLAYER HITS (${hits.size}):"); hits.forEach { o.appendLine("  …$it…") }
        val ajax = ajaxRe.findAll(html).map { it.value.replace(Regex("^[^\"'/]*[\"']"), "").take(140) }.distinct().take(10).toList()
        o.appendLine("AJAX/API ENDPOINTS: " + ajax.joinToString(" ; ").ifBlank { "none seen" })
        val data = dataUrlRe.findAll(html).map { m -> m.value.take(150) }.distinct().take(8).toList()
        if (data.isNotEmpty()) o.appendLine("DATA-ATTRS: " + data.joinToString(" ; "))
        Regex("<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.let { o.appendLine("JSON-LD: " + it.groupValues[1].replace(Regex("\\s+"), " ").take(420)) }

        val forms = Regex("<form\\b([^>]*)>(.*?)</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).take(4).toList()
        forms.forEach { m ->
            val action = Regex("action\\s*=\\s*[\"']([^\"']*)").find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
            val method = Regex("method\\s*=\\s*[\"']([^\"']*)").find(m.groupValues[1])?.groupValues?.get(1) ?: "get"
            val names = Regex("<input[^>]+name\\s*=\\s*[\"']([^\"']+)[\"'](?:[^>]*value\\s*=\\s*[\"']([^\"']*))?", RegexOption.IGNORE_CASE).findAll(m.groupValues[2]).map { n -> n.groupValues[1] + (n.groupValues[2].takeIf { v -> v.isNotBlank() }?.let { v -> "=${v.take(20)}" } ?: "") }.take(8).toList()
            o.appendLine("FORM: action=$action method=$method inputs=${names.joinToString(",")}")
        }
        val pag = Regex("href\\s*=\\s*[\"']([^\"']*(?:/page/\\d+/?|[?&](?:page|p|cstart|search_start)=\\d+)[^\"']*)", RegexOption.IGNORE_CASE).findAll(html).map { absolute(it.groupValues[1], pageUrl) }.distinct().take(4).toList()
        if (pag.isNotEmpty()) o.appendLine("PAGINATION: " + pag.joinToString(" , "))
        val shapes = runCatching { AutoCards.anchors(html, pageUrl).mapNotNull { CardLearner.shape(it.path) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6) }.getOrDefault(emptyList())
        o.appendLine("TOP LINK SHAPES: " + shapes.joinToString("; ") { "${it.key}×${it.value}" })
        val low = html.lowercase()
        val walls = wallWords.filter { w -> Regex("(?<![a-zа-я0-9])" + Regex.escape(w)).containsMatchIn(low) }.take(8)
        if (walls.isNotEmpty()) o.appendLine("ACCESS-WALL WORDS present: ${walls.joinToString()} — check whether the FULL video is free for guests; if only a trailer/preview is public, say so honestly (give_up) instead of shipping a preview adapter.")
        o.appendLine("NEXT: listing → list_cards; watch page without iframe/player → probe_urls/http_request on the AJAX endpoints above (X-Requested-With: XMLHttpRequest, Referer = page), then sniff_page; player host known → find_streams on its URL.")
        return o.toString().take(7500)
    }
}

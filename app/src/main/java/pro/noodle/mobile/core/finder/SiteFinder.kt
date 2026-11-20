package pro.noodle.mobile.core.finder

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.web.SandboxedWebExtractor
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class FinderEngine(val title: String) { BING("Bing"), DUCKDUCKGO("DuckDuckGo"), GOOGLE("Google"), YANDEX("Яндекс") }

data class FoundSite(
    val host: String,
    val homeUrl: String,
    val title: String,
    val sampleUrl: String,
    val hits: Int,
    val known: Boolean = false,
)

data class FinderResult(val sites: List<FoundSite>, val note: String = "")

/**
 * «Парсер интернета»: берёт выдачу поисковика (Bing/DuckDuckGo — обычным HTTP, Google/Яндекс — через скрытый браузер),
 * вытаскивает ссылки на сайты и группирует их по домену. Выбранный сайт открывается в SNP Studio с уже вписанным адресом.
 */
class SiteFinder(private val context: Context) {
    private val http = SecureHttpClient()
    private val policy = NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = 3 * 1024 * 1024)

    suspend fun search(engine: FinderEngine, query: String, pages: Int, onProgress: (List<FoundSite>, String) -> Unit): FinderResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext FinderResult(emptyList(), "Введите запрос.")
        val known = runCatching { RuntimeAdapterRepository(context).listAll().map { hostOf(it.definition.baseUrl) } }.getOrDefault(emptyList()).toSet()
        val acc = LinkedHashMap<String, MutableSite>()
        var note = ""
        val depth = pages.coerceIn(1, 10)
        for (p in 0 until depth) {
            currentCoroutineContext().ensureActive()
            onProgress(snapshot(acc, known), "${engine.title}: страница ${p + 1} из $depth…")
            val html = fetchPage(engine, q, p)
            if (html == null) { note = "${engine.title}: страница ${p + 1} не загрузилась."; break }
            if (looksBlocked(html)) { note = "${engine.title} показал проверку (капчу) — попробуйте другой поисковик или позже."; break }
            val found = extract(html, engine)
            if (found.isEmpty()) { note = if (p == 0) "${engine.title} не вернул результатов (возможно, блокировка или другой формат выдачи)." else ""; break }
            var fresh = 0
            for ((url, title) in found) {
                val host = hostOf(url)
                if (host.isBlank() || isNoise(host)) continue
                val s = acc.getOrPut(host) { fresh++; MutableSite(host, homeOf(url), title.ifBlank { host }, url) }
                s.hits++
                if (s.title == s.host && title.isNotBlank()) s.title = title
            }
            if (fresh == 0 && p > 0) break
            if (p < depth - 1) delay(900)
        }
        val sites = snapshot(acc, known)
        onProgress(sites, "")
        FinderResult(sites, if (sites.isEmpty() && note.isBlank()) "Ничего не найдено." else note)
    }

    private class MutableSite(val host: String, val home: String, var title: String, val sample: String, var hits: Int = 0)

    private fun snapshot(acc: Map<String, MutableSite>, known: Set<String>) =
        acc.values.map { FoundSite(it.host, it.home, it.title, it.sample, it.hits, it.host in known) }

    private suspend fun fetchPage(engine: FinderEngine, q: String, page: Int): String? {
        val enc = URLEncoder.encode(q, "UTF-8")
        return when (engine) {
            FinderEngine.BING -> http.fetchText("https://www.bing.com/search?q=$enc&first=${page * 10 + 1}&setlang=ru&cc=ru", policy, "finder-bing").takeIf { it.text.isNotBlank() }?.text
            FinderEngine.DUCKDUCKGO -> http.fetchText("https://html.duckduckgo.com/html/?q=$enc&s=${page * 30}&dc=${page * 30 + 1}&kl=ru-ru", policy, "finder-ddg").takeIf { it.text.isNotBlank() }?.text
            FinderEngine.GOOGLE -> browser("https://www.google.com/search?q=$enc&start=${page * 10}&hl=ru&num=10")
            FinderEngine.YANDEX -> browser("https://yandex.ru/search/?text=$enc&p=$page&lr=213")
        }
    }

    private suspend fun browser(url: String): String? {
        val def = AdapterDefinition(id = "finder", name = "finder", baseUrl = url, engine = "generic_html5", allowedHosts = emptyList(), allowJavaScript = true, allowCookies = true)
        val r = withTimeoutOrNull(35_000) { SandboxedWebExtractor(context).extract(url, def, timeoutMs = 18_000, sniff = false) } ?: return null
        return r.html.takeIf { r.success && it.isNotBlank() }
    }

    private fun looksBlocked(html: String): Boolean {
        val h = html.take(60_000).lowercase()
        return "showcaptcha" in h || "unusual traffic" in h || "/sorry/index" in h || "smartcaptcha" in h || "are you a human" in h ||
            "id=\"b_captcha\"" in h || "anomaly-modal" in h || "captcha-page" in h
    }

    private val anchorRe = Regex("(<a\\b[^>]*>)(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val hrefRe = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** Список (прямой URL, заголовок) в порядке выдачи. */
    internal fun extract(html: String, engine: FinderEngine): List<Pair<String, String>> {
        val all = anchorRe.findAll(html).mapNotNull { m ->
            val tag = m.groupValues[1]
            val raw = hrefRe.find(tag)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            val url = unwrap(raw) ?: return@mapNotNull null
            Triple(tag, m.groupValues[2], url)
        }.toList()
        val preferred = all.filter { (tag, inner, _) ->
            when (engine) {
                FinderEngine.BING -> false
                FinderEngine.DUCKDUCKGO -> "result__a" in tag
                FinderEngine.GOOGLE -> "<h3" in inner.lowercase()
                FinderEngine.YANDEX -> "OrganicTitle" in tag || "organic__url" in tag
            }
        }
        val bing = if (engine == FinderEngine.BING) Regex("<h2[^>]*>\\s*(<a\\b[^>]*>)(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).mapNotNull { m ->
            val raw = hrefRe.find(m.groupValues[1])?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            unwrap(raw)?.let { Triple(m.groupValues[1], m.groupValues[2], it) }
        }.toList() else emptyList()
        val chosen = (if (bing.isNotEmpty()) bing else if (preferred.isNotEmpty()) preferred else all)
        return chosen.map { (_, inner, url) -> url to clean(inner) }.distinctBy { it.first }
    }

    private fun unwrap(href: String): String? {
        var u = href
        if (u.startsWith("//")) u = "https:$u"
        val low = u.lowercase()
        runCatching {
            when {
                "bing.com/ck/a" in low -> {
                    val v = Regex("[?&]u=([^&]+)").find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
                    val b64 = v.removePrefix("a1")
                    return String(Base64.decode(b64.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }, Base64.DEFAULT), Charsets.UTF_8).takeIf { it.startsWith("http") }
                }
                "duckduckgo.com/l/" in low || u.startsWith("/l/?") -> return Regex("[?&]uddg=([^&]+)").find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                low.startsWith("/url?") || "google." in low && "/url?" in low ->
                    return Regex("[?&](?:q|url)=(https?[^&]+)").find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            }
        }
        return u.takeIf { it.startsWith("http", true) }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    private fun clean(inner: String) = unescape(inner.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim().take(90)

    private fun hostOf(url: String) = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
    private fun homeOf(url: String) = runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault(url)

    private val noise = listOf(
        "google.", "gstatic.", "googleusercontent.", "bing.", "microsoft.", "msn.", "live.com", "duckduckgo.", "yandex.", "ya.ru", "yastatic.",
        "youtube.", "youtu.be", "wikipedia.", "wikimedia.", "facebook.", "instagram.", "twitter.", "x.com", "vk.com", "ok.ru", "t.me", "telegram.",
        "reddit.", "pinterest.", "apple.com", "amazon.", "mail.ru", "baidu.", "tiktok.", "github.com", "stackoverflow.", "archive.org", "w3.org",
    )
    private fun isNoise(host: String) = noise.any { host.contains(it) || host.endsWith(it.trimEnd('.')) }
}

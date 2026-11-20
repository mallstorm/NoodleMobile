package pro.noodle.mobile.core.finder

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.studio.StudioEngineKnowledge
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class FinderEngine(val title: String) { ALL("Все сразу"), DUCKDUCKGO("DuckDuckGo"), YAHOO("Yahoo") }

data class FoundSite(
    val host: String,
    val homeUrl: String,
    val title: String,
    val sampleUrl: String,
    val hits: Int,
    val known: Boolean = false,
    val engines: Int = 1,
    /** null — не проверялся; иначе похож ли сайт на видеосайт (по главной странице). */
    val videoLike: Boolean? = null,
    val cms: String = "",
)

data class FinderResult(val sites: List<FoundSite>, val note: String = "")

data class FinderOptions(
    val pages: Int = 5,
    val variants: Boolean = true,
    val hideMainstream: Boolean = true,
    val verify: Boolean = true,
)

/**
 * «Парсер интернета»: собирает выдачу нескольких поисковиков параллельно (Bing/DDG/Brave/Mojeek/Yahoo — HTTP,
 * Google/Яндекс — скрытый браузер), добавляет варианты запроса, вытаскивает прямые ссылки, группирует по домену,
 * отсеивает крупные стриминговые сервисы и проверяет главные страницы: похож ли сайт на видеосайт и на каком он движке.
 */
class SiteFinder(private val context: Context) {
    private val http = SecureHttpClient()
    private val policy = NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = 3 * 1024 * 1024)

    private class MutableSite(val host: String, val home: String, var title: String, val sample: String) {
        var hits = 0
        val engines = HashSet<String>()
        var videoLike: Boolean? = null
        var cms = ""
    }

    suspend fun search(engine: FinderEngine, query: String, opt: FinderOptions, onProgress: (List<FoundSite>, String) -> Unit): FinderResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext FinderResult(emptyList(), "Введите запрос.")
        val known = runCatching { RuntimeAdapterRepository(context).listAll().map { hostOf(it.definition.baseUrl) } }.getOrDefault(emptyList()).toSet()
        val acc = LinkedHashMap<String, MutableSite>()
        val notes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val status = java.util.concurrent.ConcurrentHashMap<String, String>()
        val depth = opt.pages.coerceIn(1, 15)
        val engines = if (engine == FinderEngine.ALL) listOf(FinderEngine.DUCKDUCKGO, FinderEngine.YAHOO) else listOf(engine)
        val queries = if (opt.variants) variants(q) else listOf(q)
        var lastPush = 0L
        fun push(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPush < 400) return
            lastPush = now
            onProgress(snapshot(acc, known), status.entries.sortedBy { it.key }.joinToString("  •  ") { "${it.key}: ${it.value}" })
        }
        coroutineScope {
            engines.map { e ->
                async {
                    var total = 0
                    for ((vi, qq) in queries.withIndex()) {
                        val d = if (vi == 0) depth else (depth / 3).coerceAtLeast(2)
                        total += runEngine(e, qq, d, acc, notes, opt.hideMainstream) { page ->
                            status[e.title] = "стр. $page/$d" + if (queries.size > 1) " (запрос ${vi + 1}/${queries.size})" else ""
                            push()
                        }
                        if (total == 0 && vi == 0) break // движок не отдал ничего — варианты запроса не помогут
                    }
                    status[e.title] = if (total > 0) "готово" else "нет результатов"
                    push(true)
                }
            }.awaitAll()
        }
        if (opt.verify && acc.isNotEmpty()) verify(acc) { status["Проверка"] = it; push() }
        status.remove("Проверка")
        val sites = snapshot(acc, known)
        onProgress(sites, "")
        val note = notes.distinct().joinToString("\n")
        FinderResult(sites, if (sites.isEmpty() && note.isBlank()) "Ничего не найдено." else note)
    }

    private fun variants(q: String): List<String> {
        val cyr = q.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }
        return if (cyr) listOf(q, "$q смотреть онлайн бесплатно", "$q сайт каталог видео")
        else listOf(q, "$q watch online free", "$q site videos")
    }

    private suspend fun runEngine(
        e: FinderEngine, q: String, depth: Int, acc: MutableMap<String, MutableSite>, notes: MutableList<String>,
        hideMainstream: Boolean, onPage: (Int) -> Unit,
    ): Int {
        var found = 0
        var emptyStreak = 0
        var ddgNext: Map<String, String>? = null
        for (p in 0 until depth) {
            currentCoroutineContext().ensureActive()
            onPage(p + 1)
            var res = fetchPage(e, q, p, ddgNext)
            if (res.html.isBlank()) { delay(1500); res = fetchPage(e, q, p, ddgNext) } // одна повторная попытка
            if (res.html.isBlank()) { notes.add("${e.title}: страница ${p + 1} не загрузилась${if (res.error.isNotBlank()) " (${res.error})" else ""}."); break }
            if (looksBlocked(res.html)) { notes.add("${e.title} показал проверку (капчу) на странице ${p + 1} — выдача оттуда недоступна."); break }
            if (e == FinderEngine.DUCKDUCKGO) ddgNext = ddgForm(res.html)
            val links = extract(res.html, e)
            var fresh = 0
            synchronized(acc) {
                for ((url, title) in links) {
                    val host = hostOf(url)
                    if (host.isBlank() || !host.contains('.') || isNoise(host, hideMainstream)) continue
                    val s = acc.getOrPut(host) { fresh++; MutableSite(host, homeOf(url), title.ifBlank { host }, url) }
                    s.hits++; s.engines.add(e.title)
                    if (s.title == s.host && title.isNotBlank()) s.title = title
                }
            }
            found += links.size
            if (links.isEmpty() || fresh == 0) emptyStreak++ else emptyStreak = 0
            if (e == FinderEngine.DUCKDUCKGO && ddgNext == null) break
            if (emptyStreak >= 2) break
            if (p < depth - 1) delay(700L + (0..500).random())
        }
        if (found == 0) notes.add("${e.title} не вернул результатов (блокировка или другой формат выдачи).")
        return found
    }

    private fun snapshot(acc: Map<String, MutableSite>, known: Set<String>): List<FoundSite> = synchronized(acc) {
        acc.values.map { FoundSite(it.host, it.home, it.title, it.sample, it.hits, it.host in known, it.engines.size, it.videoLike, it.cms) }
            .sortedWith(compareByDescending<FoundSite> { if (it.videoLike == true) 1 else 0 }.thenByDescending { it.engines * 3 + it.hits })
    }

    private suspend fun fetchPage(e: FinderEngine, q: String, page: Int, ddgNext: Map<String, String>?): WebPage {
        val enc = URLEncoder.encode(q, "UTF-8")
        suspend fun h(url: String, key: String) = http.fetchText(url, policy, key).let { WebPage(it.text, it.finalUrl, if (it.text.isBlank()) it.errorMessage.ifBlank { "HTTP ${it.statusCode}" } else "") }
        return runCatching {
            when (e) {
                FinderEngine.DUCKDUCKGO ->
                    if (page == 0) h("https://html.duckduckgo.com/html/?q=$enc&kl=wt-wt", "finder-ddg")
                    else if (ddgNext == null) WebPage("", "", "нет ссылки на следующую страницу")
                    else http.requestText("https://html.duckduckgo.com/html/", policy, "finder-ddg", method = "POST", form = ddgNext, headers = mapOf("Referer" to "https://html.duckduckgo.com/"))
                        .let { WebPage(it.text, it.finalUrl, if (it.text.isBlank()) it.errorMessage else "") }
                FinderEngine.YAHOO -> h("https://search.yahoo.com/search?p=$enc&b=${page * 10 + 1}&ei=UTF-8&vl=lang_ru", "finder-yahoo")
                FinderEngine.ALL -> WebPage("", "", "")
            }
        }.getOrElse { WebPage("", "", it.message.orEmpty().take(80)) }
    }

    /** Форма «Next» html-версии DuckDuckGo: именно она несёт токены пагинации. */
    private fun ddgForm(html: String): Map<String, String>? {
        val forms = Regex("<form[^>]*>(.*?)</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).map { it.groupValues[1] }
        val f = forms.firstOrNull { Regex("value\\s*=\\s*[\"']Next", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
        val out = LinkedHashMap<String, String>()
        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(f).forEach { m ->
            val tag = m.value
            val name = Regex("name\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@forEach
            val value = Regex("value\\s*=\\s*[\"']([^\"']*)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1).orEmpty()
            if (name.isNotBlank() && !(Regex("type\\s*=\\s*[\"']submit", RegexOption.IGNORE_CASE).containsMatchIn(tag))) out[name] = unescape(value)
        }
        return out.takeIf { it.containsKey("q") }
    }

    /** Лёгкая проверка главных страниц: видео-признаки и движок (по базе отпечатков Studio). */
    private suspend fun verify(acc: MutableMap<String, MutableSite>, onStatus: (String) -> Unit) {
        val targets = synchronized(acc) { acc.values.sortedByDescending { it.engines.size * 3 + it.hits }.take(80).toList() }
        val gate = Semaphore(6)
        var done = 0
        coroutineScope {
            targets.map { s ->
                async {
                    gate.withPermit {
                        val html = withTimeoutOrNull(9_000) { runCatching { http.fetchText(s.home, policy, "finder-verify").text }.getOrDefault("") }.orEmpty()
                        val low = html.take(250_000).lowercase()
                        if (low.isNotBlank()) {
                            val score = listOf("<video", ".m3u8", ".mp4", "player", "смотреть", "watch", "embed", "thumb", "preview", "duration", "online", "онлайн", "видео", "video")
                                .count { it in low } + (if (Regex("class=\"[^\"]*(video|movie|film|item|thumb)[^\"]*\"").findAll(low).count() > 8) 2 else 0)
                            val cms = runCatching { StudioEngineKnowledge.detect(html, s.home).firstOrNull()?.profile?.label.orEmpty() }.getOrDefault("")
                            synchronized(acc) { s.videoLike = score >= 5; s.cms = cms }
                        } else synchronized(acc) { s.videoLike = false }
                        synchronized(this@SiteFinder) { done++; onStatus("$done/${targets.size}") }
                    }
                }
            }.awaitAll()
        }
    }

    private fun looksBlocked(html: String): Boolean {
        val h = html.take(80_000).lowercase()
        return "showcaptcha" in h || "unusual traffic" in h || "/sorry/index" in h || "smartcaptcha" in h || "are you a human" in h ||
            "id=\"b_captcha\"" in h || "anomaly-modal" in h || "captcha-page" in h || "g-recaptcha" in h && "<h3" !in h ||
            ("введите символы" in h && "капч" in h)
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
                FinderEngine.DUCKDUCKGO -> "result__a" in tag
                else -> false
            }
        }
        val chosen = if (preferred.isNotEmpty()) preferred else all
        return chosen.map { (_, inner, url) -> url to clean(inner) }.distinctBy { it.first }
    }

    private fun unwrap(href: String): String? {
        var u = href
        if (u.startsWith("//")) u = "https:$u"
        val low = u.lowercase()
        runCatching {
            when {
                "duckduckgo.com/l/" in low || u.startsWith("/l/?") -> return Regex("[?&]uddg=([^&]+)").find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                "search.yahoo.com" in low && "/ru=" in low ->
                    return Regex("/RU=([^/]+)/R[KS]=", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            }
        }
        return u.takeIf { it.startsWith("http", true) }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
    private fun clean(inner: String) = unescape(inner.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim().take(90)

    private fun hostOf(url: String) = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
    private fun homeOf(url: String) = runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault(url)

    private val engineNoise = listOf(
        "google.", "gstatic.", "googleusercontent.", "bing.", "microsoft.", "msn.", "live.com", "duckduckgo.", "yandex.", "ya.ru", "yastatic.",
        "brave.com", "mojeek.", "yahoo.", "yimg.", "oath.com", "aol.com", "baidu.", "w3.org", "schema.org",
    )
    /** Соцсети, энциклопедии, магазины, новости и т. п. — не подходят для адаптера. */
    private val generalNoise = listOf(
        "youtube.", "youtu.be", "wikipedia.", "wikimedia.", "wiktionary.", "facebook.", "instagram.", "twitter.", "x.com", "vk.com", "vk.ru", "ok.ru", "t.me", "telegram.",
        "reddit.", "pinterest.", "apple.com", "amazon.", "mail.ru", "tiktok.", "github.com", "stackoverflow.", "archive.org", "linkedin.", "quora.", "tumblr.",
        "dzen.ru", "zen.yandex", "livejournal.", "habr.com", "pikabu.", "otvet.", "answers.", "fandom.", "medium.com", "yandex", "avito.", "ozon.", "wildberries.", "aliexpress.",
        "hh.ru", "2gis.", "rbc.", "ria.ru", "lenta.ru", "kommersant.", "gazeta.ru", "tass.", "bbc.", "cnn.", "nytimes.", "theguardian.", "forbes.", "wired.", "techcrunch.",
        "gosuslugi.", "mos.ru", ".gov", ".edu", "kaspersky.", "drweb.", "play.google", "microsoft.", "steampowered.", "store.",
    )
    /** Крупные легальные стриминги и каталоги фильмов: «обычный» русскоязычный/мировой мейнстрим. */
    private val mainstream = listOf(
        "kinopoisk.", "rutube.", "ivi.ru", "okko.", "wink.ru", "start.ru", "more.tv", "premier.one", "kion.ru", "film.ru", "megogo.", "amediateka.", "tvigle.", "smotrim.", "1tv.ru", "ntv.ru",
        "kino-teatr.", "kinoafisha.", "afisha.", "kinonews.", "kinopoisk", "ruskino.", "tvzavr.", "viju.", "netflix.", "hbomax.", "max.com", "disneyplus.", "hulu.", "primevideo.", "peacocktv.",
        "paramountplus.", "imdb.", "rottentomatoes.", "metacritic.", "letterboxd.", "themoviedb.", "tmdb.", "justwatch.", "vimeo.", "dailymotion.", "twitch.", "vkvideo.", "kino.pub", "plex.tv",
        "crunchyroll.", "tubitv.", "pluto.tv", "roku.", "mubi.", "kinorium.", "myshows.", "tv.yandex",
    )

    private fun isNoise(host: String, hideMainstream: Boolean): Boolean {
        fun m(l: List<String>) = l.any { host.contains(it) || host.endsWith(it.trimEnd('.')) }
        return m(engineNoise) || m(generalNoise) || (hideMainstream && m(mainstream))
    }
}

package pro.noodle.mobile.core.studio.auto

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.impl.AutoCards
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.EngineSearchItem
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.ResolverPipeline
import pro.noodle.mobile.core.resolver.StreamHints
import pro.noodle.mobile.core.version.BuildIdentity
import java.net.URI
import java.net.URLDecoder

data class AutoSearchCandidate(
    val label: String,
    val template: String,
    val url: String,
    val status: Int = 0,
    val loaded: Boolean = false,
    val items: Int = 0,
    val relevant: Int = 0,
    val withThumbs: Int = 0,
    val score: Int = -1000,
    val pattern: String = "",
    val error: String = "",
    val withDuration: Int = 0,
    val alternatives: List<String> = emptyList(),
    val groups: String = "",
)

data class AutoPlaybackCheck(val pageUrl: String, val ok: Boolean, val sources: Int, val detail: String)

data class AutoBuildResult(
    val success: Boolean,
    val adapterJson: String = "",
    val adapterId: String = "",
    val siteName: String = "",
    val baseUrl: String = "",
    val engine: String = "",
    val searchTemplate: String = "",
    val pageTemplate: String = "",
    val linkPattern: String = "",
    val searchUrl: String = "",
    val samples: List<EngineSearchItem> = emptyList(),
    val playback: List<AutoPlaybackCheck> = emptyList(),
    val needsJavaScript: Boolean = false,
    val candidates: List<AutoSearchCandidate> = emptyList(),
    val errorCode: String = "",
    val message: String = "",
    val log: List<String> = emptyList(),
    val stream: StreamLearning? = null,
    val thumbOk: Int = 0,
    val thumbTotal: Int = 0,
) {
    val playbackOk: Int get() = playback.count { it.ok }
    fun report(): String = buildString {
        appendLine("SNP AUTO BUILDER REPORT")
        appendLine("success=$success code=$errorCode")
        appendLine("message=$message")
        appendLine("site=$siteName base=$baseUrl engine=$engine")
        appendLine("search_template=$searchTemplate")
        appendLine("page_template=$pageTemplate")
        appendLine("link_pattern=$linkPattern")
        appendLine("search_url=$searchUrl")
        appendLine("samples=${samples.size} playback_ok=$playbackOk/${playback.size} needs_js=$needsJavaScript")
        appendLine("thumbnails_ok=$thumbOk/$thumbTotal")
        stream?.let { append(it.report()) }
        samples.take(5).forEach { appendLine("  • ${it.title.take(70)} | ${it.watchPath} | thumb=${it.thumbnailUrl.isNotBlank()} | ${it.durationText}") }
        playback.forEach { appendLine("  ▶ ${if (it.ok) "OK" else "FAIL"} ${it.pageUrl} — ${it.detail}") }
        appendLine("candidates:")
        candidates.sortedByDescending { it.score }.take(10).forEach {
            appendLine("  - ${it.label} score=${it.score} http=${it.status} items=${it.items} rel=${it.relevant} thumbs=${it.withThumbs} dur=${it.withDuration} ${it.error} ${it.url}")
            if (it.groups.isNotBlank()) appendLine("      groups: ${it.groups}")
        }
        appendLine("log:")
        log.forEach { appendLine("  $it") }
    }
}

/**
 * One-button SNP creation: «сайт + запрос → рабочий адаптер».
 *
 * Pure JVM (no Android types) so the very same code runs inside the app and in CI live tests.
 *  1. Opens the home page (following redirects to the real host) and reads what the site itself
 *     declares: OpenSearch descriptor, schema.org SearchAction and every GET search form.
 *  2. Probes those routes first, then a catalogue of common CMS/tube conventions.
 *  3. For every loaded result page learns the video-card link shape (path regex) by clustering
 *     links, then scores the page by cards, previews and relevance to the query.
 *  4. Finds the pagination route on the winning page and verifies page 2 really changes.
 *  5. Opens sample cards and runs the production resolver chain to confirm a playable stream.
 */
class SnpAutoBuilder(private val http: SecureHttpClient = SecureHttpClient()) {

    suspend fun build(rawUrl: String, rawQuery: String, onProgress: (String) -> Unit = {}, ai: StreamAi? = null): AutoBuildResult = coroutineScope {
        val log = mutableListOf<String>()
        fun step(msg: String) { log += msg; onProgress(msg) }
        val query = rawQuery.trim().ifBlank { "video" }
        val input = normalizeInput(rawUrl) ?: return@coroutineScope AutoBuildResult(false, errorCode = "AUTO_URL_INVALID", message = "Укажите адрес сайта, например example.com", log = log)

        step("Открываю сайт…")
        val home = http.fetchText(input, NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = HOME_MAX_BYTES, maxRedirects = 6), SOURCE_KEY)
        if (!home.success) {
            return@coroutineScope AutoBuildResult(false, errorCode = "AUTO_SITE_UNREACHABLE", message = "Сайт не открылся: ${home.errorCode} ${home.errorMessage}".trim(), log = log)
        }
        val base = originOf(home.finalUrl.ifBlank { input })
        val host = hostOf(base)
        val root = registrable(host)
        val siteHosts = linkedSetOf(host, "*.$root").apply { if (root != host) add(root) }
        val siteName = siteName(home.text, host)
        step("Сайт: $siteName ($host)")

        val declared = (openSearchRoutes(home.text, base, siteHosts) + declaredSearchRoutes(home.text, base, siteHosts)).distinctBy { it.second }
        if (declared.isNotEmpty()) step("Сайт сам объявил поиск: ${declared.joinToString { it.first }}")
        val specs = (declared + KNOWN_ROUTES.map { it.first to it.second }).distinctBy { it.second.lowercase() }
        val homeHrefs = AutoCards.anchors(home.text, base).map { it.href }.toSet()

        step("Проверяю ${specs.size} вариантов поиска по запросу «$query»…")
        val semaphore = Semaphore(PARALLELISM)
        val probes = specs.mapIndexed { index, (label, template) ->
            async {
                semaphore.withPermit {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) { probeSearch(label, template, index < declared.size, base, siteHosts, query, homeHrefs) }
                        ?: AutoSearchCandidate(label, template, render(template, base, query, 1), error = "timeout")
                }
            }
        }.awaitAll()

        val ranked = probes.filter { it.loaded && it.items >= MIN_CARDS && it.pattern.isNotBlank() }.sortedByDescending { it.score }
        if (ranked.isEmpty()) {
            val loaded = probes.count { it.loaded }
            return@coroutineScope AutoBuildResult(
                false, siteName = siteName, baseUrl = base, candidates = probes, log = log,
                errorCode = if (loaded == 0) "AUTO_SEARCH_UNREACHABLE" else "AUTO_NO_CARDS",
                message = if (loaded == 0) "Ни один вариант поиска не открылся. Возможно, сайт закрыт защитой от ботов."
                else "Страницы поиска открываются, но карточки видео не распознаны. Попробуйте другой запрос или включите режим браузера.",
            )
        }

        // Try the strongest (search page, card pattern) pairs until one actually plays. This rejects
        // category/model tiles and "search" pages that only show a catalogue.
        val attempts = buildList {
            ranked.take(3).forEach { c ->
                add(c to c.pattern)
                c.alternatives.forEach { alt -> add(c to alt) }
            }
        }.distinctBy { (c, p) -> c.url + "|" + p }.take(MAX_ATTEMPTS)
        val mediaHosts = linkedSetOf<String>()
        val pages = mutableMapOf<String, String>()
        var chosen: Triple<AutoSearchCandidate, String, List<EngineSearchItem>>? = null
        var chosenChecks: List<AutoPlaybackCheck> = emptyList()
        var firstTry: Triple<AutoSearchCandidate, String, List<EngineSearchItem>>? = null
        var firstChecks: List<AutoPlaybackCheck> = emptyList()
        for ((index, attempt) in attempts.withIndex()) {
            val (cand, pat) = attempt
            val html = pages.getOrPut(cand.url) { http.fetchText(cand.url, policy(siteHosts), SOURCE_KEY).text }
            val found = AutoCards.extract(html, base, Regex(pat, RegexOption.IGNORE_CASE), 40)
            if (found.size < MIN_CARDS) continue
            step(if (index == 0) "Поиск найден: ${cand.template} • карточек ${found.size}" else "Пробую другой вариант: ${cand.template} • карточек ${found.size}")
            step("Проверяю воспроизведение на карточках…")
            val hosts = linkedSetOf<String>()
            val checks = spread(found, PLAYBACK_SAMPLES).map { item -> async { verifyPlayback(item.watchPath, siteHosts, hosts) } }.awaitAll()
            val ok = checks.count { it.ok }
            step("Видео: открыто $ok из ${checks.size}")
            // Without playback, only accept a fallback that clearly looks like video cards (durations).
            val videoLike = found.count { it.durationText.isNotBlank() } >= maxOf(MIN_CARDS, found.size / 4)
            if (firstTry == null && videoLike) { firstTry = Triple(cand, pat, found); firstChecks = checks }
            if (ok > 0) { chosen = Triple(cand, pat, found); chosenChecks = checks; mediaHosts += hosts; break }
        }
        val pick = chosen ?: firstTry ?: return@coroutineScope AutoBuildResult(
            false, siteName = siteName, baseUrl = base, candidates = probes, log = log, errorCode = "AUTO_NO_VIDEO_CARDS",
            message = "По запросу «$query» на сайте нашлись только разделы/категории, а не ролики. Попробуйте другое слово — например, по-русски или как его пишут в поиске самого сайта.",
        )
        val best = pick.first.copy(pattern = pick.second)
        val items = pick.third
        val checks = if (chosen != null) chosenChecks else firstChecks
        val okCount = checks.count { it.ok }
        val needsJs = okCount == 0
        val pattern = Regex(best.pattern, RegexOption.IGNORE_CASE)
        val html = pages[best.url].orEmpty()

        step("Ищу постраничную навигацию…")
        val pageTemplate = findPageTemplate(html, best, base, siteHosts, query, pattern, items)
        if (pageTemplate.isNotBlank()) step("Страницы: $pageTemplate") else step("Пагинация не подтверждена — будет только первая страница")

        // Covers: they live on image CDNs. Verify they really load with the Referer the app will send.
        val thumbItems = spread(items.filter { it.thumbnailUrl.isNotBlank() }, 4)
        val thumbOk = if (thumbItems.isEmpty()) 0 else thumbItems.map { item ->
            async { runCatching { pro.noodle.mobile.core.media.ThumbnailFetcher.fetch(http, item.thumbnailUrl, base, item.watchPath, SOURCE_KEY, true, 1536 * 1024).ok }.getOrDefault(false) }
        }.awaitAll().count { it }
        step(if (thumbItems.isEmpty()) "Обложки: у карточек их нет" else "Обложки: загружено $thumbOk из ${thumbItems.size}")
        val imageHosts = items.mapNotNull { hostOf(it.thumbnailUrl).takeIf { h -> h.isNotBlank() } }.filter { !AutoCards.sameSite(it, root) }.distinct().take(6)

        // Several real videos: measure every stream, learn which one is the full video, keep the rule.
        var learning: StreamLearning? = null
        if (okCount > 0) {
            learning = withTimeoutOrNull(LEARN_TIMEOUT_MS) { StreamLearner(http, VIEW_STRATEGIES, ENGINE).learn(items, SOURCE_KEY, ai) { step(it) } }
            learning?.let { step(it.summary + if (it.hints.isEmpty) "" else " Правила сохранены в адаптер.") }
        }

        val allowed = linkedSetOf<String>().apply { addAll(siteHosts); addAll(mediaHosts.filter { !AutoCards.sameSite(it, root) }.take(12)); addAll(imageHosts) }
        val adapterId = root.replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(120)
        val json = adapterJson(adapterId, siteName, base, best.template, pageTemplate, best.pattern, query, allowed.toList(), needsJs, learning?.hints)
        val validation = AdapterValidator.validate(json)
        if (!validation.isValid) {
            return@coroutineScope AutoBuildResult(false, adapterJson = json, siteName = siteName, baseUrl = base, candidates = probes, log = log, errorCode = validation.errorCode, message = "Сгенерированный SNP не прошёл проверку схемы: ${validation.errorMessage}")
        }
        AutoBuildResult(
            success = true,
            adapterJson = json,
            adapterId = adapterId,
            siteName = siteName,
            baseUrl = base,
            engine = ENGINE,
            searchTemplate = best.template,
            pageTemplate = pageTemplate,
            linkPattern = best.pattern,
            searchUrl = best.url,
            samples = items,
            playback = checks,
            needsJavaScript = needsJs,
            candidates = probes,
            log = log,
            stream = learning,
            thumbOk = thumbOk,
            thumbTotal = thumbItems.size,
            message = when {
                okCount > 0 && learning != null && learning.evidence.isNotEmpty() && learning.confidence == 0 -> "Поиск работает, но настоящий поток уверенно найти не удалось — возможно, воспроизводятся только короткие превью."
                okCount > 0 && thumbItems.isNotEmpty() && thumbOk == 0 -> "Адаптер готов, но обложки не загружаются (сайт блокирует картинки) — карточки будут без картинок."
                okCount > 0 -> "Адаптер готов: поиск и воспроизведение подтверждены."
                else -> "Поиск работает; поток спрятан в JavaScript — включён режим браузера для плеера."
            },
        )
    }

    private suspend fun probeSearch(label: String, template: String, declared: Boolean, base: String, hosts: Set<String>, query: String, homeHrefs: Set<String>): AutoSearchCandidate {
        val url = render(template, base, query, 1)
        val net = http.fetchText(url, policy(hosts), SOURCE_KEY)
        if (!net.success) return AutoSearchCandidate(label, template, url, net.statusCode, error = net.errorCode)
        val all = CardLearner.learnAll(net.text, net.finalUrl.ifBlank { url }, query)
        val learned = all.firstOrNull()
            ?: return AutoSearchCandidate(label, template, url, net.statusCode, loaded = true, error = "no_cards")
        val groups = all.take(4).joinToString(" | ") { g -> "${g.shape} n=${g.distinct} img=${g.withImages} dur=${g.withDuration} s=${g.score} e.g. ${g.examples.firstOrNull().orEmpty()}" }
        val items = AutoCards.extract(net.text, base, Regex(learned.pattern, RegexOption.IGNORE_CASE), 60)
        val tokens = queryTokens(query)
        val relevant = items.count { item -> val slug = AutoCards.pathOf(item.watchPath).trimEnd('/').substringAfterLast('/').lowercase(); tokens.any { t -> item.title.lowercase().contains(t) || slug.contains(t) } }
        val thumbs = items.count { it.thumbnailUrl.isNotBlank() }
        val durations = items.count { it.durationText.isNotBlank() }
        val overlapWithHome = if (items.isEmpty()) 0.0 else items.count { it.watchPath in homeHrefs }.toDouble() / items.size
        var score = minOf(items.size, 40) * 2 + relevant * 4 + thumbs + durations * 2 + learned.score / 4
        if (items.isNotEmpty() && durations.toDouble() / items.size < 0.25) score -= 60
        if (declared) score += 12
        if (overlapWithHome > 0.8) score -= 200 // the "search" just returned the home page
        if (items.size < MIN_CARDS) score -= 100
        if (relevant == 0 && tokens.isNotEmpty()) score -= 15
        val finalPath = AutoCards.pathOf(net.finalUrl)
        if (finalPath == "/" && AutoCards.pathOf(url) != "/") score -= 25
        return AutoSearchCandidate(label, template, net.finalUrl.ifBlank { url }, net.statusCode, true, items.size, relevant, thumbs, score, learned.pattern,
            withDuration = durations, alternatives = all.drop(1).take(2).map { it.pattern }, groups = groups)
    }

    private suspend fun findPageTemplate(html: String, best: AutoSearchCandidate, base: String, hosts: Set<String>, query: String, pattern: Regex, firstItems: List<EngineSearchItem>): String {
        val firstSet = firstItems.map { it.watchPath }.toSet()
        val guesses = linkedSetOf<String>()
        val encodedVariants = listOf(EngineParsing.encodedQuery(query), EngineParsing.encodedQuery(query).replace("%20", "+"), query.trim().replace(' ', '-'), query.trim().replace(' ', '+'), query.trim())
            .map { it.lowercase() }.distinct()
        val pageTokens = listOf(
            Regex("([?&](?:page|p|pg|paged|pagenum|page_num|from|cpage|search_page)=)2(?=$|&)", RegexOption.IGNORE_CASE),
            Regex("(/page/?)2(?=/|$|\\?)", RegexOption.IGNORE_CASE),
            Regex("(/)2(?=/?$|/?\\?)"),
            Regex("([-_]page[-_]?)2(?=\\.html|/|$)", RegexOption.IGNORE_CASE),
        )
        for (a in AutoCards.anchors(html, base)) {
            val href = a.href
            if (!AutoCards.sameSite(AutoCards.hostOf(href), registrable(AutoCards.hostOf(base)))) continue
            val lower = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href).lowercase()
            val hrefLower = href.lowercase()
            val variant = encodedVariants.firstOrNull { it.isNotBlank() && hrefLower.contains(it) } ?: encodedVariants.firstOrNull { it.isNotBlank() && lower.contains(it) } ?: continue
            for (token in pageTokens) {
                val m = token.find(hrefLower) ?: continue
                val origin = Regex("^https?://[^/]+").find(hrefLower)?.value.orEmpty()
                var t = hrefLower.substring(0, m.range.first) + m.groupValues[1] + "{page}" + hrefLower.substring(m.range.last + 1)
                // Replace the query only where it is a whole path segment or parameter value, never in the host.
                val rest = t.removePrefix(origin)
                val bounded = Regex("(?<=[=/])" + Regex.escape(variant) + "(?=$|[&/?#.])")
                val replaced = bounded.replace(rest, "{query}")
                if (!replaced.contains("{query}")) continue
                t = origin + replaced
                guesses += relative(t, base)
                break
            }
            if (guesses.size >= 4) break
        }
        val s = best.template
        if (s.contains('?')) guesses += "$s&page={page}" else {
            guesses += "$s?page={page}"
            guesses += s.trimEnd('/') + "/{page}/"
            guesses += s.trimEnd('/') + "/page/{page}/"
        }
        for (g in guesses.take(7)) {
            val url = render(g, base, query, 2)
            val net = withTimeoutOrNull(PROBE_TIMEOUT_MS) { http.fetchText(url, policy(hosts), SOURCE_KEY) } ?: continue
            if (!net.success) continue
            val page2 = AutoCards.extract(net.text, base, pattern, 60)
            val fresh = page2.count { it.watchPath !in firstSet }
            if (page2.size >= MIN_CARDS && fresh >= page2.size / 2) return g
        }
        return ""
    }

    private suspend fun verifyPlayback(pageUrl: String, hosts: Set<String>, mediaHosts: MutableSet<String>): AutoPlaybackCheck {
        val page = withTimeoutOrNull(VIEW_TIMEOUT_MS) { http.fetchText(pageUrl, NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = VIEW_MAX_BYTES, maxRedirects = 5), SOURCE_KEY) }
            ?: return AutoPlaybackCheck(pageUrl, false, 0, "timeout")
        if (!page.success) return AutoPlaybackCheck(pageUrl, false, 0, "${page.errorCode} ${page.errorMessage}".trim())
        val seen = mutableSetOf<String>()
        val fetcher = ResolverFetcher { url ->
            hostOf(url).takeIf { it.isNotBlank() }?.let { synchronized(seen) { seen += it } }
            http.fetchTextBlocking(url, NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = VIEW_MAX_BYTES, maxRedirects = 5), SOURCE_KEY).takeIf { it.success }?.text
        }
        val resolved = runCatching { ResolverPipeline.resolve(page.text, page.finalUrl.ifBlank { pageUrl }, ENGINE, VIEW_STRATEGIES, fetcher) }.getOrNull()
            ?: return AutoPlaybackCheck(pageUrl, false, 0, "resolver_crash")
        val media = resolved.sources.filter { it.url.startsWith("https://", true) }
        synchronized(mediaHosts) {
            seen.forEach { mediaHosts += it }
            media.forEach { s -> hostOf(s.url).takeIf { it.isNotBlank() }?.let { mediaHosts += it } }
            resolved.tracks.forEach { t -> hostOf(t.url).takeIf { it.isNotBlank() }?.let { mediaHosts += it } }
        }
        return if (media.isNotEmpty()) AutoPlaybackCheck(pageUrl, true, media.size, media.first().let { "${it.protocol} ${it.label}" })
        else AutoPlaybackCheck(pageUrl, false, 0, resolved.errorCode.ifBlank { "MEDIA_NOT_FOUND" })
    }

    // ---------------------------------------------------------------- site-declared search routes

    private suspend fun openSearchRoutes(html: String, base: String, hosts: Set<String>): List<Pair<String, String>> {
        val link = Regex("<link\\b[^>]*opensearchdescription[^>]*>", RegexOption.IGNORE_CASE).find(html)?.value ?: return emptyList()
        val href = EngineParsing.absolute(EngineParsing.attr(link, "href"), base)
        if (!href.startsWith("https://", true)) return emptyList()
        val xml = withTimeoutOrNull(PROBE_TIMEOUT_MS) { http.fetchText(href, policy(hosts), SOURCE_KEY) }?.takeIf { it.success }?.text ?: return emptyList()
        return Regex("<Url\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { m ->
            val type = EngineParsing.attr(m.value, "type")
            if (type.isNotBlank() && !type.contains("html", true)) return@mapNotNull null
            val t = EngineParsing.attr(m.value, "template").replace("{searchTerms}", "{query}")
                .replace(Regex("\\{startPage\\??\\}"), "1").replace(Regex("[&?][A-Za-z_]+=\\{[A-Za-z:]+\\?\\}"), "")
            if (!t.contains("{query}")) null else sameSiteTemplate(t, base, hosts)?.let { "opensearch" to it }
        }.toList()
    }

    private fun declaredSearchRoutes(html: String, base: String, hosts: Set<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        // schema.org WebSite SearchAction
        Regex("[\"'](?:target|urlTemplate)[\"']\\s*:\\s*[\"']([^\"']*\\{search_term_string\\}[^\"']*)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val t = m.groupValues[1].replace("\\/", "/").replace("{search_term_string}", "{query}")
            sameSiteTemplate(t, base, hosts)?.let { out += "schema.org" to it }
        }
        // <form> search
        Regex("<form\\b([^>]*)>([\\s\\S]*?)</form>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val attrs = "<form ${m.groupValues[1]}>"
            val body = m.groupValues[2]
            val method = EngineParsing.attr(attrs, "method").lowercase()
            val action = EngineParsing.attr(attrs, "action").ifBlank { "/" }
            val inputs = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).map { it.value }.toList()
            val field = inputs.firstOrNull { EngineParsing.attr(it, "type").equals("search", true) }
                ?: inputs.firstOrNull { EngineParsing.attr(it, "name").lowercase() in SEARCH_FIELD_NAMES }
                ?: inputs.firstOrNull { EngineParsing.attr(it, "type").lowercase() in setOf("", "text") && (EngineParsing.attr(it, "placeholder") + EngineParsing.attr(it, "aria-label") + EngineParsing.attr(it, "class") + EngineParsing.attr(it, "id")).contains("search", true) }
                ?: return@forEach
            val name = EngineParsing.attr(field, "name").ifBlank { return@forEach }
            if (method == "post" && !action.contains("do=search", true)) return@forEach
            val hidden = inputs.filter { EngineParsing.attr(it, "type").equals("hidden", true) }
                .mapNotNull { h -> EngineParsing.attr(h, "name").takeIf { it.isNotBlank() && it.length < 40 }?.let { it to EngineParsing.attr(h, "value") } }
                .filter { (n, v) -> v.length < 60 && !n.contains("token", true) && !n.contains("csrf", true) && !n.contains("nonce", true) }
            val params = (hidden.map { (n, v) -> "${enc(n)}=${enc(v)}" } + "${enc(name)}={query}").joinToString("&")
            val actionUrl = EngineParsing.absolute(action, base).substringBefore('#')
            val t = if (actionUrl.contains('?')) "$actionUrl&$params" else "$actionUrl?$params"
            sameSiteTemplate(t, base, hosts)?.let { out += "form:$name" to it }
        }
        // OpenSearch description is referenced from <head>; template is resolved lazily by probes.
        return out.distinctBy { it.second }.take(6)
    }

    private fun sameSiteTemplate(t: String, base: String, hosts: Set<String>): String? {
        val abs = EngineParsing.absolute(t.replace("{query}", "QUERYTOKEN"), base)
        val h = hostOf(abs)
        if (h.isBlank() || hosts.none { pattern -> if (pattern.startsWith("*.")) h == pattern.drop(2) || h.endsWith(pattern.drop(1)) else h == pattern }) return null
        return relative(abs.replace("QUERYTOKEN", "{query}"), base)
    }

    // ---------------------------------------------------------------- adapter JSON

    private fun adapterJson(id: String, name: String, base: String, template: String, pageTemplate: String, pattern: String, query: String, hosts: List<String>, js: Boolean, hints: StreamHints? = null): String {
        val search = linkedMapOf<String, JsonValue>(
            "url_template" to JsonValue.Str(template),
            "link_pattern" to JsonValue.Str(pattern),
        )
        if (pageTemplate.isNotBlank()) search["page_template"] = JsonValue.Str(pageTemplate)
        val deps = listOf("jwplayer_config", "videojs_config", "generic_player_config", "generic_html5", "oembed_provider", "iframe_chain", "hls_manifest", "dash_manifest")
        return JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(3.0),
            "id" to JsonValue.Str(id),
            "name" to JsonValue.Str(name),
            "adapter_version" to JsonValue.Str("1.0.0"),
            "publisher" to JsonValue.Str("SNP Studio Auto"),
            "homepage" to JsonValue.Str(base),
            "min_app_version" to JsonValue.Str(BuildIdentity.SNP_COMPATIBILITY_VERSION),
            "base_url" to JsonValue.Str(base),
            "engine" to JsonValue.Str(ENGINE),
            "engine_dependencies" to JsonValue.Arr(deps.map { JsonValue.Str(it) }),
            "enabled" to JsonValue.Bool(false),
            "capabilities" to JsonValue.Obj(linkedMapOf("search" to JsonValue.Bool(true), "pagination" to JsonValue.Bool(pageTemplate.isNotBlank()), "direct_url" to JsonValue.Bool(true), "embedded_playback" to JsonValue.Bool(true))),
            "permissions" to JsonValue.Obj(linkedMapOf("allowed_hosts" to JsonValue.Arr(hosts.map { JsonValue.Str(it) }), "allow_javascript" to JsonValue.Bool(js), "allow_cookies" to JsonValue.Bool(true))),
            "search" to JsonValue.Obj(search),
            "view" to JsonValue.Obj(linkedMapOf<String, JsonValue>("strategies" to JsonValue.Arr(VIEW_STRATEGIES.map { JsonValue.Str(it) })).apply {
                if (hints != null && !hints.isEmpty) {
                    val st = linkedMapOf<String, JsonValue>()
                    if (hints.prefer.isNotBlank()) st["prefer"] = JsonValue.Str(hints.prefer)
                    if (hints.reject.isNotBlank()) st["reject"] = JsonValue.Str(hints.reject)
                    if (hints.minSeconds > 0) st["min_seconds"] = JsonValue.Num(hints.minSeconds.toDouble())
                    if (hints.extract.isNotEmpty()) st["extract"] = JsonValue.Arr(hints.extract.map { JsonValue.Str(it) })
                    put("stream", JsonValue.Obj(st))
                }
            }),
            "health" to JsonValue.Obj(linkedMapOf("query" to JsonValue.Str(query), "min_results" to JsonValue.Num(1.0), "require_playback" to JsonValue.Bool(true), "auto_repair" to JsonValue.Bool(false))),
            "repair" to JsonValue.Obj(linkedMapOf("enabled" to JsonValue.Bool(false), "mode" to JsonValue.Str("conservative"), "failure_threshold" to JsonValue.Num(3.0), "cooldown_minutes" to JsonValue.Num(60.0), "auto_quarantine" to JsonValue.Bool(false), "strategies" to JsonValue.Arr(listOf(JsonValue.Str("resolver_refresh"))))),
        )).toJson(pretty = true)
    }

    // ---------------------------------------------------------------- helpers

    private fun policy(hosts: Set<String>) = NetworkPolicy(hosts.toList(), allowCookies = true, maxResponseBytes = SEARCH_MAX_BYTES, maxRedirects = 5)

    private fun render(template: String, base: String, query: String, page: Int): String =
        pro.noodle.mobile.core.engine.runtime.SearchUrls.render(template, EngineSearchRequest(baseUrl = base, query = query, page = page - 1))

    private fun <T> spread(items: List<T>, count: Int): List<T> {
        if (items.size <= count) return items
        val last = items.lastIndex
        return (0 until count).map { items[(last * it) / (count - 1)] }.distinct()
    }

    companion object {
        const val ENGINE = "auto_catalog"
        private const val SOURCE_KEY = "@snp-auto-builder"
        private const val PARALLELISM = 6
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val VIEW_TIMEOUT_MS = 12_000L
        private const val HOME_MAX_BYTES = 4 * 1024 * 1024
        private const val SEARCH_MAX_BYTES = 4 * 1024 * 1024
        private const val VIEW_MAX_BYTES = 6 * 1024 * 1024
        private const val MIN_CARDS = 3
        private const val PLAYBACK_SAMPLES = 3
        private const val MAX_ATTEMPTS = 5
        private const val LEARN_TIMEOUT_MS = 90_000L
        val VIEW_STRATEGIES = listOf("primary", "jwplayer", "videojs", "player_config", "generic_html5", "oembed", "iframe", "hls", "dash")
        private val SEARCH_FIELD_NAMES = setOf("q", "s", "k", "query", "search", "search_query", "searchtext", "search_text", "keyword", "keywords", "term", "text", "story", "searchword", "find", "w")

        /** Common CMS / tube conventions, ordered by how often they appear in the wild. */
        val KNOWN_ROUTES = listOf(
            "search_q" to "/search/?q={query}",
            "search_q_noslash" to "/search?q={query}",
            "search_path" to "/search/{query}/",
            "search_path_noslash" to "/search/{query}",
            "wp_s" to "/?s={query}",
            "q_root" to "/?q={query}",
            "k_root" to "/?k={query}",
            "search_k" to "/search?k={query}",
            "search_search" to "/search?search={query}",
            "search_query" to "/search?search_query={query}",
            "search_videos" to "/search/videos/{query}/",
            "video_search" to "/video/search?q={query}",
            "videos_search" to "/videos/search?q={query}",
            "videos_path" to "/videos/{query}/",
            "video_path" to "/video/{query}",
            "tags_path" to "/tags/{query}/",
            "tag_path" to "/tag/{query}/",
            "s_path" to "/s/{query}/",
            "dle" to "/index.php?do=search&subaction=search&story={query}",
            "plus_search" to "/search/{query_plus}/",
        )

        fun normalizeInput(raw: String): String? {
            var v = raw.trim().trim('/', ' ')
            if (v.isBlank()) return null
            if (!v.contains("://")) v = "https://$v"
            if (v.startsWith("http://", true)) v = "https://" + v.substring(7)
            return runCatching {
                val u = URI(v)
                if (u.host.isNullOrBlank() || !u.host.contains('.')) null else "https://${u.host.lowercase()}${if (u.port > 0 && u.port != 443) ":${u.port}" else ""}/"
            }.getOrNull()
        }

        fun originOf(url: String): String = runCatching { URI(url).let { "https://${it.host.lowercase()}${if (it.port > 0 && it.port != 443) ":${it.port}" else ""}" } }.getOrDefault(url.trimEnd('/'))
        fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().lowercase().trimEnd('.') }.getOrDefault("")

        /** Rough eTLD+1: keeps two labels, three for well-known second-level suffixes. */
        fun registrable(host: String): String {
            val parts = host.removePrefix("www.").split('.')
            if (parts.size <= 2) return parts.joinToString(".")
            val sld = parts[parts.size - 2]
            val keep = if (sld in setOf("co", "com", "net", "org", "gov", "ac", "edu") && parts.last().length == 2) 3 else 2
            return parts.takeLast(keep).joinToString(".")
        }

        fun siteName(html: String, host: String): String {
            val og = EngineParsing.first(html, "<meta[^>]+property=[\"']og:site_name[\"'][^>]+content=[\"']([^\"']{2,60})[\"']")
            if (og.isNotBlank()) return EngineParsing.htmlDecode(og)
            val title = EngineParsing.stripTags(EngineParsing.first(html, "<title[^>]*>([\\s\\S]*?)</title>"))
            val short = title.split(" - ", " | ", " — ", " – ", ": ").map { it.trim() }.filter { it.length in 2..40 }
                .minByOrNull { it.length }
            val fromHost = registrable(host).substringBeforeLast('.').replaceFirstChar { it.uppercase() }
            return if (short != null && short.replace(" ", "").contains(fromHost.take(4), true)) short else fromHost
        }

        fun queryTokens(q: String): List<String> = q.lowercase().split(Regex("[\\s+_-]+")).filter { it.length >= 3 }

        fun relative(abs: String, base: String): String {
            val origin = base.trimEnd('/')
            return if (abs.startsWith(origin, true)) abs.substring(origin.length).ifBlank { "/" } else abs
        }

        private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    }
}

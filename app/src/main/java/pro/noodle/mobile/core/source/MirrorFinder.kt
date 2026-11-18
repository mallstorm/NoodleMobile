package pro.noodle.mobile.core.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.source.MirrorStore.Companion.hostOf
import pro.noodle.mobile.core.source.MirrorStore.Companion.originOf
import pro.noodle.mobile.core.source.MirrorStore.Companion.registrable
import pro.noodle.mobile.core.source.MirrorStore.Companion.sameSite
import java.net.InetAddress

data class MirrorCandidate(val origin: String, val how: String, val strong: Boolean)
data class MirrorVerdict(val ok: Boolean, val note: String)
data class MirrorOutcome(val adopted: String = "", val suggested: List<String> = emptyList(), val message: String = "")

/**
 * Поиск нового адреса сайта, когда адаптер перестал работать из-за переезда на другой домен.
 *
 *  1. Сильные признаки (переключаем автоматически после проверки): старый домен сам перенаправляет на новый,
 *     страница объявляет другой адрес (canonical / og:url / hreflang / meta refresh / JS-редирект / «новый адрес»),
 *     домен из списка уже известных зеркал.
 *  2. Слабые признаки (только предложение пользователю): тот же корень домена в других зонах (.com .net .cc …).
 *
 * Любой кандидат сначала проходит настоящую проверку на тех же правилах адаптера: поиск находит карточки
 * и хотя бы один ролик открывается. Пакет адаптера при этом не меняется — домен хранится в [MirrorStore].
 */
class MirrorFinder(context: Context) {
    private val app = context.applicationContext
    private val http = SecureHttpClient()
    private val store = MirrorStore(app)
    private val repo = RuntimeAdapterRepository(app)
    private val probe = AdapterProbeService(app, repo)
    private val playback = PlaybackRepository(app, http)

    private fun anyPublic(max: Int = 3 * 1024 * 1024) = NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = max, maxRedirects = 6)

    private fun safeOrigin(raw: String): String? {
        val o = originOf(raw.trim().let { if (it.startsWith("http", true)) it else "https://$it" })
        if (o.isBlank()) return null
        val d = UrlPolicy.evaluateNavigation(o, allowHttp = false, resolveDns = false)
        return if (d.allowed) o.replaceFirst("http://", "https://") else null
    }

    /** Кандидаты в порядке убывания уверенности. */
    suspend fun discover(d: AdapterDefinition, onProgress: (String) -> Unit = {}): List<MirrorCandidate> {
        val current = originOf(d.baseUrl)
        val out = linkedMapOf<String, MirrorCandidate>()
        fun add(raw: String, how: String, strong: Boolean) {
            val o = safeOrigin(raw) ?: return
            if (sameSite(o, current) && hostOf(o).removePrefix("www.") == hostOf(current).removePrefix("www.")) return
            out.putIfAbsent(hostOf(o), MirrorCandidate(o, how, strong))
        }
        onProgress("Открываю прежний адрес…")
        val net = runCatching { http.fetchText(d.baseUrl, anyPublic(), "mirror:${d.id}") }.getOrNull()
        if (net != null) {
            if (net.finalUrl.isNotBlank() && !sameSite(net.finalUrl, current)) add(net.finalUrl, "прежний адрес перенаправляет сюда", true)
            val html = net.text
            if (html.isNotBlank()) harvest(html, net.finalUrl.ifBlank { d.baseUrl }).forEach { (url, how) ->
                // Объявленный самой страницей другой домен — тоже сильный признак, пока проверка не опровергла.
                if (!sameSite(url, current)) add(url, how, true)
            }
        }
        (store.known(d.id) + store.history(d.id)).forEach { add(it, "известное зеркало", true) }
        store.suggested(d.id).forEach { add(it, "найдено раньше", false) }
        return out.values.sortedByDescending { it.strong }
    }

    /** Адреса, которые страница сама называет своими или новыми. */
    private fun harvest(html: String, pageUrl: String): List<Pair<String, String>> {
        val found = linkedMapOf<String, String>()
        fun put(raw: String, how: String) {
            val abs = runCatching { java.net.URI(pageUrl).resolve(raw.trim()).toString() }.getOrDefault(raw)
            if (abs.startsWith("http", true)) found.putIfAbsent(abs, how)
        }
        Regex("<link[^>]+rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).take(3).forEach { put(it.groupValues[1], "canonical страницы") }
        Regex("<link[^>]+href=[\"']([^\"']+)[\"'][^>]*rel=[\"']canonical[\"']", RegexOption.IGNORE_CASE).findAll(html).take(3).forEach { put(it.groupValues[1], "canonical страницы") }
        Regex("<meta[^>]+property=[\"']og:url[\"'][^>]*content=[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).take(2).forEach { put(it.groupValues[1], "og:url страницы") }
        Regex("<link[^>]+rel=[\"']alternate[\"'][^>]*hreflang[^>]*href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).take(6).forEach { put(it.groupValues[1], "альтернативный адрес сайта") }
        Regex("<meta[^>]+http-equiv=[\"']refresh[\"'][^>]*content=[\"'][^\"']*url=([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).take(2).forEach { put(it.groupValues[1], "meta-перенаправление") }
        Regex("(?:window\\.|document\\.|top\\.)?location(?:\\.href)?\\s*=\\s*[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).take(3).forEach { put(it.groupValues[1], "JS-перенаправление") }
        Regex("(?:новый адрес|новое зеркало|мы переехали|наш новый домен|new domain|new address|we have moved|we moved|official mirror)[^<]{0,80}?(?:<[^>]*>\\s*){0,3}(?:https?://)?([a-z0-9][a-z0-9.-]+\\.[a-z]{2,})", RegexOption.IGNORE_CASE)
            .findAll(html).take(3).forEach { put("https://" + it.groupValues[1], "объявление «новый адрес» на странице") }
        return found.entries.map { it.key to it.value }
    }

    /** Настоящая проверка кандидата на правилах этого же адаптера. */
    suspend fun verify(d: AdapterDefinition, origin: String): MirrorVerdict {
        val cand = store.preview(d, origin)
        val (items, err) = runCatching { probe.searchOne(cand, d.healthQuery.ifBlank { "video" }, 10) }
            .getOrElse { return MirrorVerdict(false, "поиск: ${it.message}") }
        if (err != null) return MirrorVerdict(false, "поиск: ${err.second.take(120)}")
        if (items.size < 2) return MirrorVerdict(false, "поиск нашёл ${items.size} карточек — похоже, это другой сайт или пустая страница")
        val played = items.take(3).any { runCatching { playback.resolve(it, cand).success }.getOrDefault(false) }
        return if (played) MirrorVerdict(true, "карточек: ${items.size}, видео открывается") else MirrorVerdict(false, "карточки есть (${items.size}), но видео не открывается")
    }

    /** Автоматический режим: сильные кандидаты переключаем сразу после проверки, слабые — только предлагаем. */
    suspend fun findAndAdopt(d: AdapterDefinition, onProgress: (String) -> Unit = {}): MirrorOutcome {
        val current = originOf(d.baseUrl)
        val cands = discover(d, onProgress)
        for (c in cands.filter { it.strong }.take(4)) {
            onProgress("Проверяю ${hostOf(c.origin)} (${c.how})…")
            val v = verify(d, c.origin)
            if (v.ok) {
                store.switchTo(d.id, current, c.origin, "переезд на ${hostOf(c.origin)}: ${c.how}")
                return MirrorOutcome(adopted = c.origin, message = "Сайт переехал на ${hostOf(c.origin)} (${c.how}). ${v.note}")
            }
        }
        onProgress("Подбираю другие доменные зоны…")
        val suggestions = mutableListOf<String>()
        for (o in sweep(d)) {
            if (suggestions.size >= 3) break
            onProgress("Проверяю ${hostOf(o)}…")
            if (verify(d, o).ok) { suggestions += o; store.addSuggested(d.id, o) }
        }
        return if (suggestions.isNotEmpty()) MirrorOutcome(suggested = suggestions, message = "Найден похожий сайт на другом домене: ${suggestions.joinToString { hostOf(it) }}. Подтвердите переключение вручную.")
        else MirrorOutcome(message = "Нового адреса найти не удалось.")
    }

    /** Ручное зеркало: пользователь вводит адрес, мы проверяем и переключаем. */
    suspend fun addManual(d: AdapterDefinition, input: String): MirrorOutcome {
        val o = safeOrigin(input) ?: return MirrorOutcome(message = "Адрес не подходит (нужен публичный https-домен).")
        val v = verify(d, o)
        if (!v.ok) return MirrorOutcome(message = "Это зеркало не прошло проверку: ${v.note}")
        store.switchTo(d.id, originOf(d.baseUrl), o, "выбрано вручную: ${hostOf(o)}")
        return MirrorOutcome(adopted = o, message = "Переключено на ${hostOf(o)}. ${v.note}")
    }

    suspend fun switchToKnown(d: AdapterDefinition, origin: String): MirrorOutcome {
        val v = verify(d, origin)
        if (!v.ok) return MirrorOutcome(message = "${hostOf(origin)} сейчас не работает: ${v.note}")
        store.switchTo(d.id, originOf(d.baseUrl), origin, "переключено на зеркало ${hostOf(origin)}")
        return MirrorOutcome(adopted = origin, message = "Переключено на ${hostOf(origin)}. ${v.note}")
    }

    /** Тот же корень домена в других зонах; отсеиваем несуществующие по DNS и по ответу главной. */
    private suspend fun sweep(d: AdapterDefinition): List<String> = coroutineScope {
        val host = hostOf(d.baseUrl)
        val reg = registrable(host)
        val label = reg.substringBefore('.')
        val currentTld = reg.substringAfter('.')
        val gate = Semaphore(8)
        TLDS.filter { it != currentTld }.map { tld ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val h = "$label.$tld"
                    val resolves = withTimeoutOrNull(3_000) { runCatching { InetAddress.getAllByName(h).none(UrlPolicy::isUnsafeAddress) }.getOrDefault(false) } ?: false
                    if (!resolves) return@withPermit null
                    val r = withTimeoutOrNull(8_000) { runCatching { http.fetchText("https://$h/", anyPublic(1_000_000), "mirror:${d.id}") }.getOrNull() }
                    if (r != null && r.success && r.text.length > 500) "https://$h" else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        private val TLDS = listOf(
            "com", "net", "org", "cc", "co", "tv", "me", "to", "io", "is", "ws", "pro", "club", "site", "online", "best", "top", "one",
            "life", "live", "fun", "xyz", "info", "biz", "sex", "porn", "xxx", "ru", "su", "ua", "tube", "cam", "vip", "icu", "link", "lol",
        )
    }
}

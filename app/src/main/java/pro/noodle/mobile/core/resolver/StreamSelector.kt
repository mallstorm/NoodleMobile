package pro.noodle.mobile.core.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.model.MediaSource
import java.util.Base64

/** Rules learned for one site by SNP Studio (adapter.json → view.stream). */
data class StreamHints(
    val prefer: String = "",
    val reject: String = "",
    val minSeconds: Int = 0,
    val extract: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = prefer.isBlank() && reject.isBlank() && minSeconds <= 0 && extract.isEmpty()
    private val preferRe: Regex? by lazy { prefer.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } }
    private val rejectRe: Regex? by lazy { reject.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } }
    fun prefers(url: String) = preferRe?.containsMatchIn(url) == true
    fun rejects(url: String) = rejectRe?.containsMatchIn(url) == true
}

enum class StreamVerdict { FULL, UNKNOWN, PREVIEW, DEAD }

data class RankedSource(val source: MediaSource, val facts: StreamFacts?, val verdict: StreamVerdict, val score: Int)

object StreamScorer {
    /** Anything shorter than this, on a card that does not itself say "short", is treated as a preview. */
    const val PREVIEW_ABS_SEC = 20.0
    private val previewWords = Regex("(?:^|[/_.\\-=])(?:preview|trailer|teaser|sample|thumb|thumbs|mini|tiny|short|clip|snippet|hover)(?:[/_.\\-=?&]|\\d|$)", RegexOption.IGNORE_CASE)

    /**
     * Orders candidates so the real, full-length stream wins. Evidence, strongest first:
     * duration measured by the probe vs the duration advertised on the card/page, then size,
     * URL shape (preview/trailer words) and the per-site rules learned by Studio.
     */
    fun rank(sources: List<MediaSource>, facts: Map<String, StreamFacts>, expectedSec: Int, hints: StreamHints): List<RankedSource> {
        val probed = sources.mapNotNull { facts[it.url] }.filter { it.ok }
        val maxDur = probed.maxOfOrNull { it.durationSec } ?: -1.0
        val refDur = when {
            expectedSec > 0 -> expectedSec.toDouble()
            maxDur >= 45.0 -> maxDur
            else -> 0.0
        }
        val maxBytes = probed.filter { it.kind == "mp4" || it.kind == "webm" || it.kind == "unknown" }.maxOfOrNull { it.bytes } ?: -1L
        return sources.mapIndexed { index, s ->
            val f = facts[s.url]
            var score = 0
            var verdict = StreamVerdict.UNKNOWN
            val urlPreview = EngineParsing.isPreviewClip(s.url) || previewWords.containsMatchIn(s.url.substringBefore('?'))
            if (f != null && !f.ok && (f.status >= 400 || f.kind == "html")) {
                verdict = StreamVerdict.DEAD; score -= 2000
            } else if (f != null && !f.ok) {
                score -= 300 // network error/timeout: unknown, but try it after healthy candidates
            } else {
                if (f != null && f.durationSec >= 0) {
                    val cardSaysShort = expectedSec in 1..29
                    when {
                        // A 3–20 s file on a site of full-length videos is a hover/teaser clip, whatever the card says.
                        !cardSaysShort && f.durationSec > 0 && f.durationSec < PREVIEW_ABS_SEC -> { verdict = StreamVerdict.PREVIEW; score -= 1200 }
                        refDur > 0 && f.durationSec < refDur * 0.6 -> { verdict = StreamVerdict.PREVIEW; score -= 1000 }
                        hints.minSeconds > 0 && f.durationSec < hints.minSeconds && (refDur <= 0 || refDur >= hints.minSeconds) -> { verdict = StreamVerdict.PREVIEW; score -= 1000 }
                        refDur > 0 && f.durationSec >= refDur * 0.85 -> { verdict = StreamVerdict.FULL; score += 1000 }
                        refDur <= 0 && f.durationSec >= 45.0 -> { verdict = StreamVerdict.FULL; score += 400 }
                    }
                    if (f.live) score -= 200
                } else if (f != null && f.bytes >= 0 && (f.kind == "mp4" || f.kind == "webm")) {
                    if (f.bytes < 1_500_000 && (maxBytes > f.bytes * 3 || refDur >= 30.0 || expectedSec !in 1..29)) { verdict = StreamVerdict.PREVIEW; score -= 900 }
                    else if (maxBytes > 0 && f.bytes < maxBytes * 0.25 && f.bytes < 8_000_000) { verdict = StreamVerdict.PREVIEW; score -= 700 }
                    else if (maxBytes > 0 && f.bytes >= maxBytes * 0.8 && f.bytes > 4_000_000) score += 250
                }
                if (urlPreview && verdict != StreamVerdict.FULL) { verdict = StreamVerdict.PREVIEW; score -= 600 }
                else if (urlPreview) score -= 200
            }
            if (hints.rejects(s.url)) { verdict = StreamVerdict.PREVIEW; score -= 1500 }
            if (hints.prefers(s.url)) score += 300
            val h = (facts[s.url]?.height?.takeIf { it > 0 } ?: s.height).coerceAtMost(1080)
            score += h / 4
            score += when { s.protocol.equals("hls", true) -> 10; s.protocol.equals("dash", true) -> 0; else -> 25 }
            RankedSource(s, f, verdict, score * 100 - index) // original order breaks ties
        }.sortedByDescending { it.score }
    }
}

/** Finds media URLs the standard engines miss: JSON blobs, escaped strings, atob(), %-encoded values. */
object DeepMediaMiner {
    private val mediaUrl = Regex("(?:https?:)?//[^\"'<>\\s\\\\,()]+?\\.(?:mp4|m3u8|mpd|webm|m4v)(?:\\?[^\"'<>\\s\\\\,()]*)?", RegexOption.IGNORE_CASE)
    private val keyed = Regex("[\"'](?:video_?url|video_?alt_?url\\d*|file|src|source|stream|stream_?url|hls|hls_?url|mp4|mp4_?url|download|download_?url|play|link|url|contentUrl)[\"']?\\s*[:=]\\s*[\"']([^\"']{6,600})[\"']", RegexOption.IGNORE_CASE)
    private val b64 = Regex("(?:atob\\(\\s*|[\"'])([A-Za-z0-9+/]{24,}={0,2})[\"']")

    fun corpus(raw: String): String {
        val sb = StringBuilder(raw.length + 1024)
        val base = raw.replace("\\/", "/").replace("\\u0026", "&", true).replace("\\u002F", "/", true).replace("&amp;", "&")
        sb.append(base)
        if (base.contains("%2F", true) && base.contains("%3A", true)) {
            Regex("https?%3A%2F%2F[^\"'<>\\s\\\\]+", RegexOption.IGNORE_CASE).findAll(base).take(40).forEach { sb.append('\n').append(EngineParsing.urlDecode(it.value)) }
        }
        var decoded = 0
        for (m in b64.findAll(base)) {
            if (decoded >= 25) break
            val text = runCatching { String(Base64.getDecoder().decode(m.groupValues[1]), Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.contains("http", true) || text.contains(".mp4", true) || text.contains(".m3u8", true)) { sb.append('\n').append(text); decoded++ }
        }
        return sb.toString()
    }

    fun mine(raw: String, pageUrl: String, extract: List<String> = emptyList(), limit: Int = 24): List<MediaSource> {
        if (raw.isBlank()) return emptyList()
        val text = corpus(raw)
        val out = linkedMapOf<String, MediaSource>()
        fun add(candidate: String) {
            val url = EngineParsing.absolute(candidate.trim().trim('"', '\'', ' '), pageUrl)
            if (!url.startsWith("http", true) || out.size >= limit) return
            val path = url.substringBefore('?').lowercase()
            val looksMedia = path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".webm") || path.endsWith(".m4v") ||
                path.contains("/get_file/") || path.contains("/videoplayback") || path.contains("/stream/") || path.contains("/hls/")
            if (!looksMedia) return
            if (EngineParsing.isPreviewClip(url)) return
            if (path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".webp")) return
            out.putIfAbsent(url.lowercase(), MediaSource(
                label = EngineParsing.inferLabel(url),
                url = url,
                mimeType = when (EngineParsing.protocol(url)) { "hls" -> "application/vnd.apple.mpegurl"; "dash" -> "application/dash+xml"; else -> "video/mp4" },
                protocol = EngineParsing.protocol(url),
            ))
        }
        for (pattern in extract) {
            val re = runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }.getOrNull() ?: continue
            re.findAll(text).take(8).forEach { m -> add(if (m.groupValues.size > 1 && m.groupValues[1].isNotBlank()) m.groupValues[1] else m.value) }
        }
        mediaUrl.findAll(text).forEach { add(it.value) }
        keyed.findAll(text).forEach { add(it.groupValues[1]) }
        return out.values.toList()
    }
}

/** "video_preview.mp4" → "video.mp4", "/previews/123.mp4" → "/123.mp4": the full file often sits next to the teaser. */
object PreviewUrlVariants {
    private val rules = listOf(
        Regex("[_\\-.](?:preview|trailer|teaser|sample|short|mini|hover|thumb)(?=[_\\-.]?\\d*p?\\.(?:mp4|webm|m4v|m3u8)(?:/|$)|/?$)", RegexOption.IGNORE_CASE) to "",
        Regex("/(?:previews?|trailers?|teasers?|samples?|hover|thumbs?|clips?)/", RegexOption.IGNORE_CASE) to "/",
        Regex("(?<=/)(?:preview|trailer|teaser)[_\\-]", RegexOption.IGNORE_CASE) to "",
    )

    fun of(url: String): List<String> {
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        val out = linkedSetOf<String>()
        var acc = path
        for ((re, rep) in rules) {
            val one = re.replace(path, rep)
            if (one != path) out += one
            acc = re.replace(acc, rep)
        }
        if (acc != path) out += acc
        return out.map { if (query.isNotBlank()) "$it?$query" else it }.filter { it != url }.take(4)
    }
}

/** Player pages hidden behind iframes / JSON-LD embedUrl / og:video — where the real stream usually lives. */
object EmbedFinder {
    private val iframe = Regex("<iframe\\b[^>]*?\\s(?:data-src|src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val embedUrl = Regex("[\"'](?:embedUrl|embed_url|playerUrl|player_url|iframeUrl)[\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val ogVideo = Regex("<meta[^>]+(?:property|name)=[\"'](?:og:video(?::url|:secure_url)?|twitter:player(?::stream)?)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val playerHref = Regex("(?:href|data-href|data-url)\\s*=\\s*[\"']([^\"']*/(?:embed|player|playerjs|iframe|e)/[^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val adHosts = Regex("(?:^|\\.)(?:doubleclick|googlesyndication|exoclick|exosrv|juicyads|trafficjunky|adsterra|tsyndicate|magsrv|realsrv|adspyglass|clickadu|popads|hilltopads|twinred|traffichaus|syndication|yandex|google|facebook|twitter|vk)\\.", RegexOption.IGNORE_CASE)

    fun find(pages: List<Pair<String, String>>, limit: Int = 4): List<String> {
        val out = linkedSetOf<String>()
        for ((pageUrl, body0) in pages) {
            val body = body0.replace("\\/", "/").replace("&amp;", "&")
            listOf(iframe, embedUrl, ogVideo, playerHref).forEach { re ->
                re.findAll(body).forEach { m ->
                    val abs = EngineParsing.absolute(m.groupValues[1].trim(), pageUrl)
                    val host = runCatching { java.net.URI(abs).host.orEmpty() }.getOrDefault("")
                    if (abs.startsWith("https://", true) && host.isNotBlank() && !adHosts.containsMatchIn(host) &&
                        !abs.equals(pageUrl, true) && !abs.substringBefore('?').lowercase().let { it.endsWith(".js") || it.endsWith(".css") || it.endsWith(".jpg") || it.endsWith(".png") }) out += abs
                }
            }
        }
        return out.take(limit)
    }
}

data class StreamSelection(
    val sources: List<MediaSource>,
    val ranked: List<RankedSource>,
    val expectedSec: Int,
    val best: StreamVerdict,
    val minedExtra: Int = 0,
    /** What the deep search did when the obvious candidates were only previews. */
    val notes: List<String> = emptyList(),
) {
    val summary: String get() = ranked.firstOrNull()?.let { r ->
        "${r.verdict.name.lowercase()} • ${r.facts?.brief().orEmpty().ifBlank { "не проверен" }}" + if (expectedSec > 0) " • ожидалось ${DurationText.format(expectedSec.toDouble())}" else "" +
            if (notes.isNotEmpty()) " • глубже: " + notes.joinToString("; ") else ""
    }.orEmpty()
}

/**
 * Probes candidates (bounded, parallel), ranks them, mines deeper when only previews were found,
 * and drops confirmed previews once a real stream exists.
 */
class StreamSelector(private val client: OkHttpClient, private val referer: String) {
    private val probe = StreamProbe(client, referer)

    suspend fun select(
        sources: List<MediaSource>,
        expectedSec: Int,
        hints: StreamHints,
        pages: List<Pair<String, String>> = emptyList(),
    ): StreamSelection = withContext(Dispatchers.IO) {
        val facts = linkedMapOf<String, StreamFacts>()
        var all = sources.distinctBy { it.url.lowercase() }
        // hints.extract applies to the page itself first: that is the site-specific rule.
        var mined = 0
        if (hints.extract.isNotEmpty()) {
            val extra = pages.flatMap { (url, body) -> DeepMediaMiner.mine(body, url, hints.extract, 8) }
                .filter { c -> all.none { it.url.equals(c.url, true) } }
            if (extra.isNotEmpty()) { all = (extra.take(6) + all).distinctBy { it.url.lowercase() }; mined += extra.size.coerceAtMost(6) }
        }
        probeAll(all, facts)
        var ranked = StreamScorer.rank(all, facts, expectedSec, hints)
        if (ranked.none { it.verdict == StreamVerdict.FULL }) {
            val extra = pages.flatMap { (url, body) -> DeepMediaMiner.mine(body, url) }
                .filter { c -> all.none { it.url.equals(c.url, true) } }
                .take(10)
            if (extra.isNotEmpty()) {
                mined += extra.size
                all = (all + extra).distinctBy { it.url.lowercase() }
                probeAll(extra, facts)
                ranked = StreamScorer.rank(all, facts, expectedSec, hints)
            }
        }
        val notes = mutableListOf<String>()
        if (ranked.none { it.verdict == StreamVerdict.FULL }) {
            // Dig 1: the full file often sits next to the teaser under a predictable name.
            val variants = ranked.filter { it.verdict == StreamVerdict.PREVIEW || EngineParsing.isPreviewClip(it.source.url) }
                .flatMap { r -> PreviewUrlVariants.of(r.source.url).map { r.source.copy(url = it, label = r.source.label.ifBlank { EngineParsing.inferLabel(it) }, isDefault = false) } }
                .filter { c -> all.none { it.url.equals(c.url, true) } }
                .distinctBy { it.url.lowercase() }.take(6)
            if (variants.isNotEmpty()) {
                all = (all + variants).distinctBy { it.url.lowercase() }
                probeAll(variants, facts)
                ranked = StreamScorer.rank(all, facts, expectedSec, hints)
                val ok = variants.count { v -> ranked.any { it.source.url == v.url && it.verdict == StreamVerdict.FULL } }
                notes += "варианты без preview: ${variants.size}, полных $ok"
            }
        }
        if (ranked.none { it.verdict == StreamVerdict.FULL }) {
            // Dig 2: the real player is behind an iframe / embedUrl / og:video page.
            val embeds = EmbedFinder.find(pages)
            for (embed in embeds) {
                val path = embed.substringBefore('?').lowercase()
                val direct = path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".webm")
                val found = if (direct) listOf(MediaSource(label = EngineParsing.inferLabel(embed), url = embed, mimeType = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4", protocol = EngineParsing.protocol(embed)))
                else {
                    val body = fetchPage(embed) ?: continue
                    val engine = runCatching { pro.noodle.mobile.core.engine.impl.AutoCatalogEngine().resolvePlayback(body, embed).sources }.getOrDefault(emptyList())
                    (engine + DeepMediaMiner.mine(body, embed, hints.extract)).filter { !EngineParsing.isPreviewClip(it.url) }
                }
                val fresh = found.filter { c -> all.none { it.url.equals(c.url, true) } }.distinctBy { it.url.lowercase() }.take(6)
                notes += "embed ${hostOfUrl(embed)}: кандидатов ${fresh.size}"
                if (fresh.isEmpty()) continue
                all = (all + fresh).distinctBy { it.url.lowercase() }
                probeAll(fresh, facts)
                ranked = StreamScorer.rank(all, facts, expectedSec, hints)
                if (ranked.any { it.verdict == StreamVerdict.FULL }) break
            }
        }
        val hasGood = ranked.any { it.verdict == StreamVerdict.FULL || it.verdict == StreamVerdict.UNKNOWN }
        val kept = if (hasGood) ranked.filter { it.verdict == StreamVerdict.FULL || it.verdict == StreamVerdict.UNKNOWN || it.verdict == StreamVerdict.DEAD } else ranked
        val ordered = kept.mapIndexed { i, r -> r.source.copy(isDefault = i == 0) }
        StreamSelection(ordered, kept, expectedSec, kept.firstOrNull()?.verdict ?: StreamVerdict.UNKNOWN, mined, notes)
    }

    private fun hostOfUrl(u: String) = runCatching { java.net.URI(u).host.orEmpty() }.getOrDefault(u.take(40))

    /** Fetches an embed/player page (public HTTPS only — the client's PolicyInterceptor enforces it). */
    private fun fetchPage(url: String): String? = runCatching {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", pro.noodle.mobile.core.version.BuildIdentity.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .apply { if (referer.isNotBlank()) header("Referer", referer) }
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val body = r.body ?: return@use null
            val ct = body.contentType()?.toString().orEmpty().lowercase()
            if (ct.startsWith("video/") || ct.contains("octet-stream") || ct.startsWith("image/")) return@use null
            val src = body.source()
            src.request(PAGE_MAX_BYTES)
            src.buffer.readUtf8(minOf(src.buffer.size, PAGE_MAX_BYTES))
        }
    }.getOrNull()

    private suspend fun probeAll(list: List<MediaSource>, into: MutableMap<String, StreamFacts>) = coroutineScope {
        val gate = Semaphore(4)
        val todo = list.filter { it.url !in into }.take(MAX_PROBES)
        val results = todo.map { s ->
            async(Dispatchers.IO) { gate.withPermit { s.url to (withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.probe(s) } ?: StreamFacts(s.url, note = "timeout")) } }
        }.awaitAll()
        results.forEach { (u, f) -> into[u] = f }
    }

    companion object {
        const val MAX_PROBES = 8
        const val PROBE_TIMEOUT_MS = 9_000L
        const val PAGE_MAX_BYTES = 3_000_000L

        fun create(client: OkHttpClient, referer: String) = StreamSelector(client, referer)
    }
}

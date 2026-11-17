package pro.noodle.mobile.core.studio.auto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.EngineSearchItem
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.resolver.DeepMediaMiner
import pro.noodle.mobile.core.resolver.DurationText
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.ResolverPipeline
import pro.noodle.mobile.core.resolver.StreamFacts
import pro.noodle.mobile.core.resolver.StreamHints
import pro.noodle.mobile.core.resolver.StreamProbe
import pro.noodle.mobile.core.resolver.StreamScorer
import pro.noodle.mobile.core.resolver.StreamVerdict
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** One question to the model: returns the raw reply text, or null when AI is unavailable. */
fun interface StreamAi {
    suspend fun ask(system: String, user: String): String?
}

data class CandidateEvidence(val url: String, val origin: String, val facts: StreamFacts, val truth: Boolean, val decoy: Boolean)

data class VideoEvidence(
    val pageUrl: String,
    val title: String,
    val expectedSec: Int,
    val referer: String,
    val candidates: List<CandidateEvidence>,
    val pageBody: String,
) {
    val hasTruth: Boolean get() = candidates.any { it.truth }
}

data class StreamLearning(
    val hints: StreamHints,
    val evidence: List<VideoEvidence>,
    /** Percent of the sampled videos whose real full-length stream is chosen with these rules. */
    val confidence: Int,
    val summary: String,
    /** "mp4" (direct file), "hls" (assembled to MP4), "dash", or "" when nothing was found. */
    val downloadKind: String,
    val aiUsed: Boolean = false,
    val aiNote: String = "",
    val log: List<String> = emptyList(),
) {
    fun report(): String = buildString {
        appendLine("STREAM LEARNING: confidence=$confidence% download=$downloadKind ai=$aiUsed")
        appendLine("summary=$summary")
        if (aiNote.isNotBlank()) appendLine("ai_note=$aiNote")
        appendLine("hints: prefer='${hints.prefer}' reject='${hints.reject}' min_seconds=${hints.minSeconds} extract=${hints.extract}")
        evidence.forEachIndexed { i, e ->
            appendLine("  video ${i + 1}: ${e.pageUrl} expected=${if (e.expectedSec > 0) DurationText.format(e.expectedSec.toDouble()) else "?"}")
            e.candidates.forEach { c -> appendLine("     ${if (c.truth) "✔ REAL " else if (c.decoy) "✘ PREVIEW" else "  ?    "} [${c.origin}] ${c.url.take(140)} — ${c.facts.brief()}") }
        }
        log.forEach { appendLine("  · $it") }
    }
}

/**
 * Runs the production resolver on several real videos of the site, measures every stream it can
 * find (duration, size), decides which one is the real full-length video and which are previews,
 * and writes the rule that separates them. When the rules cannot reach every video and an AI model
 * is connected, the model is shown the evidence and proposes rules / extraction regexes, which are
 * accepted only if they measurably improve the result (up to three rounds).
 */
class StreamLearner(private val http: SecureHttpClient, private val strategies: List<String>, private val engine: String) {
    private val factsCache = ConcurrentHashMap<String, StreamFacts>()

    suspend fun learn(items: List<EngineSearchItem>, sourceKey: String, ai: StreamAi? = null, onProgress: (String) -> Unit = {}): StreamLearning = coroutineScope {
        val log = mutableListOf<String>()
        val samples = pick(items, SAMPLES)
        onProgress("Изучаю настоящие потоки на ${samples.size} видео сайта…")
        val gate = Semaphore(3)
        val evidence = samples.map { item ->
            async(Dispatchers.IO) { gate.withPermit { withTimeoutOrNull(COLLECT_TIMEOUT_MS) { collect(item, sourceKey) } } }
        }.awaitAll().filterNotNull()
        if (evidence.isEmpty()) return@coroutineScope StreamLearning(StreamHints(), emptyList(), 0, "Страницы видео не открылись — потоки не изучены.", "")

        var hints = infer(evidence, log)
        var confidence = evaluate(evidence, hints)
        log += "Правила по замерам: уверенность $confidence%"
        var aiUsed = false
        var aiNote = ""
        if (confidence < 100 && ai != null) {
            onProgress("Нейросеть разбирает страницы, где настоящий поток не найден…")
            val out = refineWithAi(ai, evidence, hints, confidence, log)
            aiUsed = out.used
            aiNote = out.note
            if (out.confidence > confidence) { hints = out.hints; confidence = out.confidence }
            log += "После нейросети: уверенность $confidence%"
        }
        val truthKinds = evidence.flatMap { e -> e.candidates.filter { it.truth }.map { it.facts.kind } }
        val kind = when {
            truthKinds.any { it == "mp4" || it == "webm" } -> "mp4"
            truthKinds.any { it == "hls" } -> "hls"
            truthKinds.any { it == "dash" } -> "dash"
            else -> ""
        }
        val found = evidence.count { it.hasTruth }
        val summary = when {
            found == 0 -> "Настоящий поток измерить не удалось (найдены только превью или ничего)."
            else -> "Настоящий поток найден на $found из ${evidence.size} видео; тип: ${when (kind) { "mp4" -> "прямой MP4 (скачивается файлом)"; "hls" -> "HLS (скачивается и склеивается в MP4)"; else -> kind }}."
        }
        StreamLearning(hints, evidence, confidence, summary, kind, aiUsed, aiNote, log)
    }

    // ------------------------------------------------------------------ evidence

    private suspend fun collect(item: EngineSearchItem, sourceKey: String): VideoEvidence? {
        val policy = NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = 6 * 1024 * 1024, maxRedirects = 5)
        val page = http.fetchText(item.watchPath, policy, sourceKey)
        if (!page.success) return null
        val pageUrl = page.finalUrl.ifBlank { item.watchPath }
        val fetched = mutableListOf<Pair<String, String>>()
        val fetcher = ResolverFetcher { u ->
            http.fetchTextBlocking(u, policy, sourceKey).takeIf { it.success }?.text?.also { body ->
                synchronized(fetched) { if (fetched.size < 4 && !u.contains(".m3u8", true) && !u.contains(".mpd", true)) fetched += u to body }
            }
        }
        val resolved = runCatching { ResolverPipeline.resolve(page.text, pageUrl, engine, strategies, fetcher) }.getOrNull()
        val pipeline = resolved?.sources.orEmpty().filter { it.url.startsWith("https://", true) }
        val bodies = buildList { add(pageUrl to page.text); synchronized(fetched) { addAll(fetched) } }
        val mined = bodies.flatMap { (u, b) -> DeepMediaMiner.mine(b, u) }.filter { m -> pipeline.none { it.url.equals(m.url, true) } }
        val origin = HashMap<String, String>()
        val all = (pipeline.map { origin[it.url] = "pipeline"; it } + mined.map { origin.putIfAbsent(it.url, "mined"); it }).distinctBy { it.url.lowercase() }.take(10)
        val referer = resolved?.playerPageUrl?.ifBlank { pageUrl } ?: pageUrl
        val facts = probeAll(all, http, sourceKey, referer)
        val expected = DurationText.seconds(item.durationText).takeIf { it > 0 } ?: DurationText.fromPage(page.text)
        return VideoEvidence(pageUrl, item.title, expected, referer, classify(all, facts, expected, origin), page.text.take(MAX_BODY))
    }

    private suspend fun probeAll(list: List<MediaSource>, http: SecureHttpClient, sourceKey: String, referer: String): Map<String, StreamFacts> = coroutineScope {
        val client = http.streamingClient(NetworkPolicy(emptyList(), allowCookies = true), sourceKey)
        val probe = StreamProbe(client, referer)
        val gate = Semaphore(4)
        list.map { s ->
            async(Dispatchers.IO) {
                gate.withPermit { s.url to (factsCache[s.url] ?: (withTimeoutOrNull(9_000) { probe.probe(s) } ?: StreamFacts(s.url, note = "timeout")).also { if (it.ok) factsCache[s.url] = it }) }
            }
        }.awaitAll().toMap()
    }

    private fun classify(list: List<MediaSource>, facts: Map<String, StreamFacts>, expected: Int, origin: Map<String, String>): List<CandidateEvidence> {
        val ok = list.mapNotNull { facts[it.url] }.filter { it.ok }
        val maxDur = ok.maxOfOrNull { it.durationSec } ?: -1.0
        val maxBytes = ok.filter { it.kind == "mp4" || it.kind == "webm" }.maxOfOrNull { it.bytes } ?: -1L
        return list.map { s ->
            val f = facts[s.url] ?: StreamFacts(s.url)
            var truth = false
            var decoy = false
            if (f.ok && f.durationSec >= 0) {
                if (expected > 0) {
                    truth = kotlin.math.abs(f.durationSec - expected) <= maxOf(10.0, expected * 0.15)
                    decoy = f.durationSec < expected * 0.6
                } else if (maxDur >= 45) {
                    truth = f.durationSec >= maxDur * 0.8
                    decoy = f.durationSec < maxDur * 0.6
                }
                if (f.live) truth = false
            } else if (f.ok && f.bytes >= 0 && (f.kind == "mp4" || f.kind == "webm")) {
                if (f.bytes < 1_500_000 && (expected >= 30 || maxBytes > f.bytes * 3)) decoy = true
                else if (maxBytes > 4_000_000 && f.bytes == maxBytes) truth = true
            }
            if (EngineParsing.isPreviewClip(s.url) && !truth) decoy = true
            CandidateEvidence(s.url, origin[s.url] ?: "pipeline", f, truth, decoy)
        }
    }

    private fun pick(items: List<EngineSearchItem>, count: Int): List<EngineSearchItem> {
        val withDur = items.filter { it.durationText.isNotBlank() && it.watchPath.startsWith("https://", true) }
        val pool = if (withDur.size >= count) withDur else items.filter { it.watchPath.startsWith("https://", true) }
        if (pool.size <= count) return pool
        val last = pool.lastIndex
        return (0 until count).map { pool[(last * it) / (count - 1)] }.distinct()
    }

    // ------------------------------------------------------------------ rule inference

    private fun infer(evidence: List<VideoEvidence>, log: MutableList<String>): StreamHints {
        val truths = evidence.flatMap { e -> e.candidates.filter { it.truth }.map { it.url } }
        val decoys = evidence.flatMap { e -> e.candidates.filter { it.decoy && !it.truth }.map { it.url } }
        if (truths.isEmpty()) return StreamHints()
        var prefer = ""
        var reject = ""
        if (decoys.isNotEmpty()) {
            val tf = truths.map(::features)
            val df = decoys.map(::features)
            val onlyTruth = tf.reduce { a, b -> a intersect b } - df.fold(emptySet<String>()) { a, b -> a union b }
            val onlyDecoy = df.reduce { a, b -> a intersect b } - tf.fold(emptySet<String>()) { a, b -> a union b }
            prefer = onlyTruth.sortedBy(::priority).firstOrNull()?.let(::toRegex).orEmpty()
            reject = onlyDecoy.sortedBy(::priority).firstOrNull()?.let(::toRegex).orEmpty()
            log += "Отличие настоящего от превью: prefer='$prefer' reject='$reject'"
        }
        val decoyMax = evidence.flatMap { e -> e.candidates.filter { it.decoy && !it.truth && it.facts.durationSec >= 0 }.map { it.facts.durationSec } }.maxOrNull()
        val truthMin = evidence.flatMap { e -> e.candidates.filter { it.truth && it.facts.durationSec >= 0 }.map { it.facts.durationSec } }.minOrNull()
        val minSeconds = if (decoyMax != null && truthMin != null && truthMin > decoyMax + 20) (decoyMax + 5).toInt().coerceIn(5, 600) else 0
        // Videos where only the deep miner found the real stream need an explicit extraction rule.
        val extract = learnExtract(evidence)
        if (extract.isNotEmpty()) log += "Правило извлечения со страницы: ${extract.joinToString()}"
        return StreamHints(prefer, reject, minSeconds, extract)
    }

    private fun learnExtract(evidence: List<VideoEvidence>): List<String> {
        val minedTruth = evidence.filter { e -> e.candidates.any { it.truth && it.origin == "mined" } && e.candidates.none { it.truth && it.origin == "pipeline" } }
        if (minedTruth.isEmpty()) return emptyList()
        val rules = LinkedHashMap<String, Int>()
        for (e in minedTruth) {
            val truth = e.candidates.first { it.truth && it.origin == "mined" }.url
            val corpus = DeepMediaMiner.corpus(e.pageBody)
            var idx = corpus.indexOf(truth.removePrefix("https:"))
            if (idx < 0) idx = corpus.indexOf(truth.substringAfter("://"))
            if (idx < 4) continue
            val prefix = corpus.substring(maxOf(0, idx - 28), idx).substringAfterLast('\n')
            if (prefix.length < 3) continue
            val rx = Regex.escape(prefix.trimStart()) + "((?:https?:)?//[^\\s\"'<>\\\\]+)"
            rules[rx] = (rules[rx] ?: 0) + 1
        }
        return rules.entries.sortedByDescending { it.value }.map { it.key }.filter { rx ->
            val re = runCatching { Regex(rx, RegexOption.IGNORE_CASE) }.getOrNull() ?: return@filter false
            minedTruth.count { e -> re.findAll(DeepMediaMiner.corpus(e.pageBody)).any { m -> e.candidates.any { c -> c.truth && c.url.endsWith(m.groupValues[1].removePrefix("https:").removePrefix("http:")) } } } * 10 >= minedTruth.size * 6
        }.take(2)
    }

    private fun features(url: String): Set<String> {
        val u = runCatching { URI(url) }.getOrNull() ?: return emptySet()
        val out = linkedSetOf<String>()
        val path = u.path.orEmpty()
        out += "ext:" + path.substringAfterLast('.', "").lowercase().take(5)
        out += "host:" + u.host.orEmpty().lowercase()
        path.split('/').filter { it.isNotBlank() }.forEach { out += "seg:" + generalize(it) }
        u.query.orEmpty().split('&').map { it.substringBefore('=') }.filter { it.isNotBlank() }.forEach { out += "q:" + it.lowercase() }
        return out
    }

    private fun generalize(seg: String): String =
        seg.lowercase().replace(Regex("[0-9a-f]{12,}"), "*").replace(Regex("\\d+"), "#")

    private fun priority(f: String) = when { f.startsWith("seg:") -> 0; f.startsWith("q:") -> 1; f.startsWith("host:") -> 2; else -> 3 }

    private fun toRegex(feature: String): String {
        val kind = feature.substringBefore(':'); val v = feature.substringAfter(':')
        return when (kind) {
            "seg" -> "/" + v.split(Regex("(?<=[#*])|(?=[#*])")).joinToString("") { when (it) { "#" -> "\\d+"; "*" -> "[0-9a-f]+"; else -> Regex.escape(it) } } + "(?:[/?#]|$)"
            "q" -> "[?&]" + Regex.escape(v) + "="
            "host" -> "//" + Regex.escape(v) + "/"
            else -> "\\." + Regex.escape(v) + "(?:\\?|$)"
        }
    }

    // ------------------------------------------------------------------ evaluation

    private suspend fun evaluate(evidence: List<VideoEvidence>, hints: StreamHints): Int {
        if (evidence.isEmpty()) return 0
        var ok = 0
        for (e in evidence) if (outcome(e, hints).verdict == StreamVerdict.FULL) ok++
        return ok * 100 / evidence.size
    }

    private data class Outcome(val verdict: StreamVerdict, val top: String, val facts: StreamFacts?)

    private suspend fun outcome(e: VideoEvidence, hints: StreamHints): Outcome {
        val known = e.candidates.map { MediaSource(url = it.url, protocol = EngineParsing.protocol(it.url)) }
        val extra = if (hints.extract.isNotEmpty()) DeepMediaMiner.mine(e.pageBody, e.pageUrl, hints.extract, 8).filter { m -> known.none { it.url.equals(m.url, true) } } else emptyList()
        val all = known + extra
        val facts = HashMap<String, StreamFacts>()
        e.candidates.forEach { facts[it.url] = it.facts }
        val missing = extra.filter { it.url !in facts }
        if (missing.isNotEmpty()) facts.putAll(probeAll(missing, http, "@snp-auto-builder", e.referer))
        val ranked = StreamScorer.rank(all, facts, e.expectedSec, hints)
        val top = ranked.firstOrNull() ?: return Outcome(StreamVerdict.UNKNOWN, "", null)
        // Compare with the measurement, not just with the scorer's own verdict.
        val measuredTruth = top.facts?.takeIf { it.ok && it.durationSec >= 0 }?.let { f ->
            if (e.expectedSec > 0) kotlin.math.abs(f.durationSec - e.expectedSec) <= maxOf(10.0, e.expectedSec * 0.15) else f.durationSec >= 45
        } ?: (top.verdict == StreamVerdict.FULL)
        return Outcome(if (measuredTruth) StreamVerdict.FULL else if (top.verdict == StreamVerdict.FULL) StreamVerdict.UNKNOWN else top.verdict, top.source.url, top.facts)
    }

    // ------------------------------------------------------------------ AI

    private data class AiOutcome(val hints: StreamHints, val confidence: Int, val used: Boolean, val note: String)

    private suspend fun refineWithAi(ai: StreamAi, evidence: List<VideoEvidence>, start: StreamHints, startConfidence: Int, log: MutableList<String>): AiOutcome {
        var best = start
        var bestConf = startConfidence
        var note = ""
        var used = false
        var feedback = "Current rules reach $startConfidence% of the sampled videos."
        for (round in 1..AI_ROUNDS) {
            val reply = runCatching { ai.ask(SYSTEM_PROMPT, userPrompt(evidence, best, feedback, round)) }.getOrNull()
            if (reply.isNullOrBlank()) { log += "AI round $round: нет ответа"; break }
            used = true
            val parsed = parseHints(reply, best) ?: run { feedback = "Round $round: your reply was not a valid JSON object with the requested keys. Reply with ONLY the JSON object."; log += "AI round $round: невалидный JSON"; null } ?: continue
            val conf = evaluate(evidence, parsed.first)
            log += "AI round $round: уверенность $conf% (${parsed.second.take(120)})"
            if (conf > bestConf) { best = parsed.first; bestConf = conf; note = parsed.second }
            if (bestConf >= 100) break
            feedback = buildString {
                appendLine("Round $round result: your rules reach $conf% (best so far $bestConf%).")
                for ((i, e) in evidence.withIndex()) {
                    val o = outcome(e, parsed.first)
                    appendLine("video ${i + 1}: ${if (o.verdict == StreamVerdict.FULL) "OK" else "FAILED"} top=${o.top.take(120)} ${o.facts?.brief().orEmpty()} expected=${e.expectedSec}s")
                }
                append("Fix the failed videos without breaking the OK ones.")
            }
        }
        return AiOutcome(best, bestConf, used, note)
    }

    private fun parseHints(reply: String, fallback: StreamHints): Pair<StreamHints, String>? {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        fun rx(key: String): String = o.optString(key, "").trim().take(600).takeIf { it.isNotBlank() && runCatching { Regex(it) }.isSuccess }.orEmpty()
        val extract = buildList {
            val arr = o.optJSONArray("extract")
            if (arr != null) for (i in 0 until minOf(arr.length(), 3)) arr.optString(i, "").trim().take(600).takeIf { it.isNotBlank() && runCatching { Regex(it) }.isSuccess }?.let { add(it) }
        }
        val hints = StreamHints(
            prefer = if (o.has("prefer")) rx("prefer") else fallback.prefer,
            reject = if (o.has("reject")) rx("reject") else fallback.reject,
            minSeconds = o.optInt("min_seconds", fallback.minSeconds).coerceIn(0, 600),
            extract = if (o.has("extract")) extract else fallback.extract,
        )
        return hints to o.optString("notes", "")
    }

    private fun userPrompt(evidence: List<VideoEvidence>, current: StreamHints, feedback: String, round: Int): String = buildString {
        appendLine("Round $round of $AI_ROUNDS. Below is measured evidence from real video pages of ONE site.")
        appendLine("Goal: rules that make the app pick the REAL full-length video stream (duration ≈ expected) and never a short preview/trailer.")
        appendLine()
        for ((i, e) in evidence.withIndex()) {
            appendLine("VIDEO ${i + 1}: ${e.pageUrl}")
            appendLine("  title=\"${e.title.take(80)}\" expected_duration=${if (e.expectedSec > 0) DurationText.format(e.expectedSec.toDouble()) else "unknown"}")
            if (e.candidates.isEmpty()) appendLine("  (no media URL candidates were found at all)")
            e.candidates.forEach { c -> appendLine("  - [${c.origin}] ${c.url.take(200)} => ${c.facts.brief()} ${if (c.truth) "<REAL>" else if (c.decoy) "<PREVIEW>" else ""}") }
            val excerpts = excerpts(e.pageBody)
            if (excerpts.isNotEmpty()) { appendLine("  html excerpts around player/media code:"); excerpts.forEach { appendLine("    > $it") } }
            appendLine()
        }
        appendLine("CURRENT RULES: prefer='${current.prefer}' reject='${current.reject}' min_seconds=${current.minSeconds} extract=${current.extract}")
        appendLine(feedback)
        appendLine()
        appendLine("Reply with ONLY a JSON object: {\"prefer\":\"\",\"reject\":\"\",\"min_seconds\":0,\"extract\":[],\"notes\":\"\"}")
    }

    private fun excerpts(body: String): List<String> {
        val re = Regex("(?:\\.m3u8|\\.mp4|flashvars|playerjs|jwplayer|atob\\(|video_url|\"sources\"|<video|<source|hls\\.js|\\.webm)", RegexOption.IGNORE_CASE)
        val out = ArrayList<String>()
        var lastEnd = -1000
        for (m in re.findAll(body)) {
            if (m.range.first - lastEnd < 500) continue
            val from = maxOf(0, m.range.first - 160); val to = minOf(body.length, m.range.last + 200)
            out += body.substring(from, to).replace(Regex("\\s+"), " ")
            lastEnd = to
            if (out.size >= 3) break
        }
        return out
    }

    companion object {
        private const val SAMPLES = 5
        private const val COLLECT_TIMEOUT_MS = 40_000L
        private const val MAX_BODY = 400_000
        private const val AI_ROUNDS = 3
        private const val SYSTEM_PROMPT = """You are an expert in web video players and scraping. You tune per-site rules for an Android video app.
You are given measured evidence: for each video page, the candidate media URLs found by the app (origin pipeline = standard player parsers, mined = generic URL mining), each with a network probe (kind, duration, size) and the card's expected duration. <REAL> marks a stream whose measured duration matches the expected one, <PREVIEW> marks a short hover/trailer clip.
Return ONE JSON object and nothing else:
- prefer: Java regex matched (case-insensitive) against absolute stream URLs; should match the real stream and not previews. Empty string if unsure.
- reject: Java regex matched against absolute URLs that identifies previews/trailers/sprites. Empty string if unsure.
- min_seconds: integer; streams shorter than this are previews (0 if unknown).
- extract: up to 3 Java regexes applied (case-insensitive, dot matches newline) to the page text after unescaping \/ and & and base64 decoding; each MUST contain exactly one capture group capturing the full absolute or protocol-relative URL of the REAL stream. Use only when the real stream is present in the page text but was not found or was ranked below a preview.
- notes: one short sentence explaining the site's mechanism.
Rules: never invent hosts or URLs that are not visible in the evidence; keep regexes short and robust to changing ids/tokens; no lookbehind; escape correctly."""
    }
}

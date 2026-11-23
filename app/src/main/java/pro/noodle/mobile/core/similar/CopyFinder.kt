package pro.noodle.mobile.core.similar

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.source.SourceNames

data class VideoCopy(
    val session: PlaybackSession,
    val sourceName: String,
    val title: String,
    /** Высота кадра в пикселях; 0 — неизвестно. */
    val height: Int,
    val durationSec: Int,
    /** Кадр совпал по хешу — это точно то же видео. */
    val verified: Boolean,
    val note: String,
)

/**
 * Ищет то же видео на других подключённых источниках: поиск по названию → отбор по длительности и названию →
 * сверка кадра (dHash) в той же секунде → ранжирование по качеству.
 */
class CopyFinder(
    context: Context,
    private val http: SecureHttpClient,
    private val search: BundledSearchRepository,
    private val playback: PlaybackRepository,
) {
    @Suppress("unused") private val app = context.applicationContext

    suspend fun find(
        current: PlaybackSession,
        currentHeight: Int,
        currentDurationMs: Long,
        currentFrameHash: Long?,
        positionMs: Long,
        onProgress: (String) -> Unit,
    ): List<VideoCopy> = withContext(Dispatchers.IO) {
        val video = current.video
        val query = cleanTitle(video.title)
        if (query.isBlank()) return@withContext emptyList()
        onProgress("Ищу «$query» на других источниках…")
        var batch = search.search(query, maxPerSource = 8)
        if (batch.items.isEmpty()) {
            val short = query.split(' ').take(4).joinToString(" ")
            if (short != query) batch = search.search(short, maxPerSource = 8)
        }
        val curTokens = tokens(video.title)
        val durSec = if (currentDurationMs > 0) (currentDurationMs / 1000).toInt() else parseDuration(video.duration)
        val cands = batch.items
            .filter { !it.watchUrl.equals(video.watchUrl, true) && it.id != video.id }
            .map { it to similarity(curTokens, tokens(it.title)) }
            .filter { it.second >= 0.6 }
            .filter { (v, _) ->
                val d = parseDuration(v.duration)
                durSec <= 0 || d <= 0 || kotlin.math.abs(d - durSec) <= maxOf(6, durSec * 3 / 100)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(MAX_CANDIDATES)
        if (cands.isEmpty()) return@withContext emptyList()
        onProgress("Кандидатов: ${cands.size}. Проверяю потоки и кадры…")

        val gate = Semaphore(2)
        val results = coroutineScope {
            cands.map { v ->
                async {
                    gate.withPermit {
                        try {
                            withTimeoutOrNull(PER_CANDIDATE_MS) { check(v, durSec, currentFrameHash, positionMs, currentDurationMs) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()
        results.sortedWith(compareByDescending<VideoCopy> { it.verified }.thenByDescending { it.height })
    }

    private suspend fun check(v: VideoItem, durSec: Int, curHash: Long?, posMs: Long, curDurMs: Long): VideoCopy? {
        val adapter = search.adapterFor(v) ?: return null
        val r = playback.resolve(v, adapter)
        val session = r.session?.takeIf { r.success } ?: return null
        val src: MediaSource = session.video.sources.firstOrNull { it.isDefault } ?: session.video.sources.firstOrNull() ?: return null
        val label = pro.noodle.mobile.core.source.SourceNames.short(adapter.name, adapter.baseUrl)
        val labelHeight = Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE).find(src.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val progressive = src.parts.size <= 1 && src.protocol.equals("http", true).let { it || src.protocol.isBlank() } &&
            src.url.substringBefore('?').lowercase().let { it.endsWith(".mp4") || it.endsWith(".m4v") || it.endsWith(".webm") }
        if (progressive && curHash != null) {
            val client = http.streamingClient(session.networkPolicy, session.sourceKey)
            val base = if (curDurMs > 0) posMs.coerceIn(0, curDurMs) else posMs
            val probe = CopyProber.probe(client, src.url, session.referer, listOf(base, base + 3000, (base - 3000).coerceAtLeast(0)))
                ?: return null
            val candDur = (probe.durationMs / 1000).toInt()
            if (durSec > 0 && candDur > 0 && kotlin.math.abs(candDur - durSec) > maxOf(8, durSec * 4 / 100)) return null
            val best = probe.hashes.minOfOrNull { FrameGrabber.hamming(it, curHash) } ?: return null
            if (best > UNLIKELY) return null
            val sure = best <= SAME
            return VideoCopy(session, label, v.title, probe.height.takeIf { it > 0 } ?: labelHeight, candDur, sure,
                if (sure) "кадр совпал" else "похоже, кадр отличается")
        }
        // HLS/DASH или части: кадр сверить нельзя — принимаем только при совпавшей длительности и названии.
        val cardDur = parseDuration(v.duration)
        if (durSec <= 0 || cardDur <= 0) return null
        return VideoCopy(session, label, v.title, labelHeight, cardDur, false, "по названию и длительности")
    }

    companion object {
        private const val MAX_CANDIDATES = 8
        private const val PER_CANDIDATE_MS = 60_000L
        private const val SAME = 14
        private const val UNLIKELY = 22

        fun cleanTitle(t: String): String = t
            .replace(Regex("[\\[(].*?[\\])]"), " ")
            .replace(Regex("\\s+[-|–—]\\s+(?:смотреть|watch).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d{3,}\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .split(' ').take(8).joinToString(" ")

        fun tokens(t: String): Set<String> = SourceNames.translit(cleanTitle(t).lowercase().replace('ё', 'е'))
            .split(' ').map { it.trim() }.filter { it.length >= 3 }.toSet()

        fun similarity(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val inter = a.intersect(b).size
            return inter.toDouble() / minOf(a.size, b.size)
        }

        fun parseDuration(s: String): Int {
            val m = Regex("(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)").find(s) ?: return 0
            val h = m.groupValues[1].toIntOrNull() ?: 0
            return h * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        }
    }
}

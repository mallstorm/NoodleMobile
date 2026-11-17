package pro.noodle.mobile.core.recommendations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.search.BundledSearchRepository
import java.util.concurrent.ConcurrentHashMap

class RelatedVideosRepository(
    context: Context,
    private val http: SecureHttpClient = SecureHttpClient(),
) {
    private val search = BundledSearchRepository(context.applicationContext, http)
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val thumbnailMisses = ConcurrentHashMap.newKeySet<String>()
    private val relatedCache = ConcurrentHashMap<String, List<VideoItem>>()
    private val thumbnailSlots = Semaphore(2)

    suspend fun load(current: VideoItem, limit: Int = 8): List<VideoItem> {
        val cacheKey = current.watchUrl.ifBlank { current.id }.lowercase()
        relatedCache[cacheKey]?.let { return it.take(limit.coerceIn(4, 12)) }

        val query = relatedQuery(current)
        if (query.isBlank()) return emptyList()

        // Watch page must not repeat a heavy search across every enabled SNP. The current
        // source is the closest equivalent to the site's own related/recommended section.
        val batch = search.searchSameSource(
            video = current,
            query = query,
            maxPerSource = limit.coerceIn(4, 12),
        )
        val currentKey = current.watchUrl.lowercase()
        val result = batch.items
            .asSequence()
            .filter { it.watchUrl.isNotBlank() && it.watchUrl.lowercase() != currentKey }
            .distinctBy { "${it.sourceBaseUrl}|${it.remoteId}|${it.watchUrl}".lowercase() }
            .sortedByDescending { relevanceScore(current, it) }
            .take(limit.coerceIn(4, 12))
            .toList()

        if (result.isNotEmpty()) relatedCache[cacheKey] = result
        return result
    }

    suspend fun loadThumbnail(video: VideoItem): Bitmap? = thumbnailSlots.withPermit {
        val url = video.thumbnailUrl.trim()
        if (url.isBlank()) return@withPermit null
        thumbnailCache[url]?.let { return@withPermit it }
        if (url in thumbnailMisses) return@withPermit null
        val adapter = search.adapterFor(video)
        val result = pro.noodle.mobile.core.media.ThumbnailFetcher.fetch(
            http, video, adapter?.baseUrl.orEmpty(), adapter?.id ?: video.sourceName.ifBlank { "thumbnails" }, adapter?.allowCookies ?: false, 1536 * 1024,
        )
        val bytes = result.bytes
        if (bytes == null || bytes.isEmpty()) {
            if (result.status != 0) thumbnailMisses += url
            return@withPermit null
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            thumbnailMisses += url
            return@withPermit null
        }
        thumbnailCache[url] = bitmap
        bitmap
    }

    private fun relatedQuery(video: VideoItem): String {
        val tags = video.tags
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .take(4)
        if (tags.isNotEmpty()) return tags.joinToString(" ")

        val stop = setOf(
            "video", "watch", "online", "the", "and", "with", "для", "как", "это", "что", "или",
            "про", "при", "без", "его", "ее", "она", "они", "где", "когда", "почему",
        )
        return Regex("[\\p{L}\\p{N}]+")
            .findAll(video.title.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in stop }
            .distinct()
            .take(4)
            .joinToString(" ")
    }

    private fun relevanceScore(current: VideoItem, candidate: VideoItem): Int {
        val currentTokens = Regex("[\\p{L}\\p{N}]+")
            .findAll((current.title + " " + current.tags.joinToString(" ")).lowercase())
            .map { it.value }
            .filter { it.length >= 3 }
            .toSet()
        if (currentTokens.isEmpty()) return 0
        val candidateTokens = Regex("[\\p{L}\\p{N}]+")
            .findAll((candidate.title + " " + candidate.tags.joinToString(" ")).lowercase())
            .map { it.value }
            .filter { it.length >= 3 }
            .toSet()
        return currentTokens.count { it in candidateTokens }
    }
}

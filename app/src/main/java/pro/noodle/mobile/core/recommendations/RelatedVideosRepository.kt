package pro.noodle.mobile.core.recommendations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    suspend fun load(current: VideoItem, limit: Int = 16): List<VideoItem> {
        val query = relatedQuery(current)
        if (query.isBlank()) return emptyList()

        val batch = search.search(query, maxPerSource = limit.coerceIn(8, 24))
        val currentKey = current.watchUrl.lowercase()
        val sameSource = current.sourceBaseUrl.lowercase()

        return batch.items
            .asSequence()
            .filter { it.watchUrl.isNotBlank() && it.watchUrl.lowercase() != currentKey }
            .distinctBy { "${it.sourceBaseUrl}|${it.remoteId}|${it.watchUrl}".lowercase() }
            .sortedWith(
                compareByDescending<VideoItem> { it.sourceBaseUrl.lowercase() == sameSource }
                    .thenByDescending { relevanceScore(current, it) },
            )
            .take(limit.coerceIn(4, 24))
            .toList()
    }

    suspend fun loadThumbnail(video: VideoItem): Bitmap? {
        val url = video.thumbnailUrl.trim()
        if (url.isBlank()) return null
        thumbnailCache[url]?.let { return it }
        if (url in thumbnailMisses) return null
        val adapter = search.adapterFor(video) ?: return null
        val result = http.fetchBytes(
            url = url,
            policy = NetworkPolicy(
                allowedHosts = adapter.allowedHosts,
                allowCookies = adapter.allowCookies,
                maxResponseBytes = 3 * 1024 * 1024,
            ),
            sourceKey = adapter.id,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.5",
            referer = adapter.baseUrl,
        )
        if (!result.success || result.bytes.isEmpty()) {
            thumbnailMisses += url
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        if (bitmap == null) {
            thumbnailMisses += url
            return null
        }
        thumbnailCache[url] = bitmap
        return bitmap
    }

    private fun relatedQuery(video: VideoItem): String {
        val tags = video.tags
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .take(5)
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
            .take(6)
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

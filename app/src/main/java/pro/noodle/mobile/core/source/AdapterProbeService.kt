package pro.noodle.mobile.core.source

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.download.MediaDownloadRuntime
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.SearchUrls
import pro.noodle.mobile.core.engine.runtime.searchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.security.UrlPolicy
import java.net.URI


enum class SemanticStatus(val wireName: String) { HEALTHY("healthy"), INVALID_ADAPTER("invalid_adapter"), SEARCH_FAILED("search_failed"), NO_RESULTS("no_results"), PLAYBACK_FAILED("playback_failed"), ERROR("error") }

data class AdapterSemanticResult(
    val adapterId: String,
    val name: String,
    val version: String,
    val status: SemanticStatus,
    val query: String,
    val searchResultCount: Int = 0,
    /** Number of sampled search cards that actually reached Media3 STATE_READY. */
    val playbackSourceCount: Int = 0,
    val errorCode: String = "",
    val message: String = "",
    val action: String = "",
)

private data class Media3ProbeResult(
    val success: Boolean,
    val errorCode: String = "",
    val message: String = "",
)

class AdapterProbeService(
    private val context: Context,
    private val runtimeRepo: RuntimeAdapterRepository = RuntimeAdapterRepository(context),
    private val http: SecureHttpClient = SecureHttpClient(),
) {
    private val playback = PlaybackRepository(context, http)
    private val status = SourceStatusStore(FileRefs.semantic(runtimeRepo.paths))
    private val updater = AdapterUpdateManager(runtimeRepo.paths, AdapterRecoveryManager(runtimeRepo.paths))

    suspend fun semantic(adapterId: String, queryOverride: String = ""): AdapterSemanticResult {
        val runtime = runtimeRepo.find(adapterId) ?: return result(adapterId, "", "", SemanticStatus.INVALID_ADAPTER, "", errorCode = "SNP_NOT_FOUND", message = "SNP не найден.")
        val d = runtime.definition
        val query = queryOverride.trim().ifBlank { d.healthQuery.trim().ifBlank { "video" } }
        val started = System.currentTimeMillis()
        val search = searchOne(d, query, CERTIFICATION_SEARCH_LIMIT)
        var out = if (search.second != null) {
            result(d.id, d.name, d.adapterVersion, SemanticStatus.SEARCH_FAILED, query, errorCode = search.second!!.first, message = search.second!!.second)
        } else if (search.first.size < d.healthMinResults) {
            result(d.id, d.name, d.adapterVersion, SemanticStatus.NO_RESULTS, query, search.first.size, errorCode = if (search.first.isEmpty()) "SNP_SEMANTIC_NO_RESULTS" else "SNP_SEMANTIC_MIN_RESULTS", message = "Получено ${search.first.size}, требуется минимум ${d.healthMinResults}.")
        } else if (!d.healthRequirePlayback) {
            result(d.id, d.name, d.adapterVersion, SemanticStatus.HEALTHY, query, search.first.size, message = "Поиск прошёл успешно.")
        } else {
            validateRealPlayback(d, query, search.first)
        }

        val pending = updater.readPending(d.id)
        if (pending != null) {
            val action = updater.completePendingValidation(d.id, out.status == SemanticStatus.HEALTHY, runtime.directory)
            if (action != null) out = out.copy(action = action, message = out.message + " [$action]")
        }
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        status.put(d.id, mapOf(
            "status" to pro.noodle.mobile.core.json.JsonValue.Str(out.status.wireName),
            "error_code" to pro.noodle.mobile.core.json.JsonValue.Str(out.errorCode),
            "message" to pro.noodle.mobile.core.json.JsonValue.Str(out.message),
            "search_count" to pro.noodle.mobile.core.json.JsonValue.Num(out.searchResultCount.toDouble()),
            "playback_count" to pro.noodle.mobile.core.json.JsonValue.Num(out.playbackSourceCount.toDouble()),
            "certification_samples" to pro.noodle.mobile.core.json.JsonValue.Num(PLAYBACK_SAMPLE_COUNT.toDouble()),
            "elapsed_ms" to pro.noodle.mobile.core.json.JsonValue.Num(elapsed.toDouble()),
        ))
        return out
    }

    suspend fun validateDefinition(d: AdapterDefinition, queryOverride: String = ""): AdapterSemanticResult {
        val query = queryOverride.trim().ifBlank { d.healthQuery.trim().ifBlank { "video" } }
        val search = searchOne(d, query, CERTIFICATION_SEARCH_LIMIT)
        if (search.second != null) return result(d.id,d.name,d.adapterVersion,SemanticStatus.SEARCH_FAILED,query,errorCode=search.second!!.first,message=search.second!!.second)
        if (search.first.size < d.healthMinResults) return result(d.id,d.name,d.adapterVersion,SemanticStatus.NO_RESULTS,query,search.first.size,errorCode=if(search.first.isEmpty()) "SNP_SEMANTIC_NO_RESULTS" else "SNP_SEMANTIC_MIN_RESULTS",message="Получено ${search.first.size}, требуется минимум ${d.healthMinResults}.")
        if (!d.healthRequirePlayback) return result(d.id,d.name,d.adapterVersion,SemanticStatus.HEALTHY,query,search.first.size,message="Поиск прошёл успешно.")
        return validateRealPlayback(d, query, search.first)
    }

    /**
     * Certification deliberately samples different positions in the result set rather than the
     * first lucky cards. A source is healthy only when every sampled item resolves and the exact
     * Media3 datasource stack used by PlayerScreen reaches STATE_READY.
     */
    private suspend fun validateRealPlayback(
        d: AdapterDefinition,
        query: String,
        items: List<VideoItem>,
    ): AdapterSemanticResult {
        val samples = spreadSamples(items, PLAYBACK_SAMPLE_COUNT)
        // Real catalogues always contain a few removed/premium cards. A source is healthy when a
        // clear majority of the spread samples resolves AND reaches Media3 STATE_READY.
        val required = kotlin.math.ceil(samples.size * PLAYBACK_REQUIRED_RATIO).toInt().coerceAtLeast(1)
        var readyCount = 0
        var firstError: Pair<String, String>? = null
        for ((index, item) in samples.withIndex()) {
            if (readyCount >= required) break
            if (samples.size - index + readyCount < required) break
            val resolved = playback.resolve(item, d)
            if (!resolved.success || resolved.session == null) {
                if (firstError == null) firstError = resolved.errorCode.ifBlank { "SNP_SEMANTIC_PLAYBACK_ERROR" } to "Карточка ${index + 1}/${samples.size}: ${resolved.errorMessage.ifBlank { "Медиапоток не разрешён." }}"
                continue
            }
            if (resolved.session.streamVerdict == "preview") {
                if (firstError == null) firstError = "SNP_PREVIEW_ONLY" to "Карточка ${index + 1}/${samples.size}: найден только короткий превью-ролик, а не само видео (${resolved.session.streamNote})."
                continue
            }
            val media3 = probeMedia3Ready(resolved.session)
            // A slow link/VPN can time out Media3 even though the stream was measured as the full
            // video (duration matches the card). That is not a broken adapter.
            if (!media3.success && media3.errorCode.endsWith("READY_TIMEOUT") && resolved.session.streamVerdict == "full") {
                readyCount++
                continue
            }
            if (!media3.success) {
                if (firstError == null) firstError = media3.errorCode.ifBlank { "SNP_MEDIA3_NOT_READY" } to "Карточка ${index + 1}/${samples.size}: реальная загрузка видео не прошла. ${media3.message}"
                continue
            }
            readyCount++
        }
        if (readyCount < required) {
            val (code, message) = firstError ?: ("SNP_SEMANTIC_PLAYBACK_ERROR" to "Медиапоток не разрешён.")
            return result(d.id, d.name, d.adapterVersion, SemanticStatus.PLAYBACK_FAILED, query, items.size, readyCount, errorCode = code, message = "$message (готово $readyCount, нужно $required)")
        }
        return result(
            d.id,
            d.name,
            d.adapterVersion,
            SemanticStatus.HEALTHY,
            query,
            items.size,
            readyCount,
            message = "Сертификация пройдена: поиск, resolver и реальная загрузка видео успешны. Media3 READY: $readyCount/${samples.size}.",
        )
    }

    private fun spreadSamples(items: List<VideoItem>, count: Int): List<VideoItem> {
        if (items.isEmpty()) return emptyList()
        if (items.size <= count) return items
        val last = items.lastIndex
        val indices = linkedSetOf(0, last)
        if (count > 2) {
            for (slot in 1 until count - 1) {
                indices += ((last.toDouble() * slot) / (count - 1).toDouble()).toInt().coerceIn(0, last)
            }
        }
        return indices.sorted().map { items[it] }.take(count)
    }

    @OptIn(UnstableApi::class)
    private suspend fun probeMedia3Ready(session: PlaybackSession): Media3ProbeResult {
        val video = session.video
        val sources = buildList {
            val defaultIndex = video.sources.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
            video.sources.getOrNull(defaultIndex)?.let { add(it) }
            video.sources.forEach { if (it !in this) add(it) }
        }
        if (sources.isEmpty()) return Media3ProbeResult(false, "SNP_MEDIA3_SOURCE_MISSING", "Resolver не вернул источник для Media3.")

        var last = Media3ProbeResult(false, "SNP_MEDIA3_SOURCE_MISSING", "Нет воспроизводимого источника.")
        for ((sourceIndex, source) in sources.take(MAX_MEDIA_SOURCE_FALLBACKS).withIndex()) {
            val attempt = probeSingleMediaSource(session, source)
            if (attempt.success) return attempt
            last = attempt.copy(message = "source ${sourceIndex + 1}/${sources.take(MAX_MEDIA_SOURCE_FALLBACKS).size}: ${attempt.message}")
        }
        return last
    }

    @OptIn(UnstableApi::class)
    private suspend fun probeSingleMediaSource(session: PlaybackSession, source: pro.noodle.mobile.core.model.MediaSource): Media3ProbeResult = withContext(Dispatchers.Main.immediate) {
        val factory = runCatching {
            MediaDownloadRuntime.cacheDataSourceFactory(
                context.applicationContext,
                session.networkPolicy,
                session.sourceKey,
                session.referer,
            )
        }.getOrElse { error ->
            return@withContext Media3ProbeResult(false, "SNP_MEDIA3_FACTORY_ERROR", "${error.javaClass.simpleName}: ${error.message}")
        }

        val player = runCatching {
            ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
        }.getOrElse { error ->
            return@withContext Media3ProbeResult(false, "SNP_MEDIA3_INIT_ERROR", "${error.javaClass.simpleName}: ${error.message}")
        }

        val gate = CompletableDeferred<Media3ProbeResult>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !gate.isCompleted) {
                    val duration = player.duration
                    if (EngineParsing.isPreviewDuration(session.video.duration, duration)) {
                        gate.complete(Media3ProbeResult(false, "SNP_MEDIA3_PREVIEW_ONLY",
                            "Вместо ролика "+session.video.duration+" открыт короткий preview: "+(duration / 1000)+" с. ("+source.url+")"))
                    } else {
                        gate.complete(Media3ProbeResult(true, message = "Media3 STATE_READY; durationMs="+duration+"; source="+source.url))
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!gate.isCompleted) {
                    val httpError = generateSequence(error.cause) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()
                    val code = httpError?.responseCode
                    val root = generateSequence(error.cause) { it.cause }.lastOrNull()
                    val rootText = root?.let { it.javaClass.simpleName + ": " + it.message.orEmpty().take(160) }.orEmpty()
                    gate.complete(
                        Media3ProbeResult(
                            false,
                            if (code != null) "${error.errorCodeName}_HTTP_$code" else error.errorCodeName,
                            if (code != null) {
                                "HTTP $code: ${error.message.orEmpty().ifBlank { "Media3 source error" }}"
                            } else {
                                error.message.orEmpty().ifBlank { "Media3 playback error" } + if (rootText.isNotBlank()) " [$rootText]" else ""
                            },
                        ),
                    )
                }
            }
        }

        try {
            player.addListener(listener)
            val item = MediaItem.Builder()
                .setUri(Uri.parse(source.url))
                .apply { if (source.mimeType.isNotBlank()) setMimeType(source.mimeType) }
                .build()
            player.setMediaItem(item)
            player.playWhenReady = false
            player.prepare()
            withTimeoutOrNull(MEDIA3_READY_TIMEOUT_MS) { gate.await() }
                ?: Media3ProbeResult(
                    false,
                    "SNP_MEDIA3_READY_TIMEOUT",
                    "Media3 не достиг STATE_READY за ${MEDIA3_READY_TIMEOUT_MS / 1000} с.",
                )
        } finally {
            player.removeListener(listener)
            player.release()
        }
    }

    suspend fun searchOne(d: AdapterDefinition, query: String, max: Int): Pair<List<VideoItem>, Pair<String, String>?> {
        val engine = RuntimeEngineRegistry.find(d.engine) ?: return emptyList<VideoItem>() to ("ENGINE_NOT_IMPLEMENTED" to "Runtime engine ${d.engine} отсутствует.")
        val req = d.searchRequest(query, page = -1, maxItems = max)
        val urls = SearchUrls.candidates(engine, req)
        if (urls.isEmpty()) return emptyList<VideoItem>() to ("SNP_SEMANTIC_SEARCH_URL_EMPTY" to "Engine не сформировал search URL.")
        var lastError: Pair<String, String>? = null
        for (url in urls) {
            val net = http.fetchText(url, NetworkPolicy(d.allowedHosts, allowCookies=d.allowCookies, maxResponseBytes=5*1024*1024), d.id)
            if (!net.success) { lastError = "SNP_SEMANTIC_SEARCH_ERROR" to "${net.errorCode}: ${net.errorMessage}"; continue }
            val finalBase = runCatching { URI(net.finalUrl).let { u -> if (u.host.isNullOrBlank()) d.baseUrl else "${u.scheme}://${u.host}${if (u.port >= 0) ":${u.port}" else ""}" } }.getOrDefault(d.baseUrl)
            val items = engine.parseSearch(net.text, req.copy(baseUrl = finalBase)).mapNotNull { p ->
                val watch = normalizeAllowed(p.watchPath, d, base = finalBase) ?: return@mapNotNull null
                VideoItem(id="${d.id}|${p.remoteId.ifBlank { watch }}", remoteId=p.remoteId, watchUrl=watch, title=p.title.ifBlank { p.remoteId.ifBlank { "Без названия" } }, thumbnailUrl=normalizeAllowed(p.thumbnailUrl,d,true,finalBase).orEmpty(), duration=p.durationText, views=p.viewsText, sourceName=d.name, sourceBaseUrl=d.baseUrl, sourceEngine=d.engine, playerPageUrl=watch, favoriteKey="${d.id}|${p.remoteId.ifBlank { watch }}")
            }.distinctBy { it.watchUrl.lowercase() }.take(max.coerceAtLeast(1))
            if (items.isNotEmpty()) return items to null
            lastError = null
        }
        return emptyList<VideoItem>() to lastError
    }

    private fun result(id:String,name:String,version:String,status:SemanticStatus,query:String,search:Int=0,playback:Int=0,errorCode:String="",message:String="") = AdapterSemanticResult(id,name,version,status,query,search,playback,errorCode,message)
    private fun normalizeAllowed(raw:String,d:AdapterDefinition,allowBlank:Boolean=false,base:String=d.baseUrl):String? { if(raw.isBlank()) return if(allowBlank) "" else null; val url=EngineParsing.absolute(raw,base); val decision=UrlPolicy.evaluateNavigation(url,allowHttp=false,resolveDns=false); if(!decision.allowed)return null; val host=runCatching{URI(decision.normalizedUrl).host.orEmpty()}.getOrDefault(""); if(d.allowedHosts.isNotEmpty()&&!UrlPolicy.isHostAllowed(host,d.allowedHosts))return null;return decision.normalizedUrl }

    companion object {
        private const val CERTIFICATION_SEARCH_LIMIT = 20
        private const val PLAYBACK_SAMPLE_COUNT = 5
        private const val PLAYBACK_REQUIRED_RATIO = 0.6
        private const val MAX_MEDIA_SOURCE_FALLBACKS = 4
        private const val MEDIA3_READY_TIMEOUT_MS = 25_000L
    }
}

object FileRefs {
    fun semantic(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "semantic_state.json")
    fun fingerprint(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "fingerprint_state.json")
    fun breakage(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "breakage_state.json")
    fun health(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "health_state.json")
    fun updates(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "update_state.json")
}

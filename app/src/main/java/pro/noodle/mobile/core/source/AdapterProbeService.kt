package pro.noodle.mobile.core.source

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.download.MediaDownloadRuntime
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
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
        val search = searchOne(d, query, 8)
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
        status.put(d.id, mapOf(
            "status" to pro.noodle.mobile.core.json.JsonValue.Str(out.status.wireName),
            "error_code" to pro.noodle.mobile.core.json.JsonValue.Str(out.errorCode),
            "message" to pro.noodle.mobile.core.json.JsonValue.Str(out.message),
            "search_count" to pro.noodle.mobile.core.json.JsonValue.Num(out.searchResultCount.toDouble()),
            "playback_count" to pro.noodle.mobile.core.json.JsonValue.Num(out.playbackSourceCount.toDouble()),
        ))
        return out
    }

    suspend fun validateDefinition(d: AdapterDefinition, queryOverride: String = ""): AdapterSemanticResult {
        val query = queryOverride.trim().ifBlank { d.healthQuery.trim().ifBlank { "video" } }
        val search = searchOne(d, query, 8)
        if (search.second != null) return result(d.id,d.name,d.adapterVersion,SemanticStatus.SEARCH_FAILED,query,errorCode=search.second!!.first,message=search.second!!.second)
        if (search.first.size < d.healthMinResults) return result(d.id,d.name,d.adapterVersion,SemanticStatus.NO_RESULTS,query,search.first.size,errorCode=if(search.first.isEmpty()) "SNP_SEMANTIC_NO_RESULTS" else "SNP_SEMANTIC_MIN_RESULTS",message="Получено ${search.first.size}, требуется минимум ${d.healthMinResults}.")
        if (!d.healthRequirePlayback) return result(d.id,d.name,d.adapterVersion,SemanticStatus.HEALTHY,query,search.first.size,message="Поиск прошёл успешно.")
        return validateRealPlayback(d, query, search.first)
    }

    /**
     * A resolver hit is not enough to call an SNP healthy. The regular player may still receive
     * 403/404/5xx from the manifest, segment or progressive media URL. Live Test therefore runs
     * the same Media3 datasource stack as PlayerScreen and waits until the source reaches READY.
     * Two different search cards are sampled so a single lucky/stale URL cannot mask a broken SNP.
     */
    private suspend fun validateRealPlayback(
        d: AdapterDefinition,
        query: String,
        items: List<VideoItem>,
    ): AdapterSemanticResult {
        val samples = items.take(PLAYBACK_SAMPLE_COUNT.coerceAtMost(items.size))
        var readyCount = 0
        for ((index, item) in samples.withIndex()) {
            val resolved = playback.resolve(item, d)
            if (!resolved.success || resolved.session == null) {
                return result(
                    d.id,
                    d.name,
                    d.adapterVersion,
                    SemanticStatus.PLAYBACK_FAILED,
                    query,
                    items.size,
                    readyCount,
                    errorCode = resolved.errorCode.ifBlank { "SNP_SEMANTIC_PLAYBACK_ERROR" },
                    message = "Карточка ${index + 1}/${samples.size}: ${resolved.errorMessage.ifBlank { "Медиапоток не разрешён." }}",
                )
            }

            val media3 = probeMedia3Ready(resolved.session)
            if (!media3.success) {
                return result(
                    d.id,
                    d.name,
                    d.adapterVersion,
                    SemanticStatus.PLAYBACK_FAILED,
                    query,
                    items.size,
                    readyCount,
                    errorCode = media3.errorCode.ifBlank { "SNP_MEDIA3_NOT_READY" },
                    message = "Карточка ${index + 1}/${samples.size}: реальная загрузка видео не прошла. ${media3.message}",
                )
            }
            readyCount++
        }

        return result(
            d.id,
            d.name,
            d.adapterVersion,
            SemanticStatus.HEALTHY,
            query,
            items.size,
            readyCount,
            message = "Поиск, разрешение и реальная загрузка видео прошли успешно. Media3 READY: $readyCount/${samples.size}.",
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun probeMedia3Ready(session: PlaybackSession): Media3ProbeResult {
        val video = session.video
        val sourceIndex = video.sources.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        val source = video.sources.getOrNull(sourceIndex)
            ?: return Media3ProbeResult(false, "SNP_MEDIA3_SOURCE_MISSING", "Resolver не вернул источник для Media3.")

        return withContext(Dispatchers.Main.immediate) {
            val factory = runCatching {
                MediaDownloadRuntime.cacheDataSourceFactory(
                    context.applicationContext,
                    session.networkPolicy,
                    session.sourceKey,
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
                        gate.complete(Media3ProbeResult(true, message = "Media3 STATE_READY"))
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!gate.isCompleted) {
                        gate.complete(
                            Media3ProbeResult(
                                false,
                                error.errorCodeName,
                                error.message.orEmpty().ifBlank { "Media3 playback error" },
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
    }

    suspend fun searchOne(d: AdapterDefinition, query: String, max: Int): Pair<List<VideoItem>, Pair<String, String>?> {
        val engine = RuntimeEngineRegistry.find(d.engine) ?: return emptyList<VideoItem>() to ("ENGINE_NOT_IMPLEMENTED" to "Runtime engine ${d.engine} отсутствует.")
        val req = EngineSearchRequest(baseUrl=d.baseUrl,query=query,page=0,maxItems=max,searchUrlTemplate=d.searchUrlTemplate,itemSelector=d.itemSelector,titleSelector=d.titleSelector,urlSelector=d.urlSelector,thumbnailSelector=d.thumbnailSelector,thumbnailAttribute=d.thumbnailAttribute,durationSelector=d.durationSelector,viewsSelector=d.viewsSelector,itemsPath=d.itemsPath,idPath=d.idPath,titlePath=d.titlePath,urlPath=d.urlPath,thumbnailPath=d.thumbnailPath,durationPath=d.durationPath,viewsPath=d.viewsPath)
        val url = engine.buildSearchUrl(req).ifBlank { templateSearchUrl(req) }
        if (url.isBlank()) return emptyList<VideoItem>() to ("SNP_SEMANTIC_SEARCH_URL_EMPTY" to "Engine не сформировал search URL.")
        val net = http.fetchText(url, NetworkPolicy(d.allowedHosts, allowCookies=d.allowCookies, maxResponseBytes=5*1024*1024), d.id)
        if (!net.success) return emptyList<VideoItem>() to ("SNP_SEMANTIC_SEARCH_ERROR" to "${net.errorCode}: ${net.errorMessage}")
        val items = engine.parseSearch(net.text, req).mapNotNull { p ->
            val watch = normalizeAllowed(p.watchPath, d) ?: return@mapNotNull null
            VideoItem(id="${d.id}|${p.remoteId.ifBlank { watch }}", remoteId=p.remoteId, watchUrl=watch, title=p.title.ifBlank { p.remoteId.ifBlank { "Без названия" } }, thumbnailUrl=normalizeAllowed(p.thumbnailUrl,d,true).orEmpty(), duration=p.durationText, views=p.viewsText, sourceName=d.name, sourceBaseUrl=d.baseUrl, sourceEngine=d.engine, playerPageUrl=watch, favoriteKey="${d.id}|${p.remoteId.ifBlank { watch }}")
        }
        return items to null
    }

    private fun result(id:String,name:String,version:String,status:SemanticStatus,query:String,search:Int=0,playback:Int=0,errorCode:String="",message:String="") = AdapterSemanticResult(id,name,version,status,query,search,playback,errorCode,message)
    private fun templateSearchUrl(r:EngineSearchRequest):String = if(r.searchUrlTemplate.isBlank()) "" else EngineParsing.absolute(r.searchUrlTemplate.replace("{base_url}",r.baseUrl,true).replace("{query}",EngineParsing.encodedQuery(r.query),true).replace("{page}",(r.page+1).toString(),true),r.baseUrl)
    private fun normalizeAllowed(raw:String,d:AdapterDefinition,allowBlank:Boolean=false):String? { if(raw.isBlank()) return if(allowBlank) "" else null; val url=EngineParsing.absolute(raw,d.baseUrl); val decision=UrlPolicy.evaluateNavigation(url,allowHttp=false,resolveDns=false); if(!decision.allowed)return null; val host=runCatching{URI(decision.normalizedUrl).host.orEmpty()}.getOrDefault(""); if(d.allowedHosts.isNotEmpty()&&!UrlPolicy.isHostAllowed(host,d.allowedHosts))return null;return decision.normalizedUrl }

    companion object {
        private const val PLAYBACK_SAMPLE_COUNT = 2
        private const val MEDIA3_READY_TIMEOUT_MS = 15_000L
    }
}

object FileRefs {
    fun semantic(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "semantic_state.json")
    fun fingerprint(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "fingerprint_state.json")
    fun breakage(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "breakage_state.json")
    fun health(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "health_state.json")
    fun updates(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "update_state.json")
}

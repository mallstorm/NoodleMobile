package pro.noodle.mobile.core.source

import android.content.Context
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
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
    val playbackSourceCount: Int = 0,
    val errorCode: String = "",
    val message: String = "",
    val action: String = "",
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
            val p = playback.resolve(search.first.first(), d)
            if (!p.success || p.session == null) result(d.id, d.name, d.adapterVersion, SemanticStatus.PLAYBACK_FAILED, query, search.first.size, errorCode = p.errorCode.ifBlank { "SNP_SEMANTIC_PLAYBACK_ERROR" }, message = p.errorMessage.ifBlank { "Медиапоток не разрешён." })
            else result(d.id, d.name, d.adapterVersion, SemanticStatus.HEALTHY, query, search.first.size, p.session.video.sources.size, message = "Поиск и разрешение медиапотока прошли успешно.")
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
        val p = playback.resolve(search.first.first(), d)
        return if (!p.success || p.session == null) result(d.id,d.name,d.adapterVersion,SemanticStatus.PLAYBACK_FAILED,query,search.first.size,errorCode=p.errorCode.ifBlank { "SNP_SEMANTIC_PLAYBACK_ERROR" },message=p.errorMessage.ifBlank { "Медиапоток не разрешён." })
        else result(d.id,d.name,d.adapterVersion,SemanticStatus.HEALTHY,query,search.first.size,p.session.video.sources.size,message="Поиск и разрешение медиапотока прошли успешно.")
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
}

object FileRefs {
    fun semantic(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "semantic_state.json")
    fun fingerprint(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "fingerprint_state.json")
    fun breakage(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "breakage_state.json")
    fun health(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "health_state.json")
    fun updates(paths: AdapterStorePaths) = java.io.File(paths.diagnostics, "update_state.json")
}

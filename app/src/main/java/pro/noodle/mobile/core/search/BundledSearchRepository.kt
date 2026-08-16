package pro.noodle.mobile.core.search

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import java.net.URI

data class SourceSearchError(val sourceId:String,val sourceName:String,val errorCode:String,val message:String)
data class SearchBatch(val items:List<VideoItem>,val errors:List<SourceSearchError>)

class BundledSearchRepository(
    context: Context,
    private val http: SecureHttpClient = SecureHttpClient(),
    private val parallelism: Int = 3,
) {
    private val runtimeRepo = RuntimeAdapterRepository(context.applicationContext)
    private fun allAdapters(): List<AdapterDefinition> = runtimeRepo.listAll().map { it.definition }
    private val overrides = SourceOverridesRepository(context.applicationContext)

    private suspend fun activeAdapters(): List<AdapterDefinition> {
        val disabled = overrides.disabledSourceIds.first()
        return runtimeRepo.listAll().filter { it.compatibility.compatible && it.packageInfo.valid }.map { it.definition }.filter { it.enabled && it.id.lowercase() !in disabled }
    }

    suspend fun search(query:String,page:Int=-1,maxPerSource:Int=30):SearchBatch = coroutineScope {
        if(query.isBlank()) return@coroutineScope SearchBatch(emptyList(), emptyList())
        val adapters = activeAdapters()
        directUrlResult(query, adapters)?.let { return@coroutineScope it }
        val sem=Semaphore(parallelism.coerceIn(1,6))
        val parts=adapters.map { adapter -> async { sem.withPermit { searchOne(adapter,query,page,maxPerSource) } } }.awaitAll()
        SearchBatch(parts.flatMap { it.first }.distinctBy { "${it.sourceBaseUrl}|${it.remoteId}|${it.watchUrl}".lowercase() },parts.mapNotNull { it.second })
    }

    private fun directUrlResult(query:String, adapters: List<AdapterDefinition>):SearchBatch? {
        val raw=query.trim()
        if(!raw.startsWith("https://",true)&&!raw.startsWith("http://",true))return null
        val decision=UrlPolicy.evaluateNavigation(raw,allowHttp=false,resolveDns=false)
        if(!decision.allowed)return SearchBatch(emptyList(),listOf(SourceSearchError("direct-url","Прямая ссылка",decision.errorCode,decision.reason)))
        val host=runCatching{URI(decision.normalizedUrl).host.orEmpty()}.getOrDefault("")
        val adapter=adapters.firstOrNull{a->a.allowedHosts.isEmpty()||UrlPolicy.isHostAllowed(host,a.allowedHosts)}
            ?:return SearchBatch(emptyList(),listOf(SourceSearchError("direct-url","Прямая ссылка","DIRECT_URL_SOURCE_NOT_FOUND","Для этого домена нет включённого SNP.")))
        val id=decision.normalizedUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank{decision.normalizedUrl.hashCode().toUInt().toString(16)}
        return SearchBatch(listOf(VideoItem(id="${adapter.id}|$id",remoteId=id,watchUrl=decision.normalizedUrl,title=id.replace('-',' ').replace('_',' '),sourceName=adapter.name,sourceBaseUrl=adapter.baseUrl,sourceEngine=adapter.engine,playerPageUrl=decision.normalizedUrl,favoriteKey="${adapter.id}|$id")),emptyList())
    }

    /** Existing favorite/history items may still resolve after the source is disabled for search. */
    fun adapterFor(video:VideoItem):AdapterDefinition? = allAdapters().firstOrNull { it.baseUrl.equals(video.sourceBaseUrl,true) || it.name.equals(video.sourceName,true) }

    private suspend fun searchOne(a:AdapterDefinition,q:String,page:Int,max:Int):Pair<List<VideoItem>,SourceSearchError?>{
        val engine=RuntimeEngineRegistry.find(a.engine) ?: return emptyList<VideoItem>() to SourceSearchError(a.id,a.name,"ENGINE_NOT_IMPLEMENTED","Runtime engine ${a.engine} отсутствует.")
        val req=EngineSearchRequest(baseUrl=a.baseUrl,query=q,page=page,maxItems=max,searchUrlTemplate=a.searchUrlTemplate,itemSelector=a.itemSelector,titleSelector=a.titleSelector,urlSelector=a.urlSelector,thumbnailSelector=a.thumbnailSelector,thumbnailAttribute=a.thumbnailAttribute,durationSelector=a.durationSelector,viewsSelector=a.viewsSelector,itemsPath=a.itemsPath,idPath=a.idPath,titlePath=a.titlePath,urlPath=a.urlPath,thumbnailPath=a.thumbnailPath,durationPath=a.durationPath,viewsPath=a.viewsPath)
        val candidates=searchCandidates(engine.buildSearchUrl(req),templateSearchUrl(req),a.baseUrl,q,page)
        if(candidates.isEmpty())return emptyList<VideoItem>() to SourceSearchError(a.id,a.name,"SEARCH_URL_EMPTY","Engine не сформировал search URL.")
        var lastError:SourceSearchError?=null
        var hadSuccessfulResponse=false
        for(url in candidates){
            val net=http.fetchText(url,NetworkPolicy(a.allowedHosts,allowCookies=a.allowCookies),a.id)
            if(!net.success){lastError=SourceSearchError(a.id,a.name,net.errorCode,net.errorMessage);continue}
            hadSuccessfulResponse=true
            val finalBase=originOf(net.finalUrl,a.baseUrl)
            val parseReq=req.copy(baseUrl=finalBase)
            val parsed=engine.parseSearch(net.text,parseReq)
            val items=parsed.mapNotNull { p ->
                val watch=normalizeAllowed(p.watchPath,a,baseUrl=finalBase) ?: return@mapNotNull null
                val thumb=normalizeAllowed(p.thumbnailUrl,a,allowBlank=true,baseUrl=finalBase).orEmpty()
                VideoItem(id="${a.id}|${p.remoteId.ifBlank{watch}}",remoteId=p.remoteId,watchUrl=watch,title=p.title.ifBlank{p.remoteId.ifBlank{"Без названия"}},thumbnailUrl=thumb,duration=p.durationText,views=p.viewsText,sourceName=a.name,sourceBaseUrl=a.baseUrl,sourceEngine=a.engine,playerPageUrl=watch,favoriteKey="${a.id}|${p.remoteId.ifBlank{watch}}")
            }.distinctBy{it.watchUrl.lowercase()}
            if(items.isNotEmpty())return items to null
        }
        if(hadSuccessfulResponse)return emptyList<VideoItem>() to SourceSearchError(a.id,a.name,"SEARCH_PARSED_ZERO","Источник ответил, но текущий SNP не распознал ни одной видеокарточки.")
        return emptyList<VideoItem>() to (lastError ?: SourceSearchError(a.id,a.name,"SEARCH_FAILED","Не удалось получить результаты поиска."))
    }

    private fun searchCandidates(primary:String,template:String,baseUrl:String,query:String,page:Int):List<String>{
        val b=baseUrl.trimEnd('/');val q=pro.noodle.mobile.core.engine.runtime.EngineParsing.encodedQuery(query);val out=linkedSetOf<String>()
        listOf(primary,template).filter{it.isNotBlank()}.forEach{out+=it}
        if(b.isNotBlank()&&q.isNotBlank()){
            if(page<0){out+="$b/search/?q=$q";out+="$b/search/$q/"}
            else{val p=(page+1).coerceAtLeast(2);out+="$b/search/$p/?q=$q";out+="$b/search/?q=$q&page=$p"}
        }
        return out.take(5)
    }

    private fun originOf(url:String,fallback:String):String=runCatching{val u=URI(url);if(u.scheme.isNullOrBlank()||u.host.isNullOrBlank())fallback else "${u.scheme}://${u.host}${if(u.port>=0)":${u.port}" else ""}"}.getOrDefault(fallback)

    private fun templateSearchUrl(r:EngineSearchRequest):String{if(r.searchUrlTemplate.isBlank())return "";return pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(r.searchUrlTemplate.replace("{base_url}",r.baseUrl,true).replace("{query}",pro.noodle.mobile.core.engine.runtime.EngineParsing.encodedQuery(r.query),true).replace("{page}",(r.page+1).coerceAtLeast(1).toString(),true),r.baseUrl)}
    private fun normalizeAllowed(raw:String,a:AdapterDefinition,allowBlank:Boolean=false,baseUrl:String=a.baseUrl):String?{if(raw.isBlank())return if(allowBlank)"" else null;val url=pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(raw,baseUrl);val d=UrlPolicy.evaluateNavigation(url,allowHttp=false,resolveDns=false);if(!d.allowed)return null;val host=runCatching{URI(d.normalizedUrl).host.orEmpty()}.getOrDefault("");if(a.allowedHosts.isNotEmpty()&&!UrlPolicy.isHostAllowed(host,a.allowedHosts))return null;return d.normalizedUrl}
}

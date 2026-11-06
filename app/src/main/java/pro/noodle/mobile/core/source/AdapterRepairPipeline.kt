package pro.noodle.mobile.core.source

import android.content.Context
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.adapter.AdapterSchemaParser
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.SearchUrls
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.version.SemanticVersion
import java.io.File


data class AdapterRepairResult(
    val attempted: Boolean = false,
    val success: Boolean = false,
    val adapterId: String = "",
    val oldVersion: String = "",
    val newVersion: String = "",
    val strategy: String = "",
    val snapshotPath: String = "",
    val errorCode: String = "",
    val message: String = "",
)

class AdapterRepairPipeline(
    private val context: Context,
    private val repo: RuntimeAdapterRepository = RuntimeAdapterRepository(context),
    private val http: SecureHttpClient = SecureHttpClient(),
) {
    private val recovery = AdapterRecoveryManager(repo.paths)
    private val probe = AdapterProbeService(context, repo, http)

    suspend fun tryRepair(adapterId: String): AdapterRepairResult {
        val runtime = repo.find(adapterId) ?: return AdapterRepairResult(errorCode="SNP_NOT_FOUND",message="SNP не найден.")
        val d = runtime.definition
        if (runtime.bundled || !repo.paths.isCustom(runtime.directory)) return AdapterRepairResult(adapterId=d.id,oldVersion=d.adapterVersion,errorCode="SNP_REPAIR_CUSTOM_ONLY",message="Автоматическое восстановление разрешено только для пользовательских SNP.")
        if (d.schemaVersion >= 3 && !d.repairEnabled) return AdapterRepairResult(adapterId=d.id,oldVersion=d.adapterVersion,errorCode="SNP_REPAIR_DISABLED",message="Repair policy отключена.")
        if (d.schemaVersion < 3 && !d.healthAutoRepair) return AdapterRepairResult(adapterId=d.id,oldVersion=d.adapterVersion,errorCode="SNP_REPAIR_DISABLED",message="Автовосстановление отключено.")

        var last = AdapterRepairResult(attempted=true,adapterId=d.id,oldVersion=d.adapterVersion,errorCode="SNP_REPAIR_NO_STRATEGY_SUCCEEDED",message="Ни одна разрешённая стратегия восстановления не прошла semantic validation.")
        if ((d.schemaVersion < 3 || d.repairStrategies.any { it.equals("selector_relearn",true) }) && d.engine.equals("declarative_catalog",true)) {
            last = selectorRelearn(runtime)
            if (last.success) return last
        }
        if (d.schemaVersion < 3 || d.repairStrategies.any { it.equals("resolver_refresh",true) }) {
            val r = resolverRefresh(runtime)
            if (r.success) return r
            last = r
        }
        return last
    }

    private suspend fun selectorRelearn(runtime: RuntimeAdapter): AdapterRepairResult {
        val d=runtime.definition
        val engine=RuntimeEngineRegistry.find("declarative_catalog") ?: return fail(d,"selector_relearn","SNP_REPAIR_ENGINE_MISSING","declarative_catalog runtime отсутствует.")
        val req=EngineSearchRequest(baseUrl=d.baseUrl,query=d.healthQuery.ifBlank{"video"},page=-1,maxItems=12,searchUrlTemplate=d.searchUrlTemplate)
        val url=SearchUrls.primary(engine,req)
        if(url.isBlank())return fail(d,"selector_relearn","SNP_REPAIR_SEARCH_URL_EMPTY","Не удалось построить URL контрольного поиска.")
        val net=http.fetchText(url,NetworkPolicy(d.allowedHosts,allowCookies=false,maxResponseBytes=10*1024*1024),"repair:${d.id}")
        if(!net.success)return fail(d,"selector_relearn","SNP_REPAIR_ANALYZE_FAILED","${net.errorCode}: ${net.errorMessage}")
        val item=guessRepeatedSelector(net.text)
        val title=guessTitleSelector(net.text)
        val thumbAttr=if(Regex("\\bdata-src\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(net.text))"data-src" else "src"
        return buildAndValidateCandidate(runtime,"selector_relearn") { map ->
            val search=(map["search"] as? JsonValue.Obj)?.let{LinkedHashMap(it.values)}?:linkedMapOf()
            search["item_selector"]=JsonValue.Str(item);search["title_selector"]=JsonValue.Str(title);search["url_selector"]=JsonValue.Str("a");search["thumbnail_selector"]=JsonValue.Str("img");search["thumbnail_attribute"]=JsonValue.Str(thumbAttr)
            map["search"]=JsonValue.Obj(search)
        }
    }

    private suspend fun resolverRefresh(runtime: RuntimeAdapter): AdapterRepairResult = buildAndValidateCandidate(runtime,"resolver_refresh") { map ->
        map["view"]=JsonValue.Obj(linkedMapOf("strategies" to JsonValue.Arr(listOf("primary","jwplayer_config","videojs_config","generic_html5","oembed_provider","iframe_chain","hls_manifest","dash_manifest").map{JsonValue.Str(it)})))
    }

    private suspend fun buildAndValidateCandidate(runtime: RuntimeAdapter, strategy:String, mutate:(LinkedHashMap<String,JsonValue>)->Unit):AdapterRepairResult{
        val d=runtime.definition
        val root=runCatching{MiniJson.parse(File(runtime.directory,"adapter.json").readText()) as? JsonValue.Obj}.getOrNull() ?: return fail(d,strategy,"SNP_REPAIR_JSON_INVALID","adapter.json не является JSON-объектом.")
        val map=LinkedHashMap(root.values)
        val next=nextRepairVersion(d.adapterVersion)
        map["adapter_version"]=JsonValue.Str(next)
        mutate(map)
        val text=JsonValue.Obj(map).toJson(pretty=true)
        val validation=AdapterValidator.validate(text)
        if(!validation.isValid||validation.definition==null)return fail(d,strategy,validation.errorCode,validation.errorMessage)
        // Security invariant: repair may never expand permissions.allowed_hosts.
        if(validation.definition.allowedHosts.map{it.lowercase()}.toSet()!=d.allowedHosts.map{it.lowercase()}.toSet())return fail(d,strategy,"SNP_REPAIR_HOST_EXPANSION","Repair candidate попытался изменить allowed_hosts.")
        val semantic=probe.validateDefinition(validation.definition)
        if(semantic.status!=SemanticStatus.HEALTHY)return fail(d,strategy,"SNP_REPAIR_CANDIDATE_FAILED","${semantic.errorCode}: ${semantic.message}")
        val candidate=File(repo.paths.repair,d.id.lowercase())
        candidate.deleteRecursively(); runtime.directory.copyRecursively(candidate,overwrite=true)
        File(candidate,"adapter.json").writeText(text,Charsets.UTF_8)
        AdapterPackageManager.createManifest(candidate,d.id,next,d.publisher)
        val pkg=AdapterPackageManager.verifyDirectory(candidate)
        if(!pkg.valid){candidate.deleteRecursively();return fail(d,strategy,pkg.errorCode,pkg.errorMessage)}
        val snapshot=recovery.createSnapshot(d.id,runtime.directory,d.adapterVersion)?.path.orEmpty()
        val old=File(repo.paths.updates,d.id.lowercase()+".repair-old");old.deleteRecursively()
        if(!runtime.directory.renameTo(old)){candidate.deleteRecursively();return fail(d,strategy,"SNP_REPAIR_SWAP_FAILED","Не удалось вывести текущий SNP из runtime.")}
        return try{
            if(!candidate.renameTo(runtime.directory))throw IllegalStateException("Не удалось активировать repair candidate")
            old.deleteRecursively()
            AdapterRepairResult(true,true,d.id,d.adapterVersion,next,strategy,snapshot,message="Repair успешно проверен: search=${semantic.searchResultCount}, playback=${semantic.playbackSourceCount}.")
        }catch(e:Exception){if(!runtime.directory.exists()&&old.exists())old.renameTo(runtime.directory);candidate.deleteRecursively();fail(d,strategy,"SNP_REPAIR_EXCEPTION","${e.javaClass.simpleName}: ${e.message}")}
    }

    private fun nextRepairVersion(current:String):String{val v=SemanticVersion.parseOrNull(current)?:return "0.0.1-repair.1";return "${v.major}.${v.minor}.${v.patch+1}-repair.1"}
    private fun guessRepeatedSelector(html:String):String{val counts=linkedMapOf<String,Int>();Regex("<(?:article|div|li)\\b[^>]*\\bclass\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",RegexOption.IGNORE_CASE).findAll(html).forEach{m->m.groupValues[1].split(Regex("\\s+")).filter{Regex("^[A-Za-z0-9_-]{2,64}$").matches(it)}.forEach{c->counts[c.lowercase()]=(counts[c.lowercase()]?:0)+1}};val best=counts.filterValues{it>2}.maxByOrNull{it.value}?.key;return if(best==null)"article" else ".$best"}
    private fun guessTitleSelector(html:String):String{val m=Regex("<(?:h2|h3|h4)\\b[^>]*\\bclass\\s*=\\s*[\"']([^\"']+)[\"']",RegexOption.IGNORE_CASE).find(html);val c=m?.groupValues?.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf{Regex("^[A-Za-z0-9_-]{2,64}$").matches(it)};return if(c==null)"a" else ".$c"}
    private fun fail(d:AdapterDefinition,strategy:String,code:String,message:String)=AdapterRepairResult(attempted=true,adapterId=d.id,oldVersion=d.adapterVersion,strategy=strategy,errorCode=code,message=message)
}

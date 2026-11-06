package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.engine.runtime.SearchUrls
import pro.noodle.mobile.core.engine.runtime.searchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.json.JsonValue
import java.net.URI
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max


enum class FingerprintStatus(val wireName: String) { BASELINE_CREATED("baseline_created"), UNCHANGED("unchanged"), MINOR_CHANGE("minor_change"), MAJOR_CHANGE("major_change"), CRITICAL_CHANGE("critical_change"), UNAVAILABLE("unavailable"), INVALID_ADAPTER("invalid_adapter") }

data class FingerprintResult(
    val adapterId: String,
    val status: FingerprintStatus,
    val previousHash: String = "",
    val currentHash: String = "",
    val changeScore: Double = 0.0,
    val tagCount: Int = 0,
    val linkCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val formCount: Int = 0,
    val scriptCount: Int = 0,
    val errorCode: String = "",
    val message: String = "",
)

private data class FingerprintMetrics(val hash:String,val tags:Int,val links:Int,val images:Int,val videos:Int,val forms:Int,val scripts:Int)

class SiteFingerprintMonitor(
    private val repo: RuntimeAdapterRepository,
    private val http: SecureHttpClient = SecureHttpClient(),
) {
    private val store = SourceStatusStore(FileRefs.fingerprint(repo.paths))

    suspend fun checkOne(adapterId: String): FingerprintResult {
        val runtime = repo.find(adapterId) ?: return FingerprintResult(adapterId, FingerprintStatus.INVALID_ADAPTER, errorCode="SNP_NOT_FOUND", message="SNP не найден.")
        val d = runtime.definition
        val engine = RuntimeEngineRegistry.find(d.engine) ?: return FingerprintResult(d.id, FingerprintStatus.INVALID_ADAPTER, errorCode="FINGERPRINT_ENGINE_UNKNOWN", message="Движок SNP не зарегистрирован.")
        val req = d.searchRequest(d.healthQuery.ifBlank{"video"}, page = -1, maxItems = 20)
        val url = SearchUrls.primary(engine, req).ifBlank { d.baseUrl }
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp=false, resolveDns=false)
        val host = runCatching { URI(decision.normalizedUrl).host.orEmpty() }.getOrDefault("")
        if (!decision.allowed || (d.allowedHosts.isNotEmpty() && !UrlPolicy.isHostAllowed(host, d.allowedHosts))) return FingerprintResult(d.id, FingerprintStatus.INVALID_ADAPTER, errorCode="FINGERPRINT_URL_RESTRICTED", message="Search endpoint не разрешён политикой SNP.")
        val net = http.fetchText(decision.normalizedUrl, NetworkPolicy(d.allowedHosts, allowCookies=false, maxResponseBytes=12*1024*1024), "fingerprint:${d.id}")
        if (!net.success) return FingerprintResult(d.id, FingerprintStatus.UNAVAILABLE, errorCode=net.errorCode.ifBlank{"FINGERPRINT_NETWORK_ERROR"}, message=net.errorMessage)
        val m = buildMetrics(net.text)
        val oldHash = store.get(d.id, "hash")
        if (oldHash.isBlank()) {
            save(d.id, m, "baseline_created")
            return from(d.id, FingerprintStatus.BASELINE_CREATED, "", m, 0.0, "Создан baseline структуры сайта.")
        }
        if (oldHash.equals(m.hash, true)) {
            save(d.id, m, "unchanged")
            return from(d.id, FingerprintStatus.UNCHANGED, oldHash, m, 0.0, "Структурный fingerprint не изменился.")
        }
        val score = (
            delta(store.getInt(d.id,"tag_count"),m.tags)*2 + delta(store.getInt(d.id,"link_count"),m.links) +
            delta(store.getInt(d.id,"image_count"),m.images) + delta(store.getInt(d.id,"video_count"),m.videos)*2 +
            delta(store.getInt(d.id,"form_count"),m.forms) + delta(store.getInt(d.id,"script_count"),m.scripts)
        ) / 8.0
        return when {
            score < 0.12 -> {
                save(d.id,m,"minor_change")
                from(d.id,FingerprintStatus.MINOR_CHANGE,oldHash,m,score,"Небольшое изменение DOM-структуры: ${"%.1f".format(score*100)}%. Baseline обновлён.")
            }
            score < 0.35 -> from(d.id,FingerprintStatus.MAJOR_CHANGE,oldHash,m,score,"Существенное изменение DOM-структуры: ${"%.1f".format(score*100)}%. Предыдущий baseline сохранён.")
            else -> from(d.id,FingerprintStatus.CRITICAL_CHANGE,oldHash,m,score,"Критическое изменение DOM-структуры: ${"%.1f".format(score*100)}%. Предыдущий baseline сохранён.")
        }.also { r -> store.put(d.id, mapOf("status" to JsonValue.Str(r.status.wireName), "last_score" to JsonValue.Num(r.changeScore))) }
    }

    private fun buildMetrics(html: String): FingerprintMetrics {
        var normalized = html.replace(Regex("<!--.*?-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        normalized = normalized.replace(Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "<script></script>")
        normalized = normalized.replace(Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "<style></style>")
        val tags = Regex("<([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>", RegexOption.IGNORE_CASE).findAll(normalized).toList()
        var links=0;var images=0;var videos=0;var forms=0;var scripts=0
        val signature=StringBuilder()
        tags.forEachIndexed { index, match ->
            val tag=match.groupValues[1].lowercase()
            when(tag){"a"->links++;"img"->images++;"video"->videos++;"form"->forms++;"script"->scripts++}
            if(index<1600){
                signature.append('<').append(tag)
                val classes=Regex("\\bclass\\s*=\\s*[\"']([^\"']+)[\"']",RegexOption.IGNORE_CASE).find(match.groupValues[2])?.groupValues?.getOrNull(1).orEmpty()
                classes.split(Regex("\\s+")).filter{Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it)}.forEach{signature.append('.').append(it.lowercase())}
                signature.append('>')
            }
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(signature.toString().toByteArray()).joinToString(""){"%02x".format(it)}
        return FingerprintMetrics(hash,tags.size,links,images,videos,forms,scripts)
    }

    private fun delta(a:Int,b:Int):Double=abs(a-b).toDouble()/max(max(a,b),1).toDouble()
    private fun save(id:String,m:FingerprintMetrics,status:String){store.put(id,mapOf("status" to JsonValue.Str(status),"hash" to JsonValue.Str(m.hash),"tag_count" to JsonValue.Num(m.tags.toDouble()),"link_count" to JsonValue.Num(m.links.toDouble()),"image_count" to JsonValue.Num(m.images.toDouble()),"video_count" to JsonValue.Num(m.videos.toDouble()),"form_count" to JsonValue.Num(m.forms.toDouble()),"script_count" to JsonValue.Num(m.scripts.toDouble())))}
    private fun from(id:String,status:FingerprintStatus,old:String,m:FingerprintMetrics,score:Double,message:String)=FingerprintResult(id,status,old,m.hash,score,m.tags,m.links,m.images,m.videos,m.forms,m.scripts,message=message)
}

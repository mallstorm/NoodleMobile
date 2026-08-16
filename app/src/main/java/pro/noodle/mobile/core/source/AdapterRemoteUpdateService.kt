package pro.noodle.mobile.core.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.version.SemanticVersion
import java.io.File
import java.net.URI


enum class AdapterUpdateState(val wireName:String){NOT_CONFIGURED("not_configured"),BUNDLED_MANAGED("bundled_managed"),UP_TO_DATE("up_to_date"),UPDATE_AVAILABLE("update_available"),RESTRICTED("restricted"),ERROR("error")}

data class AdapterUpdateCheck(
    val adapterId:String,
    val currentVersion:String,
    val latestVersion:String="",
    val feedUrl:String="",
    val packageUrl:String="",
    val packageSha256:String="",
    val state:AdapterUpdateState=AdapterUpdateState.ERROR,
    val errorCode:String="",
    val message:String="",
)

class AdapterRemoteUpdateService(
    context:Context,
    private val repo:RuntimeAdapterRepository=RuntimeAdapterRepository(context),
    private val http:SecureHttpClient=SecureHttpClient(),
){
    private val recovery=AdapterRecoveryManager(repo.paths)
    private val updater=AdapterUpdateManager(repo.paths,recovery)
    private val overrides=SourceOverridesRepository(context.applicationContext)
    private val store=SourceStatusStore(FileRefs.updates(repo.paths))
    private val cacheDir=File(context.cacheDir,"snp-updates").apply{mkdirs()}

    suspend fun check(adapterId:String):AdapterUpdateCheck{
        val runtime=repo.find(adapterId)?:return AdapterUpdateCheck(adapterId,"",state=AdapterUpdateState.ERROR,errorCode="SNP_NOT_FOUND",message="SNP не найден.")
        val d=runtime.definition
        if(runtime.bundled)return save(AdapterUpdateCheck(d.id,d.adapterVersion,state=AdapterUpdateState.BUNDLED_MANAGED,message="Штатный SNP обновляется вместе с приложением."))
        if(d.updateFeedUrl.isBlank())return save(AdapterUpdateCheck(d.id,d.adapterVersion,state=AdapterUpdateState.NOT_CONFIGURED,message="update.feed_url не настроен."))
        val decision=UrlPolicy.evaluateNavigation(d.updateFeedUrl,allowHttp=false,resolveDns=false)
        if(!decision.allowed)return save(AdapterUpdateCheck(d.id,d.adapterVersion,feedUrl=d.updateFeedUrl,state=AdapterUpdateState.RESTRICTED,errorCode=decision.errorCode,message=decision.reason))
        val host=runCatching{URI(decision.normalizedUrl).host.orEmpty()}.getOrDefault("")
        if(d.allowedHosts.isNotEmpty()&&!UrlPolicy.isHostAllowed(host,d.allowedHosts))return save(AdapterUpdateCheck(d.id,d.adapterVersion,feedUrl=decision.normalizedUrl,state=AdapterUpdateState.RESTRICTED,errorCode="SNP_UPDATE_FEED_HOST_NOT_ALLOWED",message="Update feed host не объявлен в allowed_hosts."))
        val net=http.fetchText(decision.normalizedUrl,NetworkPolicy(d.allowedHosts,allowCookies=false,maxResponseBytes=512*1024),"update:${d.id}")
        if(!net.success)return save(AdapterUpdateCheck(d.id,d.adapterVersion,feedUrl=decision.normalizedUrl,state=AdapterUpdateState.ERROR,errorCode=net.errorCode,message=net.errorMessage))
        val root=runCatching{MiniJson.parse(net.text) as? JsonValue.Obj}.getOrNull()?:return save(AdapterUpdateCheck(d.id,d.adapterVersion,feedUrl=decision.normalizedUrl,state=AdapterUpdateState.ERROR,errorCode="SNP_UPDATE_FEED_INVALID",message="Update feed должен быть JSON-объектом."))
        if(!root.string("adapter_id").orEmpty().equals(d.id,true))return save(AdapterUpdateCheck(d.id,d.adapterVersion,feedUrl=decision.normalizedUrl,state=AdapterUpdateState.ERROR,errorCode="SNP_UPDATE_ID_MISMATCH",message="adapter_id update feed не совпадает."))
        val latest=root.string("latest_version").orEmpty();val packageUrl=root.string("package_url").orEmpty();val sha=root.string("sha256").orEmpty()
        if(packageUrl.isBlank())return save(AdapterUpdateCheck(d.id,d.adapterVersion,latest,decision.normalizedUrl,state=AdapterUpdateState.ERROR,errorCode="SNP_UPDATE_PACKAGE_URL_MISSING",message="Update feed не содержит package_url."))
        val pkgDecision=UrlPolicy.evaluateNavigation(packageUrl,allowHttp=false,resolveDns=false)
        if(!pkgDecision.allowed)return save(AdapterUpdateCheck(d.id,d.adapterVersion,latest,decision.normalizedUrl,packageUrl,state=AdapterUpdateState.RESTRICTED,errorCode="SNP_UPDATE_PACKAGE_URL_UNSAFE",message="${pkgDecision.errorCode}: ${pkgDecision.reason}"))
        val pkgHost=runCatching{URI(pkgDecision.normalizedUrl).host.orEmpty()}.getOrDefault("")
        if(d.allowedHosts.isNotEmpty()&&!UrlPolicy.isHostAllowed(pkgHost,d.allowedHosts))return save(AdapterUpdateCheck(d.id,d.adapterVersion,latest,decision.normalizedUrl,pkgDecision.normalizedUrl,state=AdapterUpdateState.RESTRICTED,errorCode="SNP_UPDATE_PACKAGE_HOST_NOT_ALLOWED",message="Package host не объявлен в allowed_hosts."))
        if(sha.isNotBlank()&&!Regex("^[0-9a-fA-F]{64}$").matches(sha))return save(AdapterUpdateCheck(d.id,d.adapterVersion,latest,decision.normalizedUrl,pkgDecision.normalizedUrl,sha,state=AdapterUpdateState.ERROR,errorCode="SNP_UPDATE_SHA256_INVALID",message="sha256 должен содержать 64 hex-символа."))
        val currentV=SemanticVersion.parseOrNull(d.adapterVersion);val latestV=SemanticVersion.parseOrNull(latest)
        if(currentV==null||latestV==null)return save(AdapterUpdateCheck(d.id,d.adapterVersion,latest,decision.normalizedUrl,pkgDecision.normalizedUrl,sha,state=AdapterUpdateState.ERROR,errorCode="SNP_UPDATE_VERSION_INVALID",message="Некорректная SemVer в адаптере или update feed."))
        return save(AdapterUpdateCheck(d.id,d.adapterVersion,latest,decision.normalizedUrl,pkgDecision.normalizedUrl,sha,state=if(latestV>currentV)AdapterUpdateState.UPDATE_AVAILABLE else AdapterUpdateState.UP_TO_DATE,message=if(latestV>currentV)"Доступна версия $latest" else "Установлена актуальная версия."))
    }

    suspend fun downloadAndInstall(adapterId:String):AdapterInstallResult=withContext(Dispatchers.IO){
        val check=check(adapterId)
        if(check.state!=AdapterUpdateState.UPDATE_AVAILABLE)return@withContext AdapterInstallResult(false,errorCode="SNP_UPDATE_NOT_AVAILABLE",message=check.message)
        val runtime=repo.find(adapterId)?:return@withContext AdapterInstallResult(false,errorCode="SNP_NOT_FOUND",message="SNP не найден.")
        val temp=File(cacheDir,"${adapterId.lowercase()}-${System.currentTimeMillis()}.zip")
        try{
            val policy=NetworkPolicy(runtime.definition.allowedHosts,allowCookies=false,maxResponseBytes=AdapterZipManager.MAX_ZIP_BYTES.toInt())
            val client=http.streamingClient(policy,"update-package:$adapterId")
            val response=client.newCall(Request.Builder().url(check.packageUrl).header("User-Agent","NoodleMobile-SNPUpdate/1.0").build()).execute()
            response.use{r->
                if(!r.isSuccessful)return@withContext AdapterInstallResult(false,errorCode="SNP_UPDATE_PACKAGE_HTTP_ERROR",message="HTTP ${r.code}")
                val body=r.body?:return@withContext AdapterInstallResult(false,errorCode="SNP_UPDATE_PACKAGE_EMPTY",message="Пустой package body.")
                if(body.contentLength()>AdapterZipManager.MAX_ZIP_BYTES)return@withContext AdapterInstallResult(false,errorCode="SNP_UPDATE_PACKAGE_TOO_LARGE",message="ZIP превышает 16 MiB.")
                body.byteStream().use{input->temp.outputStream().use{out->val buf=ByteArray(64*1024);var total=0L;while(true){val n=input.read(buf);if(n<=0)break;total+=n;if(total>AdapterZipManager.MAX_ZIP_BYTES)return@withContext AdapterInstallResult(false,errorCode="SNP_UPDATE_PACKAGE_TOO_LARGE",message="ZIP превышает 16 MiB.");out.write(buf,0,n)}}}
            }
            val disabled=overrides.disabledSourceIds.first();val wasEnabled=runtime.definition.enabled&&runtime.definition.id.lowercase() !in disabled
            updater.installPackageZip(adapterId,runtime.directory,temp,check.packageSha256,wasEnabled)
        }catch(e:Exception){AdapterInstallResult(false,errorCode="SNP_UPDATE_DOWNLOAD_ERROR",message="${e.javaClass.simpleName}: ${e.message}")}
        finally{temp.delete()}
    }

    private fun save(c:AdapterUpdateCheck):AdapterUpdateCheck{store.put(c.adapterId,mapOf("state" to JsonValue.Str(c.state.wireName),"latest_version" to JsonValue.Str(c.latestVersion),"error_code" to JsonValue.Str(c.errorCode),"message" to JsonValue.Str(c.message)));return c}
}

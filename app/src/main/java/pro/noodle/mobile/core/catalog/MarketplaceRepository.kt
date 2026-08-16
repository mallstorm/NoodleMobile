package pro.noodle.mobile.core.catalog

import android.content.Context
import kotlinx.coroutines.flow.first
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.*
import pro.noodle.mobile.core.version.SemanticVersion
import java.io.File
import java.net.URI


data class MarketplaceRefreshResult(val success:Boolean,val catalog:VerifiedCatalog?=null,val errorCode:String="",val message:String="")
data class MarketplaceInstallResult(val success:Boolean,val status:String="",val errorCode:String="",val message:String="")

class MarketplaceRepository(context:Context) {
    private val app=context.applicationContext
    private val http=SecureHttpClient()
    private val keys=TrustedKeyManager(app)
    private val settings=CatalogSettingsRepository(app)
    private val runtime=RuntimeAdapterRepository(app)
    private val overrides=SourceOverridesRepository(app)
    private val recovery=AdapterRecoveryManager(runtime.paths)
    private val updater=AdapterUpdateManager(runtime.paths,recovery)

    suspend fun refresh(urlOverride:String=""):MarketplaceRefreshResult {
        val configured=urlOverride.trim().ifBlank { settings.settings.first().indexUrl.trim() }
        val uri=runCatching{URI(configured)}.getOrNull() ?: return failRefresh("CATALOG_URL_INVALID","Укажите корректный HTTPS URL каталога.")
        if(!uri.scheme.equals("https",true)||uri.host.isNullOrBlank()) return failRefresh("CATALOG_URL_HTTPS_REQUIRED","Каталог разрешён только по HTTPS.")
        val policy=NetworkPolicy(listOf(uri.host.lowercase()),maxResponseBytes=2*1024*1024,maxRedirects=5)
        val fetch=http.fetchText(configured,policy,"@source-catalog")
        if(!fetch.success)return failRefresh(fetch.errorCode,fetch.errorMessage)
        val loaded=CatalogCodec.loadEnvelope(fetch.text,keys.asMap())
        if(!loaded.success)return failRefresh(loaded.errorCode,loaded.message)
        return MarketplaceRefreshResult(true,loaded.catalog,message="Проверено RSA-подписью ключа ${loaded.catalog?.keyId}.")
    }

    suspend fun install(entry:CatalogEntry):MarketplaceInstallResult {
        if(!entry.compatible)return failInstall("CATALOG_ENTRY_INCOMPATIBLE","SNP несовместим с текущим уровнем совместимости ${AdapterCompatibility.CURRENT_APP_VERSION}.")
        val packageUri=runCatching{URI(entry.packageUrl)}.getOrNull() ?: return failInstall("CATALOG_PACKAGE_URL_INVALID","Некорректный package_url.")
        if(!packageUri.scheme.equals("https",true)||packageUri.host.isNullOrBlank())return failInstall("CATALOG_PACKAGE_HTTPS_REQUIRED","package_url должен быть HTTPS.")
        val fetch=http.fetchBytes(entry.packageUrl,NetworkPolicy(listOf(packageUri.host.lowercase()),maxResponseBytes=AdapterZipManager.MAX_ZIP_BYTES.toInt(),maxRedirects=5),"@catalog-package:${entry.adapterId}")
        if(!fetch.success)return failInstall(fetch.errorCode,fetch.errorMessage)
        val temp=File(app.cacheDir,"catalog-${entry.adapterId}-${System.currentTimeMillis()}.zip")
        return try {
            temp.writeBytes(fetch.bytes)
            val hash=AdapterPackageManager.sha256(temp)
            if(!hash.equals(entry.sha256,true))return failInstall("CATALOG_PACKAGE_HASH_MISMATCH","SHA-256 скачанного ZIP не совпадает с подписанным catalog entry.")
            val meta=inspectPackage(temp)
            if(!meta.success||meta.definition==null)return failInstall(meta.errorCode,meta.message)
            val d=meta.definition
            if(!d.id.equals(entry.adapterId,true))return failInstall("CATALOG_PACKAGE_ID_MISMATCH","adapter.json не совпадает с подписанным adapter_id.")
            if(!d.adapterVersion.equals(entry.version,true))return failInstall("CATALOG_PACKAGE_VERSION_MISMATCH","adapter_version не совпадает с подписанной версией.")
            if(!d.publisher.equals(entry.publisher,true))return failInstall("CATALOG_PACKAGE_PUBLISHER_MISMATCH","publisher пакета не совпадает с подписанным catalog entry.")
            val existing=runtime.find(entry.adapterId)
            if(existing==null){
                val installed=runtime.importCustomZip(temp)
                if(!installed.success)return failInstall(installed.errorCode,installed.message)
                overrides.setEnabled(entry.adapterId,false)
                MarketplaceInstallResult(true,"installed_disabled",message="SNP установлен выключенным. Выполните Semantic health перед включением.")
            } else {
                if(existing.bundled)return failInstall("CATALOG_UPDATE_BUNDLED_MANAGED","Штатные SNP обновляются вместе с приложением.")
                if(existing.definition.publisher.isNotBlank()&&!existing.definition.publisher.equals(entry.publisher,true))return failInstall("CATALOG_PUBLISHER_TAKEOVER_BLOCKED","Подписанный publisher не совпадает с установленным SNP.")
                val old=SemanticVersion.parseOrNull(existing.definition.adapterVersion) ?: return failInstall("CATALOG_CURRENT_VERSION_INVALID","Некорректная версия установленного SNP.")
                val next=SemanticVersion.parseOrNull(entry.version) ?: return failInstall("CATALOG_VERSION_INVALID","Некорректная catalog version.")
                if(next<=old)return failInstall("CATALOG_UPDATE_NOT_NEWER","Установленная версия не ниже catalog version.")
                val disabled=overrides.disabledSourceIds.first()
                val wasEnabled=existing.definition.enabled && existing.definition.id.lowercase() !in disabled
                val installed=updater.installPackageZip(entry.adapterId,existing.directory,temp,entry.sha256,wasEnabled)
                if(!installed.success)return failInstall(installed.errorCode,installed.message)
                MarketplaceInstallResult(true,"pending_validation",message="Обновление ${installed.installedVersion} установлено; требуется Semantic health. При неудаче выполнится rollback.")
            }
        } finally { temp.delete() }
    }

    suspend fun saveIndexUrl(url:String){settings.setIndexUrl(url)}
    fun trustedKeys():TrustedKeyManager=keys

    private data class Inspection(val success:Boolean,val definition:pro.noodle.mobile.core.adapter.AdapterDefinition?=null,val errorCode:String="",val message:String="")
    private fun inspectPackage(zip:File):Inspection{
        val stage=File(app.cacheDir,"catalog-inspect-${System.currentTimeMillis()}")
        val ext=AdapterZipManager.extractPackage(zip,stage)
        if(!ext.success||ext.root==null){stage.deleteRecursively();return Inspection(false,errorCode=ext.errorCode,message=ext.errorMessage)}
        return try{
            val root=ext.root
            if(!File(root,"snp-package.json").isFile)return Inspection(false,errorCode="CATALOG_PACKAGE_MANIFEST_REQUIRED",message="Marketplace package обязан содержать snp-package.json.")
            val pkg=AdapterPackageManager.verifyDirectory(root)
            if(!pkg.valid)return Inspection(false,errorCode=pkg.errorCode,message=pkg.errorMessage)
            val v=AdapterValidator.validate(File(root,"adapter.json").readText(Charsets.UTF_8))
            if(!v.isValid||v.definition==null)return Inspection(false,errorCode=v.errorCode,message=v.errorMessage)
            val c=AdapterCompatibility.evaluate(v.definition.minAppVersion,v.definition.maxAppVersion)
            if(!c.compatible)return Inspection(false,errorCode=c.errorCode,message=c.message)
            Inspection(true,v.definition)
        }finally{stage.deleteRecursively()}
    }
    private fun failRefresh(code:String,msg:String)=MarketplaceRefreshResult(false,errorCode=code,message=msg)
    private fun failInstall(code:String,msg:String)=MarketplaceInstallResult(false,"error",code,msg)
}

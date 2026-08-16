package pro.noodle.mobile.core.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.*
import java.io.File
import java.net.URI


data class StudioAnalyzeResult(val success:Boolean,val draft:AdapterStudioDraft?=null,val json:String="",val errorCode:String="",val message:String="")
data class StudioSaveResult(val success:Boolean,val adapterId:String="",val errorCode:String="",val message:String="")

class AdapterStudioService(context:Context){
    private val app=context.applicationContext
    private val repo=RuntimeAdapterRepository(app)
    private val overrides=SourceOverridesRepository(app)
    private val http=SecureHttpClient()

    suspend fun analyze(url:String):StudioAnalyzeResult{
        val uri=runCatching{URI(url.trim())}.getOrNull()?:return StudioAnalyzeResult(false,errorCode="STUDIO_URL_INVALID",message="Некорректный URL.")
        if(!uri.scheme.equals("https",true)||uri.host.isNullOrBlank())return StudioAnalyzeResult(false,errorCode="STUDIO_HTTPS_REQUIRED",message="Adapter Studio анализирует только HTTPS URL.")
        val fetch=http.fetchText(url.trim(),NetworkPolicy(listOf(uri.host.lowercase()),maxResponseBytes=10*1024*1024,maxRedirects=5),"@adapter-studio")
        if(!fetch.success)return StudioAnalyzeResult(false,errorCode=fetch.errorCode,message=fetch.errorMessage)
        val draft=AdapterStudioCore.analyze(fetch.finalUrl,fetch.text).getOrElse{return StudioAnalyzeResult(false,errorCode="STUDIO_ANALYZE_FAILED",message=it.message.orEmpty())}
        val json=AdapterStudioCore.toAdapterJson(draft)
        val v=AdapterValidator.validate(json)
        if(!v.isValid)return StudioAnalyzeResult(false,errorCode=v.errorCode,message=v.errorMessage)
        return StudioAnalyzeResult(true,draft,json,message=draft.notes.joinToString(" • "))
    }

    suspend fun saveJson(json:String):StudioSaveResult=withContext(Dispatchers.IO){
        val v=AdapterValidator.validate(json)
        if(!v.isValid||v.definition==null)return@withContext StudioSaveResult(false,errorCode=v.errorCode,message=v.errorMessage)
        val d=v.definition
        if(d.schemaVersion<3)return@withContext StudioSaveResult(false,errorCode="STUDIO_SCHEMA_V3_REQUIRED",message="Adapter Studio сохраняет только Schema v3.")
        val compatibility=AdapterCompatibility.evaluate(d.minAppVersion,d.maxAppVersion)
        if(!compatibility.compatible)return@withContext StudioSaveResult(false,errorCode=compatibility.errorCode,message=compatibility.message)
        val bundled=repo.listAll().firstOrNull{it.bundled&&it.definition.id.equals(d.id,true)}
        if(bundled!=null)return@withContext StudioSaveResult(false,errorCode="STUDIO_BUNDLED_COLLISION",message="Adapter Studio не может заменить штатный SNP.")
        val target=File(repo.paths.custom,d.id.lowercase())
        val recovery=AdapterRecoveryManager(repo.paths)
        var backup:File?=null
        if(target.isDirectory){
            val cur=repo.find(d.id)
            if(cur!=null&&!cur.bundled)backup=recovery.createSnapshot(d.id,target,cur.definition.adapterVersion)
            if(backup==null)return@withContext StudioSaveResult(false,errorCode="STUDIO_SNAPSHOT_FAILED",message="Не удалось создать snapshot существующего custom SNP.")
        }
        val ready=File(repo.paths.updates,"${d.id.lowercase()}.studio-ready"); ready.deleteRecursively(); ready.mkdirs()
        try{
            File(ready,"adapter.json").writeText(json,Charsets.UTF_8)
            AdapterPackageManager.createManifest(ready,d.id,d.adapterVersion,d.publisher)
            val pkg=AdapterPackageManager.verifyDirectory(ready)
            if(!pkg.valid)return@withContext StudioSaveResult(false,errorCode=pkg.errorCode,message=pkg.errorMessage)
            val old=File(repo.paths.updates,"${d.id.lowercase()}.studio-old");old.deleteRecursively()
            if(target.exists()&&!target.renameTo(old))return@withContext StudioSaveResult(false,errorCode="STUDIO_SWAP_FAILED",message="Не удалось подготовить замену custom SNP.")
            if(!ready.renameTo(target)){ if(!target.exists()&&old.exists())old.renameTo(target);return@withContext StudioSaveResult(false,errorCode="STUDIO_SWAP_FAILED",message="Не удалось активировать SNP.") }
            try {
                overrides.setEnabled(d.id,false)
            } catch (e:Exception) {
                target.deleteRecursively()
                if(old.exists())old.renameTo(target)
                return@withContext StudioSaveResult(false,errorCode="STUDIO_OVERRIDE_FAILED",message="Не удалось зафиксировать disabled override: ${e.message}")
            }
            old.deleteRecursively()
            StudioSaveResult(true,d.id,message="Custom SNP сохранён выключенным. Выполните Semantic health перед включением.")
        }catch(e:Exception){
            if(!target.exists()&&backup!=null)runCatching{recovery.restoreSnapshot(d.id,backup!!.name,target)}
            StudioSaveResult(false,errorCode="STUDIO_SAVE_EXCEPTION",message="${e.javaClass.simpleName}: ${e.message}")
        }finally{ready.deleteRecursively()}
    }
}

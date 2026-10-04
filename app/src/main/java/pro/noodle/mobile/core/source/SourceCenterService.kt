package pro.noodle.mobile.core.source

import android.content.Context
import kotlinx.coroutines.flow.first
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import java.io.File


data class SourceCenterItem(
    val adapterId:String,
    val name:String,
    val baseUrl:String,
    val engine:String,
    val schemaVersion:Int,
    val version:String,
    val publisher:String,
    val compatible:Boolean,
    val compatibilityStatus:String,
    val enabled:Boolean,
    val defaultEnabled:Boolean,
    val bundled:Boolean,
    val trust:String,
    val packageError:String,
    val historyCount:Int,
    val pendingValidation:Boolean,
    val healthStatus:String,
    val semanticStatus:String,
    val updateStatus:String,
    val latestVersion:String,
    val fingerprintStatus:String,
    val breakageStatus:String,
)


data class SourceDetails(
    val adapterId:String, val name:String, val version:String, val publisher:String, val homepage:String,
    val baseUrl:String, val engine:String, val engineDependencies:List<String>, val capabilities:List<String>,
    val allowedHosts:List<String>, val allowJavaScript:Boolean, val allowCookies:Boolean, val viewStrategies:List<String>,
    val minAppVersion:String, val maxAppVersion:String, val compatibilityStatus:String, val bundled:Boolean, val trust:String,
    val healthQuery:String, val healthMinResults:Int, val healthRequirePlayback:Boolean, val healthAutoRepair:Boolean,
    val repairEnabled:Boolean, val repairMode:String, val repairStrategies:List<String>, val repairFailureThreshold:Int,
    val repairCooldownMinutes:Int, val repairAutoQuarantine:Boolean, val updateFeedUrl:String, val snapshots:List<AdapterSnapshotInfo>,
)

data class SourceActionResult(val success:Boolean,val status:String="",val message:String="",val errorCode:String="")

class SourceCenterService(context:Context){
    private val app=context.applicationContext
    private val repo=RuntimeAdapterRepository(app)
    private val paths=repo.paths
    private val overrides=SourceOverridesRepository(app)
    private val recovery=AdapterRecoveryManager(paths)
    private val migration=AdapterMigration(paths,recovery)
    private val probe=AdapterProbeService(app,repo)
    private val fingerprint=SiteFingerprintMonitor(repo)
    private val repair=AdapterRepairPipeline(app,repo)
    private val breakage=AdapterBreakageMonitor(app,repo,probe,repair)
    private val health=SourceHealthMonitor(repo)
    private val updates=AdapterRemoteUpdateService(app,repo)
    private val updateManager=AdapterUpdateManager(paths,recovery)
    private val healthStore=SourceStatusStore(FileRefs.health(paths));private val semanticStore=SourceStatusStore(FileRefs.semantic(paths));private val updateStore=SourceStatusStore(FileRefs.updates(paths));private val fingerprintStore=SourceStatusStore(FileRefs.fingerprint(paths));private val breakageStore=SourceStatusStore(FileRefs.breakage(paths))

    fun details(id:String):SourceDetails? {
        val r=repo.find(id)?:return null; val d=r.definition
        return SourceDetails(d.id,d.name,d.adapterVersion,d.publisher,d.homepage,d.baseUrl,d.engine,d.engineDependencies,d.capabilities,d.allowedHosts,d.allowJavaScript,d.allowCookies,d.viewStrategies,d.minAppVersion,d.maxAppVersion,r.compatibility.status,r.bundled,r.packageInfo.trustStatus.wireName,d.healthQuery,d.healthMinResults,d.healthRequirePlayback,d.healthAutoRepair,d.repairEnabled,d.repairMode,d.repairStrategies,d.repairFailureThreshold,d.repairCooldownMinutes,d.repairAutoQuarantine,d.updateFeedUrl,recovery.enumerate(d.id))
    }

    suspend fun enumerate():List<SourceCenterItem>{
        val disabled=overrides.disabledSourceIds.first()
        val forced=overrides.forceEnabledSourceIds.first()
        return repo.listAll().map{r->
            val d=r.definition
            val id=d.id.lowercase()
            val effectiveEnabled=r.compatibility.compatible&&r.packageInfo.valid&&(d.enabled||id in forced)&&id !in disabled
            SourceCenterItem(d.id,d.name,d.baseUrl,d.engine,d.schemaVersion,d.adapterVersion,d.publisher,r.compatibility.compatible,r.compatibility.status,effectiveEnabled,d.enabled,r.bundled,r.packageInfo.trustStatus.wireName,if(r.packageInfo.valid)"" else r.packageInfo.errorCode,recovery.enumerate(d.id).size,updateManager.pendingFile(d.id).isFile,healthStore.get(d.id,"status"),semanticStore.get(d.id,"status"),updateStore.get(d.id,"state"),updateStore.get(d.id,"latest_version"),fingerprintStore.get(d.id,"status"),breakageStore.get(d.id,"status"))
        }
    }

    suspend fun setEnabled(id:String,enabled:Boolean):SourceActionResult{
        val r=repo.find(id)?:return fail("SNP_NOT_FOUND","SNP не найден.")
        if(enabled&&!r.compatibility.compatible)return fail(r.compatibility.errorCode,r.compatibility.message)
        if(enabled&&!r.packageInfo.valid)return fail(r.packageInfo.errorCode,r.packageInfo.errorMessage)
        overrides.setEnabled(id,enabled)
        if(enabled){
            val studioCustom=!r.bundled&&r.definition.publisher.equals("local-user",true)
            if(studioCustom){
                overrides.setSoloSource(id)
                return ok("enabled_solo","Источник включён в SOLO-режиме: общий поиск использует только этот SNP. Отключите его или включите другой обычный источник, чтобы выйти из SOLO.")
            }
            overrides.setSoloSource(null)
        }
        return ok(if(enabled)"enabled" else "disabled",if(enabled)"Источник включён." else "Источник отключён.")
    }
    suspend fun importZip(zip:File):SourceActionResult{val r=repo.importCustomZip(zip);if(!r.success)return fail(r.errorCode,r.message);if(r.adapterId.isNotBlank()) overrides.setEnabled(r.adapterId,false);return ok("installed",r.message+" Источник оставлен выключенным до ручной проверки.")}
    suspend fun removeCustom(id:String):SourceActionResult{val r=repo.find(id)?:return fail("SNP_NOT_FOUND","SNP не найден.");if(r.bundled)return fail("SNP_REMOVE_BUNDLED","Штатный SNP нельзя удалить.");val success=repo.removeCustom(id);if(success){overrides.setSoloSource(null);overrides.setEnabled(id,true);return ok("removed","Пользовательский SNP удалён.")};return fail("SNP_REMOVE_FAILED","Не удалось удалить SNP.")}
    suspend fun semantic(id:String):SourceActionResult{val r=probe.semantic(id);return SourceActionResult(r.status==SemanticStatus.HEALTHY,r.status.wireName,r.message,r.errorCode)}
    suspend fun health(id:String):SourceActionResult{val r=health.checkOne(id);return SourceActionResult(r.status=="healthy",r.status,r.message,r.errorCode)}
    suspend fun fingerprint(id:String):SourceActionResult{val r=fingerprint.checkOne(id);return SourceActionResult(r.status in setOf(FingerprintStatus.BASELINE_CREATED,FingerprintStatus.UNCHANGED,FingerprintStatus.MINOR_CHANGE),r.status.wireName,r.message,r.errorCode)}
    suspend fun breakage(id:String):SourceActionResult{val r=breakage.checkOne(id);return SourceActionResult(r.status in setOf(BreakageStatus.HEALTHY,BreakageStatus.REPAIR_SUCCEEDED),r.status.wireName,r.message,r.errorCode)}
    suspend fun repair(id:String):SourceActionResult{val r=repair.tryRepair(id);return SourceActionResult(r.success,if(r.success)"repair_succeeded" else "repair_failed",if(r.success)"${r.strategy}: ${r.message}" else r.message,r.errorCode)}
    suspend fun migrate(id:String):SourceActionResult{val r=repo.find(id)?:return fail("SNP_NOT_FOUND","SNP не найден.");val m=migration.migrateCustomToV3(r.directory,true);return SourceActionResult(m.success,if(m.changed)"migrated" else "unchanged",m.message,m.errorCode)}
    suspend fun checkUpdate(id:String):SourceActionResult{val c=updates.check(id);return SourceActionResult(c.state in setOf(AdapterUpdateState.UP_TO_DATE,AdapterUpdateState.UPDATE_AVAILABLE,AdapterUpdateState.BUNDLED_MANAGED),c.state.wireName,c.message,c.errorCode)}
    suspend fun applyUpdate(id:String):SourceActionResult{val r=updates.downloadAndInstall(id);return SourceActionResult(r.success,if(r.success)"pending_validation" else "update_failed",r.message,r.errorCode)}
    suspend fun rollback(id:String):SourceActionResult{val runtime=repo.find(id)?:return fail("SNP_NOT_FOUND","SNP не найден.");val pending=updateManager.readPending(id);val restored=if(pending!=null)updateManager.completePendingValidation(id,false,runtime.directory) else recovery.restoreLatest(id,runtime.directory);return if(restored!=null)ok("restored",restored) else fail("SNP_ROLLBACK_UNAVAILABLE","История отката отсутствует.")}
    fun history(id:String):List<AdapterSnapshotInfo> = recovery.enumerate(id)
    suspend fun restore(id:String,snapshotId:String):SourceActionResult{val runtime=repo.find(id)?:return fail("SNP_NOT_FOUND","SNP не найден.");val r=recovery.restoreSnapshot(id,snapshotId,runtime.directory);return if(r!=null)ok("restored","Восстановлен snapshot: $snapshotId") else fail("SNP_RESTORE_FAILED","Snapshot не найден или отклонён политикой пути.")}
    suspend fun exportToFile(id:String,file:File):SourceActionResult=if(repo.exportAdapter(id,file))ok("exported",file.name) else fail("SNP_EXPORT_FAILED","Не удалось экспортировать SNP.")
    fun runtimeRepository():RuntimeAdapterRepository=repo
    private fun ok(status:String,message:String)=SourceActionResult(true,status,message)
    private fun fail(code:String,message:String)=SourceActionResult(false,"error",message,code)
}

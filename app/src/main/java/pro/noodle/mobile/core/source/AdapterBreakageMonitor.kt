package pro.noodle.mobile.core.source

import android.content.Context
import pro.noodle.mobile.core.json.JsonValue
import pro.noodle.mobile.core.settings.SourceOverridesRepository


enum class BreakageStatus(val wireName:String){HEALTHY("healthy"),DEGRADED("degraded"),BROKEN("broken"),REPAIR_SUCCEEDED("repair_succeeded"),REPAIR_FAILED("repair_failed"),QUARANTINED("quarantined"),INVALID("invalid")}

data class BreakageResult(
    val adapterId:String,
    val status:BreakageStatus,
    val semanticStatus:String="",
    val consecutiveFailures:Int=0,
    val threshold:Int=3,
    val repairAction:String="",
    val errorCode:String="",
    val message:String="",
)

class AdapterBreakageMonitor(
    context:Context,
    private val repo:RuntimeAdapterRepository=RuntimeAdapterRepository(context),
    private val probe:AdapterProbeService=AdapterProbeService(context,repo),
    private val repair:AdapterRepairPipeline=AdapterRepairPipeline(context,repo),
){
    private val store=SourceStatusStore(FileRefs.breakage(repo.paths))
    private val overrides=SourceOverridesRepository(context.applicationContext)

    suspend fun checkOne(adapterId:String):BreakageResult{
        val runtime=repo.find(adapterId)?:return BreakageResult(adapterId,BreakageStatus.INVALID,errorCode="SNP_NOT_FOUND",message="SNP не найден.")
        val d=runtime.definition
        val threshold=d.repairFailureThreshold.coerceAtLeast(1)
        val oldFailures=store.getInt(d.id,"failures")
        val lastRepair=store.getLong(d.id,"last_repair_unix")
        val semantic=probe.semantic(d.id)
        if(semantic.status==SemanticStatus.HEALTHY){
            val out=BreakageResult(d.id,BreakageStatus.HEALTHY,semantic.status.wireName,0,threshold,message=semantic.message)
            save(out,lastRepair);return out
        }
        val failures=oldFailures+1
        if(failures<threshold){val out=BreakageResult(d.id,BreakageStatus.DEGRADED,semantic.status.wireName,failures,threshold,errorCode=semantic.errorCode,message=semantic.message);save(out,lastRepair);return out}
        if(!d.repairEnabled){val out=BreakageResult(d.id,BreakageStatus.BROKEN,semantic.status.wireName,failures,threshold,errorCode=semantic.errorCode,message=semantic.message);save(out,lastRepair);return out}
        val now=System.currentTimeMillis()/1000
        val cooldown=d.repairCooldownMinutes.toLong()*60
        if(lastRepair>0&&cooldown>0&&now-lastRepair<cooldown){val out=BreakageResult(d.id,BreakageStatus.BROKEN,semantic.status.wireName,failures,threshold,errorCode=semantic.errorCode,message=semantic.message+" Repair cooldown ещё активен.");save(out,lastRepair);return out}
        val r=repair.tryRepair(d.id)
        if(r.success){val out=BreakageResult(d.id,BreakageStatus.REPAIR_SUCCEEDED,semantic.status.wireName,0,threshold,"${r.strategy}:${r.oldVersion}->${r.newVersion}",message=r.message);save(out,now);return out}
        var status=BreakageStatus.REPAIR_FAILED
        var message=r.message.ifBlank{semantic.message}
        if(d.repairAutoQuarantine){overrides.setEnabled(d.id,false);status=BreakageStatus.QUARANTINED;message+=" SNP отключён до ручной проверки."}
        val out=BreakageResult(d.id,status,semantic.status.wireName,failures,threshold,r.strategy,r.errorCode.ifBlank{semantic.errorCode},message);save(out,now);return out
    }

    private fun save(r:BreakageResult,lastRepair:Long){store.put(r.adapterId,mapOf("status" to JsonValue.Str(r.status.wireName),"semantic_status" to JsonValue.Str(r.semanticStatus),"failures" to JsonValue.Num(r.consecutiveFailures.toDouble()),"threshold" to JsonValue.Num(r.threshold.toDouble()),"last_repair_unix" to JsonValue.Num(lastRepair.toDouble()),"error_code" to JsonValue.Str(r.errorCode),"message" to JsonValue.Str(r.message)))}
}

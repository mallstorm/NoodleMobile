package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.json.JsonValue
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient


data class SourceHealthResult(val adapterId:String,val status:String,val errorCode:String="",val message:String="")

class SourceHealthMonitor(private val repo:RuntimeAdapterRepository,private val http:SecureHttpClient=SecureHttpClient()){
    private val store=SourceStatusStore(FileRefs.health(repo.paths))
    suspend fun checkOne(adapterId:String):SourceHealthResult{
        val r=repo.find(adapterId)?:return SourceHealthResult(adapterId,"invalid","SNP_NOT_FOUND","SNP не найден.")
        val d=r.definition
        val net=http.fetchText(d.baseUrl,NetworkPolicy(d.allowedHosts,allowCookies=d.allowCookies,maxResponseBytes=256*1024),"health:${d.id}")
        val out=if(net.success)SourceHealthResult(d.id,"healthy",message="HTTP ${net.statusCode}") else SourceHealthResult(d.id,"unavailable",net.errorCode,net.errorMessage)
        store.put(d.id,mapOf("status" to JsonValue.Str(out.status),"error_code" to JsonValue.Str(out.errorCode),"message" to JsonValue.Str(out.message)))
        return out
    }
}

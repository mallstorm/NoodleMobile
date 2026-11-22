package pro.noodle.mobile.core.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.catalog.TrustedKeyManager
import pro.noodle.mobile.core.engine.EngineDependencies
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.selftest.CoreSelfTest
import pro.noodle.mobile.core.source.AdapterPackageManager
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.storage.NoodleDatabase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


data class AndroidDiagnosticCheck(val name:String,val passed:Boolean,val detail:String="")
data class AndroidDiagnosticReport(val checks:List<AndroidDiagnosticCheck>){val passed:Boolean get()=checks.all{it.passed}}

class DiagnosticsService(context:Context){
    private val app=context.applicationContext
    private val runtime=RuntimeAdapterRepository(app)
    suspend fun selfTest():AndroidDiagnosticReport=withContext(Dispatchers.IO){
        val checks=mutableListOf<AndroidDiagnosticCheck>()
        CoreSelfTest.run().checks.forEach{checks+=AndroidDiagnosticCheck(it.name,it.passed,it.detail)}
        val writable=runCatching{val f=File(app.filesDir,"diagnostics/.write-test");f.parentFile?.mkdirs();f.writeText("ok");val ok=f.readText()=="ok";f.delete();ok}.getOrDefault(false)
        checks+=AndroidDiagnosticCheck("Private storage writable",writable,app.filesDir.path)
        checks+=AndroidDiagnosticCheck("Engine registry count",EngineRegistry.knownEngineIds.size==15,"canonical=${EngineRegistry.knownEngineIds.size}")
        checks+=AndroidDiagnosticCheck("Runtime engine count",RuntimeEngineRegistry.implementedIds.size==15,"runtime=${RuntimeEngineRegistry.implementedIds.size}")
        val dep=EngineDependencies.validateRegistry();checks+=AndroidDiagnosticCheck("Dependency graph",dep.success,dep.errorMessage)
        val sources=runtime.listAll();checks+=AndroidDiagnosticCheck("SNP sources",true,"custom=${sources.count{!it.bundled}}")
        sources.filter{it.bundled}.forEach{r->checks+=AndroidDiagnosticCheck("Package ${r.definition.id}",r.packageInfo.valid,"${r.packageInfo.trustStatus.wireName} ${r.packageInfo.errorCode}")}
        checks+=AndroidDiagnosticCheck("Trusted key registry readable",runCatching{TrustedKeyManager(app).list();true}.getOrDefault(false),"keys=${runCatching{TrustedKeyManager(app).list().size}.getOrDefault(0)}")
        checks+=AndroidDiagnosticCheck("Room opens",runCatching{NoodleDatabase.get(app).openHelper.writableDatabase.isOpen}.getOrDefault(false))
        AndroidDiagnosticReport(checks)
    }

    suspend fun createZip():File=withContext(Dispatchers.IO){
        val report=selfTest();val out=File(app.cacheDir,"noodle-diagnostics-${System.currentTimeMillis()}.zip")
        val pkg=runCatching{app.packageManager.getPackageInfo(app.packageName,0)}.getOrNull()
        val header=buildString{
            appendLine("Noodle Mobile diagnostics")
            appendLine("package=${app.packageName}")
            appendLine("versionName=${pkg?.versionName.orEmpty()}")
            appendLine("versionCode=${pkg?.longVersionCode ?: 0}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("result=${if(report.passed)"PASS" else "FAIL"}")
        }
        val checkText=buildString{report.checks.forEach{appendLine("${if(it.passed)"PASS" else "FAIL"} | ${it.name}${if(it.detail.isBlank())"" else " | ${DiagnosticsSanitizer.sanitize(it.detail)}"}")}}
        val sourceText=buildString{runtime.listAll().forEach{r->appendLine("${r.definition.id}\t${r.definition.adapterVersion}\t${if(r.bundled)"bundled" else "custom"}\t${r.packageInfo.trustStatus.wireName}\t${r.compatibility.status}")}}
        ZipOutputStream(out.outputStream().buffered()).use{z->
            fun entry(name:String,text:String){z.putNextEntry(ZipEntry(name));z.write(DiagnosticsSanitizer.sanitize(text).toByteArray(Charsets.UTF_8));z.closeEntry()}
            entry("device.txt",header);entry("self-test.txt",checkText);entry("sources.txt",sourceText)
            val stateFiles=listOf("semantic_state.json","health_state.json","fingerprint_state.json","breakage_state.json","update_state.json")
            val diagDir=runtime.paths.diagnostics
            stateFiles.forEach{name->val f=File(diagDir,name);if(f.isFile)entry("source-state/$name",f.readText(Charsets.UTF_8))}
        }
        out
    }
}

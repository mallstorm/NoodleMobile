import java.io.File

private data class Check(val name:String,val ok:Boolean,val detail:String="")
private fun has(file:File, token:String)=file.readText().contains(token)

fun main(args:Array<String>){
    val root=File(args.getOrElse(0){"."}).canonicalFile
    fun f(path:String)=File(root,path)
    val checks=mutableListOf<Check>()
    fun ck(name:String, ok:Boolean, detail:String=""){checks+=Check(name,ok,detail)}
    val app=f("app/build.gradle.kts").readText()
    val top=f("build.gradle.kts").readText()
    val wrap=f("gradle/wrapper/gradle-wrapper.properties").readText()
    val planner=f("app/src/main/java/pro/noodle/mobile/core/download/AdaptiveDownloadPlanner.kt").readText()
    val repo=f("app/src/main/java/pro/noodle/mobile/core/download/DownloadRepository.kt").readText()
    val service=f("app/src/main/java/pro/noodle/mobile/core/download/NoodleDownloadService.kt").readText()
    val manifest=f("app/src/main/AndroidManifest.xml").readText()

    ck("stage7+ version", ("versionCode = 70007" in app && "versionName = \"0.7.0-alpha7\"" in app) || ("versionCode = 80008" in app && "versionName = \"0.8.0-alpha8\"" in app) || ("versionCode = 100010" in app && "versionName = \"0.10.0-alpha10\"" in app))
    ck("AGP 9.3.1", "com.android.application\") version \"9.3.1" in top)
    ck("AGP built-in Kotlin", "org.jetbrains.kotlin.android" !in top && "org.jetbrains.kotlin.android" !in app)
    ck("KGP 2.4.10 pinned", "kotlin-gradle-plugin:2.4.10" in top && "plugin.compose\") version \"2.4.10" in top)
    ck("KSP 2.3.11", "com.google.devtools.ksp\") version \"2.3.11" in top)
    ck("API 37", "compileSdk = 37" in app && "targetSdk = 37" in app)
    ck("Gradle 9.5.1 wrapper", "gradle-9.5.1-bin.zip" in wrap)
    ck("Gradle wrapper SHA256 pinned", "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f" in wrap)
    ck("Media3 supported runtime line", ("media3-exoplayer:1.10.1" in app && "media3-exoplayer-workmanager:1.10.1" in app) || ("media3-exoplayer:1.11.0" in app && "media3-exoplayer-workmanager:1.11.0" in app))
    ck("Adaptive manager bridge to main", "withContext(Dispatchers.Main.immediate)" in repo && "MediaDownloadRuntime.manager(app).maxParallelDownloads" in repo)
    ck("DownloadHelper main bridge", "withContext(Dispatchers.Main.immediate)" in planner)
    ck("DownloadHelper renderers", "DefaultRenderersFactory(context)" in planner && ".setRenderersFactory" in planner)
    ck("Current TrackSelectionParameters builder", "TrackSelectionParameters.Builder()" in planner && "TrackSelectionParameters.Builder(context)" !in planner)
    ck("Single best offline representation", "setForceHighestSupportedBitrate(true)" in planner)
    ck("WorkManagerScheduler enabled", "WorkManagerScheduler(applicationContext, SCHEDULER_WORK_NAME)" in service)
    ck("Media3 workmanager artifact", "media3-exoplayer-workmanager:1.10.1" in app || "media3-exoplayer-workmanager:1.11.0" in app)
    ck("Scheduler restart manifest", "androidx.media3.exoplayer.downloadService.action.RESTART" in manifest)
    ck("Scheduler service remains non-exported", "android:name=\".core.download.NoodleDownloadService\"" in manifest && "android:exported=\"false\"" in manifest)
    ck("Thread-affinity instrumentation", f("app/src/androidTest/java/pro/noodle/mobile/MediaDownloadRuntimeInstrumentedTest.kt").isFile)

    checks.forEach{ println((if(it.ok)"PASS" else "FAIL")+" | "+it.name+(if(it.detail.isBlank())"" else " | "+it.detail)) }
    val good=checks.count{it.ok}
    println("SUMMARY $good/${checks.size} passed")
    if(good!=checks.size) throw IllegalStateException("Stage7 harness failed")
}

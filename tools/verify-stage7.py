from pathlib import Path
import hashlib, re, sys
R=Path(__file__).resolve().parents[1]
checks=[]
def ck(name, ok, detail=''):
    checks.append((name,bool(ok),detail)); print(('PASS' if ok else 'FAIL')+' | '+name+((' | '+detail) if detail else ''))
def t(rel): return (R/rel).read_text(encoding='utf-8')

top=t('build.gradle.kts'); app=t('app/build.gradle.kts'); wrapper=t('gradle/wrapper/gradle-wrapper.properties')
repo=t('app/src/main/java/pro/noodle/mobile/core/download/DownloadRepository.kt')
planner=t('app/src/main/java/pro/noodle/mobile/core/download/AdaptiveDownloadPlanner.kt')
runtime=t('app/src/main/java/pro/noodle/mobile/core/download/MediaDownloadRuntime.kt')
service=t('app/src/main/java/pro/noodle/mobile/core/download/NoodleDownloadService.kt')
manifest=t('app/src/main/AndroidManifest.xml')
settings=t('app/src/main/java/pro/noodle/mobile/ui/SettingsViewModel.kt')

ck('stage7+ version', ('versionCode = 70007' in app and 'versionName = "0.7.0-alpha7"' in app) or ('versionCode = 80008' in app and 'versionName = "0.8.0-alpha8"' in app) or ('versionCode = 100010' in app and 'versionName = "0.10.0-alpha10"' in app))
ck('compileSdk/targetSdk API37', 'compileSdk = 37' in app and 'targetSdk = 37' in app)
ck('minSdk 28 retained', 'minSdk = 28' in app)
ck('AGP 9.3.1', 'id("com.android.application") version "9.3.1"' in top)
ck('AGP9 built-in Kotlin: no kotlin-android plugin', 'org.jetbrains.kotlin.android' not in top+app)
ck('KGP 2.4.10 pin', 'kotlin-gradle-plugin:2.4.10' in top)
ck('Compose compiler plugin 2.4.10', 'org.jetbrains.kotlin.plugin.compose") version "2.4.10"' in top)
ck('KSP 2.3.11', 'com.google.devtools.ksp") version "2.3.11"' in top)
ck('Room KSP schema output retained', 'room.schemaLocation' in app and 'room-compiler' in app)
ck('Gradle wrapper 9.5.1', 'gradle-9.5.1-bin.zip' in wrapper)
ck('Gradle distribution SHA256 pinned', 'distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f' in wrapper)
ck('Wrapper distribution validation on', 'validateDistributionUrl=true' in wrapper)
ck('Core KTX 1.19.0', 'androidx.core:core-ktx:1.19.0' in app)
ck('Lifecycle ViewModel KTX explicit', 'lifecycle-viewmodel-ktx:2.11.0' in app)
ck('Media3 supported runtime line', app.count(':1.10.1")') >= 7 or app.count(':1.11.0")') >= 7)
ck('Media3 WorkManager scheduler artifact', 'media3-exoplayer-workmanager:1.10.1' in app or 'media3-exoplayer-workmanager:1.11.0' in app)
ck('Room 2.8.4 retained', 'val roomVersion = "2.8.4"' in app)
ck('DataStore 1.2.1 retained', 'datastore-preferences:1.2.1' in app)
ck('WorkManager 2.11.2 retained', 'work-runtime-ktx:2.11.2' in app)
ck('Coroutines Android 1.10.2 retained', 'kotlinx-coroutines-android:1.10.2' in app)

# Build/API stabilization fixes.
ck('Adaptive enqueue main-thread manager bridge', 'withContext(Dispatchers.Main.immediate)' in repo and 'MediaDownloadRuntime.manager(app).maxParallelDownloads = maxParallel' in repo)
ck('Planner enters main looper before helper/runtime access', 'return withContext(Dispatchers.Main.immediate)' in planner)
ck('DownloadHelper uses DefaultRenderersFactory', 'DefaultRenderersFactory(context)' in planner and '.setRenderersFactory(' in planner)
ck('DownloadHelper stays policy-aware', 'MediaDownloadRuntime.downloadHelperDataSourceFactory' in planner)
ck('Nondeprecated TrackSelectionParameters builder', 'TrackSelectionParameters.Builder()' in planner and 'TrackSelectionParameters.Builder(context)' not in planner)
ck('Offline selection one highest supported representation', '.setForceHighestSupportedBitrate(true)' in planner)
ck('Audio/subtitle offline selection retained', all(x in planner for x in ['setPreferredAudioLanguage','setTrackTypeDisabled(C.TRACK_TYPE_TEXT','setPreferredTextLanguage']))
ck('DownloadHelper stream keys retained', 'helper.getDownloadRequest' in planner)
ck('Planner release on success/error/cancel', planner.count('helper.release()') >= 3)
ck('DownloadManager construction main-looper fail-fast', 'MEDIA_DOWNLOAD_RUNTIME_INIT_MAIN_THREAD_REQUIRED' in runtime and 'Looper.getMainLooper()' in runtime)
ck('DownloadManager access fail-fast', 'DOWNLOAD_MANAGER_MAIN_THREAD_REQUIRED' in runtime)
ck('DownloadIndex IO separated after main init', 'withContext(Dispatchers.Main.immediate)' in runtime and 'withContext(Dispatchers.IO)' in runtime)
ck('Settings manager call originates from viewModelScope/main', 'viewModelScope.launch' in settings and 'MediaDownloadRuntime.manager' in settings)

# Scheduler/process survival.
ck('DownloadService uses WorkManagerScheduler', 'WorkManagerScheduler(applicationContext, SCHEDULER_WORK_NAME)' in service)
ck('Unique scheduler work name', 'SCHEDULER_WORK_NAME="noodle_media_download_restart"' in service)
ck('Scheduler restart action declared', 'androidx.media3.exoplayer.downloadService.action.RESTART' in manifest)
ck('Scheduler restart DEFAULT category declared', 'android.intent.category.DEFAULT' in manifest)
ck('DownloadService dataSync FGS retained', 'android:foregroundServiceType="dataSync"' in manifest)
ck('FGS permissions retained', 'android.permission.FOREGROUND_SERVICE' in manifest and 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' in manifest)
ck('DownloadService non-exported', re.search(r'<service[\s\S]*?NoodleDownloadService[\s\S]*?android:exported="false"',manifest) is not None)
ck('Cleartext disabled', 'android:usesCleartextTraffic="false"' in manifest)

# Instrumentation-ready checks.
inst=R/'app/src/androidTest/java/pro/noodle/mobile/MediaDownloadRuntimeInstrumentedTest.kt'
ck('Thread affinity instrumentation exists', inst.is_file())
if inst.is_file():
    ins=inst.read_text(encoding='utf-8')
    ck('Instrumentation asserts main application looper', 'assertEquals(Looper.getMainLooper(), manager.applicationLooper)' in ins)
    ck('Instrumentation exercises IO failure', 'withContext(Dispatchers.IO)' in ins and 'IllegalStateException' in ins)
    ck('Instrumentation checks fail-fast code', 'DOWNLOAD_MANAGER_MAIN_THREAD_REQUIRED' in ins)
else:
    for name in ['Instrumentation asserts main application looper','Instrumentation exercises IO failure','Instrumentation checks fail-fast code']: ck(name,False)
for f in ['DatabaseInstrumentedTest.kt','DownloadPolicyInstrumentedTest.kt','MainActivitySmokeTest.kt']:
    ck('Legacy instrumentation retained '+f, (R/'app/src/androidTest/java/pro/noodle/mobile'/f).is_file())

# Security/offline invariants from prior stages that build stabilization must not weaken.
ck('Strict offline cache no upstream', '.setUpstreamDataSourceFactory(null)' in runtime)
ck('Strict offline cache no writer', '.setCacheWriteDataSinkFactory(null)' in runtime)
ck('Adaptive upstream uses SecureHttpClient', 'SecureHttpClient().streamingClient' in runtime)
ck('Download request policy codec fail-closed hook retained', 'DownloadPolicyCodec.decode(request.data)' in runtime and 'DOWNLOAD_POLICY_MISSING_OR_INVALID' in runtime)
ck('Current Media3 user agent centralized', 'BuildIdentity.USER_AGENT' in runtime)

# Source/build scripts.
buildcheck=t('tools/build-check.sh')
ck('Build check exists', (R/'tools/build-check.sh').is_file())
ck('Build check probes API37', 'platforms/android-37/android.jar' in buildcheck)
ck('Gradlew scripts retained', (R/'gradlew').is_file() and (R/'gradlew.bat').is_file())
ck('Wrapper properties retained', (R/'gradle/wrapper/gradle-wrapper.properties').is_file())

failed=[x for x in checks if not x[1]]
print(f'SUMMARY {len(checks)-len(failed)}/{len(checks)} passed')
sys.exit(1 if failed else 0)

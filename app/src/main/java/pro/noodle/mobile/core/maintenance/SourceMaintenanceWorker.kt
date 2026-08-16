package pro.noodle.mobile.core.maintenance

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import pro.noodle.mobile.core.settings.AppSettingsRepository
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.AdapterBreakageMonitor
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.source.SiteFingerprintMonitor
import java.util.concurrent.TimeUnit

class SourceMaintenanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = AppSettingsRepository(applicationContext).settings.first()
        if (!settings.backgroundMonitoring) return Result.success()
        val repo = RuntimeAdapterRepository(applicationContext)
        val disabled = SourceOverridesRepository(applicationContext).disabledSourceIds.first()
        val fingerprint = SiteFingerprintMonitor(repo)
        val breakage = AdapterBreakageMonitor(applicationContext, repo)
        var transientFailures = 0
        repo.listAll().filter { r -> r.compatibility.compatible && r.packageInfo.valid && r.definition.enabled && r.definition.id.lowercase() !in disabled }.forEach { r ->
            runCatching { fingerprint.checkOne(r.definition.id) }.onFailure { transientFailures++ }
            runCatching { breakage.checkOne(r.definition.id) }.onFailure { transientFailures++ }
        }
        return if (transientFailures > 0) Result.retry() else Result.success()
    }
}

object SourceMaintenanceScheduler {
    private const val UNIQUE = "noodle-source-maintenance"
    fun sync(context: Context, enabled: Boolean, intervalHours: Int) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }
        val hours = intervalHours.coerceIn(6, 168).toLong()
        val req = PeriodicWorkRequestBuilder<SourceMaintenanceWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

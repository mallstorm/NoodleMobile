package pro.noodle.mobile.core.maintenance

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import pro.noodle.mobile.core.settings.AppSettingsRepository
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.source.SiteFingerprintMonitor
import java.util.concurrent.TimeUnit

/**
 * Daily health check of every enabled source: real search + resolver on two cards (no Media3, which
 * needs a looper). A source that fails twice in a row triggers one notification with a
 * «Пересоздать» action that opens SNP Studio prefilled with the site and its test query.
 */
class SourceMaintenanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = AppSettingsRepository(applicationContext).settings.first()
        if (!settings.backgroundMonitoring) return Result.success()
        val repo = RuntimeAdapterRepository(applicationContext)
        val overrides = SourceOverridesRepository(applicationContext)
        val disabled = overrides.disabledSourceIds.first()
        val forced = overrides.forceEnabledSourceIds.first()
        val probe = pro.noodle.mobile.core.source.AdapterProbeService(applicationContext, repo)
        val playback = pro.noodle.mobile.core.player.PlaybackRepository(applicationContext)
        val prefs = applicationContext.getSharedPreferences("noodle_source_watch", Context.MODE_PRIVATE)
        val fingerprint = SiteFingerprintMonitor(repo)
        repo.listAll().filter { r ->
            val id = r.definition.id.lowercase()
            r.compatibility.compatible && r.packageInfo.valid && (r.definition.enabled || id in forced) && id !in disabled
        }.forEach { r ->
            val d = r.definition
            runCatching { fingerprint.checkOne(d.id) }
            val ok = runCatching {
                val (items, err) = probe.searchOne(d, d.healthQuery.ifBlank { "video" }, 10)
                err == null && items.isNotEmpty() && items.take(2).any { playback.resolve(it, d).success }
            }.getOrDefault(false)
            val failures = if (ok) 0 else prefs.getInt("fail_${d.id}", 0) + 1
            val notified = prefs.getBoolean("notified_${d.id}", false)
            prefs.edit().putInt("fail_${d.id}", failures).putBoolean("notified_${d.id}", if (ok) false else notified).apply()
            if (!ok && failures >= 2 && !notified) {
                // Сначала ищем зеркало: чаще всего сайт просто переехал на другой домен.
                val moved = runCatching { pro.noodle.mobile.core.source.MirrorFinder(applicationContext).findAndAdopt(d) }.getOrNull()
                when {
                    moved != null && moved.adopted.isNotBlank() -> {
                        SourceAlerts.notifyMirror(applicationContext, d.id, "${d.name}: новый адрес", moved.message)
                        prefs.edit().putInt("fail_${d.id}", 0).putBoolean("notified_${d.id}", false).apply()
                    }
                    moved != null && moved.suggested.isNotEmpty() -> {
                        SourceAlerts.notifyMirror(applicationContext, d.id, "${d.name}: найдено возможное зеркало", moved.message + " Откройте «Источники → ⋮ → Зеркала и домен».")
                        prefs.edit().putBoolean("notified_${d.id}", true).apply()
                    }
                    else -> {
                        SourceAlerts.notifyBroken(applicationContext, d.id, d.name, d.baseUrl, d.healthQuery)
                        prefs.edit().putBoolean("notified_${d.id}", true).apply()
                    }
                }
            }
        }
        pro.noodle.mobile.core.player.BackgroundGate.awaitIdle()
        val found = runCatching { pro.noodle.mobile.core.newvideos.NewVideosScanner(applicationContext).scan() }.getOrDefault(0)
        val notifiable = pro.noodle.mobile.core.newvideos.NewVideosStore(applicationContext).lastNotifiable
        if (found > 0 && notifiable > 0) SourceAlerts.notifyNew(applicationContext, notifiable, settings.monitoringIntervalHours)
        return Result.success()
    }
}

object SourceAlerts {
    const val CHANNEL_NEW = "noodle_new_videos"

    fun notifyNew(context: Context, count: Int, hours: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL_NEW, "Новые видео", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.content.Intent(context, pro.noodle.mobile.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = android.app.PendingIntent.getActivity(context, 7001, open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, CHANNEL_NEW)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Новые видео: $count")
            .setContentText("На ваших источниках появились новые ролики. Откройте колокольчик на главной.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(7001, n)
    }

    fun notifyMirror(context: Context, id: String, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Состояние источников", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.content.Intent(context, pro.noodle.mobile.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = android.app.PendingIntent.getActivity(context, ("m" + id).hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(("m" + id).hashCode(), n)
    }

    const val CHANNEL = "noodle_sources"
    const val EXTRA_STUDIO_URL = "pro.noodle.mobile.extra.STUDIO_URL"
    const val EXTRA_STUDIO_QUERY = "pro.noodle.mobile.extra.STUDIO_QUERY"

    fun notifyBroken(context: Context, id: String, name: String, baseUrl: String, query: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Состояние источников", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.content.Intent(context, pro.noodle.mobile.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_STUDIO_URL, baseUrl)
            .putExtra(EXTRA_STUDIO_QUERY, query)
        val pi = android.app.PendingIntent.getActivity(context, id.hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$name перестал работать")
            .setContentText("Поиск или видео не открываются. Нажмите, чтобы пересоздать адаптер в SNP Studio.")
            .setContentIntent(pi)
            .addAction(android.app.Notification.Action.Builder(null, "Пересоздать", pi).build())
            .setAutoCancel(true)
            .build()
        nm.notify(id.hashCode(), n)
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
        val hours = intervalHours.coerceIn(2, 46).toLong()
        val req = PeriodicWorkRequestBuilder<SourceMaintenanceWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

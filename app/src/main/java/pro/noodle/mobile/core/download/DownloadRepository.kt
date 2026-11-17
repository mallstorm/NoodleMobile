package pro.noodle.mobile.core.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.settings.AppSettingsRepository
import pro.noodle.mobile.core.storage.DownloadEntity
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.storage.NoodleDatabase
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class DownloadRepository(private val context: Context) {
    private val app = context.applicationContext
    private val dao = NoodleDatabase.get(app).downloads()
    private val planner = AdaptiveDownloadPlanner(app)

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observeCompleted(): Flow<List<DownloadEntity>> = dao.observeCompleted()

    suspend fun enqueue(
        title: String,
        source: MediaSource,
        policy: NetworkPolicy,
        sourceKey: String,
        destinationUri: String = "",
        thumbnailUrl: String = "",
        adaptiveSelection: AdaptiveDownloadSelection = AdaptiveDownloadSelection(),
        referer: String = "",
        /** HLS only: save as one regular .mp4 file (needs destinationUri) instead of Media3's private offline cache. */
        asFile: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val hlsFile = asFile && destinationUri.isNotBlank() && isHls(source)
        val adaptive = isAdaptive(source) && !hlsFile
        SourceReferers.remember(app, id, sourceKey, referer)
        val selected = adaptiveSelection.normalized()
        val entity = DownloadEntity(
            id = id,
            title = title,
            sourceUrl = source.url,
            sourceKey = sourceKey,
            allowedHosts = policy.allowedHosts.joinToString("\n"),
            allowCookies = policy.allowCookies,
            allowHttp = policy.allowHttp,
            destinationUri = if (adaptive) ADAPTIVE_DESTINATION else destinationUri,
            mimeType = if (adaptive) adaptiveMime(source) else if (hlsFile) "video/mp4" else source.mimeType,
            state = DownloadState.QUEUED,
            thumbnailUrl = thumbnailUrl,
            protocol = if (hlsFile) "hls" else source.protocol,
            videoSelectionLabel = if (adaptive) selected.videoLabel else source.label,
            videoMaxWidth = selected.maxVideoWidth,
            videoMaxHeight = selected.maxVideoHeight,
            audioSelectionLanguage = selected.audioLanguage,
            textSelectionLanguage = selected.textLanguage,
            includeSubtitles = selected.includeSubtitles,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        if (adaptive) enqueueAdaptive(id, source, policy, sourceKey, selected) else schedule(id)
        return id
    }

    suspend fun pause(id: String) {
        val item = dao.get(id) ?: return
        dao.updateState(id, DownloadState.PAUSED, System.currentTimeMillis())
        if (item.isAdaptive()) {
            DownloadService.sendSetStopReason(app, NoodleDownloadService::class.java, id, NoodleDownloadService.STOP_REASON_USER, false)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(workName(id))
        }
    }

    suspend fun resume(id: String) {
        val item = dao.get(id) ?: return
        if (item.state == DownloadState.COMPLETED || item.state == DownloadState.CANCELED) return
        dao.updateState(id, DownloadState.QUEUED, System.currentTimeMillis())
        if (item.isAdaptive()) {
            if (item.state == DownloadState.FAILED) {
                // Preserve DownloadHelper streamKeys. Rebuilding a plain request here would silently
                // forget the user's selected quality/audio/subtitles after a process restart.
                val request = MediaDownloadRuntime.requestFor(app, id)
                if (request != null) {
                    DownloadService.sendAddDownload(app, NoodleDownloadService::class.java, request, true)
                } else {
                    replanAdaptive(item)
                }
            } else {
                DownloadService.sendSetStopReason(app, NoodleDownloadService::class.java, id, Download.STOP_REASON_NONE, false)
            }
        } else {
            schedule(id)
        }
    }

    suspend fun cancel(id: String) {
        val item = dao.get(id) ?: return
        dao.updateState(id, DownloadState.CANCELED, System.currentTimeMillis())
        if (item.isAdaptive()) {
            DownloadService.sendRemoveDownload(app, NoodleDownloadService::class.java, id, false)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(workName(id))
        }
    }

    suspend fun remove(id: String) {
        val item = dao.get(id)
        if (item?.isAdaptive() == true) {
            DownloadService.sendRemoveDownload(app, NoodleDownloadService::class.java, id, false)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(workName(id))
            DownloadWorker.partialFile(app, id).delete()
            item?.localFilePath?.takeIf { it.startsWith(app.filesDir.absolutePath) }?.let { java.io.File(it).delete() }
        }
        dao.delete(id)
    }

    suspend fun get(id: String): DownloadEntity? = dao.get(id)

    private suspend fun enqueueAdaptive(
        id: String,
        source: MediaSource,
        policy: NetworkPolicy,
        sourceKey: String,
        selection: AdaptiveDownloadSelection,
    ) {
        val maxParallel = AppSettingsRepository(app).settings.first().parallelDownloads.coerceIn(1, 10)
        // Media3 DownloadManager is thread-affine. All manager access must happen on the
        // main looper because the singleton is created and serviced there by DownloadService.
        withContext(Dispatchers.Main.immediate) {
            MediaDownloadRuntime.manager(app).maxParallelDownloads = maxParallel
        }
        val referer = SourceReferers.forDownload(app, id, sourceKey)
        when (val plan = planner.plan(id, source, policy, sourceKey, selection, referer)) {
            is AdaptivePlanResult.Ready -> DownloadService.sendAddDownload(app, NoodleDownloadService::class.java, plan.request, true)
            is AdaptivePlanResult.Failed -> dao.updateProgress(
                id,
                DownloadState.FAILED,
                0,
                -1,
                plan.code,
                plan.message,
                System.currentTimeMillis(),
            )
        }
    }

    private suspend fun replanAdaptive(item: DownloadEntity) {
        val policy = NetworkPolicy(
            item.allowedHosts.lineSequence().filter { it.isNotBlank() }.toList(),
            allowHttp = item.allowHttp,
            allowCookies = item.allowCookies,
        )
        val source = MediaSource(
            label = item.videoSelectionLabel,
            url = item.sourceUrl,
            mimeType = item.mimeType,
            protocol = item.protocol,
        )
        enqueueAdaptive(
            item.id,
            source,
            policy,
            item.sourceKey,
            AdaptiveDownloadSelection(
                maxVideoWidth = item.videoMaxWidth,
                maxVideoHeight = item.videoMaxHeight,
                videoLabel = item.videoSelectionLabel.ifBlank { "Auto" },
                audioLanguage = item.audioSelectionLanguage,
                textLanguage = item.textSelectionLanguage,
                includeSubtitles = item.includeSubtitles,
            ),
        )
    }

    private fun schedule(id: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("noodle_download")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    fun isHls(source: MediaSource): Boolean =
        source.protocol.equals("hls", true) || source.url.substringBefore('?').endsWith(".m3u8", true) || source.mimeType.contains("mpegurl", true)

    fun isAdaptive(source: MediaSource): Boolean =
        source.protocol.equals("hls", true) || source.protocol.equals("dash", true) ||
            source.url.contains(".m3u8", true) || source.url.contains(".mpd", true) ||
            source.mimeType.contains("mpegurl", true) || source.mimeType.contains("dash", true)

    private fun adaptiveMime(source: MediaSource): String = when {
        source.protocol.equals("dash", true) || source.url.contains(".mpd", true) || source.mimeType.contains("dash", true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
        else -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
    }

    private fun DownloadEntity.isAdaptive() = destinationUri == ADAPTIVE_DESTINATION
    private fun workName(id: String) = "noodle-download-$id"

    companion object { const val ADAPTIVE_DESTINATION = "media3://adaptive" }
}

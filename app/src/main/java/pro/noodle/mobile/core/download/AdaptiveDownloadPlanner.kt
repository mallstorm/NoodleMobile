package pro.noodle.mobile.core.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.network.NetworkPolicy
import java.io.IOException
import kotlin.coroutines.resume

sealed interface AdaptivePlanResult {
    data class Ready(val request: DownloadRequest) : AdaptivePlanResult
    data class Failed(val code: String, val message: String) : AdaptivePlanResult
}

@UnstableApi
class AdaptiveDownloadPlanner(private val context: Context) {
    suspend fun plan(
        id: String,
        source: MediaSource,
        policy: NetworkPolicy,
        sourceKey: String,
        selection: AdaptiveDownloadSelection,
    ): AdaptivePlanResult {
        val selected = selection.normalized()
        val parameters = TrackSelectionParameters.Builder()
            .setMaxVideoSize(selected.maxVideoWidth, selected.maxVideoHeight)
            // Offline media stores one best-supported video/audio representation inside the
            // selected bounds instead of retaining an adaptive ladder of redundant qualities.
            .setForceHighestSupportedBitrate(true)
            .apply {
                if (selected.audioLanguage.isNotBlank()) setPreferredAudioLanguage(selected.audioLanguage)
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !selected.includeSubtitles)
                if (selected.includeSubtitles && selected.textLanguage.isNotBlank()) {
                    setPreferredTextLanguage(selected.textLanguage)
                }
            }
            .build()

        val item = MediaItem.Builder()
            .setUri(Uri.parse(source.url))
            .setMimeType(adaptiveMime(source))
            .build()

        // MediaDownloadRuntime owns the shared DownloadManager/Cache and is initialized on the
        // main looper. DownloadHelper preparation is also started there so an IO-scoped caller
        // (PlayerScreen intentionally uses Dispatchers.IO) cannot race the runtime initialization.
        return withContext(Dispatchers.Main.immediate) {
            val helper = DownloadHelper.Factory()
                .setRenderersFactory(DefaultRenderersFactory(context))
                .setDataSourceFactory(MediaDownloadRuntime.downloadHelperDataSourceFactory(context, policy, sourceKey))
                .setTrackSelectionParameters(parameters)
                .create(item)

            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { helper.release() }
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                        if (!continuation.isActive) {
                            helper.release()
                            return
                        }
                        val result = runCatching {
                            AdaptivePlanResult.Ready(
                                helper.getDownloadRequest(id, DownloadPolicyCodec.encode(sourceKey, policy))
                            )
                        }.getOrElse {
                            AdaptivePlanResult.Failed("ADAPTIVE_PLAN_BUILD_FAILED", it.message ?: "Unable to build DownloadRequest")
                        }
                        helper.release()
                        continuation.resume(result)
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        helper.release()
                        if (continuation.isActive) {
                            continuation.resume(AdaptivePlanResult.Failed("ADAPTIVE_PREPARE_FAILED", e.message ?: "Manifest preparation failed"))
                        }
                    }
                })
            }
        }
    }

    private fun adaptiveMime(source: MediaSource): String = when {
        source.protocol.equals("dash", true) || source.url.contains(".mpd", true) || source.mimeType.contains("dash", true) -> MimeTypes.APPLICATION_MPD
        else -> MimeTypes.APPLICATION_M3U8
    }
}

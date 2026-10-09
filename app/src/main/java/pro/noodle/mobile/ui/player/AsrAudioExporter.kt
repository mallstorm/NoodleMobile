package pro.noodle.mobile.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
internal class AsrAudioExporter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun exportChunk(
        source: MediaItem,
        startMs: Long,
        endMs: Long,
    ): File = withContext(Dispatchers.Main.immediate) {
        require(endMs > startMs) { "ASR_INVALID_CHUNK_RANGE" }
        val local = source.localConfiguration ?: error("ASR_MEDIA_URI_MISSING")
        val input = MediaItem.Builder()
            .setUri(local.uri)
            .apply {
                local.mimeType?.let(::setMimeType)
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs.coerceAtLeast(0L))
                        .setEndPositionMs(endMs)
                        .build()
                )
            }
            .build()
        val edited = EditedMediaItem.Builder(input)
            .setRemoveVideo(true)
            .build()

        val directory = File(appContext.cacheDir, "ru-asr-audio").apply { mkdirs() }
        val output = File(directory, "chunk-${startMs}-${endMs}-${System.nanoTime()}.mp4")
        if (output.exists()) output.delete()

        suspendCancellableCoroutine { continuation ->
            lateinit var transformer: Transformer
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            }
            transformer = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()

            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
            transformer.start(edited, output.absolutePath)
        }
    }
}

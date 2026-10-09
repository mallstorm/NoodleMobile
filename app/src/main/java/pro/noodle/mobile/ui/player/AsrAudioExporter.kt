package pro.noodle.mobile.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.download.MediaDownloadRuntime
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

            val transformerBuilder = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)

            val isRemote = local.uri.scheme.equals("http", true) || local.uri.scheme.equals("https", true)
            if (isRemote) {
                val streamingFactory = MediaDownloadRuntime.activeStreamingDataSourceFactory()
                if (streamingFactory == null) {
                    continuation.resumeWithException(IllegalStateException("ASR_STREAM_FACTORY_MISSING"))
                    return@suspendCancellableCoroutine
                }
                val mediaSourceFactory = DefaultMediaSourceFactory(streamingFactory)
                val bitmapLoader = DataSourceBitmapLoader.Builder(appContext)
                    .setDataSourceFactory(streamingFactory)
                    .build()
                val assetLoaderFactory = DefaultAssetLoaderFactory(
                    appContext,
                    DefaultDecoderFactory.Builder(appContext).build(),
                    Clock.DEFAULT,
                    mediaSourceFactory,
                    bitmapLoader,
                )
                transformerBuilder.setAssetLoaderFactory(assetLoaderFactory)
            }

            transformer = transformerBuilder.build()
            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
            transformer.start(edited, output.absolutePath)
        }
    }
}

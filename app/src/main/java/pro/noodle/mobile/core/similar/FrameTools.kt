package pro.noodle.mobile.core.similar

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** Держит ссылку на PlayerView, чтобы снять кадр с поверхности видео. */
object PlayerFrameHolder {
    @Volatile var view: WeakReference<PlayerView>? = null
}

object FrameGrabber {
    /** Текущий кадр видео (без интерфейса плеера). Вызывать на главном потоке или из корутины. */
    suspend fun grab(): Bitmap? {
        val pv = PlayerFrameHolder.view?.get() ?: return null
        val surface = pv.videoSurfaceView ?: return null
        val w = surface.width
        val h = surface.height
        if (w <= 0 || h <= 0) return null
        return when (surface) {
            is TextureView -> surface.bitmap
            is SurfaceView -> suspendCancellableCoroutine { cont ->
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                runCatching {
                    PixelCopy.request(surface, bmp, { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bmp else null)
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
            else -> null
        }
    }

    /** Сохраняет кадр в Галерею (Pictures/NoodleMobile). Возвращает Uri или null. */
    fun saveToGallery(context: Context, bmp: Bitmap, title: String): Uri? = runCatching {
        val safe = title.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().take(60).ifBlank { "frame" }
        val name = "$safe-${System.currentTimeMillis() / 1000}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NoodleMobile")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val f = File(dir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(f)
        }
    }.getOrNull()

    /** dHash 9×8 по яркости: устойчив к другому качеству, сжатию и масштабу. */
    fun dHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
                bit++
            }
        }
        if (small !== src) small.recycle()
        return hash
    }

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

data class CopyProbe(val durationMs: Long, val width: Int, val height: Int, val hashes: List<Long>)

/** MediaDataSource поверх OkHttp (Range-запросы): те же DNS/UA/Referer, что у плеера, — подписанные ссылки CDN не ломаются. */
class HttpRangeMediaDataSource(
    private val client: OkHttpClient,
    private val url: String,
    private val referer: String,
) : MediaDataSource() {
    private var total = -1L
    private var chunkStart = -1L
    private var chunk = ByteArray(0)

    private fun req(range: String) = Request.Builder().url(url)
        .header("Range", range)
        .header("User-Agent", BuildIdentity.USER_AGENT)
        .apply { if (referer.isNotBlank()) header("Referer", referer) }
        .build()

    override fun getSize(): Long {
        if (total >= 0) return total
        client.newCall(req("bytes=0-0")).execute().use { r ->
            val cr = r.header("Content-Range").orEmpty()
            total = cr.substringAfterLast('/').toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
                ?: -1L
        }
        return total
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val sz = getSize()
        if (sz in 0..position) return -1
        if (position < chunkStart || position + size > chunkStart + chunk.size) {
            val want = maxOf(size, 512 * 1024)
            val end = if (sz > 0) minOf(position + want - 1, sz - 1) else position + want - 1
            client.newCall(req("bytes=$position-$end")).execute().use { r ->
                if (!r.isSuccessful) return -1
                chunk = r.body?.bytes() ?: return -1
                chunkStart = if (r.code == 206) position else 0L.also { /* сервер игнорирует Range */ }
            }
        }
        val from = (position - chunkStart).toInt()
        if (from < 0 || from >= chunk.size) return -1
        val n = minOf(size, chunk.size - from)
        System.arraycopy(chunk, from, buffer, offset, n)
        return n
    }

    override fun close() { chunk = ByteArray(0) }
}

object CopyProber {
    /** Длительность, размер кадра и хеши кадров в указанные моменты (для прогрессивного mp4/webm). */
    fun probe(client: OkHttpClient, url: String, referer: String, timesMs: List<Long>): CopyProbe? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(HttpRangeMediaDataSource(client, url, referer))
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val hashes = timesMs.mapNotNull { t ->
                val clipped = if (dur > 0) t.coerceIn(0L, dur - 500L) else t
                val bmp = mmr.getFrameAtTime(clipped * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapNotNull null
                FrameGrabber.dHash(bmp).also { bmp.recycle() }
            }
            CopyProbe(dur, w, h, hashes)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}

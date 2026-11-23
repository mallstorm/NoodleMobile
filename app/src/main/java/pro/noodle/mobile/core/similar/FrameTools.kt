package pro.noodle.mobile.core.similar

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** Держит ссылку на PlayerView, чтобы снять кадр с поверхности видео. */
object PlayerFrameHolder {
    @Volatile var view: WeakReference<PlayerView>? = null
}

object FrameGrabber {
    /** Текущий кадр видео (без интерфейса плеера). */
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

    /** JPEG в памяти (длинная сторона ≤ 1280) — для отправки в поиск по картинке без промежуточных файлов. */
    fun toJpeg(src: Bitmap, maxSide: Int = 1280, quality: Int = 88): ByteArray {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }

    /** Сохраняет кадр в Галерею (Pictures/NoodleMobile) — по желанию пользователя. */
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
}

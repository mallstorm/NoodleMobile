package pro.noodle.mobile.ui.player

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pro.noodle.mobile.core.similar.ReverseEngine
import pro.noodle.mobile.core.similar.ReverseImageSearch
import java.io.File

/** Кадр из плеера уходит прямо в поиск по картинке (Яндекс / Bing / Google) — без сохранения в Галерею. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FrameSearchDialog(
    jpeg: ByteArray,
    onSaveToGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(ReverseEngine.YANDEX) }
    var loading by remember { mutableStateOf(true) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val frameFile = remember(jpeg) {
        File(context.cacheDir, "frame_search.jpg").also { runCatching { it.writeBytes(jpeg) } }
    }
    var chooserCb by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { chooserCb?.onReceiveValue(null) }
            webRef?.let { w -> runCatching { w.stopLoading(); w.destroy() } }
            runCatching { frameFile.delete() }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReverseEngine.values().forEach { e ->
                            FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e.title) })
                        }
                    }
                    TextButton(onClick = onSaveToGallery) { Text("В галерею") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    callback: ValueCallback<Array<Uri>>?,
                                    params: FileChooserParams?,
                                ): Boolean {
                                    // Ручной путь: «выбор файла» на странице поисковика сам получает этот кадр.
                                    chooserCb?.let { runCatching { it.onReceiveValue(null) } }
                                    chooserCb = null
                                    callback?.onReceiveValue(arrayOf(Uri.fromFile(frameFile)))
                                    return true
                                }
                            }
                            webRef = this
                        }
                    },
                    update = { w ->
                        if (w.tag != engine) {
                            w.tag = engine
                            loading = true
                            w.loadDataWithBaseURL(engine.baseUrl, ReverseImageSearch.loaderHtml(engine, jpeg), "text/html", "UTF-8", null)
                        }
                    },
                )
            }
        }
    }
}

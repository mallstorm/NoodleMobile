package pro.noodle.mobile.ui.player

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.json.JSONArray
import org.json.JSONTokener
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onOpenStudio: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(ReverseEngine.YANDEX) }
    var loading by remember { mutableStateOf(true) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val frameFile = remember(jpeg) {
        File(context.cacheDir, "frame_search.jpg").also { runCatching { it.writeBytes(jpeg) } }
    }
    var found by remember { mutableStateOf<List<FoundLink>?>(null) }
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
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        val w = webRef ?: return@FilledTonalButton
                        w.evaluateJavascript(COLLECT_JS) { raw ->
                            val list = parseLinks(raw)
                            found = list
                            if (list.isEmpty()) Toast.makeText(context, "Ссылок на сайты не найдено — прокрутите страницу до списка «Сайты, где есть картинка» и повторите", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Собрать ссылки") }
                    OutlinedButton(onClick = { loading = true; webRef?.loadUrl(engine.homeUrl) }) { Text("Вручную") }
                    TextButton(onClick = onSaveToGallery) { Text("В галерею") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
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
                found?.takeIf { it.isNotEmpty() }?.let { list ->
                    FoundLinksPanel(
                        links = list,
                        onOpen = { url -> found = null; loading = true; webRef?.loadUrl(url) },
                        onStudio = { origin -> onOpenStudio(origin) },
                        onCopy = { text ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("links", text))
                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        },
                        onClose = { found = null },
                    )
                }
                }
            }
        }
    }
}

private data class FoundLink(val url: String, val title: String) {
    val host: String get() = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    val origin: String get() = runCatching { val u = Uri.parse(url); "${u.scheme}://${u.host}" }.getOrDefault(url)
}

private const val COLLECT_JS = """(function(){
var bad=/(^|\.)(yandex\.[a-z.]+|ya\.ru|yastatic\.net|yandex-team\.ru|bing\.com|microsoft\.com|msn\.com|live\.com|google\.[a-z.]+|gstatic\.com|googleusercontent\.com|gmodules\.com|youtube\.com|schema\.org)$/i;
var out=[],seen={};var as=document.querySelectorAll('a[href]');
for(var i=0;i<as.length;i++){var h=as[i].href;if(!/^https?:/i.test(h))continue;var u;try{u=new URL(h)}catch(e){continue}
if(/google\./i.test(u.hostname)&&u.pathname==='/url'){var q=u.searchParams.get('q')||u.searchParams.get('url');if(!q)continue;try{u=new URL(q)}catch(e){continue}}
if(bad.test(u.hostname))continue;
var key=u.href.split('#')[0];if(seen[key])continue;seen[key]=1;
var t=(as[i].innerText||as[i].title||as[i].getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,120);
out.push({u:key,t:t});}
return JSON.stringify(out);})()"""

private fun parseLinks(raw: String?): List<FoundLink> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    return runCatching {
        val str = JSONTokener(raw).nextValue() as? String ?: return emptyList()
        val arr = JSONArray(str)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { FoundLink(it.optString("u"), it.optString("t")) }
            .filter { it.url.isNotBlank() && it.host.isNotBlank() }
    }.getOrDefault(emptyList())
}

@Composable
private fun FoundLinksPanel(
    links: List<FoundLink>,
    onOpen: (String) -> Unit,
    onStudio: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    val groups = remember(links) { links.groupBy { it.host }.entries.toList() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Найдено сайтов: ${groups.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Ссылок: ${links.size}. «В Studio» создаст адаптер для сайта.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onCopy(links.joinToString("\n") { it.url }) }) { Text("Копировать все") }
                TextButton(onClick = onClose) { Text("Назад") }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.key }) { g ->
                    val first = g.value.first()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(g.key + if (g.value.size > 1) "  ·  ${g.value.size} стр." else "", fontWeight = FontWeight.SemiBold)
                            Text(first.title.ifBlank { first.url }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { onStudio(first.origin) }) { Text("В Studio") }
                                OutlinedButton(onClick = { onOpen(first.url) }) { Text("Открыть") }
                                TextButton(onClick = { onCopy(g.value.joinToString("\n") { it.url }) }) { Text("Копировать") }
                            }
                        }
                    }
                }
            }
        }
    }
}

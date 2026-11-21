package pro.noodle.mobile.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * One-shot WebView extractor for adapters that explicitly opt into JavaScript.
 * It is intentionally serialized because the platform CookieManager is process-global.
 * Besides post-JS DOM, the extractor appends a local-only evidence block containing media URLs
 * actually assigned to video/source nodes or observed by the Performance API. This lets Studio and
 * the generic player resolver understand frameworks that never serialize the final HLS/DASH URL
 * into the original HTML.
 */
class SandboxedWebExtractor(private val context: Context) {
    suspend fun extract(
        url: String,
        adapter: AdapterDefinition,
        timeoutMs: Long = 12_000,
        additionalAllowedHosts: Set<String> = emptySet(),
        /** Запускать плеер (autoplay + клик по play) и записывать потоки, которые страница запрашивает сама. */
        sniff: Boolean = true,
    ): WebExtractResult = mutex.withLock {
        if (!adapter.allowJavaScript) {
            return WebExtractResult(false, errorCode = "WEB_JS_NOT_ALLOWED", errorMessage = "SNP не разрешает JavaScript.")
        }
        if (!allowed(url, adapter, additionalAllowedHosts, resolveDns = true)) {
            return WebExtractResult(false, errorCode = "WEB_URL_BLOCKED", errorMessage = "URL не прошёл SNP/security policy.")
        }
        try {
            withTimeout(timeoutMs + if (sniff) SNIFF_EXTRA_MS else 0L) { extractOnMain(url, adapter, additionalAllowedHosts, sniff) }
        } catch (_: TimeoutCancellationException) {
            WebExtractResult(false, errorCode = "WEB_TIMEOUT", errorMessage = "JavaScript extraction превысил ${timeoutMs} мс.")
        } catch (e: Exception) {
            WebExtractResult(false, errorCode = "WEB_EXTRACT_ERROR", errorMessage = e.message ?: e.javaClass.simpleName)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractOnMain(
        url: String,
        adapter: AdapterDefinition,
        additionalAllowedHosts: Set<String>,
        sniff: Boolean,
    ): WebExtractResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            val web = WebView(context.applicationContext)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                loadsImagesAutomatically = false
                if (sniff) mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(adapter.allowCookies)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
            var finished = false
            var pageLoaded = false
            var blockedNav = ""
            var challengeWaits = 0
            val captured = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
            fun close(result: WebExtractResult) {
                if (finished) return
                finished = true
                runCatching {
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.clearHistory()
                    web.clearCache(false)
                    web.destroy()
                }
                cookies.setAcceptCookie(false)
                cookies.removeAllCookies(null)
                cookies.flush()
                if (cont.isActive) cont.resume(result)
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, false, false)
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    // Клик по плееру часто запускает «кликандер»: сайт уводит всю страницу на рекламу.
                    // После первой загрузки не пускаем JS-переходы главного окна на чужой домен (редиректы сервера — можно).
                    if (pageLoaded && request.isForMainFrame && !request.isRedirect &&
                        baseDomain(request.url.host.orEmpty()) != baseDomain(Uri.parse(url).host.orEmpty())) {
                        blockedNav = request.url.toString(); return true
                    }
                    return !allowed(request.url.toString(), adapter, additionalAllowedHosts, resolveDns = true)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    val media = sniff && looksLikeMediaRequest(u, request.requestHeaders)
                    if (media && captured.size < 24 && u.startsWith("https://", true)) captured.add(u)
                    // Сам поток не качаем: нам нужен только его адрес.
                    if (media) return blockedResponse()
                    return if (allowed(u, adapter, additionalAllowedHosts, resolveDns = true)) null else blockedResponse()
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?,
                ) {
                    handler?.cancel()
                    close(
                        WebExtractResult(
                            false,
                            errorCode = "WEB_TLS_ERROR",
                            errorMessage = "TLS/SSL ошибка WebView; обход сертификата запрещён.",
                        )
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (finished) return
                    if (!allowed(url, adapter, additionalAllowedHosts, resolveDns = true)) {
                        // Уже что-то поймали со своей страницы — отдаём это, а не ошибку.
                        if (captured.isNotEmpty()) {
                            val seen = synchronized(captured) { captured.toList() }
                            close(WebExtractResult(true, finalUrl = url, html = "<!--NOODLE_RUNTIME_MEDIA\n" + seen.joinToString("\n") + "\n-->", capturedMedia = seen))
                            return
                        }
                        close(
                            WebExtractResult(
                                false,
                                errorCode = "WEB_NAV_BLOCKED",
                                errorMessage = "Финальная страница вышла за SNP policy: ${url.take(120)}",
                            )
                        )
                        return
                    }
                    pageLoaded = true
                    fun finishNow() {
                        if (finished) return
                        view.evaluateJavascript(RUNTIME_DOM_SCRIPT) { encoded ->
                            val html = decodeJsString(encoded)
                            // JS-проверка браузера: заглушка сама перезагрузит страницу — ждём настоящий DOM.
                            if (pro.noodle.mobile.core.network.CookieChallenge.looksLikeChallenge(html) && challengeWaits++ < 5) return@evaluateJavascript
                            val seen = synchronized(captured) { captured.toList() }
                            val withSniff = if (seen.isEmpty()) html else html + "\n<!--NOODLE_RUNTIME_MEDIA\n" + seen.joinToString("\n") + "\n-->"
                            close(
                                if (html.isNotBlank()) {
                                    WebExtractResult(true, finalUrl = url, html = withSniff, capturedMedia = seen)
                                } else {
                                    WebExtractResult(false, errorCode = "WEB_EMPTY_DOM", errorMessage = "WebView не вернул DOM.")
                                }
                            )
                        }
                    }
                    if (!sniff) {
                        view.postDelayed({ finishNow() }, 900L)
                        return
                    }
                    // Запускаем плеер и ждём, пока страница сама запросит поток (до ~5 с).
                    fun poll(attempt: Int) {
                        view.postDelayed({
                            if (finished) return@postDelayed
                            when {
                                captured.isNotEmpty() && attempt >= 1 -> view.postDelayed({ finishNow() }, 600L)
                                attempt >= 6 -> finishNow()
                                else -> { view.evaluateJavascript(ACTIVATE_SCRIPT, null); poll(attempt + 1) }
                            }
                        }, 800L)
                    }
                    view.postDelayed({ if (!finished) view.evaluateJavascript(ACTIVATE_SCRIPT, null) }, 300L)
                    poll(0)
                }
            }
            cont.invokeOnCancellation {
                runCatching {
                    web.post {
                        if (!finished) {
                            finished = true
                            web.stopLoading()
                            web.destroy()
                            cookies.setAcceptCookie(false)
                            cookies.removeAllCookies(null)
                            cookies.flush()
                        }
                    }
                }
            }
            web.loadUrl(url)
        }
    }

    private fun allowed(
        raw: String,
        adapter: AdapterDefinition,
        additionalAllowedHosts: Set<String>,
        resolveDns: Boolean,
    ): Boolean {
        if (raw.equals("about:blank", true)) return true
        val decision = UrlPolicy.evaluateNavigation(raw, allowHttp = false, resolveDns = false)
        if (!decision.allowed) return false
        val host = runCatching { Uri.parse(decision.normalizedUrl).host.orEmpty().lowercase().trimEnd('.') }
            .getOrDefault("")
        if (host.isBlank()) return false
        val hostAllowed = adapter.allowedHosts.isEmpty() ||
            UrlPolicy.isHostAllowed(host, adapter.allowedHosts) ||
            host in additionalAllowedHosts
        if (!hostAllowed) return false
        if (resolveDns) {
            val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { return false }
            if (addresses.isEmpty() || addresses.any(UrlPolicy::isUnsafeAddress)) return false
        }
        return true
    }

    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun baseDomain(host: String): String = host.lowercase().trimEnd('.').split('.').takeLast(2).joinToString(".")

    private fun decodeJsString(value: String?): String {
        val v = value.orEmpty()
        if (v == "null" || v.length < 2) return ""
        return runCatching {
            val raw = v.removePrefix("\"").removeSuffix("\"")
            raw.replace("\\u003C", "<", true)
                .replace("\\u003E", ">", true)
                .replace("\\u0026", "&", true)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\/", "/")
                .replace("\\\\", "\\")
        }.getOrDefault("")
    }

    companion object {
        private val mutex = Mutex()
        private const val SNIFF_EXTRA_MS = 7_000L
        private val mediaExt = Regex("\\.(?:mp4|m4v|webm|m3u8|mpd|m4s|ts|mov)(?:[?#]|$)", RegexOption.IGNORE_CASE)
        private val imageExt = Regex("\\.(?:jpe?g|png|webp|gif|svg|ico|css|js|woff2?|ttf|vtt|srt)(?:[?#]|$)", RegexOption.IGNORE_CASE)

        /** Запрос плеера за медиа: по расширению, по типичным путям CDN или по заголовку Range (его шлёт <video>). */
        internal fun looksLikeMediaRequest(url: String, headers: Map<String, String>?): Boolean {
            if (imageExt.containsMatchIn(url.substringBefore('#'))) return false
            if (mediaExt.containsMatchIn(url)) return true
            val u = url.lowercase()
            if (u.contains("/get_file/") || u.contains("/videoplayback") || u.contains("/hls/") && u.contains("m3u8")) return true
            return headers?.keys?.any { it.equals("Range", true) } == true
        }

        private val ACTIVATE_SCRIPT = """
            (function() {
              function run(doc) {
                try {
                  var sel = ['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container','.plyr__control--overlaid',
                    '.fp-play','.ytp-large-play-button','.play-btn','.play-button','.btn-play','.big-play','.video-play',
                    '[class*="play-btn"]','[class*="playBtn"]','[class*="PlayButton"]','[aria-label*="Play" i]','[title*="Play" i]',
                    '[title*="Смотреть" i]','[title*="Воспроизвести" i]','#play','.play'];
                  sel.forEach(function(s) { try { doc.querySelectorAll(s).forEach(function(e, i) { if (i < 2) e.click(); }); } catch (e) {} });
                  doc.querySelectorAll('video').forEach(function(v) {
                    try { v.muted = true; v.setAttribute('playsinline', ''); var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {}
                  });
                } catch (e) {}
              }
              run(document);
              try { document.querySelectorAll('iframe').forEach(function(f) { try { if (f.contentDocument) run(f.contentDocument); } catch (e) {} }); } catch (e) {}
            })();
        """.trimIndent()

        private val RUNTIME_DOM_SCRIPT = """
            (function() {
              var html = document.documentElement ? document.documentElement.outerHTML : '';
              var urls = [];
              function add(v) {
                if (typeof v !== 'string') return;
                v = v.trim();
                if (!/^https:\/\//i.test(v)) return;
                if (urls.indexOf(v) < 0) urls.push(v);
              }
              try {
                document.querySelectorAll('video,audio,source,iframe').forEach(function(el) {
                  add(el.currentSrc || '');
                  add(el.src || '');
                });
              } catch (e) {}
              try {
                if (window.performance && performance.getEntriesByType) {
                  performance.getEntriesByType('resource').forEach(function(entry) {
                    var u = entry && entry.name ? entry.name : '';
                    if (/\.(?:mp4|m4v|webm|m3u8|mpd)(?:[?#]|$)/i.test(u) ||
                        /(?:manifest\/video\.m3u8|master\.m3u8|playlist\.m3u8)/i.test(u)) add(u);
                  });
                }
              } catch (e) {}
              return html + '\n<!--NOODLE_RUNTIME_MEDIA\n' + urls.slice(0, 32).join('\n') + '\n-->';
            })();
        """.trimIndent()
    }
}

data class WebExtractResult(
    val success: Boolean,
    val finalUrl: String = "",
    val html: String = "",
    val errorCode: String = "",
    val errorMessage: String = "",
    /** Адреса потоков, которые страница реально запросила (в порядке появления). */
    val capturedMedia: List<String> = emptyList(),
)

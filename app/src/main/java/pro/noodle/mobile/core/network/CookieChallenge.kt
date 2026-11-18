package pro.noodle.mobile.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Решатель «JS-проверки браузера»: страница отдаёт крошечный скрипт, который сам ставит cookie
 * (`document.cookie = "hdsh=..."`) и перезагружает страницу. Обычный HTTP-клиент JS не выполняет и
 * видит одну и ту же заглушку на любой адрес.
 *
 * 1) статический разбор: если cookie — строковый литерал, ставим его без запуска JS;
 * 2) WebView: открываем страницу в скрытом WebView с тем же User-Agent, ждём перезагрузки и забираем cookie.
 * Полученные cookie кладутся в cookie-jar источника, дальше работает обычный HTTP.
 */
object CookieChallenge {
    @Volatile private var appContext: Context? = null
    private val webMutex = Mutex()
    private val attempts = ConcurrentHashMap<String, Long>()

    fun attach(context: Context) { appContext = context.applicationContext }

    private val cookieAssign = Regex("document\\.cookie\\s*=\\s*((?:(?:\"[^\"\\n]*\"|'[^'\\n]*')\\s*\\+?\\s*)+)", RegexOption.IGNORE_CASE)
    private val literal = Regex("\"([^\"\\n]*)\"|'([^'\\n]*)'")

    fun looksLikeChallenge(text: String): Boolean {
        if (text.isBlank() || text.length > 20_000) return false
        val l = text.lowercase()
        val anchors = Regex("<a\\s").findAll(l).count()
        val cookieScript = "document.cookie" in l &&
            ("location.reload" in l || "location.href" in l || "location.replace" in l || "window.location" in l) && anchors < 6
        val interstitial = anchors < 6 && ("checking your browser" in l || "just a moment" in l || "ddos-guard" in l || "проверка браузера" in l)
        return cookieScript || interstitial
    }

    /** Cookie, заданные строковыми литералами: `document.cookie="a=b; path=/"`. */
    fun staticCookies(text: String): List<Pair<String, String>> =
        cookieAssign.findAll(text).mapNotNull { m ->
            val joined = literal.findAll(m.groupValues[1]).joinToString("") { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val pair = joined.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank() || name.contains(' ')) null else name to value
        }.toList()

    /** Не чаще одной попытки на (источник, хост) за 90 с, чтобы не зациклиться на недоступной защите. */
    fun shouldTry(sourceKey: String, host: String): Boolean {
        val key = "$sourceKey|$host"
        val now = System.currentTimeMillis()
        val last = attempts[key] ?: 0L
        if (now - last < 90_000L) return false
        attempts[key] = now
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveWithWebView(url: String, timeoutMs: Long = 16_000): List<Pair<String, String>>? {
        val ctx = appContext ?: return null
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp = false, resolveDns = true)
        if (!decision.allowed) return null
        val host = runCatching { URI(decision.normalizedUrl).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return null
        val family = host.split('.').takeLast(2).joinToString(".")
        fun sameFamily(u: String): Boolean {
            val h = runCatching { URI(u).host.orEmpty() }.getOrDefault("")
            return h == family || h.endsWith(".$family")
        }
        return webMutex.withLock {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine<List<Pair<String, String>>?> { cont ->
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.setAcceptCookie(true)
                        val web = WebView(ctx)
                        web.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = BuildIdentity.USER_AGENT
                            allowFileAccess = false
                            allowContentAccess = false
                            loadsImagesAutomatically = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }
                        cm.setAcceptThirdPartyCookies(web, false)
                        var done = false
                        fun finish(result: List<Pair<String, String>>?) {
                            if (done) return
                            done = true
                            runCatching { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() }
                            cm.removeAllCookies(null)
                            cm.flush()
                            if (cont.isActive) cont.resume(result)
                        }
                        web.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                                !sameFamily(request.url.toString())

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                                if (sameFamily(request.url.toString())) null
                                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

                            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                                handler?.cancel()
                                finish(null)
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String) {
                                view.postDelayed({
                                    if (done) return@postDelayed
                                    view.evaluateJavascript("(function(){return document.documentElement?document.documentElement.outerHTML:'';})()") { raw ->
                                        if (done) return@evaluateJavascript
                                        val html = raw.orEmpty().replace("\\u003C", "<").replace("\\n", "\n").replace("\\\"", "\"")
                                        val jar = cm.getCookie(pageUrl).orEmpty()
                                        // Успех: заглушки больше нет, а cookie выданы.
                                        if (jar.isNotBlank() && !looksLikeChallenge(html)) {
                                            finish(parse(jar))
                                        }
                                    }
                                }, 700L)
                            }
                        }
                        cont.invokeOnCancellation { runCatching { web.post { finish(null) } } }
                        web.loadUrl(decision.normalizedUrl)
                    }
                }
            }
        }
    }

    private fun parse(header: String): List<Pair<String, String>> =
        header.split(';').mapNotNull { part ->
            val n = part.substringBefore('=', "").trim()
            val v = part.substringAfter('=', "").trim()
            if (n.isBlank()) null else n to v
        }
}

package pro.noodle.mobile.core.finder

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

data class WebPage(val html: String, val finalUrl: String, val error: String = "")

/**
 * Настоящий скрытый WebView для поисковиков, которые не отдают выдачу обычному HTTP-клиенту (Google, Яндекс).
 * В отличие от песочницы адаптеров, свободно ходит по редиректам внутри домена поисковика (consent/проверки),
 * сам нажимает «Принять», держит cookie между страницами и честно возвращает причину отказа.
 */
class SearchWebFetcher(private val context: Context) {
    private val mutex = Mutex()

    suspend fun fetch(url: String, timeoutMs: Long = 28_000): WebPage = mutex.withLock {
        withTimeoutOrNull(timeoutMs) { load(url) } ?: WebPage("", url, "таймаут ${timeoutMs / 1000} с")
    }

    private fun hostOk(h: String): Boolean {
        val x = h.lowercase()
        return x.contains("google.") || x.contains("gstatic.") || x.contains("yandex.") || x == "ya.ru" || x.endsWith(".ya.ru") || x.contains("yastatic.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String): WebPage = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val web = WebView(context.applicationContext)
            var done = false
            var consentTries = 0
            var polls = 0
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            fun finish(p: WebPage) {
                if (done) return
                done = true
                runCatching { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() }
                if (cont.isActive) cont.resume(p)
            }
            fun grab(view: WebView) {
                view.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { enc ->
                    val html = runCatching { JSONTokener(enc.orEmpty()).nextValue() as? String }.getOrNull().orEmpty()
                    val cur = view.url.orEmpty()
                    val host = runCatching { java.net.URI(cur).host.orEmpty() }.getOrDefault("")
                    if (host.startsWith("consent.") && consentTries++ < 3) {
                        view.evaluateJavascript(CONSENT_JS, null)
                        view.postDelayed({ if (!done) grab(view) }, 1500)
                        return@evaluateJavascript
                    }
                    // Выдача часто дорисовывается скриптом — ждём, пока DOM «созреет» (до ~4 с).
                    if (html.length < 6_000 && polls++ < 4) { view.postDelayed({ if (!done) grab(view) }, 900); return@evaluateJavascript }
                    finish(if (html.isBlank()) WebPage("", cur, "пустая страница") else WebPage(html, cur))
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val u = request.url
                    return !(u.scheme == "https" && hostOk(u.host.orEmpty()))
                }
                override fun onPageFinished(view: WebView, u: String) {
                    if (done) return
                    view.postDelayed({ if (!done) grab(view) }, 900)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) finish(WebPage("", url, "сеть: ${error?.description}"))
                }
            }
            cont.invokeOnCancellation { runCatching { web.post { if (!done) { done = true; web.stopLoading(); web.destroy() } } } }
            web.loadUrl(url)
        }
    }

    private companion object {
        const val CONSENT_JS = "(function(){var b=[].slice.call(document.querySelectorAll('button,input[type=submit],a'));" +
            "var t=b.filter(function(x){var s=(x.innerText||x.value||'').toLowerCase();return /принять все|accept all|согласен|agree|i agree/.test(s)})[0];" +
            "if(t){t.click();return 1}var f=document.querySelector('form[action*=\"consent\"]');if(f){f.submit();return 2}return 0})()"
    }
}

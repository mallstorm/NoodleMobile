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
 */
class SandboxedWebExtractor(private val context: Context) {
    suspend fun extract(url: String, adapter: AdapterDefinition, timeoutMs: Long = 12_000): WebExtractResult = mutex.withLock {
        if (!adapter.allowJavaScript) return WebExtractResult(false, errorCode="WEB_JS_NOT_ALLOWED", errorMessage="SNP не разрешает JavaScript.")
        if (!allowed(url, adapter, resolveDns=true)) return WebExtractResult(false,errorCode="WEB_URL_BLOCKED",errorMessage="URL не прошёл SNP/security policy.")
        try { withTimeout(timeoutMs) { extractOnMain(url, adapter) } }
        catch (_: TimeoutCancellationException) { WebExtractResult(false,errorCode="WEB_TIMEOUT",errorMessage="JavaScript extraction превысил ${timeoutMs} мс.") }
        catch (e: Exception) { WebExtractResult(false,errorCode="WEB_EXTRACT_ERROR",errorMessage=e.message?:e.javaClass.simpleName) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractOnMain(url:String, adapter:AdapterDefinition):WebExtractResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val cookies=CookieManager.getInstance(); cookies.removeAllCookies(null); cookies.flush()
            val web=WebView(context.applicationContext)
            web.settings.apply {
                javaScriptEnabled=true
                domStorageEnabled=false
                databaseEnabled=false
                allowFileAccess=false
                allowContentAccess=false
                javaScriptCanOpenWindowsAutomatically=false
                setSupportMultipleWindows(false)
                loadsImagesAutomatically=false
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if(Build.VERSION.SDK_INT>=26) safeBrowsingEnabled=true
            }
            CookieManager.getInstance().setAcceptCookie(adapter.allowCookies)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
            var finished=false
            fun close(result:WebExtractResult){if(finished)return;finished=true;runCatching{web.stopLoading();web.loadUrl("about:blank");web.clearHistory();web.clearCache(false);web.destroy()};cookies.setAcceptCookie(false);cookies.removeAllCookies(null);cookies.flush();if(cont.isActive)cont.resume(result)}
            web.webChromeClient=object:WebChromeClient(){
                override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin:String?,callback:GeolocationPermissions.Callback?){callback?.invoke(origin,false,false)}
            }
            web.webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest):Boolean = !allowed(request.url.toString(),adapter,resolveDns=true)
                override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest):WebResourceResponse? {
                    return if(allowed(request.url.toString(),adapter,resolveDns=true)) null else blockedResponse()
                }
                override fun onReceivedSslError(view:WebView?,handler:SslErrorHandler?,error:android.net.http.SslError?){handler?.cancel();close(WebExtractResult(false,errorCode="WEB_TLS_ERROR",errorMessage="TLS/SSL ошибка WebView; обход сертификата запрещён."))}
                override fun onPageFinished(view:WebView,url:String){
                    if(finished||!allowed(url,adapter,resolveDns=true)){close(WebExtractResult(false,errorCode="WEB_NAV_BLOCKED",errorMessage="Финальная страница вышла за SNP policy."));return}
                    // Give bounded client-side player bootstrap code a brief chance to materialize
                    // source/config elements after DOM load; the outer withTimeout remains authoritative.
                    view.postDelayed({
                        if (finished) return@postDelayed
                        view.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()") { encoded ->
                            val html=decodeJsString(encoded)
                            close(if(html.isNotBlank())WebExtractResult(true,finalUrl=url,html=html) else WebExtractResult(false,errorCode="WEB_EMPTY_DOM",errorMessage="WebView не вернул DOM."))
                        }
                    }, 650L)
                }
            }
            cont.invokeOnCancellation { runCatching { web.post { if(!finished){finished=true;web.stopLoading();web.destroy();cookies.setAcceptCookie(false);cookies.removeAllCookies(null);cookies.flush()} } } }
            web.loadUrl(url, mapOf("User-Agent" to "${BuildIdentity.USER_AGENT} WebExtractor"))
        }
    }

    private fun allowed(raw:String,adapter:AdapterDefinition,resolveDns:Boolean):Boolean{
        if(raw.equals("about:blank",true))return true
        val d=UrlPolicy.evaluateNavigation(raw,allowHttp=false,resolveDns=false);if(!d.allowed)return false
        val host=runCatching{Uri.parse(d.normalizedUrl).host.orEmpty()}.getOrDefault("");if(host.isBlank())return false
        if(adapter.allowedHosts.isNotEmpty()&&!UrlPolicy.isHostAllowed(host,adapter.allowedHosts))return false
        if(resolveDns){val addresses=runCatching{InetAddress.getAllByName(host).toList()}.getOrElse{return false};if(addresses.isEmpty()||addresses.any(UrlPolicy::isUnsafeAddress))return false}
        return true
    }
    private fun blockedResponse()=WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
    private fun decodeJsString(value:String?):String{
        val v=value.orEmpty();if(v=="null"||v.length<2)return "";return runCatching {
            val raw=v.removePrefix("\"").removeSuffix("\"")
            raw.replace("\\u003C","<",true).replace("\\u003E",">",true).replace("\\u0026","&",true).replace("\\\"","\"").replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").replace("\\\\","\\")
        }.getOrDefault("")
    }
    companion object { private val mutex=Mutex() }
}

data class WebExtractResult(val success:Boolean,val finalUrl:String="",val html:String="",val errorCode:String="",val errorMessage:String="")

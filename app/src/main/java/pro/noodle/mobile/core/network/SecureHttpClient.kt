package pro.noodle.mobile.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class NetworkPolicy(
    val allowedHosts: List<String>,
    val allowHttp: Boolean = false,
    val allowCookies: Boolean = false,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val maxRedirects: Int = 5,
)

data class BinaryFetchResult(
    val success: Boolean,
    val finalUrl: String,
    val statusCode: Int = 0,
    val contentType: String = "",
    val bytes: ByteArray = ByteArray(0),
    val errorCode: String = "",
    val errorMessage: String = "",
)

data class TextFetchResult(
    val success: Boolean,
    val finalUrl: String,
    val statusCode: Int = 0,
    val contentType: String = "",
    val text: String = "",
    val errorCode: String = "",
    val errorMessage: String = "",
)

/**
 * Shared per-session HTTP boundary for search, resolver and Media3.
 * Cookies are isolated by sourceKey and exist only when the SNP explicitly allows them.
 */
class SecureHttpClient {
    companion object {
        /** Process-local, source-isolated cookie sessions shared by search/resolver/player/download. */
        private val cookieStores = ConcurrentHashMap<String, MemoryCookieJar>()

        /** Reuse TCP/TLS/HTTP2 connections across search, resolver, thumbnails and Media3. */
        private val sharedConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

        /** Одна JS-проверка решается за раз (WebView и CookieManager общие на процесс). */
        private val challengeLock = kotlinx.coroutines.sync.Mutex()
    }

    fun fetchTextBlocking(url: String, policy: NetworkPolicy, sourceKey: String = "default"): TextFetchResult =
        kotlinx.coroutines.runBlocking { fetchText(url, policy, sourceKey) }

    /**
     * Загрузка страницы. Если сайт отдал «JS-проверку браузера» (скрипт ставит cookie и перезагружает страницу),
     * проверка решается автоматически (разбор скрипта или скрытый WebView), cookie попадают в jar источника,
     * и страница запрашивается ещё раз. Работает только когда источнику разрешены cookie.
     */
    suspend fun fetchText(url: String, policy: NetworkPolicy, sourceKey: String = "default"): TextFetchResult {
        val first = fetchTextOnce(url, policy, sourceKey)
        if (!policy.allowCookies || !CookieChallenge.looksLikeChallenge(first.text)) return first
        return challengeLock.withLock {
            // Другой параллельный запрос мог уже решить проверку.
            var res = fetchTextOnce(url, policy, sourceKey)
            if (!CookieChallenge.looksLikeChallenge(res.text)) return@withLock res
            val at = res.finalUrl.ifBlank { url }
            val host = runCatching { URI(at).host.orEmpty() }.getOrDefault("")
            if (host.isNotBlank() && CookieChallenge.shouldTry(sourceKey, host)) {
                val literal = CookieChallenge.staticCookies(res.text)
                if (literal.isNotEmpty()) {
                    importCookies(sourceKey, at, literal)
                    res = fetchTextOnce(url, policy, sourceKey)
                    if (!CookieChallenge.looksLikeChallenge(res.text)) return@withLock res
                }
                val solved = CookieChallenge.solveWithWebView(at)
                if (!solved.isNullOrEmpty()) {
                    importCookies(sourceKey, at, solved)
                    res = fetchTextOnce(url, policy, sourceKey)
                    if (!CookieChallenge.looksLikeChallenge(res.text)) return@withLock res
                }
            }
            TextFetchResult(
                false, res.finalUrl, res.statusCode, res.contentType, res.text,
                "SITE_JS_CHALLENGE", "Сайт отдаёт JS-проверку браузера (cookie) и не пускает обычные запросы; автоматически решить её не удалось.",
            )
        }
    }

    /** Кладёт cookie в jar источника (используется решателем JS-проверки). */
    fun importCookies(sourceKey: String, url: String, cookies: List<Pair<String, String>>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val jar = cookieStores.getOrPut(sourceKey) { MemoryCookieJar() }
        val expires = System.currentTimeMillis() + 4 * 3600_000L
        jar.add(httpUrl.host, cookies.map { (n, v) -> Cookie.Builder().name(n).value(v).hostOnlyDomain(httpUrl.host).path("/").expiresAt(expires).build() })
    }

    private suspend fun fetchTextOnce(url: String, policy: NetworkPolicy, sourceKey: String): TextFetchResult = withContext(Dispatchers.IO) {
        val client = clientFor(policy, sourceKey, followRedirects = false)
        var current = url
        repeat(policy.maxRedirects + 1) { redirectNo ->
            val decision = UrlPolicy.evaluateNavigation(current, allowHttp = policy.allowHttp, resolveDns = true)
            if (!decision.allowed) return@withContext TextFetchResult(false, current, errorCode = decision.errorCode, errorMessage = decision.reason)
            val host = runCatching { URI(current).host.orEmpty() }.getOrDefault("")
            if (!hostAllowed(host, policy)) {
                return@withContext TextFetchResult(false, current, errorCode = "URL_HOST_NOT_ALLOWED", errorMessage = "Хост $host не разрешён SNP-политикой.")
            }
            val request = Request.Builder().url(current)
                .header("User-Agent", BuildIdentity.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,application/vnd.apple.mpegurl,application/dash+xml,*/*;q=0.8")
                .header("Accept-Language", BuildIdentity.ACCEPT_LANGUAGE)
                .build()
            val response = try { client.newCall(request).execute() } catch (e: Exception) {
                return@withContext TextFetchResult(false, current, errorCode = "HTTP_IO_ERROR", errorMessage = e.message ?: e.javaClass.simpleName)
            }
            response.use { r ->
                if (r.code in 300..399) {
                    val location = r.header("Location").orEmpty()
                    if (location.isBlank()) return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_REDIRECT_NO_LOCATION", errorMessage = "Redirect без Location.")
                    if (redirectNo >= policy.maxRedirects) return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_TOO_MANY_REDIRECTS", errorMessage = "Превышен лимит redirect.")
                    val next = runCatching { URI(current).resolve(location).toString() }.getOrDefault("")
                    if (next.isBlank()) return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_REDIRECT_INVALID", errorMessage = "Некорректный redirect URL.")
                    if (UrlPolicy.isHttpsDowngrade(current, next) && !policy.allowHttp) return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTPS_DOWNGRADE_BLOCKED", errorMessage = "HTTPS→HTTP redirect запрещён.")
                    current = next
                    return@repeat
                }
                val body = r.body ?: return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_EMPTY_BODY", errorMessage = "Пустой HTTP body.")
                val declared = body.contentLength()
                if (declared > policy.maxResponseBytes) return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_BODY_TOO_LARGE", errorMessage = "Ответ превышает лимит ${policy.maxResponseBytes} байт.")
                val input = body.byteStream()
                val out = ByteArrayOutputStream(minOf(policy.maxResponseBytes, 64 * 1024))
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > policy.maxResponseBytes) return@withContext TextFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_BODY_TOO_LARGE", errorMessage = "Ответ превышает лимит ${policy.maxResponseBytes} байт.")
                    out.write(buf, 0, n)
                }
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val text = out.toByteArray().toString(charset)
                if (!r.isSuccessful) return@withContext TextFetchResult(false, current, statusCode = r.code, contentType = body.contentType()?.toString().orEmpty(), text = text, errorCode = "HTTP_${r.code}", errorMessage = "HTTP ${r.code}")
                return@withContext TextFetchResult(true, current, r.code, body.contentType()?.toString().orEmpty(), text)
            }
        }
        TextFetchResult(false, current, errorCode = "HTTP_REDIRECT_LOOP", errorMessage = "Redirect loop.")
    }

    /**
     * Произвольный HTTP-запрос (GET/POST, свои заголовки, form/raw body) для разведки AJAX-эндпоинтов и JSON API.
     * Те же политики безопасности: только публичные хосты, https, лимит размера, cookie по ключу источника.
     */
    suspend fun requestText(
        url: String,
        policy: NetworkPolicy,
        sourceKey: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        rawBody: String = "",
        contentType: String = "",
    ): TextFetchResult = withContext(Dispatchers.IO) {
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp = policy.allowHttp, resolveDns = true)
        if (!decision.allowed) return@withContext TextFetchResult(false, url, errorCode = decision.errorCode, errorMessage = decision.reason)
        val client = clientFor(policy, sourceKey, followRedirects = true, anyPublicHost = true)
        val m = method.uppercase().let { if (it in setOf("GET", "POST", "HEAD")) it else "GET" }
        val b = Request.Builder().url(url)
            .header("User-Agent", BuildIdentity.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,text/javascript,*/*;q=0.8")
            .header("Accept-Language", BuildIdentity.ACCEPT_LANGUAGE)
        headers.forEach { (k, v) -> if (k.isNotBlank() && !k.equals("host", true) && !k.equals("content-length", true)) b.header(k, v) }
        when (m) {
            "POST" -> {
                val body = if (form.isNotEmpty()) FormBody.Builder().also { fb -> form.forEach { (k, v) -> fb.add(k, v) } }.build()
                else rawBody.toRequestBody((contentType.ifBlank { "application/x-www-form-urlencoded; charset=UTF-8" }).toMediaTypeOrNull())
                b.post(body)
            }
            "HEAD" -> b.head()
            else -> b.get()
        }
        val response = try { client.newCall(b.build()).execute() } catch (e: Exception) {
            return@withContext TextFetchResult(false, url, errorCode = "HTTP_IO_ERROR", errorMessage = e.message ?: e.javaClass.simpleName)
        }
        response.use { r ->
            val body = r.body
            val limit = policy.maxResponseBytes
            val bytes = if (body == null) ByteArray(0) else {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                val input = body.byteStream()
                var total = 0
                while (total < limit) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n }
                out.toByteArray()
            }
            val charset = body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            TextFetchResult(r.isSuccessful, r.request.url.toString(), r.code, body?.contentType()?.toString().orEmpty(), bytes.toString(charset),
                if (r.isSuccessful) "" else "HTTP_${r.code}", if (r.isSuccessful) "" else "HTTP ${r.code}")
        }
    }

    suspend fun fetchBytes(
        url: String,
        policy: NetworkPolicy,
        sourceKey: String = "default",
        accept: String = "application/zip,application/json,application/octet-stream,*/*;q=0.4",
        referer: String = "",
    ): BinaryFetchResult = withContext(Dispatchers.IO) {
        val client = clientFor(policy, sourceKey, followRedirects = false)
        var current = url
        repeat(policy.maxRedirects + 1) { redirectNo ->
            val decision = UrlPolicy.evaluateNavigation(current, allowHttp = policy.allowHttp, resolveDns = true)
            if (!decision.allowed) return@withContext BinaryFetchResult(false, current, errorCode = decision.errorCode, errorMessage = decision.reason)
            val host = runCatching { URI(current).host.orEmpty() }.getOrDefault("")
            if (!hostAllowed(host, policy)) return@withContext BinaryFetchResult(false, current, errorCode = "URL_HOST_NOT_ALLOWED", errorMessage = "Хост $host не разрешён SNP-политикой.")
            val requestBuilder = Request.Builder().url(current)
                .header("User-Agent", BuildIdentity.USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", BuildIdentity.ACCEPT_LANGUAGE)
            if (referer.isNotBlank()) requestBuilder.header("Referer", referer)
            val request = requestBuilder.build()
            val response = try { client.newCall(request).execute() } catch (e: Exception) {
                return@withContext BinaryFetchResult(false, current, errorCode = "HTTP_IO_ERROR", errorMessage = e.message ?: e.javaClass.simpleName)
            }
            response.use { r ->
                if (r.code in 300..399) {
                    val location = r.header("Location").orEmpty()
                    if (location.isBlank()) return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_REDIRECT_NO_LOCATION", errorMessage = "Redirect без Location.")
                    if (redirectNo >= policy.maxRedirects) return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_TOO_MANY_REDIRECTS", errorMessage = "Превышен лимит redirect.")
                    val next = runCatching { URI(current).resolve(location).toString() }.getOrDefault("")
                    if (next.isBlank()) return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_REDIRECT_INVALID", errorMessage = "Некорректный redirect URL.")
                    if (UrlPolicy.isHttpsDowngrade(current, next) && !policy.allowHttp) return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTPS_DOWNGRADE_BLOCKED", errorMessage = "HTTPS→HTTP redirect запрещён.")
                    current = next
                    return@repeat
                }
                val body = r.body ?: return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_EMPTY_BODY", errorMessage = "Пустой HTTP body.")
                val declared = body.contentLength()
                if (declared > policy.maxResponseBytes) return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_BODY_TOO_LARGE", errorMessage = "Ответ превышает лимит ${policy.maxResponseBytes} байт.")
                val input = body.byteStream()
                val out = ByteArrayOutputStream(minOf(policy.maxResponseBytes, 64 * 1024))
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > policy.maxResponseBytes) return@withContext BinaryFetchResult(false, current, statusCode = r.code, errorCode = "HTTP_BODY_TOO_LARGE", errorMessage = "Ответ превышает лимит ${policy.maxResponseBytes} байт.")
                    out.write(buf, 0, n)
                }
                val bytes = out.toByteArray()
                if (!r.isSuccessful) return@withContext BinaryFetchResult(false, current, r.code, body.contentType()?.toString().orEmpty(), bytes, "HTTP_${r.code}", "HTTP ${r.code}")
                return@withContext BinaryFetchResult(true, current, r.code, body.contentType()?.toString().orEmpty(), bytes)
            }
        }
        BinaryFetchResult(false, current, errorCode = "HTTP_REDIRECT_LOOP", errorMessage = "Redirect loop.")
    }

    /**
     * OkHttp client used by Media3. Unlike fetchText(), redirects are allowed, but every
     * request in the redirect/manifest/segment chain is revalidated by PolicyInterceptor.
     */
    fun streamingClient(policy: NetworkPolicy, sourceKey: String): OkHttpClient =
        clientFor(policy, sourceKey, followRedirects = true, anyPublicHost = true).newBuilder()
            .addInterceptor { chain ->
                // Referer как у браузера: на чужой хост (CDN) — только origin страницы. Без этого часть CDN отвечает «Error. Code 4».
                val req = chain.request()
                val ref = req.header("Referer")
                if (ref.isNullOrBlank()) chain.proceed(req)
                else chain.proceed(req.newBuilder().header("Referer", pro.noodle.mobile.core.resolver.browserReferer(ref, req.url.toString())).build())
            }.build()

    private fun clientFor(policy: NetworkPolicy, sourceKey: String, followRedirects: Boolean, anyPublicHost: Boolean = false): OkHttpClient {
        val cookies = if (policy.allowCookies) cookieStores.getOrPut(sourceKey) { MemoryCookieJar() } else CookieJar.NO_COOKIES
        return OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .dns(SafeDns)
            .cookieJar(cookies)
            .addNetworkInterceptor(PolicyInterceptor(policy, anyPublicHost))
            .build()
    }

    private fun hostAllowed(host: String, policy: NetworkPolicy): Boolean =
        policy.allowedHosts.isEmpty() || UrlPolicy.isHostAllowed(host, policy.allowedHosts)

    private class PolicyInterceptor(private val policy: NetworkPolicy, private val anyPublicHost: Boolean = false) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val url = chain.request().url
            val decision = UrlPolicy.evaluateNavigation(url.toString(), allowHttp = policy.allowHttp, resolveDns = false)
            if (!decision.allowed) throw IOException("${decision.errorCode}: ${decision.reason}")
            // Media CDNs redirect signed get_file/segment URLs to storage hosts nobody can list in advance;
            // https + public-address rules above still apply to every hop.
            if (!anyPublicHost && policy.allowedHosts.isNotEmpty() && !UrlPolicy.isHostAllowed(url.host, policy.allowedHosts)) {
                throw IOException("URL_HOST_NOT_ALLOWED: ${url.host}")
            }
            return chain.proceed(chain.request())
        }
    }
}

private object SafeDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(UrlPolicy::isUnsafeAddress)) {
            throw java.net.UnknownHostException("Unsafe/private DNS target blocked: $hostname")
        }
        // IPv4 первым: страница и видео-CDN должны ходить с ОДНОГО IP. Подписанные ссылки (secure_link)
        // часто привязаны к IP, а у сайта за Cloudflare есть IPv6, у CDN — нет: без этого CDN отвечает «Error. Code 4».
        return addresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
    }
}

private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = add(url.host, cookies)

    /** Новые cookie заменяют только одноимённые, остальные (в том числе выданные решателем JS-проверки) сохраняются. */
    fun add(host: String, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val merged = LinkedHashMap<String, Cookie>()
        store[host].orEmpty().forEach { merged[it.name + "|" + it.path] = it }
        cookies.forEach { c ->
            if (c.persistent && c.expiresAt <= now) merged.remove(c.name + "|" + c.path) else merged[c.name + "|" + c.path] = c
        }
        store[host] = merged.values.toMutableList()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host].orEmpty().filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
}

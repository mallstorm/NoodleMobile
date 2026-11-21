package pro.noodle.mobile.core.studio

import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Общие утилиты подписочных клиентов (ChatGPT / Grok): JSON-запросы и потоковый Responses API. */
internal object SubHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun json(request: Request, label: String): JSONObject {
        client.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("$label: ${errorText(raw).ifBlank { "HTTP ${r.code}" }} (HTTP ${r.code})")
            return runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("$label: некорректный JSON") }
        }
    }

    fun errorText(raw: String): String = runCatching {
        val j = JSONObject(raw)
        when (val e = j.opt("error")) {
            is JSONObject -> e.optString("message").ifBlank { e.optString("error_description") }.ifBlank { e.optString("code") }
            is String -> e
            else -> j.optString("error_description").ifBlank { j.optString("message") }
        }
    }.getOrDefault(raw.take(200)).take(260)

    /** POST /responses со stream=true; возвращает собранный текст ответа. */
    suspend fun streamResponses(url: String, body: JSONObject, headers: (Request.Builder) -> Unit, label: String): String {
        val b = Request.Builder().url(url).header("Accept", "text/event-stream").post(body.put("stream", true).toString().toRequestBody(json))
        headers(b)
        val out = StringBuilder()
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) {
                val raw = r.body?.string().orEmpty()
                throw IllegalStateException("$label: ${errorText(raw).ifBlank { "HTTP ${r.code}" }} (HTTP ${r.code})")
            }
            val reader = r.body?.charStream()?.buffered() ?: throw IllegalStateException("$label: пустой ответ")
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val ev = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (ev.has("error") && !ev.isNull("error")) throw IllegalStateException("$label: ${errorText(payload)}")
                when (ev.optString("type")) {
                    "response.output_text.delta" -> out.append(ev.optString("delta"))
                    "response.failed" -> throw IllegalStateException("$label: ${ev.optJSONObject("response")?.optJSONObject("error")?.optString("message").orEmpty().take(260)}")
                    "response.completed" -> if (out.isEmpty()) {
                        // Часть бэкендов не шлёт delta — берём финальный текст.
                        val output = ev.optJSONObject("response")?.optJSONArray("output") ?: JSONArray()
                        for (i in 0 until output.length()) {
                            val parts = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (k in 0 until parts.length()) out.append(parts.optJSONObject(k)?.optString("text").orEmpty())
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    fun jwt(token: String): JSONObject = runCatching {
        val p = token.split('.').getOrNull(1).orEmpty()
        JSONObject(String(Base64.decode(p + "=".repeat((4 - p.length % 4) % 4), Base64.URL_SAFE), Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    fun randomToken(bytes: Int): String = Base64.encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** «Sign in with ChatGPT»: OAuth в браузере с возвратом на 127.0.0.1 (как в GitHubble). Токены работают через api.openai.com/v1/responses. */
internal class StudioChatGptClient {
    class Login(val authUrl: String, val redirectUri: String, val state: String, val nonce: String, val verifier: String, val server: ServerSocket)

    data class Creds(
        val clientId: String, val accessToken: String, val refreshToken: String, val idToken: String,
        val expiresAtMs: Long, val email: String, val plan: String, val subject: String,
    ) {
        fun expiresSoon() = expiresAtMs > 0 && expiresAtMs <= System.currentTimeMillis() + 5 * 60_000L
        fun toJson(): String = JSONObject().put("client_id", clientId).put("access_token", accessToken).put("refresh_token", refreshToken)
            .put("id_token", idToken).put("expires_at_ms", expiresAtMs).put("email", email).put("plan", plan).put("subject", subject).toString()
        companion object {
            fun fromJson(raw: String): Creds? = runCatching {
                val j = JSONObject(raw)
                Creds(j.optString("client_id"), j.getString("access_token"), j.optString("refresh_token"), j.optString("id_token"),
                    j.optLong("expires_at_ms"), j.optString("email"), j.optString("plan"), j.optString("subject"))
            }.getOrNull()
        }
    }

    fun begin(hostId: String): Login {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = LOGIN_TIMEOUT_MS.toInt() }
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        val state = SubHttp.randomToken(32); val nonce = SubHttp.randomToken(32); val verifier = SubHttp.randomToken(64)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val url = "$AUTH/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", DYNAMIC_CLIENT_ID).addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", "openid profile email offline_access resource.invoke $DIRECT_SCOPE")
            .addQueryParameter("resource", RESOURCE).addQueryParameter("state", state).addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", challenge)
            .addQueryParameter("agent_name_hint", "NoodleMobile").build().toString()
        return Login(url, redirect, state, nonce, verifier, server)
    }

    fun cancel(l: Login) { runCatching { l.server.close() } }

    fun await(login: Login): Creds {
        try {
            val cb = receive(login)
            if (cb["state"] != login.state) error("ChatGPT OAuth: state не совпал")
            cb["error"]?.takeIf { it.isNotBlank() }?.let { error("ChatGPT OAuth: ${cb["error_description"].orEmpty().ifBlank { it }}") }
            val code = cb["code"].orEmpty().ifBlank { error("ChatGPT OAuth не вернул authorization code") }
            val clientId = cb["client_id"].orEmpty().takeIf { it.startsWith("oaiapp_") } ?: error("ChatGPT не вернул выданный client_id")
            val form = FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
                .add("code_verifier", login.verifier).add("redirect_uri", login.redirectUri).add("resource", RESOURCE).build()
            val j = SubHttp.json(Request.Builder().url("$AUTH/api/accounts/oauth/token").header("Accept", "application/json").post(form).build(), "ChatGPT token")
            return parse(j, clientId, null)
        } finally { cancel(login) }
    }

    fun refresh(c: Creds): Creds {
        require(c.refreshToken.isNotBlank() && c.clientId.startsWith("oaiapp_")) { "Нужен повторный вход в ChatGPT" }
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("client_id", c.clientId).add("refresh_token", c.refreshToken).add("resource", RESOURCE).build()
        val j = SubHttp.json(Request.Builder().url("$AUTH/api/accounts/oauth/token").header("Accept", "application/json").post(form).build(), "ChatGPT refresh")
        return parse(j, c.clientId, c)
    }

    fun models(c: Creds): List<String> {
        val j = SubHttp.json(Request.Builder().url("$API/models").header("Authorization", "Bearer ${c.accessToken}").header("Accept", "application/json").header("User-Agent", UA).get().build(), "ChatGPT models")
        val arr = j.optJSONArray("models") ?: j.optJSONArray("data") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val vis = o.optString("visibility")
            if (vis.isNotBlank() && vis != "list") return@mapNotNull null
            o.optString("slug").ifBlank { o.optString("id") }.takeIf { it.isNotBlank() }
        }.distinct()
        if (ids.isEmpty()) error("ChatGPT не вернул доступные модели подписки")
        return ids
    }

    suspend fun chat(c: Creds, model: String, messages: List<Pair<String, String>>): String {
        val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val input = JSONArray()
        messages.filter { it.first != "system" }.forEach { (r, t) -> input.put(JSONObject().put("role", if (r == "assistant") "assistant" else "user").put("content", t)) }
        val body = JSONObject().put("model", model).put("store", false).put("instructions", system.ifBlank { "You are a helpful assistant." }).put("input", input)
        return SubHttp.streamResponses("$API/responses", body, { it.header("Authorization", "Bearer ${c.accessToken}").header("User-Agent", UA) }, "ChatGPT")
    }

    private fun parse(j: JSONObject, clientId: String, prev: Creds?): Creds {
        val access = j.optString("access_token").ifBlank { prev?.accessToken.orEmpty() }
        val refresh = j.optString("refresh_token").ifBlank { prev?.refreshToken.orEmpty() }
        val idToken = j.optString("id_token").ifBlank { prev?.idToken.orEmpty() }
        if (access.isBlank() || refresh.isBlank()) error("ChatGPT OAuth не вернул полный набор токенов")
        val scopes = j.optString("scope").split(' ', ',').filter { it.isNotBlank() }
        if (scopes.isNotEmpty() && DIRECT_SCOPE !in scopes) error("ChatGPT: подписка не разрешает такой доступ (нет scope $DIRECT_SCOPE)")
        val claims = SubHttp.jwt(idToken)
        val nested = claims.optJSONObject("https://api.openai.com/auth")
        return Creds(
            clientId, access, refresh, idToken,
            System.currentTimeMillis() + j.optLong("expires_in", 3600L).coerceAtLeast(60L) * 1000L,
            claims.optString("email").ifBlank { prev?.email.orEmpty() },
            nested?.optString("chatgpt_plan_type").orEmpty().ifBlank { prev?.plan.orEmpty() },
            claims.optString("sub").ifBlank { prev?.subject.orEmpty() },
        )
    }

    private fun receive(login: Login): Map<String, String> {
        val socket = try { login.server.accept() } catch (e: Exception) { error("ChatGPT: возврат из браузера не получен (${e.message ?: "таймаут"})") }
        socket.use { c ->
            val first = c.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
            val target = first.split(' ').getOrNull(1).orEmpty()
            val params = runCatching {
                URI("http://127.0.0.1$target").rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { p ->
                    val kv = p.split('=', limit = 2); URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
                }
            }.getOrElse { emptyMap() }
            val ok = params["state"] == login.state && params["error"].isNullOrBlank()
            val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>NoodleMobile</title>" +
                if (ok) "<h2>NoodleMobile подключён к ChatGPT</h2><p>Можно вернуться в приложение.</p>" else "<h2>Вход ChatGPT не завершён</h2><p>Вернитесь в NoodleMobile и повторите вход.</p>"
            val bytes = html.toByteArray(Charsets.UTF_8)
            c.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
            return params
        }
    }

    companion object {
        const val AUTH = "https://auth.openai.com"
        const val API = "https://api.openai.com/v1"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val UA = "NoodleMobile-Android/1.0"
        private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/** xAI / Grok: OAuth device-flow (как в Grok CLI). Запросы идут через cli-chat-proxy.grok.com/v1/responses. */
internal class StudioGrokClient {
    data class Device(val deviceCode: String, val userCode: String, val url: String, val expiresAtMs: Long, val intervalSec: Long, val tokenEndpoint: String)
    data class Creds(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val email: String, val tokenEndpoint: String) {
        fun expiresSoon() = expiresAtMs > 0 && expiresAtMs <= System.currentTimeMillis() + 2 * 60_000L
        fun toJson(): String = JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken).put("expires_at_ms", expiresAtMs).put("email", email).put("token_endpoint", tokenEndpoint).toString()
        companion object {
            fun fromJson(raw: String): Creds? = runCatching {
                val j = JSONObject(raw); Creds(j.getString("access_token"), j.optString("refresh_token"), j.optLong("expires_at_ms"), j.optString("email"), j.optString("token_endpoint"))
            }.getOrNull()
        }
    }

    fun begin(): Device {
        val disc = SubHttp.json(Request.Builder().url(DISCOVERY).header("Accept", "application/json").get().build(), "xAI discovery")
        val deviceEp = xai(disc.optString("device_authorization_endpoint")); val tokenEp = xai(disc.optString("token_endpoint"))
        val r = SubHttp.json(Request.Builder().url(deviceEp).header("Accept", "application/json").post(FormBody.Builder().add("client_id", CLIENT_ID).add("scope", SCOPE).build()).build(), "xAI вход")
        val code = r.optString("device_code"); val user = r.optString("user_code")
        val url = r.optString("verification_uri_complete").ifBlank { r.optString("verification_uri") }
        if (code.isBlank() || url.isBlank()) error("xAI не вернул данные входа")
        return Device(code, user, url, System.currentTimeMillis() + r.optLong("expires_in", 900L).coerceIn(60L, 3600L) * 1000L, r.optLong("interval", 5L).coerceIn(3L, 30L), tokenEp)
    }

    /** null — ещё ждём подтверждения. */
    fun poll(d: Device): Creds? {
        val req = Request.Builder().url(d.tokenEndpoint).header("Accept", "application/json")
            .post(FormBody.Builder().add("grant_type", DEVICE_GRANT).add("device_code", d.deviceCode).add("client_id", CLIENT_ID).build()).build()
        SubHttp.client.newCall(req).execute().use { r ->
            val j = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrElse { error("xAI вернул некорректный ответ") }
            when (j.optString("error")) {
                "authorization_pending", "slow_down" -> return null
                "expired_token" -> error("Код xAI истёк")
                "access_denied" -> error("Вход xAI отклонён")
                "" -> Unit
                else -> error(j.optString("error_description").ifBlank { "xAI: ${j.optString("error")}" })
            }
            return creds(j, d.tokenEndpoint, null)
        }
    }

    fun refresh(c: Creds): Creds {
        require(c.refreshToken.isNotBlank()) { "Нужен повторный вход в Grok" }
        val ep = c.tokenEndpoint.ifBlank { xai(SubHttp.json(Request.Builder().url(DISCOVERY).get().build(), "xAI discovery").optString("token_endpoint")) }
        val j = SubHttp.json(Request.Builder().url(ep).header("Accept", "application/json")
            .post(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", CLIENT_ID).add("refresh_token", c.refreshToken).build()).build(), "xAI refresh")
        return creds(j, ep, c)
    }

    fun models(c: Creds): List<String> {
        val j = SubHttp.json(Request.Builder().url("$BASE/models").also { headers(it, c.accessToken) }.get().build(), "Grok models")
        val arr = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull { i ->
            when (val it = arr.opt(i)) { is String -> it; is JSONObject -> it.optString("id").ifBlank { it.optString("name") }; else -> null }?.takeIf { it.isNotBlank() }
        }.filter { it.startsWith("grok", true) }.distinct().sorted()
        return ids.ifEmpty { FALLBACK }
    }

    suspend fun chat(c: Creds, model: String, messages: List<Pair<String, String>>): String {
        val input = JSONArray()
        messages.forEach { (r, t) -> input.put(JSONObject().put("role", when (r) { "system" -> "developer"; "assistant" -> "assistant"; else -> "user" }).put("content", t)) }
        val body = JSONObject().put("model", model).put("input", input)
        return SubHttp.streamResponses("$BASE/responses", body, { headers(it, c.accessToken) }, "Grok")
    }

    private fun creds(j: JSONObject, ep: String, prev: Creds?): Creds {
        val access = j.optString("access_token").ifBlank { error("xAI не вернул access token") }
        return Creds(access, j.optString("refresh_token").ifBlank { prev?.refreshToken.orEmpty() },
            System.currentTimeMillis() + j.optLong("expires_in", 3600L).coerceAtLeast(60L) * 1000L,
            SubHttp.jwt(j.optString("id_token")).optString("email").ifBlank { prev?.email.orEmpty() }, ep)
    }

    private fun xai(raw: String): String {
        val u = raw.toHttpUrl()
        if (u.scheme != "https" || (u.host != "x.ai" && !u.host.endsWith(".x.ai"))) error("xAI OAuth endpoint вне x.ai")
        return u.toString()
    }

    private fun headers(b: Request.Builder, token: String) {
        b.header("Authorization", "Bearer $token").header("X-XAI-Token-Auth", "xai-grok-cli").header("x-grok-client-version", CLIENT_VERSION)
            .header("User-Agent", "xai-grok-workspace/$CLIENT_VERSION").header("x-grok-client-identifier", "grok-shell").header("x-authenticateresponse", "authenticate-response")
    }

    companion object {
        const val DISCOVERY = "https://auth.x.ai/.well-known/openid-configuration"
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val BASE = "https://cli-chat-proxy.grok.com/v1"
        const val CLIENT_VERSION = "1.0.45"
        val FALLBACK = listOf("grok-4.7", "grok-4.7-build-fast", "grok-4.6", "grok-build-0.1", "grok-4.5", "grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning", "grok-3-mini", "grok-3-mini-fast")
    }
}

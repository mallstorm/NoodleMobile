package pro.noodle.mobile.core.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class StudioAiProvider(val wireName: String, val title: String) {
    LOCAL("local", "Без AI"),
    KILO("kilo", "Kilo Code"),
    CLINE("cline", "Cline"),
    CHATGPT("chatgpt", "ChatGPT"),
    GROK("grok", "Grok");

    companion object {
        fun fromWireName(value: String): StudioAiProvider =
            entries.firstOrNull { it.wireName == value } ?: LOCAL
    }
}

data class StudioAiAccountStatus(
    val provider: StudioAiProvider,
    val connected: Boolean,
    val accountLabel: String = "",
)

data class StudioDeviceLogin(
    val provider: StudioAiProvider,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
)

data class StudioAiReply(
    val json: String,
    val model: String,
)

data class StudioAiModel(
    val id: String,
    val name: String = "",
    val provider: String = "",
    val isFree: Boolean = false,
    val isAuto: Boolean = false,
)

/**
 * Small AI router for Adapter Studio. Authentication semantics are intentionally compatible
 * with the Kilo/Cline account flows used by OmniChat, but the HTTP client identifies itself
 * as NoodleMobile and keeps its own credentials in Android Keystore.
 */
class StudioAiRouter(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val vault = StudioSecretVault()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val gpt = StudioChatGptClient()
    private val grok = StudioGrokClient()
    private var pendingGpt: StudioChatGptClient.Login? = null
    private val grokDevices = java.util.concurrent.ConcurrentHashMap<String, StudioGrokClient.Device>()

    private fun readGpt(): StudioChatGptClient.Creds? = StudioChatGptClient.Creds.fromJson(readSecret(KEY_GPT))
    private fun saveGpt(c: StudioChatGptClient.Creds) = writeSecret(KEY_GPT, c.toJson())
    private fun currentGpt(): StudioChatGptClient.Creds? {
        val c = readGpt() ?: return null
        if (!c.expiresSoon()) return c
        return runCatching { gpt.refresh(c).also { saveGpt(it) } }.getOrElse { if (c.expiresAtMs > System.currentTimeMillis()) c else throw it }
    }
    private fun readGrok(): StudioGrokClient.Creds? = StudioGrokClient.Creds.fromJson(readSecret(KEY_GROK))
    private fun saveGrok(c: StudioGrokClient.Creds) = writeSecret(KEY_GROK, c.toJson())
    private fun currentGrok(): StudioGrokClient.Creds? {
        val c = readGrok() ?: return null
        if (!c.expiresSoon()) return c
        return runCatching { grok.refresh(c).also { saveGrok(it) } }.getOrElse { if (c.expiresAtMs > System.currentTimeMillis()) c else throw it }
    }

    fun selectedProvider(): StudioAiProvider =
        StudioAiProvider.fromWireName(prefs.getString(KEY_PROVIDER, StudioAiProvider.LOCAL.wireName).orEmpty())

    fun selectProvider(provider: StudioAiProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.wireName).apply()
    }

    fun selectedModel(provider: StudioAiProvider): String =
        prefs.getString("model_${provider.wireName}", "").orEmpty()

    fun selectModel(provider: StudioAiProvider, model: String) {
        prefs.edit().putString("model_${provider.wireName}", model.trim()).apply()
    }

    fun status(provider: StudioAiProvider): StudioAiAccountStatus = when (provider) {
        StudioAiProvider.LOCAL -> StudioAiAccountStatus(provider, true, "Локальный анализ")
        StudioAiProvider.KILO -> {
            val token = readSecret(KEY_KILO_TOKEN)
            StudioAiAccountStatus(provider, token.isNotBlank(), prefs.getString(KEY_KILO_EMAIL, "").orEmpty())
        }
        StudioAiProvider.CLINE -> {
            val credentials = readCline()
            StudioAiAccountStatus(
                provider,
                credentials != null && credentials.accessToken.isNotBlank(),
                credentials?.email.orEmpty(),
            )
        }
        StudioAiProvider.CHATGPT -> readGpt().let { StudioAiAccountStatus(provider, it != null, it?.email.orEmpty().ifBlank { it?.plan.orEmpty() }) }
        StudioAiProvider.GROK -> readGrok().let { StudioAiAccountStatus(provider, it != null, it?.email.orEmpty()) }
    }

    fun disconnect(provider: StudioAiProvider) {
        when (provider) {
            StudioAiProvider.LOCAL -> Unit
            StudioAiProvider.KILO -> prefs.edit().remove(KEY_KILO_TOKEN).remove(KEY_KILO_EMAIL).apply()
            StudioAiProvider.CLINE -> prefs.edit().remove(KEY_CLINE_CREDENTIALS).apply()
            StudioAiProvider.CHATGPT -> { pendingGpt?.let { gpt.cancel(it) }; pendingGpt = null; prefs.edit().remove(KEY_GPT).apply() }
            StudioAiProvider.GROK -> prefs.edit().remove(KEY_GROK).apply()
        }
    }

    suspend fun beginLogin(provider: StudioAiProvider): StudioDeviceLogin = withContext(Dispatchers.IO) {
        when (provider) {
            StudioAiProvider.LOCAL -> error("Локальный режим не требует авторизации")
            StudioAiProvider.KILO -> beginKiloLogin()
            StudioAiProvider.CLINE -> beginClineLogin()
            StudioAiProvider.CHATGPT -> {
                pendingGpt?.let { gpt.cancel(it) }
                val hostId = prefs.getString(KEY_GPT_HOST, "").orEmpty().ifBlank { "urn:uuid:" + java.util.UUID.randomUUID().also { id -> prefs.edit().putString(KEY_GPT_HOST, "urn:uuid:$id").apply() } }
                val l = gpt.begin(hostId); pendingGpt = l
                StudioDeviceLogin(provider, "", "", l.authUrl, 300, 1)
            }
            StudioAiProvider.GROK -> {
                val d = grok.begin(); grokDevices[d.deviceCode] = d
                StudioDeviceLogin(provider, d.deviceCode, d.userCode, d.url, ((d.expiresAtMs - System.currentTimeMillis()) / 1000).toInt(), d.intervalSec.toInt())
            }
        }
    }

    suspend fun awaitLogin(login: StudioDeviceLogin): StudioAiAccountStatus = withContext(Dispatchers.IO) {
        when (login.provider) {
            StudioAiProvider.LOCAL -> StudioAiAccountStatus(StudioAiProvider.LOCAL, true, "Локальный анализ")
            StudioAiProvider.KILO -> awaitKiloLogin(login)
            StudioAiProvider.CLINE -> awaitClineLogin(login)
            StudioAiProvider.CHATGPT -> {
                val l = pendingGpt ?: error("Начните вход заново")
                val c = gpt.await(l); pendingGpt = null
                saveGpt(c)
                StudioAiAccountStatus(StudioAiProvider.CHATGPT, true, c.email.ifBlank { c.plan })
            }
            StudioAiProvider.GROK -> {
                val d = grokDevices[login.deviceCode] ?: error("Начните вход заново")
                var found: StudioGrokClient.Creds? = null
                var wait = d.intervalSec
                while (found == null) {
                    if (System.currentTimeMillis() >= d.expiresAtMs) error("Код xAI истёк — начните вход заново")
                    delay(wait * 1000L)
                    found = grok.poll(d)
                    if (found == null) wait = (wait + 1).coerceAtMost(15)
                }
                grokDevices.remove(login.deviceCode)
                saveGrok(found)
                StudioAiAccountStatus(StudioAiProvider.GROK, true, found.email)
            }
        }
    }

    suspend fun modelCatalog(provider: StudioAiProvider): List<StudioAiModel> = withContext(Dispatchers.IO) {
        when (provider) {
            StudioAiProvider.LOCAL -> emptyList()
            StudioAiProvider.KILO -> kiloModelCatalog()
            StudioAiProvider.CLINE -> clineModelCatalog()
            StudioAiProvider.CHATGPT -> gpt.models(currentGpt() ?: error("Войдите в ChatGPT")).map { simpleModel(it) }
            StudioAiProvider.GROK -> grok.models(currentGrok() ?: error("Войдите в Grok")).map { simpleModel(it) }
        }
    }

    suspend fun models(provider: StudioAiProvider): List<String> =
        modelCatalog(provider).map(StudioAiModel::id)

    suspend fun complete(
        provider: StudioAiProvider,
        model: String,
        systemPrompt: String,
        userPrompt: String,
    ): StudioAiReply = withContext(Dispatchers.IO) {
        val reply = chat(
            provider, model,
            listOf("system" to systemPrompt, "user" to userPrompt),
            maxTokens = 7000,
        )
        val adapterJson = extractJsonObject(reply.json)
            ?: throw IllegalStateException("AI не вернул JSON-объект adapter.json")
        StudioAiReply(adapterJson, reply.model)
    }

    /**
     * Multi-turn chat over the provider's OpenAI-compatible gateway. Returns the raw assistant text
     * (in [StudioAiReply.json]) so callers can implement their own protocol on top (agent loop).
     */
    suspend fun chat(
        provider: StudioAiProvider,
        model: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int = 4000,
        temperature: Double = 0.1,
    ): StudioAiReply = withContext(Dispatchers.IO) {
        require(provider != StudioAiProvider.LOCAL) { "Для локального режима AI-вызов не требуется" }
        val chosen = model.trim().ifBlank {
            modelCatalog(provider).firstOrNull()?.id.orEmpty()
        }
        require(chosen.isNotBlank()) { "Не удалось выбрать AI-модель" }

        if (provider == StudioAiProvider.CHATGPT || provider == StudioAiProvider.GROK) {
            val text = if (provider == StudioAiProvider.CHATGPT) gpt.chat(currentGpt() ?: error("Войдите в ChatGPT"), chosen, messages)
            else grok.chat(currentGrok() ?: error("Войдите в Grok"), chosen, messages)
            if (text.isBlank()) throw IllegalStateException("${provider.title} вернул пустой ответ")
            return@withContext StudioAiReply(text, chosen)
        }
        val auth = when (provider) {
            StudioAiProvider.KILO -> readSecret(KEY_KILO_TOKEN).ifBlank {
                error("Войдите в Kilo Code")
            }
            StudioAiProvider.CLINE -> currentCline()?.let { formatClineToken(it.accessToken) }.orEmpty().ifBlank {
                error("Войдите в Cline")
            }
            else -> ""
        }
        val base = when (provider) {
            StudioAiProvider.KILO -> KILO_OPENAI_BASE
            StudioAiProvider.CLINE -> CLINE_OPENAI_BASE
            else -> error("Локальный режим")
        }
        val array = JSONArray()
        messages.forEach { (role, content) -> array.put(JSONObject().put("role", role).put("content", content)) }
        val body = JSONObject()
            .put("model", chosen)
            .put("temperature", temperature)
            .put("stream", false)
            .put("max_tokens", maxTokens)
            .put("messages", array)
        val builder = Request.Builder()
            .url("${base.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $auth")
            .header("Accept", "application/json")
            .header("User-Agent", "NoodleMobile-Android")
            .header("X-Title", "NoodleMobile")
            .post(body.toString().toRequestBody(jsonType))
        if (provider == StudioAiProvider.CLINE) {
            builder.header("X-CLIENT-TYPE", "noodlemobile")
        }
        val response = execute(builder.build())
        if (response.code !in 200..299) {
            throw IllegalStateException("${provider.title}: HTTP ${response.code}: ${response.raw.take(300)}")
        }
        val content = messageContent(response.json)
        if (content.isBlank()) throw IllegalStateException("${provider.title} вернул пустой ответ")
        StudioAiReply(content, chosen)
    }

    private fun beginKiloLogin(): StudioDeviceLogin {
        val req = Request.Builder()
            .url("$KILO_API_BASE/api/device-auth/codes")
            .header("Accept", "application/json")
            .post("{}".toRequestBody(jsonType))
            .build()
        val response = execute(req)
        requireSuccess("Kilo Code", response)
        val code = response.json.optString("code")
        val url = response.json.optString("verificationUrl")
        if (code.isBlank() || url.isBlank()) error("Kilo Code не вернул данные device login")
        return StudioDeviceLogin(
            provider = StudioAiProvider.KILO,
            deviceCode = code,
            userCode = code,
            verificationUrl = url,
            expiresInSeconds = response.json.optInt("expiresIn", 600).coerceAtLeast(30),
            pollIntervalSeconds = 3,
        )
    }

    private suspend fun awaitKiloLogin(login: StudioDeviceLogin): StudioAiAccountStatus {
        val deadline = System.currentTimeMillis() + login.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val response = execute(
                Request.Builder()
                    .url("$KILO_API_BASE/api/device-auth/codes/${login.deviceCode}")
                    .header("Accept", "application/json")
                    .get()
                    .build(),
            )
            when (response.code) {
                202 -> Unit
                403 -> error("Авторизация Kilo Code отклонена")
                410 -> error("Код авторизации Kilo Code истёк")
                in 200..299 -> {
                    val token = response.json.optString("token")
                    if (token.isBlank()) error("Kilo Code не вернул токен аккаунта")
                    val email = response.json.optString("userEmail")
                    writeSecret(KEY_KILO_TOKEN, token)
                    prefs.edit().putString(KEY_KILO_EMAIL, email).apply()
                    return StudioAiAccountStatus(StudioAiProvider.KILO, true, email)
                }
                else -> error("Kilo Code: HTTP ${response.code}: ${response.raw.take(180)}")
            }
            delay(login.pollIntervalSeconds * 1000L)
        }
        error("Время ожидания входа Kilo Code истекло")
    }

    private fun beginClineLogin(): StudioDeviceLogin {
        val body = FormBody.Builder().add("client_id", CLINE_WORKOS_CLIENT_ID).build()
        val response = execute(
            Request.Builder()
                .url("$WORKOS_BASE/user_management/authorize/device")
                .post(body)
                .build(),
        )
        requireSuccess("Cline/WorkOS", response)
        val deviceCode = response.json.optString("device_code")
        val userCode = response.json.optString("user_code")
        val verification = response.json.optString("verification_uri_complete")
            .ifBlank { response.json.optString("verification_uri") }
        if (deviceCode.isBlank() || verification.isBlank()) error("Cline не вернул данные device login")
        return StudioDeviceLogin(
            provider = StudioAiProvider.CLINE,
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verification,
            expiresInSeconds = response.json.optInt("expires_in", 300).coerceAtLeast(30),
            pollIntervalSeconds = response.json.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    private suspend fun awaitClineLogin(login: StudioDeviceLogin): StudioAiAccountStatus {
        val deadline = System.currentTimeMillis() + login.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val body = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", login.deviceCode)
                .add("client_id", CLINE_WORKOS_CLIENT_ID)
                .build()
            val response = execute(
                Request.Builder()
                    .url("$WORKOS_BASE/user_management/authenticate")
                    .post(body)
                    .build(),
            )
            if (response.code in 200..299) {
                val workAccess = response.json.optString("access_token")
                val workRefresh = response.json.optString("refresh_token")
                if (workAccess.isBlank() || workRefresh.isBlank()) error("WorkOS не вернул токены Cline")
                val registered = execute(
                    Request.Builder()
                        .url("$CLINE_API_BASE/api/v1/auth/register")
                        .header("Content-Type", "application/json")
                        .post(
                            JSONObject()
                                .put("accessToken", workAccess)
                                .put("refreshToken", workRefresh)
                                .toString()
                                .toRequestBody(jsonType),
                        )
                        .build(),
                )
                requireSuccess("Cline", registered)
                val credentials = parseClineCredentials(registered.json, null)
                saveCline(credentials)
                return StudioAiAccountStatus(
                    StudioAiProvider.CLINE,
                    true,
                    credentials.email.ifBlank { credentials.name },
                )
            }
            val error = response.json.optString("error")
            when (error) {
                "authorization_pending" -> Unit
                "slow_down" -> delay(2_000L)
                "access_denied", "expired_token", "invalid_grant" ->
                    error(response.json.optString("error_description").ifBlank { "Авторизация Cline отклонена" })
                else -> if (response.code !in 200..299) {
                    error(response.json.optString("error_description").ifBlank { "Cline/WorkOS: HTTP ${response.code}" })
                }
            }
            delay(login.pollIntervalSeconds * 1000L)
        }
        error("Время ожидания входа Cline истекло")
    }

    private fun kiloModelCatalog(): List<StudioAiModel> {
        val token = readSecret(KEY_KILO_TOKEN)
        if (token.isBlank()) error("Войдите в Kilo Code")

        val gatewayResponse = runCatching {
            execute(
                Request.Builder()
                    .url(KILO_GATEWAY_MODELS_URL)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NoodleMobile-Android")
                    .get()
                    .build(),
            )
        }.getOrNull()
        val gatewayModels = gatewayResponse
            ?.takeIf { it.code in 200..299 }
            ?.json
            ?.let(::parseKiloCatalog)
            .orEmpty()

        val legacyResponse = runCatching {
            execute(
                Request.Builder()
                    .url("$KILO_OPENAI_BASE/models")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "NoodleMobile-Android")
                    .get()
                    .build(),
            )
        }.getOrNull()
        val legacyModels = legacyResponse
            ?.takeIf { it.code in 200..299 }
            ?.json
            ?.let(::extractModelIds)
            .orEmpty()
            .map { simpleModel(it) }

        val fallbackModels = KILO_FALLBACK_MODELS.map { simpleModel(it) }
        val merged = linkedMapOf<String, StudioAiModel>()
        (gatewayModels + legacyModels + fallbackModels).forEach { model ->
            if (model.id.isNotBlank() && model.id !in merged) merged[model.id] = model
        }
        return merged.values.sortedWith(
            compareBy<StudioAiModel>(
                { !it.isAuto },
                { !it.isFree },
                { it.provider.lowercase() },
                { it.name.ifBlank { it.id }.lowercase() },
            ),
        )
    }

    private fun parseKiloCatalog(payload: JSONObject): List<StudioAiModel> {
        val array = payload.optJSONArray("data") ?: payload.optJSONArray("models") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { item.optString("model") }
            if (id.isBlank()) return@mapNotNull null
            val pricing = item.optJSONObject("pricing")
            val priceFree = pricing != null &&
                pricing.has("prompt") && pricing.has("completion") &&
                priceValue(pricing.opt("prompt")) == 0.0 &&
                priceValue(pricing.opt("completion")) == 0.0
            StudioAiModel(
                id = id,
                name = item.optString("name").ifBlank { id },
                provider = item.optString("owned_by")
                    .ifBlank { item.optString("provider") }
                    .ifBlank { id.substringBefore('/', "Kilo") },
                isFree = isFreeModelId(id) || priceFree,
                isAuto = id.startsWith("kilo-auto/", ignoreCase = true),
            )
        }
    }

    private fun priceValue(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun simpleModel(id: String): StudioAiModel = StudioAiModel(
        id = id,
        name = id,
        provider = id.substringBefore('/', "Kilo"),
        isFree = isFreeModelId(id),
        isAuto = id.startsWith("kilo-auto/", ignoreCase = true),
    )

    private fun isFreeModelId(id: String): Boolean =
        id.endsWith(":free", ignoreCase = true) ||
            id.equals("kilo-auto/free", ignoreCase = true) ||
            id.equals("openrouter/free", ignoreCase = true)

    private fun clineModelCatalog(): List<StudioAiModel> {
        val credentials = currentCline() ?: error("Войдите в Cline")
        val bearer = formatClineToken(credentials.accessToken)
        val generic = getClineJson("$CLINE_OPENAI_BASE/models", bearer)?.let(::extractModelIds).orEmpty()
        val recommended = getClineJson("$CLINE_API_BASE/api/v1/ai/cline/recommended-models", bearer)
        val pass = extractModelIds(recommended?.opt("clinePass"))
        val free = extractModelIds(recommended?.opt("free"))
        val billing = extractModelIds(recommended?.opt("billing"))
        val freeSet = free.toSet()
        return (pass + free + billing + generic + CLINE_FALLBACK_MODELS)
            .distinct()
            .map { id ->
                StudioAiModel(
                    id = id,
                    name = id,
                    provider = id.substringBefore('/', "Cline"),
                    isFree = id in freeSet || id.endsWith(":free", ignoreCase = true),
                    isAuto = false,
                )
            }
    }

    private fun getClineJson(url: String, bearer: String): JSONObject? {
        val response = execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .header("User-Agent", "NoodleMobile-Android")
                .header("X-CLIENT-TYPE", "noodlemobile")
                .header("X-Title", "NoodleMobile")
                .get()
                .build(),
        )
        return response.json.takeIf { response.code in 200..299 }
    }

    private fun currentCline(): ClineCredentials? {
        val current = readCline() ?: return null
        if (!current.expiresSoon()) return current
        return runCatching { refreshCline(current) }.getOrElse { current }
    }

    private fun refreshCline(current: ClineCredentials): ClineCredentials {
        val body = JSONObject()
            .put("refreshToken", current.refreshToken)
            .put("grantType", "refresh_token")
        val response = execute(
            Request.Builder()
                .url("$CLINE_API_BASE/api/v1/auth/refresh")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonType))
                .build(),
        )
        requireSuccess("Cline refresh", response)
        return parseClineCredentials(response.json, current).also(::saveCline)
    }

    private fun parseClineCredentials(payload: JSONObject, fallback: ClineCredentials?): ClineCredentials {
        val data = payload.optJSONObject("data") ?: payload
        val user = data.optJSONObject("userInfo") ?: JSONObject()
        val access = data.optString("accessToken").ifBlank { fallback?.accessToken.orEmpty() }
        val refresh = data.optString("refreshToken").ifBlank { fallback?.refreshToken.orEmpty() }
        if (access.isBlank() || refresh.isBlank()) error("Cline не вернул полный набор credentials")
        val expiresAt = data.optString("expiresAt")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: data.optLong("expiresAtMs", 0L).takeIf { it > 0L }
            ?: fallback?.expiresAtMs
            ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
        return ClineCredentials(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = expiresAt,
            email = user.optString("email").ifBlank { fallback?.email.orEmpty() },
            name = user.optString("name").ifBlank { fallback?.name.orEmpty() },
        )
    }

    private fun saveCline(credentials: ClineCredentials) {
        val raw = JSONObject()
            .put("accessToken", credentials.accessToken)
            .put("refreshToken", credentials.refreshToken)
            .put("expiresAtMs", credentials.expiresAtMs)
            .put("email", credentials.email)
            .put("name", credentials.name)
            .toString()
        writeSecret(KEY_CLINE_CREDENTIALS, raw)
    }

    private fun readCline(): ClineCredentials? {
        val raw = readSecret(KEY_CLINE_CREDENTIALS)
        if (raw.isBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            ClineCredentials(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                expiresAtMs = json.optLong("expiresAtMs", 0L),
                email = json.optString("email"),
                name = json.optString("name"),
            )
        }.getOrNull()
    }

    private fun writeSecret(key: String, value: String) {
        prefs.edit().putString(key, vault.encrypt(value)).apply()
    }

    private fun readSecret(key: String): String = runCatching {
        vault.decrypt(prefs.getString(key, "").orEmpty())
    }.getOrDefault("")

    private fun execute(request: Request): HttpJsonResponse = http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        val json = raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        HttpJsonResponse(response.code, json, raw)
    }

    private fun requireSuccess(label: String, response: HttpJsonResponse) {
        if (response.code in 200..299) return
        val message = response.json.optString("error_description")
            .ifBlank { response.json.optString("error") }
            .ifBlank { response.raw.take(220) }
            .ifBlank { "HTTP ${response.code}" }
        error("$label: $message")
    }

    private fun messageContent(json: JSONObject): String {
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val item = content.opt(index)
                    when (item) {
                        is String -> append(item)
                        is JSONObject -> append(item.optString("text").ifBlank { item.optString("content") })
                    }
                }
            }
            else -> ""
        }
    }

    private fun extractModelIds(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val item = value.opt(index)) {
                is String -> item.takeIf { it.isNotBlank() }
                is JSONObject -> item.optString("id")
                    .ifBlank { item.optString("model") }
                    .ifBlank { item.optString("name") }
                    .takeIf { it.isNotBlank() }
                else -> null
            }
        }
        is JSONObject -> {
            val nested = listOf("data", "models", "clinePass", "free", "billing")
                .flatMap { key -> extractModelIds(value.opt(key)) }
            if (nested.isNotEmpty()) nested else value.keys().asSequence()
                .filter { key -> key.contains('/') || key.endsWith(":free") }
                .toList()
        }
        is String -> listOf(value).filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun formatClineToken(token: String): String = token.trim().let {
        if (it.lowercase().startsWith(CLINE_TOKEN_PREFIX)) it else CLINE_TOKEN_PREFIX + it
    }

    private data class ClineCredentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val email: String,
        val name: String,
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + TimeUnit.MINUTES.toMillis(5)
    }

    private data class HttpJsonResponse(
        val code: Int,
        val json: JSONObject,
        val raw: String,
    )

    companion object {
        private const val PREFS = "adapter_studio_ai"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_KILO_TOKEN = "kilo_token"
        private const val KEY_KILO_EMAIL = "kilo_email"
        private const val KEY_CLINE_CREDENTIALS = "cline_credentials"
        private const val KEY_GPT = "chatgpt_credentials"
        private const val KEY_GPT_HOST = "chatgpt_host"
        private const val KEY_GROK = "grok_credentials"

        private const val KILO_API_BASE = "https://api.kilo.ai"
        private const val KILO_OPENAI_BASE = "https://api.kilo.ai/api/openrouter"
        private const val KILO_GATEWAY_MODELS_URL = "https://api.kilo.ai/api/gateway/models"
        private const val CLINE_API_BASE = "https://api.cline.bot"
        private const val CLINE_OPENAI_BASE = "https://api.cline.bot/api/v1"
        private const val WORKOS_BASE = "https://api.workos.com"
        private const val CLINE_WORKOS_CLIENT_ID = "client_01K3A541FN8TA3EPPHTD2325AR"
        private const val CLINE_TOKEN_PREFIX = "workos:"

        private val KILO_FALLBACK_MODELS = listOf(
            "kilo-auto/free",
            "openrouter/free",
            "kilo-auto/efficient",
            "kilo-auto/small",
            "kilo-auto/frontier",
        )
        private val CLINE_FALLBACK_MODELS = listOf(
            "cline-pass/mimo-v2.6-flash",
            "cline-pass/glm-5.3-flash",
            "cline-pass/deepseek-v4.1-flash",
            "deepseek/deepseek-v4-flash",
        )

        /** Extract the first complete JSON object, tolerating markdown fences around it. */
        fun extractJsonObject(raw: String): String? {
            val text = raw.trim()
            var start = -1
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in text.indices) {
                val char = text[index]
                if (start < 0) {
                    if (char == '{') {
                        start = index
                        depth = 1
                    }
                    continue
                }
                if (quoted) {
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == '"') {
                        quoted = false
                    }
                    continue
                }
                when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
            return null
        }
    }
}

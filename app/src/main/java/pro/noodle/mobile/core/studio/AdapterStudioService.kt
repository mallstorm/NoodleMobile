package pro.noodle.mobile.core.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.AdapterCompatibility
import pro.noodle.mobile.core.source.AdapterPackageManager
import pro.noodle.mobile.core.source.AdapterProbeService
import pro.noodle.mobile.core.source.AdapterRecoveryManager
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.source.SemanticStatus
import java.io.File
import java.net.URI


data class StudioAnalyzeResult(
    val success: Boolean,
    val draft: AdapterStudioDraft? = null,
    val json: String = "",
    val errorCode: String = "",
    val message: String = "",
    val testStatus: String = "",
    val testDetails: String = "",
    val attempts: Int = 0,
    val model: String = "",
)

data class StudioSaveResult(
    val success: Boolean,
    val adapterId: String = "",
    val errorCode: String = "",
    val message: String = "",
)

class AdapterStudioService(context: Context) {
    private val app = context.applicationContext
    private val repo = RuntimeAdapterRepository(app)
    private val overrides = SourceOverridesRepository(app)
    private val http = SecureHttpClient()
    private val probe = AdapterProbeService(app)
    private val ai = StudioAiRouter(app)

    suspend fun analyze(url: String): StudioAnalyzeResult {
        val page = inspect(url) ?: return inspectError(url)
        return StudioAnalyzeResult(
            success = true,
            draft = page.draft,
            json = page.localJson,
            message = page.draft.notes.joinToString(" • "),
            testStatus = "not_run",
        )
    }

    /**
     * Studio 2.0: local heuristic -> AI proposal -> schema validation -> live semantic test.
     * An unhealthy proposal is fed back to AI up to two times. Nothing is installed here.
     */
    suspend fun generate(
        url: String,
        instruction: String,
        provider: StudioAiProvider,
        model: String,
    ): StudioAnalyzeResult {
        val pageResult = inspectResult(url)
        if (!pageResult.success || pageResult.page == null) {
            return StudioAnalyzeResult(
                success = false,
                errorCode = pageResult.errorCode,
                message = pageResult.message,
            )
        }
        val page = pageResult.page
        if (provider == StudioAiProvider.LOCAL) {
            return testCandidate(page.localJson, page.draft, 0, "", "Локальный анализ")
        }
        if (instruction.isBlank()) {
            return StudioAnalyzeResult(
                false,
                draft = page.draft,
                json = page.localJson,
                errorCode = "STUDIO_INSTRUCTION_REQUIRED",
                message = "Опишите, что должен извлекать SNP-адаптер.",
            )
        }
        if (!ai.status(provider).connected) {
            return StudioAnalyzeResult(
                false,
                draft = page.draft,
                json = page.localJson,
                errorCode = "STUDIO_AI_LOGIN_REQUIRED",
                message = "Сначала войдите в ${provider.title}.",
            )
        }

        val html = compactHtml(page.html)
        var previousJson = page.localJson
        var feedback = ""
        var lastValid: StudioAnalyzeResult? = null
        var selectedModel = model

        for (attempt in 1..MAX_AI_ATTEMPTS) {
            val userPrompt = buildUserPrompt(
                finalUrl = page.finalUrl,
                instruction = instruction,
                localJson = page.localJson,
                html = html,
                previousJson = previousJson.takeIf { attempt > 1 }.orEmpty(),
                feedback = feedback,
                attempt = attempt,
            )
            val reply = runCatching {
                ai.complete(provider, selectedModel, SYSTEM_PROMPT, userPrompt)
            }.getOrElse { error ->
                return lastValid ?: StudioAnalyzeResult(
                    false,
                    draft = page.draft,
                    json = previousJson,
                    errorCode = "STUDIO_AI_REQUEST_FAILED",
                    message = error.message ?: "AI-запрос завершился ошибкой.",
                    attempts = attempt,
                    model = selectedModel,
                )
            }
            selectedModel = reply.model
            previousJson = reply.json

            val validation = AdapterValidator.validate(reply.json)
            if (!validation.isValid || validation.definition == null) {
                feedback = "Schema validation failed: ${validation.errorCode}: ${validation.errorMessage}"
                if (attempt == MAX_AI_ATTEMPTS) {
                    return lastValid ?: StudioAnalyzeResult(
                        false,
                        draft = page.draft,
                        json = reply.json,
                        errorCode = validation.errorCode.ifBlank { "STUDIO_AI_SCHEMA_INVALID" },
                        message = validation.errorMessage.ifBlank { "AI вернул некорректный Schema v3." },
                        attempts = attempt,
                        model = selectedModel,
                    )
                }
                continue
            }

            val semantic = runCatching { probe.validateDefinition(validation.definition) }.getOrElse { error ->
                feedback = "Live test crashed: ${error.javaClass.simpleName}: ${error.message}"
                val candidate = StudioAnalyzeResult(
                    true,
                    draft = page.draft,
                    json = reply.json,
                    message = "AI сформировал валидный Schema v3, но live test завершился исключением.",
                    testStatus = "error",
                    testDetails = feedback,
                    attempts = attempt,
                    model = selectedModel,
                )
                lastValid = candidate
                if (attempt == MAX_AI_ATTEMPTS) return candidate
                continue
            }

            val details = buildString {
                append(semantic.message)
                if (semantic.errorCode.isNotBlank()) append(" [${semantic.errorCode}]")
                append(" • search=${semantic.searchResultCount}")
                append(" • playback=${semantic.playbackSourceCount}")
            }
            val candidate = StudioAnalyzeResult(
                success = true,
                draft = page.draft,
                json = reply.json,
                message = if (semantic.status == SemanticStatus.HEALTHY) {
                    "AI-адаптер создан и live test пройден."
                } else {
                    "AI-адаптер создан; live test требует проверки."
                },
                testStatus = semantic.status.wireName,
                testDetails = details,
                attempts = attempt,
                model = selectedModel,
            )
            lastValid = candidate
            if (semantic.status == SemanticStatus.HEALTHY) return candidate

            feedback = "Live semantic test failed: status=${semantic.status.wireName}; $details"
        }
        return lastValid ?: StudioAnalyzeResult(
            false,
            draft = page.draft,
            json = previousJson,
            errorCode = "STUDIO_AI_NO_VALID_RESULT",
            message = "Не удалось получить рабочий SNP после $MAX_AI_ATTEMPTS попыток.",
            attempts = MAX_AI_ATTEMPTS,
            model = selectedModel,
        )
    }

    suspend fun testJson(json: String): StudioAnalyzeResult {
        val validation = AdapterValidator.validate(json)
        if (!validation.isValid || validation.definition == null) {
            return StudioAnalyzeResult(
                false,
                json = json,
                errorCode = validation.errorCode,
                message = validation.errorMessage,
                testStatus = "invalid_adapter",
            )
        }
        val semantic = runCatching { probe.validateDefinition(validation.definition) }.getOrElse { error ->
            return StudioAnalyzeResult(
                true,
                json = json,
                message = "Schema v3 валиден, но live test завершился ошибкой.",
                testStatus = "error",
                testDetails = "${error.javaClass.simpleName}: ${error.message}",
            )
        }
        return StudioAnalyzeResult(
            true,
            json = json,
            message = if (semantic.status == SemanticStatus.HEALTHY) "Live test пройден." else "Live test не пройден.",
            testStatus = semantic.status.wireName,
            testDetails = "${semantic.message}${semantic.errorCode.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()} • search=${semantic.searchResultCount} • playback=${semantic.playbackSourceCount}",
        )
    }

    suspend fun saveJson(json: String): StudioSaveResult = withContext(Dispatchers.IO) {
        val v = AdapterValidator.validate(json)
        if (!v.isValid || v.definition == null) {
            return@withContext StudioSaveResult(false, errorCode = v.errorCode, message = v.errorMessage)
        }
        val d = v.definition
        if (d.schemaVersion < 3) {
            return@withContext StudioSaveResult(false, errorCode = "STUDIO_SCHEMA_V3_REQUIRED", message = "Adapter Studio сохраняет только Schema v3.")
        }
        val compatibility = AdapterCompatibility.evaluate(d.minAppVersion, d.maxAppVersion)
        if (!compatibility.compatible) {
            return@withContext StudioSaveResult(false, errorCode = compatibility.errorCode, message = compatibility.message)
        }
        val bundled = repo.listAll().firstOrNull { it.bundled && it.definition.id.equals(d.id, true) }
        if (bundled != null) {
            return@withContext StudioSaveResult(false, errorCode = "STUDIO_BUNDLED_COLLISION", message = "Adapter Studio не может заменить штатный SNP.")
        }
        val target = File(repo.paths.custom, d.id.lowercase())
        val recovery = AdapterRecoveryManager(repo.paths)
        var backup: File? = null
        if (target.isDirectory) {
            val cur = repo.find(d.id)
            if (cur != null && !cur.bundled) backup = recovery.createSnapshot(d.id, target, cur.definition.adapterVersion)
            if (backup == null) {
                return@withContext StudioSaveResult(false, errorCode = "STUDIO_SNAPSHOT_FAILED", message = "Не удалось создать snapshot существующего custom SNP.")
            }
        }
        val ready = File(repo.paths.updates, "${d.id.lowercase()}.studio-ready")
        ready.deleteRecursively()
        ready.mkdirs()
        try {
            File(ready, "adapter.json").writeText(json, Charsets.UTF_8)
            AdapterPackageManager.createManifest(ready, d.id, d.adapterVersion, d.publisher)
            val pkg = AdapterPackageManager.verifyDirectory(ready)
            if (!pkg.valid) return@withContext StudioSaveResult(false, errorCode = pkg.errorCode, message = pkg.errorMessage)
            val old = File(repo.paths.updates, "${d.id.lowercase()}.studio-old")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) {
                return@withContext StudioSaveResult(false, errorCode = "STUDIO_SWAP_FAILED", message = "Не удалось подготовить замену custom SNP.")
            }
            if (!ready.renameTo(target)) {
                if (!target.exists() && old.exists()) old.renameTo(target)
                return@withContext StudioSaveResult(false, errorCode = "STUDIO_SWAP_FAILED", message = "Не удалось активировать SNP.")
            }
            try {
                overrides.setEnabled(d.id, false)
            } catch (error: Exception) {
                target.deleteRecursively()
                if (old.exists()) old.renameTo(target)
                return@withContext StudioSaveResult(false, errorCode = "STUDIO_OVERRIDE_FAILED", message = "Не удалось зафиксировать disabled override: ${error.message}")
            }
            old.deleteRecursively()
            StudioSaveResult(true, d.id, message = "Custom SNP сохранён выключенным. Выполните Semantic health перед включением.")
        } catch (error: Exception) {
            if (!target.exists() && backup != null) {
                runCatching { recovery.restoreSnapshot(d.id, backup.name, target) }
            }
            StudioSaveResult(false, errorCode = "STUDIO_SAVE_EXCEPTION", message = "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            ready.deleteRecursively()
        }
    }

    private suspend fun testCandidate(
        json: String,
        draft: AdapterStudioDraft?,
        attempts: Int,
        model: String,
        prefix: String,
    ): StudioAnalyzeResult {
        val tested = testJson(json)
        return tested.copy(
            draft = draft,
            attempts = attempts,
            model = model,
            message = "$prefix. ${tested.message}".trim(),
        )
    }

    private suspend fun inspect(url: String): InspectedPage? = inspectResult(url).page

    private suspend fun inspectResult(url: String): InspectionResult {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return InspectionResult(errorCode = "STUDIO_URL_INVALID", message = "Некорректный URL.")
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) {
            return InspectionResult(errorCode = "STUDIO_HTTPS_REQUIRED", message = "Adapter Studio анализирует только HTTPS URL.")
        }
        val fetch = http.fetchText(
            url.trim(),
            NetworkPolicy(listOf(uri.host.lowercase()), maxResponseBytes = 10 * 1024 * 1024, maxRedirects = 5),
            "@adapter-studio",
        )
        if (!fetch.success) {
            return InspectionResult(errorCode = fetch.errorCode, message = fetch.errorMessage)
        }
        val draft = AdapterStudioCore.analyze(fetch.finalUrl, fetch.text).getOrElse {
            return InspectionResult(errorCode = "STUDIO_ANALYZE_FAILED", message = it.message.orEmpty())
        }
        val json = AdapterStudioCore.toAdapterJson(draft)
        val validation = AdapterValidator.validate(json)
        if (!validation.isValid) {
            return InspectionResult(errorCode = validation.errorCode, message = validation.errorMessage)
        }
        return InspectionResult(page = InspectedPage(fetch.finalUrl, fetch.text, draft, json))
    }

    private fun inspectError(url: String): StudioAnalyzeResult {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        return if (uri == null) {
            StudioAnalyzeResult(false, errorCode = "STUDIO_URL_INVALID", message = "Некорректный URL.")
        } else {
            StudioAnalyzeResult(false, errorCode = "STUDIO_ANALYZE_FAILED", message = "Не удалось проанализировать страницу.")
        }
    }

    private fun buildUserPrompt(
        finalUrl: String,
        instruction: String,
        localJson: String,
        html: String,
        previousJson: String,
        feedback: String,
        attempt: Int,
    ): String = buildString {
        appendLine("TARGET URL: $finalUrl")
        appendLine("USER TASK:")
        appendLine(instruction.trim())
        appendLine()
        appendLine("LOCAL HEURISTIC DRAFT (valid Schema v3, improve it rather than blindly replacing useful fields):")
        appendLine(localJson)
        if (attempt > 1) {
            appendLine()
            appendLine("PREVIOUS AI CANDIDATE:")
            appendLine(previousJson)
            appendLine()
            appendLine("VALIDATOR/LIVE-TEST FEEDBACK TO FIX:")
            appendLine(feedback)
        }
        appendLine()
        appendLine("PAGE HTML SAMPLE:")
        appendLine(html)
        appendLine()
        appendLine("Return only the complete adapter.json object. No markdown and no explanation.")
    }

    private fun compactHtml(html: String): String {
        val clean = html.replace("\u0000", "")
        if (clean.length <= MAX_HTML_CHARS) return clean
        val first = clean.take(MAX_HTML_HEAD)
        val last = clean.takeLast(MAX_HTML_CHARS - MAX_HTML_HEAD)
        return "$first\n<!-- NoodleMobile Studio: middle of HTML omitted -->\n$last"
    }

    private data class InspectedPage(
        val finalUrl: String,
        val html: String,
        val draft: AdapterStudioDraft,
        val localJson: String,
    )

    private data class InspectionResult(
        val success: Boolean = true,
        val page: InspectedPage? = null,
        val errorCode: String = "",
        val message: String = "",
    ) {
        constructor(errorCode: String, message: String) : this(false, null, errorCode, message)
    }

    companion object {
        private const val MAX_AI_ATTEMPTS = 3
        private const val MAX_HTML_CHARS = 120_000
        private const val MAX_HTML_HEAD = 80_000

        private val SYSTEM_PROMPT = """
            You are the Adapter Studio compiler for NoodleMobile. Produce one strict Noodle SNP adapter.json using Schema v3.
            The adapter is declarative configuration, not executable code. Never return Kotlin, JavaScript, shell commands or markdown.
            Preserve the target site's real HTTPS origin. Infer search URL, item/title/url/thumbnail/duration/views selectors or JSON paths from the supplied HTML and task.
            Choose an engine supported by NoodleMobile when possible: declarative_catalog, wordpress_rest, json_api, kvs, or another engine already represented by the local draft.
            Configure view.strategies using only useful resolver strategies from: primary, jwplayer, videojs, generic_html5, oembed, iframe, hls, dash.
            permissions.allowed_hosts must be the minimum host set needed by search, page navigation, player embeds and media/CDN URLs visible in the evidence. Do not grant unrelated domains.
            Keep allow_javascript and allow_cookies false unless the evidence strongly requires them.
            Keep health and conservative repair blocks. generated adapters must start with enabled=false.
            Do not invent fake selectors, endpoints, view counts or CDN hosts merely to satisfy the task.
            The output must be a single parseable JSON object and must pass NoodleMobile AdapterValidator Schema v3.
        """.trimIndent()
    }
}

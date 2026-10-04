package pro.noodle.mobile.core.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.AdapterCompatibility
import pro.noodle.mobile.core.source.AdapterPackageManager
import pro.noodle.mobile.core.source.AdapterProbeService
import pro.noodle.mobile.core.source.AdapterRecoveryManager
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.source.SemanticStatus
import java.io.File


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
    private val http = pro.noodle.mobile.core.network.SecureHttpClient()
    private val probe = AdapterProbeService(app)
    private val ai = StudioAiRouter(app)
    private val discovery = StudioDiscovery(http)

    suspend fun analyze(url: String, probeQuery: String = ""): StudioAnalyzeResult {
        val d = discovery.discover(url, probeQuery)
        if (!d.success || d.draft == null) {
            return StudioAnalyzeResult(
                success = false,
                json = d.localJson,
                errorCode = d.errorCode,
                message = d.message,
                testStatus = "discovery_failed",
                testDetails = d.evidence.ifBlank { d.summary },
            )
        }
        return StudioAnalyzeResult(
            success = true,
            draft = d.draft,
            json = d.localJson,
            message = d.message,
            testStatus = "not_run",
            testDetails = d.summary,
        )
    }

    /**
     * Studio 2.1: Delphi-style deterministic discovery -> deterministic Schema v3 -> live test ->
     * fresh route re-discovery -> AI audit/repair -> final live semantic test.
     * AI receives confirmed search/view/iframe evidence instead of guessing endpoints from one page.
     */
    suspend fun generate(
        url: String,
        instruction: String,
        provider: StudioAiProvider,
        model: String,
        probeQuery: String = "",
    ): StudioAnalyzeResult {
        val d = discovery.discover(url, probeQuery)
        if (!d.success || d.draft == null || d.localJson.isBlank()) {
            return StudioAnalyzeResult(
                success = false,
                json = d.localJson,
                errorCode = d.errorCode.ifBlank { "STUDIO_DISCOVERY_FAILED" },
                message = d.message.ifBlank { "Discovery не смог подтвердить реальный поиск и страницу видео." },
                testStatus = "discovery_failed",
                testDetails = d.evidence.ifBlank { d.summary },
            )
        }

        val deterministic = testCandidate(
            json = d.localJson,
            draft = d.draft,
            attempts = 0,
            model = "",
            prefix = "Discovery: ${d.summary}",
        )
        val deterministicHealthy = deterministic.testStatus == SemanticStatus.HEALTHY.wireName

        if (provider == StudioAiProvider.LOCAL) {
            return deterministic.copy(
                message = if (deterministicHealthy) {
                    "Discovery сам создал рабочий SNP. Для независимого AI-аудита выберите Kilo Code или Cline."
                } else {
                    "Discovery создал локальный SNP; live test требует проверки."
                },
                testDetails = "${d.summary}\n${deterministic.testDetails}".trim(),
            )
        }

        if (!ai.status(provider).connected) {
            return if (deterministicHealthy) {
                deterministic.copy(
                    message = "Discovery сам создал рабочий SNP; AI-аудит маршрутов пропущен, потому что ${provider.title} не подключён.",
                    testDetails = "${d.summary}\n${deterministic.testDetails}".trim(),
                )
            } else {
                StudioAnalyzeResult(
                    false,
                    draft = d.draft,
                    json = d.localJson,
                    errorCode = "STUDIO_AI_LOGIN_REQUIRED",
                    message = "Сначала войдите в ${provider.title}.",
                    testStatus = deterministic.testStatus,
                    testDetails = deterministic.testDetails,
                )
            }
        }

        // A healthy local adapter is not trusted blindly: hit the site again and give the model
        // a fresh, independently collected route/evidence snapshot to audit.
        val auditDiscovery = if (deterministicHealthy) {
            val fresh = discovery.discover(url, d.query)
            if (fresh.success && fresh.draft != null && fresh.localJson.isNotBlank()) fresh else d
        } else d

        val effectiveInstruction = instruction.trim().ifBlank {
            "Audit the confirmed NoodleMobile routes and adapter. Verify search, video-page and player/iframe evidence. Preserve confirmed working routes; only repair fields contradicted by the fresh evidence."
        }

        var previousJson = d.localJson
        var feedback = if (deterministicHealthy) {
            buildString {
                append("The deterministic SNP already passed live test. Perform an independent route audit using the FRESH discovery evidence. ")
                append("Original search_template=${d.searchTemplate}; fresh search_template=${auditDiscovery.searchTemplate}; ")
                append("original engine=${d.engine}; fresh engine=${auditDiscovery.engine}. ")
                append("Do not change a confirmed route merely to make the JSON look different. Return the audited complete adapter.json.")
            }
        } else {
            "Deterministic live test before AI: status=${deterministic.testStatus}; ${deterministic.testDetails}"
        }
        var lastValid: StudioAnalyzeResult? = deterministic
        var selectedModel = model

        for (attempt in 1..MAX_AI_ATTEMPTS) {
            val userPrompt = buildUserPrompt(
                discovery = auditDiscovery,
                instruction = effectiveInstruction,
                previousJson = previousJson.takeIf { attempt > 1 }.orEmpty(),
                feedback = feedback,
                attempt = attempt,
            )
            val aiResult = runCatching { ai.complete(provider, selectedModel, buildSystemPrompt(), userPrompt) }
            if (aiResult.isFailure) {
                val error = aiResult.exceptionOrNull()
                return lastValid?.copy(
                    message = if (deterministicHealthy) {
                        "Локальный SNP остаётся healthy, но AI-аудит маршрутов не завершён: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}"
                    } else {
                        "Discovery завершён, но AI-запрос не прошёл: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}"
                    },
                    attempts = attempt,
                    model = selectedModel,
                ) ?: StudioAnalyzeResult(
                    false,
                    draft = d.draft,
                    json = previousJson,
                    errorCode = "STUDIO_AI_REQUEST_FAILED",
                    message = error?.message ?: "AI-запрос завершился ошибкой.",
                    attempts = attempt,
                    model = selectedModel,
                )
            }
            val reply = aiResult.getOrThrow()
            selectedModel = reply.model
            previousJson = reply.json

            val validation = AdapterValidator.validate(reply.json)
            if (!validation.isValid || validation.definition == null) {
                feedback = "Schema validation failed: ${validation.errorCode}: ${validation.errorMessage}. Preserve confirmed discovery facts."
                if (attempt == MAX_AI_ATTEMPTS) {
                    return lastValid ?: StudioAnalyzeResult(
                        false,
                        draft = d.draft,
                        json = reply.json,
                        errorCode = validation.errorCode.ifBlank { "STUDIO_AI_SCHEMA_INVALID" },
                        message = validation.errorMessage.ifBlank { "AI вернул некорректный Schema v3." },
                        attempts = attempt,
                        model = selectedModel,
                    )
                }
                continue
            }

            val semanticResult = runCatching { probe.validateDefinition(validation.definition) }
            if (semanticResult.isFailure) {
                val error = semanticResult.exceptionOrNull()
                feedback = "Live test crashed: ${error?.javaClass?.simpleName}: ${error?.message}. Reuse confirmed search_template=${auditDiscovery.searchTemplate} and engine=${auditDiscovery.engine}."
                val candidate = StudioAnalyzeResult(
                    true,
                    draft = d.draft,
                    json = reply.json,
                    message = "AI сформировал Schema v3, но live test завершился исключением.",
                    testStatus = "error",
                    testDetails = "${auditDiscovery.summary}\n$feedback",
                    attempts = attempt,
                    model = selectedModel,
                )
                if (!deterministicHealthy) lastValid = candidate
                if (attempt == MAX_AI_ATTEMPTS) return lastValid ?: candidate
                continue
            }
            val semantic = semanticResult.getOrThrow()

            val details = buildString {
                append(auditDiscovery.summary)
                append("\n")
                if (deterministicHealthy) append("AI route audit: fresh discovery completed • ")
                append(semantic.message)
                if (semantic.errorCode.isNotBlank()) append(" [${semantic.errorCode}]")
                append(" • search=${semantic.searchResultCount}")
                append(" • playback=${semantic.playbackSourceCount}")
            }
            val candidate = StudioAnalyzeResult(
                success = true,
                draft = d.draft,
                json = reply.json,
                message = if (semantic.status == SemanticStatus.HEALTHY) {
                    if (deterministicHealthy) {
                        "Discovery и независимый AI-аудит маршрутов пройдены; финальный live test healthy."
                    } else {
                        "AI-адаптер создан на подтверждённых Discovery-данных и live test пройден."
                    }
                } else {
                    "AI-адаптер создан; Discovery подтверждён, но live test требует исправления."
                },
                testStatus = semantic.status.wireName,
                testDetails = details,
                attempts = attempt,
                model = selectedModel,
            )
            if (semantic.status == SemanticStatus.HEALTHY) return candidate
            if (!deterministicHealthy) lastValid = candidate

            feedback = buildString {
                append("Live semantic test failed: status=${semantic.status.wireName}; $details. ")
                append("Do not invent a new search endpoint. Confirmed search_template=${auditDiscovery.searchTemplate}; confirmed search_url=${auditDiscovery.searchUrl}; confirmed engine=${auditDiscovery.engine}. ")
                append("If parsing is wrong, repair selectors/fields while preserving the confirmed endpoint and site family.")
            }
        }
        return lastValid ?: StudioAnalyzeResult(
            false,
            draft = d.draft,
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
            return StudioAnalyzeResult(false, json = json, errorCode = validation.errorCode, message = validation.errorMessage, testStatus = "invalid_adapter")
        }
        val semanticResult = runCatching { probe.validateDefinition(validation.definition) }
        if (semanticResult.isFailure) {
            val error = semanticResult.exceptionOrNull()
            return StudioAnalyzeResult(true, json = json, message = "Schema v3 валиден, но live test завершился ошибкой.", testStatus = "error", testDetails = "${error?.javaClass?.simpleName}: ${error?.message}")
        }
        val semantic = semanticResult.getOrThrow()
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
        if (!v.isValid || v.definition == null) return@withContext StudioSaveResult(false, errorCode = v.errorCode, message = v.errorMessage)
        val d = v.definition
        if (d.schemaVersion < 3) return@withContext StudioSaveResult(false, errorCode = "STUDIO_SCHEMA_V3_REQUIRED", message = "Adapter Studio сохраняет только Schema v3.")
        val compatibility = AdapterCompatibility.evaluate(d.minAppVersion, d.maxAppVersion)
        if (!compatibility.compatible) return@withContext StudioSaveResult(false, errorCode = compatibility.errorCode, message = compatibility.message)
        val bundled = repo.listAll().firstOrNull { it.bundled && it.definition.id.equals(d.id, true) }
        if (bundled != null) return@withContext StudioSaveResult(false, errorCode = "STUDIO_BUNDLED_COLLISION", message = "Adapter Studio не может заменить штатный SNP.")
        val target = File(repo.paths.custom, d.id.lowercase())
        val recovery = AdapterRecoveryManager(repo.paths)
        var backup: File? = null
        if (target.isDirectory) {
            val cur = repo.find(d.id)
            if (cur != null && !cur.bundled) backup = recovery.createSnapshot(d.id, target, cur.definition.adapterVersion)
            if (backup == null) return@withContext StudioSaveResult(false, errorCode = "STUDIO_SNAPSHOT_FAILED", message = "Не удалось создать snapshot существующего custom SNP.")
        }
        val ready = File(repo.paths.updates, "${d.id.lowercase()}.studio-ready")
        ready.deleteRecursively(); ready.mkdirs()
        try {
            File(ready, "adapter.json").writeText(json, Charsets.UTF_8)
            AdapterPackageManager.createManifest(ready, d.id, d.adapterVersion, d.publisher)
            val pkg = AdapterPackageManager.verifyDirectory(ready)
            if (!pkg.valid) return@withContext StudioSaveResult(false, errorCode = pkg.errorCode, message = pkg.errorMessage)
            val old = File(repo.paths.updates, "${d.id.lowercase()}.studio-old")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) return@withContext StudioSaveResult(false, errorCode = "STUDIO_SWAP_FAILED", message = "Не удалось подготовить замену custom SNP.")
            if (!ready.renameTo(target)) {
                if (!target.exists() && old.exists()) old.renameTo(target)
                return@withContext StudioSaveResult(false, errorCode = "STUDIO_SWAP_FAILED", message = "Не удалось активировать SNP.")
            }
            try { overrides.setEnabled(d.id, false) } catch (error: Exception) {
                target.deleteRecursively(); if (old.exists()) old.renameTo(target)
                return@withContext StudioSaveResult(false, errorCode = "STUDIO_OVERRIDE_FAILED", message = "Не удалось зафиксировать disabled override: ${error.message}")
            }
            old.deleteRecursively()
            StudioSaveResult(true, d.id, message = "Custom SNP сохранён выключенным. После ручного включения Studio-SNP запускается в SOLO-режиме для изолированной проверки.")
        } catch (error: Exception) {
            if (!target.exists() && backup != null) runCatching { recovery.restoreSnapshot(d.id, backup.name, target) }
            StudioSaveResult(false, errorCode = "STUDIO_SAVE_EXCEPTION", message = "${error.javaClass.simpleName}: ${error.message}")
        } finally { ready.deleteRecursively() }
    }

    private suspend fun testCandidate(json: String, draft: AdapterStudioDraft?, attempts: Int, model: String, prefix: String): StudioAnalyzeResult {
        val tested = testJson(json)
        return tested.copy(draft = draft, attempts = attempts, model = model, message = "$prefix. ${tested.message}".trim())
    }

    private fun buildUserPrompt(discovery: StudioDiscoveryResult, instruction: String, previousJson: String, feedback: String, attempt: Int): String = buildString {
        appendLine("TARGET URL: ${discovery.finalUrl}")
        appendLine("USER TASK:"); appendLine(instruction.trim()); appendLine()
        appendLine(discovery.evidence); appendLine()
        appendLine("DETERMINISTIC LOCAL DRAFT (valid Schema v3; confirmed discovery values are authoritative):"); appendLine(discovery.localJson)
        if (attempt > 1) { appendLine(); appendLine("PREVIOUS AI CANDIDATE:"); appendLine(previousJson) }
        appendLine(); appendLine("VALIDATOR/LIVE-TEST FEEDBACK:"); appendLine(feedback)
        appendLine(); appendLine("CONFIRMED SEARCH HTML SAMPLE:"); appendLine(compactHtml(discovery.searchHtml, 24_000))
        if (discovery.viewHtml.isNotBlank()) { appendLine(); appendLine("CONFIRMED VIDEO PAGE HTML SAMPLE:"); appendLine(compactHtml(discovery.viewHtml, 24_000)) }
        if (discovery.iframeHtml.isNotBlank()) { appendLine(); appendLine("CONFIRMED IFRAME/PLAYER HTML SAMPLE:"); appendLine(compactHtml(discovery.iframeHtml, 12_000)) }
        appendLine(); appendLine("Return only the complete adapter.json object. No markdown and no explanation.")
    }

    private fun buildSystemPrompt(): String {
        val engines = (EngineRegistry.knownEngineIds + RuntimeEngineRegistry.implementedIds).distinct().sorted().joinToString(", ")
        return """
            You are the Adapter Studio compiler and route auditor for NoodleMobile. Produce one strict Noodle SNP adapter.json using Schema v3.
            The adapter is declarative configuration, not executable code. Never return Kotlin, JavaScript, shell commands or markdown.
            NoodleMobile runtime engines available in this installed build: $engines.
            Resolver/view strategies include: primary, jwplayer, videojs, generic_html5, oembed, iframe, hls, dash.
            DISCOVERY FACTS are produced by NoodleMobile itself after real HTTP probing and are authoritative. They may be a fresh second pass specifically for route auditing.
            Do not replace a confirmed search_template, search_url, site family or view URL with a guessed endpoint. If the fresh evidence agrees with the working draft, preserve it exactly.
            Prefer a detected built-in family engine (kvs_view, kvs_videos, noodle_watch_playlist, novideo_embed, sosalkino_catalog) over declarative_catalog when Discovery identifies it.
            Infer selectors or JSON paths only from supplied confirmed HTML. Do not invent selectors, endpoints, view counts or CDN hosts.
            permissions.allowed_hosts must be the minimum host set visible in confirmed navigation/player/media evidence. Do not grant unrelated domains.
            Keep allow_javascript and allow_cookies false unless the evidence strongly requires them.
            Keep health and conservative repair blocks. Generated adapters must start with enabled=false.
            The output must be one parseable JSON object and must pass NoodleMobile AdapterValidator Schema v3.
        """.trimIndent()
    }

    private fun compactHtml(html: String, maxChars: Int): String {
        val clean = html.replace("\u0000", "")
        if (clean.length <= maxChars) return clean
        val head = (maxChars * 2) / 3
        return clean.take(head) + "\n<!-- NoodleMobile Studio Discovery: middle omitted -->\n" + clean.takeLast(maxChars - head)
    }

    companion object { private const val MAX_AI_ATTEMPTS = 3 }
}

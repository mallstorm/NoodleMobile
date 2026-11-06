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

data class StudioAutoResult(
    val success: Boolean,
    val healthy: Boolean = false,
    val build: pro.noodle.mobile.core.studio.auto.AutoBuildResult? = null,
    val json: String = "",
    val adapterId: String = "",
    val testStatus: String = "",
    val testDetails: String = "",
    val searchCount: Int = 0,
    val playbackReady: Int = 0,
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

    private val autoBuilder = pro.noodle.mobile.core.studio.auto.SnpAutoBuilder(http)

    /**
     * Simple Studio: site + query → learned SNP → real Media3 live test. When static resolving
     * fails the adapter is retried with the sandboxed browser player enabled.
     */
    suspend fun autoCreate(url: String, query: String, onProgress: (String) -> Unit = {}): StudioAutoResult = withContext(Dispatchers.IO) {
        val build = runCatching { autoBuilder.build(url, query, onProgress) }.getOrElse { e ->
            return@withContext StudioAutoResult(false, errorCode = "AUTO_BUILD_CRASH", message = "${e.javaClass.simpleName}: ${e.message}")
        }
        if (!build.success) return@withContext StudioAutoResult(false, build = build, json = build.adapterJson, errorCode = build.errorCode, message = build.message, testDetails = build.report())
        var json = build.adapterJson
        onProgress("Финальная проверка в плеере (Media3)…")
        var test = testJson(json)
        if (test.testStatus == SemanticStatus.PLAYBACK_FAILED.wireName && !build.needsJavaScript) {
            onProgress("Плеер не открыл поток напрямую — пробую через встроенный браузер…")
            val withJs = json.replace("\"allow_javascript\": false", "\"allow_javascript\": true").replace("\"allow_javascript\":false", "\"allow_javascript\":true")
            val retry = testJson(withJs)
            if (retry.testStatus == SemanticStatus.HEALTHY.wireName) { json = withJs; test = retry }
        }
        val counts = Regex("search=(\\d+) • playback=(\\d+)").find(test.testDetails)
        val healthy = test.testStatus == SemanticStatus.HEALTHY.wireName
        StudioAutoResult(
            success = true,
            healthy = healthy,
            build = build,
            json = json,
            adapterId = build.adapterId,
            testStatus = test.testStatus,
            testDetails = test.testDetails + "\n\n" + build.report(),
            searchCount = counts?.groupValues?.get(1)?.toIntOrNull() ?: build.samples.size,
            playbackReady = counts?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            message = if (healthy) "Готово: сайт подключён, поиск и видео работают." else "Адаптер собран, но проверка в плеере не пройдена: ${test.testDetails.substringBefore('\n')}",
        )
    }

    /** Saves the adapter and immediately enables it as a regular (non-SOLO) source. */
    suspend fun installAndEnable(json: String): StudioSaveResult {
        val saved = saveJson(json)
        if (!saved.success) return saved
        runCatching {
            overrides.setEnabled(saved.adapterId, true)
            overrides.setSoloSource(null)
        }.onFailure { return StudioSaveResult(false, saved.adapterId, "STUDIO_ENABLE_FAILED", "SNP сохранён, но не включён: ${it.message}") }
        return saved.copy(message = "Сайт добавлен и включён в общий поиск.")
    }

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
     * Primary creation path: Delphi-style deterministic discovery -> Schema v3 -> local live test.
     * AI is used only as a repair/compiler when deterministic creation is not healthy.
     * Independent AI route validation is intentionally NOT mandatory; it lives in auditRoutes().
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
        if (deterministic.testStatus == SemanticStatus.HEALTHY.wireName) {
            return deterministic.copy(
                message = "Discovery сам создал рабочий SNP; AI не потребовался. При желании запустите AI-проверку маршрутов отдельно в Live test.",
                testDetails = "${d.summary}\n${deterministic.testDetails}".trim(),
            )
        }

        if (provider == StudioAiProvider.LOCAL) {
            return deterministic.copy(
                message = "Discovery создал локальный SNP; live test требует проверки.",
                testDetails = "${d.summary}\n${deterministic.testDetails}".trim(),
            )
        }
        if (!ai.status(provider).connected) {
            return StudioAnalyzeResult(
                false,
                draft = d.draft,
                json = d.localJson,
                errorCode = "STUDIO_AI_LOGIN_REQUIRED",
                message = "Сначала войдите в ${provider.title} или выберите «Без AI».",
                testStatus = deterministic.testStatus,
                testDetails = deterministic.testDetails,
            )
        }

        val effectiveInstruction = instruction.trim().ifBlank {
            "Repair the NoodleMobile SNP using confirmed Discovery facts. Preserve confirmed search routes and site family; fix only fields required to make search and playback pass live test."
        }
        var previousJson = d.localJson
        var feedback = "Deterministic live test before AI repair: status=${deterministic.testStatus}; ${deterministic.testDetails}"
        var lastValid: StudioAnalyzeResult? = deterministic
        var selectedModel = model

        for (attempt in 1..MAX_AI_ATTEMPTS) {
            val userPrompt = buildUserPrompt(
                discovery = d,
                instruction = effectiveInstruction,
                previousJson = previousJson.takeIf { attempt > 1 }.orEmpty(),
                feedback = feedback,
                attempt = attempt,
            )
            val aiResult = runCatching { ai.complete(provider, selectedModel, buildSystemPrompt(), userPrompt) }
            if (aiResult.isFailure) {
                val error = aiResult.exceptionOrNull()
                return lastValid?.copy(
                    message = "Discovery завершён, но AI-repair не прошёл: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}",
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
                feedback = "Live test crashed: ${error?.javaClass?.simpleName}: ${error?.message}. Reuse confirmed search_template=${d.searchTemplate} and engine=${d.engine}."
                val candidate = StudioAnalyzeResult(
                    true,
                    draft = d.draft,
                    json = reply.json,
                    message = "AI сформировал Schema v3, но live test завершился исключением.",
                    testStatus = "error",
                    testDetails = "${d.summary}\n$feedback",
                    attempts = attempt,
                    model = selectedModel,
                )
                lastValid = candidate
                if (attempt == MAX_AI_ATTEMPTS) return candidate
                continue
            }
            val semantic = semanticResult.getOrThrow()
            val details = buildString {
                append(d.summary)
                append("\n")
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
                    "AI-repair создал рабочий SNP на подтверждённых Discovery-данных."
                } else {
                    "AI-адаптер создан; Discovery подтверждён, но live test требует исправления."
                },
                testStatus = semantic.status.wireName,
                testDetails = details,
                attempts = attempt,
                model = selectedModel,
            )
            lastValid = candidate
            if (semantic.status == SemanticStatus.HEALTHY) return candidate

            feedback = buildString {
                append("Live semantic test failed: status=${semantic.status.wireName}; $details. ")
                append("Do not invent a new search endpoint. Confirmed search_template=${d.searchTemplate}; confirmed search_url=${d.searchUrl}; confirmed engine=${d.engine}. ")
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

    /**
     * Optional second opinion. Re-enters the target resource, refreshes Discovery evidence,
     * asks the selected model to audit routes, validates Schema v3 and then performs a final live test.
     * A failed audit never invalidates or overwrites an already-working local SNP.
     */
    suspend fun auditRoutes(
        url: String,
        json: String,
        instruction: String,
        provider: StudioAiProvider,
        model: String,
        probeQuery: String = "",
    ): StudioAnalyzeResult {
        val originalValidation = AdapterValidator.validate(json)
        if (!originalValidation.isValid || originalValidation.definition == null) {
            return StudioAnalyzeResult(false, json = json, errorCode = originalValidation.errorCode, message = originalValidation.errorMessage, testStatus = "invalid_adapter")
        }
        if (provider == StudioAiProvider.LOCAL) {
            return StudioAnalyzeResult(false, json = json, errorCode = "STUDIO_AI_AUDIT_PROVIDER_REQUIRED", message = "Для AI-проверки маршрутов выберите Kilo Code или Cline.", testStatus = "audit_not_run")
        }
        if (!ai.status(provider).connected) {
            return StudioAnalyzeResult(false, json = json, errorCode = "STUDIO_AI_LOGIN_REQUIRED", message = "Сначала войдите в ${provider.title}.", testStatus = "audit_not_run")
        }
        if (model.isBlank()) {
            return StudioAnalyzeResult(false, json = json, errorCode = "STUDIO_AI_MODEL_REQUIRED", message = "Выберите модель для AI-проверки.", testStatus = "audit_not_run")
        }

        val originalTest = testJson(json)
        val query = probeQuery.ifBlank { originalValidation.definition.healthQuery }
        val fresh = discovery.discover(url, query)
        if (!fresh.success || fresh.draft == null) {
            return originalTest.copy(
                json = json,
                message = "Локальный Live Test сохранён; дополнительный AI-аудит не выполнен, потому что повторный Discovery не подтвердил маршруты.",
                testDetails = "${originalTest.testDetails}\nAI audit discovery: ${fresh.errorCode}: ${fresh.message}\n${fresh.evidence}".trim(),
            )
        }

        val auditInstruction = instruction.trim().ifBlank {
            "Audit the current NoodleMobile SNP against the fresh Discovery evidence. Confirm real search, video-page, iframe/player and media routes. Preserve routes that are already confirmed. Return a corrected adapter only when the evidence requires it."
        }
        var feedback = "Existing local Live Test: status=${originalTest.testStatus}; ${originalTest.testDetails}"
        var selectedModel = model
        var previousCandidate = json

        for (attempt in 1..MAX_AI_AUDIT_ATTEMPTS) {
            val prompt = buildAuditPrompt(
                discovery = fresh,
                currentJson = json,
                instruction = auditInstruction,
                feedback = feedback,
                previousCandidate = previousCandidate.takeIf { attempt > 1 }.orEmpty(),
            )
            val reply = runCatching { ai.complete(provider, selectedModel, buildSystemPrompt(), prompt) }.getOrElse { error ->
                return originalTest.copy(
                    json = json,
                    message = "Локальный Live Test сохранён; AI-проверка маршрутов не завершена: ${error.message ?: error.javaClass.simpleName}",
                    testDetails = "${originalTest.testDetails}\nFresh Discovery: ${fresh.summary}".trim(),
                    attempts = attempt,
                    model = selectedModel,
                )
            }
            selectedModel = reply.model
            previousCandidate = reply.json
            val validation = AdapterValidator.validate(reply.json)
            if (!validation.isValid || validation.definition == null) {
                feedback = "AI audit candidate failed Schema v3 validation: ${validation.errorCode}: ${validation.errorMessage}. Keep confirmed routes and return a valid complete adapter.json."
                continue
            }

            val semantic = runCatching { probe.validateDefinition(validation.definition) }.getOrElse { error ->
                feedback = "AI audit candidate live test crashed: ${error.javaClass.simpleName}: ${error.message}. Preserve confirmed search_template=${fresh.searchTemplate} and engine=${fresh.engine}."
                null
            } ?: continue

            val details = buildString {
                append("AI route audit • fresh Discovery: ${fresh.summary}\n")
                append(semantic.message)
                if (semantic.errorCode.isNotBlank()) append(" [${semantic.errorCode}]")
                append(" • search=${semantic.searchResultCount}")
                append(" • playback=${semantic.playbackSourceCount}")
            }
            if (semantic.status == SemanticStatus.HEALTHY) {
                return StudioAnalyzeResult(
                    success = true,
                    draft = fresh.draft,
                    json = reply.json,
                    message = "AI-проверка маршрутов пройдена; финальный Live Test healthy.",
                    testStatus = semantic.status.wireName,
                    testDetails = details,
                    attempts = attempt,
                    model = selectedModel,
                )
            }
            feedback = "AI audit live test failed: status=${semantic.status.wireName}; $details. Do not invent endpoints; use confirmed search_template=${fresh.searchTemplate}, search_url=${fresh.searchUrl}, engine=${fresh.engine}."
        }

        return originalTest.copy(
            json = json,
            message = "Исходный SNP оставлен без изменений: дополнительный AI-аудит не смог подтвердить улучшенный вариант.",
            testDetails = "${originalTest.testDetails}\nFresh Discovery: ${fresh.summary}\n$feedback".trim(),
            attempts = MAX_AI_AUDIT_ATTEMPTS,
            model = selectedModel,
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

    private fun buildAuditPrompt(
        discovery: StudioDiscoveryResult,
        currentJson: String,
        instruction: String,
        feedback: String,
        previousCandidate: String,
    ): String = buildString {
        appendLine("OPTIONAL AI ROUTE AUDIT FOR NOODLEMOBILE")
        appendLine("TARGET URL: ${discovery.finalUrl}")
        appendLine("AUDIT TASK:"); appendLine(instruction); appendLine()
        appendLine(discovery.evidence); appendLine()
        appendLine("CURRENT USER-APPROVED/EDITABLE SNP:"); appendLine(currentJson)
        if (previousCandidate.isNotBlank()) { appendLine(); appendLine("PREVIOUS AUDIT CANDIDATE:"); appendLine(previousCandidate) }
        appendLine(); appendLine("CURRENT LOCAL LIVE-TEST RESULT:"); appendLine(feedback)
        appendLine(); appendLine("FRESH SEARCH HTML SAMPLE:"); appendLine(compactHtml(discovery.searchHtml, 24_000))
        if (discovery.viewHtml.isNotBlank()) { appendLine(); appendLine("FRESH VIDEO PAGE HTML SAMPLE:"); appendLine(compactHtml(discovery.viewHtml, 24_000)) }
        if (discovery.iframeHtml.isNotBlank()) { appendLine(); appendLine("FRESH IFRAME/PLAYER HTML SAMPLE:"); appendLine(compactHtml(discovery.iframeHtml, 12_000)) }
        appendLine()
        appendLine("This is validation, not mandatory creation. Preserve a working route unless fresh evidence contradicts it. Return only the complete audited adapter.json object.")
    }

    private fun buildSystemPrompt(): String {
        val engines = (EngineRegistry.knownEngineIds + RuntimeEngineRegistry.implementedIds).distinct().sorted().joinToString(", ")
        return """
            You are the Adapter Studio compiler and route auditor for NoodleMobile. Produce one strict Noodle SNP adapter.json using Schema v3.
            The adapter is declarative configuration, not executable code. Never return Kotlin, JavaScript, shell commands or markdown.
            NoodleMobile runtime engines available in this installed build: $engines.
            Resolver/view strategies include: primary, jwplayer, videojs, generic_html5, oembed, iframe, hls, dash.
            DISCOVERY FACTS are produced by NoodleMobile itself after real HTTP probing and are authoritative.
            Do not replace a confirmed search_template, search_url, site family or view URL with a guessed endpoint. If fresh evidence agrees with the working draft, preserve it exactly.
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

    companion object {
        private const val MAX_AI_ATTEMPTS = 3
        private const val MAX_AI_AUDIT_ATTEMPTS = 2
    }
}

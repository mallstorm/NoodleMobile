package pro.noodle.mobile.core.studio

import android.content.Context
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.source.AdapterProbeService
import pro.noodle.mobile.core.source.SemanticStatus

/**
 * Optional post-HTTP recovery used by Studio when deterministic creation is not healthy.
 * Recovery is still fully local before AI: original Delphi-style routes -> expanded CMS route
 * catalogue -> sandboxed browser execution. The browser receives the strongest deterministic
 * candidate, so dynamic skins benefit from the broader engine knowledge as well.
 */
class StudioBrowserRecoveryService(context: Context) {
    private val app = context.applicationContext
    private val http = SecureHttpClient()
    private val discovery = StudioDiscovery(http)
    private val knownRoutes = StudioKnownRouteDiscovery(http)
    private val browser = StudioBrowserDiscovery(app)
    private val probe = AdapterProbeService(app)

    suspend fun recover(url: String, probeQuery: String = ""): StudioAnalyzeResult {
        val deterministic = discovery.discover(url, probeQuery)
        val expanded = knownRoutes.discover(url, probeQuery, deterministic)
        val seed = when {
            expanded.success && !deterministic.success -> expanded
            expanded.success && expanded.searchTemplate.isNotBlank() &&
                !expanded.searchTemplate.equals(deterministic.searchTemplate, true) -> expanded
            expanded.success && expanded.score > deterministic.score -> expanded
            else -> deterministic
        }
        val rendered = browser.recover(url, seed, probeQuery)
        if (!rendered.success || rendered.draft == null || rendered.localJson.isBlank()) {
            return StudioAnalyzeResult(
                success = false,
                json = rendered.localJson,
                errorCode = rendered.errorCode.ifBlank { "STUDIO_BROWSER_DISCOVERY_FAILED" },
                message = rendered.message.ifBlank { "Расширенный локальный Discovery/Browser Discovery не смог подтвердить динамические маршруты." },
                testStatus = "browser_discovery_failed",
                testDetails = rendered.evidence.ifBlank { rendered.summary },
            )
        }
        val validation = AdapterValidator.validate(rendered.localJson)
        if (!validation.isValid || validation.definition == null) {
            return StudioAnalyzeResult(
                success = false,
                draft = rendered.draft,
                json = rendered.localJson,
                errorCode = validation.errorCode,
                message = validation.errorMessage,
                testStatus = "invalid_adapter",
                testDetails = rendered.evidence,
            )
        }
        val semantic = runCatching { probe.validateDefinition(validation.definition) }.getOrElse { error ->
            return StudioAnalyzeResult(
                success = true,
                draft = rendered.draft,
                json = rendered.localJson,
                message = "Расширенный Browser Discovery создал Schema v3, но Live Test завершился исключением.",
                testStatus = "error",
                testDetails = "${rendered.summary}\n${error.javaClass.simpleName}: ${error.message}",
            )
        }
        return StudioAnalyzeResult(
            success = true,
            draft = rendered.draft,
            json = rendered.localJson,
            message = if (semantic.status == SemanticStatus.HEALTHY) {
                "Расширенный локальный Discovery подтвердил движок/маршрут; Live Test healthy."
            } else {
                "Расширенный Discovery подтвердил маршруты; Live Test ещё требует repair."
            },
            testStatus = semantic.status.wireName,
            testDetails = "${rendered.summary}\n${semantic.message}${semantic.errorCode.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()} • search=${semantic.searchResultCount} • playback=${semantic.playbackSourceCount}",
        )
    }
}

package pro.noodle.mobile.core.studio

import android.content.Context
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.source.AdapterProbeService
import pro.noodle.mobile.core.source.SemanticStatus

/**
 * Optional post-HTTP recovery used by Studio when deterministic creation is not healthy.
 * It keeps browser execution out of the mandatory path and only upgrades the candidate when
 * the rendered post-JS DOM produces a valid Schema v3 adapter and a better live-test result.
 */
class StudioBrowserRecoveryService(context: Context) {
    private val app = context.applicationContext
    private val discovery = StudioDiscovery(SecureHttpClient())
    private val browser = StudioBrowserDiscovery(app)
    private val probe = AdapterProbeService(app)

    suspend fun recover(url: String, probeQuery: String = ""): StudioAnalyzeResult {
        val deterministic = discovery.discover(url, probeQuery)
        val rendered = browser.recover(url, deterministic, probeQuery)
        if (!rendered.success || rendered.draft == null || rendered.localJson.isBlank()) {
            return StudioAnalyzeResult(
                success = false,
                json = rendered.localJson,
                errorCode = rendered.errorCode.ifBlank { "STUDIO_BROWSER_DISCOVERY_FAILED" },
                message = rendered.message.ifBlank { "Browser Discovery не смог подтвердить динамические маршруты." },
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
                message = "Browser Discovery создал Schema v3, но Live Test завершился исключением.",
                testStatus = "error",
                testDetails = "${rendered.summary}\n${error.javaClass.simpleName}: ${error.message}",
            )
        }
        return StudioAnalyzeResult(
            success = true,
            draft = rendered.draft,
            json = rendered.localJson,
            message = if (semantic.status == SemanticStatus.HEALTHY) {
                "Browser Discovery подтвердил динамический сайт; Live Test healthy."
            } else {
                "Browser Discovery подтвердил динамические маршруты; Live Test ещё требует repair."
            },
            testStatus = semantic.status.wireName,
            testDetails = "${rendered.summary}\n${semantic.message}${semantic.errorCode.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()} • search=${semantic.searchResultCount} • playback=${semantic.playbackSourceCount}",
        )
    }
}

package pro.noodle.mobile.core.source

import android.content.Context
import pro.noodle.mobile.core.json.JsonValue
import java.io.File
import java.net.URI

/**
 * Sanitized runtime telemetry for Source Center diagnostics. Query strings, cookies and auth
 * headers are never persisted. URLs are reduced to scheme://host/path so a diagnostics export can
 * explain routing/playback failures without leaking user queries or tokens.
 */
class SourceRuntimeDiagnosticsStore(context: Context) {
    private val repo = RuntimeAdapterRepository(context.applicationContext)
    private val store = SourceStatusStore(File(repo.paths.diagnostics, "runtime_state.json"))

    fun recordSearch(
        sourceId: String,
        engine: String,
        url: String,
        httpStatus: Int,
        latencyMs: Long,
        resultCount: Int,
        errorCode: String = "",
    ) {
        store.put(sourceId, mapOf(
            "engine" to JsonValue.Str(engine),
            "last_search_url" to JsonValue.Str(sanitizeUrl(url)),
            "search_http" to JsonValue.Num(httpStatus.toDouble()),
            "search_latency_ms" to JsonValue.Num(latencyMs.coerceAtLeast(0).toDouble()),
            "search_count" to JsonValue.Num(resultCount.coerceAtLeast(0).toDouble()),
            "search_error" to JsonValue.Str(errorCode),
            "search_at" to JsonValue.Num(System.currentTimeMillis().toDouble()),
        ))
    }

    fun recordPlayback(
        sourceId: String,
        watchUrl: String,
        mediaUrl: String,
        status: String,
        errorCode: String = "",
    ) {
        store.put(sourceId, mapOf(
            "last_watch_url" to JsonValue.Str(sanitizeUrl(watchUrl)),
            "last_playback_url" to JsonValue.Str(sanitizeUrl(mediaUrl)),
            "playback_status" to JsonValue.Str(status),
            "playback_error" to JsonValue.Str(errorCode),
            "playback_at" to JsonValue.Num(System.currentTimeMillis().toDouble()),
        ))
    }

    fun summary(sourceId: String): String = buildString {
        val engine = store.get(sourceId, "engine")
        val searchUrl = store.get(sourceId, "last_search_url")
        val searchHttp = store.getInt(sourceId, "search_http")
        val searchMs = store.getLong(sourceId, "search_latency_ms")
        val searchCount = store.getInt(sourceId, "search_count")
        val playbackUrl = store.get(sourceId, "last_playback_url")
        val playbackStatus = store.get(sourceId, "playback_status")
        val playbackError = store.get(sourceId, "playback_error")
        if (engine.isNotBlank()) append("engine=$engine")
        if (searchUrl.isNotBlank()) appendSeparator().append("search=$searchUrl")
        if (searchHttp > 0) appendSeparator().append("HTTP $searchHttp")
        if (searchMs > 0) appendSeparator().append("${searchMs}ms")
        if (searchCount > 0) appendSeparator().append("cards=$searchCount")
        if (playbackUrl.isNotBlank()) appendSeparator().append("media=$playbackUrl")
        if (playbackStatus.isNotBlank()) appendSeparator().append("playback=$playbackStatus")
        if (playbackError.isNotBlank()) appendSeparator().append("error=$playbackError")
    }

    private fun StringBuilder.appendSeparator(): StringBuilder {
        if (isNotEmpty()) append(" • ")
        return this
    }

    private fun sanitizeUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val u = URI(raw)
            if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) ""
            else "${u.scheme.lowercase()}://${u.host.lowercase()}${u.path.orEmpty().take(240)}"
        }.getOrDefault("")
    }
}

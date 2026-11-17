package pro.noodle.mobile.core.download

import android.content.Context

/**
 * Hot-link protected CDNs reject media requests without the page Referer. The Referer that made
 * playback work is remembered per download (and per source) so background downloads reuse it.
 */
object SourceReferers {
    private const val PREFS = "noodle_referers"

    fun remember(context: Context, downloadId: String, sourceKey: String, referer: String) {
        if (referer.isBlank()) return
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("dl:$downloadId", referer)
                .putString("src:$sourceKey", referer)
                .apply()
        }
    }

    fun forDownload(context: Context, downloadId: String, sourceKey: String): String = runCatching {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("dl:$downloadId", null) ?: p.getString("src:$sourceKey", null).orEmpty()
    }.getOrDefault("")
}

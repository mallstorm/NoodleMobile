package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.MediaSource

/**
 * Generic playback resolver for the large family of JS players that ultimately expose a public
 * MP4/HLS/DASH URL in markup or configuration. Studio can fingerprint the concrete player family,
 * while this engine keeps the runtime small: we do not need a separate parser class for every skin.
 */
class GenericPlayerConfigEngine : SourceEngine {
    override val id = "generic_player_config"
    override val aliases = setOf(
        "player_config", "js_player", "plyr", "flowplayer", "clappr", "shaka", "shaka_player",
        "hlsjs", "hls.js", "dashjs", "dash.js", "mediaelement", "mediaelementjs", "vidstack",
        "fluidplayer", "dplayer", "artplayer", "openplayerjs", "projekktor", "jplayer", "ableplayer",
        "bitmovin", "theoplayer", "azure_media_player", "cloudinary_player", "mux_player",
        "cloudflare_stream", "bunny_stream", "wistia_player", "brightcove_player", "kaltura_player",
    )

    override fun resolvePlayback(text: String, pageUrl: String): EngineResolveResult {
        if (text.isBlank()) return EngineResolveResult()
        val handled = PLAYER_MARKERS.any { text.contains(it, ignoreCase = true) } ||
            MEDIA_URL_RE.containsMatchIn(text) || CONFIG_KEY_RE.containsMatchIn(text)
        if (!handled) return EngineResolveResult()

        val sources = mutableListOf<MediaSource>()
        val seen = linkedSetOf<String>()
        fun add(raw: String, label: String = "") {
            val decoded = EngineParsing.htmlDecode(raw)
                .replace("\\/", "/")
                .replace("\\u0026", "&", true)
                .trim()
            val absolute = EngineParsing.absolute(decoded, pageUrl)
            if (absolute.isBlank() || !absolute.startsWith("http", true) || !looksLikeMedia(absolute)) return
            if (!seen.add(absolute.lowercase())) return
            val protocol = EngineParsing.protocol(absolute)
            val mime = when (protocol) {
                "hls" -> "application/vnd.apple.mpegurl"
                "dash" -> "application/dash+xml"
                else -> if (absolute.substringBefore('?').endsWith(".webm", true)) "video/webm" else "video/mp4"
            }
            sources += MediaSource(
                label = EngineParsing.inferLabel(absolute, mime, label),
                url = absolute,
                mimeType = mime,
                protocol = protocol,
                isDefault = sources.isEmpty(),
            )
        }

        CONFIG_KEY_RE.findAll(text).forEach { m -> add(m.groupValues[2]) }
        DATA_ATTR_RE.findAll(text).forEach { m -> add(m.groupValues[2]) }
        MEDIA_URL_RE.findAll(text).forEach { m -> add(m.value) }

        // Mux exposes a deterministic HLS URL for a public playback-id.
        Regex("<mux-player\\b[^>]*\\bplayback-id=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(text).forEach { m ->
                val id = m.groupValues[1].trim()
                if (id.matches(Regex("[A-Za-z0-9_-]{6,256}"))) add("https://stream.mux.com/$id.m3u8", "Mux HLS")
            }

        // Cloudflare Stream documents /iframe and /manifest/video.m3u8 on the same video UID.
        Regex("https://customer-[A-Za-z0-9-]+\\.cloudflarestream\\.com/([^/?#\"']+)/(?:iframe|manifest/video\\.m3u8)", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val origin = m.value.substringBefore("/${m.groupValues[1]}/")
                add("$origin/${m.groupValues[1]}/manifest/video.m3u8", "Cloudflare HLS")
            }

        val followUps = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(text)
            .map { EngineParsing.absolute(it.groupValues[1], pageUrl) }
            .filter { it.startsWith("https://", true) }
            .distinct()
            .take(8)
            .toList()

        val ok = sources.isNotEmpty() || followUps.isNotEmpty()
        return EngineResolveResult(
            handled = true,
            success = sources.isNotEmpty(),
            title = EngineParsing.titleFromHtml(text),
            thumbnailUrl = EngineParsing.imageFromHtml(text, pageUrl),
            playerPageUrl = followUps.firstOrNull().orEmpty().ifBlank { pageUrl },
            sources = sources,
            followUpPages = followUps,
            errorCode = if (ok) "" else "PLAYER_CONFIG_MEDIA_NOT_FOUND",
            errorMessage = if (ok) "" else "Плеер распознан, но открытый MP4/HLS/DASH URL не найден.",
        )
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
            path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            lower.contains("manifest/video.m3u8") || lower.contains("/playlist.m3u8") ||
            lower.contains("/master.m3u8") || lower.contains("stream.mux.com/")
    }

    companion object {
        private val CONFIG_KEY_RE = Regex(
            "(?:[\"']?(?:file|src|source|video_url|videoUrl|contentUrl|manifest|hls|dash)[\"']?)\\s*[:=]\\s*([\"'])(https?://[^\"']+)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val DATA_ATTR_RE = Regex(
            "\\b(data-(?:src|video|hls|dash|source)|src)=[\"'](https?://[^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        )
        private val MEDIA_URL_RE = Regex(
            "https?://[^\"'<>\\s\\\\]+(?:\\.(?:mp4|m4v|webm|m3u8|mpd)(?:\\?[^\"'<>\\s\\\\]*)?|/manifest/video\\.m3u8(?:\\?[^\"'<>\\s\\\\]*)?)",
            RegexOption.IGNORE_CASE,
        )
        private val PLAYER_MARKERS = listOf(
            "new Plyr(", "plyr__video", "flowplayer(", "new Clappr.Player", "shaka.Player",
            "new Hls(", "dashjs.MediaPlayer", "mediaelementplayer(", "<media-player", "new DPlayer(",
            "new Artplayer(", "fluidPlayer(", "OpenPlayerJS", "projekktor(", ".jPlayer(", "AblePlayer",
            "bitmovin.player", "THEOplayer.Player", "azuremediaplayer", "cloudinary.videoPlayer",
            "<mux-player", "cloudflarestream.com", "mediadelivery.net/embed", "fast.wistia.net/embed",
            "players.brightcove.net", "kaltura", "jwplayer(", "videojs(", "video-js",
        )
    }
}

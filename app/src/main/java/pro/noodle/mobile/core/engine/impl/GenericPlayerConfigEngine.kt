package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.MediaSource

/**
 * Generic playback resolver for the large family of JS players that ultimately expose a public
 * MP4/HLS/DASH URL in markup or configuration. Studio fingerprints the concrete player family,
 * while this engine keeps the runtime small: one audited resolver covers many skins/frameworks.
 */
class GenericPlayerConfigEngine : SourceEngine {
    override val id = "generic_player_config"
    override val aliases = setOf(
        "player_config", "js_player", "plyr", "flowplayer", "clappr", "shaka", "shaka_player",
        "hlsjs", "hls.js", "dashjs", "dash.js", "mediaelement", "mediaelementjs", "vidstack",
        "fluidplayer", "dplayer", "artplayer", "openplayerjs", "projekktor", "jplayer", "ableplayer",
        "bitmovin", "theoplayer", "azure_media_player", "cloudinary_player", "mux_player",
        "cloudflare_stream", "bunny_stream", "wistia_player", "brightcove_player", "kaltura_player",
        "playerjs", "fvplayer", "presto_player", "paella", "rxplayer", "chimee", "afterglow",
        "amalia", "leanback", "sublimevideo", "vime", "videogular", "media_chrome", "video_react",
        "uppod", "kinescope", "boomstream", "rutube", "vk_video", "ok_video", "mailru_video",
        "rumble", "streamable", "loom", "twitch", "odysee", "gumlet", "dacast", "vidyard",
        "cincopa", "twentythree", "streamshark",
    )

    override fun resolvePlayback(text: String, pageUrl: String): EngineResolveResult {
        if (text.isBlank()) return EngineResolveResult()
        // JS configs often escape slashes. Normalize a working copy before fingerprint/media regexes.
        val normalizedText = text.replace("\\/", "/").replace("\\u0026", "&", true)
        val handled = PLAYER_MARKERS.any { normalizedText.contains(it, ignoreCase = true) } ||
            MEDIA_URL_RE.containsMatchIn(normalizedText) || CONFIG_KEY_RE.containsMatchIn(normalizedText) ||
            PROVIDER_PAGE_RE.containsMatchIn(normalizedText)
        if (!handled) return EngineResolveResult()

        val sources = mutableListOf<MediaSource>()
        val seen = linkedSetOf<String>()
        fun add(raw: String, label: String = "") {
            val decoded = EngineParsing.htmlDecode(raw)
                .replace("\\/", "/")
                .replace("\\u0026", "&", true)
                .trim()
                .trim('"', '\'', ' ')
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
        fun addConfigValue(raw: String) {
            val decoded = EngineParsing.htmlDecode(raw).replace("\\/", "/").replace("\\u0026", "&", true)
            // PlayerJS and several legacy players allow a single `file` string containing multiple
            // quality URLs. Extract individual media URLs first, then try the whole value.
            MEDIA_URL_RE.findAll(decoded).forEach { add(it.value) }
            add(decoded)
        }

        CONFIG_KEY_RE.findAll(normalizedText).forEach { m -> addConfigValue(m.groupValues[2]) }
        DATA_ATTR_RE.findAll(normalizedText).forEach { m -> addConfigValue(m.groupValues[2]) }
        MEDIA_URL_RE.findAll(normalizedText).forEach { m -> add(m.value) }

        // Mux exposes a deterministic HLS URL for a public playback-id.
        Regex("<mux-player\\b[^>]*\\bplayback-id=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(normalizedText).forEach { m ->
                val id = m.groupValues[1].trim()
                if (id.matches(Regex("[A-Za-z0-9_-]{6,256}"))) add("https://stream.mux.com/$id.m3u8", "Mux HLS")
            }

        // Cloudflare Stream documents /iframe and /manifest/video.m3u8 on the same public video UID.
        Regex("https://customer-[A-Za-z0-9-]+\\.cloudflarestream\\.com/([^/?#\"']+)/(?:iframe|manifest/video\\.m3u8)", RegexOption.IGNORE_CASE)
            .findAll(normalizedText).forEach { m ->
                val origin = m.value.substringBefore("/${m.groupValues[1]}/")
                add("$origin/${m.groupValues[1]}/manifest/video.m3u8", "Cloudflare HLS")
            }

        val followUps = linkedSetOf<String>()
        fun follow(raw: String) {
            val u = EngineParsing.absolute(EngineParsing.htmlDecode(raw).replace("\\/", "/"), pageUrl)
            if (u.startsWith("https://", true)) followUps += u
        }
        Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(normalizedText).forEach { follow(it.groupValues[1]) }
        // Some providers use custom elements or JS configuration instead of a literal iframe.
        PROVIDER_PAGE_RE.findAll(normalizedText).forEach { follow(it.value) }
        Regex("<kinescope-iframe-player\\b[^>]*\\burl=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(normalizedText).forEach { follow(it.groupValues[1]) }

        val limitedFollowUps = followUps.take(12)
        val ok = sources.isNotEmpty() || limitedFollowUps.isNotEmpty()
        return EngineResolveResult(
            handled = true,
            success = sources.isNotEmpty(),
            title = EngineParsing.titleFromHtml(normalizedText),
            thumbnailUrl = EngineParsing.imageFromHtml(normalizedText, pageUrl),
            playerPageUrl = limitedFollowUps.firstOrNull().orEmpty().ifBlank { pageUrl },
            sources = sources,
            followUpPages = limitedFollowUps,
            errorCode = if (ok) "" else "PLAYER_CONFIG_MEDIA_NOT_FOUND",
            errorMessage = if (ok) "" else "Плеер распознан, но открытый MP4/HLS/DASH URL не найден.",
        )
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
            path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".m4s") ||
            lower.contains("manifest/video.m3u8") || lower.contains("/playlist.m3u8") ||
            lower.contains("/master.m3u8") || lower.contains("/manifest.mpd") ||
            lower.contains("stream.mux.com/")
    }

    companion object {
        private val CONFIG_KEY_RE = Regex(
            "(?:[\"']?(?:file|src|source|video_url|videoUrl|contentUrl|content_url|manifest|manifestUrl|hls|hlsUrl|dash|dashUrl|streamUrl|playbackUrl)[\"']?)\\s*[:=]\\s*([\"'])((?:(?:https?:)?//|/)[^\"']+)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val DATA_ATTR_RE = Regex(
            "\\b(data-(?:src|video|hls|dash|source|manifest|playback-url)|src|url)=[\"']((?:(?:https?:)?//|/)[^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        )
        private val MEDIA_URL_RE = Regex(
            "(?:(?:https?:)?//)[^\"'<>\\s\\\\,]+(?:\\.(?:mp4|m4v|webm|m3u8|mpd)(?:\\?[^\"'<>\\s\\\\,]*)?|/manifest/video\\.m3u8(?:\\?[^\"'<>\\s\\\\,]*)?)",
            RegexOption.IGNORE_CASE,
        )
        private val PROVIDER_PAGE_RE = Regex(
            "https://(?:kinescope\\.io/(?:embed/)?[^\"'<>\\s]+|play\\.boomstream\\.com/[^\"'<>\\s]+|rutube\\.ru/play/embed/[^\"'<>\\s]+|(?:www\\.)?vk\\.com/video_ext\\.php\\?[^\"'<>\\s]+|ok\\.ru/videoembed/[^\"'<>\\s]+|my\\.mail\\.ru/video/embed/[^\"'<>\\s]+|rumble\\.com/embed/[^\"'<>\\s]+|streamable\\.com/(?:e|o)/[^\"'<>\\s]+|(?:www\\.)?loom\\.com/embed/[^\"'<>\\s]+|player\\.twitch\\.tv/[^\"'<>\\s]+|odysee\\.com/\\$/embed/[^\"'<>\\s]+|play\\.gumlet\\.io/embed/[^\"'<>\\s]+|iframe\\.dacast\\.com/[^\"'<>\\s]+|play\\.vidyard\\.com/[^\"'<>\\s]+|embed\\.streamshark\\.io/[^\"'<>\\s]+)",
            RegexOption.IGNORE_CASE,
        )
        private val PLAYER_MARKERS = listOf(
            "new Plyr(", "plyr__video", "flowplayer(", "new Clappr.Player", "shaka.Player",
            "new Hls(", "dashjs.MediaPlayer", "mediaelementplayer(", "<media-player", "new DPlayer(",
            "new Artplayer(", "fluidPlayer(", "OpenPlayerJS", "projekktor(", ".jPlayer(", "AblePlayer",
            "bitmovin.player", "THEOplayer.Player", "azuremediaplayer", "cloudinary.videoPlayer",
            "<mux-player", "cloudflarestream.com", "mediadelivery.net/embed", "fast.wistia.net/embed",
            "players.brightcove.net", "kaltura", "jwplayer(", "videojs(", "video-js", "new Playerjs(",
            "playerjs.js", "PlayerjsAsync", "presto-player", "fv-player", "paella", "new RxPlayer(",
            "new Chimee(", "afterglow", "amalia-player", "LeanBackPlayer", "sublimevideo", "<vm-player",
            "videogular", "<media-controller", "video-react", "uppod", "kinescope.io", "boomstream.com",
            "rutube.ru/play/embed/", "vk.com/video_ext.php", "ok.ru/videoembed/", "my.mail.ru/video/embed/",
            "rumble.com/embed/", "streamable.com/e/", "loom.com/embed/", "player.twitch.tv/", "odysee.com/$/embed/",
            "play.gumlet.io/embed/", "iframe.dacast.com/", "play.vidyard.com/", "embed.streamshark.io/",
        )
    }
}

package pro.noodle.mobile.core.studio

/**
 * Fingerprint catalogue used only by local Studio discovery. A profile is not a promise that a
 * provider-specific private API can be called; it tells Studio which public search routes and
 * playback strategies are worth trying before AI repair.
 */
internal data class StudioEngineProfile(
    val id: String,
    val label: String,
    val family: String,
    val markers: List<String>,
    val searchTemplates: List<String> = emptyList(),
    val playbackStrategy: String = "generic_player_config",
    val requiresJavaScript: Boolean = false,
    val allowCookies: Boolean = false,
    val minHits: Int = 1,
)

internal data class StudioEngineMatch(val profile: StudioEngineProfile, val hits: Int)

internal object StudioEngineKnowledge {
    val profiles: List<StudioEngineProfile> = listOf(
        // Video CMS / site engines.
        p("kvs", "Kernel Video Sharing", "cms", listOf("kt_player", "flashvars", "/get_file/", "kvs_player"), listOf("/search/?q={query}", "/search/{query}/"), "jwplayer_config"),
        p("wordpress_video", "WordPress video site", "cms", listOf("wp-content/", "wp-includes/", "/wp-json/"), listOf("/?s={query}", "/search/{query}/"), "generic_player_config"),
        p("clipbucket", "ClipBucket", "cms", listOf("clipbucket", "cb_video", "embed_player.php?vid=", "watch_video.php"), listOf("/search_result.php?query={query}", "/search_result.php?query={query}&type=videos", "/search/?q={query}"), "videojs_config"),
        p("avideo", "AVideo / YouPHPTube", "cms", listOf("YouPHPTube", "/plugin/API/", "AVideo Platform"), listOf("/search?search={query}", "/?search={query}", "/videos?search={query}"), "generic_player_config"),
        p("peertube", "PeerTube", "cms", listOf("peertube", "/api/v1/videos", "/api/v1/search/videos"), listOf("/search?search={query}", "/search?search={query}&resultType=videos"), "generic_player_config", true),
        p("mediacms", "MediaCMS", "cms", listOf("MediaCMS", "/api/v1/media", "mediacms.io"), listOf("/search?q={query}", "/search?query={query}", "/media/?q={query}"), "videojs_config", true),
        p("phpvibe", "PHPVibe", "cms", listOf("PHPVibe", "vibe-content", "phpvibe.com"), listOf("/search/{query}", "/search?key={query}", "/?s={query}"), "generic_player_config"),
        p("playtube", "PlayTube", "cms", listOf("PlayTube", "pt_page_margin", "pt_"), listOf("/search/{query}", "/search?keyword={query}", "/search?q={query}"), "videojs_config", true, true),
        p("datalife", "DataLife Engine", "cms", listOf("dle-content", "dle-ajax", "DataLife Engine"), listOf("/index.php?do=search&subaction=search&story={query}", "/?do=search&subaction=search&story={query}")),
        p("drupal", "Drupal media site", "cms", listOf("drupalSettings", "/sites/default/files/", "Drupal.settings"), listOf("/search/node?keys={query}", "/search/node?keys={query}&page=0")),
        p("joomla", "Joomla media site", "cms", listOf("Joomla!", "/media/system/js/", "option=com_"), listOf("/component/search/?searchword={query}&searchphrase=all", "/index.php?option=com_search&searchword={query}")),
        p("phpmelody", "PHP Melody", "cms", listOf("PHP Melody", "phpmelody", "pm-video"), listOf("/search.php?keywords={query}", "/search.php?q={query}")),
        p("cumulusclips", "CumulusClips", "cms", listOf("CumulusClips", "cumulusclips"), listOf("/search/?q={query}", "/search/{query}/")),
        p("owncast", "Owncast", "cms", listOf("owncast", "/api/status", "owncast.online"), playback = "hls_manifest", js = true),
        p("mediagoblin", "GNU MediaGoblin", "cms", listOf("MediaGoblin", "mediagoblin", "/u/"), listOf("/search/?q={query}", "/search?q={query}")),
        p("tubeace", "TubeAce / tube script family", "cms", listOf("tubeace", "tube ace"), listOf("/search/{query}", "/search?q={query}")),
        p("adultvideoscript", "Adult Video Script family", "cms", listOf("Adult Video Script", "avs_player", "video_player.php"), listOf("/search/{query}", "/search.php?search={query}")),

        // Player frameworks. Most ultimately surface HTML5, MP4, HLS, DASH or an iframe.
        p("jwplayer", "JW Player", "player", listOf("jwplayer(", "jwplayer.js", ".setup({"), playback = "jwplayer_config"),
        p("videojs", "Video.js", "player", listOf("video-js", "videojs(", "video.min.js"), playback = "videojs_config"),
        p("plyr", "Plyr", "player", listOf("new Plyr(", "plyr__video", "cdn.plyr.io")),
        p("flowplayer", "Flowplayer", "player", listOf("flowplayer(", "flowplayer.min.js", "fp-player")),
        p("clappr", "Clappr", "player", listOf("new Clappr.Player", "clappr.min.js", "@clappr/player")),
        p("shaka", "Shaka Player", "player", listOf("shaka.Player", "shaka-player", "shaka.ui.Overlay")),
        p("hlsjs", "hls.js", "player", listOf("new Hls(", "hls.js", "Hls.isSupported"), playback = "hls_manifest"),
        p("dashjs", "dash.js", "player", listOf("dashjs.MediaPlayer", "dash.all.min.js", "application/dash+xml"), playback = "dash_manifest"),
        p("mediaelement", "MediaElement.js", "player", listOf("mediaelementplayer(", "mejs__", "MediaElementPlayer")),
        p("vidstack", "Vidstack", "player", listOf("<media-player", "vidstack", "@vidstack/player")),
        p("fluidplayer", "Fluid Player", "player", listOf("fluidPlayer(", "fluidplayer.min.js")),
        p("dplayer", "DPlayer", "player", listOf("new DPlayer(", "dplayer.min.js")),
        p("artplayer", "Artplayer", "player", listOf("new Artplayer(", "artplayer.js")),
        p("openplayerjs", "OpenPlayerJS", "player", listOf("OpenPlayerJS", "new OpenPlayer(", "openplayerjs")),
        p("projekktor", "Projekktor", "player", listOf("projekktor(", "projekktor.min.js")),
        p("jplayer", "jPlayer", "player", listOf(".jPlayer(", "jquery.jplayer")),
        p("ableplayer", "Able Player", "player", listOf("AblePlayer", "ableplayer.min.js")),
        p("bitmovin", "Bitmovin Player", "player", listOf("bitmovin.player", "BitmovinPlayer", "bitmovin-player"), js = true),
        p("theoplayer", "THEOplayer", "player", listOf("THEOplayer.Player", "theoplayer.com", "THEOplayer"), js = true),
        p("azuremedia", "Azure Media Player", "player", listOf("azuremediaplayer", "amp.azure.net", "amp("), js = true),
        p("cloudinary", "Cloudinary Video Player", "player", listOf("cloudinary.videoPlayer", "cloudinary-core", "cloudinary-video-player"), js = true),
        p("mux", "Mux Player", "provider", listOf("<mux-player", "player.mux.com/", "stream.mux.com/"), playback = "generic_player_config", js = true),
        p("cloudflare_stream", "Cloudflare Stream", "provider", listOf("cloudflarestream.com", "videodelivery.net", "embed.cloudflarestream.com"), playback = "generic_player_config", js = true),
        p("bunny_stream", "Bunny Stream", "provider", listOf("iframe.mediadelivery.net/embed", "video.bunnycdn.com", "bunnycdn"), js = true),
        p("wistia", "Wistia", "provider", listOf("fast.wistia.net/embed", "wistia_async_", "Wistia.embed"), js = true),
        p("brightcove", "Brightcove", "provider", listOf("players.brightcove.net", "data-account=", "video-cloud"), js = true),
        p("kaltura", "Kaltura", "provider", listOf("kaltura", "kwidget", "entry_id", "embedIframeJs"), js = true),
        p("vimeo", "Vimeo embed", "provider", listOf("player.vimeo.com/video/", "vimeo-player"), playback = "iframe_chain", js = true),
        p("youtube", "YouTube embed", "provider", listOf("youtube.com/embed/", "youtube-nocookie.com/embed/", "YT.Player"), playback = "iframe_chain", js = true),
        p("dailymotion", "Dailymotion embed", "provider", listOf("dailymotion.com/embed/video/", "geo.dailymotion.com/player"), playback = "iframe_chain", js = true),
        p("sproutvideo", "SproutVideo", "provider", listOf("videos.sproutvideo.com/embed", "sproutvideo.com/player"), playback = "iframe_chain", js = true),
        p("panopto", "Panopto", "provider", listOf("Panopto", "/Panopto/Pages/Embed.aspx"), playback = "iframe_chain", js = true, cookies = true),
        p("videopress", "VideoPress / Jetpack Video", "provider", listOf("videopress.com/embed", "VideoPress", "videopress-player"), playback = "iframe_chain", js = true),
    )

    fun detect(html: String, url: String = ""): List<StudioEngineMatch> {
        if (html.isBlank() && url.isBlank()) return emptyList()
        val haystack = "$url\n${html.take(MAX_FINGERPRINT_BYTES)}"
        return profiles.mapNotNull { profile ->
            val hits = profile.markers.count { marker -> haystack.contains(marker, ignoreCase = true) }
            if (hits >= profile.minHits) StudioEngineMatch(profile, hits) else null
        }.sortedWith(compareByDescending<StudioEngineMatch> { it.hits }.thenBy { it.profile.id })
    }

    fun searchTemplates(matches: List<StudioEngineMatch>): List<String> =
        matches.flatMap { it.profile.searchTemplates }.distinct()

    fun playbackStrategies(matches: List<StudioEngineMatch>): List<String> =
        matches.map { it.profile.playbackStrategy }.filter { it.isNotBlank() }.distinct()

    fun summary(matches: List<StudioEngineMatch>): String = matches.take(6).joinToString(", ") {
        "${it.profile.label}(${it.hits})"
    }

    private fun p(
        id: String,
        label: String,
        family: String,
        markers: List<String>,
        search: List<String> = emptyList(),
        playback: String = "generic_player_config",
        js: Boolean = false,
        cookies: Boolean = false,
        minHits: Int = 1,
    ) = StudioEngineProfile(id, label, family, markers, search, playback, js, cookies, minHits)

    private const val MAX_FINGERPRINT_BYTES = 1_500_000
}

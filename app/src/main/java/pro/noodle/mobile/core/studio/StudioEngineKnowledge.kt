package pro.noodle.mobile.core.studio

/**
 * Fingerprint catalogue used only by local Studio discovery. A profile is not a promise that a
 * provider-specific private API can be called; it tells Studio which public search routes and
 * playback strategies are worth trying before AI repair.
 *
 * The catalogue intentionally separates a concrete site/CMS fingerprint from the runtime resolver.
 * Dozens of players eventually expose the same public primitives (HTML5/MP4, HLS, DASH or iframe),
 * so we keep many fingerprints but only a small audited resolver set.
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
    val probeGlobally: Boolean = false,
)

internal data class StudioEngineMatch(val profile: StudioEngineProfile, val hits: Int)

internal object StudioEngineKnowledge {
    val profiles: List<StudioEngineProfile> = listOf(
        // ---- Video CMS / tube / portal engines -------------------------------------------------
        p("kvs", "Kernel Video Sharing", "cms", listOf("kt_player", "flashvars", "/get_file/", "kvs_player"), listOf("/search/?q={query}", "/search/{query}/"), "jwplayer_config", global = true),
        p("wordpress_video", "WordPress video site", "cms", listOf("wp-content/", "wp-includes/", "/wp-json/"), listOf("/?s={query}", "/search/{query}/"), global = true),
        p("clipbucket", "ClipBucket", "cms", listOf("clipbucket", "cb_video", "embed_player.php?vid=", "watch_video.php"), listOf("/search_result.php?query={query}", "/search_result.php?query={query}&type=videos", "/search/?q={query}"), "videojs_config", global = true),
        p("avideo", "AVideo / YouPHPTube", "cms", listOf("YouPHPTube", "/plugin/API/", "AVideo Platform"), listOf("/search?search={query}", "/?search={query}", "/videos?search={query}"), global = true),
        p("peertube", "PeerTube", "cms", listOf("peertube", "/api/v1/videos", "/api/v1/search/videos"), listOf("/search?search={query}", "/search?search={query}&resultType=videos"), js = true, global = true),
        p("mediacms", "MediaCMS", "cms", listOf("MediaCMS", "/api/v1/media", "mediacms-player"), listOf("/search?q={query}", "/search?query={query}", "/media/?q={query}"), "videojs_config", js = true, global = true),
        p("phpvibe", "PHPVibe", "cms", listOf("PHPVibe", "vibe-content", "phpvibe.com"), listOf("/search/{query}", "/search?key={query}", "/?s={query}"), global = true),
        p("playtube", "PlayTube", "cms", listOf("PlayTube", "pt_page_margin", "pt_"), listOf("/search/{query}", "/search?keyword={query}", "/search?q={query}"), "videojs_config", js = true, cookies = true, global = true),
        p("datalife", "DataLife Engine", "cms", listOf("dle-content", "dle-ajax", "DataLife Engine"), listOf("/index.php?do=search&subaction=search&story={query}", "/?do=search&subaction=search&story={query}"), global = true),
        p("drupal", "Drupal media site", "cms", listOf("drupalSettings", "/sites/default/files/", "Drupal.settings"), listOf("/search/node?keys={query}", "/search/node?keys={query}&page=0"), global = true),
        p("joomla", "Joomla media site", "cms", listOf("Joomla!", "/media/system/js/", "option=com_"), listOf("/component/search/?searchword={query}&searchphrase=all", "/index.php?option=com_search&searchword={query}"), global = true),
        p("phpmelody", "PHP Melody", "cms", listOf("PHP Melody", "phpmelody", "pm-video"), listOf("/search.php?keywords={query}", "/search.php?q={query}"), global = true),
        p("cumulusclips", "CumulusClips", "cms", listOf("CumulusClips", "cumulusclips"), listOf("/search/?q={query}", "/search/{query}/"), global = true),
        p("owncast", "Owncast", "cms", listOf("owncast", "/api/status", "owncast.online"), playback = "hls_manifest", js = true),
        p("mediagoblin", "GNU MediaGoblin", "cms", listOf("MediaGoblin", "mediagoblin", "/u/"), listOf("/search/?q={query}", "/search?q={query}"), global = true),
        p("tubeace", "TubeAce / tube script family", "cms", listOf("tubeace", "tube ace"), listOf("/search/{query}", "/search?q={query}"), global = true),
        p("adultvideoscript", "Adult Video Script family", "cms", listOf("Adult Video Script", "avs_player", "video_player.php"), listOf("/search/{query}", "/search.php?search={query}"), global = true),
        p("phpmotion", "PHPmotion", "cms", listOf("PHPmotion", "phpmotion", "video_player.php"), listOf("/search.php?keywords={query}", "/search.php?search={query}")),
        p("clipshare", "ClipShare", "cms", listOf("ClipShare", "clipshare", "video_details.php"), listOf("/search.php?query={query}", "/search.php?type=videos&query={query}")),
        p("mediacore", "MediaCore", "cms", listOf("MediaCore", "mediacore", "mediacore.tv"), listOf("/search/?q={query}", "/search?q={query}")),
        p("plumi", "Plumi / Plone video portal", "cms", listOf("Plumi", "plumi", "portal_type=Video"), listOf("/search?SearchableText={query}", "/@@search?SearchableText={query}")),
        p("opencast", "Opencast", "cms", listOf("opencast", "/paella7/ui/watch.html", "/paella8/ui/watch.html", "/engage/ui/index.html"), listOf("/search/?q={query}", "/search?q={query}"), playback = "generic_player_config", js = true, cookies = true, minHits = 1),
        p("tobira", "Tobira / Opencast portal", "cms", listOf("Tobira", "tobira", "Opencast"), listOf("/search?q={query}", "/~search?query={query}"), js = true, cookies = true, minHits = 2),
        p("streama", "Streama", "cms", listOf("Streama", "streama", "grails-app"), listOf("/search?query={query}", "/search?q={query}"), js = true, cookies = true, minHits = 2),
        p("kaltura_mediapace", "Kaltura MediaSpace", "cms", listOf("Kaltura MediaSpace", "mediaspace", "KalturaPlayer"), listOf("/search/{query}", "/search?q={query}"), playback = "generic_player_config", js = true, cookies = true, minHits = 2),

        // ---- LMS / education shells which commonly host real video players ----------------------
        p("moodle_video", "Moodle media page", "lms", listOf("M.cfg", "moodle", "media_videojs"), playback = "generic_player_config", js = true, cookies = true, minHits = 2),
        p("canvas_lms_video", "Canvas LMS media", "lms", listOf("instructure", "CanvasRenderingContext2D", "media_comment"), playback = "iframe_chain", js = true, cookies = true, minHits = 2),

        // ---- Player frameworks ------------------------------------------------------------------
        p("jwplayer", "JW Player", "player", listOf("jwplayer(", "jwplayer.js", ".setup({"), playback = "jwplayer_config"),
        p("videojs", "Video.js", "player", listOf("video-js", "videojs(", "video.min.js"), playback = "videojs_config"),
        p("playerjs", "PlayerJS", "player", listOf("new Playerjs(", "playerjs.js", "PlayerjsAsync"), playback = "generic_player_config"),
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
        p("fvplayer", "FV Player", "player", listOf("flowplayer", "fv-player", "fv_player", "fvplayer"), playback = "generic_player_config", js = true, minHits = 2),
        p("presto_player", "Presto Player", "player", listOf("presto-player", "presto_player", "presto-player-block"), playback = "generic_player_config", js = true),
        p("paella", "Paella Player", "player", listOf("paella", "paella-player", "paella7", "paella8"), playback = "generic_player_config", js = true),
        p("rxplayer", "RxPlayer", "player", listOf("new RxPlayer(", "rx-player", "rxplayer"), playback = "generic_player_config", js = true),
        p("chimee", "Chimee", "player", listOf("new Chimee(", "chimee-player", "chimee"), playback = "generic_player_config", js = true),
        p("afterglow", "Afterglow", "player", listOf("afterglow", "afterglow-player"), playback = "generic_player_config"),
        p("amalia", "Amalia.js", "player", listOf("amalia.js", "fr.ina.amalia", "amalia-player"), playback = "generic_player_config"),
        p("leanback", "LeanBack Player", "player", listOf("LeanBackPlayer", "leanback-player", "leanbackPlayer"), playback = "generic_player_config"),
        p("sublimevideo", "SublimeVideo", "player", listOf("sublimevideo", "sublimevideo.net"), playback = "generic_player_config"),
        p("vime", "Vime player", "player", listOf("<vm-player", "@vime/core", "vime-player"), playback = "generic_player_config", js = true),
        p("reactplayer", "ReactPlayer", "player", listOf("react-player", "ReactPlayer", "reactPlayer"), playback = "iframe_chain", js = true, minHits = 2),
        p("videogular", "Videogular", "player", listOf("videogular", "vg-player", "ngx-videogular"), playback = "generic_player_config", js = true),
        p("media_chrome", "Media Chrome", "player", listOf("<media-controller", "media-chrome", "<media-play-button"), playback = "generic_player_config", js = true),
        p("video_react", "Video-React", "player", listOf("video-react", "video-react-controls"), playback = "generic_player_config", js = true),
        p("uppod", "Uppod Player", "player", listOf("uppod", "uppod.swf", "uppod.js"), playback = "generic_player_config"),

        // ---- Video hosting / embed providers -----------------------------------------------------
        p("mux", "Mux Player", "provider", listOf("<mux-player", "player.mux.com/", "stream.mux.com/"), js = true),
        p("cloudflare_stream", "Cloudflare Stream", "provider", listOf("cloudflarestream.com", "videodelivery.net", "embed.cloudflarestream.com"), js = true),
        p("bunny_stream", "Bunny Stream", "provider", listOf("iframe.mediadelivery.net/embed", "video.bunnycdn.com", "bunnycdn"), playback = "iframe_chain", js = true),
        p("wistia", "Wistia", "provider", listOf("fast.wistia.net/embed", "wistia_async_", "Wistia.embed"), playback = "iframe_chain", js = true),
        p("brightcove", "Brightcove", "provider", listOf("players.brightcove.net", "data-account=", "video-cloud"), playback = "iframe_chain", js = true),
        p("kaltura", "Kaltura", "provider", listOf("kaltura", "kwidget", "entry_id", "embedIframeJs"), playback = "iframe_chain", js = true),
        p("vimeo", "Vimeo embed", "provider", listOf("player.vimeo.com/video/", "vimeo-player"), playback = "iframe_chain", js = true),
        p("youtube", "YouTube embed", "provider", listOf("youtube.com/embed/", "youtube-nocookie.com/embed/", "YT.Player"), playback = "iframe_chain", js = true),
        p("dailymotion", "Dailymotion embed", "provider", listOf("dailymotion.com/embed/video/", "geo.dailymotion.com/player"), playback = "iframe_chain", js = true),
        p("sproutvideo", "SproutVideo", "provider", listOf("videos.sproutvideo.com/embed", "sproutvideo.com/player"), playback = "iframe_chain", js = true),
        p("panopto", "Panopto", "provider", listOf("Panopto", "/Panopto/Pages/Embed.aspx"), playback = "iframe_chain", js = true, cookies = true),
        p("videopress", "VideoPress / Jetpack Video", "provider", listOf("videopress.com/embed", "VideoPress", "videopress-player"), playback = "iframe_chain", js = true),
        p("kinescope", "Kinescope", "provider", listOf("kinescope.io/embed/", "player.kinescope.io", "kinescope-iframe-player"), playback = "iframe_chain", js = true),
        p("boomstream", "Boomstream", "provider", listOf("play.boomstream.com/", "boomstream.com/player", "Boomstream"), playback = "iframe_chain", js = true),
        p("rutube", "RUTUBE", "provider", listOf("rutube.ru/play/embed/", "rutube.ru/video/", "rutube-player"), playback = "iframe_chain", js = true),
        p("vk_video", "VK Video", "provider", listOf("vk.com/video_ext.php", "vkvideo.ru", "VK.Video"), playback = "iframe_chain", js = true, cookies = true),
        p("ok_video", "OK.ru Video", "provider", listOf("ok.ru/videoembed/", "ok.ru/video/", "odnoklassniki"), playback = "iframe_chain", js = true, cookies = true),
        p("mailru_video", "Mail.ru Video", "provider", listOf("my.mail.ru/video/embed/", "videoapi.my.mail.ru", "my.mail.ru/video/"), playback = "iframe_chain", js = true),
        p("rumble", "Rumble", "provider", listOf("rumble.com/embed/", "rumble.com/embedJS/", "RumblePlayer"), playback = "iframe_chain", js = true),
        p("streamable", "Streamable", "provider", listOf("streamable.com/e/", "streamable.com/o/", "streamable-player"), playback = "iframe_chain", js = true),
        p("loom", "Loom", "provider", listOf("loom.com/embed/", "www.loom.com/share/", "loom-video"), playback = "iframe_chain", js = true),
        p("twitch", "Twitch Player", "provider", listOf("player.twitch.tv/", "clips.twitch.tv/embed", "Twitch.Player"), playback = "iframe_chain", js = true, cookies = true),
        p("kick", "Kick player", "provider", listOf("player.kick.com/", "kick.com/", "kick-player"), playback = "iframe_chain", js = true, cookies = true, minHits = 2),
        p("odysee", "Odysee / LBRY", "provider", listOf("odysee.com/$/embed/", "odysee.com/@", "lbry"), playback = "iframe_chain", js = true),
        p("archive_org", "Internet Archive video", "provider", listOf("archive.org/embed/", "archive.org/details/", "jwplayer("), playback = "iframe_chain", js = true, minHits = 2),
        p("gumlet", "Gumlet Video", "provider", listOf("play.gumlet.io/embed/", "video.gumlet.io", "gumlet-video"), playback = "iframe_chain", js = true),
        p("dacast", "Dacast", "provider", listOf("iframe.dacast.com/", "player.dacast.com/", "dacast"), playback = "iframe_chain", js = true),
        p("ibm_video", "IBM Video Streaming / Ustream", "provider", listOf("video.ibm.com/embed/", "ustream.tv/embed/", "Ustream"), playback = "iframe_chain", js = true),
        p("wowza", "Wowza Streaming / Player", "provider", listOf("WowzaPlayer", "wowzaplayer.com", "wowza.com"), playback = "generic_player_config", js = true, minHits = 2),
        p("vidyard", "Vidyard", "provider", listOf("play.vidyard.com/", "vidyard.com/embed", "vidyard-player"), playback = "iframe_chain", js = true),
        p("cincopa", "Cincopa", "provider", listOf("cincopa.com/media-platform/iframe", "cdn.cincopa.com", "cincopa_player"), playback = "iframe_chain", js = true),
        p("twentythree", "TwentyThree Video", "provider", listOf("twentythree.net", "video.twentythree.com", "23video.com"), playback = "iframe_chain", js = true),
        p("streamshark", "StreamShark", "provider", listOf("embed.streamshark.io", "streamshark.io", "StreamShark"), playback = "iframe_chain", js = true),
        p("nuum", "NUUM video/stream", "provider", listOf("nuum.ru", "player.nuum.ru", "NUUM"), playback = "iframe_chain", js = true, cookies = true, minHits = 2),
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

    /** Only conservative, battle-tested routes are probed when no fingerprint exists. Obscure CMS
     * routes remain available immediately after their fingerprint is seen, but they do not create
     * blind network noise on every unknown site. */
    fun globalSearchTemplates(): List<String> = profiles
        .filter { it.family == "cms" && it.probeGlobally }
        .flatMap { it.searchTemplates }
        .distinct()

    fun playbackStrategies(matches: List<StudioEngineMatch>): List<String> =
        matches.map { it.profile.playbackStrategy }.filter { it.isNotBlank() }.distinct()

    fun summary(matches: List<StudioEngineMatch>): String = matches.take(8).joinToString(", ") {
        "${it.profile.label}(${it.hits})"
    }

    fun catalogueSummary(): String {
        val groups = profiles.groupingBy { it.family }.eachCount().toSortedMap()
        return "profiles=${profiles.size} • " + groups.entries.joinToString(" • ") { "${it.key}=${it.value}" }
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
        global: Boolean = false,
    ) = StudioEngineProfile(id, label, family, markers, search, playback, js, cookies, minHits, global)

    private const val MAX_FINGERPRINT_BYTES = 1_500_000
}

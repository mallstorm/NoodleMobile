package pro.noodle.mobile.core.engine

object EngineRegistry {
    private val descriptors: List<EngineDescriptor> = listOf(
        EngineDescriptor("kvs_view", setOf("kvs", "kvs_flashvars", "fapaxi"), legacyCapabilities()),
        EngineDescriptor("kvs_videos", setOf("pornobomba", "kvs_videos_flashvars"), legacyCapabilities()),
        EngineDescriptor("noodle_watch_playlist", setOf("noodle", "playlist"), setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK, EngineCapability.CATEGORIES)),
        EngineDescriptor("sosalkino_catalog", setOf("sosalkino"), setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK, EngineCapability.CATEGORIES)),
        EngineDescriptor("novideo_embed", setOf("novideo", "novideo-embed", "iframe_flashvars"), setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK, EngineCapability.IFRAME, EngineCapability.CATEGORIES)),
        EngineDescriptor("generic_html5", setOf("html5", "generic", "generic_video", "video_object"), setOf(EngineCapability.PLAYBACK, EngineCapability.HTML5, EngineCapability.HLS, EngineCapability.DASH, EngineCapability.IFRAME, EngineCapability.SUBTITLES, EngineCapability.AUDIO_TRACKS, EngineCapability.ADAPTIVE_QUALITY)),
        EngineDescriptor("generic_player_config", setOf(
            "player_config", "js_player", "plyr", "flowplayer", "clappr", "shaka", "shaka_player",
            "hlsjs", "hls.js", "dashjs", "dash.js", "mediaelement", "mediaelementjs", "vidstack",
            "fluidplayer", "dplayer", "artplayer", "openplayerjs", "projekktor", "jplayer", "ableplayer",
            "bitmovin", "theoplayer", "azure_media_player", "cloudinary_player", "mux_player",
            "cloudflare_stream", "bunny_stream", "wistia_player", "brightcove_player", "kaltura_player",
        ), playbackCapabilities()),
        EngineDescriptor("jwplayer_config", setOf("jwplayer", "jw"), playbackCapabilities()),
        EngineDescriptor("videojs_config", setOf("videojs", "video.js"), playbackCapabilities()),
        EngineDescriptor("iframe_chain", setOf("iframe", "iframes"), setOf(EngineCapability.PLAYBACK, EngineCapability.IFRAME, EngineCapability.HTML5)),
        EngineDescriptor("hls_manifest", setOf("hls", "m3u8"), setOf(EngineCapability.PLAYBACK, EngineCapability.HLS, EngineCapability.SUBTITLES, EngineCapability.AUDIO_TRACKS, EngineCapability.ADAPTIVE_QUALITY)),
        EngineDescriptor("dash_manifest", setOf("dash", "mpd"), setOf(EngineCapability.PLAYBACK, EngineCapability.DASH, EngineCapability.SUBTITLES, EngineCapability.AUDIO_TRACKS, EngineCapability.ADAPTIVE_QUALITY)),
        EngineDescriptor("declarative_catalog", setOf("declarative", "declarative_html"), setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK)),
        EngineDescriptor("json_api_catalog", setOf("json_api", "api_json"), setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK)),
        EngineDescriptor("oembed_provider", setOf("oembed"), setOf(EngineCapability.PLAYBACK, EngineCapability.HTML5, EngineCapability.IFRAME)),
        EngineDescriptor("wordpress_rest", setOf("wordpress", "wp_rest"), setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK, EngineCapability.CATEGORIES)),
    )

    private val byAnyId: Map<String, EngineDescriptor> = buildMap {
        descriptors.forEach { d ->
            put(d.id, d)
            d.aliases.forEach { alias -> require(put(alias.lowercase(), d) == null) { "Duplicate engine alias: $alias" } }
        }
    }

    val knownEngineIds: List<String> get() = descriptors.map { it.id }

    fun normalizeId(value: String): String {
        val key = value.trim().lowercase()
        return byAnyId[key]?.id ?: key
    }

    fun descriptor(value: String): EngineDescriptor? = byAnyId[value.trim().lowercase()]
    fun isKnown(value: String): Boolean = descriptor(value) != null
    fun capabilitiesOf(value: String): Set<EngineCapability> = descriptor(value)?.capabilities.orEmpty()

    private fun legacyCapabilities() = setOf(EngineCapability.SEARCH, EngineCapability.PAGINATION, EngineCapability.DIRECT_URL, EngineCapability.PLAYBACK, EngineCapability.CATEGORIES)
    private fun playbackCapabilities() = setOf(EngineCapability.PLAYBACK, EngineCapability.HTML5, EngineCapability.HLS, EngineCapability.DASH, EngineCapability.IFRAME, EngineCapability.SUBTITLES, EngineCapability.AUDIO_TRACKS, EngineCapability.ADAPTIVE_QUALITY)
}

package pro.noodle.mobile.core.engine

enum class EngineCapability(val wireName: String) {
    SEARCH("search"), PAGINATION("pagination"), DIRECT_URL("direct_url"), PLAYBACK("playback"),
    HTML5("html5"), HLS("hls"), DASH("dash"), IFRAME("iframe"), JAVASCRIPT("javascript"),
    CATEGORIES("categories"), SUBTITLES("subtitles"), AUDIO_TRACKS("audio_tracks"),
    ADAPTIVE_QUALITY("adaptive_quality")
}

data class EngineDescriptor(
    val id: String,
    val aliases: Set<String> = emptySet(),
    val capabilities: Set<EngineCapability> = emptySet(),
)

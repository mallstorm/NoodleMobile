package pro.noodle.mobile.core.model

data class MediaSource(
    val label: String = "",
    val url: String,
    val mimeType: String = "",
    val protocol: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bandwidth: Long = 0,
    val codecs: String = "",
    val isDefault: Boolean = false,
    /** Фильм из нескольких частей: адреса частей по порядку (проигрываются как одно видео). */
    val parts: List<String> = emptyList(),
    /** Длительности частей в мс (-1 — неизвестно). */
    val partDurationsMs: List<Long> = emptyList(),
)

data class MediaVariant(
    val id: String,
    val label: String = "",
    val mimeType: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bandwidth: Long = 0,
    val isDefault: Boolean = false,
)

data class AudioTrack(
    val id: String,
    val label: String = "",
    val language: String = "",
    val mimeType: String = "",
    val codecs: String = "",
    val isDefault: Boolean = false,
)

data class TextTrack(
    val label: String = "",
    val language: String = "",
    val kind: String = "",
    val url: String,
    val isDefault: Boolean = false,
)

data class VideoItem(
    val id: String = "",
    val remoteId: String = "",
    val watchUrl: String = "",
    val title: String,
    val thumbnailUrl: String = "",
    val duration: String = "",
    val views: String = "",
    val sourceName: String = "",
    val sourceBaseUrl: String = "",
    val sourceEngine: String = "",
    val estimatedSize: Long = 0,
    val trailerUrl: String = "",
    val downloadUrl: String = "",
    val playerPageUrl: String = "",
    val favoriteKey: String = "",
    val tags: List<String> = emptyList(),
    val note: String = "",
    val favoriteTimestamp: Long = 0,
    val sources: List<MediaSource> = emptyList(),
    val variants: List<MediaVariant> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val textTracks: List<TextTrack> = emptyList(),
)

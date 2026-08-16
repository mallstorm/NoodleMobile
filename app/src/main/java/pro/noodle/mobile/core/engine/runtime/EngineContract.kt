package pro.noodle.mobile.core.engine.runtime

import pro.noodle.mobile.core.model.AudioTrack
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.MediaVariant
import pro.noodle.mobile.core.model.TextTrack

data class EngineSearchRequest(
    val baseUrl: String,
    val query: String,
    val page: Int = -1,
    val maxItems: Int = 40,
    val searchUrlTemplate: String = "",
    val itemSelector: String = "",
    val titleSelector: String = "",
    val urlSelector: String = "",
    val thumbnailSelector: String = "",
    val thumbnailAttribute: String = "",
    val durationSelector: String = "",
    val viewsSelector: String = "",
    val itemsPath: String = "",
    val idPath: String = "",
    val titlePath: String = "",
    val urlPath: String = "",
    val thumbnailPath: String = "",
    val durationPath: String = "",
    val viewsPath: String = "",
)

data class EngineSearchItem(
    val remoteId: String = "",
    val watchPath: String = "",
    val title: String = "",
    val thumbnailUrl: String = "",
    val durationText: String = "",
    val viewsText: String = "",
)

data class EngineResolveResult(
    val handled: Boolean = false,
    val success: Boolean = false,
    val title: String = "",
    val thumbnailUrl: String = "",
    val playerPageUrl: String = "",
    val sources: List<MediaSource> = emptyList(),
    val variants: List<MediaVariant> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val tracks: List<TextTrack> = emptyList(),
    val followUpPages: List<String> = emptyList(),
    val errorCode: String = "",
    val errorMessage: String = "",
)

interface SourceEngine {
    val id: String
    val aliases: Set<String> get() = emptySet()
    fun buildSearchUrl(request: EngineSearchRequest): String = ""
    fun parseSearch(text: String, request: EngineSearchRequest): List<EngineSearchItem> = emptyList()
    fun resolvePlayback(text: String, pageUrl: String): EngineResolveResult = EngineResolveResult()
}

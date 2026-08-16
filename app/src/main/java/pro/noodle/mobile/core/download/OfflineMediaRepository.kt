package pro.noodle.mobile.core.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import pro.noodle.mobile.core.storage.DownloadEntity
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.storage.NoodleDatabase

sealed interface OfflinePlaybackSpec {
    val id: String
    val title: String
    val mimeType: String

    data class Adaptive(
        override val id: String,
        override val title: String,
        override val mimeType: String,
        val request: DownloadRequest,
    ) : OfflinePlaybackSpec

    data class Progressive(
        override val id: String,
        override val title: String,
        override val mimeType: String,
        val uri: Uri,
    ) : OfflinePlaybackSpec
}

@UnstableApi
class OfflineMediaRepository(context: Context) {
    private val app = context.applicationContext
    private val dao = NoodleDatabase.get(app).downloads()

    suspend fun open(id: String): Result<OfflinePlaybackSpec> = runCatching {
        val item = dao.get(id) ?: error("OFFLINE_ITEM_NOT_FOUND")
        require(item.state == DownloadState.COMPLETED) { "OFFLINE_ITEM_NOT_COMPLETE" }
        if (item.destinationUri == DownloadRepository.ADAPTIVE_DESTINATION) {
            val request = MediaDownloadRuntime.requestFor(app, id) ?: error("OFFLINE_MEDIA3_REQUEST_MISSING")
            OfflinePlaybackSpec.Adaptive(item.id, item.title, item.mimeType, request)
        } else {
            val location = item.localFilePath.ifBlank { item.destinationUri }
            require(location.isNotBlank()) { "OFFLINE_FILE_LOCATION_MISSING" }
            OfflinePlaybackSpec.Progressive(item.id, item.title, item.mimeType, Uri.parse(location))
        }
    }
}

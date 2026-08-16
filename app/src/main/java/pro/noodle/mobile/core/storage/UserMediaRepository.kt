package pro.noodle.mobile.core.storage

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.noodle.mobile.core.model.VideoItem

class UserMediaRepository(context: Context) {
    private val db = NoodleDatabase.get(context)
    fun favorites(): Flow<List<VideoItem>> = db.favorites().observeAll().map { list -> list.map(FavoriteEntity::toVideoItem) }
    fun isFavorite(key: String): Flow<Boolean> = db.favorites().observeIsFavorite(key)
    suspend fun addFavorite(video: VideoItem) = db.favorites().upsert(video.toFavoriteEntity())
    suspend fun removeFavorite(video: VideoItem) = db.favorites().delete(video.favoriteKey.ifBlank { "${video.sourceBaseUrl}|${video.remoteId.ifBlank { video.watchUrl }}" })
    suspend fun playbackPosition(key: String): PlaybackPositionEntity? = db.playbackPositions().get(key)
    suspend fun savePlaybackPosition(key: String, positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return
        if (durationMs > 0 && positionMs >= durationMs - 10_000) db.playbackPositions().delete(key)
        else db.playbackPositions().upsert(PlaybackPositionEntity(key, positionMs, durationMs, System.currentTimeMillis()))
    }
}

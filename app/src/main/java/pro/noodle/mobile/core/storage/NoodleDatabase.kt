package pro.noodle.mobile.core.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import pro.noodle.mobile.core.model.VideoItem

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val favoriteKey: String,
    val id: String,
    val remoteId: String,
    val watchUrl: String,
    val title: String,
    val thumbnailUrl: String,
    val duration: String,
    val views: String,
    val sourceName: String,
    val sourceBaseUrl: String,
    val sourceEngine: String,
    val tags: String,
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceUrl: String,
    val sourceKey: String,
    val allowedHosts: String,
    val allowCookies: Boolean,
    val allowHttp: Boolean = false,
    val destinationUri: String,
    val mimeType: String,
    val state: DownloadState,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val errorCode: String = "",
    val errorMessage: String = "",
    val localFilePath: String = "",
    val thumbnailUrl: String = "",
    val protocol: String = "",
    val videoSelectionLabel: String = "",
    val videoMaxWidth: Int = Int.MAX_VALUE,
    val videoMaxHeight: Int = Int.MAX_VALUE,
    val audioSelectionLanguage: String = "",
    val textSelectionLanguage: String = "",
    val includeSubtitles: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY createdAt DESC") fun observeAll(): Flow<List<FavoriteEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE favoriteKey=:key)") fun observeIsFavorite(key: String): Flow<Boolean>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE favoriteKey=:key") suspend fun delete(key: String)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE mediaKey=:key LIMIT 1") suspend fun get(key: String): PlaybackPositionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: PlaybackPositionEntity)
    @Query("DELETE FROM playback_positions WHERE mediaKey=:key") suspend fun delete(key: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun observeAll(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE state='COMPLETED' ORDER BY updatedAt DESC") fun observeCompleted(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE id=:id LIMIT 1") suspend fun get(id: String): DownloadEntity?
    @Query("SELECT COUNT(*) FROM downloads WHERE state='RUNNING' AND id != :id AND updatedAt >= :cutoff") suspend fun countRunningRecentExcluding(id: String, cutoff: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DownloadEntity)
    @Query("UPDATE downloads SET state=:state, downloadedBytes=:downloaded, totalBytes=:total, errorCode=:errorCode, errorMessage=:errorMessage, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateProgress(id: String, state: DownloadState, downloaded: Long, total: Long, errorCode: String, errorMessage: String, updatedAt: Long)
    @Query("UPDATE downloads SET state=:state, updatedAt=:updatedAt WHERE id=:id") suspend fun updateState(id: String, state: DownloadState, updatedAt: Long)
    @Query("UPDATE downloads SET state='COMPLETED', downloadedBytes=:downloaded, totalBytes=:total, localFilePath=:localPath, errorCode='', errorMessage='', updatedAt=:updatedAt WHERE id=:id") suspend fun markCompleted(id: String, downloaded: Long, total: Long, localPath: String, updatedAt: Long)
    @Query("DELETE FROM downloads WHERE id=:id") suspend fun delete(id: String)
}

@Database(entities = [FavoriteEntity::class, PlaybackPositionEntity::class, DownloadEntity::class], version = 3, exportSchema = true)
@TypeConverters(DbConverters::class)
abstract class NoodleDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun playbackPositions(): PlaybackPositionDao
    abstract fun downloads(): DownloadDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN allowHttp INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnailUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN protocol TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoSelectionLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoMaxWidth INTEGER NOT NULL DEFAULT 2147483647")
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoMaxHeight INTEGER NOT NULL DEFAULT 2147483647")
                db.execSQL("ALTER TABLE downloads ADD COLUMN audioSelectionLanguage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN textSelectionLanguage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN includeSubtitles INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var instance: NoodleDatabase? = null
        fun get(context: Context): NoodleDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NoodleDatabase::class.java, "noodle-mobile.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}

class DbConverters {
    @TypeConverter fun stateToString(v: DownloadState): String = v.name
    @TypeConverter fun stringToState(v: String): DownloadState = runCatching { DownloadState.valueOf(v) }.getOrDefault(DownloadState.FAILED)
}

private const val SEP = '\u001F'
fun FavoriteEntity.toVideoItem() = VideoItem(id=id, remoteId=remoteId, watchUrl=watchUrl, title=title, thumbnailUrl=thumbnailUrl, duration=duration, views=views, sourceName=sourceName, sourceBaseUrl=sourceBaseUrl, sourceEngine=sourceEngine, favoriteKey=favoriteKey, tags=tags.split(SEP).filter { it.isNotBlank() }, note=note, favoriteTimestamp=createdAt)
fun VideoItem.toFavoriteEntity(now: Long = System.currentTimeMillis()) = FavoriteEntity(favoriteKey=favoriteKey.ifBlank { "$sourceBaseUrl|${remoteId.ifBlank { watchUrl }}" }, id=id, remoteId=remoteId, watchUrl=watchUrl, title=title, thumbnailUrl=thumbnailUrl, duration=duration, views=views, sourceName=sourceName, sourceBaseUrl=sourceBaseUrl, sourceEngine=sourceEngine, tags=tags.joinToString(SEP.toString()), note=note, createdAt=if(favoriteTimestamp>0) favoriteTimestamp else now)

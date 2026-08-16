package pro.noodle.mobile

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pro.noodle.mobile.core.storage.DownloadEntity
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.storage.NoodleDatabase

@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {
    private lateinit var db: NoodleDatabase

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NoodleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun close() = db.close()

    @Test fun downloadRoundTripPreservesAdaptiveSelectionMetadata() = runBlocking {
        val now = System.currentTimeMillis()
        db.downloads().upsert(
            DownloadEntity(
                id = "test-adaptive",
                title = "Fixture",
                sourceUrl = "https://media.example/master.m3u8",
                sourceKey = "fixture",
                allowedHosts = "media.example",
                allowCookies = false,
                destinationUri = "media3://adaptive",
                mimeType = "application/x-mpegURL",
                state = DownloadState.COMPLETED,
                protocol = "hls",
                videoSelectionLabel = "720p",
                videoMaxWidth = 1280,
                videoMaxHeight = 720,
                audioSelectionLanguage = "ru",
                textSelectionLanguage = "en",
                includeSubtitles = true,
                createdAt = now,
                updatedAt = now,
            )
        )
        val item = db.downloads().get("test-adaptive")!!
        assertEquals("720p", item.videoSelectionLabel)
        assertEquals(1280, item.videoMaxWidth)
        assertEquals("ru", item.audioSelectionLanguage)
        assertTrue(item.includeSubtitles)
        assertEquals(1, db.downloads().observeCompleted().first().size)
    }
}

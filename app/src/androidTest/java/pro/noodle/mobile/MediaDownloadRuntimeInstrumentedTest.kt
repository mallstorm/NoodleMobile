package pro.noodle.mobile

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.noodle.mobile.core.download.MediaDownloadRuntime

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaDownloadRuntimeInstrumentedTest {
    @Test
    fun downloadManagerRemainsMainLooperAffined() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = withContext(Dispatchers.Main.immediate) {
            MediaDownloadRuntime.manager(context)
        }
        assertEquals(Looper.getMainLooper(), manager.applicationLooper)

        val failure = withContext(Dispatchers.IO) {
            runCatching { MediaDownloadRuntime.manager(context) }.exceptionOrNull()
        }
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("DOWNLOAD_MANAGER_MAIN_THREAD_REQUIRED") == true)
    }
}

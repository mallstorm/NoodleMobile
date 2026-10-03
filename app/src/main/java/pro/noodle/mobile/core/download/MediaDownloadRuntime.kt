package pro.noodle.mobile.core.download

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.*
import kotlinx.coroutines.*
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.storage.NoodleDatabase
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
object MediaDownloadRuntime {
    @Volatile private var holder: Holder? = null

    /** DownloadManager is thread-affine in Media3. First construction must happen on main. */
    private fun holder(context: Context): Holder {
        holder?.let { return it }
        check(Looper.myLooper() == Looper.getMainLooper()) { "MEDIA_DOWNLOAD_RUNTIME_INIT_MAIN_THREAD_REQUIRED" }
        val app = context.applicationContext
        return synchronized(this) { holder ?: Holder(app).also { holder = it } }
    }

    /** Mutable DownloadManager operations are only legal from its application/main looper. */
    fun manager(context: Context): DownloadManager {
        check(Looper.myLooper() == Looper.getMainLooper()) { "DOWNLOAD_MANAGER_MAIN_THREAD_REQUIRED" }
        return holder(context).manager
    }

    /** Streaming playback: cache hit first, otherwise network through the exact SNP policy. */
    fun cacheDataSourceFactory(context: Context, policy: NetworkPolicy, sourceKey: String): DataSource.Factory =
        holder(context).cacheFactory(policy, sourceKey, readOnly = true)

    /** DownloadHelper preparation must resolve manifests through the exact same SNP sandbox. */
    fun downloadHelperDataSourceFactory(context: Context, policy: NetworkPolicy, sourceKey: String): DataSource.Factory =
        holder(context).upstreamFactory(policy, sourceKey)

    /** Strict offline playback: a cache miss fails instead of silently reaching the network. */
    fun offlineCacheDataSourceFactory(context: Context): DataSource.Factory = holder(context).offlineFactory()

    suspend fun requestFor(context: Context, id: String): DownloadRequest? {
        // Initialize the thread-affine manager/cache holder on main, then perform the blocking
        // DownloadIndex read on IO without touching DownloadManager itself.
        val runtime = withContext(Dispatchers.Main.immediate) { holder(context) }
        return withContext(Dispatchers.IO) { runtime.downloadIndex.getDownload(id)?.request }
    }

    private class Holder(val context: Context) {
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(File(context.filesDir, "media3-download-cache"), NoOpCacheEvictor(), database)
        val downloadIndex = DefaultDownloadIndex(database)
        private val executor = Executors.newFixedThreadPool(4)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val dao = NoodleDatabase.get(context).downloads()

        private val downloaderFactory = DownloaderFactory { request ->
            val env = DownloadPolicyCodec.decode(request.data)
                ?: throw IllegalArgumentException("DOWNLOAD_POLICY_MISSING_OR_INVALID")
            DefaultDownloaderFactory(cacheFactory(env.policy, env.sourceKey, readOnly = false), executor)
                .createDownloader(request)
        }

        val manager = DownloadManager(context, downloadIndex, downloaderFactory).apply {
            maxParallelDownloads = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    sync(download, finalException)
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    scope.launch {
                        dao.get(download.request.id)?.let {
                            dao.updateState(it.id, DownloadState.CANCELED, System.currentTimeMillis())
                        }
                    }
                }
            })
            resumeDownloads()
        }

        init {
            scope.launch(Dispatchers.Main.immediate) {
                while (isActive) {
                    manager.currentDownloads.forEach { sync(it, null) }
                    delay(1_000)
                }
            }
        }

        fun upstreamFactory(policy: NetworkPolicy, sourceKey: String): DataSource.Factory {
            val client = SecureHttpClient().streamingClient(policy, sourceKey)
            // Keep the exact same User-Agent that was used to obtain signed media URLs.
            // Some CDNs bind short-lived signatures to the request fingerprint.
            val headers = buildMap<String, String> {
                put("Accept", "video/*,application/vnd.apple.mpegurl,application/dash+xml,*/*;q=0.8")
                val sourceHost = sourceKey.trim().lowercase()
                if (sourceHost.contains('.') && sourceHost.none { it.isWhitespace() || it == '/' || it == ':' }) {
                    put("Referer", "https://$sourceHost/")
                }
            }
            return OkHttpDataSource.Factory(client)
                .setUserAgent(BuildIdentity.USER_AGENT)
                .setDefaultRequestProperties(headers)
        }

        fun cacheFactory(policy: NetworkPolicy, sourceKey: String, readOnly: Boolean): CacheDataSource.Factory {
            val factory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory(policy, sourceKey))
            if (readOnly) factory.setCacheWriteDataSinkFactory(null)
            return factory
        }

        fun offlineFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)

        private fun sync(download: Download, error: Exception?) {
            scope.launch {
                val item = dao.get(download.request.id) ?: return@launch
                val state = when (download.state) {
                    Download.STATE_QUEUED -> DownloadState.QUEUED
                    Download.STATE_DOWNLOADING -> DownloadState.RUNNING
                    Download.STATE_STOPPED -> DownloadState.PAUSED
                    Download.STATE_COMPLETED -> DownloadState.COMPLETED
                    Download.STATE_FAILED -> DownloadState.FAILED
                    Download.STATE_REMOVING -> DownloadState.CANCELED
                    Download.STATE_RESTARTING -> DownloadState.QUEUED
                    else -> DownloadState.QUEUED
                }
                if (state == DownloadState.COMPLETED) {
                    dao.markCompleted(
                        item.id,
                        download.bytesDownloaded,
                        download.contentLength,
                        "media3-cache",
                        System.currentTimeMillis(),
                    )
                } else {
                    dao.updateProgress(
                        item.id,
                        state,
                        download.bytesDownloaded,
                        download.contentLength,
                        if (state == DownloadState.FAILED) "MEDIA3_DOWNLOAD_FAILED" else "",
                        if (state == DownloadState.FAILED) (error?.message ?: "Media3 download failed") else "",
                        System.currentTimeMillis(),
                    )
                }
            }
        }
    }
}

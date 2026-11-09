package dev.personal.music.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dev.personal.music.playback.YouTubeDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Songs saved on the phone, built on Media3's download stack.
 *
 * - [cache]: every saved song's audio, keyed by videoId, in `files/offline-audio`. Nothing is
 *   evicted automatically; a song leaves only when no saved playlist wants it ([sync]).
 * - [downloadManager]: fills the cache through our [YouTubeDataSource] (same request shape as
 *   playback) and remembers its downloads in a small database, so they resume after a restart.
 *   It runs inside [AudioDownloadService], which keeps the process alive and shows progress.
 * - [playbackDataSourceFactory]: what the player reads through. Saved songs come from disk without
 *   touching the network (CacheDataSource never opens the upstream for a fully cached span);
 *   everything else streams. It is read-only, so plain streaming never fills the phone.
 *
 * Must be created on the main thread (DownloadManager calls its listeners on the creating thread).
 */
@OptIn(UnstableApi::class)
class OfflineAudio(
    private val context: Context,
    client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val database = StandaloneDatabaseProvider(context)
    private val audioDir = File(context.filesDir, "offline-audio")
    val cache = SimpleCache(audioDir, NoOpCacheEvictor(), database)
    private val upstream = YouTubeDataSource.Factory(client)

    // Default requirement is "any network": Wi-Fi or mobile data, as the owner decided.
    val downloadManager = DownloadManager(context, database, cache, upstream, Executors.newFixedThreadPool(2))
        .apply { maxParallelDownloads = 2 }

    private val _audio = MutableStateFlow<Map<String, SavedAudio>>(emptyMap())

    /** State of every song the download manager knows about, by videoId. */
    val audio: StateFlow<Map<String, SavedAudio>> = _audio.asStateFlow()

    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var indexLoaded = false
    private var wanted: Set<String>? = null

    /** True while the app's screen is visible: Android only lets us start the service then. */
    var inForeground = false
        set(value) {
            field = value
            if (value) startServiceIfNeeded()
        }

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = loadIndex()

            override fun onDownloadChanged(dm: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) Log.w(TAG, "Download ${download.request.id} failed", finalException)
                _audio.update { it + (download.request.id to download.toSaved()) }
            }

            override fun onDownloadRemoved(dm: DownloadManager, download: Download) {
                _audio.update { it - download.request.id }
            }
        })
        if (downloadManager.isInitialized) loadIndex()
        watchNetwork()

        // The manager reports state changes, not byte progress: poll the active downloads.
        scope.launch {
            while (true) {
                delay(1000)
                val active = downloadManager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                if (active.isNotEmpty()) {
                    _audio.update { m -> m + active.associate { it.request.id to it.toSaved() } }
                }
            }
        }
    }

    /** For the player: saved songs from disk, the rest streamed. Never writes to the cache. */
    fun playbackDataSourceFactory(): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setCacheKeyFactory(CACHE_KEY)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Makes the phone hold exactly [wanted] (videoIds): downloads what's missing, deletes the rest. */
    fun sync(wanted: Set<String>) {
        this.wanted = wanted
        applySync()
    }

    /** "Reintentar": restarts the failed downloads among [videoIds]. */
    fun retry(videoIds: Collection<String>) {
        videoIds.filter { _audio.value[it]?.state == SaveState.Failed }
            .forEach { downloadManager.addDownload(requestFor(it)) }
        startServiceIfNeeded(force = true)
    }

    /** Free space left where songs are saved. */
    fun freeBytes(): Long = context.filesDir.usableSpace

    private fun applySync() {
        val w = wanted ?: return
        if (!indexLoaded) return // runs again once the saved state is known
        val plan = planSync(w, _audio.value)
        plan.add.forEach { downloadManager.addDownload(requestFor(it)) }
        plan.remove.forEach { downloadManager.removeDownload(it) }
        val changed = plan.add.isNotEmpty() || plan.remove.isNotEmpty()
        if (changed) Log.i(TAG, "sync: +${plan.add.size} -${plan.remove.size}")
        // Just-added downloads aren't in [audio] yet (the manager reports them async): force.
        startServiceIfNeeded(force = changed)
    }

    /** Reads what was saved before (completed and failed downloads live only in the index). */
    private fun loadIndex() {
        scope.launch {
            val fromIndex = withContext(Dispatchers.IO) {
                buildMap {
                    downloadManager.downloadIndex.getDownloads().use { c ->
                        while (c.moveToNext()) put(c.download.request.id, c.download.toSaved())
                    }
                }
            }
            // Events that arrived while reading are newer than the index.
            _audio.update { fromIndex + it }
            indexLoaded = true
            applySync()
        }
    }

    /**
     * Downloads (and deletions) only run while [AudioDownloadService] is up. On Android 12+ it
     * cannot be started from the background, so this runs when the app is opened, when the
     * playlists change, and when the network comes back with the app open.
     */
    private fun startServiceIfNeeded(force: Boolean = false) {
        if (!inForeground) return
        val busy = force || _audio.value.values.any { it.state != SaveState.Saved && it.state != SaveState.Failed } ||
            downloadManager.currentDownloads.isNotEmpty()
        if (!busy) return
        try {
            DownloadService.start(context, AudioDownloadService::class.java)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not start the download service", e)
        }
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (now != _online.value) {
                    _online.value = now
                    if (now) scope.launch { startServiceIfNeeded() }
                }
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    private fun isOnlineNow(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun requestFor(videoId: String) =
        DownloadRequest.Builder(videoId, YouTubeDataSource.uriFor(videoId))
            .setCustomCacheKey(videoId)
            .build()

    private fun Download.toSaved() = SavedAudio(
        state = when (state) {
            Download.STATE_COMPLETED -> SaveState.Saved
            Download.STATE_DOWNLOADING -> SaveState.Downloading
            Download.STATE_FAILED -> SaveState.Failed
            Download.STATE_REMOVING -> SaveState.Removing
            else -> SaveState.Queued // queued, stopped, restarting
        },
        percent = percentDownloaded,
        bytes = bytesDownloaded,
    )

    companion object {
        private const val TAG = "OfflineAudio"

        /**
         * Cache key = videoId, for downloads (customCacheKey) and playback (taken from the
         * `ytm://track/<videoId>` URI) alike, so the player finds what the downloader wrote.
         */
        private val CACHE_KEY = CacheKeyFactory { spec -> spec.key ?: spec.uri.lastPathSegment ?: spec.uri.toString() }
    }
}

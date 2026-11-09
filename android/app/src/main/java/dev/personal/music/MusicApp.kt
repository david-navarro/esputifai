package dev.personal.music

import android.app.Application
import dev.personal.music.data.AlbumStore
import dev.personal.music.data.PlayCountStore
import dev.personal.music.data.PlaylistStore
import dev.personal.music.data.YouTubeMusic
import dev.personal.music.offline.OfflineAudio
import dev.personal.music.offline.wantedOnPhone
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class MusicApp : Application() {
    /** Shared by NewPipeExtractor and the audio data source. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** The user's playlists; one instance for the whole app so every screen sees the same data. */
    val playlists: PlaylistStore by lazy { PlaylistStore(File(filesDir, "playlists.json")) }

    /** The Albumteca. */
    val albums: AlbumStore by lazy { AlbumStore(File(filesDir, "albums.json")) }

    /** Times each song started playing; counted by PlaybackService. */
    val plays: PlayCountStore by lazy { PlayCountStore(File(filesDir, "plays.json")) }

    /** Lives as long as the process; everything in it runs on the main thread. */
    val appScope = MainScope()

    /** Songs saved on the phone (created in [onCreate], on the main thread, as Media3 requires). */
    lateinit var offline: OfflineAudio
        private set

    override fun onCreate() {
        super.onCreate()
        YouTubeMusic.init(httpClient)
        offline = OfflineAudio(this, httpClient, appScope)

        // Saved playlists and albums decide what is on the phone: re-sync on every change.
        appScope.launch {
            playlists.load()
            albums.load()
            combine(playlists.playlists, albums.albums, ::wantedOnPhone).collect { offline.sync(it) }
        }
    }
}

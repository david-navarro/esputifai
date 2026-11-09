package dev.personal.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.personal.music.MusicApp
import dev.personal.music.data.Track
import kotlinx.coroutines.launch
import dev.personal.music.ui.MainActivity

/**
 * Owns the ExoPlayer and exposes it as a MediaSession: background playback, the media
 * notification, lock screen, headphones and Bluetooth controls all come from Media3.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Saved songs are read from the phone, the rest streamed (see OfflineAudio).
        val dataSources = (application as MusicApp).offline.playbackDataSourceFactory()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(SkipUnplayable(player))
        player.addListener(CountPlays(player, application as MusicApp))

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(Callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music going, but don't linger if nothing is playing.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * A song that can't play (typically: no internet and it isn't saved on the phone) is skipped
     * instead of stopping the music. Gives up after trying every song in the queue once, so a
     * queue where nothing plays ends in the error instead of looping.
     */
    private class SkipUnplayable(private val player: Player) : Player.Listener {
        private var skippedInARow = 0

        override fun onPlayerError(error: PlaybackException) {
            if (player.hasNextMediaItem() && skippedInARow < player.mediaItemCount) {
                skippedInARow++
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) skippedInARow = 0
        }
    }

    /**
     * Counts a play when a song plays to its end (song counters, the Top and the Albumteca's "Más
     * escuchados"): that is what "listened to" means (owner's rule, 2026-11-09), so starting a song
     * 100 times and skipping it counts nothing. A song ends when the player moves on by itself
     * (AUTO, or REPEAT of the same song) or the queue finishes (STATE_ENDED); skips and taps are
     * SEEK / PLAYLIST_CHANGED and don't count. Counted here rather than in the UI so plays with
     * the app closed count too. The song's details are kept with the count so the Top can show it.
     */
    private class CountPlays(private val player: Player, private val app: MusicApp) : Player.Listener {
        private var current: MediaItem? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            if (finished) current?.let(::count)
            current = mediaItem
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) player.currentMediaItem?.let(::count)
        }

        private fun count(item: MediaItem) {
            val id = item.mediaId.takeIf { it.isNotEmpty() } ?: return
            val md = item.mediaMetadata
            val track = Track(
                videoId = id,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                durationSec = (md.durationMs ?: 0L) / 1000,
                thumbnailUrl = md.artworkUri?.toString(),
            )
            app.appScope.launch { app.plays.countPlay(track) }
        }
    }

    private object Callback : MediaSession.Callback {
        /**
         * Media3 strips URIs from items sent by controllers; rebuild them from the mediaId
         * (which is the YouTube videoId).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.map { it.buildUpon().setUri(YouTubeDataSource.uriFor(it.mediaId)).build() }
                    .toMutableList(),
            )
    }
}

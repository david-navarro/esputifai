package dev.personal.music.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.personal.music.MusicApp
import dev.personal.music.R
import dev.personal.music.ui.MainActivity

/**
 * Foreground service that runs the downloads of [OfflineAudio], with a progress notification.
 * It stops by itself when there is nothing left to do.
 */
@OptIn(UnstableApi::class)
class AudioDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {
    private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as MusicApp).offline.downloadManager

    /**
     * No scheduler: Media3 only uses one below Android 12, and the phones this app runs on are
     * far newer. When the network comes back, [OfflineAudio] restarts us if the app is open.
     */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val message = if (notMetRequirements != 0) "Esperando conexión" else "Guardando canciones en el móvil"
        return notifications.buildProgressNotification(
            this, android.R.drawable.stat_sys_download, openApp, message, downloads, notMetRequirements,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "downloads"
    }
}

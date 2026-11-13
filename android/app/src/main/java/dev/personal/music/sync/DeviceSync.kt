package dev.personal.music.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.personal.music.data.AlbumLibrary
import dev.personal.music.data.AlbumStore
import dev.personal.music.data.PlayCountStore
import dev.personal.music.data.PlayHistory
import dev.personal.music.data.PlaylistLibrary
import dev.personal.music.data.PlaylistStore
import dev.personal.music.data.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** What the sync panel shows. */
sealed interface SyncStatus {
    /** Not turned on in this device. */
    data object Off : SyncStatus

    /** Turned on, but Google wants the user again (access revoked, password changed…). */
    data object NeedsSignIn : SyncStatus

    data object Syncing : SyncStatus

    /** [devices] = other devices whose data was received. */
    data class Synced(val atMs: Long, val devices: Int) : SyncStatus

    data class Failed(val message: String, val atMs: Long) : SyncStatus
}

/**
 * Keeps playlists, Albumteca and plays the same on all the user's devices (each person with their
 * own Google account), through the app's hidden folder in their Drive ([DriveAppData]).
 *
 * Every device writes only its own file, `device-<random id>.json`, with its whole library and
 * its own plays, and reads everyone else's: devices never overwrite each other's writes, so there
 * is no locking. What is received is merged with [SyncMerge] (rules there).
 *
 * When it syncs:
 * - a few seconds after any change here (a song added, a play counted… even with the screen off
 *   while music plays), to send it;
 * - when the app comes to the front, and every minute while it is there, to receive.
 * Each round lists the folder (one small request) and only downloads files that changed.
 */
@OptIn(FlowPreview::class)
class DeviceSync(
    context: Context,
    private val drive: DriveAppData,
    private val playlists: PlaylistStore,
    private val albums: AlbumStore,
    private val plays: PlayCountStore,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val fileName = "device-" + (prefs.getString(KEY_DEVICE, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }) + ".json"

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val enabled get() = prefs.getBoolean(KEY_ENABLED, false)
    private val mutex = Mutex()
    private var watcher: Job? = null
    private var poller: Job? = null
    private var foreground = false

    // What is known of the folder in this run of the app (all re-read after a restart).
    private var myFileId: String? = null
    private val seen = mutableMapOf<String, String>() // file id -> modifiedTime already read
    private val received = mutableMapOf<String, Remote>() // file id -> other device's data
    private var lastSent: String? = null

    private class Remote(val playlists: PlaylistLibrary, val albums: AlbumLibrary, val plays: PlayHistory)

    /** Called once when the app starts. */
    fun start() {
        if (enabled) {
            _status.value = SyncStatus.Syncing
            watchChanges()
        }
    }

    /** The app's screen is shown / hidden. */
    fun setForeground(shown: Boolean) {
        foreground = shown
        poller?.cancel()
        poller = null
        if (shown && enabled) {
            poller = scope.launch {
                while (isActive) {
                    syncNow()
                    delay(POLL_MS)
                }
            }
        }
    }

    /**
     * "Sincronizar con Google". Returns the Google screen to show (account, permission) if needed;
     * its result goes to [onGoogleResult]. Null when access was granted without it.
     */
    suspend fun turnOn(): PendingIntent? {
        val result = runCatching { drive.authorize() }.getOrElse {
            _status.value = SyncStatus.Failed(reason(it), System.currentTimeMillis())
            return null
        }
        if (result.hasResolution()) return result.pendingIntent
        enable()
        return null
    }

    /** True if the user granted access in the Google screen. */
    fun onGoogleResult(data: Intent?): Boolean {
        val granted = runCatching { drive.resultFrom(data) }.getOrNull()?.accessToken != null
        if (granted) enable()
        return granted
    }

    /** Stops syncing on this device; its data stays here and in Drive. */
    fun turnOff() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        watcher?.cancel()
        poller?.cancel()
        watcher = null
        poller = null
        _status.value = SyncStatus.Off
    }

    /** "Sincronizar ahora". */
    fun syncSoon() {
        scope.launch { syncNow(shown = true) }
    }

    private fun enable() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        _status.value = SyncStatus.Syncing
        watchChanges()
        setForeground(foreground) // starts the polling if the app is on screen
        if (!foreground) syncSoon()
    }

    private fun watchChanges() {
        watcher?.cancel()
        watcher = scope.launch {
            combine(playlists.library, albums.library, plays.mine) { _, _, _ -> }
                .drop(1) // the current value: nothing has changed yet
                .debounce(SEND_DELAY_MS)
                .collect { syncNow() }
        }
    }

    /**
     * One round: receive what changed, merge it, send ours if it differs from what was last sent.
     * [shown] = show "Sincronizando…" (manual rounds; automatic ones are silent unless they fail).
     */
    private suspend fun syncNow(shown: Boolean = false) = mutex.withLock {
        if (!enabled) return@withLock
        if (shown || _status.value !is SyncStatus.Synced) _status.value = SyncStatus.Syncing
        try {
            val token = drive.token()
            if (token == null) {
                _status.value = SyncStatus.NeedsSignIn
                return@withLock
            }
            // Never send before the local files are read: an empty library would look like "all deleted".
            playlists.load()
            albums.load()
            plays.load()

            val files = withContext(Dispatchers.IO) { drive.list(token) }
            myFileId = files.find { it.name == fileName }?.id
            val others = files.filter { it.name.startsWith("device-") && it.name != fileName }
            for (f in others.filter { seen[it.id] != it.modifiedTime }) {
                val remote = withContext(Dispatchers.IO) { runCatching { parse(drive.download(token, f.id)) }.getOrNull() }
                if (remote != null) received[f.id] = remote // unreadable (newer app version?): skipped
                seen[f.id] = f.modifiedTime
            }
            received.keys.retainAll(others.map { it.id }.toSet())

            playlists.merge(received.values.map { it.playlists })
            albums.merge(received.values.map { it.albums })
            plays.setOthers(SyncMerge.addUp(received.values.map { it.plays }))

            val mine = snapshot()
            if (mine != lastSent || myFileId == null) {
                withContext(Dispatchers.IO) {
                    val id = myFileId
                    if (id == null) myFileId = drive.create(token, fileName, mine) else drive.update(token, id, mine)
                }
                lastSent = mine
            }
            _status.value = SyncStatus.Synced(System.currentTimeMillis(), received.size)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _status.value = SyncStatus.Failed(reason(e), System.currentTimeMillis())
        }
    }

    /** This device's file: whole library (deletions included) and only its own plays. */
    private fun snapshot(): String = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("device", Build.MODEL)
        .put("playlists", PlaylistStore.encode(playlists.library.value))
        .put("albums", AlbumStore.encode(albums.library.value))
        .put("plays", PlayCountStore.encode(plays.mine.value))
        .toString()

    private fun parse(text: String): Remote? {
        val o = JSONObject(text)
        if (o.getInt("version") > FORMAT_VERSION) return null
        return Remote(
            PlaylistStore.decode(o.getJSONObject("playlists")),
            AlbumStore.decode(o.getJSONObject("albums")),
            PlayCountStore.decode(o.getJSONObject("plays")),
        )
    }

    private fun reason(e: Throwable): String = when (e) {
        is IOException -> "Sin conexión"
        is DriveAppData.DriveException -> "Google Drive ha respondido con un error (${e.code})"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val KEY_ENABLED = "enabled"
        const val KEY_DEVICE = "deviceId"
        const val SEND_DELAY_MS = 5_000L
        const val POLL_MS = 60_000L
    }
}

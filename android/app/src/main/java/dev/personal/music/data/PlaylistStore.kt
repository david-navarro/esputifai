package dev.personal.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user's local playlists, persisted in `playlists.json` (see [JsonStore] for how writes work).
 * Format v2: `{"version":2,"playlists":[{id,name,createdAtMs,updatedAtMs,offline,tracks:[Track…]}],
 * "deleted":{"<id>":<ms>}}`. v1 files (no `updatedAtMs`, no `deleted`) are read as created = updated.
 *
 * Every change of a list's name or songs stamps [Playlist.updatedAtMs] and every deletion is
 * remembered in `deleted`: that is what device sync needs to merge (see [SyncMerge]).
 */
class PlaylistStore(
    file: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonStore(file, PlaylistLibrary(), ::encode, ::decode, io, clock)
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /** Everything, deletions included: what sync sends to the other devices. */
    val library: StateFlow<PlaylistLibrary> = store.state

    /** All playlists in creation order (stable, so the list never jumps around). */
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun load() {
        store.load()
        publish()
    }

    /** Creates a playlist, empty or with [tracks] (an imported list; repeated songs kept once). */
    suspend fun create(name: String, offline: Boolean = false, tracks: List<Track> = emptyList()): Playlist = edit { lib ->
        val p = Playlist(UUID.randomUUID().toString(), cleanName(name), tracks.distinctBy { it.videoId }, clock(), offline)
        lib.copy(playlists = lib.playlists + p) to p
    }

    suspend fun rename(id: String, name: String) = editPlaylist(id) {
        if (it.name == cleanName(name)) it else it.copy(name = cleanName(name)).touched()
    }

    /** Switches a playlist between "saved on the phone" and streaming (this device only). */
    suspend fun setOffline(id: String, offline: Boolean) = editPlaylist(id) { it.copy(offline = offline) }

    suspend fun delete(id: String) = edit { lib ->
        if (lib.playlists.none { it.id == id }) lib to Unit
        else PlaylistLibrary(lib.playlists.filterNot { it.id == id }, lib.deleted + (id to clock())) to Unit
    }

    /** Appends [track]; returns false (and changes nothing) if it is already in the playlist. */
    suspend fun addTrack(id: String, track: Track, index: Int? = null): Boolean = edit { lib ->
        var added = false
        lib.mapIf(id) { p ->
            if (p.contains(track.videoId)) {
                p
            } else {
                added = true
                val at = (index ?: p.tracks.size).coerceIn(0, p.tracks.size)
                p.copy(tracks = p.tracks.toMutableList().apply { add(at, track) }).touched()
            }
        } to added
    }

    /** Removes the track; returns its former position (for undo) or -1 if it wasn't there. */
    suspend fun removeTrack(id: String, videoId: String): Int = edit { lib ->
        var removedAt = -1
        lib.mapIf(id) { p ->
            removedAt = p.tracks.indexOfFirst { it.videoId == videoId }
            if (removedAt < 0) p else p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != removedAt }).touched()
        } to removedAt
    }

    suspend fun moveTrack(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices || from == to) {
            p
        } else {
            p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) }).touched()
        }
    }

    /** Takes in what the other devices have (see [SyncMerge.playlists]). */
    suspend fun merge(remote: List<PlaylistLibrary>) = edit { lib -> SyncMerge.playlists(lib, remote) to Unit }

    private suspend fun <R> edit(change: (PlaylistLibrary) -> Pair<PlaylistLibrary, R>): R =
        store.edit(change).also { publish() }

    private fun publish() {
        _playlists.value = store.state.value.playlists
    }

    private suspend fun editPlaylist(id: String, change: (Playlist) -> Playlist) =
        edit { lib -> lib.mapIf(id, change) to Unit }

    private fun Playlist.touched() = copy(updatedAtMs = clock())

    private fun cleanName(name: String) = name.trim().ifEmpty { "Lista sin nombre" }

    private inline fun PlaylistLibrary.mapIf(id: String, change: (Playlist) -> Playlist) =
        copy(playlists = playlists.map { if (it.id == id) change(it) else it })

    companion object {
        private const val FORMAT_VERSION = 2

        fun encode(lib: PlaylistLibrary): JSONObject = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("playlists", JSONArray(lib.playlists.map { p ->
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("createdAtMs", p.createdAtMs)
                    .put("updatedAtMs", p.updatedAtMs)
                    .put("offline", p.offline)
                    .put("tracks", JSONArray(p.tracks.map { it.toJson() }))
            }))
            .put("deleted", lib.deleted.toJson())

        fun decode(root: JSONObject): PlaylistLibrary = PlaylistLibrary(
            playlists = root.getJSONArray("playlists").mapObjects { p ->
                val created = p.optLong("createdAtMs")
                Playlist(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    createdAtMs = created,
                    offline = p.optBoolean("offline", false), // absent in files from v0.2.0
                    tracks = p.getJSONArray("tracks").mapObjects(::trackFromJson),
                    updatedAtMs = p.optLong("updatedAtMs", created), // absent before v0.7.0
                )
            },
            deleted = root.optJSONObject("deleted").toTimes(),
        )
    }
}

/** `{"<id>":<ms>}` maps, used for deletions. */
fun Map<String, Long>.toJson(): JSONObject = JSONObject().also { o -> forEach { (k, v) -> o.put(k, v) } }

fun JSONObject?.toTimes(): Map<String, Long> =
    this?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }.orEmpty()

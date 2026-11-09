package dev.personal.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user's local playlists, persisted in `playlists.json` (see [JsonStore] for how writes work).
 * Format: `{"version":1,"playlists":[{id,name,createdAtMs,offline,tracks:[Track…]}]}`.
 */
class PlaylistStore(
    file: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonStore(file, emptyList(), ::encode, ::decode, io, clock)

    /** All playlists in creation order (stable, so the list never jumps around). */
    val playlists: StateFlow<List<Playlist>> = store.state

    suspend fun load() = store.load()

    suspend fun create(name: String, offline: Boolean = false): Playlist = store.edit { all ->
        val p = Playlist(UUID.randomUUID().toString(), cleanName(name), emptyList(), clock(), offline)
        (all + p) to p
    }

    suspend fun rename(id: String, name: String) = editPlaylist(id) { it.copy(name = cleanName(name)) }

    /** Switches a playlist between "saved on the phone" and streaming. */
    suspend fun setOffline(id: String, offline: Boolean) = editPlaylist(id) { it.copy(offline = offline) }

    suspend fun delete(id: String) = store.edit { all -> all.filterNot { it.id == id } to Unit }

    /** Appends [track]; returns false (and changes nothing) if it is already in the playlist. */
    suspend fun addTrack(id: String, track: Track, index: Int? = null): Boolean = store.edit { all ->
        var added = false
        all.mapIf(id) { p ->
            if (p.contains(track.videoId)) {
                p
            } else {
                added = true
                val at = (index ?: p.tracks.size).coerceIn(0, p.tracks.size)
                p.copy(tracks = p.tracks.toMutableList().apply { add(at, track) })
            }
        } to added
    }

    /** Removes the track; returns its former position (for undo) or -1 if it wasn't there. */
    suspend fun removeTrack(id: String, videoId: String): Int = store.edit { all ->
        var removedAt = -1
        all.mapIf(id) { p ->
            removedAt = p.tracks.indexOfFirst { it.videoId == videoId }
            if (removedAt < 0) p else p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != removedAt })
        } to removedAt
    }

    suspend fun moveTrack(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices || from == to) {
            p
        } else {
            p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    private suspend fun editPlaylist(id: String, change: (Playlist) -> Playlist) =
        store.edit { all -> all.mapIf(id, change) to Unit }

    private fun cleanName(name: String) = name.trim().ifEmpty { "Lista sin nombre" }

    private inline fun List<Playlist>.mapIf(id: String, change: (Playlist) -> Playlist) =
        map { if (it.id == id) change(it) else it }

    companion object {
        private const val FORMAT_VERSION = 1

        fun encode(playlists: List<Playlist>): JSONObject = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("playlists", JSONArray(playlists.map { p ->
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("createdAtMs", p.createdAtMs)
                    .put("offline", p.offline)
                    .put("tracks", JSONArray(p.tracks.map { it.toJson() }))
            }))

        fun decode(root: JSONObject): List<Playlist> = root.getJSONArray("playlists").mapObjects { p ->
            Playlist(
                id = p.getString("id"),
                name = p.getString("name"),
                createdAtMs = p.optLong("createdAtMs"),
                offline = p.optBoolean("offline", false), // absent in files from v0.2.0
                tracks = p.getJSONArray("tracks").mapObjects(::trackFromJson),
            )
        }
    }
}

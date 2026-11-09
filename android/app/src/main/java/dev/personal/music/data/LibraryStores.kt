package dev.personal.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Albumteca, persisted in `albums.json`:
 * `{"version":1,"albums":[{id,title,artist,thumbnailUrl,addedAtMs,offline,tracks:[Track…]}]}`.
 */
class AlbumStore(
    file: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonStore(file, emptyList(), ::encode, ::decode, io, clock)

    /** Albums in the order they were added; the screen sorts them. */
    val albums: StateFlow<List<LibraryAlbum>> = store.state

    suspend fun load() = store.load()

    /** Adds the album with its tracks; returns false if it was already there. */
    suspend fun add(album: Album, tracks: List<Track>, offline: Boolean = false): Boolean = store.edit { all ->
        if (all.any { it.id == album.id }) all to false
        else (all + LibraryAlbum(album, tracks, clock(), offline)) to true
    }

    suspend fun remove(id: String) = store.edit { all -> all.filterNot { it.id == id } to Unit }

    /** Switches an album between "saved on the phone" and streaming. */
    suspend fun setOffline(id: String, offline: Boolean) =
        store.edit { all -> all.map { if (it.id == id) it.copy(offline = offline) else it } to Unit }

    companion object {
        fun encode(albums: List<LibraryAlbum>): JSONObject = JSONObject()
            .put("version", 1)
            .put("albums", JSONArray(albums.map { a ->
                JSONObject()
                    .put("id", a.album.id)
                    .put("title", a.album.title)
                    .put("artist", a.album.artist)
                    .put("thumbnailUrl", a.album.thumbnailUrl ?: JSONObject.NULL)
                    .put("addedAtMs", a.addedAtMs)
                    .put("offline", a.offline)
                    .put("tracks", JSONArray(a.tracks.map { it.toJson() }))
            }))

        fun decode(root: JSONObject): List<LibraryAlbum> = root.getJSONArray("albums").mapObjects { a ->
            LibraryAlbum(
                album = Album(
                    id = a.getString("id"),
                    title = a.optString("title"),
                    artist = a.optString("artist"),
                    thumbnailUrl = if (a.isNull("thumbnailUrl")) null else a.getString("thumbnailUrl"),
                ),
                tracks = a.getJSONArray("tracks").mapObjects(::trackFromJson),
                addedAtMs = a.optLong("addedAtMs"),
                offline = a.optBoolean("offline", false),
            )
        }
    }
}

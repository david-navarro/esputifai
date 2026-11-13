package dev.personal.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Albumteca, persisted in `albums.json`:
 * `{"version":1,"albums":[{id,title,artist,thumbnailUrl,addedAtMs,offline,tracks:[Track…]}],
 * "deleted":{"<id>":<ms>}}`. `deleted` (since v0.7.0, optional) remembers removals for sync; an
 * album's content never changes, so for sync its version is simply [LibraryAlbum.addedAtMs].
 */
class AlbumStore(
    file: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonStore(file, AlbumLibrary(), ::encode, ::decode, io, clock)
    private val _albums = MutableStateFlow<List<LibraryAlbum>>(emptyList())

    /** Everything, removals included: what sync sends to the other devices. */
    val library: StateFlow<AlbumLibrary> = store.state

    /** Albums in the order they were added; the screen sorts them. */
    val albums: StateFlow<List<LibraryAlbum>> = _albums.asStateFlow()

    suspend fun load() {
        store.load()
        publish()
    }

    /** Adds the album with its tracks; returns false if it was already there. */
    suspend fun add(album: Album, tracks: List<Track>, offline: Boolean = false): Boolean = edit { lib ->
        if (lib.albums.any { it.id == album.id }) lib to false
        else lib.copy(albums = lib.albums + LibraryAlbum(album, tracks, clock(), offline)) to true
    }

    suspend fun remove(id: String) = edit { lib ->
        if (lib.albums.none { it.id == id }) lib to Unit
        else AlbumLibrary(lib.albums.filterNot { it.id == id }, lib.deleted + (id to clock())) to Unit
    }

    /** Switches an album between "saved on the phone" and streaming (this device only). */
    suspend fun setOffline(id: String, offline: Boolean) = edit { lib ->
        lib.copy(albums = lib.albums.map { if (it.id == id) it.copy(offline = offline) else it }) to Unit
    }

    /** Takes in what the other devices have (see [SyncMerge.albums]). */
    suspend fun merge(remote: List<AlbumLibrary>) = edit { lib -> SyncMerge.albums(lib, remote) to Unit }

    private suspend fun <R> edit(change: (AlbumLibrary) -> Pair<AlbumLibrary, R>): R =
        store.edit(change).also { publish() }

    private fun publish() {
        _albums.value = store.state.value.albums
    }

    companion object {
        fun encode(lib: AlbumLibrary): JSONObject = JSONObject()
            .put("version", 1)
            .put("albums", JSONArray(lib.albums.map { a ->
                JSONObject()
                    .put("id", a.album.id)
                    .put("title", a.album.title)
                    .put("artist", a.album.artist)
                    .put("thumbnailUrl", a.album.thumbnailUrl ?: JSONObject.NULL)
                    .put("addedAtMs", a.addedAtMs)
                    .put("offline", a.offline)
                    .put("tracks", JSONArray(a.tracks.map { it.toJson() }))
            }))
            .put("deleted", lib.deleted.toJson())

        fun decode(root: JSONObject): AlbumLibrary = AlbumLibrary(
            albums = root.getJSONArray("albums").mapObjects { a ->
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
            },
            deleted = root.optJSONObject("deleted").toTimes(),
        )
    }
}

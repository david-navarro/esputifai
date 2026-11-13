package dev.personal.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How many times a song has started playing, with what is needed to show it in the Top. */
data class SongPlays(
    val count: Int,
    val lastPlayedMs: Long,
    /** Null for songs counted before v0.5.0, which only stored the count. */
    val track: Track?,
)

/** An album the user has played from its page, remembered so the Top can rank it. */
data class PlayedAlbum(val album: Album, val trackIds: List<String>)

data class PlayHistory(
    val songs: Map<String, SongPlays> = emptyMap(),
    val albums: Map<String, PlayedAlbum> = emptyMap(),
) {
    /** Plays by videoId, the shape the Albumteca order and the song rows need. */
    val counts: Map<String, Int> by lazy { songs.mapValues { it.value.count } }
}

/**
 * Play history in `plays.json`:
 * `{"version":2,"songs":{"<videoId>":{count,lastPlayedMs,track}},"albums":{"<id>":{album,trackIds}}}`.
 * Version 1 files (`{"plays":{"<videoId>":n}}`) are read as songs without a track.
 *
 * With device sync, `plays.json` only holds this device's plays ([mine], what sync sends) and
 * `plays-others.json` the sum of the other devices' ones, as last received. [history], what the
 * app shows, is both added up: each device only ever counts its own plays, so nothing is counted
 * twice however often devices sync.
 */
class PlayCountStore(
    file: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    othersFile: File = File(file.parentFile, "plays-others.json"),
) {
    private val own = JsonStore(file, PlayHistory(), ::encode, ::decode, io, clock)
    private val others = JsonStore(othersFile, PlayHistory(), ::encode, ::decode, io, clock)
    private val _history = MutableStateFlow(PlayHistory())

    /** This device's plays. */
    val mine: StateFlow<PlayHistory> = own.state

    /** All plays, from every synced device. */
    val history: StateFlow<PlayHistory> = _history.asStateFlow()

    suspend fun load() {
        own.load()
        others.load()
        publish()
    }

    /** Called by the player each time [track] is played to the end. */
    suspend fun countPlay(track: Track) {
        own.edit { h ->
            val before = h.songs[track.videoId]
            val now = SongPlays((before?.count ?: 0) + 1, clock(), track)
            h.copy(songs = h.songs + (track.videoId to now)) to Unit
        }
        publish()
    }

    /** Remembers an album played from its page (no-op if already known with the same songs). */
    suspend fun rememberAlbum(album: Album, tracks: List<Track>) {
        own.edit { h ->
            h.copy(albums = h.albums + (album.id to PlayedAlbum(album, tracks.map { it.videoId }))) to Unit
        }
        publish()
    }

    /** Replaces the other devices' plays with what sync has just received from them. */
    suspend fun setOthers(sum: PlayHistory) {
        others.edit { sum to Unit }
        publish()
    }

    private fun publish() {
        _history.value = SyncMerge.addUp(listOf(own.state.value, others.state.value))
    }

    companion object {
        fun encode(h: PlayHistory): JSONObject = JSONObject()
            .put("version", 2)
            .put("songs", JSONObject().apply {
                h.songs.forEach { (id, s) ->
                    put(id, JSONObject()
                        .put("count", s.count)
                        .put("lastPlayedMs", s.lastPlayedMs)
                        .put("track", s.track?.toJson() ?: JSONObject.NULL))
                }
            })
            .put("albums", JSONObject().apply {
                h.albums.forEach { (id, a) ->
                    put(id, JSONObject()
                        .put("album", a.album.toJson())
                        .put("trackIds", JSONArray(a.trackIds)))
                }
            })

        fun decode(root: JSONObject): PlayHistory {
            if (root.optInt("version", 1) < 2) {
                val o = root.getJSONObject("plays")
                return PlayHistory(o.keys().asSequence().associateWith { SongPlays(o.getInt(it), 0, null) })
            }
            val songs = root.getJSONObject("songs")
            val albums = root.optJSONObject("albums") ?: JSONObject()
            return PlayHistory(
                songs = songs.keys().asSequence().associateWith { id ->
                    val s = songs.getJSONObject(id)
                    SongPlays(
                        count = s.getInt("count"),
                        lastPlayedMs = s.optLong("lastPlayedMs"),
                        track = s.optJSONObject("track")?.let(::trackFromJson),
                    )
                },
                albums = albums.keys().asSequence().associateWith { id ->
                    val a = albums.getJSONObject(id)
                    val ids = a.getJSONArray("trackIds")
                    PlayedAlbum(albumFromJson(a.getJSONObject("album")), (0 until ids.length()).map(ids::getString))
                },
            )
        }
    }
}

fun Album.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("artist", artist)
    .put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)

fun albumFromJson(a: JSONObject) = Album(
    id = a.getString("id"),
    title = a.optString("title"),
    artist = a.optString("artist"),
    thumbnailUrl = if (a.isNull("thumbnailUrl")) null else a.getString("thumbnailUrl"),
)

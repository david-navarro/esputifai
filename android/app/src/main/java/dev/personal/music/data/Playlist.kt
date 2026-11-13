package dev.personal.music.data

/**
 * A user-made playlist stored on the device. Tracks are unique by videoId and kept in order.
 * [offline] = "Guardada en el móvil": all its songs are downloaded and kept while it stays on.
 * It is a choice of each device, so sync never changes it.
 * [updatedAtMs] = last change of its name or songs; between devices, the latest change wins.
 */
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track>,
    val createdAtMs: Long,
    val offline: Boolean = false,
    val updatedAtMs: Long = createdAtMs,
) {
    fun contains(videoId: String): Boolean = tracks.any { it.videoId == videoId }
}

/**
 * The playlists plus when each deleted one was deleted (by id), so a deletion reaches the other
 * devices instead of the list coming back from them.
 */
data class PlaylistLibrary(
    val playlists: List<Playlist> = emptyList(),
    val deleted: Map<String, Long> = emptyMap(),
)

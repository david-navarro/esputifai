package dev.personal.music.data

/**
 * A user-made playlist stored on the device. Tracks are unique by videoId and kept in order.
 * [offline] = "Guardada en el móvil": all its songs are downloaded and kept while it stays on.
 */
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track>,
    val createdAtMs: Long,
    val offline: Boolean = false,
) {
    fun contains(videoId: String): Boolean = tracks.any { it.videoId == videoId }
}

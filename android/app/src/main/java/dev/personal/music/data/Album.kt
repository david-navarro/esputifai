package dev.personal.music.data

import java.text.Collator
import java.util.Locale

/** A YouTube Music album (or single/EP). [id] is its playlist id (`OLAK5uy_…`). */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
) {
    val url get() = "https://music.youtube.com/playlist?list=$id"
}

/** A YouTube Music artist. [channelId] identifies their songs and albums in search results. */
data class Artist(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String?,
)

/**
 * An album in the user's Albumteca. Its tracks are stored when it is added, so it can be saved on
 * the phone and its plays counted without asking YouTube again.
 */
data class LibraryAlbum(
    val album: Album,
    val tracks: List<Track>,
    val addedAtMs: Long,
    val offline: Boolean = false,
) {
    val id get() = album.id
}

enum class AlbumOrder(val label: String) {
    MostPlayed("Más escuchados"),
    AToZ("A-Z"),
    ZToA("Z-A"),
}

/**
 * Albumteca order. "Más escuchados" = plays of the album's songs from anywhere (owner's decision,
 * 2026-11-07); ties, and never-played albums, go alphabetically. Alphabetical order ignores case
 * and accents (Spanish collation), so "Ángel" sorts with "A".
 */
fun sortAlbums(albums: List<LibraryAlbum>, order: AlbumOrder, plays: Map<String, Int>): List<LibraryAlbum> {
    val collator = Collator.getInstance(Locale("es", "ES")).apply { strength = Collator.PRIMARY }
    val byTitle = compareBy<LibraryAlbum, String>(collator) { it.album.title }
    return when (order) {
        AlbumOrder.AToZ -> albums.sortedWith(byTitle)
        AlbumOrder.ZToA -> albums.sortedWith(byTitle.reversed())
        AlbumOrder.MostPlayed -> albums.sortedWith(
            compareByDescending<LibraryAlbum> { playsOf(it, plays) }.then(byTitle),
        )
    }
}

fun playsOf(album: LibraryAlbum, plays: Map<String, Int>): Int = album.tracks.sumOf { plays[it.videoId] ?: 0 }

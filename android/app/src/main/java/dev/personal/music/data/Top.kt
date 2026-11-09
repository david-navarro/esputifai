package dev.personal.music.data

import java.text.Collator
import java.util.Locale

/** One row of the Top: something and how many times the user has played it. */
data class Ranked<T>(val item: T, val plays: Int)

/** An artist as the Top knows them: by name (songs don't carry the channel id). */
data class TopArtist(val name: String, val thumbnailUrl: String?)

/**
 * The user's most played songs, artists and albums, computed from [PlayHistory]. Ties go to the
 * most recently played (songs) or alphabetically (artists, albums); things never played are left
 * out. Lists are capped at [LIMIT] rows.
 */
object Top {
    const val LIMIT = 100

    /**
     * Songs with their stored metadata. Songs counted before v0.5.0 have none; [known] (the
     * playlists' and Albumteca's songs) fills it in where possible, the rest are skipped.
     */
    fun songs(history: PlayHistory, known: List<Track> = emptyList(), limit: Int = LIMIT): List<Ranked<Track>> {
        val byId = known.associateBy { it.videoId }
        return history.songs.entries
            .mapNotNull { (id, s) -> (s.track ?: byId[id])?.let { Triple(it, s.count, s.lastPlayedMs) } }
            .sortedWith(compareByDescending<Triple<Track, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(limit)
            .map { Ranked(it.first, it.second) }
    }

    /**
     * Artists by the plays of their songs; pass all of them (`songs(…, limit = Int.MAX_VALUE)`). Names are matched ignoring case and accents, so
     * "Rosalía" and "ROSALÍA" are one artist; the name shown is the spelling of their most played
     * song, and so is the picture.
     */
    fun artists(songs: List<Ranked<Track>>): List<Ranked<TopArtist>> {
        val collator = spanishCollator()
        return songs
            .filter { it.item.artist.isNotBlank() }
            .groupBy { it.item.artist.trim().lowercase(Locale.ROOT).stripAccents() }
            .values
            .map { group ->
                val top = group.maxBy { it.plays }
                Ranked(TopArtist(top.item.artist.trim(), top.item.thumbnailUrl), group.sumOf { it.plays })
            }
            .sortedWith(compareByDescending<Ranked<TopArtist>> { it.plays }.thenBy(collator) { it.item.name })
            .take(LIMIT)
    }

    /**
     * Albums in the Albumteca or played from their page, by the plays of their songs (the same
     * measure as the Albumteca's "Más escuchados").
     */
    fun albums(history: PlayHistory, albumteca: List<LibraryAlbum>): List<Ranked<Album>> {
        val counts = history.counts
        val candidates = history.albums.values.associate { it.album.id to (it.album to it.trackIds) } +
            albumteca.associate { it.id to (it.album to it.tracks.map(Track::videoId)) }
        val collator = spanishCollator()
        return candidates.values
            .map { (album, ids) -> Ranked(album, ids.sumOf { counts[it] ?: 0 }) }
            .filter { it.plays > 0 }
            .sortedWith(compareByDescending<Ranked<Album>> { it.plays }.thenBy(collator) { it.item.title })
            .take(LIMIT)
    }

    private fun spanishCollator() = Collator.getInstance(Locale("es", "ES")).apply { strength = Collator.PRIMARY }

    private fun String.stripAccents() =
        java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}

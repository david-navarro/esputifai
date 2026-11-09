package dev.personal.music.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Smoke test of related songs, albums and artists against the real YouTube Music (needs network).
 * Run: scripts/build-apk.sh testDebugUnitTest
 */
class CatalogSmokeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = YouTubeMusic.init(OkHttpClient())
    }

    @Test
    fun relatedSongsPageByTenWithoutRepeats() {
        val results = YouTubeMusic.search("rosalía malamente")
        val seed = results.first()
        val related = RelatedSongs(seed.videoId, exclude = results)
        val pages = (1..3).map { related.next() }
        pages.forEachIndexed { i, p -> println("related page $i: ${p.map { "${it.title} — ${it.artist}" }}") }
        assertTrue("first page should be full", pages[0].size == RelatedSongs.PAGE_SIZE)
        val all = pages.flatten().map { it.videoId }
        assertEquals("no repeats across pages", all.size, all.toSet().size)
        assertFalse("seed must not be related to itself", seed.videoId in all)
        assertTrue("search results are not repeated", results.none { it.videoId in all })
        val keys = pages.flatten().map(RelatedSongs::titleKey)
        assertEquals("no video versions of songs already given", keys.size, keys.toSet().size)
    }

    @Test
    fun titleKeyMatchesVideoVersions() {
        val a = Track("1", "DI MI NOMBRE (Cap.8: Éxtasis)", "ROSALÍA", 0, null)
        val b = Track("2", "ROSALÍA - DI MI NOMBRE (Cap.8: Éxtasis) (Official Video)", "ROSALÍA", 0, null)
        assertEquals(RelatedSongs.titleKey(a), RelatedSongs.titleKey(b))
    }

    @Test
    fun albumSearchAndTracks() {
        val album = YouTubeMusic.searchAlbums("rosalía motomami").first()
        println("album: $album")
        assertTrue(album.id.startsWith("OLAK5uy_"))
        val tracks = YouTubeMusic.albumTracks(album)
        println("tracks (${tracks.size}): ${tracks.take(5).map { it.title }}")
        assertTrue("an album has songs", tracks.size >= 5)
        assertTrue("tracks carry the album cover", tracks.all { it.thumbnailUrl == album.thumbnailUrl })
    }

    @Test
    fun artistPageKeepsOnlyTheirSongsAndAlbums() {
        val artist = YouTubeMusic.searchArtists("rosalía").first()
        println("artist: $artist")
        val songs = YouTubeMusic.artistSongs(artist)
        val albums = YouTubeMusic.artistAlbums(artist)
        println("songs (${songs.size}): ${songs.take(8).map { it.title }}")
        println("albums (${albums.size}): ${albums.map { it.title }}")
        assertTrue("songs found", songs.size >= 5)
        assertTrue("albums found", albums.size >= 3)
        assertTrue("only this artist's albums", albums.all { it.artist.equals(artist.name, ignoreCase = true) })
    }
}

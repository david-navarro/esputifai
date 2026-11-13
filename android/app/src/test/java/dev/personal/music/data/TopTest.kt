package dev.personal.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Play history persistence (including v1 files) and the Top rankings. */
class TopTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun t(id: String, artist: String = "Artist") = Track(id, "Song $id", artist, 200, "https://img/$id")
    private fun plays(vararg p: Pair<Track, Int>, lastMs: Long = 0) =
        PlayHistory(p.associate { (track, n) -> track.videoId to SongPlays(n, lastMs, track) })

    @Test
    fun historySurvivesReopenWithSongDetailsAndAlbums() = runBlocking {
        val file = dir.root.resolve("plays.json")
        var now = 1_000L
        val s = PlayCountStore(file, Dispatchers.Unconfined, clock = { now })
        s.countPlay(t("a")); now = 2_000L; s.countPlay(t("a"))
        val album = Album("OLAK5uy_x", "Motomami", "ROSALÍA", null)
        s.rememberAlbum(album, listOf(t("a"), t("b")))
        val reopened = PlayCountStore(file, Dispatchers.Unconfined).also { it.load() }.history.value
        assertEquals(s.history.value, reopened)
        assertEquals(SongPlays(2, 2_000L, t("a")), reopened.songs["a"])
        assertEquals(PlayedAlbum(album, listOf("a", "b")), reopened.albums[album.id])
    }

    @Test
    fun readsVersion1FilesAsCountsWithoutDetails() = runBlocking {
        val file = dir.root.resolve("plays.json")
        file.writeText("""{"version":1,"plays":{"a":3,"b":1}}""")
        val h = PlayCountStore(file, Dispatchers.Unconfined).also { it.load() }.history.value
        assertEquals(mapOf("a" to 3, "b" to 1), h.counts)
        assertNull(h.songs.getValue("a").track)
        // Old songs come back in the Top if a list or album knows them; unknown ones are skipped.
        assertEquals(listOf("a"), Top.songs(h, known = listOf(t("a"))).map { it.item.videoId })
    }

    @Test
    fun songsByPlaysThenMostRecent() {
        val h = PlayHistory(mapOf(
            "old" to SongPlays(2, 100, t("old")),
            "new" to SongPlays(2, 200, t("new")),
            "top" to SongPlays(5, 0, t("top")),
        ))
        assertEquals(listOf("top", "new", "old"), Top.songs(h).map { it.item.videoId })
    }

    @Test
    fun artistsMergeSpellingsAndSumPlays() {
        val h = plays(t("1", "ROSALÍA") to 3, t("2", "Rosalia") to 2, t("3", "Estopa") to 4, t("4", "") to 9)
        val artists = Top.artists(Top.songs(h, limit = Int.MAX_VALUE))
        assertEquals(listOf("ROSALÍA" to 5, "Estopa" to 4), artists.map { it.item.name to it.plays })
        assertEquals("picture of their most played song", "https://img/1", artists[0].item.thumbnailUrl)
    }

    @Test
    fun albumsFromAlbumtecaAndPlayedPagesSkippingUnplayed() {
        val played = Album("p", "Played page", "X", null)
        val h = plays(t("a") to 1, t("b") to 2, t("c") to 4).copy(
            albums = mapOf("p" to PlayedAlbum(played, listOf("c"))),
        )
        val albumteca = listOf(
            LibraryAlbum(Album("l", "Library", "Y", null), listOf(t("a"), t("b")), 0),
            LibraryAlbum(Album("never", "Never played", "Z", null), listOf(t("z")), 0),
        )
        assertEquals(listOf("p" to 4, "l" to 3), Top.albums(h, albumteca).map { it.item.id to it.plays })
    }
}

package dev.personal.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Albumteca order and persistence of albums and play counts. */
class AlbumtecaTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun t(id: String) = Track(id, "Song $id", "Artist", 200, null)
    private fun lib(title: String, vararg songs: String) =
        LibraryAlbum(Album("id-$title", title, "Artist", null), songs.map(::t), 0)

    @Test
    fun ordersByPlaysThenAlphabetically() {
        val albums = listOf(lib("Ángel", "a"), lib("zeta", "z1", "z2"), lib("Motomami", "m"), lib("bravo", "b"))
        val plays = mapOf("z1" to 3, "z2" to 3, "m" to 5) // zeta 6, Motomami 5
        assertEquals(listOf("zeta", "Motomami", "Ángel", "bravo"), sortAlbums(albums, AlbumOrder.MostPlayed, plays).map { it.album.title })
        assertEquals(listOf("Ángel", "bravo", "Motomami", "zeta"), sortAlbums(albums, AlbumOrder.AToZ, plays).map { it.album.title })
        assertEquals(listOf("zeta", "Motomami", "bravo", "Ángel"), sortAlbums(albums, AlbumOrder.ZToA, plays).map { it.album.title })
    }

    @Test
    fun albumsAndPlaysSurviveReopen() = runBlocking {
        val file = dir.root.resolve("albums.json")
        val s = AlbumStore(file, Dispatchers.Unconfined)
        val a = Album("OLAK5uy_x", "Motomami", "ROSALÍA", "https://example/cover")
        assertTrue(s.add(a, listOf(t("a"), t("b"))))
        assertFalse("no duplicates", s.add(a, emptyList()))
        s.setOffline(a.id, true)
        val reopened = AlbumStore(file, Dispatchers.Unconfined).also { it.load() }.albums.value
        assertEquals(s.albums.value, reopened)
        assertTrue(reopened.single().offline)

        val playsFile = dir.root.resolve("plays.json")
        val p = PlayCountStore(playsFile, Dispatchers.Unconfined)
        p.countPlay("a"); p.countPlay("a"); p.countPlay("b")
        val counts = PlayCountStore(playsFile, Dispatchers.Unconfined).also { it.load() }.plays.value
        assertEquals(mapOf("a" to 2, "b" to 1), counts)
        assertEquals(3, playsOf(reopened.single(), counts))
    }
}

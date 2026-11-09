package dev.personal.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared-playlist links: round trip, the three forms they arrive in, and their size. */
class PlaylistShareTest {
    private fun t(i: Int) = Track("vid${i.toString().padStart(8, '0')}", "Canción número $i (Remastered)", "Artista ÁÉÍ $i", 200L + i, null)
    private val tracks = (1..100).map(::t)

    @Test
    fun roundTripKeepsNameSongsAndOrder() {
        val link = PlaylistShare.link("Verano «2026» & co", tracks)
        val back = PlaylistShare.parse(link)!!
        assertEquals("Verano «2026» & co", back.name)
        assertEquals(tracks.map { it.copy(thumbnailUrl = PlaylistShare.thumbnailOf(it.videoId)) }, back.tracks)
    }

    @Test
    fun acceptsWebLinkAppLinkAndBareData() {
        val data = PlaylistShare.encode("L", tracks.take(3))
        for (form in listOf(PlaylistShare.PAGE + "#" + data, "esputifai://import?d=$data", data)) {
            assertEquals(form, 3, PlaylistShare.parse(form)?.tracks?.size)
        }
    }

    @Test
    fun rejectsGarbageAndCutLinks() {
        val link = PlaylistShare.link("L", tracks)
        assertNull(PlaylistShare.parse("https://example.com/#hola"))
        assertNull(PlaylistShare.parse(link.dropLast(20)))
        assertNull(PlaylistShare.parse(""))
    }

    @Test
    fun hundredSongsFitInAWhatsAppMessage() {
        val link = PlaylistShare.link("Lista", tracks)
        println("100 songs -> ${link.length} chars")
        assertTrue(link.length < 6_000)
    }
}

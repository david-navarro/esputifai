package dev.personal.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PlaylistStore against real search results (needs network for the first test): what goes in
 * must come back identical after reopening the file, with order and edits preserved.
 */
class PlaylistStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun store() = PlaylistStore(dir.root.resolve("playlists.json"), Dispatchers.Unconfined)

    @Test
    fun realTracksSurviveReopenWithEdits() = runBlocking {
        YouTubeMusic.init(OkHttpClient())
        val found = YouTubeMusic.search("rosalía malamente") + YouTubeMusic.search("serrat mediterráneo")
        val tracks = found.distinctBy { it.videoId }.take(5)
        assertTrue("need at least 4 real tracks, got ${tracks.size}", tracks.size >= 4)

        val s = store()
        val p = s.create("  Para cocinar  ")
        assertEquals("Para cocinar", p.name)
        tracks.forEach { assertTrue(s.addTrack(p.id, it)) }
        assertFalse("duplicates must be refused", s.addTrack(p.id, tracks[0]))

        s.moveTrack(p.id, 0, 2)                       // [1, 2, 0, 3, 4…]
        val removedAt = s.removeTrack(p.id, tracks[3].videoId)
        assertEquals(3, removedAt)
        s.addTrack(p.id, tracks[3], removedAt)        // undo puts it back in place
        s.rename(p.id, "Cocina")
        val other = s.create("Vacía", offline = true)
        s.setOffline(p.id, true)
        s.setOffline(p.id, false)

        // Reopen from disk, as after an app restart.
        val reopened = store().also { it.load() }.playlists.value
        assertEquals(s.playlists.value, reopened)
        assertEquals(listOf("Cocina", "Vacía"), reopened.map { it.name })
        assertEquals(
            listOf(tracks[1], tracks[2], tracks[0], tracks[3]) + tracks.drop(4),
            reopened[0].tracks,
        )
        assertTrue(reopened[1].tracks.isEmpty())
        assertEquals(listOf(false, true), reopened.map { it.offline })
        reopened[0].tracks.forEach { println("saved: ${it.title} — ${it.artist} (${it.durationSec}s) ${it.thumbnailUrl}") }

        s.delete(other.id)
        assertEquals(listOf("Cocina"), store().also { it.load() }.playlists.value.map { it.name })
    }

    @Test
    fun unreadableFileIsSetAsideNotOverwritten() = runBlocking {
        val file = dir.root.resolve("playlists.json")
        file.writeText("{ this is not json")
        val s = store()
        s.load()
        assertTrue(s.playlists.value.isEmpty())
        val aside = dir.root.listFiles()!!.single { it.name.startsWith("playlists.json.unreadable-") }
        assertEquals("{ this is not json", aside.readText())

        s.create("Nueva")
        assertEquals(1, store().also { it.load() }.playlists.value.size)
    }

    @Test
    fun filesFromV020WithoutOfflineFieldLoadAsStreaming() = runBlocking {
        dir.root.resolve("playlists.json").writeText(
            """{"version":1,"playlists":[{"id":"x","name":"Vieja","createdAtMs":1,"tracks":[]}]}""",
        )
        val p = store().also { it.load() }.playlists.value.single()
        assertEquals("Vieja", p.name)
        assertFalse(p.offline)
    }

    @Test
    fun blankNameGetsDefault() = runBlocking {
        assertEquals("Lista sin nombre", store().create("   ").name)
    }
}

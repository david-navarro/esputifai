package dev.personal.music.offline

import dev.personal.music.data.Album
import dev.personal.music.data.LibraryAlbum
import dev.personal.music.data.Playlist
import dev.personal.music.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "what must be on the phone" rules agreed with the owner (README, "Saved playlists"). */
class OfflineRulesTest {
    private fun t(id: String) = Track(id, "Song $id", "Artist", 200, null)
    private fun list(id: String, offline: Boolean, vararg songs: String) =
        Playlist(id, id, songs.map(::t), 0, offline)
    private fun saved(bytes: Long = 4_000_000) = SavedAudio(SaveState.Saved, 100f, bytes)

    @Test
    fun onlySavedListsWantSongs() {
        val lists = listOf(list("cocina", true, "a", "b"), list("coche", false, "c"))
        assertEquals(setOf("a", "b"), wantedOnPhone(lists))
    }

    @Test
    fun songSharedWithAnotherSavedListIsKept() {
        // "Cocina" goes to streaming; "a" is also in "Coche", which stays saved.
        val before = mapOf("a" to saved(), "b" to saved())
        val after = listOf(list("cocina", false, "a", "b"), list("coche", true, "a"))
        val plan = planSync(wantedOnPhone(after), before)
        assertEquals(setOf("b"), plan.remove)
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun savedAlbumKeepsSongsOfAListGoingToStreaming() {
        val album = LibraryAlbum(Album("OLAK", "Motomami", "ROSALÍA", null), listOf(t("a"), t("x")), 0, offline = true)
        val lists = listOf(list("cocina", false, "a", "b"))
        val plan = planSync(wantedOnPhone(lists, listOf(album)), mapOf("a" to saved(), "b" to saved()))
        assertEquals(setOf("b"), plan.remove)  // "a" stays: the saved album needs it
        assertEquals(setOf("x"), plan.add)     // the album's other song downloads
        assertTrue(wantedOnPhone(lists, listOf(album.copy(offline = false))).isEmpty())
    }

    @Test
    fun addingToSavedListDownloadsOnlyNewSongs() {
        val plan = planSync(setOf("a", "b"), mapOf("a" to saved()))
        assertEquals(setOf("b"), plan.add)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun failedAreNotRetriedBySyncButRemovingCanBeReAdded() {
        val existing = mapOf(
            "f" to SavedAudio(SaveState.Failed, 0f, 0),
            "r" to SavedAudio(SaveState.Removing, 0f, 0),
        )
        val plan = planSync(setOf("f", "r"), existing)
        assertEquals(setOf("r"), plan.add) // re-wanted while being deleted
        assertTrue(plan.remove.isEmpty())
        // Removing is not removed twice.
        assertTrue(planSync(emptySet(), existing).remove == setOf("f"))
    }

    @Test
    fun progressAndSizes() {
        val p = list("cocina", true, "a", "b", "c")
        val audio = mapOf("a" to saved(3_000_000), "b" to SavedAudio(SaveState.Failed, 0f, 0))
        val progress = progressOf(p, audio)
        assertEquals(OfflineProgress(total = 3, saved = 1, failed = 1, bytes = 3_000_000), progress)
        assertEquals(1, progress.pending)
        assertEquals("96 MB", formatBytes(96_200_000))
        assertEquals("1,2 GB", formatBytes(1_230_000_000))
    }
}

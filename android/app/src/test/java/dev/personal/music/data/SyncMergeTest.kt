package dev.personal.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Device sync rules (SyncMerge), with real stores playing two devices ("phone" and "tablet")
 * that exchange what DeviceSync would send through Drive. Clocks are fake so "later" is explicit.
 */
class SyncMergeTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun t(id: String) = Track(id, "Song $id", "Artist", 200, null)

    /** A device: its stores in its own folder, all on one adjustable clock. */
    private inner class Device(name: String) {
        var now = 1_000L
        private val folder = dir.newFolder(name)
        val playlists = PlaylistStore(folder.resolve("playlists.json"), Dispatchers.Unconfined) { now }
        val albums = AlbumStore(folder.resolve("albums.json"), Dispatchers.Unconfined) { now }
        val plays = PlayCountStore(folder.resolve("plays.json"), Dispatchers.Unconfined, { now })

        /** One sync round with [other], as DeviceSync does it (other's file → merge here). */
        suspend fun receive(other: Device) {
            // Through JSON, like the real Drive file.
            playlists.merge(listOf(PlaylistStore.decode(JSONObject(PlaylistStore.encode(other.playlists.library.value).toString()))))
            albums.merge(listOf(AlbumStore.decode(JSONObject(AlbumStore.encode(other.albums.library.value).toString()))))
            plays.setOthers(PlayCountStore.decode(JSONObject(PlayCountStore.encode(other.plays.mine.value).toString())))
        }
    }

    private suspend fun syncBoth(a: Device, b: Device) {
        a.receive(b)
        b.receive(a)
        a.receive(b)
    }

    private fun Device.names() = playlists.playlists.value.map { it.name }
    private fun Device.songs(name: String) = playlists.playlists.value.single { it.name == name }.tracks.map { it.videoId }

    @Test
    fun listsCreatedOnEachDeviceEndUpOnBoth() = runBlocking {
        val phone = Device("phone")
        val tablet = Device("tablet")
        phone.playlists.create("Coche", tracks = listOf(t("a")))
        tablet.now = 2_000
        tablet.playlists.create("Cocina", tracks = listOf(t("b")))
        syncBoth(phone, tablet)
        assertEquals(listOf("Coche", "Cocina"), phone.names())
        assertEquals(phone.playlists.playlists.value, tablet.playlists.playlists.value)
    }

    @Test
    fun latestChangeOfAListWins() = runBlocking {
        val phone = Device("phone")
        val tablet = Device("tablet")
        val p = phone.playlists.create("Coche", tracks = listOf(t("a")))
        syncBoth(phone, tablet)

        phone.now = 5_000
        phone.playlists.addTrack(p.id, t("b"))
        tablet.now = 6_000
        tablet.playlists.rename(p.id, "Viajes") // later: wins as a whole
        syncBoth(phone, tablet)
        assertEquals(listOf("Viajes"), phone.names())
        assertEquals(listOf("a"), phone.songs("Viajes"))
        assertEquals(phone.playlists.playlists.value, tablet.playlists.playlists.value)
    }

    @Test
    fun deletionsReachTheOtherDeviceAndLaterChangesBringAListBack() = runBlocking {
        val phone = Device("phone")
        val tablet = Device("tablet")
        val keep = phone.playlists.create("Coche")
        val gone = phone.playlists.create("Vieja")
        syncBoth(phone, tablet)

        phone.now = 5_000
        phone.playlists.delete(gone.id)
        syncBoth(phone, tablet)
        assertEquals(listOf("Coche"), tablet.names())

        // Deleted on the tablet, but the phone changed it afterwards: the change wins.
        tablet.now = 6_000
        tablet.playlists.delete(keep.id)
        phone.now = 7_000
        phone.playlists.addTrack(keep.id, t("x"))
        syncBoth(phone, tablet)
        assertEquals(listOf("Coche"), tablet.names())
        assertEquals(listOf("x"), tablet.songs("Coche"))
    }

    @Test
    fun savedOnThePhoneIsEachDevicesOwnChoice() = runBlocking {
        val phone = Device("phone")
        val tablet = Device("tablet")
        val p = phone.playlists.create("Coche", offline = true)
        val a = Album("OLAK", "Motomami", "ROSALÍA", null)
        phone.albums.add(a, listOf(t("m")), offline = true)
        syncBoth(phone, tablet)
        assertFalse(tablet.playlists.playlists.value.single().offline)
        assertFalse(tablet.albums.albums.value.single().offline)

        tablet.now = 5_000
        tablet.playlists.addTrack(p.id, t("z")) // newer version comes from the tablet…
        syncBoth(phone, tablet)
        assertTrue("…but the phone keeps it saved", phone.playlists.playlists.value.single().offline)
        assertTrue(phone.albums.albums.value.single().offline)
    }

    @Test
    fun albumRemovalSyncsAndReAddingBringsItBack() = runBlocking {
        val phone = Device("phone")
        val tablet = Device("tablet")
        val a = Album("OLAK", "Motomami", "ROSALÍA", null)
        phone.albums.add(a, listOf(t("m")))
        syncBoth(phone, tablet)
        assertEquals(1, tablet.albums.albums.value.size)

        tablet.now = 5_000
        tablet.albums.remove(a.id)
        syncBoth(phone, tablet)
        assertTrue(phone.albums.albums.value.isEmpty())

        phone.now = 6_000
        phone.albums.add(a, listOf(t("m")))
        syncBoth(phone, tablet)
        assertEquals(listOf("Motomami"), tablet.albums.albums.value.map { it.album.title })
    }

    @Test
    fun playsAddUpAndAreNeverCountedTwice() = runBlocking {
        val phone = Device("phone")
        val tablet = Device("tablet")
        phone.plays.countPlay(t("a")); phone.plays.countPlay(t("a"))
        tablet.plays.countPlay(t("a")); tablet.plays.countPlay(t("b"))
        repeat(3) { syncBoth(phone, tablet) } // syncing again and again changes nothing
        val expected = mapOf("a" to 3, "b" to 1)
        assertEquals(expected, phone.plays.history.value.counts)
        assertEquals(expected, tablet.plays.history.value.counts)
        assertEquals("each device keeps only its own", mapOf("a" to 2), phone.plays.mine.value.counts)

        // The others' plays survive a restart (plays-others.json).
        val reopened = PlayCountStore(dir.root.resolve("phone/plays.json"), Dispatchers.Unconfined).also { it.load() }
        assertEquals(expected, reopened.history.value.counts)
    }

    @Test
    fun filesFromBeforeSyncAreRead() {
        val v1 = JSONObject("""{"version":1,"playlists":[{"id":"p","name":"Coche","createdAtMs":42,"offline":true,"tracks":[]}]}""")
        val lib = PlaylistStore.decode(v1)
        assertEquals(42L, lib.playlists.single().updatedAtMs)
        assertTrue(lib.deleted.isEmpty())
        val albums = AlbumStore.decode(JSONObject("""{"version":1,"albums":[]}"""))
        assertTrue(albums.deleted.isEmpty())
    }
}

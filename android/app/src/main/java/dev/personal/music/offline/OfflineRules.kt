package dev.personal.music.offline

import dev.personal.music.data.LibraryAlbum
import dev.personal.music.data.Playlist
import java.util.Locale

/*
 * The rules of "Guardada en el móvil", as pure functions (no Android, unit-tested):
 *
 * - A song must be on the phone if it is in at least one saved playlist or saved Albumteca album
 *   (offline = true).
 * - Anything else that is saved gets deleted. So turning a list or album to streaming, deleting
 *   it, or removing a song from it frees exactly the songs nothing else saved still needs.
 */

/** Where a song is in the download pipeline. */
enum class SaveState { Queued, Downloading, Saved, Failed, Removing }

data class SavedAudio(val state: SaveState, val percent: Float, val bytes: Long)

/** videoIds that must be on the phone. */
fun wantedOnPhone(playlists: List<Playlist>, albums: List<LibraryAlbum> = emptyList()): Set<String> =
    (playlists.filter { it.offline }.flatMap { it.tracks } + albums.filter { it.offline }.flatMap { it.tracks })
        .map { it.videoId }.toSet()

/** Download progress of a set of songs (a playlist or an album), for its status line. */
fun progressOf(tracks: List<dev.personal.music.data.Track>, audio: Map<String, SavedAudio>): OfflineProgress {
    val states = tracks.map { audio[it.videoId] }
    return OfflineProgress(
        total = states.size,
        saved = states.count { it?.state == SaveState.Saved },
        failed = states.count { it?.state == SaveState.Failed },
        bytes = states.sumOf { if (it?.state == SaveState.Saved) it.bytes else 0L },
    )
}

data class SyncPlan(val add: Set<String>, val remove: Set<String>)

/**
 * What to ask the download manager for. Failed downloads are not re-added here (that would retry
 * in a loop on every playlist edit); the "Reintentar" button does that explicitly.
 */
fun planSync(wanted: Set<String>, existing: Map<String, SavedAudio>): SyncPlan = SyncPlan(
    add = wanted.filterNot { it in existing && existing[it]!!.state != SaveState.Removing }.toSet(),
    remove = existing.filter { (id, s) -> id !in wanted && s.state != SaveState.Removing }.keys,
)

/** Download progress of one saved playlist, for its status line. */
data class OfflineProgress(
    val total: Int,
    val saved: Int,
    val failed: Int,
    val bytes: Long,
) {
    val pending get() = total - saved - failed
    val complete get() = saved == total
}

fun progressOf(playlist: Playlist, audio: Map<String, SavedAudio>): OfflineProgress = progressOf(playlist.tracks, audio)

/** "96 MB", "1,2 GB" (Spanish decimal comma). */
fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return when {
        mb < 1 -> "menos de 1 MB"
        mb < 1000 -> "%.0f MB".format(Locale.ROOT, mb)
        else -> "%.1f GB".format(Locale.ROOT, mb / 1000).replace('.', ',')
    }
}

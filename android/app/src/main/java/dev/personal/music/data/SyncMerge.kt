package dev.personal.music.data

/**
 * How device sync combines this device's data with what the other devices sent. Pure functions,
 * so the rules are tested on the JVM (SyncMergeTest).
 *
 * Rules (owner's decisions, 2026-11-13):
 * - Playlists: the latest change of each list wins as a whole ([Playlist.updatedAtMs]). Two
 *   devices changing the same list within one sync round is rare; then one change is lost.
 * - Deletions are remembered (`deleted` maps): a list or album is gone if it was deleted after its
 *   last change, and comes back if it was changed (or re-added) after being deleted.
 * - "Guardada en el móvil" ([Playlist.offline], [LibraryAlbum.offline]) belongs to each device:
 *   kept as it is here, false for what arrives new.
 * - Plays are never merged, only added up: each device sends just its own (see [PlayCountStore]).
 *
 * Merging is order-independent: every device ends up with the same lists and albums.
 */
object SyncMerge {

    fun playlists(local: PlaylistLibrary, remote: List<PlaylistLibrary>): PlaylistLibrary {
        val all = listOf(local) + remote
        val deleted = latest(all.map { it.deleted })
        val offlineHere = local.playlists.associate { it.id to it.offline }
        val kept = all.flatMap { it.playlists }
            .groupBy { it.id }
            .values.map { versions -> versions.maxWith(compareBy({ it.updatedAtMs }, ::tieBreak)) }
            .filter { it.updatedAtMs > (deleted[it.id] ?: Long.MIN_VALUE) }
            .map { it.copy(offline = offlineHere[it.id] ?: false) }
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        return PlaylistLibrary(kept, deleted)
    }

    fun albums(local: AlbumLibrary, remote: List<AlbumLibrary>): AlbumLibrary {
        val all = listOf(local) + remote
        val deleted = latest(all.map { it.deleted })
        val offlineHere = local.albums.associate { it.id to it.offline }
        val kept = all.flatMap { it.albums }
            .groupBy { it.id }
            .values.map { versions -> versions.maxWith(compareBy({ it.addedAtMs }, { it.tracks.size })) }
            .filter { it.addedAtMs > (deleted[it.id] ?: Long.MIN_VALUE) }
            .map { it.copy(offline = offlineHere[it.id] ?: false) }
            .sortedWith(compareBy({ it.addedAtMs }, { it.id }))
        return AlbumLibrary(kept, deleted)
    }

    /** Sum of several devices' play histories (counts added, latest play and details kept). */
    fun addUp(parts: List<PlayHistory>): PlayHistory {
        val nonEmpty = parts.filter { it.songs.isNotEmpty() || it.albums.isNotEmpty() }
        if (nonEmpty.size <= 1) return nonEmpty.firstOrNull() ?: PlayHistory()
        val songs = nonEmpty.flatMap { it.songs.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, plays) ->
                SongPlays(
                    count = plays.sumOf { it.count },
                    lastPlayedMs = plays.maxOf { it.lastPlayedMs },
                    track = plays.filter { it.track != null }.maxByOrNull { it.lastPlayedMs }?.track,
                )
            }
        return PlayHistory(songs, nonEmpty.fold(emptyMap()) { acc, h -> acc + h.albums })
    }

    /** Same timestamp on two devices (very unlikely): any fixed rule will do, so all agree. */
    private fun tieBreak(p: Playlist) = p.name + "\u0000" + p.tracks.joinToString(",") { it.videoId }

    private fun latest(maps: List<Map<String, Long>>): Map<String, Long> =
        maps.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.max() }
}

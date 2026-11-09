package dev.personal.music.data

/** A playable song as shown in the UI and queued in the player. */
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
)

/** A direct googlevideo URL for a track's audio, valid until [expiresAtMs]. */
data class ResolvedAudio(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    /** Total size in bytes, or -1 if unknown. */
    val contentLength: Long,
    val expiresAtMs: Long,
)

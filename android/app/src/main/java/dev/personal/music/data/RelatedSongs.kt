package dev.personal.music.data

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * "Relacionadas": songs related to [seedVideoId], from its YouTube Music radio (`RDAMVM<id>`),
 * handed out [PAGE_SIZE] at a time for the "Más relacionadas" button.
 *
 * The radio comes in pages of ~25 that overlap, and later pages bring video versions of songs
 * already given ("ROSALÍA - DI MI NOMBRE (Official Video)" after "DI MI NOMBRE"; checked
 * 2026-11-07). So songs are de-duplicated by videoId and by [titleKey], songs in [exclude] (the
 * search results on screen) are skipped, and after three pages that add nothing new the radio
 * counts as finished. Blocking: call off the main thread, one call at a time.
 */
class RelatedSongs(private val seedVideoId: String, exclude: List<Track> = emptyList()) {
    private val url = "https://music.youtube.com/watch?v=$seedVideoId&list=RDAMVM$seedVideoId"
    private val seen = (exclude.map { it.videoId } + seedVideoId).toMutableSet()
    private val seenTitles = exclude.map(::titleKey).toMutableSet()
    private val buffer = ArrayDeque<Track>()
    private var started = false
    private var nextPage: Page? = null
    private var exhausted = false

    /** Whether another [next] call can return something. */
    val hasMore: Boolean @Synchronized get() = buffer.isNotEmpty() || !exhausted

    @Synchronized
    fun next(count: Int = PAGE_SIZE): List<Track> {
        var fruitless = 0
        while (buffer.size < count && !exhausted) {
            val items: List<InfoItem>
            if (!started) {
                started = true
                val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
                items = info.relatedItems
                nextPage = info.nextPage
            } else {
                val page = nextPage ?: break
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
                items = more.items
                nextPage = more.nextPage
            }
            val fresh = items.filterIsInstance<StreamInfoItem>()
                .mapNotNull { with(YouTubeMusic) { it.toTrack() } }
                .filter { seen.add(it.videoId) && seenTitles.add(titleKey(it)) }
            buffer.addAll(fresh)
            fruitless = if (fresh.isEmpty()) fruitless + 1 else 0
            if (nextPage == null || fruitless >= 3) exhausted = true
        }
        if (nextPage == null && started) exhausted = true
        return List(minOf(count, buffer.size)) { buffer.removeFirst() }
    }

    companion object {
        const val PAGE_SIZE = 10

        private val VIDEO_TAG = Regex("""[(\[][^)\]]*(video|lyric|letra|audio|visuali[sz]er|en vivo|live)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

        /** "ROSALÍA - DI MI NOMBRE (Official Video)" and "DI MI NOMBRE" by ROSALÍA give the same key. */
        fun titleKey(t: Track): String {
            var title = VIDEO_TAG.replace(t.title, "")
            val prefix = Regex("^(.*?)\\s+-\\s+").find(title)
            if (prefix != null && t.artist.isNotBlank() && prefix.groupValues[1].contains(t.artist, ignoreCase = true)) {
                title = title.substring(prefix.range.last + 1)
            }
            return title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + "|" + t.artist.lowercase()
        }
    }
}

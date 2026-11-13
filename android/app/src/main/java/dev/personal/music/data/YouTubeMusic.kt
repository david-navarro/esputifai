package dev.personal.music.data

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music access through NewPipeExtractor. All calls block: run them off the main thread.
 * Pure JVM code (no Android APIs) so it can be smoke-tested from a plain unit test.
 */
object YouTubeMusic {
    private val cache = ConcurrentHashMap<String, ResolvedAudio>()

    fun init(client: OkHttpClient) {
        NewPipe.init(OkHttpDownloader(client), Localization("es", "ES"), ContentCountry("ES"))
    }

    fun search(query: String): List<Track> =
        searchPages(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, pages = 1)
            .filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }

    fun searchAlbums(query: String): List<Album> =
        searchPages(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS, pages = 1)
            .filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toAlbum() }

    /** Public playlists (YouTube Music's "Community playlists" and featured ones). */
    fun searchPlaylists(query: String): List<OnlinePlaylist> =
        searchPages(query, YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS, pages = 1)
            .filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toOnlinePlaylist() }

    fun searchArtists(query: String): List<Artist> =
        searchPages(query, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS, pages = 1)
            .filterIsInstance<ChannelInfoItem>().mapNotNull { it.toArtist() }

    /**
     * Autocomplete for the search box. YouTube's suggestions are for all of YouTube, so terms
     * that are never music (reactions, karaoke, tutorials) are dropped.
     */
    fun suggestions(query: String): List<String> =
        ServiceList.YouTube.suggestionExtractor.suggestionList(query)
            .filterNot { s -> NOT_MUSIC.any { it in s.lowercase() } }
            .take(8)

    private val NOT_MUSIC = listOf("reaction", "reaccion", "reacción", "react ", "karaoke", "tutorial", "how to")

    /**
     * The album a song belongs to ("Ir al álbum"). NewPipe's song results carry no album, so this
     * searches albums for "artist title" and opens the first few: the first one containing the
     * song wins; failing that (remasters and reissues use other ids), the first one with a song
     * of the same title. Null if none matches. Takes about a second (one request per album).
     */
    fun findAlbum(track: Track): Album? {
        val candidates = searchAlbums("${track.artist} ${track.title}").take(ALBUM_CANDIDATES)
        val tracks = mutableMapOf<Album, List<Track>>()
        for (a in candidates) {
            val list = runCatching { albumTracks(a) }.getOrDefault(emptyList()).also { tracks[a] = it }
            if (list.any { it.videoId == track.videoId }) return a
        }
        val wanted = looseTitle(track.title)
        return candidates.firstOrNull { a -> tracks[a].orEmpty().any { looseTitle(it.title) == wanted } }
    }

    private const val ALBUM_CANDIDATES = 4

    /** "Let It Be (Remastered 2009)" and "Let It Be - Remastered" compare equal to "let it be". */
    internal fun looseTitle(title: String): String =
        title.replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""\s+-\s+.*$"""), " ")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** All songs of an album, in album order, each with the album cover. */
    fun albumTracks(album: Album): List<Track> =
        playlistItems(album.url, limit = Int.MAX_VALUE)
            .map { if (album.thumbnailUrl != null) it.copy(thumbnailUrl = album.thumbnailUrl) else it }

    /**
     * Songs of a public playlist, in its order, each with its own artwork. Capped at
     * [PLAYLIST_MAX_SONGS]: some lists have thousands of songs, and each page of 100 is a request.
     */
    fun playlistTracks(playlist: OnlinePlaylist): List<Track> =
        playlistItems(playlist.url, limit = PLAYLIST_MAX_SONGS).distinctBy { it.videoId }

    const val PLAYLIST_MAX_SONGS = 200

    /** The songs of any YouTube playlist URL (albums are playlists too), up to [limit]. */
    private fun playlistItems(url: String, limit: Int): List<Track> {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = info.relatedItems.toMutableList<InfoItem>()
        var page = info.nextPage
        while (page != null && items.size < limit) { // albums usually fit in one page; long ones don't
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            items += more.items
            page = more.nextPage
        }
        return items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }.take(limit)
    }

    /**
     * An artist's songs and albums. YouTube's artist channels expose no usable tabs through
     * NewPipe (checked 2026-11-07: "<name> - Topic" with zero tabs), so this searches by the
     * artist's name and keeps only results whose uploader is that artist's channel, which also
     * drops namesakes. Two result pages each: ~25 songs and ~10 albums for a big artist.
     */
    fun artistSongs(artist: Artist): List<Track> =
        searchPages(artist.name, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, pages = 2)
            .filterIsInstance<StreamInfoItem>()
            .filter { it.uploaderUrl.orEmpty().endsWith(artist.channelId) }
            .mapNotNull { it.toTrack() }.distinctBy { it.videoId }

    fun artistAlbums(artist: Artist): List<Album> =
        searchPages(artist.name, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS, pages = 2)
            .filterIsInstance<PlaylistInfoItem>()
            .filter { it.uploaderUrl.orEmpty().endsWith(artist.channelId) }
            .mapNotNull { it.toAlbum() }.distinctBy { it.id }

    private fun searchPages(query: String, filter: String, pages: Int): List<InfoItem> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        val items = extractor.initialPage.items.toMutableList<InfoItem>()
        var next: Page? = extractor.initialPage.nextPage
        repeat(pages - 1) {
            val page = next ?: return items
            val more = extractor.getPage(page)
            items += more.items
            next = more.nextPage
        }
        return items
    }

    /** Direct audio URL for a track, cached until shortly before it expires. */
    fun resolveAudio(videoId: String): ResolvedAudio {
        cache[videoId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val stream = pickAudioStream(info.audioStreams)
            ?: error("No playable audio stream for $videoId")
        val url = stream.content
        val resolved = ResolvedAudio(
            videoId = videoId,
            url = url,
            mimeType = stream.format?.mimeType ?: "audio/mp4",
            bitrate = stream.averageBitrate,
            contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 }
                ?: queryParam(url, "clen")?.toLongOrNull() ?: -1L,
            expiresAtMs = expiryOf(url),
        )
        cache[videoId] = resolved
        return resolved
    }

    /** Forget a cached URL (e.g. after googlevideo answered 403). */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /** Highest bitrate original-language progressive stream; Opus wins ties (better quality per bit). */
    private fun pickAudioStream(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .maxWithOrNull(compareBy<AudioStream>({ it.averageBitrate }, { it.format == MediaFormat.WEBMA_OPUS }))

    internal fun StreamInfoItem.toTrack(): Track? {
        val id = runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(url) }.getOrNull()
            ?: return null
        val thumb = thumbnails.maxByOrNull { it.width * it.height }?.url
        return Track(
            videoId = id,
            title = name,
            artist = uploaderName.orEmpty(),
            durationSec = duration,
            thumbnailUrl = thumb?.let(::upscaleThumbnail),
        )
    }

    private fun PlaylistInfoItem.toAlbum(): Album? {
        val id = Regex("[?&]list=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        return Album(
            id = id,
            title = name,
            artist = uploaderName.orEmpty(),
            thumbnailUrl = thumbnails.maxByOrNull { it.width * it.height }?.url?.let(::upscaleThumbnail),
        )
    }

    private fun PlaylistInfoItem.toOnlinePlaylist(): OnlinePlaylist? {
        val id = Regex("[?&]list=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        return OnlinePlaylist(
            id = id,
            title = name,
            author = uploaderName.orEmpty(),
            thumbnailUrl = thumbnails.maxByOrNull { it.width * it.height }?.url?.let(::upscaleThumbnail),
            songCount = streamCount,
        )
    }

    private fun ChannelInfoItem.toArtist(): Artist? {
        val id = url.substringAfter("/channel/", "").substringBefore('?').ifEmpty { return null }
        return Artist(id, name, thumbnails.maxByOrNull { it.width * it.height }?.url?.let(::upscaleThumbnail))
    }

    /**
     * YouTube Music thumbnails come at 60-120px; googleusercontent URLs encode the size
     * (`=w60-h60-…` for covers, `=s88-…` for artist avatars), so ask for more.
     */
    private fun upscaleThumbnail(url: String): String = when {
        "googleusercontent.com" !in url && "ggpht.com" !in url -> url
        Regex("=w\\d+-h\\d+").containsMatchIn(url) -> url.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
        Regex("=s\\d+").containsMatchIn(url) -> url.replace(Regex("=s\\d+.*$"), "=s544")
        else -> url
    }

    private fun queryParam(url: String, name: String): String? =
        Regex("[?&]$name=([^&]+)").find(url)?.groupValues?.get(1)

    /** googlevideo URLs carry `expire=<unix seconds>`; keep a 10 min safety margin. */
    private fun expiryOf(url: String): Long {
        val expire = queryParam(url, "expire")?.toLongOrNull() ?: return System.currentTimeMillis() + 3_600_000
        return expire * 1000 - 600_000
    }
}

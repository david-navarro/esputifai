package dev.personal.music.data

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Builds googlevideo requests the way YouTube's own players (and NewPipe) do:
 * POST with a tiny body, the byte range as a `range=` URL parameter and a request counter `rn=`.
 * Plain GET + Range header gets throttled or rejected for some clients.
 */
object StreamRequests {
    /** Bytes per request. Small enough to avoid throttling, big enough to keep request count low. */
    const val CHUNK_SIZE = 2L * 1024 * 1024

    private val POST_BODY = byteArrayOf(0x78, 0)
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    fun build(url: String, start: Long, endInclusive: Long, requestNumber: Int): Request {
        val fullUrl = "$url&range=$start-$endInclusive&rn=$requestNumber"
        val builder = Request.Builder()
            .url(fullUrl)
            .post(POST_BODY.toRequestBody())
            .header("Accept-Encoding", "identity") // byte counts must match the range
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", YOUTUBE_ORIGIN)
                .header("Referer", YOUTUBE_ORIGIN)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            OkHttpDownloader.USER_AGENT
        }
        return builder.header("User-Agent", userAgent).build()
    }
}

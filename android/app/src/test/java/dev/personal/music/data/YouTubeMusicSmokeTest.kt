package dev.personal.music.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Smoke test against the real YouTube (needs network). Verifies the whole data path the app uses:
 * search -> resolve audio URL -> fetch byte ranges with the same request shape as the player.
 * Run: scripts/build-apk.sh testDebugUnitTest
 */
class YouTubeMusicSmokeTest {
    companion object {
        private val client = OkHttpClient()

        @JvmStatic
        @BeforeClass
        fun setUp() = YouTubeMusic.init(client)
    }

    @Test
    fun searchResolveAndFetchRanges() {
        val tracks = YouTubeMusic.search("radiohead creep")
        tracks.take(3).forEach { println("search: $it") }
        assertTrue("search returned nothing", tracks.isNotEmpty())

        val audio = YouTubeMusic.resolveAudio(tracks.first().videoId)
        println("resolved: mime=${audio.mimeType} bitrate=${audio.bitrate} size=${audio.contentLength} " +
            "host=${audio.url.substringAfter("//").substringBefore("/")} client=${Regex("[?&]c=([^&]+)").find(audio.url)?.groupValues?.get(1)}")
        assertTrue("unknown content length", audio.contentLength > 0)

        // Start, a chunk boundary in the middle, and the tail: what playback and seeking need.
        val ranges = listOf(
            0L to 1023L,
            StreamRequests.CHUNK_SIZE - 512 to StreamRequests.CHUNK_SIZE + 511,
            audio.contentLength - 1000 to audio.contentLength - 1,
        )
        ranges.forEachIndexed { i, (start, end) ->
            client.newCall(StreamRequests.build(audio.url, start, end, i)).execute().use { r ->
                val bytes = r.body.bytes()
                println("range $start-$end -> HTTP ${r.code}, ${bytes.size} bytes")
                assertTrue("HTTP ${r.code} for range $start-$end", r.isSuccessful)
                assertEquals(end - start + 1, bytes.size.toLong())
                if (start == 0L) {
                    val webm = bytes.take(4) == listOf(0x1A, 0x45, 0xDF, 0xA3).map { it.toByte() }
                    val mp4 = String(bytes, 4, 4) == "ftyp"
                    assertTrue("not an audio container", webm || mp4)
                }
            }
        }
    }
}

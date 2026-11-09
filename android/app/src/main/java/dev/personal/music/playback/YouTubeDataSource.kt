package dev.personal.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import dev.personal.music.data.ResolvedAudio
import dev.personal.music.data.StreamRequests
import dev.personal.music.data.YouTubeMusic
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * ExoPlayer DataSource for `ytm://track/<videoId>` URIs.
 *
 * Resolves the videoId to a googlevideo URL lazily (on ExoPlayer's loader thread, so queued
 * tracks cost nothing until they play) and downloads it in [StreamRequests.CHUNK_SIZE] pieces,
 * opening the next piece transparently. A 403 on open means the cached URL went stale: it is
 * re-resolved once.
 */
@UnstableApi
class YouTubeDataSource(private val client: OkHttpClient) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var audio: ResolvedAudio? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var position = 0L // absolute offset of the next byte to read
    private var end = 0L // absolute last byte (inclusive) to deliver
    private var chunkEnd = 0L
    private var requestNumber = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val videoId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Not a track URI: ${dataSpec.uri}")
        transferInitializing(dataSpec)

        var resolved = resolve(videoId)
        val total = resolved.contentLength
        if (total <= 0) throw IOException("Unknown content length for $videoId")
        if (dataSpec.position >= total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - 1
        else minOf(total - 1, position + dataSpec.length - 1)

        try {
            openChunk(resolved)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403) throw e
            YouTubeMusic.invalidate(videoId)
            resolved = resolve(videoId)
            openChunk(resolved)
        }
        audio = resolved
        opened = true
        transferStarted(dataSpec)
        return end - position + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position > end) return C.RESULT_END_OF_INPUT
        if (position > chunkEnd) {
            closeChunk()
            openChunk(audio!!)
        }
        val toRead = minOf(length.toLong(), chunkEnd - position + 1).toInt()
        val n = input!!.read(buffer, offset, toRead)
        if (n == -1) {
            // Server closed the chunk early: reopen from where we are.
            closeChunk()
            openChunk(audio!!)
            return read(buffer, offset, length)
        }
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeChunk()
        audio = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun resolve(videoId: String): ResolvedAudio =
        try {
            YouTubeMusic.resolveAudio(videoId)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // ExtractionException etc.: surface as an IO error so ExoPlayer reports it normally.
            throw IOException("Could not resolve audio for $videoId: ${e.message}", e)
        }

    private fun openChunk(a: ResolvedAudio) {
        chunkEnd = minOf(end, position + StreamRequests.CHUNK_SIZE - 1)
        val r = client.newCall(StreamRequests.build(a.url, position, chunkEnd, requestNumber++)).execute()
        if (!r.isSuccessful) {
            val code = r.code
            val message = r.message
            r.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code, message, null, emptyMap(), DataSpec(Uri.parse(a.url)), ByteArray(0),
            )
        }
        response = r
        input = r.body.byteStream()
    }

    private fun closeChunk() {
        try {
            input?.close()
        } catch (_: IOException) {
        }
        response?.close()
        input = null
        response = null
    }

    class Factory(private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = YouTubeDataSource(client)
    }

    companion object {
        const val SCHEME = "ytm"
        fun uriFor(videoId: String): Uri = Uri.parse("$SCHEME://track/$videoId")
    }
}

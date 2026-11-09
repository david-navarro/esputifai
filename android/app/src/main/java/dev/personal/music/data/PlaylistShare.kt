package dev.personal.music.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A playlist received from someone else, before it is imported. */
data class SharedPlaylist(val name: String, val tracks: List<Track>)

/**
 * Playlists as links that can be sent by WhatsApp, with no server.
 *
 * The whole list travels inside the link, after the `#`:
 * `https://david-navarro.github.io/esputifai/l/#<data>`. Browsers never send the part after `#`
 * to the server, so GitHub Pages only serves a static page (`docs/l/index.html`) that shows the
 * list and opens the app with `esputifai://import?d=<data>`. WhatsApp only turns `https://` into
 * links, hence the page in between.
 *
 * `<data>` = base64url (no padding) of zlib-deflated JSON
 * `{"v":1,"n":"<name>","t":[["<videoId>","<title>","<artist>",<durationSec>],…]}`. Thumbnails are
 * left out to keep links short (~25 characters per song); the importer uses YouTube's.
 */
object PlaylistShare {
    const val PAGE = "https://david-navarro.github.io/esputifai/l/"
    private const val VERSION = 1

    fun link(name: String, tracks: List<Track>): String = PAGE + "#" + encode(name, tracks)

    fun encode(name: String, tracks: List<Track>): String {
        val json = JSONObject()
            .put("v", VERSION)
            .put("n", name)
            .put("t", JSONArray(tracks.map { JSONArray(listOf(it.videoId, it.title, it.artist, it.durationSec)) }))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(json.toString().toByteArray()))
    }

    /**
     * Reads a shared list from a link: the web page (`…/l/#<data>`), the app link
     * (`esputifai://import?d=<data>`) or the bare data. Null if it isn't a valid list.
     */
    fun parse(link: String): SharedPlaylist? = runCatching {
        val data = when {
            "#" in link -> link.substringAfter('#')
            "d=" in link -> link.substringAfter("d=").substringBefore('&')
            else -> link
        }.trim()
        val json = JSONObject(inflate(Base64.getUrlDecoder().decode(data)).decodeToString())
        if (json.getInt("v") > VERSION) return null
        val t = json.getJSONArray("t")
        val tracks = (0 until t.length()).map { i ->
            val a = t.getJSONArray(i)
            val id = a.getString(0)
            Track(id, a.getString(1), a.getString(2), a.optLong(3), thumbnailOf(id))
        }
        SharedPlaylist(json.getString("n"), tracks)
    }.getOrNull()

    /** YouTube's own picture of a song; for music it is the cover with side bars, cropped away in the UI. */
    fun thumbnailOf(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(bytes); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val i = Inflater().apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) error("truncated")
            out.write(buf, 0, n)
            check(out.size() < 2_000_000) { "too big" }
        }
        i.end()
        return out.toByteArray()
    }
}

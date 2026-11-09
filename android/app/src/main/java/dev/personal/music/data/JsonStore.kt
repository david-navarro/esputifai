package dev.personal.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A value of type [T] persisted as one JSON file, shared by the playlist, albumteca and play-count
 * stores.
 *
 * Why JSON files and not Room: small data, always read whole, no queries. Files keep the build
 * free of extra plugins and are easy to inspect (`adb shell run-as dev.personal.music ls files`).
 *
 * Every change is written to disk (temp file + rename, so a crash never leaves half a file) before
 * [state] is updated, so what the UI shows is always what is saved. Changes are serialized with a
 * mutex; the file is read once, lazily, on first use. A file that can't be read is renamed to
 * `<name>.unreadable-<ms>` and never overwritten.
 */
class JsonStore<T>(
    private val file: File,
    private val empty: T,
    private val encode: (T) -> JSONObject,
    private val decode: (JSONObject) -> T,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val _state = MutableStateFlow(empty)
    val state: StateFlow<T> = _state.asStateFlow()

    suspend fun load() = mutex.withLock { ensureLoaded() }

    /** Applies [change] to the current value; saves and publishes it if it changed. */
    suspend fun <R> edit(change: (T) -> Pair<T, R>): R = mutex.withLock {
        ensureLoaded()
        val (next, result) = change(_state.value)
        if (next != _state.value) {
            withContext(io) { write(next) }
            _state.value = next
        }
        result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _state.value = withContext(io) { read() }
        loaded = true
    }

    private fun read(): T {
        if (!file.exists()) return empty
        return try {
            decode(JSONObject(file.readText()))
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "${file.name}.unreadable-${clock()}"))
            empty
        }
    }

    private fun write(value: T) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encode(value).toString(2))
        if (!tmp.renameTo(file)) throw IOException("Could not replace $file")
    }
}

/** Track <-> JSON, shared by playlists and albums. */
fun Track.toJson(): JSONObject = JSONObject()
    .put("videoId", videoId)
    .put("title", title)
    .put("artist", artist)
    .put("durationSec", durationSec)
    .put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)

fun trackFromJson(t: JSONObject) = Track(
    videoId = t.getString("videoId"),
    title = t.optString("title"),
    artist = t.optString("artist"),
    durationSec = t.optLong("durationSec"),
    thumbnailUrl = if (t.isNull("thumbnailUrl")) null else t.getString("thumbnailUrl"),
)

fun <E> org.json.JSONArray.mapObjects(f: (JSONObject) -> E): List<E> = (0 until length()).map { f(getJSONObject(it)) }

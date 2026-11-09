package dev.personal.music.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.personal.music.MusicApp
import dev.personal.music.data.Album
import dev.personal.music.data.AlbumOrder
import dev.personal.music.data.Artist
import dev.personal.music.data.LibraryAlbum
import dev.personal.music.data.Playlist
import dev.personal.music.data.PlaylistShare
import dev.personal.music.data.SharedPlaylist
import dev.personal.music.data.Ranked
import dev.personal.music.data.RelatedSongs
import dev.personal.music.data.Top
import dev.personal.music.data.TopArtist
import dev.personal.music.data.Track
import dev.personal.music.data.YouTubeMusic
import dev.personal.music.offline.SavedAudio
import dev.personal.music.playback.PlaybackService
import dev.personal.music.playback.YouTubeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchKind(val label: String) {
    Songs("Canciones"),
    Albums("Álbumes"),
    Artists("Artistas"),
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Songs(val tracks: List<Track>, val related: RelatedState) : SearchState
    data class Albums(val albums: List<Album>) : SearchState
    data class Artists(val artists: List<Artist>) : SearchState
    data class Error(val message: String) : SearchState
}

/** The "Relacionadas" section under song results; it loads after the results are shown. */
data class RelatedState(
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val hasMore: Boolean = true,
    val error: Boolean = false,
)

/** The "Top" tab: the user's most played songs, artists and albums. */
data class TopLists(
    val songs: List<Ranked<Track>> = emptyList(),
    val artists: List<Ranked<TopArtist>> = emptyList(),
    val albums: List<Ranked<Album>> = emptyList(),
)

enum class TopKind(val label: String) {
    Songs("Canciones"),
    Artists("Artistas"),
    Albums("Álbumes"),
}

/** What the artist page shows. */
data class ArtistPage(val songs: List<Track>, val albums: List<Album>)

data class NowPlaying(
    val videoId: String,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val error: String?,
) {
    /** The playing item as a [Track], e.g. to add it to a playlist. */
    fun toTrack() = Track(videoId, title, artist, durationMs / 1000, artworkUri?.toString())
}

/** A short notice for the snackbar, optionally with an "undo" action. */
data class UiMessage(val text: String, val undo: (() -> Unit)? = null)

/**
 * Search, playlists (with the "active" one that "+" adds to), the Albumteca, and a
 * MediaController connected to [PlaybackService].
 */
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val store = (app as MusicApp).playlists
    private val offline = (app as MusicApp).offline
    private val albumStore = (app as MusicApp).albums
    private val playCounts = (app as MusicApp).plays
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Download state of saved songs, by videoId. */
    val savedAudio: StateFlow<Map<String, SavedAudio>> = offline.audio

    /** Whether the phone has internet right now. */
    val online: StateFlow<Boolean> = offline.online

    fun freeBytes(): Long = offline.freeBytes()

    /** All playlists, in creation order. */
    val playlists: StateFlow<List<Playlist>> = store.playlists

    private val activeListId = MutableStateFlow(prefs.getString(KEY_ACTIVE_LIST, null))

    /** The list "+" adds to with one tap; null until chosen (or after it is deleted). */
    val activeList: StateFlow<Playlist?> = combine(store.playlists, activeListId) { lists, id ->
        lists.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _searchKind = MutableStateFlow(
        runCatching { SearchKind.valueOf(prefs.getString(KEY_SEARCH_KIND, null)!!) }.getOrDefault(SearchKind.Songs),
    )
    val searchKind: StateFlow<SearchKind> = _searchKind.asStateFlow()

    /** The Albumteca, unsorted (the screen sorts it with [albumOrder]). */
    val albums: StateFlow<List<LibraryAlbum>> = albumStore.albums

    /** Times each song has been played, by videoId. */
    val plays: StateFlow<Map<String, Int>> = playCounts.history.map { it.counts }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Recomputed off the main thread whenever something is played or the library changes. */
    val top: StateFlow<TopLists> = combine(playCounts.history, store.playlists, albumStore.albums) { h, lists, albums ->
        val known = lists.flatMap { it.tracks } + albums.flatMap { it.tracks }
        TopLists(
            songs = Top.songs(h, known),
            artists = Top.artists(Top.songs(h, known, limit = Int.MAX_VALUE)),
            albums = Top.albums(h, albums),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TopLists())

    private val _topKind = MutableStateFlow(
        runCatching { TopKind.valueOf(prefs.getString(KEY_TOP_KIND, null)!!) }.getOrDefault(TopKind.Songs),
    )
    val topKind: StateFlow<TopKind> = _topKind.asStateFlow()

    fun setTopKind(kind: TopKind) {
        _topKind.value = kind
        prefs.edit().putString(KEY_TOP_KIND, kind.name).apply()
    }

    private val typed = MutableStateFlow("")

    /** Autocomplete for what is being typed; empty when there is nothing to suggest. */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<List<String>> = typed
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(250) // one request per pause in typing, not per key
        .mapLatest { q ->
            if (q.length < 2) emptyList()
            else runCatching { withContext(Dispatchers.IO) { YouTubeMusic.suggestions(q) } }.getOrDefault(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The search box changed: fetch suggestions for it. */
    fun onQueryTyped(query: String) {
        typed.value = query
    }

    private val _albumOrder = MutableStateFlow(
        runCatching { AlbumOrder.valueOf(prefs.getString(KEY_ALBUM_ORDER, null)!!) }.getOrDefault(AlbumOrder.MostPlayed),
    )
    val albumOrder: StateFlow<AlbumOrder> = _albumOrder.asStateFlow()

    private var related: RelatedSongs? = null
    private var relatedJob: Job? = null
    private var lastQuery = ""

    // Album and artist pages fetched this session; YouTube is slow and they rarely change.
    private val albumTrackCache = mutableMapOf<String, List<Track>>()
    private val artistCache = mutableMapOf<String, ArtistPage>()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        viewModelScope.launch { store.load() }
        viewModelScope.launch { albumStore.load() }
        viewModelScope.launch { playCounts.load() }

        controllerFuture.addListener({
            controller = controllerFuture.get().also { it.addListener(listener) }
            refresh()
        }, ContextCompat.getMainExecutor(app))

        // Progress bar ticker; listener events don't fire for position changes.
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
    }

    // --- Search ---

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        typed.value = "" // hide the suggestions
        lastQuery = q
        val kind = _searchKind.value
        searchJob?.cancel()
        relatedJob?.cancel()
        related = null
        searchJob = viewModelScope.launch {
            _search.value = SearchState.Loading
            _search.value = try {
                withContext(Dispatchers.IO) {
                    when (kind) {
                        SearchKind.Songs -> SearchState.Songs(YouTubeMusic.search(q), RelatedState())
                        SearchKind.Albums -> SearchState.Albums(YouTubeMusic.searchAlbums(q))
                        SearchKind.Artists -> SearchState.Artists(YouTubeMusic.searchArtists(q))
                    }
                }
            } catch (e: Exception) {
                SearchState.Error(e.message ?: e.javaClass.simpleName)
            }
            // Results are on screen; now the related songs of the first one, without blocking them.
            (_search.value as? SearchState.Songs)?.tracks?.let { results ->
                if (results.isEmpty()) {
                    updateRelated { RelatedState(loading = false, hasMore = false) }
                } else {
                    related = RelatedSongs(results.first().videoId, exclude = results)
                    moreRelated()
                }
            }
        }
    }

    /** Switches between songs, albums and artists, repeating the last search. */
    fun setSearchKind(kind: SearchKind) {
        if (kind == _searchKind.value) return
        _searchKind.value = kind
        prefs.edit().putString(KEY_SEARCH_KIND, kind.name).apply()
        if (lastQuery.isNotEmpty()) search(lastQuery) else _search.value = SearchState.Idle
    }

    /** Next 10 related songs ("Más relacionadas"). */
    fun moreRelated() {
        val source = related ?: return
        if (relatedJob?.isActive == true) return
        relatedJob = viewModelScope.launch {
            updateRelated { it.copy(loading = true, error = false) }
            try {
                val page = withContext(Dispatchers.IO) { source.next() }
                val hasMore = withContext(Dispatchers.IO) { source.hasMore }
                updateRelated { it.copy(tracks = it.tracks + page, loading = false, hasMore = hasMore) }
            } catch (e: Exception) {
                updateRelated { it.copy(loading = false, error = true) }
            }
        }
    }

    private fun updateRelated(change: (RelatedState) -> RelatedState) = _search.update {
        if (it is SearchState.Songs) it.copy(related = change(it.related)) else it
    }

    // --- Albums and artists ---

    /** The album's songs: from the Albumteca if it is there, else from YouTube (cached). */
    suspend fun albumTracks(album: Album): List<Track> {
        albums.value.find { it.id == album.id }?.let { return it.tracks }
        albumTrackCache[album.id]?.let { return it }
        return withContext(Dispatchers.IO) { YouTubeMusic.albumTracks(album) }
            .also { albumTrackCache[album.id] = it }
    }

    suspend fun artistPage(artist: Artist): ArtistPage {
        artistCache[artist.channelId]?.let { return it }
        return withContext(Dispatchers.IO) {
            ArtistPage(YouTubeMusic.artistSongs(artist), YouTubeMusic.artistAlbums(artist))
        }.also { artistCache[artist.channelId] = it }
    }

    /**
     * Finds the artist a Top row refers to: the Top only knows the name, the artist page needs the
     * channel. Prefers an exact name match over YouTube's first result.
     */
    suspend fun findArtist(name: String): Artist? = withContext(Dispatchers.IO) {
        val found = YouTubeMusic.searchArtists(name)
        found.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: found.firstOrNull()
    }

    private val albumOfCache = mutableMapOf<String, Album?>()

    /**
     * The album of [track] for "Ir al álbum": the Albumteca and albums already played know it at
     * once; otherwise YouTube is searched (about a second). Null if not found.
     */
    suspend fun albumOf(track: Track): Album? {
        albums.value.find { a -> a.tracks.any { it.videoId == track.videoId } }?.let { return it.album }
        playCounts.history.value.albums.values.find { track.videoId in it.trackIds }?.let { return it.album }
        if (track.videoId in albumOfCache) return albumOfCache[track.videoId]
        return withContext(Dispatchers.IO) { YouTubeMusic.findAlbum(track) }
            .also { albumOfCache[track.videoId] = it }
    }

    /** Plays from an album page; the album is remembered so it can appear in the Top. */
    fun playAlbum(album: Album, tracks: List<Track>, index: Int) {
        play(tracks, index)
        viewModelScope.launch {
            val all = runCatching { albumTracks(album) }.getOrDefault(tracks)
            playCounts.rememberAlbum(album, all)
        }
    }

    fun addToAlbumteca(album: Album) = viewModelScope.launch {
        if (albums.value.any { it.id == album.id }) {
            say("«${album.title}» ya está en la Albumteca")
            return@launch
        }
        try {
            val tracks = albumTracks(album)
            if (albumStore.add(album, tracks)) say("«${album.title}» añadido a la Albumteca")
        } catch (e: Exception) {
            say("No se ha podido añadir: ${e.message ?: "sin conexión"}")
        }
    }

    fun removeFromAlbumteca(album: LibraryAlbum) = viewModelScope.launch {
        albumStore.remove(album.id)
        say("«${album.album.title}» quitado de la Albumteca")
    }

    /** "Guardar en el móvil" / "Pasar a streaming" for an Albumteca album. */
    fun setAlbumOffline(album: LibraryAlbum, offline: Boolean) = viewModelScope.launch {
        albumStore.setOffline(album.id, offline)
        say(if (offline) "Guardando «${album.album.title}» en el móvil" else "«${album.album.title}» pasa a streaming")
    }

    fun retrySaving(album: LibraryAlbum) = offline.retry(album.tracks.map { it.videoId })

    fun setAlbumOrder(order: AlbumOrder) {
        _albumOrder.value = order
        prefs.edit().putString(KEY_ALBUM_ORDER, order.name).apply()
    }

    // --- Active list ---

    fun setActiveList(playlist: Playlist?) {
        activeListId.value = playlist?.id
        prefs.edit().putString(KEY_ACTIVE_LIST, playlist?.id).apply()
    }

    /**
     * The one-tap "+": adds [track] to the active list. Returns false when there is no active
     * list yet, so the screen opens the panel to choose one.
     */
    fun addToActiveList(track: Track): Boolean {
        val list = activeList.value ?: return false
        viewModelScope.launch {
            if (store.addTrack(list.id, track)) {
                say("Añadida a «${list.name}»") {
                    viewModelScope.launch { store.removeTrack(list.id, track.videoId) }
                }
            } else {
                say("Ya está en «${list.name}»")
            }
        }
        return true
    }

    /**
     * Play [tracks] as the queue, starting at [index]. If that song is the one already playing it
     * is not restarted (owner's rule): it keeps going and only the queue around it changes.
     */
    fun play(tracks: List<Track>, index: Int) {
        val c = controller ?: return
        val target = tracks.getOrNull(index) ?: return
        if (c.currentMediaItem?.mediaId == target.videoId && c.playerError == null) {
            val at = c.currentMediaItemIndex
            // Remove + add rather than replace: added items go through the session's
            // onAddMediaItems, which restores their URIs.
            c.removeMediaItems(at + 1, c.mediaItemCount)
            c.addMediaItems(tracks.drop(index + 1).map { it.toMediaItem() })
            c.removeMediaItems(0, at)
            c.addMediaItems(0, tracks.take(index).map { it.toMediaItem() })
            if (!c.isPlaying) c.play()
            return
        }
        c.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    /** Play [tracks] in random order. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isNotEmpty()) play(tracks.shuffled(), 0)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playerError != null) c.prepare() // retry after an error
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    // --- Sharing playlists ---

    private val _incoming = MutableStateFlow<SharedPlaylist?>(null)

    /** A list someone shared, waiting for "¿Importar?"; null when there is none. */
    val incoming: StateFlow<SharedPlaylist?> = _incoming.asStateFlow()

    /** The app was opened from a shared-list link. */
    fun receiveShared(link: String) {
        val shared = PlaylistShare.parse(link)
        if (shared == null || shared.tracks.isEmpty()) say("Ese enlace no contiene una lista válida")
        else _incoming.value = shared
    }

    fun dismissIncoming() {
        _incoming.value = null
    }

    /** Imports the pending shared list as a new streaming list. */
    fun importIncoming() = viewModelScope.launch {
        val shared = _incoming.value ?: return@launch
        _incoming.value = null
        val taken = store.playlists.value.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) shared.name else "${shared.name} ($it)" }
            .first { it !in taken }
        store.create(name, offline = false, tracks = shared.tracks)
        say("Lista «$name» importada")
    }

    /** The text to send for [playlist]: a sentence and its link. */
    fun shareText(playlist: Playlist): String {
        val songs = if (playlist.tracks.size == 1) "1 canción" else "${playlist.tracks.size} canciones"
        return "Te paso mi lista «${playlist.name}» de Esputifai ($songs): " +
            PlaylistShare.link(playlist.name, playlist.tracks)
    }

    // --- Playlists ---

    /** Creates a playlist; if [firstTrack] is given it goes straight in. */
    fun createPlaylist(
        name: String,
        offline: Boolean,
        firstTrack: Track? = null,
        makeActive: Boolean = false,
    ) = viewModelScope.launch {
        val p = store.create(name, offline)
        if (makeActive || activeList.value == null) setActiveList(p) // the first list becomes active too
        if (firstTrack != null) {
            store.addTrack(p.id, firstTrack)
            say("Añadida a «${p.name}»")
        } else {
            say("Lista «${p.name}» creada")
        }
    }

    fun renamePlaylist(id: String, name: String) = viewModelScope.launch { store.rename(id, name) }

    /** "Guardar en el móvil" / "Pasar a streaming". Downloads and deletions follow by themselves. */
    fun setPlaylistOffline(playlist: Playlist, offline: Boolean) = viewModelScope.launch {
        store.setOffline(playlist.id, offline)
        say(if (offline) "Guardando «${playlist.name}» en el móvil" else "«${playlist.name}» pasa a streaming")
    }

    /** Everything saved (lists and albums) back to streaming. */
    fun allToStreaming() = viewModelScope.launch {
        store.playlists.value.filter { it.offline }.forEach { store.setOffline(it.id, false) }
        albumStore.albums.value.filter { it.offline }.forEach { albumStore.setOffline(it.id, false) }
        say("Todo pasa a streaming")
    }

    /** "Reintentar" for songs of the playlist that failed to save. */
    fun retrySaving(playlist: Playlist) = offline.retry(playlist.tracks.map { it.videoId })

    fun deletePlaylist(playlist: Playlist) = viewModelScope.launch {
        store.delete(playlist.id)
        say("Lista «${playlist.name}» borrada")
    }

    /** Adds [track] to the playlist, or takes it out if it was already there. */
    fun toggleInPlaylist(playlist: Playlist, track: Track) = viewModelScope.launch {
        if (playlist.contains(track.videoId)) {
            store.removeTrack(playlist.id, track.videoId)
        } else {
            store.addTrack(playlist.id, track)
            if (activeList.value == null) setActiveList(playlist) // first choice becomes active
        }
    }

    fun removeFromPlaylist(playlist: Playlist, track: Track) = viewModelScope.launch {
        val at = store.removeTrack(playlist.id, track.videoId)
        if (at >= 0) {
            say("Quitada de «${playlist.name}»") {
                viewModelScope.launch { store.addTrack(playlist.id, track, at) }
            }
        }
    }

    fun moveInPlaylist(playlistId: String, from: Int, to: Int) =
        viewModelScope.launch { store.moveTrack(playlistId, from, to) }

    private fun say(text: String, undo: (() -> Unit)? = null) {
        _messages.trySend(UiMessage(text, undo))
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            _nowPlaying.value = null
            return
        }
        val md = item.mediaMetadata
        _nowPlaying.update {
            NowPlaying(
                videoId = item.mediaId,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                artworkUri = md.artworkUri,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { it > 0 } ?: (md.durationMs ?: 0L),
                hasNext = c.hasNextMediaItem(),
                hasPrevious = c.hasPreviousMediaItem() || c.currentPosition > 3_000,
                error = c.playerError?.let(::describe),
            )
        }
    }

    private fun describe(e: PlaybackException): String =
        generateSequence<Throwable>(e) { it.cause }.last().message ?: e.errorCodeName

    private companion object {
        const val KEY_ACTIVE_LIST = "activeList"
        const val KEY_SEARCH_KIND = "searchKind"
        const val KEY_ALBUM_ORDER = "albumOrder"
        const val KEY_TOP_KIND = "topKind"
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}

private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(YouTubeDataSource.uriFor(videoId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setDurationMs(durationSec.takeIf { it > 0 }?.times(1000))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

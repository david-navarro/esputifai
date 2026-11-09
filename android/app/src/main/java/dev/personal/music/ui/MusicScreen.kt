package dev.personal.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.personal.music.data.Album
import dev.personal.music.data.Artist
import dev.personal.music.data.Track

private enum class Tab(val label: String, val icon: ImageVector) {
    Search("Buscar", Icons.Rounded.Search),
    Playlists("Mis listas", Icons.AutoMirrored.Rounded.QueueMusic),
    Albums("Albumteca", Icons.Rounded.Album),
}

/** A screen pushed on top of a tab's main screen. */
private sealed interface Route {
    data class PlaylistPage(val id: String) : Route
    data class AlbumPage(val album: Album) : Route
    data class ArtistPage(val artist: Artist) : Route
}

/**
 * App shell: three tabs at the bottom (Buscar / Mis listas / Albumteca), the player above them,
 * snackbars with "Deshacer", and the "add to playlist" panel. Navigation is plain state, no
 * library: each tab keeps its own stack of pushed screens (Buscar → Artista → Álbum…); "back"
 * pops it, and tapping the current tab again returns to its main screen.
 */
@Composable
fun MusicScreen(vm: MusicViewModel) {
    val search by vm.search.collectAsStateWithLifecycle()
    val searchKind by vm.searchKind.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val activeList by vm.activeList.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val plays by vm.plays.collectAsStateWithLifecycle()
    val albumOrder by vm.albumOrder.collectAsStateWithLifecycle()
    val savedAudio by vm.savedAudio.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var showingStorage by remember { mutableStateOf(false) }

    var tab by rememberSaveable { mutableStateOf(Tab.Search) }
    val stacks = remember { mutableStateMapOf<Tab, List<Route>>() }
    val route = stacks[tab].orEmpty().lastOrNull()
    fun push(r: Route) { stacks[tab] = stacks[tab].orEmpty() + r }
    fun pop() { stacks[tab] = stacks[tab].orEmpty().dropLast(1) }

    var query by rememberSaveable { mutableStateOf("") }
    var addingTrack by remember { mutableStateOf<Track?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val activeIds = remember(activeList) { activeList?.tracks?.map { it.videoId }?.toSet().orEmpty() }
    val albumtecaIds = remember(albums) { albums.map { it.id }.toSet() }

    val songs = SongActions(
        playingId = nowPlaying?.videoId,
        activeIds = activeIds,
        onPlay = vm::play,
        onAdd = { t -> if (!vm.addToActiveList(t)) addingTrack = t }, // no active list yet: choose
        onChooseLists = { addingTrack = it },
    )
    val activeListBar: @Composable () -> Unit = {
        ActiveListBar(
            active = activeList,
            playlists = playlists,
            onSelect = vm::setActiveList,
            onCreate = { name, offline -> vm.createPlaylist(name, offline, makeActive = true) },
        )
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val result = snackbar.showSnackbar(
                m.text,
                actionLabel = m.undo?.let { "Deshacer" },
                // Undo needs time to read and reach for it.
                duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.undo?.invoke()
        }
    }

    BackHandler(enabled = route != null) { pop() }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    action = data.visuals.actionLabel?.let { label ->
                        {
                            TextButton(onClick = { data.performAction() }) {
                                Text(label)
                            }
                        }
                    },
                    modifier = Modifier.padding(12.dp),
                ) { Text(data.visuals.message) }
            }
        },
        bottomBar = {
            Column {
                nowPlaying?.let { np ->
                    PlayerBar(
                        np,
                        onToggle = vm::togglePlayPause,
                        onNext = vm::next,
                        onPrevious = vm::previous,
                        online = online,
                        inActiveList = np.videoId in activeIds,
                        onAdd = { songs.onAdd(np.toTrack()) },
                        onChooseLists = { addingTrack = np.toTrack() },
                    )
                }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                if (t == tab) stacks[t] = emptyList() // back to the tab's main screen
                                tab = t
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                is Route.AlbumPage -> {
                    val inLib = albums.find { it.id == route.album.id }
                    AlbumScreen(
                        album = inLib?.album ?: route.album,
                        inAlbumteca = inLib,
                        loadTracks = vm::albumTracks,
                        songs = songs,
                        activeListBar = activeListBar,
                        savedAudio = savedAudio,
                        online = online,
                        onBack = ::pop,
                        onAddToAlbumteca = { vm.addToAlbumteca(route.album) },
                        onRemoveFromAlbumteca = { inLib?.let(vm::removeFromAlbumteca) },
                        onSetOffline = { off -> inLib?.let { vm.setAlbumOffline(it, off) } },
                        onRetry = { inLib?.let(vm::retrySaving) },
                    )
                }
                is Route.ArtistPage -> ArtistScreen(
                    artist = route.artist,
                    loadPage = vm::artistPage,
                    songs = songs,
                    activeListBar = activeListBar,
                    albumtecaIds = albumtecaIds,
                    onBack = ::pop,
                    onOpenAlbum = { push(Route.AlbumPage(it)) },
                    onAddAlbum = vm::addToAlbumteca,
                )
                is Route.PlaylistPage -> {
                    val open = playlists.find { it.id == route.id }
                    if (open == null) {
                        LaunchedEffect(route) { pop() } // deleted
                    } else {
                        PlaylistDetailScreen(
                            playlist = open,
                            playingId = nowPlaying?.videoId,
                            savedAudio = savedAudio,
                            online = online,
                            onBack = ::pop,
                            onPlay = vm::play,
                            onShuffle = vm::playShuffled,
                            onRename = { vm.renamePlaylist(open.id, it) },
                            onDelete = {
                                pop()
                                vm.deletePlaylist(open)
                            },
                            onSetOffline = { vm.setPlaylistOffline(open, it) },
                            onRetry = { vm.retrySaving(open) },
                            onRemove = { vm.removeFromPlaylist(open, it) },
                            onMove = { from, to -> vm.moveInPlaylist(open.id, from, to) },
                            onGoToSearch = { tab = Tab.Search },
                        )
                    }
                }
                null -> when (tab) {
                    Tab.Search -> SearchScreen(
                        query = query,
                        onQueryChange = { query = it },
                        kind = searchKind,
                        onKindChange = vm::setSearchKind,
                        state = search,
                        songs = songs,
                        albumtecaIds = albumtecaIds,
                        activeListBar = activeListBar,
                        onSearch = vm::search,
                        onMoreRelated = vm::moreRelated,
                        onOpenAlbum = { push(Route.AlbumPage(it)) },
                        onAddAlbum = vm::addToAlbumteca,
                        onOpenArtist = { push(Route.ArtistPage(it)) },
                    )
                    Tab.Playlists -> PlaylistsScreen(
                        playlists,
                        savedAudio = savedAudio,
                        online = online,
                        onOpen = { push(Route.PlaylistPage(it.id)) },
                        onCreate = { name, offline -> vm.createPlaylist(name, offline) },
                        savedAlbums = albums.count { it.offline },
                        onShowStorage = { showingStorage = true },
                    )
                    Tab.Albums -> AlbumtecaScreen(
                        albums = albums,
                        plays = plays,
                        order = albumOrder,
                        online = online,
                        onOrderChange = vm::setAlbumOrder,
                        onOpen = { push(Route.AlbumPage(it.album)) },
                        onGoToSearch = {
                            vm.setSearchKind(SearchKind.Albums)
                            tab = Tab.Search
                        },
                    )
                }
            }
        }
    }

    addingTrack?.let { track ->
        AddToPlaylistSheet(
            track = track,
            playlists = playlists,
            onToggle = { vm.toggleInPlaylist(it, track) },
            onCreateWithTrack = { name, offline -> vm.createPlaylist(name, offline, track) },
            onDismiss = { addingTrack = null },
        )
    }

    if (showingStorage) {
        StorageDialog(
            playlists = playlists,
            albums = albums,
            savedAudio = savedAudio,
            freeBytes = remember { vm.freeBytes() },
            onAllToStreaming = vm::allToStreaming,
            onDismiss = { showingStorage = false },
        )
    }
}

/** Compact player above the tabs: song, "+" (active list), previous / play-pause / next. */
@Composable
private fun PlayerBar(
    np: NowPlaying,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    online: Boolean,
    inActiveList: Boolean,
    onAdd: () -> Unit,
    onChooseLists: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            val progress = if (np.durationMs > 0) np.positionMs.toFloat() / np.durationMs else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(np.artworkUri?.toString(), 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        np.error?.let { if (online) "No se puede reproducir: $it" else "Sin internet y no está guardada en el móvil" }
                            ?: "${np.artist} · ${clock(np.positionMs)} / ${clock(np.durationMs)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (np.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AddToPlaylistButton(inActiveList, onClick = onAdd, onLongClick = onChooseLists)
                IconButton(onClick = onPrevious, enabled = np.hasPrevious) { Icon(Icons.Rounded.SkipPrevious, "Anterior") }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (np.isBuffering) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        IconButton(onClick = onToggle) {
                            Icon(
                                if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (np.isPlaying) "Pausa" else "Reproducir",
                            )
                        }
                    }
                }
                IconButton(onClick = onNext, enabled = np.hasNext) { Icon(Icons.Rounded.SkipNext, "Siguiente") }
            }
        }
    }
}

/** "m:ss" for the player clock; unlike [formatDuration] it shows 0:00 instead of nothing. */
private fun clock(ms: Long) = formatDuration(ms).ifEmpty { "0:00" }

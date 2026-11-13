package dev.personal.music.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import dev.personal.music.data.OnlinePlaylist
import dev.personal.music.data.Track

private enum class Tab(val label: String, val icon: ImageVector) {
    Search("Buscar", Icons.Rounded.Search),
    Playlists("Mis listas", Icons.AutoMirrored.Rounded.QueueMusic),
    Albums("Albumteca", Icons.Rounded.Album),
    Top("Top", Icons.Rounded.EmojiEvents),
}

/** A screen pushed on top of a tab's main screen. */
private sealed interface Route {
    data class PlaylistPage(val id: String) : Route
    data class AlbumPage(val album: Album) : Route
    data class ArtistPage(val artist: Artist) : Route
    data class OnlinePlaylistPage(val playlist: OnlinePlaylist) : Route
}

/**
 * App shell: four tabs at the bottom (Buscar / Mis listas / Albumteca / Top), the player above them,
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
    val top by vm.top.collectAsStateWithLifecycle()
    val topKind by vm.topKind.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val albumOrder by vm.albumOrder.collectAsStateWithLifecycle()
    val savedAudio by vm.savedAudio.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var showingStorage by remember { mutableStateOf(false) }
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val savedOnline by vm.savedOnline.collectAsStateWithLifecycle()
    var showingSync by remember { mutableStateOf(false) }
    // Google's account / permission screen, when turning sync on needs it.
    val googleScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onSyncGoogleResult(it.data)
    }
    val turnOnSync = {
        vm.turnOnSync { screen -> googleScreen.launch(IntentSenderRequest.Builder(screen.intentSender).build()) }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Search) }
    val stacks = remember { mutableStateMapOf<Tab, List<Route>>() }
    val route = stacks[tab].orEmpty().lastOrNull()
    fun push(r: Route) { stacks[tab] = stacks[tab].orEmpty() + r }
    fun pop() { stacks[tab] = stacks[tab].orEmpty().dropLast(1) }

    var query by rememberSaveable { mutableStateOf("") }
    var addingTrack by remember { mutableStateOf<Track?>(null) }
    var songMenu by remember { mutableStateOf<Track?>(null) }
    var showingPlayer by remember { mutableStateOf(false) }
    val queue by vm.queue.collectAsStateWithLifecycle()
    var albumLookup by remember { mutableStateOf(AlbumLookup.Idle) }
    val openSongMenu: (Track) -> Unit = {
        albumLookup = AlbumLookup.Idle
        songMenu = it
    }
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

    CompositionLocalProvider(LocalPlays provides plays, LocalSongMenu provides openSongMenu) {
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
                        onLongPress = { openSongMenu(np.toTrack()) },
                        onOpen = { showingPlayer = true },
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
                    val album = inLib?.album ?: route.album
                    AlbumScreen(
                        album = album,
                        inAlbumteca = inLib,
                        loadTracks = vm::albumTracks,
                        // Playing from here also remembers the album for the Top.
                        songs = SongActions(
                            playingId = songs.playingId,
                            activeIds = songs.activeIds,
                            onPlay = { tracks, i -> vm.playAlbum(album, tracks, i) },
                            onAdd = songs.onAdd,
                            onChooseLists = songs.onChooseLists,
                        ),
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
                is Route.OnlinePlaylistPage -> OnlinePlaylistScreen(
                    playlist = route.playlist,
                    loadTracks = vm::onlinePlaylistTracks,
                    songs = songs,
                    activeListBar = activeListBar,
                    // The local copy's current name; null if not saved or since deleted.
                    savedAs = savedOnline[route.playlist.id]?.let { id -> playlists.find { it.id == id }?.name },
                    onBack = ::pop,
                    onSave = { vm.saveOnlinePlaylist(route.playlist, it) },
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
                            onShare = {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, vm.shareText(open))
                                context.startActivity(Intent.createChooser(send, "Compartir «${open.name}»"))
                            },
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
                        onQueryChange = {
                            query = it
                            vm.onQueryTyped(it)
                        },
                        suggestions = suggestions,
                        onSuggestion = {
                            query = it
                            vm.search(it)
                        },
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
                        onOpenPlaylist = { push(Route.OnlinePlaylistPage(it)) },
                    )
                    Tab.Playlists -> PlaylistsScreen(
                        playlists,
                        savedAudio = savedAudio,
                        online = online,
                        onOpen = { push(Route.PlaylistPage(it.id)) },
                        onCreate = { name, offline -> vm.createPlaylist(name, offline) },
                        savedAlbums = albums.count { it.offline },
                        onShowStorage = { showingStorage = true },
                        syncStatus = syncStatus,
                        onOpenSync = { showingSync = true },
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
                    Tab.Top -> TopScreen(
                        top = top,
                        kind = topKind,
                        onKindChange = vm::setTopKind,
                        songs = songs,
                        activeListBar = activeListBar,
                        onOpenArtist = { a ->
                            scope.launch {
                                val artist = runCatching { vm.findArtist(a.name) }.getOrNull()
                                if (artist != null) push(Route.ArtistPage(artist))
                                else snackbar.showSnackbar("No se ha encontrado a ${a.name}. ¿Hay internet?")
                            }
                        },
                        onOpenAlbum = { push(Route.AlbumPage(it)) },
                    )
                }
            }
        }
    }

    }

    val np = nowPlaying
    if (showingPlayer && np != null) {
        // The sheet is outside the Scaffold's providers, and its song rows need them too.
        CompositionLocalProvider(LocalPlays provides plays, LocalSongMenu provides openSongMenu) {
            NowPlayingSheet(
                np = np,
                queue = queue,
                onSeek = { vm.seekTo(it) },
                onToggle = vm::togglePlayPause,
                onNext = { vm.next() },
                onPrevious = { vm.previous() },
                onPlayItem = vm::playQueueItem,
                onMove = { from, to -> vm.moveInQueue(from, to) },
                onRemove = { vm.removeFromQueue(it) },
                onDismiss = { showingPlayer = false },
            )
        }
    } else if (showingPlayer) {
        showingPlayer = false // the queue was emptied
    }

    incoming?.let { shared ->
        AlertDialog(
            onDismissRequest = vm::dismissIncoming,
            title = { Text("¿Importar «${shared.name}»?") },
            text = {
                val n = shared.tracks.size
                Text(
                    "Te han compartido una lista con ${if (n == 1) "1 canción" else "$n canciones"}. " +
                        "Se añadirá a «Mis listas», en streaming.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importIncoming()
                    stacks[Tab.Playlists] = emptyList()
                    tab = Tab.Playlists
                }) { Text("Importar") }
            },
            dismissButton = { TextButton(onClick = vm::dismissIncoming) { Text("Cancelar") } },
        )
    }

    songMenu?.let { track ->
        SongMenuDialog(
            track = track,
            lookup = albumLookup,
            onGoToAlbum = {
                albumLookup = AlbumLookup.Searching
                scope.launch {
                    val album = runCatching { vm.albumOf(track) }.getOrNull()
                    if (songMenu != track) return@launch // closed meanwhile
                    if (album == null) {
                        albumLookup = AlbumLookup.NotFound
                    } else {
                        songMenu = null
                        showingPlayer = false
                        push(Route.AlbumPage(album))
                    }
                }
            },
            onDismiss = { songMenu = null },
        )
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

    if (showingSync) {
        SyncDialog(
            status = syncStatus,
            onTurnOn = { turnOnSync() },
            onSyncNow = vm::syncNow,
            onTurnOff = vm::turnOffSync,
            onDismiss = { showingSync = false },
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
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
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
                Column(Modifier.weight(1f).combinedClickable(onClick = onOpen, onLongClick = onLongPress)) {
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

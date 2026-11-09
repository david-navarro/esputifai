package dev.personal.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.personal.music.data.Album
import dev.personal.music.data.AlbumOrder
import dev.personal.music.data.LibraryAlbum
import dev.personal.music.data.Track
import dev.personal.music.data.sortAlbums
import dev.personal.music.offline.SaveState
import dev.personal.music.offline.SavedAudio
import dev.personal.music.offline.progressOf

/** The Albumteca tab: saved albums, sortable by plays or alphabetically. */
@Composable
fun AlbumtecaScreen(
    albums: List<LibraryAlbum>,
    plays: Map<String, Int>,
    order: AlbumOrder,
    online: Boolean,
    onOrderChange: (AlbumOrder) -> Unit,
    onOpen: (LibraryAlbum) -> Unit,
    onGoToSearch: () -> Unit,
) {
    val sorted = remember(albums, plays, order) { sortAlbums(albums, order, plays) }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Albumteca",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        if (albums.isEmpty()) {
            EmptyMessage(
                "Todavía no hay álbumes",
                "En Buscar, elige «Álbumes» (o abre un artista) y pulsa el botón junto a un álbum para añadirlo aquí.",
                Icons.Rounded.Album,
            ) { BigButton("Ir a Buscar", Icons.Rounded.LibraryAdd, onClick = onGoToSearch) }
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumOrder.entries.forEach { o ->
                FilterChip(selected = o == order, onClick = { onOrderChange(o) }, label = { Text(o.label) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sorted, key = { it.id }) { a ->
                val unavailable = !online && !a.offline
                Box(Modifier.alpha(if (unavailable) 0.45f else 1f)) {
                    AlbumRow(
                        a.album,
                        subtitle = listOfNotNull(
                            a.album.artist.ifBlank { null },
                            songCount(a.tracks.size),
                            if (a.offline) "En el móvil" else if (unavailable) "Necesita internet" else null,
                        ).joinToString(" · "),
                        onClick = { onOpen(a) },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One album, from search, an artist page or the Albumteca. Its songs come from the Albumteca when
 * it is there, otherwise from YouTube. Albumteca options (save on the phone, remove) are in "⋮".
 */
@Composable
fun AlbumScreen(
    album: Album,
    inAlbumteca: LibraryAlbum?,
    loadTracks: suspend (Album) -> List<Track>,
    songs: SongActions,
    activeListBar: @Composable () -> Unit,
    savedAudio: Map<String, SavedAudio>,
    online: Boolean,
    onBack: () -> Unit,
    onAddToAlbumteca: () -> Unit,
    onRemoveFromAlbumteca: () -> Unit,
    onSetOffline: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val tracks by produceState<Result<List<Track>>?>(null, album.id, inAlbumteca?.tracks, attempt) {
        value = inAlbumteca?.let { Result.success(it.tracks) } ?: runCatching { loadTracks(album) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingStreaming by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(album.title, onBack) {
            if (inAlbumteca != null) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, "Opciones del álbum")
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (inAlbumteca.offline) {
                            MenuItem("Pasar a streaming", Icons.Rounded.Cloud) { menuOpen = false; confirmingStreaming = true }
                        } else {
                            MenuItem("Guardar en el móvil", Icons.Rounded.DownloadForOffline) { menuOpen = false; onSetOffline(true) }
                        }
                        MenuItem("Quitar de la Albumteca", Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                            menuOpen = false
                            confirmingRemove = true
                        }
                    }
                }
            }
        }
        activeListBar()
        val loaded = tracks
        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.isFailure -> EmptyMessage(
                "No se ha podido abrir el álbum",
                "Comprueba que hay internet.",
                Icons.Rounded.CloudOff,
            ) { OutlinedButton(onClick = { attempt++ }) { Text("Reintentar") } }
            else -> {
                val list = loaded.getOrThrow()
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") {
                        AlbumHeader(album, list, inAlbumteca, savedAudio, online, songs, onAddToAlbumteca, onRetry)
                    }
                    itemsIndexed(list, key = { _, t -> t.videoId }) { index, track ->
                        TrackRow(
                            track,
                            playing = track.videoId == songs.playingId,
                            onClick = { songs.onPlay(list, index) },
                            dimmed = !online && savedAudio[track.videoId]?.state != SaveState.Saved,
                        ) {
                            if (inAlbumteca?.offline == true) SaveIndicator(savedAudio[track.videoId])
                            AddToPlaylistButton(
                                inActiveList = track.videoId in songs.activeIds,
                                onClick = { songs.onAdd(track) },
                                onLongClick = { songs.onChooseLists(track) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmingStreaming && inAlbumteca != null) {
        ConfirmDialog(
            title = "¿Pasar «${album.title}» a streaming?",
            text = "Las canciones de este álbum dejarán de estar en el móvil y hará falta internet para escucharlas. " +
                "Las que también estén en una lista u otro álbum guardado se quedan.",
            confirmLabel = "Pasar a streaming",
            onConfirm = { onSetOffline(false) },
            onDismiss = { confirmingStreaming = false },
        )
    }
    if (confirmingRemove && inAlbumteca != null) {
        ConfirmDialog(
            title = "¿Quitar «${album.title}» de la Albumteca?",
            text = "El álbum sigue en YouTube Music y puedes volver a añadirlo." +
                if (inAlbumteca.offline) " Lo guardado en el móvil para este álbum se borra." else "",
            confirmLabel = "Quitar",
            onConfirm = onRemoveFromAlbumteca,
            onDismiss = { confirmingRemove = false },
        )
    }
}

@Composable
private fun AlbumHeader(
    album: Album,
    tracks: List<Track>,
    inAlbumteca: LibraryAlbum?,
    savedAudio: Map<String, SavedAudio>,
    online: Boolean,
    songs: SongActions,
    onAddToAlbumteca: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(album.thumbnailUrl, 96)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    listOf(songCount(tracks.size), formatDuration(tracks.sumOf { it.durationSec } * 1000))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Reproducir", Icons.Rounded.PlayArrow, onClick = { songs.onPlay(tracks, 0) }, Modifier.weight(1f))
            FilledTonalButton(onClick = { songs.onPlay(tracks.shuffled(), 0) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Aleatorio")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (inAlbumteca == null) {
            OutlinedButton(onClick = onAddToAlbumteca, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.LibraryAdd, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Añadir a la Albumteca")
            }
        } else if (inAlbumteca.offline) {
            OfflineStatus(progressOf(inAlbumteca.tracks, savedAudio), online, onRetry)
        } else {
            StatusLine(Icons.Rounded.LibraryAddCheck, "En tu Albumteca", MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

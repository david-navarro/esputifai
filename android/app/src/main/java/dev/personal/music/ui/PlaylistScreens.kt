package dev.personal.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.personal.music.data.Playlist
import dev.personal.music.data.Track
import dev.personal.music.offline.OfflineProgress
import dev.personal.music.offline.SaveState
import dev.personal.music.offline.SavedAudio
import dev.personal.music.sync.SyncStatus
import dev.personal.music.offline.formatBytes
import dev.personal.music.offline.progressOf

/** "Mis listas": every playlist as a big row, plus the button to create one. */
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    savedAudio: Map<String, SavedAudio>,
    online: Boolean,
    onOpen: (Playlist) -> Unit,
    onCreate: (name: String, offline: Boolean) -> Unit,
    savedAlbums: Int,
    onShowStorage: () -> Unit,
    syncStatus: SyncStatus,
    onOpenSync: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    val savedLists = playlists.filter { it.offline }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mis listas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            SyncButton(syncStatus, onOpenSync)
        }
        if (playlists.isEmpty()) {
            EmptyMessage(
                "Todavía no tienes listas",
                "Crea una lista y luego añade canciones desde Buscar con el botón que hay junto a cada canción.",
                Icons.AutoMirrored.Rounded.QueueMusic,
            ) { BigButton("Nueva lista", Icons.Rounded.Add, onClick = { creating = true }) }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (savedLists.isNotEmpty() || savedAlbums > 0) {
                    item { StorageLine(savedLists.size, savedAlbums, savedAudio, onShowStorage) }
                }
                item {
                    BigButton(
                        "Nueva lista",
                        Icons.Rounded.Add,
                        onClick = { creating = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(playlists, key = { it.id }) { p ->
                    val unavailable = !online && !p.offline
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (unavailable) 0.45f else 1f)
                            .clickable { onOpen(p) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCover(p, 52)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    songCount(p.tracks.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                when {
                                    p.offline -> Badge(Icons.Rounded.DownloadForOffline, "En el móvil", MaterialTheme.colorScheme.primary)
                                    unavailable -> Badge(Icons.Rounded.CloudOff, "Necesita internet", MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NewPlaylistDialog(onCreate = onCreate, onDismiss = { creating = false })
    }
}

/** " · [icon] En el móvil" after the song count. */
@Composable
private fun Badge(icon: ImageVector, text: String, color: Color) {
    Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = color)
    Spacer(Modifier.width(2.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "En el móvil: 3 listas · 1 álbum · 96 MB". Opens the storage panel. */
@Composable
private fun StorageLine(lists: Int, albums: Int, savedAudio: Map<String, SavedAudio>, onClick: () -> Unit) {
    val bytes = savedAudio.values.filter { it.state == SaveState.Saved }.sumOf { it.bytes }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                "En el móvil: " + listOfNotNull(
                    lists.takeIf { it > 0 }?.let { if (it == 1) "1 lista" else "$it listas" },
                    albums.takeIf { it > 0 }?.let { if (it == 1) "1 álbum" else "$it álbumes" },
                    formatBytes(bytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** One playlist: play / shuffle buttons and its songs, each with a menu to move or remove it. */
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    playingId: String?,
    savedAudio: Map<String, SavedAudio>,
    online: Boolean,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onRename: (String) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onSetOffline: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onRemove: (Track) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onGoToSearch: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingStreaming by remember { mutableStateOf(false) }
    val tracks = playlist.tracks
    val isSaved = { t: Track -> savedAudio[t.videoId]?.state == SaveState.Saved }

    Column(Modifier.fillMaxSize()) {
        // Top bar: back, name, list menu.
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver a mis listas")
            }
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, "Opciones de la lista")
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (playlist.offline) {
                        MenuItem("Pasar a streaming", Icons.Rounded.Cloud) { menuOpen = false; confirmingStreaming = true }
                    } else {
                        MenuItem("Guardar en el móvil", Icons.Rounded.DownloadForOffline) { menuOpen = false; onSetOffline(true) }
                    }
                    MenuItem("Cambiar nombre", Icons.Rounded.Edit) { menuOpen = false; renaming = true }
                    if (tracks.isNotEmpty()) MenuItem("Compartir", Icons.Rounded.Share) { menuOpen = false; onShare() }
                    MenuItem("Borrar lista", Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                        menuOpen = false
                        confirmingDelete = true
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            EmptyMessage(
                "Esta lista está vacía",
                "Busca canciones y pulsa el botón que hay junto a cada una para añadirlas aquí." +
                    if (playlist.offline) " Se guardarán en el móvil solas." else "",
                Icons.AutoMirrored.Rounded.QueueMusic,
            ) { BigButton("Ir a Buscar", Icons.Rounded.Search, onClick = onGoToSearch) }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            listOf(songCount(tracks.size), formatDuration(tracks.sumOf { it.durationSec } * 1000))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigButton("Reproducir", Icons.Rounded.PlayArrow, onClick = { onPlay(tracks, 0) }, Modifier.weight(1f))
                            FilledTonalButton(onClick = { onShuffle(tracks) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("Aleatorio")
                            }
                        }
                        when {
                            playlist.offline -> OfflineStatus(progressOf(playlist, savedAudio), online, onRetry)
                            !online -> StatusLine(
                                Icons.Rounded.CloudOff,
                                "Sin internet. Esta lista se escucha en streaming y necesita conexión.",
                                MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
                itemsIndexed(tracks, key = { _, t -> t.videoId }) { index, track ->
                    TrackRow(
                        track,
                        playing = track.videoId == playingId,
                        onClick = { onPlay(tracks, index) },
                        modifier = Modifier.animateItem(),
                        dimmed = !online && !isSaved(track),
                    ) {
                        if (playlist.offline) SaveIndicator(savedAudio[track.videoId])
                        TrackMenu(
                            canMoveUp = index > 0,
                            canMoveDown = index < tracks.lastIndex,
                            onMoveUp = { onMove(index, index - 1) },
                            onMoveDown = { onMove(index, index + 1) },
                            onRemove = { onRemove(track) },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog("Cambiar nombre", "Guardar", onConfirm = onRename, onDismiss = { renaming = false }, initial = playlist.name)
    }
    if (confirmingStreaming) {
        ConfirmDialog(
            title = "¿Pasar «${playlist.name}» a streaming?",
            text = "Las canciones de esta lista dejarán de estar en el móvil y hará falta internet para escucharlas. " +
                "Las que también estén en otra lista guardada se quedan.",
            confirmLabel = "Pasar a streaming",
            onConfirm = { onSetOffline(false) },
            onDismiss = { confirmingStreaming = false },
        )
    }
    if (confirmingDelete) {
        ConfirmDialog(
            title = "¿Borrar «${playlist.name}»?",
            text = "Se borra la lista con sus ${songCount(tracks.size)}. Las canciones siguen en YouTube Music; solo desaparece la lista." +
                if (playlist.offline) " Lo guardado en el móvil para esta lista también se borra." else "",
            confirmLabel = "Borrar",
            onConfirm = onDelete,
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** Status of a saved playlist under its play buttons. */
@Composable
internal fun OfflineStatus(p: OfflineProgress, online: Boolean, onRetry: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    when {
        p.complete -> StatusLine(
            Icons.Rounded.DownloadDone,
            "Todo guardado en el móvil · ${formatBytes(p.bytes)}",
            MaterialTheme.colorScheme.primary,
        )
        p.pending > 0 && !online -> StatusLine(
            Icons.Rounded.CloudOff,
            "Sin conexión · ${p.saved} de ${p.total} guardadas. Seguirá sola al volver internet.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        p.pending > 0 -> {
            StatusLine(
                Icons.Rounded.DownloadForOffline,
                "Guardando en el móvil… ${p.saved} de ${p.total}",
                MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (p.total > 0) p.saved.toFloat() / p.total else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        }
        else -> {
            StatusLine(
                Icons.Rounded.ErrorOutline,
                if (p.failed == 1) "1 canción no se pudo guardar" else "${p.failed} canciones no se pudieron guardar",
                MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}

@Composable
internal fun StatusLine(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Per-song progress inside a saved list; nothing once it is saved (the list says so). */
@Composable
internal fun SaveIndicator(audio: SavedAudio?) {
    when (audio?.state) {
        SaveState.Saved -> {}
        SaveState.Failed -> Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = "No se pudo guardar",
            tint = MaterialTheme.colorScheme.error,
        )
        SaveState.Downloading -> if (audio.percent >= 0) {
            CircularProgressIndicator(progress = { audio.percent / 100f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        else -> CircularProgressIndicator(
            Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Panel behind the "En el móvil" line: what each saved list and album takes; all to streaming. */
@Composable
fun StorageDialog(
    playlists: List<Playlist>,
    albums: List<dev.personal.music.data.LibraryAlbum>,
    savedAudio: Map<String, SavedAudio>,
    freeBytes: Long,
    onAllToStreaming: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val saved = playlists.filter { it.offline }
    val total = savedAudio.values.filter { it.state == SaveState.Saved }.sumOf { it.bytes }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardado en el móvil") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                run {
                    Text(
                        "Ocupa ${formatBytes(total)}. Quedan ${formatBytes(freeBytes)} libres en el móvil.",
                    )
                }
                saved.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaylistCover(p, 40)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatBytes(progressOf(p, savedAudio).bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                albums.filter { it.offline }.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(a.album.thumbnailUrl, 40)
                        Spacer(Modifier.width(12.dp))
                        Text(a.album.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatBytes(progressOf(a.tracks, savedAudio).bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                run {
                    Text(
                        "Una canción que está en dos listas o álbumes ocupa espacio una sola vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { confirming = true }) {
                Text("Pasar todo a streaming", color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (confirming) {
        ConfirmDialog(
            title = "¿Pasar todo a streaming?",
            text = "Se borra del móvil todo lo guardado (${formatBytes(total)}). Las listas y álbumes no se borran, pero hará falta internet para escucharlos.",
            confirmLabel = "Pasar todo",
            onConfirm = { onAllToStreaming(); onDismiss() },
            onDismiss = { confirming = false },
        )
    }
}

/** The "⋮" on each song inside a playlist: move up, move down, remove. */
@Composable
internal fun TrackMenu(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    removeLabel: String = "Quitar de la lista",
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Rounded.MoreVert, "Opciones de la canción")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canMoveUp) MenuItem("Subir", Icons.Rounded.ArrowUpward) { open = false; onMoveUp() }
            if (canMoveDown) MenuItem("Bajar", Icons.Rounded.ArrowDownward) { open = false; onMoveDown() }
            MenuItem(removeLabel, Icons.Rounded.RemoveCircleOutline, MaterialTheme.colorScheme.error) {
                open = false
                onRemove()
            }
        }
    }
}

@Composable
internal fun MenuItem(text: String, icon: ImageVector, color: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color) },
        onClick = onClick,
        modifier = Modifier,
    )
}

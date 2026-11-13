package dev.personal.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.RadioButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.personal.music.data.Album
import dev.personal.music.data.Artist
import dev.personal.music.data.Playlist
import dev.personal.music.data.Track

/*
 * Shared building blocks. Sizes are Material 3 defaults (as in v0.1.0); whoever needs bigger text
 * raises the phone's font size, which the app follows.
 */

/** One song: artwork, title, "artist · duration", and an optional action at the end. */
/**
 * Times each song has been played, by videoId. Provided once by MusicScreen so every song row in
 * the app can show its counter without passing the map through every screen.
 */
val LocalPlays = compositionLocalOf<Map<String, Int>> { emptyMap() }

/** What a long press on any song row does (opens the song's menu); provided by MusicScreen. */
val LocalSongMenu = compositionLocalOf<((Track) -> Unit)?> { null }

@Composable
fun TrackRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val songMenu = LocalSongMenu.current
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.4f else 1f)
            .background(if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = songMenu?.let { open ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        open(track)
                    }
                },
            )
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.thumbnailUrl, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.End) {
            Text(
                formatDuration(track.durationSec * 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlayCount(LocalPlays.current[track.videoId] ?: 0)
        }
        trailing()
    }
}

/** "▶ 12": how many times the user has played something; nothing if never. */
@Composable
fun PlayCount(plays: Int) {
    if (plays <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "$plays",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Artwork(url: String?, sizeDp: Int) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** A playlist's cover: its first song's artwork, or a music icon while it is empty. */
@Composable
fun PlaylistCover(playlist: Playlist, sizeDp: Int) {
    val first = playlist.tracks.firstOrNull()
    if (first != null) {
        Artwork(first.thumbnailUrl, sizeDp)
    } else {
        Box(
            Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size((sizeDp / 2).dp),
            )
        }
    }
}

/** A button with an icon, for the main action of a screen. */
@Composable
fun BigButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text)
    }
}

/** A message in the middle of an empty screen, with an optional button under it. */
@Composable
fun EmptyMessage(
    title: String,
    detail: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Asks for a playlist name (create or rename). The keyboard opens right away. */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    extra: (@Composable () -> Unit)? = null,
) {
    // Cursor at the end, so renaming just means typing more or deleting.
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val confirm = {
        if (value.text.isNotBlank()) {
            onConfirm(value.text.trim())
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text("Por ejemplo: Para cocinar") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = value.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** "Nueva lista": a name plus how it is listened to (streaming or saved on the phone). */
@Composable
fun NewPlaylistDialog(onCreate: (name: String, offline: Boolean) -> Unit, onDismiss: () -> Unit) {
    var offline by remember { mutableStateOf(false) }
    NameDialog(
        title = "Nueva lista",
        confirmLabel = "Crear",
        onConfirm = { onCreate(it, offline) },
        onDismiss = onDismiss,
        extra = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("¿Cómo se escucha?", style = MaterialTheme.typography.titleSmall)
                ModeOption(
                    selected = !offline,
                    icon = Icons.Rounded.Cloud,
                    title = "En streaming",
                    detail = "Necesita internet. No ocupa espacio.",
                ) { offline = false }
                ModeOption(
                    selected = offline,
                    icon = Icons.Rounded.DownloadForOffline,
                    title = "Guardada en el móvil",
                    detail = "Se escucha sin internet. Unos 4 MB por canción.",
                ) { offline = true }
            }
        },
    )
}

/** One big radio option: the whole card is the touch target. */
@Composable
private fun ModeOption(selected: Boolean, icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Yes/no question before something that can't be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

fun songCount(n: Int) = if (n == 1) "1 canción" else "$n canciones"

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, total % 3600 / 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

/**
 * The "+" next to each song. Tap: add to the active list. Long press: choose lists in the panel.
 * Shows a check when the song is already in the active list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddToPlaylistButton(inActiveList: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = "Añadir a la lista activa",
                onLongClickLabel = "Elegir listas",
                role = Role.Button,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (inActiveList) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd,
            contentDescription = if (inActiveList) "Ya está en la lista activa" else "Añadir a la lista activa",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * "Añadir a: [Cocina ▾]" above song lists: shows the active list and changes it in two taps.
 */
@Composable
fun ActiveListBar(
    active: Playlist?,
    playlists: List<Playlist>,
    onSelect: (Playlist) -> Unit,
    onCreate: (name: String, offline: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.PlaylistAdd,
            contentDescription = null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text("Añadir a:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { open = true }) {
                Text(active?.name ?: "Elegir lista", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Cambiar lista activa")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                playlists.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            if (p.id == active?.id) Icon(Icons.Rounded.Check, contentDescription = "Activa")
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = { open = false; onSelect(p) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Nueva lista…") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = { open = false; creating = true },
                )
            }
        }
    }
    if (creating) {
        NewPlaylistDialog(onCreate = onCreate, onDismiss = { creating = false })
    }
}

/** One album in a list: cover, title, artist, and an action at the end. */
@Composable
fun AlbumRow(
    album: Album,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) = CoverRow(album.thumbnailUrl, album.title, subtitle, onClick, trailing)

/** A square cover, a title, a subtitle and an action at the end: albums and public playlists. */
@Composable
fun CoverRow(
    thumbnailUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(thumbnailUrl, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}

/** "+" for albums: add to the Albumteca; a check once it is there. */
@Composable
fun AddToAlbumtecaButton(inAlbumteca: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (inAlbumteca) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
            contentDescription = if (inAlbumteca) "Ya está en la Albumteca" else "Añadir a la Albumteca",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** One artist in search results: round photo and name. */
@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artist.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Artista", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A section title inside a scrolling list ("Relacionadas", "Álbumes"…). */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Top bar of a pushed screen: back arrow, title, optional actions. */
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

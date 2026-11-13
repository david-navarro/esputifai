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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.personal.music.data.OnlinePlaylist
import dev.personal.music.data.Track
import dev.personal.music.data.YouTubeMusic

/**
 * A public YouTube Music playlist found in Buscar → "Listas". It is only browsed and played from
 * YouTube; "Guardar en Mis listas" copies its songs into a new local streaming list, which from
 * then on is the user's own (edit, save on the phone, share, sync) and never follows the original.
 */
@Composable
fun OnlinePlaylistScreen(
    playlist: OnlinePlaylist,
    loadTracks: suspend (OnlinePlaylist) -> List<Track>,
    songs: SongActions,
    activeListBar: @Composable () -> Unit,
    /** Name of the local copy made this session, or null if it hasn't been saved. */
    savedAs: String?,
    onBack: () -> Unit,
    onSave: (List<Track>) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val tracks by produceState<Result<List<Track>>?>(null, playlist.id, attempt) {
        value = runCatching { loadTracks(playlist) }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(playlist.title, onBack)
        activeListBar()
        val loaded = tracks
        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.isFailure -> EmptyMessage(
                "No se ha podido abrir la lista",
                "Comprueba que hay internet.",
                Icons.Rounded.CloudOff,
            ) { OutlinedButton(onClick = { attempt++ }) { Text("Reintentar") } }
            else -> {
                val list = loaded.getOrThrow()
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") { Header(playlist, list, songs, savedAs, onSave) }
                    if (list.isEmpty()) {
                        item(key = "empty") { EmptyMessage("Esta lista no tiene canciones que se puedan reproducir") }
                    }
                    songItems(list, songs, keyPrefix = "pl")
                }
            }
        }
    }
}

@Composable
private fun Header(
    playlist: OnlinePlaylist,
    tracks: List<Track>,
    songs: SongActions,
    savedAs: String?,
    onSave: (List<Track>) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(playlist.thumbnailUrl ?: tracks.firstOrNull()?.thumbnailUrl, 96)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (playlist.author.isNotBlank()) {
                    Text(playlist.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOf(songCount(tracks.size), formatDuration(tracks.sumOf { it.durationSec } * 1000))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (tracks.size >= YouTubeMusic.PLAYLIST_MAX_SONGS) {
            Text(
                "Lista larga: se muestran las primeras ${YouTubeMusic.PLAYLIST_MAX_SONGS} canciones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (tracks.isNotEmpty()) {
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
            if (savedAs == null) {
                OutlinedButton(onClick = { onSave(tracks) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Guardar en Mis listas")
                }
            } else {
                StatusLine(Icons.AutoMirrored.Rounded.PlaylistAddCheck, "En Mis listas como «$savedAs»", MaterialTheme.colorScheme.primary)
            }
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

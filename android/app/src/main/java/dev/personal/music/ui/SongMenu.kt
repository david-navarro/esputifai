package dev.personal.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.personal.music.data.Track

/** Where the "Ir al álbum" lookup is. */
enum class AlbumLookup { Idle, Searching, NotFound }

/**
 * Menu of a song, opened by a long press on it anywhere in the app. For now one option, "Ir al
 * álbum", which opens the album page as if it had been searched.
 */
@Composable
fun SongMenuDialog(track: Track, lookup: AlbumLookup, onGoToAlbum: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick = onGoToAlbum,
                    enabled = lookup != AlbumLookup.Searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (lookup == AlbumLookup.Searching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Album, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (lookup == AlbumLookup.Searching) "Buscando el álbum…" else "Ir al álbum")
                    }
                }
                if (lookup == AlbumLookup.NotFound) {
                    Text(
                        "No se ha encontrado el álbum de esta canción (o no hay internet).",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

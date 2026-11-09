package dev.personal.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "Reproduciendo" panel, opened by tapping the player: a seek bar and the queue (jump to a song,
 * move it, take it out). Not a full-screen player (owner's choice): it is a sheet over the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    np: NowPlaying,
    queue: QueueState,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(np.artworkUri?.toString(), 64)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(np.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SeekBar(np.positionMs, np.durationMs, onSeek)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = np.hasPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(36.dp))
                }
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    if (np.isBuffering) {
                        CircularProgressIndicator(Modifier.size(40.dp))
                    } else {
                        FilledIconButton(onClick = onToggle, modifier = Modifier.size(72.dp)) {
                            Icon(
                                if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (np.isPlaying) "Pausa" else "Reproducir",
                                Modifier.size(40.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = onNext, enabled = np.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(36.dp))
                }
            }
        }
        SectionHeader(if (queue.tracks.size == 1) "Cola · 1 canción" else "Cola · ${queue.tracks.size} canciones")
        val listState = rememberLazyListState()
        // Open with the current song at the top, so what comes next is in view.
        LaunchedEffect(Unit) { if (queue.current > 0) listState.scrollToItem(queue.current) }
        LazyColumn(state = listState) {
            itemsIndexed(queue.tracks, key = { i, t -> "$i-${t.videoId}" }) { index, track ->
                TrackRow(track, playing = index == queue.current, onClick = { onPlayItem(index) }) {
                    TrackMenu(
                        canMoveUp = index > 0,
                        canMoveDown = index < queue.tracks.lastIndex,
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        onRemove = { onRemove(index) },
                        removeLabel = "Quitar de la cola",
                    )
                }
            }
        }
    }
}

/** Draggable progress: the position follows the finger and is applied when it is lifted. */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1).toFloat()
    val shown = dragging ?: positionMs.coerceIn(0, durationMs.coerceAtLeast(0)).toFloat()
    Column(Modifier.padding(top = 12.dp)) {
        Slider(
            value = shown.coerceIn(0f, max),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(time(shown.toLong()), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(time(durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun time(ms: Long) = formatDuration(ms).ifEmpty { "0:00" }

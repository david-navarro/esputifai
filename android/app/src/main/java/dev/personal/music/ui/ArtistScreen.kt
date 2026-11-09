package dev.personal.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.personal.music.data.Album
import dev.personal.music.data.Artist

/** How many songs the artist page shows before "Ver todas". */
private const val TOP_SONGS = 5

/**
 * An artist on one page (owner's choice, 2026-11-07): popular songs on top ("Ver todas" shows the
 * rest), albums below. Songs go to playlists with "+", albums to the Albumteca.
 */
@Composable
fun ArtistScreen(
    artist: Artist,
    loadPage: suspend (Artist) -> ArtistPage,
    songs: SongActions,
    activeListBar: @Composable () -> Unit,
    albumtecaIds: Set<String>,
    onBack: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onAddAlbum: (Album) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val page by produceState<Result<ArtistPage>?>(null, artist.channelId, attempt) {
        value = runCatching { loadPage(artist) }
    }
    var allSongs by rememberSaveable(artist.channelId) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(artist.name, onBack)
        val loaded = page
        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.isFailure -> EmptyMessage(
                "No se ha podido abrir el artista",
                "Comprueba que hay internet.",
                Icons.Rounded.CloudOff,
            ) { OutlinedButton(onClick = { attempt++ }) { Text("Reintentar") } }
            else -> {
                val p = loaded.getOrThrow()
                val shown = if (allSongs) p.songs else p.songs.take(TOP_SONGS)
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = artist.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(artist.name, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    item(key = "bar") { activeListBar() }
                    item(key = "songs-header") { SectionHeader("Canciones") }
                    if (p.songs.isEmpty()) {
                        item(key = "no-songs") { Hint("No se han encontrado canciones.") }
                    }
                    // Playing a song queues the songs on screen, from that one on.
                    songItems(shown, songs, keyPrefix = "s")
                    if (p.songs.size > TOP_SONGS) {
                        item(key = "more-songs") {
                            TextButton(onClick = { allSongs = !allSongs }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(if (allSongs) "Ver menos" else "Ver todas (${p.songs.size})")
                            }
                        }
                    }
                    item(key = "albums-header") { SectionHeader("Álbumes") }
                    if (p.albums.isEmpty()) {
                        item(key = "no-albums") { Hint("No se han encontrado álbumes.") }
                    }
                    items(p.albums, key = { "a-${it.id}" }) { a ->
                        AlbumRow(a, a.artist, onClick = { onOpenAlbum(a) }) {
                            AddToAlbumtecaButton(a.id in albumtecaIds) { onAddAlbum(a) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
)

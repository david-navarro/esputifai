package dev.personal.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.personal.music.data.Album
import dev.personal.music.data.Artist
import dev.personal.music.data.Track

/** What every song row needs to know and do; shared by search, album and artist screens. */
class SongActions(
    val playingId: String?,
    val activeIds: Set<String>,
    val onPlay: (List<Track>, Int) -> Unit,
    val onAdd: (Track) -> Unit,
    val onChooseLists: (Track) -> Unit,
)

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    kind: SearchKind,
    onKindChange: (SearchKind) -> Unit,
    state: SearchState,
    songs: SongActions,
    albumtecaIds: Set<String>,
    activeListBar: @Composable () -> Unit,
    onSearch: (String) -> Unit,
    onMoreRelated: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onAddAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SearchField(query, onQueryChange, onSearch)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SearchKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = k == kind,
                    onClick = { onKindChange(k) },
                    shape = SegmentedButtonDefaults.itemShape(i, SearchKind.entries.size),
                ) { Text(k.label) }
            }
        }
        if (kind != SearchKind.Artists) activeListBar()
        Box(Modifier.fillMaxSize()) {
            when (state) {
                SearchState.Idle -> EmptyMessage(
                    when (kind) {
                        SearchKind.Songs -> "Busca una canción"
                        SearchKind.Albums -> "Busca un álbum"
                        SearchKind.Artists -> "Busca un artista"
                    },
                    "Escribe arriba y pulsa buscar en el teclado.",
                    Icons.Rounded.Search,
                )
                SearchState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SearchState.Error -> EmptyMessage(
                    "No se ha podido buscar",
                    "Comprueba que hay internet y vuelve a intentarlo.\n(${state.message})",
                    Icons.Rounded.WifiOff,
                )
                is SearchState.Songs ->
                    if (state.tracks.isEmpty()) NoResults()
                    else LazyColumn(Modifier.fillMaxSize()) {
                        songItems(state.tracks, songs, keyPrefix = "r")
                        relatedSection(state.related, songs, onMoreRelated)
                    }
                is SearchState.Albums ->
                    if (state.albums.isEmpty()) NoResults()
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(state.albums, key = { it.id }) { a ->
                            AlbumRow(a, a.artist, onClick = { onOpenAlbum(a) }) {
                                AddToAlbumtecaButton(a.id in albumtecaIds) { onAddAlbum(a) }
                            }
                        }
                    }
                is SearchState.Artists ->
                    if (state.artists.isEmpty()) NoResults()
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(state.artists, key = { it.channelId }) { a -> ArtistRow(a) { onOpenArtist(a) } }
                    }
            }
        }
    }
}

/** Song rows with "+"; tapping one plays [tracks] from there. */
fun LazyListScope.songItems(tracks: List<Track>, songs: SongActions, keyPrefix: String) {
    itemsIndexed(tracks, key = { _, t -> "$keyPrefix-${t.videoId}" }) { index, track ->
        TrackRow(track, track.videoId == songs.playingId, onClick = { songs.onPlay(tracks, index) }) {
            AddToPlaylistButton(
                inActiveList = track.videoId in songs.activeIds,
                onClick = { songs.onAdd(track) },
                onLongClick = { songs.onChooseLists(track) },
            )
        }
    }
}

/** "Relacionadas" with the first result, 10 at a time. */
private fun LazyListScope.relatedSection(related: RelatedState, songs: SongActions, onMore: () -> Unit) {
    if (related.tracks.isEmpty() && !related.loading && !related.error) return
    item(key = "related-header") { SectionHeader("Relacionadas") }
    songItems(related.tracks, songs, keyPrefix = "rel")
    item(key = "related-footer") {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                related.loading -> CircularProgressIndicator()
                related.error -> OutlinedButton(onClick = onMore) { Text("No se han podido cargar · Reintentar") }
                related.hasMore -> OutlinedButton(onClick = onMore) { Text("Más relacionadas") }
            }
        }
    }
}

@Composable
private fun NoResults() =
    EmptyMessage("No hay resultados", "Prueba a escribirlo de otra forma.", Icons.Rounded.SearchOff)

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Canciones, álbumes, artistas…") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Clear, "Borrar") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch(query)
            focus.clearFocus()
        }),
    )
}

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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.personal.music.data.Album
import dev.personal.music.data.Artist
import dev.personal.music.data.OnlinePlaylist
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
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
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
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        SearchField(query, onQueryChange, onSearch, Modifier.onFocusChanged { typing = it.isFocused })
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SearchKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = k == kind,
                    onClick = { onKindChange(k) },
                    shape = SegmentedButtonDefaults.itemShape(i, SearchKind.entries.size),
                    // No check mark: four labels must fit on a phone; the fill shows the choice.
                    icon = {},
                ) { Text(k.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        // While typing, suggestions take the place of the results.
        if (typing && query.isNotBlank() && suggestions.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions, key = { it }) { s ->
                    SuggestionRow(s, onPick = {
                        focus.clearFocus()
                        onSuggestion(s)
                    }, onFill = { onQueryChange("$s ") })
                }
            }
            return@Column
        }
        // Only where songs can be added with "+" straight from the results.
        if (kind == SearchKind.Songs || kind == SearchKind.Albums) activeListBar()
        Box(Modifier.fillMaxSize()) {
            when (state) {
                SearchState.Idle -> EmptyMessage(
                    when (kind) {
                        SearchKind.Songs -> "Busca una canción"
                        SearchKind.Albums -> "Busca un álbum"
                        SearchKind.Artists -> "Busca un artista"
                        SearchKind.Playlists -> "Busca una lista"
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
                is SearchState.Playlists ->
                    if (state.playlists.isEmpty()) NoResults()
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(state.playlists, key = { it.id }) { p ->
                            CoverRow(p.thumbnailUrl, p.title, playlistSubtitle(p), onClick = { onOpenPlaylist(p) }) {
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

/** "Lista · Warner Music Spain · 50 canciones" (YouTube often gives no song count). */
fun playlistSubtitle(p: OnlinePlaylist) = listOfNotNull(
    "Lista",
    p.author.ifBlank { null },
    p.songCount.takeIf { it > 0 }?.let { songCount(it.toInt()) },
).joinToString(" · ")

@Composable
private fun NoResults() =
    EmptyMessage("No hay resultados", "Prueba a escribirlo de otra forma.", Icons.Rounded.SearchOff)

/** A suggestion: tap searches it; the arrow copies it into the box to keep typing. */
@Composable
private fun SuggestionRow(text: String, onPick: () -> Unit, onFill: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, "Completar sin buscar") }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Canciones, álbumes, artistas, listas…") },
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

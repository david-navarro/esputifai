package dev.personal.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.personal.music.data.Album
import dev.personal.music.data.Ranked
import dev.personal.music.data.TopArtist

/** "Top": what the user listens to most, as songs, artists or albums. */
@Composable
fun TopScreen(
    top: TopLists,
    kind: TopKind,
    onKindChange: (TopKind) -> Unit,
    songs: SongActions,
    activeListBar: @Composable () -> Unit,
    onOpenArtist: (TopArtist) -> Unit,
    onOpenAlbum: (Album) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Lo más escuchado",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TopKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = k == kind,
                    onClick = { onKindChange(k) },
                    shape = SegmentedButtonDefaults.itemShape(i, TopKind.entries.size),
                ) { Text(k.label) }
            }
        }
        if (kind == TopKind.Songs) activeListBar()
        val empty = when (kind) {
            TopKind.Songs -> top.songs.isEmpty()
            TopKind.Artists -> top.artists.isEmpty()
            TopKind.Albums -> top.albums.isEmpty()
        }
        if (empty) {
            EmptyMessage(
                "Aún no hay nada",
                when (kind) {
                    TopKind.Albums -> "Aquí salen los álbumes de tu Albumteca y los que escuches desde su página."
                    else -> "Lo que escuches aparecerá aquí, de más a menos escuchado."
                },
                Icons.Rounded.EmojiEvents,
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            when (kind) {
                TopKind.Songs -> songItems(top.songs.map { it.item }, songs, keyPrefix = "top")
                TopKind.Artists -> items(top.artists, key = { it.item.name }) { TopArtistRow(it) { onOpenArtist(it.item) } }
                TopKind.Albums -> items(top.albums, key = { it.item.id }) { r ->
                    AlbumRow(r.item, "${r.item.artist} · ${timesPlayed(r.plays)}", onClick = { onOpenAlbum(r.item) })
                }
            }
        }
    }
}

@Composable
private fun TopArtistRow(ranked: Ranked<TopArtist>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ranked.item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ranked.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                timesPlayed(ranked.plays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun timesPlayed(n: Int) = if (n == 1) "1 reproducción" else "$n reproducciones"

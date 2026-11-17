package pro.noodle.mobile.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.recommendations.RelatedVideosRepository

@Composable
internal fun RelatedVideosSection(
    currentVideo: VideoItem,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onOpen: (VideoItem) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { RelatedVideosRepository(context.applicationContext) }
    var loading by remember(currentVideo.id, currentVideo.watchUrl) { mutableStateOf(true) }
    var error by remember(currentVideo.id, currentVideo.watchUrl) { mutableStateOf("") }
    var items by remember(currentVideo.id, currentVideo.watchUrl) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var shown by remember(currentVideo.id, currentVideo.watchUrl) { mutableStateOf(12) }

    LaunchedEffect(currentVideo.id, currentVideo.watchUrl) {
        loading = true
        error = ""
        // Give Media3 the network first. Related cards start shortly after playback preparation.
        delay(1_200)
        runCatching {
            repository.loadProgressive(currentVideo) { partial ->
                items = partial
                if (partial.isNotEmpty()) loading = false
            }
        }.onSuccess { items = it }.onFailure { error = it.message.orEmpty() }
        loading = false
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Похожие видео",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row {
                    IconButton(onClick = { onColumnsChange(1) }) {
                        Icon(
                            Icons.Outlined.ViewList,
                            "1 колонка",
                            tint = if (columns == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onColumnsChange(2) }) {
                        Icon(
                            Icons.Outlined.GridView,
                            "2 колонки",
                            tint = if (columns != 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val visible = items.take(shown)
        if (loading && items.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (items.isEmpty()) {
            Text(
                if (error.isBlank()) "Похожих видео пока нет" else "Не удалось загрузить похожие видео",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (columns == 1) {
            visible.forEach { video ->
                RelatedListCard(video, repository, onOpen)
            }
        } else {
            visible.chunked(2).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { video ->
                        Box(Modifier.weight(1f)) {
                            RelatedGridCard(video, repository, onOpen)
                        }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (loading && items.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (items.size > shown) {
            OutlinedButton(onClick = { shown += 12 }, modifier = Modifier.fillMaxWidth()) {
                Text("Показать ещё (${items.size - shown})")
            }
        }
    }
}

@Composable
private fun RelatedGridCard(
    video: VideoItem,
    repository: RelatedVideosRepository,
    onOpen: (VideoItem) -> Unit,
) {
    Card(
        onClick = { onOpen(video) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            RelatedPreview(video, repository, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    video.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    video.sourceName.ifBlank { "Источник" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (video.views.isNotBlank()) {
                    Text(
                        video.views,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedListCard(
    video: VideoItem,
    repository: RelatedVideosRepository,
    onOpen: (VideoItem) -> Unit,
) {
    Card(
        onClick = { onOpen(video) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RelatedPreview(video, repository, Modifier.width(152.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (video.views.isNotBlank()) {
                    Text(video.views, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RelatedPreview(
    video: VideoItem,
    repository: RelatedVideosRepository,
    modifier: Modifier,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = video.thumbnailUrl) {
        value = repository.loadThumbnail(video)
    }
    Box(modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = .45f),
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PlayCircle, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (video.duration.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                color = Color.Black.copy(alpha = .76f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    video.duration,
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

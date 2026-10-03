package pro.noodle.mobile.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.ui.player.PlayerScreen

private enum class DurationFilter(val title: String) {
    ANY("Любая"), SHORT("< 5 мин"), MEDIUM("5–20 мин"), LONG("> 20 мин")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoodleSearchScreen(vm: SearchViewModel) {
    val state by vm.state.collectAsState()
    state.playback?.let { session ->
        val client = remember(session.sourceKey, session.video.id) { vm.streamingClient(session) }
        PlayerScreen(session, client, vm::closePlayer)
        return
    }

    var filterSheet by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var durationFilter by remember { mutableStateOf(DurationFilter.ANY) }

    val sourceNames = remember(state.items) { state.items.map { it.sourceName }.filter { it.isNotBlank() }.distinct() }
    val visibleItems = remember(state.items, sourceFilter, durationFilter) {
        state.items.filter { item ->
            (sourceFilter == null || item.sourceName == sourceFilter) && matchesDuration(item.duration, durationFilter)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Искать по всем источникам…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            FilledTonalIconButton(onClick = { filterSheet = true }) {
                Icon(Icons.Outlined.FilterList, "Фильтры")
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterChip(
                selected = sourceFilter == null,
                onClick = { sourceFilter = null },
                label = { Text("Все") },
            )
            sourceNames.take(4).forEach { source ->
                FilterChip(
                    selected = sourceFilter == source,
                    onClick = { sourceFilter = if (sourceFilter == source) null else source },
                    label = { Text(shortSourceName(source), maxLines = 1) },
                )
            }
            if (sourceNames.size > 4) AssistChip(onClick = { filterSheet = true }, label = { Text("+${sourceNames.size - 4}") })
        }

        Button(
            onClick = vm::search,
            enabled = !state.loading && state.query.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Outlined.Search, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.loading) "Ищем…" else "Найти")
        }

        if (state.loading || state.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                color = if (state.message.contains(":")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.errors.isNotEmpty()) {
            Text(
                "Недоступно источников: ${state.errors.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        if (visibleItems.isEmpty() && state.items.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("По выбранным фильтрам ничего нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleItems, key = { it.id }) { video ->
                    SearchGridCard(video, vm)
                }
            }
        }
    }

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Фильтры", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Источники", style = MaterialTheme.typography.titleMedium)
                FlowChips(sourceNames, sourceFilter) { sourceFilter = it }
                Text("Длительность", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DurationFilter.entries.forEach { f ->
                        FilterChip(selected = durationFilter == f, onClick = { durationFilter = f }, label = { Text(f.title) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { sourceFilter = null; durationFilter = DurationFilter.ANY },
                        modifier = Modifier.weight(1f),
                    ) { Text("Сброс") }
                    Button(onClick = { filterSheet = false }, modifier = Modifier.weight(1f)) { Text("Применить") }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun FlowChips(sources: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Все источники") })
        sources.forEach { source ->
            FilterChip(
                selected = selected == source,
                onClick = { onSelect(if (selected == source) null else source) },
                label = { Text(source) },
            )
        }
    }
}

@Composable
private fun SearchGridCard(video: VideoItem, vm: SearchViewModel) {
    ElevatedCard(onClick = { vm.open(video) }, modifier = Modifier.fillMaxWidth()) {
        Column {
            val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = video.thumbnailUrl) {
                value = vm.loadThumbnail(video)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PlayCircle, null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (video.duration.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                        color = MaterialTheme.colorScheme.background.copy(alpha = .82f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(video.duration, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            shortSourceName(video.sourceName),
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                    if (video.views.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(video.views, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun shortSourceName(value: String): String {
    val v = value.trim()
    return when {
        v.length <= 15 -> v
        v.contains('.') -> v.substringBefore('.').take(15)
        else -> v.take(15)
    }
}

private fun matchesDuration(raw: String, filter: DurationFilter): Boolean {
    if (filter == DurationFilter.ANY) return true
    val seconds = parseDurationSeconds(raw) ?: return true
    return when (filter) {
        DurationFilter.ANY -> true
        DurationFilter.SHORT -> seconds < 5 * 60
        DurationFilter.MEDIUM -> seconds in 5 * 60..20 * 60
        DurationFilter.LONG -> seconds > 20 * 60
    }
}

private fun parseDurationSeconds(raw: String): Int? {
    val parts = raw.trim().split(':').mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return null
    return when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        else -> parts.takeLast(3).let { it[0] * 3600 + it[1] * 60 + it[2] }
    }
}

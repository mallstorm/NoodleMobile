package pro.noodle.mobile.ui.search

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.outlined.Search
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.version.BuildIdentity
import pro.noodle.mobile.ui.player.PlayerScreen

private enum class DurationFilter(val title: String) {
    ANY("Все"), SHORT("Короткие"), MEDIUM("Средние"), LONG("Длинные")
}

private enum class ResultSort(val title: String) {
    SOURCE_ORDER("По выдаче"), VIEWS("По просмотрам"), DURATION("По длительности")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoodleSearchScreen(
    vm: SearchViewModel,
    onOpenSettings: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    state.playback?.let { session ->
        val client = remember(session.sourceKey, session.video.id) { vm.streamingClient(session) }
        PlayerScreen(session, client, vm::closePlayer)
        return
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val focus = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var filterSheet by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var durationFilter by remember { mutableStateOf(DurationFilter.ANY) }
    var sort by remember { mutableStateOf(ResultSort.SOURCE_ORDER) }
    var sortMenu by remember { mutableStateOf(false) }
    var gridMode by remember { mutableStateOf(true) }

    val sourceNames = remember(state.items) {
        state.items.map { it.sourceName }.filter { it.isNotBlank() }.distinct()
    }
    val visibleItems = remember(state.items, sourceFilter, durationFilter, sort) {
        val filtered = state.items.filter { item ->
            (sourceFilter == null || item.sourceName == sourceFilter) && matchesDuration(item.duration, durationFilter)
        }
        when (sort) {
            ResultSort.SOURCE_ORDER -> filtered
            ResultSort.VIEWS -> filtered.sortedByDescending { parseViewsScore(it.views) }
            ResultSort.DURATION -> filtered.sortedByDescending { parseDurationSeconds(it.duration) ?: 0 }
        }
    }

    // Infinite scroll: request another page burst before the user actually reaches the end.
    // SearchViewModel deduplicates pages and BundledSearchRepository caps all network work at 6.
    LaunchedEffect(gridMode, visibleItems.size, state.hasMore, state.loadingMore, landscape) {
        snapshotFlow {
            if (gridMode) gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            else listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val threshold = if (landscape && gridMode) 12 else 6
                if (visibleItems.isNotEmpty() && lastVisible >= visibleItems.lastIndex - threshold) {
                    vm.loadMore()
                }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (landscape) 10.dp else 14.dp, vertical = if (landscape) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (landscape) 6.dp else 11.dp),
    ) {
        if (!landscape) {
            NoodleHeader(
                errorCount = state.errors.size,
                onStatusClick = { errorDialog = true },
                onSettingsClick = onOpenSettings,
            )
        }

        if (landscape) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SearchField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    onSearch = {
                        focus.clearFocus()
                        vm.search()
                    },
                    onFilters = { filterSheet = true },
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                )

                Box {
                    FilledTonalIconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Outlined.SwapVert, "Сортировка")
                    }
                    SortMenu(sort = sort, expanded = sortMenu, onDismiss = { sortMenu = false }) {
                        sort = it
                        sortMenu = false
                    }
                }

                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row {
                        IconButton(onClick = { gridMode = true }) {
                            Icon(
                                Icons.Outlined.GridView,
                                "Сетка",
                                tint = if (gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { gridMode = false }) {
                            Icon(
                                Icons.Outlined.ViewList,
                                "Список",
                                tint = if (!gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            SearchField(
                value = state.query,
                onValueChange = vm::setQuery,
                onSearch = {
                    focus.clearFocus()
                    vm.search()
                },
                onFilters = { filterSheet = true },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DurationFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = durationFilter == filter && sourceFilter == null,
                        onClick = {
                            durationFilter = filter
                            if (filter != DurationFilter.ANY) sourceFilter = null
                        },
                        label = { Text(filter.title) },
                    )
                }
                sourceNames.take(3).forEach { source ->
                    FilterChip(
                        selected = sourceFilter == source,
                        onClick = {
                            sourceFilter = if (sourceFilter == source) null else source
                            if (sourceFilter != null) durationFilter = DurationFilter.ANY
                        },
                        label = { Text(shortSourceName(source), maxLines = 1) },
                    )
                }
                if (sourceNames.size > 3) {
                    AssistChip(onClick = { filterSheet = true }, label = { Text("+${sourceNames.size - 3}") })
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.items.isEmpty()) "Поиск по подключённым источникам" else "Найдено: ${visibleItems.size} видео",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                Box {
                    OutlinedButton(
                        onClick = { sortMenu = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(sort.title, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                    }
                    SortMenu(sort = sort, expanded = sortMenu, onDismiss = { sortMenu = false }) {
                        sort = it
                        sortMenu = false
                    }
                }

                Spacer(Modifier.width(7.dp))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row {
                        IconButton(onClick = { gridMode = true }) {
                            Icon(
                                Icons.Outlined.GridView,
                                "Сетка",
                                tint = if (gridMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { gridMode = false }) {
                            Icon(
                                Icons.Outlined.ViewList,
                                "Список",
                                tint = if (!gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.loading || state.resolving || state.loadingMore) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                color = if (state.message.contains(":")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.items.isEmpty() && !state.loading) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    Text(
                        if (state.query.isBlank()) "Что будем смотреть?" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (state.query.isBlank()) "Введите запрос в строку сверху и нажмите лупу или «Поиск» на клавиатуре. Поиск идёт сразу по всем включённым сайтам."
                        else "Попробуйте другой запрос или проверьте источники.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    FilledTonalButton(onClick = onOpenSettings) { Text("Добавить сайт") }
                }
            }
        } else if (visibleItems.isEmpty() && state.items.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("По выбранным фильтрам ничего нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (gridMode) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (landscape) 4 else 2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 11.dp),
            ) {
                items(visibleItems, key = { it.id }) { video ->
                    SearchGridCard(video, vm)
                }
                if (state.loadingMore) {
                    item(key = "paging-progress") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(visibleItems.size, key = { visibleItems[it].id }) { index ->
                    SearchListCard(visibleItems[index], vm)
                }
                if (state.loadingMore) {
                    item(key = "paging-progress") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
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
                FilterChip(
                    selected = sourceFilter == null,
                    onClick = { sourceFilter = null },
                    label = { Text("Все источники") },
                )
                sourceNames.forEach { source ->
                    FilterChip(
                        selected = sourceFilter == source,
                        onClick = { sourceFilter = if (sourceFilter == source) null else source },
                        label = { Text(source) },
                    )
                }
                Text("Длительность", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DurationFilter.entries.forEach { f ->
                        FilterChip(
                            selected = durationFilter == f,
                            onClick = { durationFilter = f },
                            label = { Text(f.title) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            sourceFilter = null
                            durationFilter = DurationFilter.ANY
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Сброс") }
                    Button(onClick = { filterSheet = false }, modifier = Modifier.weight(1f)) { Text("Применить") }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (errorDialog) {
        AlertDialog(
            onDismissRequest = { errorDialog = false },
            confirmButton = {
                TextButton(onClick = { errorDialog = false }) { Text("Закрыть") }
            },
            title = { Text("Состояние источников") },
            text = {
                if (state.errors.isEmpty()) {
                    Text("Ошибок источников в текущем поиске нет.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.errors.take(6).forEach { e ->
                            Text("${e.sourceName}: ${e.errorCode}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFilters: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text("Поиск по всем источникам") },
        leadingIcon = {
            IconButton(onClick = onSearch, enabled = enabled && value.isNotBlank()) {
                Icon(
                    Icons.Outlined.Search,
                    "Найти",
                    tint = if (value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Outlined.Close, "Очистить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onFilters) {
                    Icon(Icons.Outlined.Tune, "Фильтры", tint = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun SortMenu(
    sort: ResultSort,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ResultSort) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ResultSort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.title) },
                onClick = { onSelect(option) },
                leadingIcon = { if (sort == option) Icon(Icons.Outlined.Check, null) },
            )
        }
    }
}

@Composable
private fun NoodleHeader(
    errorCount: Int,
    onStatusClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Noodle",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Mobile",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        BuildIdentity.VERSION_NAME,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Все источники. Больше контента.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BadgedBox(
            badge = {
                if (errorCount > 0) Badge { Text(errorCount.coerceAtMost(9).toString()) }
            },
        ) {
            FilledTonalIconButton(onClick = onStatusClick) {
                Icon(Icons.Outlined.NotificationsNone, "Состояние источников")
            }
        }
        Spacer(Modifier.width(6.dp))
        FilledTonalIconButton(onClick = onSettingsClick) {
            Icon(Icons.Outlined.Settings, "Настройки")
        }
    }
}

@Composable
private fun SearchGridCard(video: VideoItem, vm: SearchViewModel) {
    ElevatedCard(
        onClick = { vm.open(video) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            SearchPreview(video, vm, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    color = sourceColor(video.sourceName).copy(alpha = .18f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        shortSourceName(video.sourceName).ifBlank { "Источник" },
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = sourceColor(video.sourceName),
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (video.views.isNotBlank()) {
                        Icon(Icons.Outlined.Visibility, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(video.views, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.addFavorite(video) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.FavoriteBorder, "В избранное", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchListCard(video: VideoItem, vm: SearchViewModel) {
    ElevatedCard(
        onClick = { vm.open(video) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchPreview(video, vm, modifier = Modifier.width(150.dp).aspectRatio(16f / 10f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(shortSourceName(video.sourceName), color = sourceColor(video.sourceName), style = MaterialTheme.typography.labelMedium)
                if (video.views.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Visibility, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(video.views, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = { vm.addFavorite(video) }) {
                Icon(Icons.Outlined.FavoriteBorder, "В избранное")
            }
        }
    }
}

@Composable
private fun SearchPreview(video: VideoItem, vm: SearchViewModel, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = video.thumbnailUrl) {
        value = vm.loadThumbnail(video)
    }
    val source = sourceColor(video.sourceName)
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
                                source.copy(alpha = .62f),
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
            color = Color.Black.copy(alpha = .28f),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        ) {
            Text(
                "VIDEO",
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Bold,
            )
        }

        IconButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
        ) {
            Icon(Icons.Outlined.MoreVert, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
        }

        if (video.duration.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp),
                color = Color.Black.copy(alpha = .76f),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    video.duration,
                    Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun sourceColor(source: String): Color {
    val values = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    )
    return values[(source.hashCode() and Int.MAX_VALUE) % values.size]
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

private fun parseViewsScore(raw: String): Double {
    val normalized = raw.lowercase().replace(',', '.').replace(" ", "")
    val number = Regex("[0-9]+(?:\\.[0-9]+)?").find(normalized)?.value?.toDoubleOrNull() ?: return 0.0
    val multiplier = when {
        "млрд" in normalized || "b" in normalized -> 1_000_000_000.0
        "млн" in normalized || "m" in normalized -> 1_000_000.0
        "тыс" in normalized || "k" in normalized -> 1_000.0
        else -> 1.0
    }
    return number * multiplier
}

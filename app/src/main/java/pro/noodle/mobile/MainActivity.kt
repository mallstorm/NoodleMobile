package pro.noodle.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.noodle.mobile.core.selftest.CoreSelfTest
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.version.BuildIdentity
import pro.noodle.mobile.ui.NoodleTheme
import pro.noodle.mobile.ui.SettingsViewModel
import pro.noodle.mobile.ui.downloads.DownloadsViewModel
import pro.noodle.mobile.ui.favorites.FavoritesViewModel
import pro.noodle.mobile.ui.history.HistoryScreen
import pro.noodle.mobile.ui.history.HistoryViewModel
import pro.noodle.mobile.ui.player.OfflinePlayerScreen
import pro.noodle.mobile.ui.player.PlayerScreen
import pro.noodle.mobile.ui.search.NoodleSearchScreen
import pro.noodle.mobile.ui.search.SearchViewModel
import pro.noodle.mobile.ui.security.AppLockGate
import pro.noodle.mobile.ui.security.AppLockSettingsCard
import pro.noodle.mobile.ui.sources.SourceCenterScreen

class MainActivity : ComponentActivity() {
    private var relockCallback: (() -> Unit)? = null

    override fun onStop() {
        super.onStop()
        relockCallback?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: SettingsViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            val settingsLoaded by vm.settingsLoaded.collectAsState()
            var lockInitialized by remember { mutableStateOf(false) }
            var unlocked by remember { mutableStateOf(false) }

            LaunchedEffect(settingsLoaded) {
                if (settingsLoaded && !lockInitialized) {
                    unlocked = settings.lockMode == "none"
                    lockInitialized = true
                }
            }
            LaunchedEffect(settings.lockMode) {
                if (settings.lockMode == "none") unlocked = true
            }
            DisposableEffect(settings.lockMode) {
                relockCallback = if (settings.lockMode == "none") null else ({ unlocked = false })
                onDispose { relockCallback = null }
            }

            NoodleTheme(settings.theme) {
                if (!settingsLoaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val baseDensity = LocalDensity.current
                    val scale = 0.90f * (settings.uiScalePercent / 100f)
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = baseDensity.density * scale,
                            fontScale = baseDensity.fontScale * scale,
                        ),
                    ) {
                        if (settings.lockMode == "none" || unlocked) {
                            NoodleApp(vm)
                        } else {
                            AppLockGate(settings) { unlocked = true }
                        }
                    }
                }
            }
        }
    }
}

enum class Section(val title: String) {
    SEARCH("Поиск"),
    DOWNLOADS("Загрузки"),
    FAVORITES("Избранное"),
    HISTORY("История"),
    MORE("Ещё"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoodleApp(settingsVm: SettingsViewModel) {
    var section by remember { mutableStateOf(Section.SEARCH) }
    val searchVm: SearchViewModel = viewModel()
    val favoritesVm: FavoritesViewModel = viewModel()
    val downloadsVm: DownloadsViewModel = viewModel()
    val historyVm: HistoryViewModel = viewModel()

    val searchState by searchVm.state.collectAsState()
    val favoritesState by favoritesVm.state.collectAsState()
    val historyState by historyVm.state.collectAsState()
    val offlinePlayback by downloadsVm.offlinePlayback.collectAsState()
    val playerActive =
        (section == Section.SEARCH && searchState.playback != null) ||
            (section == Section.FAVORITES && favoritesState.playback != null) ||
            (section == Section.HISTORY && historyState.playback != null) ||
            (section == Section.DOWNLOADS && offlinePlayback != null)

    Scaffold(
        topBar = {
            if (!playerActive && section != Section.SEARCH) {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("NoodleMobile", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                BuildIdentity.STAGE_LABEL,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!playerActive) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Section.entries.forEach { s ->
                        NavigationBarItem(
                            selected = section == s,
                            onClick = { section = s },
                            icon = { Icon(iconFor(s), null) },
                            label = { Text(s.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(if (playerActive) PaddingValues(0.dp) else pad).fillMaxSize()) {
            when (section) {
                Section.SEARCH -> NoodleSearchScreen(
                    vm = searchVm,
                    onOpenSettings = { section = Section.MORE },
                )
                Section.DOWNLOADS -> DownloadsScreen(downloadsVm)
                Section.FAVORITES -> FavoritesScreen(favoritesVm)
                Section.HISTORY -> HistoryScreen(historyVm)
                Section.MORE -> MoreScreen(settingsVm)
            }
        }
    }
}

private fun iconFor(s: Section) = when (s) {
    Section.SEARCH -> Icons.Outlined.Search
    Section.DOWNLOADS -> Icons.Outlined.Download
    Section.FAVORITES -> Icons.Outlined.FavoriteBorder
    Section.HISTORY -> Icons.Outlined.History
    Section.MORE -> Icons.Outlined.MoreHoriz
}

@Composable
private fun FavoritesScreen(vm: FavoritesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    state.playback?.let { s ->
        val c = remember(s.sourceKey, s.video.id) { vm.streamingClient(s) }
        PlayerScreen(s, c, vm::closePlayer)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Избранное", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Сохранённые ролики доступны здесь в любой момент",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        if (state.items.isEmpty()) {
            item {
                Text(
                    "Пока пусто. Добавьте видео кнопкой ♥ в плеере.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.items, key = { it.favoriteKey }) { v ->
            ElevatedCard(onClick = { vm.open(v) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(
                            v.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.remove(v) }) { Icon(Icons.Outlined.Delete, "Удалить") }
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(vm: DownloadsViewModel) {
    val list by vm.items.collectAsState()
    val library by vm.completed.collectAsState()
    val offline by vm.offlinePlayback.collectAsState()
    val message by vm.message.collectAsState()
    if (offline != null) {
        OfflinePlayerScreen(offline!!, vm::closeOffline)
        return
    }
    var mode by remember { mutableStateOf(0) }
    val shown = when (mode) {
        1 -> list.filter {
            it.state == DownloadState.RUNNING ||
                it.state == DownloadState.QUEUED ||
                it.state == DownloadState.PAUSED
        }
        2 -> library
        else -> list
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Загрузки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Офлайн-библиотека и текущие загрузки",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Все") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("В процессе") })
                FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("Готово (${library.size})") })
            }
            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (shown.isEmpty()) item { Text("Здесь пока пусто", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.id }) { d ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Download,
                            null,
                            tint = when (d.state) {
                                DownloadState.COMPLETED -> MaterialTheme.colorScheme.secondary
                                DownloadState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(
                                listOf(
                                    d.protocol.takeIf { it.isNotBlank() },
                                    d.videoSelectionLabel.takeIf { it.isNotBlank() },
                                ).filterNotNull().joinToString(" • ").ifBlank { d.state.name },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (d.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { d.downloadedBytes.toFloat() / d.totalBytes.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (d.state == DownloadState.RUNNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        "${bytes(d.downloadedBytes)} / ${if (d.totalBytes > 0) bytes(d.totalBytes) else "?"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (d.errorMessage.isNotBlank()) {
                        Text(
                            "${d.errorCode}: ${d.errorMessage}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row {
                        if (d.state == DownloadState.COMPLETED) {
                            IconButton(onClick = { vm.openOffline(d.id) }) {
                                Icon(Icons.Outlined.PlayCircle, "Открыть офлайн")
                            }
                        }
                        when (d.state) {
                            DownloadState.RUNNING, DownloadState.QUEUED -> IconButton(onClick = { vm.pause(d.id) }) {
                                Icon(Icons.Outlined.Pause, "Пауза")
                            }
                            DownloadState.PAUSED, DownloadState.FAILED -> IconButton(onClick = { vm.resume(d.id) }) {
                                Icon(Icons.Outlined.PlayArrow, "Продолжить")
                            }
                            else -> Unit
                        }
                        if (d.state != DownloadState.COMPLETED && d.state != DownloadState.CANCELED) {
                            IconButton(onClick = { vm.cancel(d.id) }) { Icon(Icons.Outlined.Cancel, "Отмена") }
                        }
                        IconButton(onClick = { vm.remove(d.id) }) { Icon(Icons.Outlined.Delete, "Удалить") }
                    }
                }
            }
        }
    }
}

private fun bytes(v: Long): String = when {
    v >= 1024L * 1024 * 1024 -> "%.2f ГБ".format(v / (1024.0 * 1024 * 1024))
    v >= 1024L * 1024 -> "%.1f МБ".format(v / (1024.0 * 1024))
    v >= 1024 -> "%.1f КБ".format(v / 1024.0)
    else -> "$v Б"
}

private enum class MorePage { MENU, SOURCES, SETTINGS }

@Composable
private fun MoreScreen(settingsVm: SettingsViewModel) {
    var page by remember { mutableStateOf(MorePage.MENU) }
    when (page) {
        MorePage.SOURCES -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { page = MorePage.MENU }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Text("Источники (SNP)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f)) { SourceCenterScreen() }
        }
        MorePage.SETTINGS -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { page = MorePage.MENU }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f)) { SettingsScreen(settingsVm) }
        }
        MorePage.MENU -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Ещё", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Источники, диагностика и параметры приложения",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ElevatedCard(onClick = { page = MorePage.SOURCES }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Источники (SNP)") },
                        supportingContent = { Text("Подключённые сайты, Marketplace, Studio и диагностика") },
                        leadingContent = { Icon(Icons.Outlined.Hub, null, tint = MaterialTheme.colorScheme.secondary) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    )
                }
            }
            item {
                ElevatedCard(onClick = { page = MorePage.SETTINGS }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Настройки") },
                        supportingContent = { Text("Тема, масштаб, защита, загрузки и диагностика") },
                        leadingContent = { Icon(Icons.Outlined.Settings, null, tint = MaterialTheme.colorScheme.tertiary) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val diagnosticsMessage by vm.diagnosticsMessage.collectAsState()
    val report = remember { CoreSelfTest.run() }
    val exportDiagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.exportDiagnostics(uri)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Тема", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "noodle_neon" to "Noodle",
                            "blue_glass" to "Blue",
                            "emerald_pulse" to "Emerald",
                            "graphite_pro" to "Graphite",
                            "aurora_light" to "Light",
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = settings.theme == id,
                                onClick = { vm.theme(id) },
                                label = { Text(label) },
                            )
                        }
                    }

                    HorizontalDivider()
                    Text("Масштаб интерфейса: ${settings.uiScalePercent}%", fontWeight = FontWeight.SemiBold)
                    Text(
                        "100% теперь соответствует 90% размера прежнего интерфейса.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.uiScalePercent.toFloat(),
                        onValueChange = { raw ->
                            val value = when {
                                raw >= 95f -> 100
                                raw >= 85f -> 90
                                else -> 80
                            }
                            vm.uiScale(value)
                        },
                        valueRange = 80f..100f,
                        steps = 1,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("80%", style = MaterialTheme.typography.labelSmall)
                        Text("90%", style = MaterialTheme.typography.labelSmall)
                        Text("100%", style = MaterialTheme.typography.labelSmall)
                    }

                    HorizontalDivider()
                    Text("Параллельных загрузок: ${settings.parallelDownloads}")
                    Slider(
                        settings.parallelDownloads.toFloat(),
                        { vm.parallel(it.toInt()) },
                        valueRange = 1f..3f,
                        steps = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(settings.rememberPlaybackPosition, vm::rememberPosition)
                        Spacer(Modifier.width(8.dp))
                        Text("Запоминать позицию воспроизведения")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(settings.backgroundMonitoring, vm::background)
                        Spacer(Modifier.width(8.dp))
                        Text("Фоновый fingerprint + breakage monitor")
                    }
                    if (settings.backgroundMonitoring) {
                        Text("Интервал мониторинга: ${settings.monitoringIntervalHours} ч")
                        Slider(
                            settings.monitoringIntervalHours.toFloat(),
                            { vm.monitoringHours(it.toInt()) },
                            valueRange = 6f..168f,
                        )
                    }
                }
            }
        }
        item {
            AppLockSettingsCard(settings, vm::setLock)
        }
        item {
            HorizontalDivider()
            Text("Core Self-Test", style = MaterialTheme.typography.titleLarge)
            Text(if (report.passed) "Все проверки ядра пройдены" else "Есть ошибки")
        }
        items(report.checks) { c ->
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = { if (c.detail.isNotBlank()) Text(c.detail) },
                leadingContent = { Icon(if (c.passed) Icons.Outlined.CheckCircle else Icons.Outlined.Error, null) },
            )
        }
        item {
            HorizontalDivider()
            Text("Диагностика", style = MaterialTheme.typography.titleLarge)
            Text(
                "ZIP проходит sanitizer и не включает cookies, Authorization и секретные ключи.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { exportDiagnostics.launch("noodle-mobile-diagnostics.zip") }) {
                Icon(Icons.Outlined.BugReport, null)
                Spacer(Modifier.width(8.dp))
                Text("Экспортировать ZIP")
            }
            if (diagnosticsMessage.isNotBlank()) {
                Text(diagnosticsMessage, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

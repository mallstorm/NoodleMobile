package pro.noodle.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import pro.noodle.mobile.ui.player.PlayerChromeState
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        consumeStudioIntent(intent)
    }

    private fun consumeStudioIntent(intent: android.content.Intent?) {
        val url = intent?.getStringExtra(pro.noodle.mobile.core.maintenance.SourceAlerts.EXTRA_STUDIO_URL).orEmpty()
        if (url.isBlank()) return
        pro.noodle.mobile.ui.sources.StudioRequest.pending = pro.noodle.mobile.ui.sources.StudioRequest.Pending(
            url, intent?.getStringExtra(pro.noodle.mobile.core.maintenance.SourceAlerts.EXTRA_STUDIO_QUERY).orEmpty(),
        )
        intent?.removeExtra(pro.noodle.mobile.core.maintenance.SourceAlerts.EXTRA_STUDIO_URL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeStudioIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 701)
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
    var section by rememberSaveable { mutableStateOf(Section.SEARCH) }
    val studioRequest = pro.noodle.mobile.ui.sources.StudioRequest.pending
    LaunchedEffect(studioRequest) { if (studioRequest != null) section = Section.MORE }
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

    // The bottom app navigation belongs to the portrait site shell only. It disappears for
    // every landscape screen and whenever PlayerScreen explicitly owns the whole display.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playerFullscreen = PlayerChromeState.fullscreen
    val showBottomNavigation = !isLandscape && !playerFullscreen

    fun closeAllPlayback() {
        searchVm.closePlayer()
        favoritesVm.closePlayer()
        historyVm.closePlayer()
        downloadsVm.closeOffline()
        PlayerChromeState.fullscreen = false
    }

    fun navigateTo(target: Section) {
        // Bottom navigation is authoritative: changing a section always tears down any
        // currently open player so no hidden video can continue playing behind another tab.
        closeAllPlayback()
        section = target
    }

    // Android system Back from any player means "leave video now", not "maybe exit fullscreen".
    // Closing the VM playback state disposes PlayerScreen and releases ExoPlayer immediately.
    BackHandler(enabled = playerActive) {
        closeAllPlayback()
    }

    Scaffold(
        topBar = {
            if (!playerActive && section != Section.SEARCH) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Noodle", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            Text("Mobile", fontWeight = FontWeight.Medium)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            if (showBottomNavigation) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Section.entries.forEach { s ->
                        NavigationBarItem(
                            selected = section == s,
                            onClick = { navigateTo(s) },
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
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (section) {
                Section.SEARCH -> NoodleSearchScreen(vm = searchVm)
                Section.DOWNLOADS -> DownloadsScreen(downloadsVm)
                Section.FAVORITES -> FavoritesScreen(favoritesVm)
                Section.HISTORY -> HistoryScreen(historyVm)
                Section.MORE -> MoreScreen(settingsVm)
            }
            if (!playerActive) pro.noodle.mobile.ui.download.BatchBar(Modifier.align(Alignment.BottomCenter)) { section = Section.DOWNLOADS }
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
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(initialValue = null, key1 = v.thumbnailUrl) {
                        value = vm.loadThumbnail(v)
                    }
                    Surface(modifier = Modifier.size(width = 136.dp, height = 82.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.fillMaxSize()) {
                            if (bitmap != null) androidx.compose.foundation.Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.Center).size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Outlined.Favorite, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            pro.noodle.mobile.ui.download.SelectCheck(v, Modifier.align(Alignment.TopStart))
                            if (v.duration.isNotBlank()) Surface(Modifier.align(Alignment.BottomEnd).padding(5.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f)) {
                                Text(v.duration, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(v.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(v.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
    val offline by vm.offlinePlayback.collectAsState()
    val message by vm.message.collectAsState()
    if (offline != null) {
        OfflinePlayerScreen(offline!!, vm::closeOffline)
        return
    }
    var mode by rememberSaveable { mutableStateOf(0) }
    val active = list.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
    val paused = list.filter { it.state == DownloadState.PAUSED }
    val done = list.filter { it.state == DownloadState.COMPLETED }
    val failed = list.filter { it.state == DownloadState.FAILED }
    val shown = when (mode) {
        1 -> active
        2 -> paused
        3 -> done
        4 -> failed
        else -> list
    }
    // Скорость считаем по приросту скачанных байт между обновлениями списка (с лёгким сглаживанием).
    val speeds = remember { mutableStateMapOf<String, Long>() }
    val samples = remember { HashMap<String, Pair<Long, Long>>() }
    LaunchedEffect(list) {
        val now = System.currentTimeMillis()
        list.forEach { d ->
            if (d.state != DownloadState.RUNNING) { samples.remove(d.id); speeds.remove(d.id); return@forEach }
            val prev = samples[d.id]
            if (prev == null) samples[d.id] = d.downloadedBytes to now
            else if (now - prev.second >= 900 && d.downloadedBytes >= prev.first) {
                val inst = (d.downloadedBytes - prev.first) * 1000 / (now - prev.second)
                val old = speeds[d.id] ?: 0L
                speeds[d.id] = if (old <= 0) inst else (old * 2 + inst) / 3
                samples[d.id] = d.downloadedBytes to now
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Все" to list.size, "В процессе" to active.size, "Пауза" to paused.size, "Готово" to done.size, "Ошибки" to failed.size)
                    .forEachIndexed { i, (label, n) ->
                        FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text("$label  $n") })
                    }
            }
            if (active.isNotEmpty() || paused.isNotEmpty() || failed.isNotEmpty()) {
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (active.isNotEmpty()) TextButton(onClick = vm::pauseAll, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Пауза всех") }
                    if (paused.isNotEmpty() || failed.isNotEmpty()) TextButton(onClick = vm::resumeAll, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Продолжить все") }
                    if (failed.isNotEmpty()) TextButton(onClick = vm::clearFinished, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Убрать ошибки") }
                }
            }
            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (shown.isEmpty()) item { Text("Здесь пока пусто", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        items(shown, key = { it.id }) { d ->
            val total = d.totalBytes
            val pct = if (total > 0) (d.downloadedBytes * 100 / total).toInt().coerceIn(0, 100) else -1
            val speed = speeds[d.id] ?: 0L
            val tint = when (d.state) {
                DownloadState.COMPLETED -> MaterialTheme.colorScheme.secondary
                DownloadState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = tint.copy(alpha = .14f), modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    when (d.state) {
                                        DownloadState.COMPLETED -> Icons.Outlined.PlayCircle
                                        DownloadState.PAUSED -> Icons.Outlined.Pause
                                        DownloadState.FAILED -> Icons.Outlined.Cancel
                                        else -> Icons.Outlined.Download
                                    },
                                    null, tint = tint, modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            val st = when (d.state) {
                                DownloadState.RUNNING -> "качается"
                                DownloadState.QUEUED -> "в очереди"
                                DownloadState.PAUSED -> "на паузе"
                                DownloadState.FAILED -> "ошибка"
                                DownloadState.COMPLETED -> "готово"
                                else -> "отменено"
                            }
                            Text(
                                listOf(d.protocol.takeIf { it.isNotBlank() }, d.videoSelectionLabel.takeIf { it.isNotBlank() }, st).filterNotNull().joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (d.state) {
                            DownloadState.COMPLETED -> IconButton(onClick = { vm.openOffline(d.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.PlayArrow, "Открыть офлайн") }
                            DownloadState.RUNNING, DownloadState.QUEUED -> IconButton(onClick = { vm.pause(d.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Pause, "Пауза") }
                            DownloadState.PAUSED, DownloadState.FAILED -> IconButton(onClick = { vm.resume(d.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.PlayArrow, "Продолжить") }
                            else -> Unit
                        }
                        if (d.state != DownloadState.COMPLETED && d.state != DownloadState.CANCELED) {
                            IconButton(onClick = { vm.cancel(d.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Cancel, "Отмена") }
                        }
                        IconButton(onClick = { vm.remove(d.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Delete, "Удалить") }
                    }
                    if (d.state != DownloadState.COMPLETED) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pct >= 0) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.weight(1f))
                            else if (d.state == DownloadState.RUNNING) LinearProgressIndicator(Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                            if (pct >= 0) Text("$pct%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("${bytes(d.downloadedBytes)} / ${if (total > 0) bytes(total) else "?"}", style = MaterialTheme.typography.labelSmall)
                            if (d.state == DownloadState.RUNNING && speed > 0) {
                                Text("↓ ${bytes(speed)}/с", style = MaterialTheme.typography.labelSmall)
                                if (total > d.downloadedBytes) {
                                    val eta = (total - d.downloadedBytes) / speed
                                    Text("≈ " + if (eta >= 3600) "${eta / 3600} ч ${eta % 3600 / 60} мин" else if (eta >= 60) "${eta / 60} мин ${eta % 60} с" else "$eta с", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else {
                        Text(bytes(d.totalBytes.coerceAtLeast(d.downloadedBytes)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.errorMessage.isNotBlank() && d.state != DownloadState.COMPLETED) {
                        Text("${d.errorCode}: ${d.errorMessage}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 3)
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

private enum class MorePage { MENU, SOURCES, STUDIO, FINDER }

@Composable
private fun MoreScreen(settingsVm: SettingsViewModel) {
    var page by rememberSaveable { mutableStateOf(MorePage.MENU) }
    val studioRequest = pro.noodle.mobile.ui.sources.StudioRequest.pending
    LaunchedEffect(studioRequest) { if (studioRequest != null) page = MorePage.STUDIO }
    BackHandler(enabled = page != MorePage.MENU) { page = MorePage.MENU }
    val backupContext = LocalContext.current
    val backupScope = rememberCoroutineScope()
    var backupMessage by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { backupBusy = true; backupScope.launch { backupMessage = pro.noodle.mobile.core.backup.BackupService(backupContext).export(uri).message; backupBusy = false } }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { backupBusy = true; backupScope.launch { backupMessage = pro.noodle.mobile.core.backup.BackupService(backupContext).restore(uri).message; backupBusy = false } }
    }
    when (page) {
        MorePage.SOURCES, MorePage.STUDIO -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { page = MorePage.MENU }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Text("Источники (SNP)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f)) { SourceCenterScreen(openStudio = page == MorePage.STUDIO, onStudioClosed = { if (page == MorePage.STUDIO) page = MorePage.SOURCES }) }
        }
        MorePage.FINDER -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = MorePage.MENU }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Text("Поиск сайтов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            pro.noodle.mobile.ui.finder.SiteFinderScreen(onOpenStudio = { page = MorePage.STUDIO })
        }
        MorePage.MENU -> SettingsScreen(settingsVm) {
            item {
                Text("Ещё", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            item {
                ElevatedCard(onClick = { page = MorePage.SOURCES }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Источники (SNP)") },
                        supportingContent = { Text("Подключённые сайты, добавление сайта, Studio и диагностика") },
                        leadingContent = { Icon(Icons.Outlined.Hub, null, tint = MaterialTheme.colorScheme.secondary) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    )
                }
            }
            item {
                ElevatedCard(onClick = { page = MorePage.FINDER }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Поиск сайтов") },
                        supportingContent = { Text("Найти сайты через Bing, DuckDuckGo, Google или Яндекс и отправить в Studio") },
                        leadingContent = { Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.secondary) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    )
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Резервная копия", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Избранное, история, добавленные сайты и настройки — одним файлом. Входы в нейросети не сохраняются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { exportBackup.launch("noodle-backup.zip") }, enabled = !backupBusy) { Text("Экспорт") }
                            OutlinedButton(onClick = { importBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !backupBusy) { Text("Импорт") }
                        }
                        if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (backupMessage.isNotBlank()) Text(backupMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: SettingsViewModel, header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    val settings by vm.settings.collectAsState()
    val diagnosticsMessage by vm.diagnosticsMessage.collectAsState()
    val report = remember { CoreSelfTest.run() }
    val exportDiagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.exportDiagnostics(uri)
    }
    var devOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header()
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Тема", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "salad_sand" to "Салат",
                            "aurora_light" to "Light",
                            "graphite_pro" to "Graphite",
                            "blue_glass" to "Blue",
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
                    pro.noodle.mobile.ui.NoodleSlider(
                        value = settings.uiScalePercent.toFloat(),
                        onValueChange = { raw -> vm.uiScale(pro.noodle.mobile.core.settings.snapScale(raw.toInt())) },
                        valueRange = 85f..100f,
                        steps = 2,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("85%", style = MaterialTheme.typography.labelSmall)
                        Text("90%", style = MaterialTheme.typography.labelSmall)
                        Text("95%", style = MaterialTheme.typography.labelSmall)
                        Text("100%", style = MaterialTheme.typography.labelSmall)
                    }

                    HorizontalDivider()
                    Text("Параллельных загрузок: ${settings.parallelDownloads}")
                    pro.noodle.mobile.ui.NoodleSlider(
                        settings.parallelDownloads.toFloat(),
                        { vm.parallel(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(settings.downloadSelectMode, vm::downloadSelectMode)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Выбор видео для загрузки")
                            Text("На роликах появятся квадратики с галочкой — отметьте много и скачайте одной кнопкой.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val pickFolder = pro.noodle.mobile.ui.download.rememberFolderPicker()
                    OutlinedButton(onClick = pickFolder, modifier = Modifier.fillMaxWidth()) {
                        Text("Папка загрузок: ${pro.noodle.mobile.ui.download.folderLabel(settings.downloadFolderUri)}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(settings.rememberPlaybackPosition, vm::rememberPosition)
                        Spacer(Modifier.width(8.dp))
                        Text("Запоминать позицию воспроизведения")
                    }

                    HorizontalDivider()
                    Text("Похожие видео", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Вид рекомендаций под плеером. По умолчанию используется плотная сетка в две колонки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.relatedVideosColumns == 1,
                            onClick = { vm.relatedVideosColumns(1) },
                            leadingIcon = { Icon(Icons.Outlined.ViewList, null) },
                            label = { Text("1 колонка") },
                        )
                        FilterChip(
                            selected = settings.relatedVideosColumns != 1,
                            onClick = { vm.relatedVideosColumns(2) },
                            leadingIcon = { Icon(Icons.Outlined.GridView, null) },
                            label = { Text("2 колонки") },
                        )
                    }

                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(settings.backgroundMonitoring, vm::background)
                        Spacer(Modifier.width(8.dp))
                        Text("Мониторинг: проверка источников и поиск новых видео")
                    }
                    if (settings.backgroundMonitoring) {
                        Text("Интервал мониторинга: ${settings.monitoringIntervalHours} ч")
                        Text(
                            "Раз в столько часов приложение сканирует источники. Колокольчик на главной показывает видео, появившиеся за это окно.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        pro.noodle.mobile.ui.NoodleSlider(
                            settings.monitoringIntervalHours.toFloat(),
                            { vm.monitoringHours(it.toInt()) },
                            valueRange = 2f..46f,
                            steps = 43,
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
            TextButton(onClick = { devOpen = !devOpen }) {
                Text(if (devOpen) "Скрыть диагностику ▴" else "Диагностика и самотест ▾")
            }
        }
        if (devOpen) {
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
}

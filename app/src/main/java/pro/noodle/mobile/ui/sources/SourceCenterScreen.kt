package pro.noodle.mobile.ui.sources

import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.AddLink
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.noodle.mobile.core.catalog.CatalogEntry
import pro.noodle.mobile.core.catalog.TrustedCatalogKey
import pro.noodle.mobile.core.source.AdapterSnapshotInfo
import pro.noodle.mobile.core.source.SourceCenterItem
import pro.noodle.mobile.core.source.SourceDetails

@Composable
fun SourceCenterScreen(
    vm: SourcesViewModel = viewModel(),
    tools: SourceToolsViewModel = viewModel(),
    openStudio: Boolean = false,
    onStudioClosed: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val toolState by tools.state.collectAsState()
    var exportId by remember { mutableStateOf("") }
    var showMarketplace by remember { mutableStateOf(false) }
    var mirrorFor by remember { mutableStateOf("") }
    var limitFor by remember { mutableStateOf("") }
    var showStudio by remember(openStudio) { mutableStateOf(openStudio) }
    var showKeys by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId
        exportId = ""
        if (uri != null && id.isNotBlank()) vm.exportToUri(id, uri)
    }
    val keyImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tools.importKeys(uri)
    }
    val keyExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) tools.exportKeys(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { showStudio = true }) {
                Icon(Icons.Outlined.AddLink, null)
                Spacer(Modifier.width(5.dp))
                Text("Добавить сайт")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::checkAll, enabled = state.busyId.isBlank() && state.adapters.isNotEmpty()) {
                Icon(Icons.Outlined.FactCheck, "Проверить все включённые")
            }
            IconButton(
                onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = state.busyId.isBlank(),
            ) { Icon(Icons.Outlined.Add, "Импорт ZIP") }
            IconButton(onClick = vm::reload, enabled = state.busyId.isBlank()) {
                Icon(Icons.Outlined.Refresh, "Обновить")
            }
        }

        if (state.loading || state.busyId.isNotBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotBlank()) {
            Text(state.message, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
        }
        if (state.error.isNotBlank()) {
            Text(state.error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.adapters, key = { it.adapterId }) { item ->
                SourceCard(
                    item = item,
                    busy = state.busyId == item.adapterId,
                    onEnabled = { vm.setEnabled(item.adapterId, it) },
                    onAction = { action ->
                        when (action) {
                            "reconfigure" -> {
                                StudioRequest.pending = StudioRequest.Pending(item.baseUrl, item.healthQuery.takeIf { it.isNotBlank() && it != "video" } ?: "", autoStart = true)
                                showStudio = true
                            }
                            "details" -> vm.showDetails(item.adapterId)
                            "mirrors" -> mirrorFor = item.adapterId
                            "limit" -> limitFor = item.adapterId
                            "health" -> vm.health(item.adapterId)
                            "semantic" -> vm.semantic(item.adapterId)
                            "fingerprint" -> vm.fingerprint(item.adapterId)
                            "breakage" -> vm.breakage(item.adapterId)
                            "repair" -> vm.repair(item.adapterId)
                            "migrate" -> vm.migrate(item.adapterId)
                            "check_update" -> vm.checkUpdate(item.adapterId)
                            "apply_update" -> vm.applyUpdate(item.adapterId)
                            "rollback" -> vm.rollback(item.adapterId)
                            "history" -> vm.showHistory(item.adapterId)
                            "export" -> {
                                exportId = item.adapterId
                                exporter.launch("${item.adapterId}-${item.version}.snp.zip")
                            }
                            "remove" -> vm.removeCustom(item.adapterId)
                        }
                    },
                )
            }
        }
    }

    state.details?.let { SourceDetailsDialog(it, vm::closeDetails) }
    if (limitFor.isNotBlank()) NewLimitDialog(limitFor, state.adapters.firstOrNull { it.adapterId == limitFor }?.name ?: limitFor) { limitFor = "" }
    if (mirrorFor.isNotBlank()) MirrorDialog(mirrorFor, onClose = { mirrorFor = ""; vm.reload() })
    if (state.historyFor.isNotBlank()) {
        HistoryDialog(state.historyFor, state.history, vm::closeHistory) { snapshot ->
            vm.restore(state.historyFor, snapshot)
        }
    }
    if (showMarketplace) {
        MarketplaceDialog(
            state = toolState,
            vm = tools,
            onDismiss = { showMarketplace = false },
            onKeys = { showKeys = true },
            onInstalled = vm::reload,
        )
    }
    if (showStudio) {
        Studio2Dialog(toolState, tools, onDismiss = { showStudio = false; onStudioClosed() }, onSaved = vm::reload)
    }
    if (showKeys) {
        TrustedKeysDialog(
            keys = toolState.trustedKeys,
            onDismiss = { showKeys = false },
            onImport = { keyImporter.launch(arrayOf("application/json", "text/plain")) },
            onExport = { keyExporter.launch("noodle-trusted-keys.json") },
            onRemove = tools::removeKey,
        )
    }
}

@Composable
private fun SourceCard(
    item: SourceCenterItem,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAction: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.packageError.isBlank() && item.compatible) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                    null,
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text("${item.adapterId} • ${item.version} • schema ${item.schemaVersion}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${item.engine} • ${if (item.bundled) "bundled" else "custom"} • ${item.trust}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(item.enabled, onEnabled, enabled = !busy && item.compatible && item.packageError.isBlank())
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "Действия") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        SourceMenuAction("Перенастроить (Studio)", "reconfigure", { menu = false }, onAction)
                        SourceMenuAction("Подробности", "details", { menu = false }, onAction)
                        HorizontalDivider()
                        SourceMenuAction("Лимит новых видео за период", "limit", { menu = false }, onAction)
                        SourceMenuAction("Зеркала и домен", "mirrors", { menu = false }, onAction)
                        SourceMenuAction("Проверить доступность", "health", { menu = false }, onAction)
                        SourceMenuAction("Проверить поиск и видео (подробно)", "semantic", { menu = false }, onAction)
                        SourceMenuAction("Fingerprint", "fingerprint", { menu = false }, onAction)
                        SourceMenuAction("Breakage check", "breakage", { menu = false }, onAction)
                        HorizontalDivider()
                        SourceMenuAction("Проверить обновление", "check_update", { menu = false }, onAction)
                        if (!item.bundled) SourceMenuAction("Установить обновление", "apply_update", { menu = false }, onAction)
                        SourceMenuAction("История snapshots", "history", { menu = false }, onAction)
                        SourceMenuAction("Откатить последнюю", "rollback", { menu = false }, onAction)
                        if (!item.bundled && item.schemaVersion < 3) SourceMenuAction("Мигрировать в Schema v3", "migrate", { menu = false }, onAction)
                        if (!item.bundled) SourceMenuAction("Repair", "repair", { menu = false }, onAction)
                        SourceMenuAction("Экспорт ZIP", "export", { menu = false }, onAction)
                        if (!item.bundled) {
                            HorizontalDivider()
                            SourceMenuAction("Удалить custom SNP", "remove", { menu = false }, onAction)
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier.clickable { onAction("details") },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill("Сайт", item.healthStatus)
                StatusPill("Поиск и видео", item.semanticStatus + if (item.pendingValidation) " • pending" else "")
            }
            if (item.semanticMessage.isNotBlank() && item.semanticStatus.lowercase().let { it.contains("fail") || it.contains("error") || it.contains("no_results") || it.contains("invalid") }) {
                Text(
                    (if (item.semanticErrorCode.isNotBlank()) item.semanticErrorCode + ": " else "") + item.semanticMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                )
                TextButton(onClick = { onAction("reconfigure") }) { Text("Перенастроить автоматически") }
            }
            if (item.updateStatus.isNotBlank() && item.latestVersion.isNotBlank()) {
                StatusLine("Обновление", item.updateStatus + " → " + item.latestVersion)
            }
            if (!item.compatible) Text("Несовместимая версия приложения: ${item.compatibilityStatus}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (item.packageError.isNotBlank()) Text("Package: ${item.packageError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (item.historyCount > 0) Text("Snapshots: ${item.historyCount}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SourceDetailsDialog(d: SourceDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text(d.name) },
        text = {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    Text("Последняя проверка «Поиск и видео»", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (d.semanticStatus.isBlank()) {
                        Text("Ещё не проводилась. Меню ⋮ → «Semantic health».", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            d.semanticStatus + (if (d.semanticErrorCode.isNotBlank()) " • ${d.semanticErrorCode}" else "") + "\n" + d.semanticMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.semanticStatus == "healthy") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        )
                        if (d.semanticReport.isNotBlank()) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(d.semanticReport, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            TextButton(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString("${d.name} (${d.baseUrl})\n${d.semanticStatus} ${d.semanticErrorCode}\n${d.semanticMessage}\n\n${d.semanticReport}"))
                                copied = true
                            }) { Text(if (copied) "Отчёт скопирован" else "Скопировать отчёт проверки") }
                        }
                    }
                    HorizontalDivider()
                    if (d.userRules.isNotBlank()) {
                        Text("Правила «не то видео» (добавлены вами)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(d.userRules, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("Журнал воспроизведения (последние 20)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        d.playbackLog.ifBlank { "Пока пусто." },
                        style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    HorizontalDivider()
                }
                item {
                    Detail("ID", d.adapterId)
                    Detail("Version / publisher", "${d.version} / ${d.publisher}")
                    Detail("Base URL", d.baseUrl)
                    Detail("Engine", d.engine)
                    Detail("Dependencies", d.engineDependencies.joinToString().ifBlank { "—" })
                    Detail("Strategies", d.viewStrategies.joinToString().ifBlank { "—" })
                    Detail("Allowed hosts", d.allowedHosts.joinToString().ifBlank { "—" })
                    Detail("Permissions", "JavaScript=${d.allowJavaScript}, cookies=${d.allowCookies}")
                    Detail("Compatibility", "${d.compatibilityStatus} • min=${d.minAppVersion.ifBlank { "—" }} • max=${d.maxAppVersion.ifBlank { "—" }}")
                    Detail("Package", "${if (d.bundled) "bundled" else "custom"} • ${d.trust}")
                    Detail("Health", "query=${d.healthQuery}, min=${d.healthMinResults}, playback=${d.healthRequirePlayback}, autoRepair=${d.healthAutoRepair}")
                    Detail("Repair", "enabled=${d.repairEnabled}, mode=${d.repairMode}, threshold=${d.repairFailureThreshold}, cooldown=${d.repairCooldownMinutes}m, quarantine=${d.repairAutoQuarantine}")
                    Detail("Repair strategies", d.repairStrategies.joinToString().ifBlank { "—" })
                    Detail("Update feed", d.updateFeedUrl.ifBlank { "—" })
                    Detail("Snapshots", d.snapshots.size.toString())
                }
            }
        },
    )
}

@Composable
private fun Detail(name: String, value: String) {
    Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MarketplaceDialog(
    state: SourceToolsUiState,
    vm: SourceToolsViewModel,
    onDismiss: () -> Unit,
    onKeys: () -> Unit,
    onInstalled: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SNP Marketplace", Modifier.weight(1f))
                TextButton(onClick = onKeys) { Text("Trusted Keys") }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.catalogUrl,
                    onValueChange = vm::setCatalogUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS URL подписанного каталога") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::refreshCatalog, enabled = !state.marketplaceBusy && state.catalogUrl.isNotBlank()) { Text("Проверить") }
                    TextButton(onClick = vm::saveCatalogUrl) { Text("Сохранить URL") }
                }
                if (state.marketplaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                state.catalog?.let { catalog ->
                    Text("${catalog.name} • ${catalog.publisher} • key=${catalog.keyId}", fontWeight = FontWeight.SemiBold)
                    if (catalog.rejectedEntries > 0) {
                        Text("Отклонено некорректных entries: ${catalog.rejectedEntries}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(catalog.entries, key = { it.adapterId }) { entry ->
                            MarketplaceEntryCard(entry, state.marketplaceBusy) { vm.install(entry, onInstalled) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MarketplaceEntryCard(e: CatalogEntry, busy: Boolean, onInstall: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.SemiBold)
                Text("${e.adapterId} • ${e.version} • ${e.publisher}", style = MaterialTheme.typography.bodySmall)
                if (e.description.isNotBlank()) Text(e.description, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                if (!e.compatible) Text("Несовместим: ${e.compatibilityStatus}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = onInstall, enabled = !busy && e.compatible) { Text("Установить") }
        }
    }
}

@Composable
private fun TrustedKeysDialog(
    keys: List<TrustedCatalogKey>,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRemove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Trusted RSA Keys") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport) { Text("Импорт JSON") }
                    TextButton(onClick = onExport) { Text("Экспорт user keys") }
                }
                Text(
                    "Приватные ключи в приложение не импортируются. Поддерживаются только RSA-SHA256 public keys ≥2048 бит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (keys.isEmpty()) item { Text("Trusted keys отсутствуют.") }
                    items(keys, key = { it.keyId }) { key ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(key.keyId, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${key.algorithm} • ${key.modulusHex.length * 4} bit • ${if (key.builtIn) "built-in" else "user"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!key.builtIn) IconButton(onClick = { onRemove(key.keyId) }) { Icon(Icons.Outlined.Delete, "Удалить") }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun HistoryDialog(
    id: String,
    history: List<AdapterSnapshotInfo>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Snapshots: $id") },
        text = {
            if (history.isEmpty()) {
                Text("История отката отсутствует.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(history, key = { it.snapshotId }) { snapshot ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(snapshot.version.ifBlank { "unknown" }, fontWeight = FontWeight.SemiBold)
                                    Text(snapshot.snapshotId, style = MaterialTheme.typography.labelSmall)
                                    Text(if (snapshot.validPackage) "integrity: OK" else "integrity: FAIL", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onRestore(snapshot.snapshotId) }, enabled = snapshot.validPackage) { Text("Восстановить") }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun StatusPill(label: String, value: String) {
    val v = value.lowercase()
    val (color, text) = when {
        v.isBlank() || v.startsWith("unknown") || v.startsWith("not_") || v == "—" -> MaterialTheme.colorScheme.outline to "не проверено"
        v.startsWith("healthy") || v.startsWith("ok") || v.startsWith("reachable") || v.startsWith("unchanged") -> MaterialTheme.colorScheme.secondary to "работает"
        v.contains("fail") || v.contains("error") || v.contains("unavailable") || v.contains("no_results") || v.contains("broken") -> MaterialTheme.colorScheme.error to "ошибка"
        else -> MaterialTheme.colorScheme.tertiary to value.take(22)
    }
    androidx.compose.material3.Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    ) {
        Text("$label: $text", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatusLine(name: String, value: String) {
    if (value.isNotBlank()) Text("$name: $value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SourceMenuAction(label: String, key: String, dismiss: () -> Unit, onAction: (String) -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { dismiss(); onAction(key) })
}

/** Домен источника: активный адрес, известные зеркала, поиск нового адреса после переезда, ручной ввод. */
@Composable
private fun MirrorDialog(adapterId: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val store = remember { pro.noodle.mobile.core.source.MirrorStore(ctx) }
    val finder = remember { pro.noodle.mobile.core.source.MirrorFinder(ctx) }
    var def by remember { mutableStateOf<pro.noodle.mobile.core.adapter.AdapterDefinition?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    LaunchedEffect(adapterId, tick) {
        def = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pro.noodle.mobile.core.source.RuntimeAdapterRepository(ctx).find(adapterId)?.definition }
    }
    val d = def
    fun run(block: suspend () -> pro.noodle.mobile.core.source.MirrorOutcome) {
        if (busy) return
        busy = true; message = ""
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = runCatching { block() }.getOrElse { pro.noodle.mobile.core.source.MirrorOutcome(message = "Ошибка: ${it.message}") }
            message = r.message; busy = false; tick++
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Зеркала и домен") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d == null) { Text("Загружаю…"); return@Column }
                val own = pro.noodle.mobile.core.source.MirrorStore.originOf(d.baseUrl)
                Text("Сейчас: ${pro.noodle.mobile.core.source.MirrorStore.hostOf(own)}", fontWeight = FontWeight.SemiBold)
                store.note(adapterId).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    "Если сайт переехал или недоступен, приложение само ищет новый адрес: смотрит, куда ведёт прежний, что пишет сама страница, и проверяет находку поиском и открытием видео.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { run { finder.findAndAdopt(d) { message = it } } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Найти зеркало или новый адрес") }
                val known = (store.known(adapterId) + store.history(adapterId)).filter { !it.equals(own, true) }.distinct()
                if (known.isNotEmpty()) {
                    Text("Известные зеркала", fontWeight = FontWeight.SemiBold)
                    known.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pro.noodle.mobile.core.source.MirrorStore.hostOf(o), Modifier.weight(1f))
                            TextButton(onClick = { run { finder.switchToKnown(d, o) } }, enabled = !busy) { Text("Включить") }
                        }
                    }
                }
                val suggested = store.suggested(adapterId).filter { !it.equals(own, true) }
                if (suggested.isNotEmpty()) {
                    Text("Найдено (нужно подтверждение)", fontWeight = FontWeight.SemiBold)
                    suggested.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pro.noodle.mobile.core.source.MirrorStore.hostOf(o), Modifier.weight(1f))
                            TextButton(onClick = { run { finder.switchToKnown(d, o) } }, enabled = !busy) { Text("Переключить") }
                        }
                    }
                }
                OutlinedTextField(manual, { manual = it }, label = { Text("Добавить зеркало вручную (адрес)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { run { finder.addManual(d, manual) } }, enabled = !busy && manual.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Проверить и переключить") }
                if (store.active(adapterId).isNotBlank()) {
                    TextButton(onClick = { store.reset(adapterId); message = "Возвращён исходный домен адаптера."; tick++ }, enabled = !busy) { Text("Вернуть исходный домен") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Закрыть") } },
    )
}


/** Сколько новых роликов этот источник может добавить в «Новое» за один период мониторинга. */
@Composable
private fun NewLimitDialog(adapterId: String, name: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val store = remember { pro.noodle.mobile.core.newvideos.NewVideosStore(ctx) }
    var value by remember { mutableStateOf(store.limitFor(adapterId)) }
    var hours by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        hours = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pro.noodle.mobile.core.settings.AppSettingsRepository(ctx).settings.first().monitoringIntervalHours
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Лимит новых видео") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(if (value == 0) "Без ограничения" else "Не более $value за период", fontWeight = FontWeight.SemiBold)
                Text(
                    "Период мониторинга${if (hours > 0) " — $hours ч (меняется в настройках)" else ""}. За это время с источника в «Новое» и в уведомление попадёт не больше выбранного числа роликов. 0 — без ограничения.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pro.noodle.mobile.ui.NoodleSlider(value.toFloat(), { value = it.toInt() }, valueRange = 0f..30f, steps = 29)
            }
        },
        confirmButton = { TextButton(onClick = { store.setLimit(adapterId, value); onClose() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

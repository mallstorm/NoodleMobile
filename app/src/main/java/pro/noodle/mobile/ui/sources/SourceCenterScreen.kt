package pro.noodle.mobile.ui.sources

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
import androidx.compose.ui.text.font.FontFamily
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
) {
    val state by vm.state.collectAsState()
    val toolState by tools.state.collectAsState()
    var exportId by remember { mutableStateOf("") }
    var showMarketplace by remember { mutableStateOf(false) }
    var showStudio by remember { mutableStateOf(false) }
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
            FilledTonalButton(onClick = { showMarketplace = true }) {
                Icon(Icons.Outlined.Storefront, null)
                Spacer(Modifier.width(5.dp))
                Text("Маркет")
            }
            FilledTonalButton(onClick = { showStudio = true }) {
                Icon(Icons.Outlined.Build, null)
                Spacer(Modifier.width(5.dp))
                Text("Studio")
            }
            Spacer(Modifier.weight(1f))
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
                            "details" -> vm.showDetails(item.adapterId)
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
        StudioDialog(toolState, tools, onDismiss = { showStudio = false }, onSaved = vm::reload)
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
                        SourceMenuAction("Подробности", "details", { menu = false }, onAction)
                        HorizontalDivider()
                        SourceMenuAction("Проверить доступность", "health", { menu = false }, onAction)
                        SourceMenuAction("Semantic health", "semantic", { menu = false }, onAction)
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
            StatusLine("health", item.healthStatus)
            StatusLine("semantic", item.semanticStatus + if (item.pendingValidation) " • pending validation" else "")
            StatusLine("fingerprint", item.fingerprintStatus)
            StatusLine("breakage", item.breakageStatus)
            StatusLine("update", item.updateStatus + item.latestVersion.takeIf { it.isNotBlank() }?.let { " → $it" }.orEmpty())
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
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
private fun StudioDialog(
    state: SourceToolsUiState,
    vm: SourceToolsViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Adapter Studio Mobile") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.studioUrl,
                    onValueChange = vm::setStudioUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS URL сайта") },
                    singleLine = true,
                )
                Button(onClick = vm::analyzeStudio, enabled = !state.studioBusy && state.studioUrl.isNotBlank()) { Text("Анализировать") }
                if (state.studioBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.studioMessage.isNotBlank()) Text(state.studioMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state.studioJson.isNotBlank()) {
                    Text("Schema v3 preview — можно отредактировать перед сохранением", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.studioJson,
                        onValueChange = vm::setStudioJson,
                        modifier = Modifier.fillMaxWidth().height(330.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        label = { Text("adapter.json") },
                    )
                    Button(onClick = { vm.saveStudio(onSaved) }, enabled = !state.studioBusy) { Text("Сохранить custom SNP") }
                }
            }
        },
    )
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
private fun StatusLine(name: String, value: String) {
    if (value.isNotBlank()) Text("$name: $value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SourceMenuAction(label: String, key: String, dismiss: () -> Unit, onAction: (String) -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { dismiss(); onAction(key) })
}

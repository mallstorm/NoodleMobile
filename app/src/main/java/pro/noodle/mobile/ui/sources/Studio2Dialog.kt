package pro.noodle.mobile.ui.sources

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pro.noodle.mobile.core.studio.StudioAiModel
import pro.noodle.mobile.core.studio.StudioAiProvider

@Composable
internal fun Studio2Dialog(
    state: SourceToolsUiState,
    vm: SourceToolsViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
                    Column(Modifier.weight(1f)) {
                        Text("SNP Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Подключение нового сайта", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Справка") }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StudioHero()
                    TextButton(onClick = { help = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Справка: как создать адаптер")
                    }
                    OutlinedTextField(
                        value = state.studioUrl,
                        onValueChange = vm::setStudioUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Адрес сайта") },
                        placeholder = { Text("example.com") },
                        leadingIcon = { Icon(Icons.Outlined.AddLink, null) },
                        singleLine = true,
                        enabled = !state.autoBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = state.studioQuery,
                        onValueChange = vm::setStudioQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Проверочный запрос") },
                        placeholder = { Text("слово, по которому на сайте точно есть видео") },
                        supportingText = { Text("По нему Studio найдёт поиск, карточки и проверит воспроизведение") },
                        singleLine = true,
                        enabled = !state.autoBusy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    )
                    Button(
                        onClick = vm::autoCreate,
                        enabled = !state.autoBusy && state.studioUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (state.autoBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Outlined.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.autoBusy) "Studio работает…" else "Найти адаптер автоматически", fontWeight = FontWeight.SemiBold)
                    }

                    if (state.autoSteps.isNotEmpty()) StudioSteps(state.autoSteps, state.autoBusy)
                    if (state.autoDone) StudioAutoResultCard(state, onInstall = { vm.installAuto(onSaved) }, onAdvanced = { advanced = true })

                    HorizontalDivider()
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (advanced) "Скрыть расширенный режим" else "Расширенный режим: AI, JSON, ручная проверка")
                    }
                    AnimatedVisibility(advanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StudioAdvancedSection(state, vm, onSaved, onModelMenu = { modelMenu = true })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (help) StudioHelpDialog(onDismiss = { help = false })

    if (modelMenu) {
        StudioModelPickerDialog(
            provider = state.studioProvider,
            models = state.studioModelCatalog,
            selected = state.studioModel,
            onDismiss = { modelMenu = false },
            onSelect = { model ->
                vm.setStudioModel(model)
                modelMenu = false
            },
        )
    }
}

@Composable
private fun StudioHero() {
    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayCircle, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Укажите сайт и запрос — Studio сама найдёт поиск, распознает карточки видео, страницы и плеер, проверит воспроизведение и подключит сайт.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun StudioSteps(steps: List<String>, busy: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { index, step ->
                val running = busy && index == steps.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.TaskAlt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(step, style = MaterialTheme.typography.bodySmall, color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StudioAutoResultCard(state: SourceToolsUiState, onInstall: () -> Unit, onAdvanced: () -> Unit) {
    val ok = state.autoHealthy
    val accent = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        state.autoInstalled -> "${state.autoSiteName.ifBlank { "Сайт" }} подключён"
                        ok -> "Адаптер для ${state.autoSiteName.ifBlank { "сайта" }} готов"
                        else -> "Не удалось собрать рабочий адаптер"
                    },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.autoSearchCount > 0 || state.autoSamples.isNotEmpty()) {
                Text(
                    "Найдено видео: ${maxOf(state.autoSearchCount, state.autoSamples.size)} • воспроизведение подтверждено: ${state.autoPlaybackReady}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.autoSamples.forEach { sample ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PlayCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(sample.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sample.duration.isNotBlank()) Text(sample.duration, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.autoError.isNotBlank()) Text(state.autoError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            when {
                state.autoInstalled -> Text("Сайт уже участвует в общем поиске. Его можно отключить в списке источников.", style = MaterialTheme.typography.bodySmall)
                ok -> Button(onClick = onInstall, enabled = !state.autoBusy, modifier = Modifier.fillMaxWidth()) { Text("Добавить сайт в поиск") }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.studioJson.isNotBlank()) OutlinedButton(onClick = onInstall, enabled = !state.autoBusy) { Text("Всё равно добавить") }
                    FilledTonalButton(onClick = onAdvanced) { Text("Расширенный режим") }
                }
            }
        }
    }
}

@Composable
private fun StudioAdvancedSection(state: SourceToolsUiState, vm: SourceToolsViewModel, onSaved: () -> Unit, onModelMenu: () -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(
        value = state.studioInstruction,
        onValueChange = vm::setStudioInstruction,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Задание для AI (необязательно)") },
        placeholder = { Text("Например: найди поиск, карточки видео, превью, длительность и основной HLS/MP4-поток.") },
        minLines = 3,
        maxLines = 6,
    )
        Text("Обработка", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioAiProvider.entries.forEach { provider ->
                FilterChip(
                    selected = state.studioProvider == provider,
                    onClick = { vm.setStudioProvider(provider) },
                    label = { Text(provider.title) },
                )
            }
        }

        if (state.studioProvider != StudioAiProvider.LOCAL) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.studioConnected) Icons.Outlined.CheckCircle else Icons.Outlined.CloudOff,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.studioConnected) "${state.studioProvider.title} подключён" else "${state.studioProvider.title} не подключён",
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.studioAccount.isNotBlank()) {
                                Text(state.studioAccount, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.studioConnected) {
                            TextButton(onClick = vm::disconnectStudioAi, enabled = !state.studioAuthBusy) {
                                Text("Выйти")
                            }
                        } else {
                            Button(onClick = vm::beginStudioLogin, enabled = !state.studioAuthBusy) {
                                Icon(Icons.Outlined.Login, null)
                                Spacer(Modifier.width(5.dp))
                                Text("Войти")
                            }
                        }
                    }

                    if (state.studioLoginUrl.isNotBlank()) {
                        if (state.studioLoginCode.isNotBlank()) {
                            Text("Код: ${state.studioLoginCode}", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(state.studioLoginUrl)),
                                    )
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, null)
                            Spacer(Modifier.width(5.dp))
                            Text("Открыть страницу входа")
                        }
                    }

                    if (state.studioConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Модель", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = vm::refreshStudioModels, enabled = !state.studioAuthBusy) {
                                Icon(Icons.Outlined.Refresh, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Обновить")
                            }
                        }
                        if (state.studioModelCatalog.isNotEmpty()) {
                            val freeCount = state.studioModelCatalog.count { it.isFree }
                            val autoCount = state.studioModelCatalog.count { it.isAuto }
                            Text(
                                "Всего ${state.studioModelCatalog.size} • Free $freeCount${if (autoCount > 0) " • Auto $autoCount" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onModelMenu,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.studioModelCatalog.isNotEmpty() && !state.studioAuthBusy,
                        ) {
                            Text(
                                state.studioModel.ifBlank {
                                    if (state.studioAuthBusy) "Загрузка моделей…" else "Модель не выбрана"
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::analyzeStudio,
                enabled = !state.studioBusy && state.studioUrl.isNotBlank(),
            ) {
                Text("Локальный черновик")
            }
            Button(
                onClick = vm::generateStudio,
                enabled = !state.studioBusy &&
                    !state.studioAuthBusy &&
                    state.studioUrl.isNotBlank() &&
                    (state.studioProvider == StudioAiProvider.LOCAL ||
                        (state.studioConnected && state.studioModel.isNotBlank() && state.studioInstruction.isNotBlank())),
            ) {
                Icon(Icons.Outlined.Science, null)
                Spacer(Modifier.width(5.dp))
                Text("Создать адаптер")
            }
        }

        if (state.studioBusy || state.studioAuthBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.studioMessage.isNotBlank()) {
            Text(
                state.studioMessage,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.error.isNotBlank()) {
            Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.studioTestStatus.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Live test: ${state.studioTestStatus}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.studioTestStatus == "healthy") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (state.studioAttempts > 0) {
                        Text("AI-попыток: ${state.studioAttempts}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.studioTestDetails.isNotBlank()) {
                        Text(state.studioTestDetails, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (state.studioJson.isNotBlank()) {
            Text(
                "Schema v3 preview — результат можно проверить и отредактировать до установки",
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = state.studioJson,
                onValueChange = vm::setStudioJson,
                modifier = Modifier.fillMaxWidth().height(300.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                label = { Text("adapter.json") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testStudio, enabled = !state.studioBusy) {
                    Text("Live test")
                }
                Button(
                    onClick = { vm.saveStudio(onSaved) },
                    enabled = !state.studioBusy,
                ) {
                    Text("Сохранить custom SNP")
                }
            }
            Text(
                "Новый SNP сохраняется выключенным. AI не может самостоятельно включить или установить источник без этой кнопки.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
}

private enum class StudioModelFilter {
    ALL,
    FREE,
    AUTO,
    PAID,
}

@Composable
private fun StudioModelPickerDialog(
    provider: StudioAiProvider,
    models: List<StudioAiModel>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember(provider) { mutableStateOf("") }
    var filter by remember(provider) {
        mutableStateOf(if (provider == StudioAiProvider.KILO) StudioModelFilter.FREE else StudioModelFilter.ALL)
    }
    val filtered = remember(models, query, filter) {
        val needle = query.trim().lowercase()
        models.filter { model ->
            val matchesFilter = when (filter) {
                StudioModelFilter.ALL -> true
                StudioModelFilter.FREE -> model.isFree
                StudioModelFilter.AUTO -> model.isAuto
                StudioModelFilter.PAID -> !model.isFree
            }
            val matchesQuery = needle.isBlank() ||
                model.id.lowercase().contains(needle) ||
                model.name.lowercase().contains(needle) ||
                model.provider.lowercase().contains(needle)
            matchesFilter && matchesQuery
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Выбор модели") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск модели") },
                    placeholder = { Text("openrouter, deepseek, gemini…") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(
                        selected = filter == StudioModelFilter.ALL,
                        onClick = { filter = StudioModelFilter.ALL },
                        label = { Text("Все") },
                    )
                    FilterChip(
                        selected = filter == StudioModelFilter.FREE,
                        onClick = { filter = StudioModelFilter.FREE },
                        label = { Text("Free") },
                    )
                    if (models.any { it.isAuto }) {
                        FilterChip(
                            selected = filter == StudioModelFilter.AUTO,
                            onClick = { filter = StudioModelFilter.AUTO },
                            label = { Text("Auto") },
                        )
                    }
                    FilterChip(
                        selected = filter == StudioModelFilter.PAID,
                        onClick = { filter = StudioModelFilter.PAID },
                        label = { Text("Платные") },
                    )
                }
                Text(
                    "Показано ${filtered.size} из ${models.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item { Text("По этому фильтру моделей нет.", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(filtered, key = { it.id }) { model ->
                        TextButton(
                            onClick = { onSelect(model.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(
                                        model.name.ifBlank { model.id },
                                        fontWeight = if (model.id == selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2,
                                    )
                                    if (model.name.isNotBlank() && model.name != model.id) {
                                        Text(
                                            model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                                if (model.isAuto) {
                                    Text(
                                        "AUTO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                                if (model.isFree) {
                                    Text(
                                        "FREE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

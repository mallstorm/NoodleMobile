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
import androidx.compose.material3.AlertDialog
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
    val context = LocalContext.current
    var modelMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Adapter Studio 2.0") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 690.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "URL + задание → локальный анализ → AI → Schema v3 → live test. Установка выполняется только после вашего подтверждения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = state.studioUrl,
                    onValueChange = vm::setStudioUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS URL сайта") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = state.studioInstruction,
                    onValueChange = vm::setStudioInstruction,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Что должен делать адаптер") },
                    placeholder = {
                        Text("Например: найди поиск, карточки видео, превью, длительность, просмотры и основной HLS/MP4-поток.")
                    },
                    minLines = 4,
                    maxLines = 7,
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
                                    onClick = { modelMenu = true },
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
                Spacer(Modifier.height(2.dp))
            }
        },
    )

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

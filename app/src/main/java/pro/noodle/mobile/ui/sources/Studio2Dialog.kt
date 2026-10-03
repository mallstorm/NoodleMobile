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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
                                androidx.compose.foundation.layout.Box {
                                    OutlinedButton(
                                        onClick = { modelMenu = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = state.studioModels.isNotEmpty() && !state.studioAuthBusy,
                                    ) {
                                        Text(
                                            state.studioModel.ifBlank {
                                                if (state.studioAuthBusy) "Загрузка моделей…" else "Модель не выбрана"
                                            },
                                            maxLines = 1,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = modelMenu,
                                        onDismissRequest = { modelMenu = false },
                                    ) {
                                        state.studioModels.take(120).forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    vm.setStudioModel(model)
                                                    modelMenu = false
                                                },
                                            )
                                        }
                                    }
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
}

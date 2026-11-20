package pro.noodle.mobile.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import pro.noodle.mobile.core.dub.DubConfig
import pro.noodle.mobile.core.dub.DubProvider
import pro.noodle.mobile.core.dub.TtsEngine
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DubSheet(status: StateFlow<String>, onDismiss: () -> Unit) {
    val s by DubConfig.flow.collectAsState()
    val st by status.collectAsState()
    var keyDraft by remember(s.provider) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Озвучка на русском (ИИ)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Герои говорят по-русски: речь распознаётся, переводится и озвучивается с опережением.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.enabled, onCheckedChange = { v -> DubConfig.update { it.copy(enabled = v) } })
            }
            if (st.isNotBlank()) Text(st, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

            Text("Сервис распознавания и перевода", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DubProvider.values().forEach { p -> FilterChip(selected = s.provider == p, onClick = { DubConfig.update { it.copy(provider = p) } }, label = { Text(p.title) }) }
            }
            OutlinedTextField(
                value = keyDraft, onValueChange = { keyDraft = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (s.key.isNotBlank()) "Ключ ${s.provider.title} сохранён — введите новый, чтобы заменить" else "API-ключ ${s.provider.title}") },
                visualTransformation = PasswordVisualTransformation(),
                trailingIcon = {
                    if (keyDraft.isNotBlank()) androidx.compose.material3.TextButton(onClick = {
                        val k = keyDraft
                        DubConfig.update { cur -> if (cur.provider == DubProvider.GROQ) cur.copy(groqKey = k) else cur.copy(openaiKey = k) }
                        keyDraft = ""
                    }) { Text("Сохранить") }
                },
            )
            Text("Голос", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsEngine.values().forEach { t -> FilterChip(selected = s.tts == t, onClick = { DubConfig.update { it.copy(tts = t) } }, label = { Text(t.title) }) }
            }
            if (s.tts == TtsEngine.OPENAI && s.openaiKey.isBlank()) {
                Text("Для голоса OpenAI выберите «OpenAI» выше и сохраните ключ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Громкость оригинала во время перевода: ${(s.originalVolume * 100).roundToInt()}%")
            Slider(value = s.originalVolume, onValueChange = { v -> DubConfig.update { it.copy(originalVolume = v) } }, valueRange = 0f..1f)
            Text(
                "Подсказка: Groq даёт бесплатный лимит на распознавание; системный голос работает без интернета и бесплатно, голос OpenAI звучит естественнее. Перевод — по ключу выбранного сервиса.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

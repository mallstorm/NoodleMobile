package pro.noodle.mobile.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.noodle.mobile.core.newvideos.NewEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Лента «Новое»: ролики, которые приложение обнаружило на источниках за выбранное окно (2–46 ч). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewVideosScreen(newVm: NewVideosViewModel, searchVm: SearchViewModel, onClose: () -> Unit) {
    val st by newVm.state.collectAsState()
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text("Новое за ${st.windowHours} ч", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (st.lastScanMs > 0) "Сканировали в ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(st.lastScanMs))} • источников с видео: ${st.sources}"
                    else "Ещё не сканировали",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = newVm::scan, enabled = !st.scanning) { Icon(Icons.Outlined.Refresh, "Сканировать сейчас") }
        }
        if (st.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        if (st.message.isNotBlank() && !st.scanning) {
            Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (st.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .75f))
                    Text("Новых видео пока нет", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Первое сканирование запоминает то, что уже есть на сайтах. Всё, что появится позже, окажется здесь. Период ленты и частота проверки задаются в «Ещё → Интервал мониторинга».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(onClick = newVm::scan, enabled = !st.scanning) { Text("Проверить сейчас") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(st.items, key = { it.video.id + it.seenAtMs }) { e -> NewVideoRow(e, searchVm) }
            }
        }
    }
}

@Composable
private fun NewVideoRow(e: NewEntry, vm: SearchViewModel) {
    val v = e.video
    ElevatedCard(
        onClick = { vm.open(v) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchPreview(v, vm, modifier = Modifier.width(146.dp).aspectRatio(16f / 10f).let { it })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(v.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(v.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        "добавлено ${ago(e.seenAtMs)}",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

private fun ago(ms: Long): String {
    val min = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
    return when {
        min < 2 -> "только что"
        min < 60 -> "$min мин назад"
        else -> "${min / 60} ч назад"
    }
}

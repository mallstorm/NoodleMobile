package pro.noodle.mobile.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.noodle.mobile.core.storage.HistoryItem
import pro.noodle.mobile.ui.player.PlayerScreen
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val state by vm.state.collectAsState()
    state.playback?.let { session ->
        val client = remember(session.sourceKey, session.video.id) { vm.streamingClient(session) }
        PlayerScreen(session, client, vm::closePlayer)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("История", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Недавно открытые ролики", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.items.isNotEmpty()) TextButton(onClick = vm::clear) { Text("Очистить") }
            }
            if (state.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.items.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize().padding(top = 80.dp), contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.History, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("История пока пуста", fontWeight = FontWeight.SemiBold)
                        Text("Открытые ролики будут появляться здесь", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(state.items, key = { it.video.favoriteKey }) { item ->
                HistoryCard(item, vm)
            }
        }
    }
}

@Composable
private fun HistoryCard(item: HistoryItem, vm: HistoryViewModel) {
    val video = item.video
    ElevatedCard(onClick = { vm.open(item) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = video.thumbnailUrl) {
                value = vm.loadThumbnail(video)
            }
            Surface(modifier = Modifier.size(width = 118.dp, height = 72.dp), shape = MaterialTheme.shapes.medium) {
                if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayCircle, null, modifier = Modifier.size(32.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.durationMs > 0) {
                    val progress = (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            IconButton(onClick = { vm.remove(item) }) { Icon(Icons.Outlined.Delete, "Удалить") }
        }
    }
}

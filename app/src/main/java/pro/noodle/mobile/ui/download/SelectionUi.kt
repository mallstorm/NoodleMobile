package pro.noodle.mobile.ui.download

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.download.BatchDownloads
import pro.noodle.mobile.core.download.rememberIsSelected
import pro.noodle.mobile.core.download.rememberSelectMode
import pro.noodle.mobile.core.model.VideoItem

/** Маленький квадратик с галкой в углу превью; виден только при включённом режиме выбора. */
@Composable
fun SelectCheck(video: VideoItem, modifier: Modifier = Modifier) {
    if (!rememberSelectMode()) return
    val on = rememberIsSelected(video)
    Surface(
        modifier = modifier.padding(6.dp).size(24.dp).clickable { BatchDownloads.toggle(video) },
        shape = RoundedCornerShape(6.dp),
        color = if (on) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .45f),
        border = BorderStroke(1.5.dp, if (on) MaterialTheme.colorScheme.primary else Color.White),
    ) {
        if (on) Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, "Выбрано", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun rememberFolderPicker(onPicked: () -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            scope.launch { BatchDownloads.setFolder(uri); onPicked() }
        }
    }
    return { launcher.launch(null) }
}

fun folderLabel(uri: String): String {
    if (uri.isBlank()) return "не выбрана"
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrDefault(uri)
    return Uri.decode(id).substringAfter(':').ifBlank { "Память устройства" }.let { if (it == "Память устройства") it else "/$it" }
}

/** Плавающая панель внизу: «Скачать выбранные (N)» + прогресс постановки в очередь. */
@Composable
fun BatchBar(modifier: Modifier = Modifier, onOpenDownloads: () -> Unit) {
    val selected by BatchDownloads.selected.collectAsState()
    val st by BatchDownloads.state.collectAsState()
    val folder by BatchDownloads.folder.collectAsState()
    var pendingStart by remember { mutableStateOf(false) }
    val pick = rememberFolderPicker { if (pendingStart) { pendingStart = false; BatchDownloads.startSelected() } }
    if (selected.isEmpty() && !st.running && st.message.isBlank()) return
    Surface(modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (st.running) {
                LinearProgressIndicator(progress = { if (st.total == 0) 0f else (st.queued + st.failed).toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
                Text("Готовлю загрузки: ${st.queued + st.failed} из ${st.total}", style = MaterialTheme.typography.bodySmall)
            } else if (st.message.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onOpenDownloads(); BatchDownloads.dismissMessage() }) { Text("К загрузкам") }
                    IconButton(onClick = BatchDownloads::dismissMessage) { Icon(Icons.Outlined.Close, "Скрыть") }
                }
            }
            if (selected.isNotEmpty() && !st.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (!BatchDownloads.startSelected()) { pendingStart = true; pick() }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Скачать выбранные (${selected.size})")
                    }
                    OutlinedButton(onClick = BatchDownloads::clear) { Text("Сброс") }
                }
                Text(
                    "Папка: ${folderLabel(folder)} • нажмите, чтобы сменить",
                    Modifier.clickable { pick() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

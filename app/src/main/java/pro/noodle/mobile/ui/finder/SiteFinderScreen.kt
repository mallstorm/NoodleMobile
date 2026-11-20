package pro.noodle.mobile.ui.finder

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.finder.FinderEngine
import pro.noodle.mobile.core.finder.FoundSite
import pro.noodle.mobile.core.finder.SiteFinder
import pro.noodle.mobile.ui.sources.StudioRequest

data class FinderState(
    val engine: FinderEngine = FinderEngine.BING,
    val query: String = "",
    val pages: Int = 3,
    val busy: Boolean = false,
    val progress: String = "",
    val sites: List<FoundSite> = emptyList(),
    val note: String = "",
)

class SiteFinderViewModel(app: Application) : AndroidViewModel(app) {
    private val finder = SiteFinder(app)
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state.asStateFlow()
    private var job: Job? = null

    fun setEngine(e: FinderEngine) = _state.update { it.copy(engine = e) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setPages(p: Int) = _state.update { it.copy(pages = p) }

    fun search() {
        val st = _state.value
        if (st.busy || st.query.isBlank()) return
        _state.update { it.copy(busy = true, sites = emptyList(), note = "", progress = "Запускаю поиск…") }
        job = viewModelScope.launch {
            val r = runCatching { finder.search(st.engine, st.query, st.pages) { sites, p -> _state.update { it.copy(sites = sites, progress = p) } } }
            _state.update { it.copy(busy = false, progress = "", sites = r.getOrNull()?.sites ?: it.sites, note = r.getOrNull()?.note ?: "Ошибка поиска: ${r.exceptionOrNull()?.message}") }
        }
    }

    fun stop() { job?.cancel(); _state.update { it.copy(busy = false, progress = "") } }
}

@Composable
fun SiteFinderScreen(onOpenStudio: () -> Unit, vm: SiteFinderViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Найдите сайты через поисковик, выберите нужный — Studio откроется с уже вписанным адресом, останется нажать «Создать адаптер».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinderEngine.values().forEach { e -> FilterChip(selected = st.engine == e, onClick = { vm.setEngine(e) }, label = { Text(e.title) }, enabled = !st.busy) }
            }
        }
        item {
            OutlinedTextField(
                value = st.query, onValueChange = vm::setQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Запрос") }, enabled = !st.busy,
            )
        }
        item {
            Column {
                Text("Глубина: ${st.pages} стр. выдачи", style = MaterialTheme.typography.bodyMedium)
                Slider(value = st.pages.toFloat(), onValueChange = { vm.setPages(it.toInt()) }, valueRange = 1f..10f, steps = 8, enabled = !st.busy)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::search, enabled = !st.busy && st.query.isNotBlank()) { Text("Искать") }
                if (st.busy) FilledTonalButton(onClick = vm::stop) { Text("Стоп") }
            }
        }
        if (st.busy) item { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(st.progress, style = MaterialTheme.typography.bodySmall) } }
        if (st.note.isNotBlank()) item { Text(st.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (st.sites.isNotEmpty()) item { Text("Найдено сайтов: ${st.sites.size}", fontWeight = FontWeight.SemiBold) }
        items(st.sites, key = { it.host }) { s ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.host, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            (if (s.known) "уже подключён • " else "") + "в выдаче: ${s.hits}",
                            style = MaterialTheme.typography.labelSmall, color = if (s.known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.homeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                        Icon(Icons.Outlined.OpenInBrowser, "Открыть в браузере")
                    }
                    FilledTonalButton(onClick = { StudioRequest.pending = StudioRequest.Pending(s.homeUrl, "", autoStart = false); onOpenStudio() }) { Text("В Studio") }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

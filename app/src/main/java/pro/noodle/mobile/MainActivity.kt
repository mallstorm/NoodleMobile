package pro.noodle.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.selftest.CoreSelfTest
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.version.BuildIdentity
import pro.noodle.mobile.ui.NoodleTheme
import pro.noodle.mobile.ui.SettingsViewModel
import pro.noodle.mobile.ui.downloads.DownloadsViewModel
import pro.noodle.mobile.ui.favorites.FavoritesViewModel
import pro.noodle.mobile.ui.player.OfflinePlayerScreen
import pro.noodle.mobile.ui.player.PlayerScreen
import pro.noodle.mobile.ui.search.SearchViewModel
import pro.noodle.mobile.ui.sources.SourcesViewModel
import pro.noodle.mobile.ui.sources.SourceCenterScreen

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{val vm:SettingsViewModel=viewModel();val settings by vm.settings.collectAsState();NoodleTheme(settings.theme){NoodleApp(vm)}}}}
enum class Section(val title:String){SEARCH("Поиск"),FAVORITES("Избранное"),DOWNLOADS("Загрузки"),SOURCES("Источники"),SETTINGS("Настройки")}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NoodleApp(settingsVm:SettingsViewModel){
    var section by remember { mutableStateOf(Section.SEARCH) }
    val searchVm:SearchViewModel=viewModel()
    val favoritesVm:FavoritesViewModel=viewModel()
    val downloadsVm:DownloadsViewModel=viewModel()
    val searchState by searchVm.state.collectAsState()
    val favoritesState by favoritesVm.state.collectAsState()
    val offlinePlayback by downloadsVm.offlinePlayback.collectAsState()
    val playerActive=(section==Section.SEARCH&&searchState.playback!=null)||(section==Section.FAVORITES&&favoritesState.playback!=null)||(section==Section.DOWNLOADS&&offlinePlayback!=null)
    Scaffold(
        topBar = {
            if(!playerActive) TopAppBar(title = {
                Column {
                    Text("Noodle Mobile", fontWeight = FontWeight.SemiBold)
                    Text(BuildIdentity.STAGE_LABEL, style = MaterialTheme.typography.labelSmall)
                }
            })
        },
        bottomBar = {
            if(!playerActive) NavigationBar {
                Section.entries.forEach { s ->
                    NavigationBarItem(
                        selected = section == s,
                        onClick = { section = s },
                        icon = { Icon(iconFor(s), null) },
                        label = { Text(s.title) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(if(playerActive)PaddingValues(0.dp) else pad).fillMaxSize()) {
            when(section) {
                Section.SEARCH -> SearchScreen(searchVm)
                Section.FAVORITES -> FavoritesScreen(favoritesVm)
                Section.DOWNLOADS -> DownloadsScreen(downloadsVm)
                Section.SOURCES -> SourcesScreen()
                Section.SETTINGS -> SettingsScreen(settingsVm)
            }
        }
    }
}
private fun iconFor(s:Section)=when(s){Section.SEARCH->Icons.Outlined.Search;Section.FAVORITES->Icons.Outlined.FavoriteBorder;Section.DOWNLOADS->Icons.Outlined.Download;Section.SOURCES->Icons.Outlined.Hub;Section.SETTINGS->Icons.Outlined.Settings}

@Composable private fun SearchScreen(vm:SearchViewModel=viewModel()){
    val state by vm.state.collectAsState();state.playback?.let{session->val client=remember(session.sourceKey,session.video.id){vm.streamingClient(session)};PlayerScreen(session,client,vm::closePlayer);return}
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(state.query,vm::setQuery,Modifier.weight(1f),placeholder={Text("Поиск по включённым источникам")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true);Button(onClick=vm::search,enabled=!state.loading&&state.query.isNotBlank()){Text("Найти")}}
        if(state.loading||state.resolving)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.message.isNotBlank())Text(state.message,color=if(state.message.contains(":"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.errors.isNotEmpty()){
            Text("Проблемы источников: ${state.errors.size}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.errors.take(3).forEach{e->Text("${e.sourceName}: ${e.errorCode}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
        }
        LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){items(state.items,key={it.id}){video->
            ElevatedCard(onClick={vm.open(video)},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                    SearchThumbnail(video,vm)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text(video.title,fontWeight=FontWeight.SemiBold,maxLines=2)
                        Text(video.sourceName,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text(listOf(video.duration.takeIf{it.isNotBlank()},video.views.takeIf{it.isNotBlank()}).filterNotNull().joinToString(" • ").ifBlank{"Видео"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.PlayCircle,null)
                }
            }
        }}
    }
}

@Composable private fun SearchThumbnail(video:pro.noodle.mobile.core.model.VideoItem,vm:SearchViewModel){
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue=null,key1=video.thumbnailUrl){value=vm.loadThumbnail(video)}
    Surface(modifier=Modifier.size(width=132.dp,height=82.dp),shape=MaterialTheme.shapes.medium,tonalElevation=2.dp){
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.PlayCircle,null,modifier=Modifier.size(34.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable private fun FavoritesScreen(vm:FavoritesViewModel=viewModel()){
    val state by vm.state.collectAsState();state.playback?.let{s->val c=remember(s.sourceKey,s.video.id){vm.streamingClient(s)};PlayerScreen(s,c,vm::closePlayer);return}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Избранное",style=MaterialTheme.typography.titleLarge);if(state.resolving)LinearProgressIndicator(Modifier.fillMaxWidth());if(state.message.isNotBlank())Text(state.message,color=MaterialTheme.colorScheme.error)}
        if(state.items.isEmpty())item{Text("Пока пусто. Добавьте видео кнопкой ♥ в плеере.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.items,key={it.favoriteKey}){v->ElevatedCard(onClick={vm.open(v)},modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(v.title,fontWeight=FontWeight.SemiBold,maxLines=2);Text(v.sourceName,style=MaterialTheme.typography.bodySmall)};IconButton(onClick={vm.remove(v)}){Icon(Icons.Outlined.Delete,"Удалить")}}}}
    }
}


@Composable private fun DownloadsScreen(vm:DownloadsViewModel){
    val list by vm.items.collectAsState()
    val library by vm.completed.collectAsState()
    val offline by vm.offlinePlayback.collectAsState()
    val message by vm.message.collectAsState()
    if(offline!=null){OfflinePlayerScreen(offline!!,vm::closeOffline);return}
    var libraryMode by remember { mutableStateOf(false) }
    val shown=if(libraryMode) library else list
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{
            Text("Загрузки",style=MaterialTheme.typography.titleLarge)
            Text("Progressive: Range + SAF. HLS/DASH: Media3 DownloadHelper + выбранные дорожки + строгий offline cache.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)){
                FilterChip(selected=!libraryMode,onClick={libraryMode=false},label={Text("Очередь")},leadingIcon=if(!libraryMode)({Icon(Icons.Outlined.Check,null)})else null)
                FilterChip(selected=libraryMode,onClick={libraryMode=true},label={Text("Библиотека (${library.size})")},leadingIcon=if(libraryMode)({Icon(Icons.Outlined.Check,null)})else null)
            }
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
        if(shown.isEmpty())item{Text(if(libraryMode)"Завершённых офлайн-медиа пока нет" else "Очередь пуста")}
        items(shown,key={it.id}){d->
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(d.title,fontWeight=FontWeight.SemiBold,maxLines=2)
                    val selection=listOf(d.videoSelectionLabel.takeIf{it.isNotBlank()},d.audioSelectionLanguage.takeIf{it.isNotBlank()}?.let{"audio=$it"},if(d.includeSubtitles)"subs=${d.textSelectionLanguage.ifBlank{"auto"}}" else null).filterNotNull().joinToString(" • ")
                    Text(listOf(d.state.name,d.protocol.takeIf{it.isNotBlank()},selection.takeIf{it.isNotBlank()}).filterNotNull().joinToString(" • "),style=MaterialTheme.typography.labelMedium)
                    if(d.totalBytes>0)LinearProgressIndicator(progress={d.downloadedBytes.toFloat()/d.totalBytes.toFloat()},modifier=Modifier.fillMaxWidth()) else if(d.state==DownloadState.RUNNING)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${bytes(d.downloadedBytes)} / ${if(d.totalBytes>0)bytes(d.totalBytes) else "?"}",style=MaterialTheme.typography.bodySmall)
                    if(d.errorMessage.isNotBlank())Text("${d.errorCode}: ${d.errorMessage}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    Row{
                        if(d.state==DownloadState.COMPLETED)IconButton(onClick={vm.openOffline(d.id)}){Icon(Icons.Outlined.PlayCircle,"Открыть офлайн")}
                        when(d.state){
                            DownloadState.RUNNING,DownloadState.QUEUED->IconButton(onClick={vm.pause(d.id)}){Icon(Icons.Outlined.Pause,"Пауза")}
                            DownloadState.PAUSED,DownloadState.FAILED->IconButton(onClick={vm.resume(d.id)}){Icon(Icons.Outlined.PlayArrow,"Продолжить")}
                            else->{}
                        }
                        if(d.state!=DownloadState.COMPLETED&&d.state!=DownloadState.CANCELED)IconButton(onClick={vm.cancel(d.id)}){Icon(Icons.Outlined.Cancel,"Отмена")}
                        IconButton(onClick={vm.remove(d.id)}){Icon(Icons.Outlined.Delete,"Удалить")}
                    }
                }
            }
        }
    }
}
private fun bytes(v:Long):String=when{v>=1024L*1024*1024->"%.2f ГБ".format(v/(1024.0*1024*1024));v>=1024L*1024->"%.1f МБ".format(v/(1024.0*1024));v>=1024->"%.1f КБ".format(v/1024.0);else->"$v Б"}

@Composable private fun SourcesScreen(vm:SourcesViewModel=viewModel()) {
    SourceCenterScreen(vm)
}

@Composable private fun SettingsScreen(vm:SettingsViewModel){
    val settings by vm.settings.collectAsState()
    val diagnosticsMessage by vm.diagnosticsMessage.collectAsState()
    val report=remember{CoreSelfTest.run()}
    val exportDiagnostics=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)vm.exportDiagnostics(uri)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Настройки",style=MaterialTheme.typography.titleLarge)}
        item{ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Тема",fontWeight=FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("blue_glass" to "Blue","emerald_pulse" to "Emerald","graphite_pro" to "Graphite","aurora_light" to "Light").forEach{(id,label)->FilterChip(selected=settings.theme==id,onClick={vm.theme(id)},label={Text(label)})}}
            Text("Параллельных загрузок: ${settings.parallelDownloads}")
            Slider(settings.parallelDownloads.toFloat(),{vm.parallel(it.toInt())},valueRange=1f..3f,steps=1)
            Row(verticalAlignment=Alignment.CenterVertically){Switch(settings.rememberPlaybackPosition,vm::rememberPosition);Spacer(Modifier.width(8.dp));Text("Запоминать позицию воспроизведения")}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(settings.backgroundMonitoring,vm::background);Spacer(Modifier.width(8.dp));Text("Фоновый fingerprint + breakage monitor")}
            if(settings.backgroundMonitoring){Text("Интервал мониторинга: ${settings.monitoringIntervalHours} ч");Slider(settings.monitoringIntervalHours.toFloat(),{vm.monitoringHours(it.toInt())},valueRange=6f..168f)}
        }}}
        item{HorizontalDivider();Text("Core Self-Test",style=MaterialTheme.typography.titleLarge);Text(if(report.passed)"Все проверки ядра пройдены" else "Есть ошибки")}
        items(report.checks){c->ListItem(headlineContent={Text(c.name)},supportingContent={if(c.detail.isNotBlank())Text(c.detail)},leadingContent={Icon(if(c.passed)Icons.Outlined.CheckCircle else Icons.Outlined.Error,null)})}
        item{HorizontalDivider();Text("Диагностика",style=MaterialTheme.typography.titleLarge);Text("ZIP проходит sanitizer и не включает cookies, Authorization и секретные ключи.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Button(onClick={exportDiagnostics.launch("noodle-mobile-diagnostics.zip")}){Icon(Icons.Outlined.BugReport,null);Spacer(Modifier.width(8.dp));Text("Экспортировать ZIP")};if(diagnosticsMessage.isNotBlank())Text(diagnosticsMessage,style=MaterialTheme.typography.bodySmall)}
    }
}

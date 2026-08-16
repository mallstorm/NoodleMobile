package pro.noodle.mobile.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.download.AdaptiveDownloadSelection
import pro.noodle.mobile.core.download.DownloadRepository
import pro.noodle.mobile.core.download.MediaDownloadRuntime
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.storage.UserMediaRepository
import pro.noodle.mobile.core.settings.AppSettings
import pro.noodle.mobile.core.settings.AppSettingsRepository

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(session: PlaybackSession, streamingClient: OkHttpClient, onBack: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val activity=context.findActivity()
    val video=session.video
    val key=video.favoriteKey.ifBlank { video.id.ifBlank { video.watchUrl } }
    val userRepo=remember { UserMediaRepository(context.applicationContext) }
    val downloadRepo=remember { DownloadRepository(context.applicationContext) }
    val settingsRepo=remember { AppSettingsRepository(context.applicationContext) }
    val appSettings by remember { settingsRepo.settings }.collectAsState(initial=AppSettings())
    val ioScope=remember { CoroutineScope(SupervisorJob()+Dispatchers.IO) }
    val isFavorite by remember(key){userRepo.isFavorite(key)}.collectAsState(initial=false)
    var message by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var selectedVariant by remember(video.id){ mutableIntStateOf(-1) }
    var selectedAudio by remember(video.id){ mutableIntStateOf(-1) }
    var selectedText by remember(video.id){ mutableIntStateOf(-1) }
    var pendingDownload by remember { mutableStateOf<MediaSource?>(null) }
    var showAdaptiveDownloadDialog by remember { mutableStateOf(false) }
    val createDocument=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")){uri->
        val src=pendingDownload; pendingDownload=null
        if(uri!=null&&src!=null){
            runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
            ioScope.launch{downloadRepo.enqueue(video.title,src,session.networkPolicy,session.sourceKey,uri.toString(),video.thumbnailUrl);withContext(Dispatchers.Main){message="Добавлено в загрузки"}}
        }
    }

    val initial=video.sources.indexOfFirst{it.isDefault}.takeIf{it>=0}?:0
    var selectedIndex by remember(video.id){mutableIntStateOf(initial.coerceIn(0,(video.sources.size-1).coerceAtLeast(0)))}
    val source=video.sources.getOrNull(selectedIndex)
    if(source==null){Column(Modifier.fillMaxSize().padding(16.dp)){Button(onClick=onBack){Text("Назад")};Text("Воспроизводимый источник отсутствует.")};return}

    val player=remember(video.id,streamingClient,session.sourceKey){
        // Playback reads the exact same Media3 cache used by adaptive downloads. Missing data
        // still goes through the same per-SNP host/DNS/cookie policy.
        val factory=MediaDownloadRuntime.cacheDataSourceFactory(context.applicationContext,session.networkPolicy,session.sourceKey)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
    }
    var restoredPosition by remember(video.id){mutableLongStateOf(0L)}
    LaunchedEffect(video.id,appSettings.rememberPlaybackPosition){restoredPosition=if(appSettings.rememberPlaybackPosition)withContext(Dispatchers.IO){userRepo.playbackPosition(key)?.positionMs?:0L}else 0L}

    fun mediaItemFor(s:MediaSource):MediaItem{
        val b=MediaItem.Builder().setUri(Uri.parse(s.url));if(s.mimeType.isNotBlank())b.setMimeType(s.mimeType)
        val subtitles=video.textTracks.map{t->MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url)).setMimeType(guessSubtitleMime(t.url)).apply{if(t.language.isNotBlank())setLanguage(t.language);if(t.label.isNotBlank())setLabel(t.label);setSelectionFlags(if(t.isDefault)C.SELECTION_FLAG_DEFAULT else 0)}.build()}
        if(subtitles.isNotEmpty())b.setSubtitleConfigurations(subtitles);return b.build()
    }

    LaunchedEffect(player,source.url,restoredPosition){
        val old=player.currentPosition.takeIf{it>0}?:restoredPosition
        val was=player.playWhenReady
        player.setMediaItem(mediaItemFor(source),old.coerceAtLeast(0));player.prepare();player.playWhenReady=was||old==0L
    }

    DisposableEffect(fullscreen,activity){
        activity?.window?.let{w->val c=WindowInsetsControllerCompat(w,w.decorView);if(fullscreen)c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())}
        onDispose { if(fullscreen)activity?.window?.let{w->WindowInsetsControllerCompat(w,w.decorView).show(WindowInsetsCompat.Type.systemBars())} }
    }
    DisposableEffect(player){onDispose{val pos=player.currentPosition.coerceAtLeast(0);val dur=player.duration.coerceAtLeast(0);player.release();ioScope.launch{if(appSettings.rememberPlaybackPosition)runCatching{userRepo.savePlaybackPosition(key,pos,dur)};ioScope.cancel()}}}

    if(showAdaptiveDownloadDialog){
        AdaptiveDownloadDialog(
            video = video,
            initialVariant = selectedVariant,
            initialAudio = selectedAudio,
            initialText = selectedText,
            onDismiss = { showAdaptiveDownloadDialog=false },
            onConfirm = { selection ->
                showAdaptiveDownloadDialog=false
                ioScope.launch {
                    downloadRepo.enqueue(
                        video.title, source, session.networkPolicy, session.sourceKey,
                        thumbnailUrl = video.thumbnailUrl, adaptiveSelection = selection,
                    )
                    withContext(Dispatchers.Main){ message="Adaptive offline: ${selection.summary()}" }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()){
        if(!fullscreen) Row(Modifier.fillMaxWidth().padding(8.dp)){
            IconButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,"Назад")}
            Column(Modifier.weight(1f)){Text(video.title,style=MaterialTheme.typography.titleMedium,maxLines=2);Text("${source.label.ifBlank{"Auto"}} • ${source.protocol.ifBlank{"HTTP"}}",style=MaterialTheme.typography.bodySmall)}
            IconButton(onClick={ioScope.launch{if(isFavorite)userRepo.removeFavorite(video) else userRepo.addFavorite(video)}}){Icon(if(isFavorite)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,"Избранное")}
            IconButton(onClick={
                val adaptive=source.protocol.equals("hls",true)||source.protocol.equals("dash",true)||source.url.contains(".m3u8",true)||source.url.contains(".mpd",true)
                if(adaptive){ showAdaptiveDownloadDialog=true }
                else {pendingDownload=source;createDocument.launch(safeFileName(video.title,source.url))}
            }){Icon(Icons.Outlined.Download,"Скачать")}
            IconButton(onClick={fullscreen=!fullscreen}){Icon(if(fullscreen)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,"Полный экран")}
            if(Build.VERSION.SDK_INT>=26)IconButton(onClick={activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())}){Icon(Icons.Outlined.PictureInPictureAlt,"PiP")}
        }
        AndroidView(factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true;layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)}},update={it.player=player},modifier=Modifier.fillMaxWidth().weight(1f))
        if(!fullscreen){
            if(message.isNotBlank())Text(message,Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(video.sources.size>1)ChoiceRow("Источник",video.sources.mapIndexed{i,s->s.label.ifBlank{s.protocol.ifBlank{"Источник ${i+1}"}}},selectedIndex){selectedIndex=it}
            if(video.variants.isNotEmpty())ChoiceRow("Качество",listOf("Auto")+video.variants.map{it.label.ifBlank{if(it.height>0)"${it.height}p" else it.id}},selectedVariant+1){idx->selectedVariant=idx-1;val b=player.trackSelectionParameters.buildUpon();if(idx<=0)b.setMaxVideoSize(Int.MAX_VALUE,Int.MAX_VALUE) else {val v=video.variants[idx-1];if(v.width>0&&v.height>0)b.setMaxVideoSize(v.width,v.height)};player.trackSelectionParameters=b.build()}
            if(video.audioTracks.isNotEmpty())ChoiceRow("Аудио",listOf("Auto")+video.audioTracks.map{it.label.ifBlank{it.language.ifBlank{"Track"}}},selectedAudio+1){idx->selectedAudio=idx-1;val b=player.trackSelectionParameters.buildUpon();if(idx>0){val t=video.audioTracks[idx-1];b.setPreferredAudioLanguage(t.language.ifBlank { null })}else b.setPreferredAudioLanguage(null);player.trackSelectionParameters=b.build()}
            if(video.textTracks.isNotEmpty())ChoiceRow("Субтитры",listOf("Выкл","Auto")+video.textTracks.map{it.label.ifBlank{it.language.ifBlank{"Track"}}},selectedText+1){idx->selectedText=idx-1;val b=player.trackSelectionParameters.buildUpon();when(idx){0->{b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);b.setPreferredTextLanguage(null)};1->{b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false);b.setPreferredTextLanguage(null)};else->{b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false);val t=video.textTracks[idx-2];b.setPreferredTextLanguage(t.language.ifBlank { null })}};player.trackSelectionParameters=b.build()}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){AssistChip(onClick={player.setPlaybackSpeed(1f)},label={Text("1×")});AssistChip(onClick={player.setPlaybackSpeed(1.5f)},label={Text("1.5×")});AssistChip(onClick={player.setPlaybackSpeed(2f)},label={Text("2×")})}
        }
    }
}

@Composable private fun ChoiceRow(title:String,options:List<String>,selected:Int,onSelect:(Int)->Unit){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=10.dp));options.forEachIndexed{i,t->FilterChip(selected=i==selected,onClick={onSelect(i)},label={Text(t)})}}}
private fun safeFileName(title:String,url:String):String{val ext=Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.',"mp4").take(8).lowercase().takeIf{it.matches(Regex("[a-z0-9]{1,8}"))}?:"mp4";val base=title.replace(Regex("[^\\p{L}\\p{N}._ -]+"),"_").trim().take(80).ifBlank{"video"};return "$base.$ext"}
private fun guessSubtitleMime(url:String)=when{url.endsWith(".vtt",true)->MimeTypes.TEXT_VTT;url.endsWith(".srt",true)->MimeTypes.APPLICATION_SUBRIP;url.endsWith(".ttml",true)||url.endsWith(".xml",true)->MimeTypes.APPLICATION_TTML;else->MimeTypes.TEXT_VTT}
private tailrec fun Context.findActivity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.findActivity();else->null}


@Composable
private fun AdaptiveDownloadDialog(
    video: VideoItem,
    initialVariant: Int,
    initialAudio: Int,
    initialText: Int,
    onDismiss: () -> Unit,
    onConfirm: (AdaptiveDownloadSelection) -> Unit,
) {
    var variant by remember { mutableIntStateOf(initialVariant) }
    var audio by remember { mutableIntStateOf(initialAudio) }
    var text by remember { mutableIntStateOf(initialText) }
    val qualityNames = listOf("Auto") + video.variants.map { it.label.ifBlank { if (it.height > 0) "${it.height}p" else it.id } }
    val audioNames = listOf("Auto") + video.audioTracks.map { it.label.ifBlank { it.language.ifBlank { "Track" } } }
    val textNames = listOf("Без субтитров", "Auto") + video.textTracks.map { it.label.ifBlank { it.language.ifBlank { "Track" } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скачать для офлайн") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Media3 скачает только выбранные adaptive-дорожки.", style = MaterialTheme.typography.bodySmall)
                ChoiceRow("Качество", qualityNames, variant + 1) { variant = it - 1 }
                if (video.audioTracks.isNotEmpty()) ChoiceRow("Аудио", audioNames, audio + 1) { audio = it - 1 }
                if (video.textTracks.isNotEmpty()) ChoiceRow("Субтитры", textNames, text + 1) { text = it - 1 }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = video.variants.getOrNull(variant)
                val a = video.audioTracks.getOrNull(audio)
                val textOption = text + 1
                val t = if (textOption >= 2) video.textTracks.getOrNull(textOption - 2) else null
                onConfirm(
                    AdaptiveDownloadSelection(
                        maxVideoWidth = v?.width?.takeIf { it > 0 } ?: Int.MAX_VALUE,
                        maxVideoHeight = v?.height?.takeIf { it > 0 } ?: Int.MAX_VALUE,
                        videoLabel = v?.label?.ifBlank { if ((v?.height ?: 0) > 0) "${v?.height}p" else "Selected" } ?: "Auto",
                        audioLanguage = a?.language.orEmpty(),
                        textLanguage = t?.language.orEmpty(),
                        includeSubtitles = textOption >= 1,
                    )
                )
            }) { Text("Скачать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

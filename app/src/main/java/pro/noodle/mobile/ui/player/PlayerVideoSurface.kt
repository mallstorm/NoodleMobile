package pro.noodle.mobile.ui.player

import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.player.NoodlePlaybackService
import pro.noodle.mobile.core.player.PlaybackSessionHolder

@Composable
internal fun PlayerVideoSurface(
    player: Player,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    activity: android.app.Activity?,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit = {},
    audioOnly: Boolean = false,
    onAudioOnlyChange: (Boolean) -> Unit = {},
    title: String = "",
) {
    val context = LocalContext.current
    var locked by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableIntStateOf(0) } // 0 fit, 1 stretch, 2 crop
    val audioOnlyState by rememberUpdatedState(audioOnly)
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var lastPreviewSeek by remember { mutableLongStateOf(-1L) }

    // Keep the root Scaffold informed about true player fullscreen. This is deliberately
    // separate from device orientation: portrait manual fullscreen must hide app navigation too.
    DisposableEffect(fullscreen) {
        PlayerChromeState.fullscreen = fullscreen
        onDispose {
            if (PlayerChromeState.fullscreen == fullscreen) {
                PlayerChromeState.fullscreen = false
            }
        }
    }

    // Home/app-switch must never leave ordinary playback running in the background.
    // PiP is the explicit exception: entering PiP is an intentional background-playback action.
    DisposableEffect(player, activity) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true && !audioOnlyState) {
                player.pause()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    // MediaSession: управление с экрана блокировки/из шторки; сервис нужен только в режиме «только звук».
    DisposableEffect(player) {
        val session = runCatching { androidx.media3.session.MediaSession.Builder(context, player).setId("noodle_player").build() }.getOrNull()
        PlaybackSessionHolder.session = session
        onDispose {
            if (PlaybackSessionHolder.session === session) PlaybackSessionHolder.session = null
            runCatching { context.stopService(android.content.Intent(context, NoodlePlaybackService::class.java)) }
            runCatching { session?.release() }
        }
    }
    LaunchedEffect(audioOnly) {
        if (audioOnly) runCatching { context.startService(android.content.Intent(context, NoodlePlaybackService::class.java)) }
        else runCatching { context.stopService(android.content.Intent(context, NoodlePlaybackService::class.java)) }
    }
    // Автовход в PiP при сворачивании во время воспроизведения (Android 12+).
    LaunchedEffect(isPlaying, fullscreen) {
        if (Build.VERSION.SDK_INT >= 31) {
            val vs = player.videoSize
            val ratio = if (vs.width > 0 && vs.height > 0) Rational(vs.width.coerceAtMost(vs.height * 2).coerceAtLeast(vs.height / 2), vs.height) else Rational(16, 9)
            runCatching {
                activity?.setPictureInPictureParams(
                    PictureInPictureParams.Builder().setAspectRatio(ratio).setAutoEnterEnabled(isPlaying && !audioOnly).build(),
                )
            }
        }
    }

    // React only to a real orientation change. This keeps the manual fullscreen button usable
    // in portrait while still making landscape enter fullscreen and portrait leave it.
    val orientation = LocalConfiguration.current.orientation
    var previousOrientation by remember { mutableIntStateOf(orientation) }
    LaunchedEffect(orientation) {
        if (orientation != previousOrientation) {
            when (orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> if (!fullscreen) onToggleFullscreen()
                Configuration.ORIENTATION_PORTRAIT -> if (fullscreen) onToggleFullscreen()
            }
            previousOrientation = orientation
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && !scrubbing) {
            delay(3_000)
            controlsVisible = false
        }
    }

    val interaction = remember { MutableInteractionSource() }
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = !audioOnly
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = {
                it.player = player
                it.resizeMode = when (aspectMode) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                it.keepScreenOn = !audioOnly
                it.visibility = if (audioOnly) android.view.View.INVISIBLE else android.view.View.VISIBLE
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gestures: tap = controls, double tap left/right = ∓10 s, horizontal swipe = seek with preview,
        // vertical swipe left = brightness, right = volume.
        if (audioOnly) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Headphones, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Только звук", color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (title.isNotBlank()) Text(title, color = Color.White.copy(alpha = .7f), maxLines = 2, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
        var gestureLabel by remember { mutableStateOf("") }
        var gestureTick by remember { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player, locked) {
                    var boosted = false
                    var prevSpeed = 1f
                    detectTapGestures(
                        onPress = {
                            if (locked) return@detectTapGestures
                            val job = scope.launch {
                                delay(450)
                                boosted = true
                                prevSpeed = player.playbackParameters.speed
                                player.setPlaybackSpeed(2f)
                                gestureLabel = "2× скорость"; gestureTick++
                            }
                            tryAwaitRelease()
                            job.cancel()
                            if (boosted) { boosted = false; player.setPlaybackSpeed(prevSpeed) }
                        },
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val forward = o.x > size.width / 2f
                            val target = if (forward) player.currentPosition + 10_000L else player.currentPosition - 10_000L
                            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                            player.seekTo(target.coerceIn(0L, dur ?: Long.MAX_VALUE))
                            gestureLabel = if (forward) "+10 с" else "−10 с"
                            gestureTick++
                        },
                    )
                }
                .pointerInput(player, locked) {
                    if (locked) return@pointerInput
                    var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
                    var dx = 0f; var dy = 0f; var startX = 0f
                    var seekFrom = 0L; var seekTo = 0L
                    var brightFrom = 0.5f; var volFrom = 0; val volMax = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; dx = 0f; dy = 0f; startX = o.x },
                        onDragEnd = {
                            if (mode == 1) player.seekTo(seekTo)
                            mode = 0; gestureTick++
                        },
                        onDragCancel = { mode = 0; gestureTick++ },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x; dy += amount.y
                            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                            if (mode == 0 && (kotlin.math.abs(dx) > 24f || kotlin.math.abs(dy) > 24f)) {
                                mode = when {
                                    kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dur > 0L && player.isCurrentMediaItemSeekable) 1 else -1
                                    startX < size.width / 2f -> 2
                                    else -> 3
                                }
                                seekFrom = player.currentPosition.coerceAtLeast(0L)
                                brightFrom = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                volFrom = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            }
                            when (mode) {
                                1 -> {
                                    // Full width ≈ 3 minutes (or the whole clip if shorter).
                                    val range = dur.coerceAtMost(180_000L).coerceAtLeast(30_000L)
                                    val delta = (dx / size.width * range).toLong()
                                    seekTo = (seekFrom + delta).coerceIn(0L, dur)
                                    gestureLabel = "${if (delta >= 0) "+" else "−"}${formatTime(kotlin.math.abs(delta))}\n${formatTime(seekTo)} / ${formatTime(dur)}"
                                }
                                2 -> activity?.window?.let { w ->
                                    val v = (brightFrom - dy / size.height).coerceIn(0.02f, 1f)
                                    w.attributes = w.attributes.apply { screenBrightness = v }
                                    gestureLabel = "Яркость ${(v * 100).toInt()}%"
                                }
                                3 -> {
                                    val v = (volFrom - dy / size.height * volMax * 1.3f).toInt().coerceIn(0, volMax)
                                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                                    gestureLabel = "Громкость ${v * 100 / volMax}%"
                                }
                            }
                        },
                    )
                },
        )
        LaunchedEffect(gestureTick) {
            if (gestureLabel.isNotBlank()) { delay(700); gestureLabel = "" }
        }
        DisposableEffect(activity) {
            onDispose {
                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
            }
        }
        if (gestureLabel.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = .62f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    gestureLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)))

            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "Назад", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (!locked) {
                    IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, "Скачать", tint = Color.White) }
                    IconButton(onClick = { onAudioOnlyChange(!audioOnly) }) {
                        Icon(Icons.Outlined.Headphones, "Только звук", tint = if (audioOnly) MaterialTheme.colorScheme.tertiary else Color.White)
                    }
                    IconButton(onClick = {
                        aspectMode = (aspectMode + 1) % 3
                        gestureLabel = when (aspectMode) { 1 -> "Растянуть"; 2 -> "Обрезать по экрану"; else -> "По размеру" }; gestureTick++
                    }) { Icon(Icons.Outlined.AspectRatio, "Пропорции", tint = Color.White) }
                }
                IconButton(onClick = { locked = !locked; gestureLabel = if (locked) "Экран заблокирован" else "Разблокировано"; gestureTick++ }) {
                    Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, "Блокировка экрана", tint = if (locked) MaterialTheme.colorScheme.tertiary else Color.White)
                }
                if (!locked) IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, "Настройки воспроизведения", tint = Color.White)
                }
            }

            if (!locked) Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = {
                        player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                        controlsVisible = true
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .55f),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Outlined.Replay10, "Назад на 10 секунд")
                }

                FilledIconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        controlsVisible = true
                    },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Icon(
                        if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (isPlaying) "Пауза" else "Воспроизвести",
                        modifier = Modifier.size(34.dp),
                    )
                }

                FilledIconButton(
                    onClick = {
                        val target = player.currentPosition + 10_000L
                        player.seekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
                        controlsVisible = true
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .55f),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Outlined.Forward10, "Вперёд на 10 секунд")
                }
            }

            if (!locked) Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${formatTime(if (scrubbing) scrubPosition else positionMs)} / ${formatTime(durationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    if (Build.VERSION.SDK_INT >= 26 && !fullscreen) {
                        IconButton(
                            onClick = {
                                activity?.enterPictureInPictureMode(
                                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                                )
                            },
                        ) {
                            Icon(Icons.Outlined.PictureInPictureAlt, "PiP", tint = Color.White)
                        }
                    }
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(
                            if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            if (fullscreen) "Выйти из полного экрана" else "Полный экран",
                            tint = Color.White,
                        )
                    }
                }

                // Compact YouTube-like timeline at the very bottom edge of the video.
                Box(Modifier.fillMaxWidth().height(28.dp)) {
                    if (durationMs > 0L && bufferedMs > 0L) {
                        LinearProgressIndicator(
                            progress = { (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center),
                            color = Color.White.copy(alpha = .36f),
                            trackColor = Color.White.copy(alpha = .14f),
                        )
                    }
                    val seekable = durationMs > 0L && player.isCurrentMediaItemSeekable
                    Slider(
                        value = (if (scrubbing) scrubPosition else positionMs).coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                        onValueChange = { value ->
                            if (!scrubbing) {
                                // Keyframe seeks while dragging give an instant live preview of the frame.
                                (player as? androidx.media3.exoplayer.ExoPlayer)?.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                                lastPreviewSeek = -1L
                            }
                            scrubbing = true
                            scrubPosition = value.toLong()
                            controlsVisible = true
                            val step = (durationMs / 60L).coerceAtLeast(1_500L)
                            if (lastPreviewSeek < 0L || kotlin.math.abs(scrubPosition - lastPreviewSeek) >= step) {
                                lastPreviewSeek = scrubPosition
                                player.seekTo(scrubPosition)
                            }
                        },
                        onValueChangeFinished = {
                            (player as? androidx.media3.exoplayer.ExoPlayer)?.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                            player.seekTo(scrubPosition.coerceAtLeast(0L))
                            positionMs = scrubPosition
                            scrubbing = false
                        },
                        enabled = seekable,
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000L
    val seconds = total % 60L
    val minutes = (total / 60L) % 60L
    val hours = total / 3600L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

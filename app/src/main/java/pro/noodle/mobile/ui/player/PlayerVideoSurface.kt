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

@Composable
internal fun PlayerVideoSurface(
    player: Player,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    activity: android.app.Activity?,
    onFrameSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var locked by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableIntStateOf(0) } // 0 fit, 1 stretch, 2 crop
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
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) {
                player.pause()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
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
                    pro.noodle.mobile.core.similar.PlayerFrameHolder.view = java.lang.ref.WeakReference(this)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
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
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gestures: tap = controls, double tap left/right = ∓10 s, horizontal swipe = seek with preview,
        // vertical swipe left = brightness, right = volume.
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
                    var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
                    var dx = 0f; var dy = 0f; var startX = 0f
                    var seekFrom = 0L; var seekTo = 0L
                    var brightFrom = 0.5f; var volFrom = 0; val volMax = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; dx = 0f; dy = 0f; startX = o.x },
                        onDragEnd = {
                            if (mode == 1) player.seekTo(seekTo)
                            // Палец чуть «поплыл» при тапе: это всё равно тап, а не жест.
                            if (mode == 0) controlsVisible = !controlsVisible
                            mode = 0; gestureTick++
                        },
                        onDragCancel = { mode = 0; gestureTick++ },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x; dy += amount.y
                            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                            if (locked) return@detectDragGestures
                            if (mode == 0 && (kotlin.math.abs(dx) > 48f || kotlin.math.abs(dy) > 48f)) {
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
                    IconButton(onClick = {
                        aspectMode = (aspectMode + 1) % 3
                        gestureLabel = when (aspectMode) { 1 -> "Растянуть"; 2 -> "Обрезать по экрану"; else -> "По размеру" }; gestureTick++
                    }) { Icon(Icons.Outlined.AspectRatio, "Пропорции", tint = Color.White) }
                }
                IconButton(onClick = { locked = !locked; gestureLabel = if (locked) "Экран заблокирован" else "Разблокировано"; gestureTick++ }) {
                    Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, "Блокировка экрана", tint = if (locked) MaterialTheme.colorScheme.tertiary else Color.White)
                }
                if (!locked) IconButton(onClick = onFrameSearch) {
                    Icon(Icons.Outlined.PhotoCamera, "Сохранить кадр", tint = Color.White)
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
                    pro.noodle.mobile.ui.NoodleSlider(
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
                        modifier = Modifier.fillMaxWidth().height(32.dp).align(Alignment.BottomCenter),
                        thumbSize = 18.dp,
                        ringWidth = 5.dp,
                        trackHeight = 5.dp,
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

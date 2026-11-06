package pro.noodle.mobile.ui.player

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

@Composable
internal fun PlayerVideoSurface(
    player: Player,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    activity: android.app.Activity?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dubbingController = remember(player) {
        RussianDubbingController(context.applicationContext, player)
    }
    val dubbingState by dubbingController.state.collectAsState()
    var showDubbingSettings by remember { mutableStateOf(false) }

    DisposableEffect(dubbingController) {
        onDispose { dubbingController.close() }
    }

    LaunchedEffect(dubbingState.requiresSetup) {
        if (dubbingState.requiresSetup) showDubbingSettings = true
    }

    if (showDubbingSettings) {
        DubbingSettingsDialog(
            controller = dubbingController,
            onDismiss = { showDubbingSettings = false },
        )
    }

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
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { controlsVisible = !controlsVisible },
                ),
        )

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
                FilledTonalButton(
                    onClick = {
                        dubbingController.toggle()
                        controlsVisible = true
                    },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (dubbingState.enabled) {
                            Color.White.copy(alpha = .92f)
                        } else {
                            Color.Black.copy(alpha = .55f)
                        },
                        contentColor = if (dubbingState.enabled) Color.Black else Color.White,
                    ),
                ) {
                    Icon(Icons.Outlined.Translate, "Русская AI-озвучка", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (dubbingState.enabled) "RU AI" else "RU", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = { showDubbingSettings = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Outlined.Tune, "Настройки RU AI", tint = Color.White)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, "Настройки воспроизведения", tint = Color.White)
                }
            }

            Row(
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

            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                if (dubbingState.phase != DubbingPhase.OFF) {
                    Surface(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        color = Color.Black.copy(alpha = .62f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            dubbingState.message,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
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

@Composable
private fun DubbingSettingsDialog(
    controller: RussianDubbingController,
    onDismiss: () -> Unit,
) {
    val initial = remember { controller.configuration() }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var mode by remember { mutableStateOf(initial.mode) }
    var voiceName by remember { mutableStateOf(initial.voiceName) }
    var apiKey by remember { mutableStateOf("") }
    val storedKey = controller.hasAsrApiKey()
    val voices = controller.russianVoiceNames()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Русская AI-озвучка") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "RU AI всегда переводит именно речь из аудиодорожки. Субтитры для автодубляжа не требуются и не используются как источник.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == DubbingMode.VOICE_OVER,
                        onClick = { mode = DubbingMode.VOICE_OVER },
                        label = { Text("Закадровый") },
                    )
                    FilterChip(
                        selected = mode == DubbingMode.DUB,
                        onClick = { mode = DubbingMode.DUB },
                        label = { Text("Дубляж") },
                    )
                }

                Text("Русский голос", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = voiceName.isBlank(),
                        onClick = { voiceName = "" },
                        label = { Text("Авто / по спикерам") },
                    )
                    voices.forEach { voice ->
                        FilterChip(
                            selected = voiceName == voice,
                            onClick = { voiceName = voice },
                            label = { Text(voice.substringAfterLast('.').take(22)) },
                        )
                    }
                }

                HorizontalDivider()
                Text("Распознавание речи и автодубляж", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    singleLine = true,
                    label = { Text("Endpoint") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    singleLine = true,
                    label = { Text("Модель") },
                    supportingText = { Text("По умолчанию: speaker-aware diarization") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (storedKey) "API key (уже сохранён)" else "API key") },
                    supportingText = {
                        Text(
                            if (storedKey) {
                                "Оставьте пустым, чтобы сохранить текущий ключ."
                            } else {
                                "Ключ шифруется Android Keystore и не пишется в открытом виде."
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Аудио распознаётся по речи; облачный ASR может тарифицироваться выбранным провайдером.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    controller.saveConfiguration(
                        DubbingConfig(
                            endpoint = endpoint.trim().ifBlank { DubbingConfig.DEFAULT_ENDPOINT },
                            model = model.trim().ifBlank { DubbingConfig.DEFAULT_MODEL },
                            mode = mode,
                            voiceName = voiceName,
                        ),
                        newApiKey = apiKey.trim().takeIf { it.isNotBlank() },
                    )
                    onDismiss()
                }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
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

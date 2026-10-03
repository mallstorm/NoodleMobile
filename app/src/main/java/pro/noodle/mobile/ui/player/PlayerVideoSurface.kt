package pro.noodle.mobile.ui.player

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }

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

        // Always keep a Compose hit target above the Android PlayerView. Without this layer
        // PlayerView can consume the first touch and the custom controls may never reappear.
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

                // Timeline belongs to the lower edge of the video, never to its centre.
                Box(Modifier.fillMaxWidth().height(28.dp)) {
                    if (durationMs > 0L && bufferedMs > 0L) {
                        LinearProgressIndicator(
                            progress = { (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.Center),
                            color = Color.White.copy(alpha = .38f),
                            trackColor = Color.White.copy(alpha = .16f),
                        )
                    }
                    Slider(
                        value = (if (scrubbing) scrubPosition else positionMs).toFloat().coerceAtLeast(0f),
                        onValueChange = { value ->
                            scrubbing = true
                            scrubPosition = value.toLong()
                            controlsVisible = true
                        },
                        onValueChangeFinished = {
                            player.seekTo(scrubPosition.coerceAtLeast(0L))
                            positionMs = scrubPosition
                            scrubbing = false
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
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

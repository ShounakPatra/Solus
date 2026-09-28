package com.shounak.localmeshai.ui.components

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object AudioPlayerCoordinator {
    val activeAudioPath = mutableStateOf<String?>(null)
}

@Composable
fun AudioMessagePlayer(
    audioPath: String,
    audioName: String? = null,
    initialDurationMs: Long = 0L,
    isUser: Boolean = true,
    modifier: Modifier = Modifier
) {
    val file = remember(audioPath) { File(audioPath) }
    var fileExists by remember(audioPath) { mutableStateOf(file.exists()) }
    var mediaPlayer by remember(audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(audioPath) { mutableStateOf(false) }
    var currentPositionMs by remember(audioPath) { mutableLongStateOf(0L) }
    var durationMs by remember(audioPath, initialDurationMs) {
        mutableLongStateOf(initialDurationMs.coerceAtLeast(0L))
    }

    // Resolve duration in background if not known
    LaunchedEffect(audioPath) {
        withContext(Dispatchers.IO) {
            val f = File(audioPath)
            fileExists = f.exists()
            if (f.exists()) {
                if (durationMs <= 0L) {
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(f.absolutePath)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val dur = durStr?.toLongOrNull()
                        retriever.release()
                        if (dur != null && dur > 0L) {
                            withContext(Dispatchers.Main) {
                                durationMs = dur
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (durationMs <= 0L && f.extension.equals("wav", ignoreCase = true) && f.length() > 44) {
                    val calcDur = ((f.length() - 44) * 1000L / (16000 * 2)).coerceAtLeast(1000L)
                    withContext(Dispatchers.Main) {
                        durationMs = calcDur
                    }
                }
            }
        }
    }

    // Pause if another player became active
    val activePath by AudioPlayerCoordinator.activeAudioPath
    LaunchedEffect(activePath) {
        if (activePath != audioPath && isPlaying) {
            mediaPlayer?.runCatching { pause() }
            isPlaying = false
        }
    }

    // Progress tick while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val player = mediaPlayer
            if (player != null) {
                runCatching {
                    currentPositionMs = player.currentPosition.toLong()
                    if (durationMs <= 0L && player.duration > 0) {
                        durationMs = player.duration.toLong()
                    }
                }
            }
            delay(50L)
        }
    }

    // Clean up MediaPlayer on unmount or path change
    DisposableEffect(audioPath) {
        onDispose {
            mediaPlayer?.runCatching {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
            isPlaying = false
            if (AudioPlayerCoordinator.activeAudioPath.value == audioPath) {
                AudioPlayerCoordinator.activeAudioPath.value = null
            }
        }
    }

    fun startPlayback() {
        if (!fileExists) return
        val player = mediaPlayer ?: try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    isPlaying = false
                    currentPositionMs = 0L
                    if (AudioPlayerCoordinator.activeAudioPath.value == audioPath) {
                        AudioPlayerCoordinator.activeAudioPath.value = null
                    }
                }
                if (durationMs <= 0L && duration > 0) {
                    durationMs = duration.toLong()
                }
            }.also { mediaPlayer = it }
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        AudioPlayerCoordinator.activeAudioPath.value = audioPath
        player.start()
        isPlaying = true
    }

    fun pausePlayback() {
        mediaPlayer?.runCatching { pause() }
        isPlaying = false
        if (AudioPlayerCoordinator.activeAudioPath.value == audioPath) {
            AudioPlayerCoordinator.activeAudioPath.value = null
        }
    }

    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        mediaPlayer?.runCatching {
            seekTo(positionMs.toInt())
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val containerBg = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f)
    }
    val borderCol = primaryColor.copy(alpha = 0.28f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = containerBg,
        border = BorderStroke(1.dp, borderCol)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play / Pause round button
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = primaryColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                pausePlayback()
                            } else {
                                startPlayback()
                            }
                        },
                        enabled = fileExists,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause audio" else "Play audio",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Audio title & soundbars
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (!fileExists) "Audio unavailable" else (audioName ?: "Voice message"),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (fileExists) {
                            AudioWaveformBars(
                                isPlaying = isPlaying,
                                color = primaryColor
                            )
                        }
                    }

                    // Time display: 0:04 / 0:18
                    Text(
                        text = if (!fileExists) "File was removed" else "${formatTime(currentPositionMs)} / ${formatTime(durationMs.coerceAtLeast(currentPositionMs))}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Seekable track bar
            if (fileExists && durationMs > 0L) {
                AudioProgressBar(
                    currentMs = currentPositionMs,
                    totalMs = durationMs,
                    activeColor = primaryColor,
                    onSeek = { seekTo(it) }
                )
            }
        }
    }
}

@Composable
private fun AudioProgressBar(
    currentMs: Long,
    totalMs: Long,
    activeColor: Color,
    onSeek: (Long) -> Unit
) {
    val progress = if (totalMs > 0L) {
        (currentMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(totalMs) {
                detectTapGestures { offset ->
                    if (totalMs > 0L && size.width > 0) {
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((fraction * totalMs).toLong())
                    }
                }
            }
            .pointerInput(totalMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (totalMs > 0L && size.width > 0) {
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek((fraction * totalMs).toLong())
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (totalMs > 0L && size.width > 0) {
                            val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek((fraction * totalMs).toLong())
                        }
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()

        // Background inactive track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
        )

        // Active progress track
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progress)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(activeColor)
        )

        // Circle thumb indicator
        val thumbRadiusPx = 5.dp.value * 2f // ~10dp diameter
        val thumbOffsetPx = ((widthPx * progress) - (thumbRadiusPx / 2f)).coerceIn(0f, (widthPx - thumbRadiusPx).coerceAtLeast(0f))
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetPx.toInt(), 0) }
                .size(10.dp)
                .clip(CircleShape)
                .background(activeColor)
        )
    }
}

@Composable
private fun AudioWaveformBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_bars")
    val b1 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar1"
    )
    val b2 by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(310, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar2"
    )
    val b3 by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(490, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar3"
    )
    val b4 by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(370, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar4"
    )

    val barValues = listOf(b1, b2, b3, b4)

    Row(
        modifier = modifier.height(13.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        barValues.forEach { anim ->
            val scale = if (isPlaying) anim else 0.35f
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((13 * scale).dp.coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

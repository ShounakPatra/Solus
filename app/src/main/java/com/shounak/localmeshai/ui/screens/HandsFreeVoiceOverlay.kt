package com.shounak.localmeshai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import com.shounak.localmeshai.models.state.VoiceModePhase
import com.shounak.localmeshai.models.state.VoiceModeUiState
import com.shounak.localmeshai.ui.components.LiveAuroraBackground
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

/**
 * Fullscreen Obsidian Dark Glassmorphism overlay for Milestone 2 Hands-Free Two-Way Offline Voice Mode.
 */
@Composable
fun HandsFreeVoiceOverlay(
    state: VoiceModeUiState,
    hazeState: HazeState,
    activeModelName: String?,
    onDownloadVoiceAssets: () -> Unit,
    onOpenModelsTab: () -> Unit,
    onToggleMute: () -> Unit,
    onInterrupt: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val phaseAccent by animateColorAsState(
        targetValue = when (state.phase) {
            VoiceModePhase.Initializing -> Color(0xFF60A5FA)
            VoiceModePhase.Listening -> if (state.isMuted) Color(0xFF94A3B8) else Color(0xFF38BDF8)
            VoiceModePhase.Transcribing -> Color(0xFFF59E0B)
            VoiceModePhase.Thinking -> Color(0xFFA855F7)
            VoiceModePhase.Speaking -> Color(0xFF10B981)
            VoiceModePhase.MissingAssets -> Color(0xFF38BDF8)
            VoiceModePhase.Error -> colors.error
            VoiceModePhase.Idle -> Color(0xFF94A3B8)
        },
        label = "voice_phase_accent"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb_pulse")
    val idleBreathRaw by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_orb_breath"
    )
    val idleBreath = if (state.phase == VoiceModePhase.Initializing || state.phase == VoiceModePhase.Idle) 1f else idleBreathRaw
    val levelScale by animateFloatAsState(
        targetValue = if (state.phase == VoiceModePhase.Initializing) 1f else 1f + (state.micLevel * 0.48f).coerceIn(0f, 0.48f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voice_mic_scale"
    )
    val smoothMicLevel by animateFloatAsState(
        targetValue = if (state.isMuted && state.phase == VoiceModePhase.Listening) 0f else state.micLevel.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "voice_smooth_mic_level"
    )
    var wavePhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.phase, state.isMuted) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dt = ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    val speed = when {
                        state.isMuted && state.phase == VoiceModePhase.Listening -> 0.6f
                        state.phase == VoiceModePhase.Speaking -> 7.2f
                        state.phase == VoiceModePhase.Thinking || state.phase == VoiceModePhase.Transcribing -> 4.6f
                        else -> 5.4f
                    }
                    wavePhase = (wavePhase + dt * speed) % (2f * PI.toFloat() * 100f)
                }
                lastNanos = nanos
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF060913).copy(alpha = 0.985f))
    ) {
        LiveAuroraBackground(
            accentColor = phaseAccent,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hands-Free Voice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = activeModelName?.let { "Offline · $it" } ?: "100% On-Device",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }

                LiquidGlassButton(
                    onClick = onClose,
                    hazeState = hazeState,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    tintColor = colors.surfaceContainerHighest,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit voice mode",
                        tint = colors.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Pill
            Surface(
                shape = RoundedCornerShape(50),
                color = phaseAccent.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, phaseAccent.copy(alpha = 0.55f))
            ) {
                Text(
                    text = if (state.phase == VoiceModePhase.Listening && state.isMuted) "Muted" else state.phase.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = phaseAccent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Center Content: Missing Assets OR Live Orb + Transcript
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (state.phase == VoiceModePhase.MissingAssets) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassEffect(
                                hazeState = hazeState,
                                shape = RoundedCornerShape(24.dp),
                                blurRadius = 22.dp,
                                tintColor = colors.surfaceContainerHigh.copy(alpha = 0.72f),
                                borderAlpha = 0.40f
                            )
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Download Offline Voice Models",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Voice Mode runs 100% locally using Silero VAD, Whisper Tiny EN, and TinyVoca TTS. None are bundled in the APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            VoiceReadinessRow("Silero VAD ONNX (~2.3 MB)", state.readiness.hasSileroVad)
                            VoiceReadinessRow("Whisper Tiny EN INT8 (~43 MB)", state.readiness.hasWhisperStt)
                            VoiceReadinessRow("TinyVoca Neural TTS (~21.4 MB)", state.readiness.hasTinyVoca)
                        }

                        if (state.isDownloadingAssets) {
                            LinearProgressIndicator(
                                progress = { state.downloadProgress.coerceIn(0.01f, 0.99f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF38BDF8)
                            )
                            Text(
                                text = state.downloadLabel.ifBlank { "Downloading voice models…" },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF38BDF8)
                            )
                        } else {
                            if (!state.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                            LiquidGlassButton(
                                onClick = onDownloadVoiceAssets,
                                hazeState = hazeState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = Color(0xFF38BDF8).copy(alpha = 0.28f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Download Required Models", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            TextButton(onClick = onOpenModelsTab) {
                                Text("Manage in Model Manager", color = colors.primary)
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Animated Voice Orb
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(164.dp)
                                    .graphicsLayer {
                                        val s = (idleBreath * levelScale).coerceIn(0.9f, 1.44f)
                                        scaleX = s
                                        scaleY = s
                                    }
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                phaseAccent.copy(alpha = 0.44f),
                                                phaseAccent.copy(alpha = 0.14f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(134.dp)
                                    .graphicsLayer {
                                        val s = 0.96f + smoothMicLevel * 0.24f
                                        scaleX = s
                                        scaleY = s
                                    }
                                    .clip(CircleShape)
                                    .border(1.dp, phaseAccent.copy(alpha = (0.14f + smoothMicLevel * 0.55f).coerceIn(0.14f, 0.72f)), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(112.dp)
                                    .clip(CircleShape)
                                    .background(phaseAccent.copy(alpha = 0.22f))
                                    .border(1.5.dp, phaseAccent.copy(alpha = 0.72f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isMuted && state.phase == VoiceModePhase.Listening) {
                                    Icon(
                                        imageVector = Icons.Default.MicOff,
                                        contentDescription = "Muted",
                                        tint = phaseAccent,
                                        modifier = Modifier.size(40.dp)
                                    )
                                } else {
                                    VoiceIntensityBars(
                                        micLevel = smoothMicLevel,
                                        phase = state.phase,
                                        wavePhase = wavePhase,
                                        barColor = phaseAccent,
                                        modifier = Modifier.size(52.dp)
                                    )
                                }
                            }
                        }

                        // Live Transcript & Streaming Reply Box
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .glassEffect(
                                    hazeState = hazeState,
                                    shape = RoundedCornerShape(20.dp),
                                    blurRadius = 18.dp,
                                    tintColor = colors.surfaceContainer.copy(alpha = 0.65f),
                                    borderAlpha = 0.35f
                                )
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!state.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.error
                                )
                            }
                            if (state.lastUserTranscript.isNotBlank()) {
                                Text(
                                    text = "You: ${state.lastUserTranscript}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                            } else if (state.phase == VoiceModePhase.Initializing) {
                                Text(
                                    text = "Initializing Silero VAD, Whisper STT, and TinyVoca voice engines…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            } else if (state.phase == VoiceModePhase.Listening) {
                                Text(
                                    text = "Speak naturally — Solus listens with Silero VAD, transcribes locally with Whisper, and interrupts immediately if you speak over it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (state.streamingAssistantText.isNotBlank()) {
                                Text(
                                    text = state.streamingAssistantText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Control Bar
            if (state.phase != VoiceModePhase.MissingAssets) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiquidGlassButton(
                        onClick = onToggleMute,
                        hazeState = hazeState,
                        modifier = Modifier
                            .size(56.dp)
                            .border(
                                1.dp,
                                if (state.isMuted) colors.error.copy(alpha = 0.6f) else colors.outline.copy(alpha = 0.45f),
                                CircleShape
                            ),
                        shape = CircleShape,
                        tintColor = if (state.isMuted) colors.error.copy(alpha = 0.22f) else colors.surfaceContainerHighest,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (state.isMuted) "Unmute mic" else "Mute mic",
                            tint = if (state.isMuted) colors.error else colors.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (state.phase == VoiceModePhase.Speaking || state.phase == VoiceModePhase.Thinking || state.phase == VoiceModePhase.Transcribing) {
                        LiquidGlassButton(
                            onClick = onInterrupt,
                            hazeState = hazeState,
                            modifier = Modifier
                                .height(52.dp)
                                .border(1.dp, phaseAccent.copy(alpha = 0.65f), RoundedCornerShape(26.dp)),
                            shape = RoundedCornerShape(26.dp),
                            tintColor = phaseAccent.copy(alpha = 0.24f),
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Interrupt", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else if (state.phase == VoiceModePhase.Error) {
                        LiquidGlassButton(
                            onClick = onRetry,
                            hazeState = hazeState,
                            modifier = Modifier
                                .height(52.dp)
                                .border(1.dp, colors.primary.copy(alpha = 0.65f), RoundedCornerShape(26.dp)),
                            shape = RoundedCornerShape(26.dp),
                            tintColor = colors.primary.copy(alpha = 0.24f),
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) {
                            Text("Retry Voice", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    LiquidGlassButton(
                        onClick = onClose,
                        hazeState = hazeState,
                        modifier = Modifier
                            .size(56.dp)
                            .border(1.dp, colors.error.copy(alpha = 0.65f), CircleShape),
                        shape = CircleShape,
                        tintColor = colors.error.copy(alpha = 0.24f),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "End voice mode",
                            tint = colors.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceReadinessRow(label: String, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (ready) "Downloaded" else "Missing",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (ready) Color(0xFF10B981) else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun VoiceIntensityBars(
    micLevel: Float,
    phase: VoiceModePhase,
    wavePhase: Float,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barCount = 5
        val envelopes = floatArrayOf(0.46f, 0.78f, 1.0f, 0.78f, 0.46f)
        val totalWidth = size.width
        val totalHeight = size.height
        val barWidth = totalWidth / 9.2f
        val gap = (totalWidth - barWidth * barCount) / (barCount - 1)
        val minHeight = barWidth * 1.25f
        val maxHeight = totalHeight * 0.92f

        if (phase == VoiceModePhase.Initializing || phase == VoiceModePhase.Idle) {
            for (i in 0 until barCount) {
                val h = minHeight
                val x = i * (barWidth + gap)
                val y = (totalHeight - h) * 0.5f
                drawRoundRect(
                    color = barColor.copy(alpha = 0.72f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
            return@Canvas
        }

        val baseDrive = when (phase) {
            VoiceModePhase.Listening -> (micLevel * 1.35f).coerceIn(0f, 1f)
            VoiceModePhase.Speaking -> maxOf(0.48f, (micLevel * 1.1f).coerceIn(0f, 1f))
            VoiceModePhase.Thinking, VoiceModePhase.Transcribing -> maxOf(0.28f, (micLevel * 0.9f).coerceIn(0f, 1f))
            else -> 0f
        }

        for (i in 0 until barCount) {
            val osc = (sin(wavePhase + i * 1.15f) + 1f) * 0.5f
            val secondaryOsc = (sin(wavePhase * 1.7f - i * 0.85f) + 1f) * 0.5f
            val waveMod = 0.42f + 0.58f * (osc * 0.65f + secondaryOsc * 0.35f)
            val fraction = (0.14f + baseDrive * waveMod).coerceIn(0.12f, 1f) * envelopes[i]
            val h = (minHeight + (maxHeight - minHeight) * fraction).coerceIn(minHeight, maxHeight)
            val x = i * (barWidth + gap)
            val y = (totalHeight - h) * 0.5f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

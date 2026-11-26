package com.shounak.localmeshai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.StarterModelRecommendation
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

@Composable
fun OnboardingOverlay(
    isVisible: Boolean,
    hazeState: HazeState? = null,
    starterRecommendation: StarterModelRecommendation? = null,
    starterModelState: ModelInfo? = starterRecommendation?.model,
    onStartStarterDownload: ((String) -> Unit)? = null,
    onPauseStarterDownload: ((String) -> Unit)? = null,
    onResumeStarterDownload: ((String) -> Unit)? = null,
    onUseStarterModel: ((ModelInfo) -> Unit)? = null,
    onGoToModels: () -> Unit
) {
    if (!isVisible) return

    val haptic = LocalHapticFeedback.current
    val effectiveHazeState = hazeState ?: remember { HazeState() }

    Dialog(
        onDismissRequest = onGoToModels,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(32.dp))
                        .glassEffect(
                            hazeState = effectiveHazeState,
                            shape = RoundedCornerShape(32.dp),
                            tintColor = Color(0xFF0F172A).copy(alpha = 0.92f)
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.6f),
                                    Color(0xFFA855F7).copy(alpha = 0.4f),
                                    Color(0xFF3B82F6).copy(alpha = 0.2f)
                                )
                            ),
                            shape = RoundedCornerShape(32.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header Icon Badge
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF0EA5E9).copy(alpha = 0.4f),
                                            Color(0xFF8B5CF6).copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Title
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Welcome to Solus",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "100% Offline & Private AI",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF7DD3FC)
                                )
                            }
                        }

                        // Device-tuned starter recommendation card (downloaded on-demand via Model Manager)
                        if (starterRecommendation != null && starterModelState != null) {
                            StarterModelCard(
                                recommendation = starterRecommendation,
                                model = starterModelState,
                                onStartDownload = { onStartStarterDownload?.invoke(starterModelState.id) },
                                onPauseDownload = { onPauseStarterDownload?.invoke(starterModelState.id) },
                                onResumeDownload = { onResumeStarterDownload?.invoke(starterModelState.id) },
                                onUseModel = { onUseStarterModel?.invoke(starterModelState) }
                            )
                        }

                        // Steps List
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OnboardingStepCard(
                                stepNumber = "1",
                                title = "Download On-Demand",
                                description = "Models download via resumable HTTPS with SHA-256 verification and stay inside your private app storage.",
                                icon = Icons.Default.Download,
                                accentColor = Color(0xFF38BDF8)
                            )

                            OnboardingStepCard(
                                stepNumber = "2",
                                title = "Chat Completely Offline",
                                description = "Once downloaded, turn off Wi-Fi and chat anytime. Zero telemetry, zero cloud accounts.",
                                icon = Icons.Default.Lock,
                                accentColor = Color(0xFFA855F7)
                            )

                            OnboardingStepCard(
                                stepNumber = "3",
                                title = "Thinking Mode & Docs",
                                description = "Enable Thinking Mode for step-by-step reasoning or attach documents & photos for local analysis.",
                                icon = Icons.Default.Psychology,
                                accentColor = Color(0xFF34D399)
                            )
                        }

                        // Primary CTA (Start chatting if starter is ready, Download starter if available, or Browse Models)
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (isPressed) 0.96f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "btnScale"
                        )

                        val canOneTapDownload = starterModelState != null &&
                            onStartStarterDownload != null &&
                            starterModelState.status == ModelStatus.NotDownloaded
                        val isStarterReady = starterModelState != null &&
                            onUseStarterModel != null &&
                            starterModelState.status == ModelStatus.Available

                        val primaryLabel = when {
                            isStarterReady -> "Start Chatting Now"
                            canOneTapDownload -> "Download Starter (${starterModelState!!.size})"
                            else -> "Browse All Models"
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .scale(scale)
                                .clip(RoundedCornerShape(26.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF0EA5E9),
                                            Color(0xFF8B5CF6)
                                        )
                                    )
                                )
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    when {
                                        isStarterReady -> onUseStarterModel?.invoke(starterModelState!!)
                                        canOneTapDownload -> onStartStarterDownload?.invoke(starterModelState!!.id)
                                        else -> onGoToModels()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = primaryLabel,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Icon(
                                    imageVector = if (isStarterReady) Icons.Default.PlayArrow else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (starterModelState != null) {
                            TextButton(onClick = onGoToModels) {
                                Text(
                                    text = "Browse All Models in Model Manager",
                                    fontSize = 13.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StarterModelCard(
    recommendation: StarterModelRecommendation,
    model: ModelInfo,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onUseModel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F2942).copy(alpha = 0.72f))
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Best Fit for Your Device",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7DD3FC)
                )
            }
            Text(
                text = recommendation.ramSummary,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }

        Text(
            text = "${model.cleanDisplayName} (${model.size})",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = recommendation.reason,
            fontSize = 12.sp,
            color = Color(0xFFCBD5E1),
            lineHeight = 16.sp
        )

        when (model.status) {
            ModelStatus.Downloading -> {
                LinearProgressIndicator(
                    progress = { model.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF38BDF8),
                    trackColor = Color(0xFF1E293B)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Downloading in background: ${(model.progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = Color(0xFF7DD3FC)
                    )
                    TextButton(onClick = onPauseDownload, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            }
            ModelStatus.Paused, ModelStatus.Failed -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model.errorMessage ?: "Download paused (${(model.progress * 100).toInt()}%)",
                        fontSize = 11.sp,
                        color = if (model.status == ModelStatus.Failed) Color(0xFFFCA5A5) else Color(0xFFFDE68A),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = if (model.status == ModelStatus.Paused) onResumeDownload else onStartDownload,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(if (model.status == ModelStatus.Paused) "Resume" else "Retry", fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                    }
                }
            }
            ModelStatus.Available -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable(onClick = onUseModel)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                    Text(
                        text = "Ready offline — tap Start Chatting Now",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF34D399)
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun OnboardingStepCard(
    stepNumber: String,
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1E293B).copy(alpha = 0.6f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$stepNumber. $title",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                lineHeight = 16.sp
            )
        }
    }
}

package com.shounak.localmeshai.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shounak.localmeshai.ai.voice.VoiceModelCatalog
import com.shounak.localmeshai.features.GlassActionDialog
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * Model Manager card for on-demand offline voice assets:
 * - Silero VAD ONNX (~2.3 MB)
 * - Whisper Tiny EN INT8 ONNX (~43 MB)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceModelsCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val revision by VoiceModelCatalog.revision.collectAsState()
    val downloadStatus by VoiceModelCatalog.downloadStatus.collectAsState()

    val hasVad = remember(revision) { VoiceModelCatalog.isInstalled(context, VoiceModelCatalog.SILERO_VAD) }
    val hasWhisper = remember(revision) { VoiceModelCatalog.isInstalled(context, VoiceModelCatalog.WHISPER_TINY_EN) }
    val allVoiceSttReady = hasVad && hasWhisper

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        GlassActionDialog(
            hazeState = hazeState,
            onDismiss = { showDeleteConfirm = false },
            title = "Delete offline voice models?",
            confirmLabel = "Delete",
            destructive = true,
            confirmEnabled = !downloadStatus.isDownloading,
            onConfirm = {
                showDeleteConfirm = false
                VoiceModelCatalog.delete(context, VoiceModelCatalog.SILERO_VAD)
                VoiceModelCatalog.delete(context, VoiceModelCatalog.WHISPER_TINY_EN)
            }
        ) {
            Text(
                "Removes Silero VAD (~2.3 MB) and Whisper Tiny EN (~43 MB) from local storage. You can re-download them anytime.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = MaterialTheme.shapes.medium,
                blurRadius = 16.dp,
                tintColor = colors.surfaceContainer
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF38BDF8).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice Mode (Silero VAD + Whisper)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "DictionLabs/silero-vad-onnx + Xenova/whisper-tiny.en • ~45 MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (hasVad) Color(0xFF10B981).copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.25f),
                    border = BorderStroke(0.8.dp, if (hasVad) Color(0xFF10B981) else colors.outlineVariant.copy(alpha = 0.40f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasVad) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                        }
                        Text(
                            text = if (hasVad) "VAD: Downloaded" else "VAD: 2.3 MB",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (hasVad) Color(0xFF10B981) else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (hasWhisper) Color(0xFF10B981).copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.25f),
                    border = BorderStroke(0.8.dp, if (hasWhisper) Color(0xFF10B981) else colors.outlineVariant.copy(alpha = 0.40f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasWhisper) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                        }
                        Text(
                            text = if (hasWhisper) "Whisper: Downloaded" else "Whisper: 43 MB",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (hasWhisper) Color(0xFF10B981) else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Text(
                text = "100% offline speech detection (Silero VAD) and INT8 English transcription (Whisper Tiny EN) for Hands-Free Two-Way Voice Mode.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            if (downloadStatus.isDownloading) {
                val fraction = downloadStatus.fraction.coerceIn(0.01f, 0.99f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF38BDF8),
                        trackColor = Color(0xFF26252F)
                    )
                    Text(
                        text = "Downloading ${downloadStatus.currentModelName}: ${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8)
                    )
                }
            } else if (!downloadStatus.errorMessage.isNullOrBlank()) {
                Text(
                    text = "Voice download error: ${downloadStatus.errorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!allVoiceSttReady && !downloadStatus.isDownloading) {
                    LiquidGlassButton(
                        onClick = {
                            val token = AppSettings.getInstance(context).settings.value.huggingFaceToken
                            scope.launch {
                                runCatching {
                                    VoiceModelCatalog.downloadMissingAssets(
                                        context = context,
                                        specs = listOf(VoiceModelCatalog.SILERO_VAD, VoiceModelCatalog.WHISPER_TINY_EN),
                                        bearerToken = token
                                    )
                                }
                            }
                        },
                        hazeState = hazeState,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.60f), RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        tintColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (downloadStatus.errorMessage != null) "Retry Voice Download" else "Download Voice Assets (~45 MB)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (hasVad || hasWhisper) {
                    LiquidGlassButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = !downloadStatus.isDownloading,
                        hazeState = hazeState,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .border(0.8.dp, colors.error.copy(alpha = 0.40f), RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        tintColor = colors.error.copy(alpha = 0.16f),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete Voice Models", style = MaterialTheme.typography.labelMedium, color = colors.error, fontWeight = FontWeight.SemiBold)
                    }
                }

                LiquidGlassButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/Xenova/whisper-tiny.en"))
                        context.startActivity(intent)
                    },
                    hazeState = hazeState,
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    tintColor = colors.surfaceContainerHighest,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "View on Hugging Face", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

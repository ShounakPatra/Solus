package com.shounak.localmeshai.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.window.Dialog
import com.shounak.localmeshai.ai.tts.TinyVocaManager
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.GlassDispersionCard
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

private val PurpleAccent = Color(0xFFA684FF)
private val DarkControlBg = Color(0xFF26252F)

@Composable
fun TinyVocaModelCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ttsManager = remember { TinyVocaManager.getInstance(context) }
    val isDownloaded by ttsManager.isModelDownloaded.collectAsState()
    val isResidentInRam by ttsManager.isModelResidentInRam.collectAsState()
    val downloadProgress by ttsManager.downloadProgress.collectAsState()
    val isSpeaking by ttsManager.isSpeaking.collectAsState()
    val appSettings = remember { AppSettings.getInstance(context) }
    val settingsData by appSettings.settings.collectAsState()
    val colors = MaterialTheme.colorScheme
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

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
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PurpleAccent.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = PurpleAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "TinyVoca Neural TTS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "n4ze3m/tiny-voca • 8.1 MB (Compact INT8)",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (isDownloaded) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        border = BorderStroke(0.8.dp, Color(0xFF10B981))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(12.dp))
                            Text(
                                text = if (isResidentInRam) "In RAM" else "Ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.outlineVariant.copy(alpha = 0.25f)
                    ) {
                        Text(
                            text = "Not downloaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Text(
                text = "Ultra-lightweight on-device neural voice model for offline answer reading. Pre-initialized in RAM for instant playback with F2 (Female) and M2 (Male) voices.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            // Download Progress Bar
            if (downloadProgress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { downloadProgress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = PurpleAccent,
                        trackColor = DarkControlBg
                    )
                    Text(
                        text = "Downloading TinyVoca: ${((downloadProgress ?: 0f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = PurpleAccent
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isDownloaded && downloadProgress == null) {
                    LiquidGlassButton(
                        onClick = {
                            ttsManager.restoreOrDownloadTinyVoca(
                                onComplete = {
                                    Toast.makeText(context, "TinyVoca model ready in RAM!", Toast.LENGTH_SHORT).show()
                                },
                                onError = { err ->
                                    Toast.makeText(context, "Failed: $err", Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        tintColor = PurpleAccent.copy(alpha = 0.28f),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = PurpleAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ttsManager.hasBundledAsset) "Restore Model (Offline / Instant)" else "Download Model (8.1 MB)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else if (isDownloaded) {
                    LiquidGlassButton(
                        onClick = {
                            if (isSpeaking) {
                                ttsManager.stop()
                            } else {
                                ttsManager.speak(
                                    text = "Hello! I am Solus, your private on-device AI assistant.",
                                    voice = settingsData.ttsVoice,
                                    speed = settingsData.ttsSpeed
                                )
                            }
                        },
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        tintColor = if (isSpeaking) colors.error.copy(alpha = 0.25f) else PurpleAccent.copy(alpha = 0.25f),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(if (isSpeaking) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isSpeaking) "Stop Preview" else "Test Voice (${settingsData.ttsVoice})", style = MaterialTheme.typography.labelMedium)
                    }

                    LiquidGlassButton(
                        onClick = {
                            showDeleteConfirmDialog = true
                        },
                        hazeState = hazeState,
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        tintColor = colors.error.copy(alpha = 0.15f),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Model", tint = colors.error, modifier = Modifier.size(16.dp))
                    }
                }

                // Hugging Face Link Button
                LiquidGlassButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/n4ze3m/tiny-voca"))
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

    if (showDeleteConfirmDialog) {
        Dialog(onDismissRequest = { showDeleteConfirmDialog = false }) {
            GlassDispersionCard(
                hazeState = hazeState,
                cornerRadius = 24.dp,
                blurRadius = 30.dp,
                tintColor = colors.surfaceContainerHigh.copy(alpha = 0.90f),
                borderAlpha = 0.40f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.error.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = "Delete TinyVoca Model?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }
                    Text(
                        text = "This removes the offline neural voice model from internal storage. You can restore or re-download it anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        LiquidGlassButton(
                            onClick = { showDeleteConfirmDialog = false },
                            hazeState = hazeState,
                            shape = RoundedCornerShape(12.dp),
                            tintColor = colors.surfaceContainerHighest.copy(alpha = 0.45f),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                        }
                        LiquidGlassButton(
                            onClick = {
                                showDeleteConfirmDialog = false
                                ttsManager.deleteTinyVocaModel()
                                Toast.makeText(context, "TinyVoca model removed", Toast.LENGTH_SHORT).show()
                            },
                            hazeState = hazeState,
                            shape = RoundedCornerShape(12.dp),
                            tintColor = colors.error.copy(alpha = 0.35f),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("Delete", style = MaterialTheme.typography.labelLarge, color = colors.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

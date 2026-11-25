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
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shounak.localmeshai.rag.EmbeddingModelStatus
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

private val CyanAccent = Color(0xFF00E5FF)
private val DarkControlBg = Color(0xFF1B242C)

/**
 * Model Manager card for the optional neural document-search embedder (minishlab/potion-base-2M).
 * Provides rich information, status badges, manual download on demand, and delete controls.
 */
@Composable
fun DocumentSearchModelCard(hazeState: HazeState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ragManager = remember { RagManager.getInstance(context) }
    val appSettings = remember { AppSettings.getInstance(context) }
    val settings by appSettings.settings.collectAsState()
    val status by ragManager.embeddingStatus.collectAsState()
    val filesRevision by ragManager.embeddingFilesRevision.collectAsState()
    val spec = EmbeddingModels.POTION_BASE_2M
    val installed = ragManager.embeddingEngine.algorithmId == spec.id
    val hasDownloadedFiles = remember(filesRevision, status) { EmbeddingModels.directory(context, spec).exists() }
    val sizeLabel = com.shounak.localmeshai.utils.ByteSizeLabel.wholeMib(spec.downloadBytes)
    val colors = MaterialTheme.colorScheme
    val busy = status is EmbeddingModelStatus.Downloading || status is EmbeddingModelStatus.Reindexing
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        com.shounak.localmeshai.features.GlassActionDialog(
            hazeState = hazeState,
            onDismiss = { confirmDelete = false },
            title = "Delete neural search model?",
            confirmLabel = "Delete model",
            destructive = true,
            confirmEnabled = !busy,
            onConfirm = {
                confirmDelete = false
                appSettings.updateSettings { it.copy(embeddingModelPromptSkipped = true) }
                ragManager.requestEmbeddingModel(EmbeddingModels.LEXICAL_ID)
            }
        ) {
            Text(
                "This removes the downloaded search model ($sizeLabel). Your documents stay in the knowledge base and use built-in keyword search. You can manually download the model again anytime.",
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
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyanAccent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Potion Base 2M Search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "minishlab/potion-base-2M • $sizeLabel (Static 64-D)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Status Pills in FlowRow below title/subtitle
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (installed) {
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
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                            Text(
                                text = "Installed",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
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
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceContainerHighest.copy(alpha = 0.50f),
                    border = BorderStroke(0.6.dp, colors.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Static 64-D",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceContainerHighest.copy(alpha = 0.50f),
                    border = BorderStroke(0.6.dp, colors.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Offline RAG",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Ultra-compact 64-dimensional neural embedding model optimized for mobile. Enables offline semantic vector search across all indexed documents, PDFs, and notes without cloud dependency. Works with any model backend (GGUF, LiteRT, ONNX).",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            // Download or Reindex Progress Bar
            when (val s = status) {
                is EmbeddingModelStatus.Downloading -> {
                    val fraction = (s.bytesDone.toFloat() / s.bytesTotal.coerceAtLeast(1L)).coerceIn(0.01f, 0.99f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = CyanAccent,
                            trackColor = DarkControlBg
                        )
                        Text(
                            "Downloading Potion Search: ${(fraction * 100).toInt()}% of $sizeLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent
                        )
                    }
                }
                is EmbeddingModelStatus.Reindexing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = CyanAccent,
                            trackColor = DarkControlBg
                        )
                        Text(
                            "Re-indexing documents… ${s.chunksDone}/${s.chunksTotal} chunks",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent
                        )
                    }
                }
                is EmbeddingModelStatus.Failed -> {
                    Text(
                        "Document search setup failed: ${s.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error
                    )
                }
                else -> Unit
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!installed && !hasDownloadedFiles && !busy) {
                    LiquidGlassButton(
                        onClick = {
                            appSettings.updateSettings { it.copy(embeddingModelPromptSkipped = false) }
                            ragManager.requestEmbeddingModel(spec.id)
                        },
                        hazeState = hazeState,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .border(1.dp, CyanAccent.copy(alpha = 0.60f), RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        tintColor = CyanAccent.copy(alpha = 0.30f),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (status is EmbeddingModelStatus.Failed) "Retry Download ($sizeLabel)" else "Download Search Model ($sizeLabel)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else if (installed || hasDownloadedFiles) {
                    LiquidGlassButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy,
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
                        Text("Delete Search Model", style = MaterialTheme.typography.labelMedium, color = colors.error, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Hugging Face Link Button
                LiquidGlassButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/minishlab/potion-base-2M"))
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

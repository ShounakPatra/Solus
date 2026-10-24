package com.shounak.localmeshai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shounak.localmeshai.rag.EmbeddingModelStatus
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

/**
 * Model Manager card for the document-search embedder. It is listed as a required component
 * after install, but the user may choose "Later"; the card then shrinks to a one-line reminder
 * and the model can still be fetched here or from Settings > RAG.
 */
@Composable
fun DocumentSearchModelCard(hazeState: HazeState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ragManager = remember { RagManager.getInstance(context) }
    val appSettings = remember { AppSettings.getInstance(context) }
    val settings by appSettings.settings.collectAsState()
    val status by ragManager.embeddingStatus.collectAsState()
    val spec = EmbeddingModels.BGE_SMALL_EN_V15
    val installed = settings.ragEmbeddingModel == spec.id
    val sizeMb = spec.downloadBytes / 1_000_000
    val colors = MaterialTheme.colorScheme
    val busy = status is EmbeddingModelStatus.Downloading || status is EmbeddingModelStatus.Reindexing
    val compact = installed || (settings.embeddingModelPromptSkipped && !busy && status !is EmbeddingModelStatus.Failed)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = colors.surfaceContainer),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (installed) Icons.Default.CheckCircle else Icons.Default.Search,
                    contentDescription = null,
                    tint = if (installed) colors.primary else colors.onSurface
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (installed || compact) "Document search model" else "Required: Document search model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            installed -> "${spec.displayName} installed · $sizeMb MB · runs offline"
                            compact -> "Not downloaded · $sizeMb MB · search uses keyword matching until then"
                            else -> "${spec.displayName} · $sizeMb MB. Lets Solus find answers in your documents by meaning, not just matching words. Runs fully offline."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (compact && !installed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    LiquidGlassButton(
                        onClick = { ragManager.requestEmbeddingModel(spec.id) },
                        hazeState = hazeState,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("Download", style = MaterialTheme.typography.labelMedium, color = colors.primary) }
                }
            }

            when (val s = status) {
                is EmbeddingModelStatus.Downloading -> {
                    val fraction = (s.bytesDone.toFloat() / s.bytesTotal.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = colors.primary)
                    Text(
                        "Downloading… ${(fraction * 100).toInt()}% of $sizeMb MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                is EmbeddingModelStatus.Reindexing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = colors.primary)
                    Text(
                        "Re-indexing documents… ${s.chunksDone}/${s.chunksTotal} chunks",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                is EmbeddingModelStatus.Failed -> Text(
                    "Download failed: ${s.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error
                )
                else -> Unit
            }

            if (!compact && !busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    LiquidGlassButton(
                        onClick = {
                            appSettings.updateSettings { it.copy(embeddingModelPromptSkipped = false) }
                            ragManager.requestEmbeddingModel(spec.id)
                        },
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = colors.primary.copy(alpha = 0.20f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (status is EmbeddingModelStatus.Failed) "Retry" else "Download $sizeMb MB",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary
                        )
                    }
                    LiquidGlassButton(
                        onClick = { appSettings.updateSettings { it.copy(embeddingModelPromptSkipped = true) } },
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text("Later", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

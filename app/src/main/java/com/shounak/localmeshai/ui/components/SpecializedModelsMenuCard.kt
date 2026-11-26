package com.shounak.localmeshai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shounak.localmeshai.ai.tts.TinyVocaManager
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

/**
 * Compact Glass UI menu for specialized on-device AI models (Document Search, TinyVoca Neural TTS,
 * and Offline Image Generation). Collapsed by default to keep Model Manager compact and clutter-free,
 * expanding on tap to display the model cards with download, delete, and selection controls.
 */
@Composable
fun SpecializedModelsMenuCard(
    hazeState: HazeState,
    imageGenerationModels: List<ModelInfo> = emptyList(),
    renderImageModelItem: (@Composable (ModelInfo) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val ttsManager = remember { TinyVocaManager.getInstance(context) }
    val isVocaDownloaded by ttsManager.isModelDownloaded.collectAsState()
    val voiceRevision by com.shounak.localmeshai.ai.voice.VoiceModelCatalog.revision.collectAsState()
    val isVoiceSttDownloaded = remember(voiceRevision) {
        com.shounak.localmeshai.ai.voice.VoiceModelCatalog.isInstalled(context, com.shounak.localmeshai.ai.voice.VoiceModelCatalog.SILERO_VAD) &&
            com.shounak.localmeshai.ai.voice.VoiceModelCatalog.isInstalled(context, com.shounak.localmeshai.ai.voice.VoiceModelCatalog.WHISPER_TINY_EN)
    }

    val ragManager = remember { RagManager.getInstance(context) }
    val searchSpec = EmbeddingModels.POTION_BASE_2M
    val isSearchInstalled = ragManager.embeddingEngine.algorithmId == searchSpec.id

    val reduceMotion = com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(),
        label = "menu_arrow_rotation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 18.dp,
                tintColor = colors.surfaceContainer.copy(alpha = 0.65f),
                borderAlpha = 0.35f
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Clickable Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Specialized AI Models",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Document search, voice & image generation",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(arrowRotation)
                    )
                }

                // Model status pills in clean FlowRow below title/subtitle
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 50.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Document Search Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSearchInstalled) Color(0xFF10B981).copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.30f),
                        border = BorderStroke(0.7.dp, if (isSearchInstalled) Color(0xFF10B981).copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.40f))
                    ) {
                        Text(
                            text = if (isSearchInstalled) "Search: Downloaded" else "Search: 7 MiB",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = if (isSearchInstalled) Color(0xFF10B981) else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                        )
                    }

                    // TinyVoca Voice Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isVocaDownloaded) Color(0xFF10B981).copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.30f),
                        border = BorderStroke(0.7.dp, if (isVocaDownloaded) Color(0xFF10B981).copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.40f))
                    ) {
                        Text(
                            text = if (isVocaDownloaded) "Voice: Downloaded" else "Voice: 21 MB",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = if (isVocaDownloaded) Color(0xFF10B981) else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                        )
                    }

                    if (imageGenerationModels.isNotEmpty()) {
                        val hasDownloadedImageModel = imageGenerationModels.any { it.status == ModelStatus.Available }
                        val defaultImageSizeLabel = imageGenerationModels.firstOrNull()?.size?.takeIf { it.isNotBlank() } ?: "~313 MB"
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (hasDownloadedImageModel) Color(0xFF10B981).copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.30f),
                            border = BorderStroke(0.7.dp, if (hasDownloadedImageModel) Color(0xFF10B981).copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = if (hasDownloadedImageModel) "Image: Downloaded" else "Image: $defaultImageSizeLabel",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = if (hasDownloadedImageModel) Color(0xFF10B981) else colors.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                            )
                        }
                    }

                    // Voice Mode (Silero VAD + Whisper) Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isVoiceSttDownloaded) Color(0xFF10B981).copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.30f),
                        border = BorderStroke(0.7.dp, if (isVoiceSttDownloaded) Color(0xFF10B981).copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.40f))
                    ) {
                        Text(
                            text = if (isVoiceSttDownloaded) "STT: Downloaded" else "STT: ~45 MB",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = if (isVoiceSttDownloaded) Color(0xFF10B981) else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                        )
                    }
                }
            }

            // Expandable Content: Document Search, TinyVoca, Voice STT & Image Generation Cards
            AnimatedVisibility(
                visible = isExpanded,
                enter = if (reduceMotion) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
                exit = if (reduceMotion) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(
                        color = colors.outlineVariant.copy(alpha = 0.25f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    DocumentSearchModelCard(hazeState = hazeState)

                    TinyVocaModelCard(hazeState = hazeState)

                    VoiceModelsCard(hazeState = hazeState)

                    if (renderImageModelItem != null) {
                        imageGenerationModels.forEach { imageModel ->
                            renderImageModelItem(imageModel)
                        }
                    }
                }
            }
        }
    }
}

package com.shounak.localmeshai.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.models.state.ImageGenerationState
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val RoseAccent = Color(0xFFFF7AA2)

@Composable
fun ImageGenerationProgressCard(
    state: ImageGenerationState,
    hazeState: HazeState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return
    val colors = MaterialTheme.colorScheme
    val elapsedText = (state as? ImageGenerationState.Generating)?.let { gen ->
        String.format(Locale.US, "%.1fs", gen.elapsedMs / 1000.0)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(18.dp),
                blurRadius = 20.dp,
                tintColor = colors.surfaceContainerHigh.copy(alpha = 0.86f),
                borderAlpha = 0.38f
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.4.dp,
                color = RoseAccent
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.statusText ?: "Preparing offline image engine…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = when (state) {
                    is ImageGenerationState.UnloadingChatModel -> "Freeing LLM RAM to avoid Android low-memory kill"
                    is ImageGenerationState.LoadingEngine -> "Mapping quantized ONNX weights on-device"
                    is ImageGenerationState.Generating -> buildString {
                        append(state.modelName)
                        append(" • Seed ")
                        append(state.seed)
                        if (elapsedText != null) {
                            append(" • ")
                            append(elapsedText)
                        }
                    }
                    else -> "100% offline • Zero telemetry"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LiquidGlassButton(
                onClick = onCancel,
                hazeState = hazeState,
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(10.dp),
                tintColor = colors.errorContainer.copy(alpha = 0.28f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = colors.error, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel", style = MaterialTheme.typography.labelSmall, color = colors.error)
            }
        }
    }
}

@Composable
fun GeneratedImageBubbleContent(
    imagePath: String,
    promptText: String,
    seed: Long?,
    generationTimeMs: Long?,
    modelName: String?,
    hazeState: HazeState,
    canRegenerate: Boolean,
    onSaveToGallery: () -> Unit,
    onRegenerateNewSeed: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = imagePath) {
        value = withContext(Dispatchers.IO) {
            val file = File(imagePath)
            if (file.isFile && file.length() > 0L) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap!!.width.toFloat() / bitmap!!.height.coerceAtLeast(1).toFloat())
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0E0E14))
                    .clickable(onClick = onFullscreenClick)
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = promptText.ifBlank { "Offline generated image" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.56f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Full-screen preview",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(5.dp)
                            .size(16.dp)
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainerHighest.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = colors.onSurfaceVariant)
                        Text(
                            text = if (File(imagePath).isFile) "Loading generated image…" else "Image file missing on disk",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Metadata badges (seed, time, model)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!modelName.isNullOrBlank()) {
                MetadataPill(text = modelName)
            }
            if (generationTimeMs != null && generationTimeMs > 0L) {
                MetadataPill(text = String.format(Locale.US, "%.1fs", generationTimeMs / 1000.0))
            }
            if (seed != null) {
                MetadataPill(text = "Seed $seed")
            }
        }

        // Quick Actions: Save to Gallery, Regenerate (new seed), Fullscreen
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassButton(
                onClick = onSaveToGallery,
                enabled = bitmap != null,
                hazeState = hazeState,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp),
                shape = RoundedCornerShape(10.dp),
                tintColor = colors.primary.copy(alpha = 0.16f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save", style = MaterialTheme.typography.labelSmall, color = colors.onSurface, maxLines = 1)
            }

            LiquidGlassButton(
                onClick = onRegenerateNewSeed,
                enabled = canRegenerate && promptText.isNotBlank(),
                hazeState = hazeState,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp),
                shape = RoundedCornerShape(10.dp),
                tintColor = RoseAccent.copy(alpha = 0.18f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = RoseAccent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Seed", style = MaterialTheme.typography.labelSmall, color = colors.onSurface, maxLines = 1)
            }

            LiquidGlassButton(
                onClick = onFullscreenClick,
                enabled = bitmap != null,
                hazeState = hazeState,
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(10.dp),
                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Full screen", tint = colors.onSurface, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun MetadataPill(text: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun GeneratedImageFullscreenDialog(
    imagePath: String,
    promptText: String,
    seed: Long?,
    generationTimeMs: Long?,
    modelName: String?,
    hazeState: HazeState,
    onSaveToGallery: () -> Unit,
    onOpenExternal: () -> Unit,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = imagePath) {
        value = withContext(Dispatchers.IO) {
            val file = File(imagePath)
            if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF008080C))
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = modelName ?: "Generated Image",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val infoParts = listOfNotNull(
                            seed?.let { "Seed $it" },
                            generationTimeMs?.takeIf { it > 0L }?.let { String.format(Locale.US, "%.1fs", it / 1000.0) }
                        ).joinToString(" • ")
                        if (infoParts.isNotEmpty()) {
                            Text(
                                text = infoParts,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }
                    }

                    LiquidGlassButton(
                        onClick = onClose,
                        hazeState = hazeState,
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = promptText,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                        )
                    } else {
                        CircularProgressIndicator(color = RoseAccent)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (promptText.isNotBlank()) {
                        Text(
                            text = promptText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LiquidGlassButton(
                            onClick = onSaveToGallery,
                            hazeState = hazeState,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = colors.primary.copy(alpha = 0.28f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save to Gallery", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }

                        LiquidGlassButton(
                            onClick = onOpenExternal,
                            hazeState = hazeState,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = Color.White.copy(alpha = 0.14f)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("External Viewer", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

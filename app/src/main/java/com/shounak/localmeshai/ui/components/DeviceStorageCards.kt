package com.shounak.localmeshai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.animatedGlassHalo
import com.shounak.localmeshai.utils.fluidReveal
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

/**
 * Storage summary card displaying total downloaded model count and storage size pill.
 */
@Composable
fun StorageSummaryCard(
    downloadedModels: List<ModelInfo>,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val storagePaths = downloadedModels.mapNotNull { it.localPath }.distinct().sorted()
    val totalBytes by produceState(0L, storagePaths) {
        value = withContext(Dispatchers.IO) {
            storagePaths.sumOf { path ->
                val file = File(path)
                when {
                    file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    file.isFile -> file.length()
                    else -> 0L
                }
            }
        }
    }
    val cardTint = MaterialTheme.colorScheme.surfaceContainer
    Card(
        modifier = modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 60,
                initialYOffset = 20.dp,
                initialXOffset = (-18).dp,
                initialRotationZ = -0.6f
            )
            .animatedGlassHalo(
                enabled = downloadedModels.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                alpha = 0.04f,
                durationMillis = 5_000
            )
            .glassEffect(
                hazeState = hazeState,
                shape = MaterialTheme.shapes.medium,
                blurRadius = 16.dp,
                tintColor = cardTint
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "Downloaded models",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (downloadedModels.isEmpty()) {
                    Text(
                        text = "No models downloaded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "${downloadedModels.size} model${if (downloadedModels.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (downloadedModels.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = formatStorageBytes(totalBytes),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Processor & Memory card displaying chipset, core count, available RAM pill, and gauge bar.
 */
@Composable
fun DeviceHardwareCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var ramInfo by remember { mutableStateOf(DeviceUtils.getRamMemoryInfo(context)) }
    val socInfo = remember { DeviceUtils.getDeviceSoCInfo(context) }
    val cardTint = MaterialTheme.colorScheme.surfaceContainer

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000L)
            ramInfo = DeviceUtils.getRamMemoryInfo(context)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 80,
                initialYOffset = 20.dp,
                initialXOffset = 18.dp,
                initialRotationZ = 0.5f
            )
            .animatedGlassHalo(
                enabled = true,
                shape = MaterialTheme.shapes.medium,
                alpha = 0.035f,
                durationMillis = 4_800
            )
            .glassEffect(
                hazeState = hazeState,
                shape = MaterialTheme.shapes.medium,
                blurRadius = 16.dp,
                tintColor = cardTint
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Device SoC Model",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Processor & Memory",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = socInfo.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (socInfo.details.isNotBlank()) {
                            Text(
                                text = socInfo.details,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.2f GiB free", ramInfo.availableGb),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Live RAM utilization bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { ramInfo.usedPercentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        ramInfo.usedPercentage > 0.88f -> MaterialTheme.colorScheme.error
                        ramInfo.usedPercentage > 0.75f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Available: ${String.format(Locale.US, "%.2f", ramInfo.availableGb)} GiB / Total: ${String.format(Locale.US, "%.1f", ramInfo.totalGb)} GiB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(ramInfo.usedPercentage * 100).toInt()}% used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

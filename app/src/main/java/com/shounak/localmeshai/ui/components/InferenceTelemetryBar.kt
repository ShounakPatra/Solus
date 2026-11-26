package com.shounak.localmeshai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shounak.localmeshai.utils.GlassDropdownMenu
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import java.util.Locale

@Composable
fun InferenceTelemetryBar(
    tokensPerSecond: Float,
    lastInferenceMs: Long,
    runtimeStatus: String = "Ready",
    activeBackend: String = "Auto",
    selectedBackendPreference: String = "AUTO",
    npuLabel: String = "NPU",
    npuEnabled: Boolean = false,
    npuUnavailableReason: String? = null,
    onSelectBackendPreference: ((String) -> Unit)? = null,
    batteryTempC: Float? = null,
    availableRamMb: Long? = null,
    accentColor: Color = Color(0xFF38BDF8),
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    contextIndicator: (@Composable () -> Unit)? = null
) {
    val isNoModel = runtimeStatus.equals("No model", ignoreCase = true) || runtimeStatus.equals("Not loaded", ignoreCase = true)
    val colors = MaterialTheme.colorScheme
    val pillShape = CircleShape

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .wrapContentWidth()
                .clip(pillShape)
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = colors.surface,
                                blurRadius = 28.dp,
                                tint = HazeTint(Color(0xFF0B132B).copy(alpha = 0.34f)),
                                noiseFactor = 0.06f
                            )
                        )
                    } else {
                        Modifier.background(Color(0xFF0B132B).copy(alpha = 0.88f))
                    }
                )
                .border(1.dp, accentColor.copy(alpha = 0.42f), pillShape)
        ) {
        Row(
            modifier = Modifier
                .clip(pillShape)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isNoModel) {
                Text(
                    text = "No model",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8),
                    maxLines = 1
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (tokensPerSecond > 0f) String.format(Locale.US, "%.1f t/s", tokensPerSecond) else runtimeStatus,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        maxLines = 1
                    )
                }
            }

            if (!isNoModel && contextIndicator != null) {
                Text(text = "·", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), maxLines = 1)
                contextIndicator()
            }

            if (!isNoModel && lastInferenceMs > 0L) {
                Text(text = "·", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), maxLines = 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFA855F7),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "${lastInferenceMs}ms",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFC084FC),
                        maxLines = 1
                    )
                }
            }

            Text(text = "·", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), maxLines = 1)

            val (badgeLabel, badgeTint) = resolveBackendBadge(activeBackend)
            var backendMenuExpanded by remember { mutableStateOf(false) }
            val arrowRotation by animateFloatAsState(
                targetValue = if (backendMenuExpanded) 180f else 0f,
                label = "telemetry_backend_arrow_rotation"
            )
            Box {
                val badgeShape = CircleShape
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(badgeShape)
                        .background(badgeTint.copy(alpha = 0.16f))
                        .border(0.6.dp, badgeTint.copy(alpha = 0.45f), badgeShape)
                        .then(
                            if (onSelectBackendPreference != null) {
                                Modifier.clickable { backendMenuExpanded = !backendMenuExpanded }
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = badgeTint,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = badgeLabel,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onSelectBackendPreference != null) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select compute accelerator",
                            tint = badgeTint,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(arrowRotation)
                        )
                    }
                }

                if (onSelectBackendPreference != null) {
                    GlassDropdownMenu(
                        expanded = backendMenuExpanded,
                        onDismissRequest = { backendMenuExpanded = false },
                        hazeState = hazeState,
                        offset = DpOffset(0.dp, 6.dp),
                        transformOrigin = TransformOrigin(0.5f, 0f),
                        modifier = Modifier.widthIn(min = 200.dp, max = 250.dp)
                    ) {
                        val options = listOf(
                            Triple("AUTO", "Auto", "NPU → GPU → CPU" to true),
                            Triple("NPU", npuLabel, (if (npuEnabled) "Hardware NPU" else (npuUnavailableReason ?: "NPU unavailable")) to npuEnabled),
                            Triple("VULKAN", "GPU", "Vulkan / LiteRT GPU" to true),
                            Triple("CPU", "CPU", "ARM CPU (Safe)" to true)
                        )
                        options.forEach { (prefKey, title, subAndEnabled) ->
                            val (subtitle, enabled) = subAndEnabled
                            val isSelected = selectedBackendPreference.equals(prefKey, ignoreCase = true)
                            DropdownMenuItem(
                                enabled = enabled,
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = when {
                                                    !enabled -> colors.onSurfaceVariant.copy(alpha = 0.45f)
                                                    isSelected -> colors.primary
                                                    else -> colors.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.42f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = colors.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    if (enabled) {
                                        backendMenuExpanded = false
                                        onSelectBackendPreference(prefKey)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (batteryTempC != null) {
                Text(text = "·", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), maxLines = 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = null,
                        tint = if (batteryTempC > 42f) Color(0xFFEF4444) else Color(0xFFF59E0B),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f°C", batteryTempC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (batteryTempC > 42f) Color(0xFFEF4444) else Color(0xFFF59E0B),
                        maxLines = 1
                    )
                }
            }

            if (availableRamMb != null && availableRamMb > 0L) {
                Text(text = "·", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), maxLines = 1)
                Text(
                    text = String.format(Locale.US, "%.1fGB free", availableRamMb / 1024f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF94A3B8),
                    maxLines = 1
                )
            }
        }
        }
    }
}

private fun resolveBackendBadge(activeBackend: String): Pair<String, Color> {
    val lower = activeBackend.lowercase(Locale.US)
    return when {
        lower == "auto" ->
            "Auto" to Color(0xFF38BDF8)
        lower.contains("qualcomm npu") || lower.contains("qnn") || lower.contains("hexagon") ->
            "Qualcomm NPU" to Color(0xFF10B981)
        lower.contains("mediatek npu") || lower.contains("neuropilot") ->
            "MediaTek NPU" to Color(0xFF22D3EE)
        lower == "npu" ->
            "NPU" to Color(0xFF22D3EE)
        lower.contains("vulkan") ->
            "Vulkan GPU" to Color(0xFF38BDF8)
        lower.contains("gpu") ->
            "GPU" to Color(0xFF38BDF8)
        lower.contains("xnnpack") ->
            "XNNPACK CPU" to Color(0xFF6EE7B7)
        lower.contains("cpu") ->
            "CPU" to Color(0xFF94A3B8)
        else ->
            activeBackend.ifBlank { "Auto" } to Color(0xFF6EE7B7)
    }
}

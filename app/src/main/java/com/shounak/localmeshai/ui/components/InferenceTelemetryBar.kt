package com.shounak.localmeshai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    activeBackend: String = "Local GPU/CPU",
    batteryTempC: Float? = null,
    availableRamMb: Long? = null,
    accentColor: Color = Color(0xFF38BDF8),
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    contextIndicator: (@Composable () -> Unit)? = null
) {
    val isNoModel = runtimeStatus.equals("No model", ignoreCase = true) || runtimeStatus.equals("Not loaded", ignoreCase = true)
    val colors = MaterialTheme.colorScheme
    val pillShape = RoundedCornerShape(20.dp)

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(
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
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = activeBackend,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF6EE7B7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

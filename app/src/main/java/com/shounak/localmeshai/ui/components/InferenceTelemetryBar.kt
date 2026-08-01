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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun InferenceTelemetryBar(
    tokensPerSecond: Float,
    lastInferenceMs: Long,
    activeBackend: String = "Local GPU/CPU",
    batteryTempC: Float? = null,
    availableRamMb: Long? = null,
    accentColor: Color = Color(0xFF38BDF8),
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.90f))
                .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                    text = if (tokensPerSecond > 0f) String.format(Locale.US, "%.1f t/s", tokensPerSecond) else "Ready",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    maxLines = 1
                )
            }

            Text(text = "•", fontSize = 10.sp, color = Color(0xFF475569), maxLines = 1)

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
                    text = if (lastInferenceMs > 0L) "${lastInferenceMs}ms" else "--",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFC084FC),
                    maxLines = 1
                )
            }

            Text(text = "•", fontSize = 10.sp, color = Color(0xFF475569), maxLines = 1)

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
                    maxLines = 1
                )
            }

            if (batteryTempC != null) {
                Text(text = "•", fontSize = 10.sp, color = Color(0xFF475569), maxLines = 1)
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
                Text(text = "•", fontSize = 10.sp, color = Color(0xFF475569), maxLines = 1)
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

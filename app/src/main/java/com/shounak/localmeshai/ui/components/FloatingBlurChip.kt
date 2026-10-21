package com.shounak.localmeshai.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

@Composable
fun FloatingBlurChip(hazeState: HazeState, onClick: () -> Unit,
                     accent: Color = MaterialTheme.colorScheme.primary,
                     selected: Boolean = false, modifier: Modifier = Modifier,
                     content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.heightIn(min = 36.dp).clip(shape)
        .hazeEffect(state = hazeState, style = HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.surface,
            blurRadius = 24.dp, tint = HazeTint(accent.copy(alpha = if (selected) 0.18f else 0.09f)),
            noiseFactor = 0.04f))
        .border(0.8.dp, accent.copy(alpha = if (selected) 0.65f else 0.30f), shape)
        .semantics { this.selected = selected }
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

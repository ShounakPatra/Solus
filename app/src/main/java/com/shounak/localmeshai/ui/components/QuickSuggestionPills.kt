package com.shounak.localmeshai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SuggestionItem(
    val label: String,
    val promptText: String,
    val icon: ImageVector,
    val accentColor: Color
)

@Composable
fun QuickSuggestionPills(
    isVisible: Boolean,
    hazeState: dev.chrisbanes.haze.HazeState,
    onSelectSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)),
        modifier = modifier
    ) {
        val haptic = LocalHapticFeedback.current
        val suggestions = remember {
            listOf(
                SuggestionItem("Explain Simply", "Explain simply in easy terms: ", Icons.Default.Lightbulb, Color(0xFF38BDF8)),
                SuggestionItem("Summarize Text", "Summarize key points of: ", Icons.AutoMirrored.Filled.MenuBook, Color(0xFFA855F7)),
                SuggestionItem("Fix & Format Code", "Review and format this code snippet: ", Icons.Default.Code, Color(0xFF34D399)),
                SuggestionItem("Brainstorm Ideas", "Brainstorm 5 creative ideas for: ", Icons.Default.AutoAwesome, Color(0xFFF59E0B)),
                SuggestionItem("Step-by-Step", "Analyze step-by-step: ", Icons.Default.Psychology, Color(0xFFEC4899))
            )
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(suggestions, key = { it.label }) { item ->
                SuggestionPill(
                    item = item,
                    hazeState = hazeState,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectSuggestion(item.promptText)
                    }
                )
            }
        }
    }
}

@Composable
private fun SuggestionPill(
    item: SuggestionItem,
    hazeState: dev.chrisbanes.haze.HazeState,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pillScale"
    )

    FloatingBlurChip(hazeState = hazeState, onClick = onClick, accent = item.accentColor,
        modifier = Modifier.scale(scale)) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = item.accentColor,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = item.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        )
    }
}

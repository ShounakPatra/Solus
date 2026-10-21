package com.shounak.localmeshai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SlidingGlassTab(val label: String, val icon: ImageVector? = null)

/** A single moving selection pill, using the bottom navigation's spring motion. */
@Composable
internal fun SlidingGlassTabs(
    tabs: List<SlidingGlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    verticalPadding: Dp = 10.dp
) {
    require(tabs.isNotEmpty() && selectedIndex in tabs.indices)
    val visuals = com.shounak.localmeshai.features.LocalWorkspaceVisuals.current
    val density = LocalDensity.current
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val gap = 4.dp
    val gapPx = with(density) { gap.toPx() }
    val itemWidthPx = ((contentWidthPx - gapPx * (tabs.size - 1)) / tabs.size).coerceAtLeast(0f)
    val itemWidth = with(density) { itemWidthPx.toDp() }
    val selectionPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (visuals.reduceMotion) snap() else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "glass_tab_selection"
    )
    val itemShape = RoundedCornerShape(cornerRadius - 3.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(cornerRadius),
                tintColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.50f),
                borderAlpha = 0.28f
            )
            .padding(3.dp)
    ) {
        Box(Modifier.matchParentSize().clip(itemShape)) {
            if (itemWidthPx > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset((selectionPosition * (itemWidthPx + gapPx)).roundToInt(), 0) }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val travel = abs(selectionPosition - selectedIndex).coerceIn(0f, 1f)
                            scaleX = 1f + travel * 0.07f
                            scaleY = 1f - travel * 0.025f
                        }
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), itemShape)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.48f), itemShape)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .onSizeChanged { contentWidthPx = it.width }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = if (visuals.reduceMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                    label = "glass_tab_color"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = if (visuals.largeControls) 48.dp else 0.dp)
                        .clip(itemShape)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 6.dp, vertical = verticalPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        tab.icon?.let { icon ->
                            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = if (density.fontScale >= 1.3f) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shounak.localmeshai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

@Composable
internal fun GlassActionButton(
    onClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    LiquidGlassButton(
        onClick = onClick, hazeState = hazeState, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/** Bounded body with a persistent title and actions, including above the keyboard. */
@Composable
internal fun GlassContentDialog(
    onDismissRequest: () -> Unit,
    hazeState: HazeState,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {}
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * 0.92f)
                    .glassEffect(hazeState, RoundedCornerShape(28.dp),
                        tintColor = MaterialTheme.colorScheme.surfaceContainer, borderAlpha = 0.4f)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}

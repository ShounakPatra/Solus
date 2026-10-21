package com.shounak.localmeshai.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

data class WorkspaceVisuals(val reduceMotion: Boolean = false, val largeControls: Boolean = true)
val LocalWorkspaceVisuals = staticCompositionLocalOf { WorkspaceVisuals() }

@Composable
fun WorkspaceAppearance(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { WorkspacePreferences.get(context) }
    val revision by preferences.revision.collectAsState()
    val visuals = remember(revision) { WorkspaceVisuals(preferences.reduceMotion, preferences.largeControls) }
    CompositionLocalProvider(LocalWorkspaceVisuals provides visuals, content = content)
}

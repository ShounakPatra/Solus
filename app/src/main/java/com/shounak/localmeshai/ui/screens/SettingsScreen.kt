package com.shounak.localmeshai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.LiquidGlassButton
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

private const val HF_TOKEN_TUTORIAL_URL = "https://youtu.be/il58zFv0tmU?si=5o_gE8p-JqwWkekY"

@Composable
fun SettingsDialog(
    mainViewModel: MainViewModel,
    hazeState: HazeState,
    onDismissRequest: () -> Unit
) {
    val settingsData by mainViewModel.appSettingsData.collectAsState()
    val appSettings = mainViewModel.appSettings
    var hfTokenDraft by remember { mutableStateOf(settingsData.huggingFaceToken) }
    var isTokenVisible by remember { mutableStateOf(false) }
    var showLastChar by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(hfTokenDraft) {
        if (hfTokenDraft.isNotEmpty() && !isTokenVisible) {
            showLastChar = true
            delay(1200L)
            showLastChar = false
        } else {
            showLastChar = false
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Top App Bar with back button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiquidGlassButton(
                        onClick = onDismissRequest,
                        hazeState = hazeState,
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        tintColor = Color(0xFF22252A),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to main screen",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        "⚙️ Settings & Customization",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                }

                // Scrollable Settings Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Category 1: Visual Themes
                    SettingsCategory(title = "🎨 Visual Themes") {
                        SettingsSwitchRow(
                            title = "Dynamic Model Accent Themes",
                            subtitle = "Adapt UI accents for DeepSeek (Cyan), Gemma (Amber), Qwen (Violet), Llama (Green)",
                            checked = settingsData.enableDynamicThemes,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableDynamicThemes = enabled) }
                            }
                        )
                    }

                    // Category 2: Telemetry
                    SettingsCategory(title = "📊 Telemetry & Thermal Guard") {
                        SettingsSwitchRow(
                            title = "Performance Telemetry Bar",
                            subtitle = "Show generation speed (t/s) and latency (ms) header pill",
                            checked = settingsData.enableTelemetryBar,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableTelemetryBar = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f))
                        SettingsSwitchRow(
                            title = "Battery Temperature (°C)",
                            subtitle = "Display live battery thermal reading in telemetry bar",
                            checked = settingsData.showThermalGuard,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(showThermalGuard = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f))
                        SettingsSwitchRow(
                            title = "System Memory Indicator",
                            subtitle = "Display available RAM (GB free) in telemetry bar",
                            checked = settingsData.showRamGuard,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(showRamGuard = enabled) }
                            }
                        )
                    }

                    // Category 3: Chat UX
                    SettingsCategory(title = "💬 Chat & Starter UX") {
                        SettingsSwitchRow(
                            title = "Quick Suggestion Pills",
                            subtitle = "Show empty chat prompt suggestion chips",
                            checked = settingsData.enableSuggestionPills,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableSuggestionPills = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f))
                        SettingsSwitchRow(
                            title = "System Prompt Persona Bar",
                            subtitle = "Show persona chips (Code Auditor, ELI5, Proofreader)",
                            checked = settingsData.enablePersonaPresets,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enablePersonaPresets = enabled) }
                            }
                        )
                    }

                    // Category 4: Navigation & Layout
                    SettingsCategory(title = "📱 Navigation & Layout") {
                        SettingsSwitchRow(
                            title = "Auto-Hide Bottom Navigation Bar",
                            subtitle = "Hide bottom bar for full-screen chat view. Slide left/right to navigate tabs.",
                            checked = settingsData.enableAutoHideBottomBar,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableAutoHideBottomBar = enabled) }
                            }
                        )
                    }

                    // Category 5: Tools & Memory
                    SettingsCategory(title = "⚡ Tools & Memory") {
                        SettingsSwitchRow(
                            title = "Solus Bench Rating Button",
                            subtitle = "Show device performance benchmark button on model cards",
                            checked = settingsData.enableSolusBench,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableSolusBench = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f))
                        Text("Auto-Unload Model Timer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0 to "Off", 5 to "5 min", 15 to "15 min", 30 to "30 min").forEach { (mins, label) ->
                                FilterChip(
                                    selected = settingsData.autoUnloadMinutes == mins,
                                    onClick = {
                                        appSettings.updateSettings { it.copy(autoUnloadMinutes = mins) }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    // Category 6: Access Token
                    SettingsCategory(title = "🔐 Hugging Face Access Token") {
                        OutlinedTextField(
                            value = hfTokenDraft,
                            onValueChange = { hfTokenDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Hugging Face Read Token") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (hfTokenDraft.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                hfTokenDraft = ""
                                                showLastChar = false
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear token",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { isTokenVisible = !isTokenVisible }
                                    ) {
                                        Icon(
                                            imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (isTokenVisible) "Hide token" else "Show token",
                                            tint = if (isTokenVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            visualTransformation = if (isTokenVisible) {
                                VisualTransformation.None
                            } else {
                                LastCharPasswordVisualTransformation(showLastChar = showLastChar)
                            },
                            shape = RoundedCornerShape(14.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HF_TOKEN_TUTORIAL_URL))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1.2f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("How to create token", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }

                            Button(
                                onClick = {
                                    appSettings.updateSettings { it.copy(huggingFaceToken = hfTokenDraft.trim()) }
                                    mainViewModel.setHuggingFaceToken(hfTokenDraft.trim())
                                },
                                enabled = hfTokenDraft.trim() != settingsData.huggingFaceToken,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save Token", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private class LastCharPasswordVisualTransformation(
    private val showLastChar: Boolean,
    private val maskChar: Char = '•'
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val transformedText = if (showLastChar) {
            if (text.length > 1) {
                maskChar.toString().repeat(text.length - 1) + text.last()
            } else {
                text.text
            }
        } else {
            maskChar.toString().repeat(text.length)
        }
        return TransformedText(AnnotatedString(transformedText), OffsetMapping.Identity)
    }
}

package com.shounak.localmeshai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.utils.AppUpdateManager
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import com.shounak.localmeshai.utils.glassEffect
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
                .background(colors.background)
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
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(21.dp),
                        tintColor = colors.surfaceContainer,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to main screen",
                            tint = colors.onSurface,
                            modifier = Modifier.size(20.dp)
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
                    SettingsCategory(title = "🎨 Visual Themes", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Dark Mode",
                            subtitle = "Enable dark visual appearance throughout the app (default)",
                            checked = settingsData.enableDarkMode,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableDarkMode = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
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
                    SettingsCategory(title = "📊 Telemetry & Thermal Guard", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Performance Telemetry Bar",
                            subtitle = "Show generation speed (t/s) and latency (ms) header pill",
                            checked = settingsData.enableTelemetryBar,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableTelemetryBar = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                        SettingsSwitchRow(
                            title = "Battery Temperature (°C)",
                            subtitle = "Display live battery thermal reading in telemetry bar",
                            checked = settingsData.showThermalGuard,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(showThermalGuard = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
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
                    SettingsCategory(title = "💬 Chat & Starter UX", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Quick Suggestion Pills",
                            subtitle = "Show empty chat prompt suggestion chips",
                            checked = settingsData.enableSuggestionPills,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableSuggestionPills = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
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
                    SettingsCategory(title = "📱 Navigation & Layout", hazeState = hazeState) {
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
                    SettingsCategory(title = "⚡ Tools & Memory", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Solus Bench Rating Button",
                            subtitle = "Show device performance benchmark button on model cards",
                            checked = settingsData.enableSolusBench,
                            onCheckedChange = { enabled ->
                                appSettings.updateSettings { it.copy(enableSolusBench = enabled) }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                        Text(
                            "Auto-Unload Model Timer",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0 to "Off", 5 to "5 min", 15 to "15 min", 30 to "30 min").forEach { (mins, label) ->
                                val isSelected = settingsData.autoUnloadMinutes == mins
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        appSettings.updateSettings { it.copy(autoUnloadMinutes = mins) }
                                    },
                                    label = { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primary.copy(alpha = 0.28f),
                                        selectedLabelColor = Color.White,
                                        containerColor = colors.surfaceContainerLow,
                                        labelColor = colors.onSurfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                        selectedBorderColor = colors.primary.copy(alpha = 0.75f),
                                        borderWidth = 0.8.dp,
                                        selectedBorderWidth = 1.dp
                                    )
                                )
                            }
                        }
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                        Text(
                            "Model Inference Backend (GGUF & LiteRT)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        val vulkanDeviceInfo = remember { LlamaCppEngine.getVulkanDeviceInfo() }
                        val gpuName = vulkanDeviceInfo?.devices?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                            ?: if (LlamaCppEngine.isVulkanAvailable()) "GPU Supported" else "CPU Only"
                        Text(
                            text = "Detected Hardware: $gpuName",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                        )
                        Text(
                            text = "Choose whether models run on GPU accelerator or CPU-safe mode. Changing this automatically re-initializes the active model on the selected chip.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "AUTO" to "Auto (GPU/CPU)",
                                "VULKAN" to "GPU (Vulkan / LiteRT)",
                                "CPU" to "CPU (Safe)"
                            ).forEach { (backendPref, label) ->
                                val isSelected = settingsData.llamaBackendPreference.equals(backendPref, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        appSettings.updateSettings { it.copy(llamaBackendPreference = backendPref) }
                                    },
                                    label = { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primary.copy(alpha = 0.28f),
                                        selectedLabelColor = Color.White,
                                        containerColor = colors.surfaceContainerLow,
                                        labelColor = colors.onSurfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                        selectedBorderColor = colors.primary.copy(alpha = 0.75f),
                                        borderWidth = 0.8.dp,
                                        selectedBorderWidth = 1.dp
                                    )
                                )
                            }
                        }
                    }

                    // Category 6: Access Token
                    SettingsCategory(title = "🔐 Hugging Face Access Token", hazeState = hazeState) {
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
                                                tint = colors.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { isTokenVisible = !isTokenVisible }
                                    ) {
                                        Icon(
                                            imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (isTokenVisible) "Hide token" else "Show token",
                                            tint = if (isTokenVisible) colors.primary else colors.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            visualTransformation = if (isTokenVisible) {
                                VisualTransformation.None
                            } else {
                                LastCharPasswordVisualTransformation(showLastChar = showLastChar)
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.surfaceContainerLow,
                                unfocusedContainerColor = colors.surfaceContainerLow,
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.65f),
                                focusedLabelColor = colors.primary,
                                unfocusedLabelColor = colors.onSurfaceVariant.copy(alpha = 0.80f),
                                focusedTextColor = colors.onSurface,
                                unfocusedTextColor = colors.onSurface,
                                cursorColor = colors.primary
                            )
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
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
                                border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f)),
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

                    // Category: Persistent Memory (Decoupled from RAG)
                    val memoryManager = remember { com.shounak.localmeshai.memory.PersistentMemoryManager.getInstance(context) }
                    var memoryCount by remember { mutableStateOf(memoryManager.totalMemoryCount) }
                    var activeMemoryCount by remember { mutableStateOf(memoryManager.activeMemoryCount) }
                    var showMemoryDialog by remember { mutableStateOf(false) }

                    if (showMemoryDialog) {
                        PersistentMemoryDialog(
                            memoryManager = memoryManager,
                            onDismissRequest = {
                                showMemoryDialog = false
                                memoryCount = memoryManager.totalMemoryCount
                                activeMemoryCount = memoryManager.activeMemoryCount
                            }
                        )
                    }

                    SettingsCategory(title = "🧠 Persistent Memory", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Enable Persistent Memory",
                            subtitle = "Remember user identity, facts, preferences, and custom rules across chats",
                            checked = settingsData.enablePersistentMemory,
                            onCheckedChange = { isEnabled ->
                                appSettings.updateSettings { it.copy(enablePersistentMemory = isEnabled) }
                            }
                        )

                        if (settingsData.enablePersistentMemory) {
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                            SettingsSwitchRow(
                                title = "Auto-Extract Memories",
                                subtitle = "Automatically detect and save facts when you say 'Remember that...'",
                                checked = settingsData.autoExtractMemories,
                                onCheckedChange = { isEnabled ->
                                    appSettings.updateSettings { it.copy(autoExtractMemories = isEnabled) }
                                }
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = colors.surfaceContainerLow,
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Saved Memories ($memoryCount)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface
                                        )
                                        SuggestionChip(
                                            onClick = { showMemoryDialog = true },
                                            label = { Text("$activeMemoryCount active", style = MaterialTheme.typography.labelSmall) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = colors.surfaceContainer,
                                                labelColor = colors.primary
                                            ),
                                            border = SuggestionChipDefaults.suggestionChipBorder(
                                                enabled = true,
                                                borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                                borderWidth = 0.8.dp
                                            )
                                        )
                                    }
                                    Text(
                                        text = "Memories are automatically included in system prompt context across all models without altering documents or RAG.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                                    )
                                    Button(
                                        onClick = { showMemoryDialog = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.primary.copy(alpha = 0.22f),
                                            contentColor = colors.primary
                                        ),
                                        border = BorderStroke(0.8.dp, colors.primary.copy(alpha = 0.50f))
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Manage Saved Memories", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    // Category: Retrieval-Augmented Generation (RAG)
                    val ragManager = remember { com.shounak.localmeshai.rag.RagManager.getInstance(context) }
                    var ragDocCount by remember { mutableStateOf(ragManager.indexedDocuments.size) }
                    var ragChunkCount by remember { mutableStateOf(ragManager.totalIndexedChunks) }

                    SettingsCategory(title = "📚 Retrieval-Augmented Generation (RAG)", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Enable RAG Knowledge Base",
                            subtitle = "Semantically search indexed documents and inject relevant context into chats",
                            checked = settingsData.enableRag,
                            onCheckedChange = { isEnabled ->
                                appSettings.updateSettings { it.copy(enableRag = isEnabled) }
                            }
                        )

                        if (settingsData.enableRag) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = colors.surfaceContainerLow,
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Vector Search Status",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text("$ragDocCount documents", style = MaterialTheme.typography.labelSmall) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = colors.surfaceContainer,
                                                labelColor = colors.onSurfaceVariant
                                            ),
                                            border = SuggestionChipDefaults.suggestionChipBorder(
                                                enabled = true,
                                                borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                                borderWidth = 0.8.dp
                                            )
                                        )
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text("$ragChunkCount vector chunks", style = MaterialTheme.typography.labelSmall) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = colors.surfaceContainer,
                                                labelColor = colors.onSurfaceVariant
                                            ),
                                            border = SuggestionChipDefaults.suggestionChipBorder(
                                                enabled = true,
                                                borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                                borderWidth = 0.8.dp
                                            )
                                        )
                                    }
                                    Text(
                                        text = "Top-K Chunks: ${settingsData.ragTopK} | Min Similarity: ${(settingsData.ragMinSimilarity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                                    )

                                    if (ragChunkCount > 0) {
                                        OutlinedButton(
                                            onClick = {
                                                ragManager.clearKnowledgeBase()
                                                ragDocCount = 0
                                                ragChunkCount = 0
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = colors.error
                                            ),
                                            border = BorderStroke(0.8.dp, colors.error.copy(alpha = 0.40f)),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Clear RAG Knowledge Base", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Category 7: About Solus & Updates
                    val updateState by mainViewModel.updateState.collectAsState()
                    val isCheckingForUpdates by mainViewModel.isCheckingForUpdates.collectAsState()

                    SettingsCategory(title = "ℹ️ About Solus & Updates", hazeState = hazeState) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surfaceContainerLow,
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Solus — Private On-Device AI",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "Version: ${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                                )
                                Text(
                                    text = "Developer: Shounak Patra",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateManager.GITHUB_REPO_URL))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
                                    border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("GitHub: ShounakPatra/Solus", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        SettingsSwitchRow(
                            title = "Automatic Check for Updates",
                            subtitle = "Check for newer GitHub releases on app startup",
                            checked = settingsData.autoCheckUpdates,
                            onCheckedChange = { isEnabled ->
                                appSettings.updateSettings { it.copy(autoCheckUpdates = isEnabled) }
                            }
                        )

                        Button(
                            onClick = { mainViewModel.checkForUpdates(silent = false) },
                            enabled = !isCheckingForUpdates,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isCheckingForUpdates) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Checking for updates...", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Check for Updates", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        when (val result = updateState) {
                            is AppUpdateManager.UpdateCheckResult.UpdateAvailable -> {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colors.primaryContainer.copy(alpha = 0.35f),
                                    border = BorderStroke(0.8.dp, colors.primary.copy(alpha = 0.50f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                tint = colors.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "New Version Available: v${result.updateInfo.latestVersion}",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.onPrimaryContainer
                                            )
                                        }
                                        Text(
                                            result.updateInfo.releaseNotes.take(300),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onPrimaryContainer
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val url = result.updateInfo.downloadUrl ?: result.updateInfo.htmlUrl
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(intent)
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Download Release")
                                            }
                                            OutlinedButton(
                                                onClick = { mainViewModel.dismissUpdateState() },
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Dismiss")
                                            }
                                        }
                                    }
                                }
                            }
                            is AppUpdateManager.UpdateCheckResult.UpToDate -> {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colors.surfaceContainerLow,
                                    border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = colors.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "You have the latest version installed (v${result.currentVersion}).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurface
                                        )
                                    }
                                }
                            }
                            is AppUpdateManager.UpdateCheckResult.Error -> {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colors.errorContainer.copy(alpha = 0.40f),
                                    border = BorderStroke(0.8.dp, colors.error.copy(alpha = 0.40f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = null,
                                            tint = colors.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Update check failed: ${result.message}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onErrorContainer
                                        )
                                    }
                                }
                            }
                            null -> {}
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
    hazeState: HazeState,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(20.dp)
    val cardTint = colors.surfaceContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = cardShape,
                blurRadius = 16.dp,
                tintColor = cardTint,
                borderAlpha = 0.35f
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
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
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.82f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = colors.onSurfaceVariant.copy(alpha = 0.7f),
                uncheckedTrackColor = colors.surfaceContainerHighest.copy(alpha = 0.40f),
                uncheckedBorderColor = colors.outlineVariant.copy(alpha = 0.50f)
            )
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

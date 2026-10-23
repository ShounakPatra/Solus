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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.WifiOff
import com.shounak.localmeshai.utils.CrashReportManager
import com.shounak.localmeshai.utils.CrashReport
import com.shounak.localmeshai.utils.DeviceUtils
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.ai.ChatInferenceManager
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.utils.AppUpdateManager
import com.shounak.localmeshai.utils.UpdateNotesFormatter
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

private const val HF_TOKEN_TUTORIAL_URL = "https://youtu.be/il58zFv0tmU?si=5o_gE8p-JqwWkekY"

@Composable
fun SettingsDialog(
    mainViewModel: MainViewModel,
    hazeState: HazeState,
    scrollToAbout: Boolean = false,
    onDismissRequest: () -> Unit,
    onAutoHideToggleChanged: ((Boolean) -> Unit)? = null
) {
    val settingsData by mainViewModel.appSettingsData.collectAsState()
    val appSettings = mainViewModel.appSettings
    var hfTokenDraft by remember { mutableStateOf(settingsData.huggingFaceToken) }
    var isTokenVisible by remember { mutableStateOf(false) }
    var showLastChar by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var showToolSkills by remember { mutableStateOf(false) }
    if (showToolSkills) ToolSkillsDialog(onDismiss = { showToolSkills = false })

    val scrollState = rememberScrollState()
    var scrollContainerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var aboutSectionOffsetY by remember { mutableIntStateOf(-1) }

    LaunchedEffect(scrollToAbout) {
        if (scrollToAbout) {
            try {
                withTimeout(1500L) {
                    snapshotFlow {
                        if (aboutSectionOffsetY > 0 && scrollState.maxValue > 0) aboutSectionOffsetY else -1
                    }.filter { it > 0 }.first()
                }
                delay(150L)
                scrollState.animateScrollTo(aboutSectionOffsetY.coerceIn(0, scrollState.maxValue))
            } catch (_: Exception) {
                if (scrollState.maxValue > 0) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            } finally {
                mainViewModel.onScrolledToAbout()
            }
        }
    }

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
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as?
            androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colors.background.toArgb()))
            }
        }
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
                        .onGloballyPositioned { coordinates ->
                            scrollContainerCoordinates = coordinates
                        }
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    com.shounak.localmeshai.features.WorkspaceSettingsPanel(mainViewModel.availableModels.toList(), hazeState)

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
                                onAutoHideToggleChanged?.invoke(enabled)
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
                            listOf(0 to "Off", 1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min").forEach { (mins, label) ->
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
                        val gpuName by produceState(initialValue = "Detecting…") {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val vulkanDeviceInfo = LlamaCppEngine.getVulkanDeviceInfo()
                                vulkanDeviceInfo?.devices?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                                    ?: if (LlamaCppEngine.isVulkanAvailable()) "GPU Supported" else "CPU Only"
                            }
                        }
                        Text(
                            text = "Detected Hardware: $gpuName",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                        )
                        val isGgufEngineActive by ChatInferenceManager.isGgufActive.collectAsState()
                        val selectedTextPath by mainViewModel.selectedTextModelPath.collectAsState()
                        val isGgufSelected = remember(selectedTextPath, mainViewModel.availableModels.toList()) {
                            selectedTextPath?.endsWith(".gguf", ignoreCase = true) == true ||
                                mainViewModel.availableModels.any {
                                    it.localPath == selectedTextPath && (it.fileName.endsWith(".gguf", ignoreCase = true) || it.backend.contains("GGUF", ignoreCase = true))
                                }
                        }
                        val isGgufActive = isGgufEngineActive || isGgufSelected

                        Text(
                            text = "Choose whether models run on GPU accelerator, CPU-safe mode, or Auto mode (prefers validated GPU with automatic CPU fallback). Changing this automatically re-initializes the active model on the selected chip.",
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
                                val isChipEnabled = true
                                val isSelected = settingsData.llamaBackendPreference.equals(backendPref, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        appSettings.updateSettings { it.copy(llamaBackendPreference = backendPref) }
                                    },
                                    enabled = isChipEnabled,
                                    label = { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primary.copy(alpha = 0.28f),
                                        selectedLabelColor = Color.White,
                                        containerColor = colors.surfaceContainerLow,
                                        labelColor = colors.onSurfaceVariant,
                                        disabledContainerColor = colors.surfaceContainerLowest.copy(alpha = 0.20f),
                                        disabledLabelColor = colors.onSurfaceVariant.copy(alpha = 0.35f),
                                        disabledSelectedContainerColor = colors.surfaceContainerLowest.copy(alpha = 0.20f)
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = isChipEnabled,
                                        selected = isSelected,
                                        borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                        selectedBorderColor = colors.primary.copy(alpha = 0.75f),
                                        disabledBorderColor = colors.outlineVariant.copy(alpha = 0.20f),
                                        borderWidth = 0.8.dp,
                                        selectedBorderWidth = 1.dp
                                    )
                                )
                            }
                        }
                        SettingsSwitchRow(
                            title = "Tool calling (experimental)",
                            subtitle = "Calculator, local date/time and enabled document search for compatible LiteRT-LM and GGUF models. Changes apply to your next message, including in the current chat.",
                            checked = settingsData.enableToolCalling,
                            onCheckedChange = { enabled -> appSettings.updateSettings { it.copy(enableToolCalling = enabled) } }
                        )
                        OutlinedButton(onClick = { showToolSkills = true }, modifier = Modifier.fillMaxWidth()) { Text("Manage tool skills") }
                        if (isGgufActive) {
                            Text(
                                text = "GGUF models support Auto (prefers validated GPU with automatic CPU fallback), explicit Vulkan GPU, and explicit CPU mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.primary.copy(alpha = 0.85f)
                            )
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
                            LiquidGlassButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HF_TOKEN_TUTORIAL_URL))
                                    context.startActivity(intent)
                                },
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(42.dp)
                                    .border(
                                        width = 0.8.dp,
                                        color = colors.outlineVariant.copy(alpha = 0.50f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "How to create token",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }

                            val saveEnabled = hfTokenDraft.trim() != settingsData.huggingFaceToken
                            LiquidGlassButton(
                                onClick = {
                                    runCatching { mainViewModel.setHuggingFaceToken(hfTokenDraft.trim()) }
                                        .onFailure {
                                            android.widget.Toast.makeText(context,
                                                "Could not securely save token. Please try again.",
                                                android.widget.Toast.LENGTH_LONG).show()
                                        }
                                },
                                enabled = saveEnabled,
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .border(
                                        width = 1.dp,
                                        color = if (saveEnabled) colors.primary.copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.30f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = if (saveEnabled) colors.primary.copy(alpha = 0.25f) else colors.surfaceContainerHighest.copy(alpha = 0.20f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (saveEnabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Save Token",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (saveEnabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    // Category: Persistent Memory (Decoupled from RAG)
                    val memoryManager = remember { com.shounak.localmeshai.memory.PersistentMemoryManager.getInstance(context) }
                    LaunchedEffect(memoryManager) { memoryManager.loadAsync() }
                    val savedMemories by memoryManager.memories.collectAsState()
                    val memoryCount = savedMemories.size
                    val activeMemoryCount = savedMemories.count { it.isEnabled }
                    var showMemoryDialog by remember { mutableStateOf(false) }

                    if (showMemoryDialog) {
                        PersistentMemoryDialog(
                            memoryManager = memoryManager,
                            onDismissRequest = {
                                showMemoryDialog = false
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
                                            color = colors.onSurface,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        SuggestionChip(
                                            onClick = { showMemoryDialog = true },
                                            label = { Text("$activeMemoryCount active", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
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
                                    LiquidGlassButton(
                                        onClick = { showMemoryDialog = true },
                                        hazeState = hazeState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .border(
                                                width = 0.9.dp,
                                                color = colors.primary.copy(alpha = 0.50f),
                                                shape = RoundedCornerShape(12.dp)
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        tintColor = colors.primary.copy(alpha = 0.20f),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = colors.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Manage Saved Memories",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Category: Retrieval-Augmented Generation (RAG)
                    val ragManager = remember { com.shounak.localmeshai.rag.RagManager.getInstance(context) }
                    var ragDocCount by remember { mutableStateOf(ragManager.indexedDocuments.size) }
                    var ragChunkCount by remember { mutableStateOf(ragManager.totalIndexedChunks) }
                    var showRagDialog by remember { mutableStateOf(false) }

                    if (showRagDialog) {
                        RagKnowledgeBaseDialog(
                            ragManager = ragManager,
                            onDismissRequest = {
                                showRagDialog = false
                                ragDocCount = ragManager.indexedDocuments.size
                                ragChunkCount = ragManager.totalIndexedChunks
                            }
                        )
                    }

                    SettingsCategory(title = "📚 Retrieval-Augmented Generation (RAG)", hazeState = hazeState) {
                        SettingsSwitchRow(
                            title = "Enable RAG Knowledge Base",
                            subtitle = "Semantically search indexed documents and inject relevant context into chats. Changes, including document-search tool availability, apply to your next message in the current chat.",
                            checked = settingsData.enableRag,
                            onCheckedChange = { isEnabled ->
                                appSettings.updateSettings { it.copy(enableRag = isEnabled) }
                            }
                        )

                        if (settingsData.enableRag) {
                            val embeddingStatus by ragManager.embeddingStatus.collectAsState()
                            val neuralSpec = com.shounak.localmeshai.rag.embedding.EmbeddingModels.BGE_SMALL_EN_V15
                            val neuralOn = settingsData.ragEmbeddingModel == neuralSpec.id
                            val switching = embeddingStatus is com.shounak.localmeshai.rag.EmbeddingModelStatus.Downloading ||
                                embeddingStatus is com.shounak.localmeshai.rag.EmbeddingModelStatus.Reindexing
                            LaunchedEffect(embeddingStatus) {
                                if (embeddingStatus is com.shounak.localmeshai.rag.EmbeddingModelStatus.Idle) {
                                    ragDocCount = ragManager.indexedDocuments.size
                                    ragChunkCount = ragManager.totalIndexedChunks
                                }
                            }
                            val neuralSubtitle = when (val status = embeddingStatus) {
                                is com.shounak.localmeshai.rag.EmbeddingModelStatus.Downloading ->
                                    "Downloading ${neuralSpec.displayName}… ${(status.bytesDone * 100 / status.bytesTotal.coerceAtLeast(1L)).toInt()}%"
                                is com.shounak.localmeshai.rag.EmbeddingModelStatus.Reindexing ->
                                    "Re-indexing documents… ${status.chunksDone}/${status.chunksTotal} chunks"
                                is com.shounak.localmeshai.rag.EmbeddingModelStatus.Failed ->
                                    "Couldn't switch search model: ${status.message}"
                                else -> if (neuralOn) {
                                    "On: ${neuralSpec.displayName}, fully offline. Finds passages by meaning, not just matching words"
                                } else {
                                    "Downloads a ${neuralSpec.downloadBytes / 1_000_000} MB on-device model (about 90 MB RAM only while searching). Finds passages by meaning, not just matching words"
                                }
                            }
                            SettingsSwitchRow(
                                title = "Neural Semantic Search",
                                subtitle = neuralSubtitle,
                                checked = neuralOn,
                                enabled = !switching,
                                onCheckedChange = { enable ->
                                    ragManager.requestEmbeddingModel(
                                        if (enable) neuralSpec.id else com.shounak.localmeshai.rag.embedding.EmbeddingModels.LEXICAL_ID
                                    )
                                }
                            )
                        }

                        if (settingsData.enableRag) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = colors.surfaceContainerLow,
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Knowledge Base Status",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val docSuffix = if (ragDocCount == 1) "doc" else "docs"
                                        val chunkSuffix = if (ragChunkCount == 1) "chunk" else "chunks"
                                        val ragBadgeText = if (ragDocCount == 0) "0 docs • 0 chunks" else "$ragDocCount $docSuffix • $ragChunkCount $chunkSuffix"
                                        SuggestionChip(
                                            onClick = { showRagDialog = true },
                                            label = {
                                                Text(
                                                    text = ragBadgeText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = colors.surfaceContainer,
                                                labelColor = if (ragDocCount > 0) colors.primary else colors.onSurfaceVariant
                                            ),
                                            border = SuggestionChipDefaults.suggestionChipBorder(
                                                enabled = true,
                                                borderColor = colors.outlineVariant.copy(alpha = 0.50f),
                                                borderWidth = 0.8.dp
                                            )
                                        )
                                    }
                                    Text(
                                        text = "Top-K Chunks: ${settingsData.ragTopK} | Min Relevance: ${(settingsData.ragMinSimilarity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                                    )

                                    LiquidGlassButton(
                                        onClick = { showRagDialog = true },
                                        hazeState = hazeState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .border(
                                                width = 0.9.dp,
                                                color = colors.primary.copy(alpha = 0.50f),
                                                shape = RoundedCornerShape(12.dp)
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        tintColor = colors.primary.copy(alpha = 0.20f),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Tune,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = colors.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Manage Knowledge Base & Documents",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.primary
                                        )
                                    }

                                    if (ragChunkCount > 0) {
                                        LiquidGlassButton(
                                            onClick = {
                                                ragManager.clearKnowledgeBase()
                                                ragDocCount = 0
                                                ragChunkCount = 0
                                            },
                                            hazeState = hazeState,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(42.dp)
                                                .border(
                                                    width = 0.8.dp,
                                                    color = colors.error.copy(alpha = 0.45f),
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            tintColor = colors.error.copy(alpha = 0.16f),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = colors.error
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Clear RAG Knowledge Base",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Category: Crash Reports & Diagnostics
                    val crashReports by mainViewModel.crashReports.collectAsState()
                    var showCrashDialog by remember { mutableStateOf(false) }

                    if (showCrashDialog) {
                        CrashReportsDialog(
                            reports = crashReports,
                            onDismissRequest = { showCrashDialog = false },
                            onClearReports = { mainViewModel.clearCrashReports() },
                            onSendReport = { report ->
                                CrashReportManager.sendToGitHub(context, report.details, report.title)
                            },
                            hazeState = hazeState
                        )
                    }

                    SettingsCategory(title = "🛠️ Crash Reports & Diagnostics", hazeState = hazeState) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surfaceContainerLow,
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (crashReports.isEmpty()) Icons.Default.CheckCircle else Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = if (crashReports.isEmpty()) colors.primary else colors.error,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (crashReports.isEmpty()) {
                                                "All systems operational ✓"
                                            } else {
                                                "${crashReports.size} crash report(s) saved"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.onSurface
                                        )
                                        Text(
                                            text = if (crashReports.isEmpty()) {
                                                "No crashes recorded. Engine runtimes are stable."
                                            } else {
                                                "Latest crash: ${crashReports.first().formattedDate}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (crashReports.isNotEmpty()) {
                                        LiquidGlassButton(
                                            onClick = { showCrashDialog = true },
                                            hazeState = hazeState,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(42.dp)
                                                .border(
                                                    width = 0.8.dp,
                                                    color = colors.outlineVariant.copy(alpha = 0.50f),
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Visibility,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = colors.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "See Reports",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                        LiquidGlassButton(
                                            onClick = {
                                                val latest = crashReports.first()
                                                CrashReportManager.sendToGitHub(context, latest.details, latest.title)
                                            },
                                            hazeState = hazeState,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(42.dp)
                                                .border(
                                                    width = 0.9.dp,
                                                    color = colors.error.copy(alpha = 0.50f),
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            tintColor = colors.error.copy(alpha = 0.22f),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.BugReport,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = colors.error
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Send to Repo",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.error
                                            )
                                        }
                                    } else {
                                        LiquidGlassButton(
                                            onClick = {
                                                CrashReportManager.openNewIssue(
                                                    context,
                                                    title = "[Report / Feedback] Solus Issue",
                                                    body = "### Description\n(Describe your issue or feedback here)\n\n### Complete Device Specifications\n${DeviceUtils.formatFullDeviceDiagnostics(context)}"
                                                )
                                            },
                                            hazeState = hazeState,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(42.dp)
                                                .border(
                                                    width = 0.8.dp,
                                                    color = colors.outlineVariant.copy(alpha = 0.50f),
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.OpenInBrowser,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = colors.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "Report Bug / Issue to Repo",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Category 7: About Solus & Updates
                    val updateState by mainViewModel.updateState.collectAsState()
                    val updateDownloadStatus by mainViewModel.updateDownloadStatus.collectAsState()
                    val isCheckingForUpdates by mainViewModel.isCheckingForUpdates.collectAsState()

                    SettingsCategory(
                        title = "ℹ️ About Solus & Updates",
                        hazeState = hazeState,
                        modifier = Modifier.onGloballyPositioned { aboutCoords ->
                            val container = scrollContainerCoordinates
                            if (container != null && container.isAttached && aboutCoords.isAttached) {
                                val localPos = container.localPositionOf(aboutCoords, Offset.Zero)
                                val targetScroll = scrollState.value + localPos.y.roundToInt()
                                if (targetScroll > 0) {
                                    aboutSectionOffsetY = targetScroll
                                }
                            }
                        }
                    ) {
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
                                LiquidGlassButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateManager.GITHUB_REPO_URL))
                                        context.startActivity(intent)
                                    },
                                    hazeState = hazeState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .border(
                                            width = 0.8.dp,
                                            color = colors.outlineVariant.copy(alpha = 0.50f),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = colors.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "GitHub: ShounakPatra/Solus",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        SettingsSwitchRow(
                            title = "Automatic Check for Updates",
                            subtitle = "Periodically check for newer GitHub releases in background and send notifications",
                            checked = settingsData.autoCheckUpdates,
                            onCheckedChange = { isEnabled ->
                                appSettings.updateSettings { it.copy(autoCheckUpdates = isEnabled) }
                                if (isEnabled) {
                                    AppUpdateManager.scheduleBackgroundUpdateCheck(context, force = true)
                                    mainViewModel.checkForUpdates(silent = true)
                                } else {
                                    AppUpdateManager.cancelBackgroundUpdateCheck(context)
                                    AppUpdateManager.cancelUpdateNotification(context)
                                }
                            }
                        )

                        LiquidGlassButton(
                            onClick = { mainViewModel.checkForUpdates(silent = false) },
                            enabled = !isCheckingForUpdates,
                            hazeState = hazeState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .border(
                                    width = 1.dp,
                                    color = if (!isCheckingForUpdates) colors.primary.copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.30f),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = if (!isCheckingForUpdates) colors.primary.copy(alpha = 0.25f) else colors.surfaceContainerHighest.copy(alpha = 0.20f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            if (isCheckingForUpdates) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = colors.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Checking for updates...",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Check for Updates",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.primary
                                )
                            }
                        }

                        when (val result = updateState) {
                            is AppUpdateManager.UpdateCheckResult.UpdateAvailable -> {
                                val parsedNotes = remember(result.updateInfo.releaseNotes) {
                                    UpdateNotesFormatter.parse(result.updateInfo.releaseNotes)
                                }
                                var showFullChangelogDialog by remember { mutableStateOf(false) }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colors.primaryContainer.copy(alpha = 0.35f),
                                    border = BorderStroke(0.8.dp, colors.primary.copy(alpha = 0.50f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Icon(
                                                    Icons.Default.NewReleases,
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
                                        }

                                        if (!parsedNotes.subtitle.isNullOrBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = colors.primary.copy(alpha = 0.12f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = parsedNotes.subtitle,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                )
                                            }
                                        }

                                        // Highlights section formatted cleanly without raw HTML or markdown
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val displayBullets = if (parsedNotes.highlights.isNotEmpty()) {
                                                parsedNotes.highlights.take(4)
                                            } else {
                                                parsedNotes.cleanSummary.lines().filter { it.isNotBlank() }.take(4)
                                            }

                                            if (displayBullets.isNotEmpty()) {
                                                displayBullets.forEach { bulletText ->
                                                    val bulletFormatted = if (bulletText.trim().startsWith("•")) {
                                                        bulletText.trim()
                                                    } else {
                                                        "• ${bulletText.trim()}"
                                                    }
                                                    Text(
                                                        text = UpdateNotesFormatter.formatToAnnotatedString(
                                                            bulletFormatted,
                                                            primaryColor = colors.primary,
                                                            textColor = colors.onPrimaryContainer
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onPrimaryContainer
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = UpdateNotesFormatter.formatToAnnotatedString(
                                                        parsedNotes.cleanSummary.ifBlank { "A new update is available with improvements and fixes." },
                                                        primaryColor = colors.primary,
                                                        textColor = colors.onPrimaryContainer
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onPrimaryContainer
                                                )
                                            }
                                        }

                                        if (parsedNotes.fullChangelog.isNotBlank()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                LiquidGlassButton(
                                                    onClick = { showFullChangelogDialog = true },
                                                    hazeState = hazeState,
                                                    modifier = Modifier
                                                        .height(28.dp)
                                                        .border(
                                                            width = 0.8.dp,
                                                            color = colors.primary.copy(alpha = 0.40f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        ),
                                                    shape = RoundedCornerShape(8.dp),
                                                    tintColor = colors.primary.copy(alpha = 0.15f),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                ) {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.Article,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(13.dp),
                                                        tint = colors.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        "View Full Changelog",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = colors.primary
                                                    )
                                                }
                                            }
                                        }

                                        when (val status = updateDownloadStatus) {
                                            is MainViewModel.UpdateDownloadStatus.Downloading -> {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    LinearProgressIndicator(
                                                        progress = { status.progress },
                                                        modifier = Modifier.fillMaxWidth().height(6.dp),
                                                        color = colors.primary,
                                                        trackColor = colors.surfaceVariant
                                                    )
                                                    Text(
                                                        text = "Downloading APK update: ${(status.progress * 100).toInt()}%",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.onPrimaryContainer
                                                    )
                                                }
                                            }
                                            is MainViewModel.UpdateDownloadStatus.ReadyToInstall -> {
                                                Text(
                                                    text = "APK downloaded and ready to install.",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.primary
                                                )
                                            }
                                            is MainViewModel.UpdateDownloadStatus.Installing -> {
                                                Text(
                                                    text = "Package installer launched...",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colors.primary
                                                )
                                            }
                                            is MainViewModel.UpdateDownloadStatus.Error -> {
                                                Text(
                                                    text = "Download error: ${status.message}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.error
                                                )
                                            }
                                            else -> {}
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val apkUrl = result.updateInfo.downloadUrl
                                            when (val status = updateDownloadStatus) {
                                                is MainViewModel.UpdateDownloadStatus.Downloading -> {
                                                    LiquidGlassButton(
                                                        onClick = {},
                                                        enabled = false,
                                                        hazeState = hazeState,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(42.dp)
                                                            .border(
                                                                width = 0.8.dp,
                                                                color = colors.outlineVariant.copy(alpha = 0.30f),
                                                                shape = RoundedCornerShape(12.dp)
                                                            ),
                                                        shape = RoundedCornerShape(12.dp),
                                                        tintColor = colors.surfaceContainerHighest.copy(alpha = 0.20f),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(16.dp),
                                                            strokeWidth = 2.dp,
                                                            color = colors.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            "Downloading...",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = colors.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                is MainViewModel.UpdateDownloadStatus.ReadyToInstall -> {
                                                    LiquidGlassButton(
                                                        onClick = {
                                                            AppUpdateManager.installApk(context, status.apkFile)
                                                        },
                                                        hazeState = hazeState,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(42.dp)
                                                            .border(
                                                                width = 1.dp,
                                                                color = colors.primary.copy(alpha = 0.60f),
                                                                shape = RoundedCornerShape(12.dp)
                                                            ),
                                                        shape = RoundedCornerShape(12.dp),
                                                        tintColor = colors.primary.copy(alpha = 0.25f),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Download,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp),
                                                            tint = colors.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            "Install Update",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = colors.primary
                                                        )
                                                    }
                                                }
                                                else -> {
                                                    if (!apkUrl.isNullOrBlank()) {
                                                        LiquidGlassButton(
                                                            onClick = {
                                                                mainViewModel.downloadAndInstallUpdate(
                                                                    result.updateInfo.latestVersion,
                                                                    apkUrl
                                                                )
                                                            },
                                                            hazeState = hazeState,
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(42.dp)
                                                                .border(
                                                                    width = 1.dp,
                                                                    color = colors.primary.copy(alpha = 0.60f),
                                                                    shape = RoundedCornerShape(12.dp)
                                                                ),
                                                            shape = RoundedCornerShape(12.dp),
                                                            tintColor = colors.primary.copy(alpha = 0.25f),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Download,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp),
                                                                tint = colors.primary
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                "Download & Install",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = colors.primary
                                                            )
                                                        }
                                                    } else {
                                                        LiquidGlassButton(
                                                            onClick = {
                                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.updateInfo.htmlUrl))
                                                                context.startActivity(intent)
                                                            },
                                                            hazeState = hazeState,
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(42.dp)
                                                                .border(
                                                                    width = 1.dp,
                                                                    color = colors.primary.copy(alpha = 0.60f),
                                                                    shape = RoundedCornerShape(12.dp)
                                                                ),
                                                            shape = RoundedCornerShape(12.dp),
                                                            tintColor = colors.primary.copy(alpha = 0.25f),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.OpenInBrowser,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp),
                                                                tint = colors.primary
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                "View Release",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = colors.primary
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            LiquidGlassButton(
                                                onClick = { mainViewModel.dismissUpdateState() },
                                                hazeState = hazeState,
                                                modifier = Modifier
                                                    .height(42.dp)
                                                    .border(
                                                        width = 0.8.dp,
                                                        color = colors.outlineVariant.copy(alpha = 0.50f),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ),
                                                shape = RoundedCornerShape(12.dp),
                                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    "Dismiss",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                if (showFullChangelogDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showFullChangelogDialog = false },
                                        icon = {
                                            Icon(Icons.Default.NewReleases, contentDescription = null, tint = colors.primary)
                                        },
                                        title = {
                                            Text(
                                                "Solus v${result.updateInfo.latestVersion} Changelog",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        text = {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (!parsedNotes.subtitle.isNullOrBlank()) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = colors.primary.copy(alpha = 0.12f),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(
                                                            text = parsedNotes.subtitle,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = colors.primary,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = UpdateNotesFormatter.formatToAnnotatedString(
                                                        parsedNotes.fullChangelog,
                                                        primaryColor = colors.primary,
                                                        textColor = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            LiquidGlassButton(
                                                onClick = { showFullChangelogDialog = false },
                                                hazeState = hazeState,
                                                modifier = Modifier
                                                    .height(38.dp)
                                                    .border(
                                                        width = 0.8.dp,
                                                        color = colors.primary.copy(alpha = 0.50f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ),
                                                shape = RoundedCornerShape(10.dp),
                                                tintColor = colors.primary.copy(alpha = 0.20f),
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    "Close",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.primary
                                                )
                                            }
                                        },
                                        dismissButton = {
                                            LiquidGlassButton(
                                                onClick = {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.updateInfo.htmlUrl))
                                                    context.startActivity(intent)
                                                },
                                                hazeState = hazeState,
                                                modifier = Modifier
                                                    .height(38.dp)
                                                    .border(
                                                        width = 0.8.dp,
                                                        color = colors.outlineVariant.copy(alpha = 0.50f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ),
                                                shape = RoundedCornerShape(10.dp),
                                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.OpenInBrowser,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = colors.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    "GitHub",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                        }
                                    )
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
                                val isOffline = result.message.contains("No internet connection", ignoreCase = true) ||
                                    result.message.contains("offline", ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colors.errorContainer.copy(alpha = 0.40f),
                                    border = BorderStroke(0.8.dp, colors.error.copy(alpha = 0.40f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (isOffline) Icons.Default.WifiOff else Icons.Default.Clear,
                                                contentDescription = null,
                                                tint = colors.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                if (isOffline) "No Internet Connection" else "Update Check Failed",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.error
                                            )
                                        }
                                        Text(
                                            result.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onErrorContainer
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            LiquidGlassButton(
                                                onClick = { mainViewModel.checkForUpdates(silent = false) },
                                                hazeState = hazeState,
                                                modifier = Modifier
                                                    .height(34.dp)
                                                    .border(
                                                        width = 0.8.dp,
                                                        color = colors.error.copy(alpha = 0.50f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ),
                                                shape = RoundedCornerShape(10.dp),
                                                tintColor = colors.error.copy(alpha = 0.20f),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = colors.error
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    "Retry",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.error
                                                )
                                            }
                                            LiquidGlassButton(
                                                onClick = { mainViewModel.dismissUpdateState() },
                                                hazeState = hazeState,
                                                modifier = Modifier
                                                    .height(34.dp)
                                                    .border(
                                                        width = 0.8.dp,
                                                        color = colors.outlineVariant.copy(alpha = 0.40f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ),
                                                shape = RoundedCornerShape(10.dp),
                                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    "Dismiss",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                        }
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
internal fun SettingsCategory(
    title: String,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(20.dp)
    val cardTint = colors.surfaceContainer

    Card(
        modifier = modifier
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
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
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
            enabled = enabled,
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

@Composable
private fun CrashReportsDialog(
    reports: List<CrashReport>,
    onDismissRequest: () -> Unit,
    onClearReports: () -> Unit,
    onSendReport: (CrashReport) -> Unit,
    hazeState: HazeState
) {
    val context = LocalContext.current
    var selectedReport by remember { mutableStateOf(reports.firstOrNull()) }
    val colors = MaterialTheme.colorScheme

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .glassEffect(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(24.dp),
                    blurRadius = 24.dp,
                    tintColor = colors.surfaceContainerHigh,
                    borderAlpha = 0.40f
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = colors.error)
                        Text(
                            "Crash Reports (${reports.size})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LiquidGlassButton(
                        onClick = onDismissRequest,
                        hazeState = hazeState,
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(18.dp),
                        tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp),
                            tint = colors.onSurfaceVariant
                        )
                    }
                }

                if (reports.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No crash reports available.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    // Selector row if multiple reports
                    if (reports.size > 1) {
                        Text("Select report:", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            reports.forEachIndexed { index, report ->
                                val isSel = selectedReport == report
                                FilterChip(
                                    selected = isSel,
                                    onClick = { selectedReport = report },
                                    label = { Text("#${index + 1} • ${report.formattedDate.takeLast(8)}") },
                                    leadingIcon = if (report.isNativeCrash) {
                                        { Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }
                    }

                    // Details box for selected report
                    (selectedReport ?: reports.firstOrNull())?.let { report ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = colors.surfaceContainerLow,
                            border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = report.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (report.isNativeCrash) colors.error else colors.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (report.isNativeCrash) colors.error.copy(alpha = 0.15f) else colors.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = if (report.isNativeCrash) "NATIVE" else "JVM",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (report.isNativeCrash) colors.error else colors.primary
                                        )
                                    }
                                }

                                Text(
                                    text = report.details,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }

                        // Action Buttons: Copy, Share, Send to Repo
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LiquidGlassButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("Crash Report", report.details)
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Full crash debug log copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .border(
                                        width = 0.8.dp,
                                        color = colors.outlineVariant.copy(alpha = 0.50f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Copy",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }

                            LiquidGlassButton(
                                onClick = {
                                    CrashReportManager.shareCrashReport(context, report)
                                },
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .border(
                                        width = 0.8.dp,
                                        color = colors.outlineVariant.copy(alpha = 0.50f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Share",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }

                            LiquidGlassButton(
                                onClick = { onSendReport(report) },
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(42.dp)
                                    .border(
                                        width = 1.dp,
                                        color = colors.primary.copy(alpha = 0.60f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.primary.copy(alpha = 0.25f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Send to Repo",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.primary
                                )
                            }
                        }
                    }
                }

                if (reports.isNotEmpty()) {
                    LiquidGlassButton(
                        onClick = {
                            onClearReports()
                            selectedReport = null
                        },
                        hazeState = hazeState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .border(
                                width = 0.9.dp,
                                color = colors.error.copy(alpha = 0.50f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = colors.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Clear All Crash Reports",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.error
                        )
                    }
                }
            }
        }
    }
}


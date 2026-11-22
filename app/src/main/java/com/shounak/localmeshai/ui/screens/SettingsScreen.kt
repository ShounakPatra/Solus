package com.shounak.localmeshai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.ai.ChatInferenceManager
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.features.WorkspaceAccessibilitySection
import com.shounak.localmeshai.features.WorkspaceBackupSection
import com.shounak.localmeshai.features.WorkspaceProfilesSection
import com.shounak.localmeshai.features.WorkspaceStorageSection
import com.shounak.localmeshai.ui.components.defaultPersonas
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.AppUpdateManager
import com.shounak.localmeshai.utils.CrashReport
import com.shounak.localmeshai.utils.CrashReportManager
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HF_TOKEN_TUTORIAL_URL = "https://youtu.be/il58zFv0tmU?si=5o_gE8p-JqwWkekY"

enum class SettingsSubMenu(val title: String, val subtitle: String) {
    APPEARANCE("Appearance", "Themes and visual appearance"),
    CHAT_DEFAULTS("Chat defaults", "These apply to every new answer, on device and in the cloud."),
    VOICE("Voice & Speech", "TinyVoca neural voice, F2/M2 switcher, and speed adjustment"),
    WEB_SEARCH("Web search", "Web search providers and API keys"),
    PERSONAS("Personas", "System prompt persona presets"),
    MEMORY("Persistent Memory", "Long-term memories and facts across chats"),
    ACCESSIBILITY("Accessibility", "Motion reduction and touch targets"),
    AGENT_TOOLS("Agent & Tools", "Experimental tool calling and skills"),
    RAG("Knowledge Base (RAG)", "Offline document search and neural embeddings"),
    TELEMETRY("Telemetry & Thermal Guard", "Generation speed, battery temp and RAM free"),
    ENGINE("On-Device Engine", "Hardware backend, GPU acceleration and speed tuning"),
    PROVIDERS("Providers & API", "Hugging Face read token and downloads"),
    MODEL_PROFILES("Model Profiles", "Per-model custom system instructions and context limits"),
    STORAGE("Storage & Downloads", "Storage usage inspection and cache cleanup"),
    BACKUP("Backup & Import", "Export and restore chat history and settings"),
    PRIVACY("Privacy & Architecture", "100% on-device AI security and data isolation"),
    TROUBLESHOOTING("Troubleshooting & Diagnostics", "Crash reports, logs and repo feedback"),
    ABOUT("About Solus", "App version, developer and update checks")
}

data class SearchableSetting(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val category: String,
    val targetSubMenu: SettingsSubMenu
)

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
    val speedScope = rememberCoroutineScope()
    var tuningSpeed by remember { mutableStateOf(false) }
    var speedStatus by remember { mutableStateOf<String?>(null) }
    if (showToolSkills) ToolSkillsDialog(hazeState = hazeState, onDismiss = { showToolSkills = false })

    // Navigation and search states
    var currentSubMenu by rememberSaveable { mutableStateOf<SettingsSubMenu?>(null) }
    val mainSettingsScrollState = rememberScrollState()
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Intercept back gesture: if inside submenu or search, go back to main settings menu
    BackHandler(enabled = isSearchActive || currentSubMenu != null) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (currentSubMenu != null) {
            currentSubMenu = null
        }
    }

    LaunchedEffect(scrollToAbout) {
        if (scrollToAbout) {
            currentSubMenu = SettingsSubMenu.ABOUT
            mainViewModel.onScrolledToAbout()
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

    // Memory stats for main menu display
    val memoryManager = remember { com.shounak.localmeshai.memory.PersistentMemoryManager.getInstance(context) }
    LaunchedEffect(memoryManager) { memoryManager.loadAsync() }
    val savedMemories by memoryManager.memories.collectAsState()
    val memoryCount = savedMemories.size
    val activeMemoryCount = savedMemories.count { it.isEnabled }
    var showMemoryDialog by remember { mutableStateOf(false) }
    if (showMemoryDialog) {
        PersistentMemoryDialog(
            memoryManager = memoryManager,
            onDismissRequest = { showMemoryDialog = false }
        )
    }

    // RAG stats for main menu display
    val ragManager = remember { com.shounak.localmeshai.rag.RagManager.getInstance(context) }
    var ragDocCount by remember { mutableStateOf(ragManager.indexedDocuments.size) }
    var ragChunkCount by remember { mutableStateOf(ragManager.totalIndexedChunks) }
    val ragIndexNeedsRecovery by ragManager.indexNeedsRecovery.collectAsState()
    val ragClearScope = rememberCoroutineScope()
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

    // Crash reports
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

    // Search dictionary
    val searchableSettings = remember {
        listOf(
            SearchableSetting("Dark Mode", "Toggle dark and light visual themes", "appearance theme color style dark light mode", "APP > Appearance", SettingsSubMenu.APPEARANCE),
            SearchableSetting("Dynamic Themes", "Model accent colors for DeepSeek, Gemma, Qwen, Llama", "accent colors deepseek gemma qwen llama theme", "APP > Appearance", SettingsSubMenu.APPEARANCE),
            SearchableSetting("Warm up after loading", "Prepare prefill and decode token before ready", "warmup speed prefill decode loading startup", "APP > Chat Defaults", SettingsSubMenu.CHAT_DEFAULTS),
            SearchableSetting("Quick Suggestion Pills", "Show empty chat prompt suggestion chips", "starter prompts suggestions pills chips chat empty", "APP > Chat Defaults", SettingsSubMenu.CHAT_DEFAULTS),
            SearchableSetting("Auto-Unload Timer", "Unload inactive models from RAM to save memory and battery", "unload timer battery ram sleep timeout minutes", "APP > Chat Defaults", SettingsSubMenu.CHAT_DEFAULTS),
            SearchableSetting("Voice Switcher", "Toggle between F2 (female) and M2 (male) voice", "voice speech tts female male f2 m2 audio tinyvoca", "APP > Voice & Speech", SettingsSubMenu.VOICE),
            SearchableSetting("Speech Speed", "1.0x / 1.25x / 1.5x speed multiplier slider", "speed rate multiplier voice tts speech 1.0x 1.25x 1.5x", "APP > Voice & Speech", SettingsSubMenu.VOICE),
            SearchableSetting("TinyVoca Neural TTS", "Download and manage the on-device 8.7 MB TinyVoca model", "tinyvoca voca tts model download onnx neural speech voice", "APP > Voice & Speech", SettingsSubMenu.VOICE),
            SearchableSetting("Live Text Highlight", "Turn on or off real-time text word highlighting when voice model speaks", "live text highlight voice speech word karaoke reading read aloud tts", "APP > Voice & Speech", SettingsSubMenu.VOICE),
            SearchableSetting("Personas", "Code Auditor, ELI5, Proofreader, Translator system prompts", "personas persona presets coder eli5 proofreader translator instructions prompt", "APP > Personas", SettingsSubMenu.PERSONAS),
            SearchableSetting("Persistent Memory", "Remember user identity, preferences, facts across chats", "memory remember recall facts long term identity persistent", "APP > Memory", SettingsSubMenu.MEMORY),
            SearchableSetting("Auto-Extract Memories", "Automatically detect and save facts when you say 'Remember that...'", "memory extract auto save facts", "APP > Memory", SettingsSubMenu.MEMORY),
            SearchableSetting("Reduce Decorative Motion", "Limit movement and animated transitions", "accessibility motion animation reduce decorative", "APP > Accessibility", SettingsSubMenu.ACCESSIBILITY),
            SearchableSetting("Larger Touch Targets", "Make buttons easier to tap", "accessibility touch targets buttons large comfortable", "APP > Accessibility", SettingsSubMenu.ACCESSIBILITY),
            SearchableSetting("Tool Calling (Experimental)", "Calculator, local date/time, and document search tools", "tools tool calling calculator time function calling agent capabilities", "AGENT > Agent & Tools", SettingsSubMenu.AGENT_TOOLS),
            SearchableSetting("Web Search", "DuckDuckGo web search provider and API keys", "web search duckduckgo brave tavily internet browsing online", "AGENT > Web search", SettingsSubMenu.WEB_SEARCH),
            SearchableSetting("Tool Skills", "Manage tool skills and capabilities", "skills capabilities plugins agent extensions", "AGENT > Agent & Tools", SettingsSubMenu.AGENT_TOOLS),
            SearchableSetting("RAG Knowledge Base", "Semantically search indexed documents and inject context", "rag knowledge base documents pdf search semantic index context", "AGENT > Knowledge Base (RAG)", SettingsSubMenu.RAG),
            SearchableSetting("Neural Semantic Search", "BGE-small neural embedding search offline", "neural embeddings bge search semantic rag vector small", "AGENT > Knowledge Base (RAG)", SettingsSubMenu.RAG),
            SearchableSetting("Performance Telemetry Bar", "Show tokens per second (t/s) and latency pill", "telemetry speed tokens per second latency fps stats header pill", "AGENT > Telemetry & Thermal Guard", SettingsSubMenu.TELEMETRY),
            SearchableSetting("Battery Temperature Guard", "Display live battery thermal reading in telemetry bar", "thermal temperature battery heat celsius guard monitor", "AGENT > Telemetry & Thermal Guard", SettingsSubMenu.TELEMETRY),
            SearchableSetting("System Memory (RAM) Guard", "Display free system RAM indicator", "ram memory free gigabytes indicator telemetry guard hardware", "AGENT > Telemetry & Thermal Guard", SettingsSubMenu.TELEMETRY),
            SearchableSetting("Solus Bench Rating", "Device performance benchmark button on model cards", "bench benchmark rating speed score test solusbench", "AGENT > Telemetry & Thermal Guard", SettingsSubMenu.TELEMETRY),
            SearchableSetting("Inference Backend (GPU/CPU)", "Choose Auto, Vulkan GPU accelerator, or CPU safe mode", "backend vulkan gpu cpu acceleration hardware litert gguf", "MODELS > On-Device Engine", SettingsSubMenu.ENGINE),
            SearchableSetting("Runtime Speed Tuner", "Measure and tune CPU threads and GPU probe speeds", "tune speed benchmark threads probe optimize latency measurement", "MODELS > On-Device Engine", SettingsSubMenu.ENGINE),
            SearchableSetting("Hugging Face Access Token", "Enter personal access token for gated models", "token hugging face hf api key gated download login auth secret", "MODELS > Providers & API", SettingsSubMenu.PROVIDERS),
            SearchableSetting("Per-Model Profiles", "Save custom instructions, context limits, sampling per model", "profiles per model context limit temperature top p top k prompt sampling", "MODELS > Model Profiles", SettingsSubMenu.MODEL_PROFILES),
            SearchableSetting("Storage Usage & Breakdown", "Inspect model, attachment, and knowledge base storage", "storage size disk space usage bytes cache clean cleanup gigabytes", "MODELS > Storage & Downloads", SettingsSubMenu.STORAGE),
            SearchableSetting("Clear Unused Cache", "Delete temporary attachments and cache files", "cache clear cleanup delete free disk space temporary files", "MODELS > Storage & Downloads", SettingsSubMenu.STORAGE),
            SearchableSetting("Export Backup", "Export chat history, memories, and portable settings to ZIP", "backup export save zip transfer archive portable", "DATA > Backup & Import", SettingsSubMenu.BACKUP),
            SearchableSetting("Restore Backup", "Import saved chats, memories, and portable settings from ZIP", "restore import backup zip data load import", "DATA > Backup & Import", SettingsSubMenu.BACKUP),
            SearchableSetting("Privacy & Architecture", "100% offline, local-only AI security architecture", "privacy security offline safety local encryption tracking telemetry", "DATA > Privacy & Architecture", SettingsSubMenu.PRIVACY),
            SearchableSetting("Crash Reports & Logs", "Inspect JVM and native engine crash logs", "crash reports logs bugs error stacktrace debug exception", "HELP > Troubleshooting & Diagnostics", SettingsSubMenu.TROUBLESHOOTING),
            SearchableSetting("Report Issue to GitHub", "Open issue report or bug report on repository", "report bug github issue feedback support repo submit", "HELP > Troubleshooting & Diagnostics", SettingsSubMenu.TROUBLESHOOTING),
            SearchableSetting("About Solus & Updates", "Version info, developer, GitHub repository and update checks", "about version update release git github author developer", "HELP > About Solus", SettingsSubMenu.ABOUT),
            SearchableSetting("Check for Updates", "Check for newer Solus releases on GitHub", "update check release download upgrade new version github releases", "HELP > About Solus", SettingsSubMenu.ABOUT)
        )
    }

    val searchResults = remember(searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else {
            searchableSettings.filter { item ->
                item.title.lowercase().contains(q) ||
                item.subtitle.lowercase().contains(q) ||
                item.keywords.lowercase().contains(q) ||
                item.category.lowercase().contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (isSearchActive) {
                isSearchActive = false
                searchQuery = ""
            } else if (currentSubMenu != null) {
                currentSubMenu = null
            } else {
                onDismissRequest()
            }
        },
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
                // Top Bar
                if (currentSubMenu != null) {
                    // Submenu Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidGlassButton(
                            onClick = { currentSubMenu = null },
                            hazeState = hazeState,
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(21.dp),
                            tintColor = colors.surfaceContainer,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Settings",
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentSubMenu?.title ?: "Settings",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                ),
                                color = colors.onSurface
                            )
                            currentSubMenu?.subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else if (isSearchActive) {
                    // Search Active Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidGlassButton(
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                            },
                            hazeState = hazeState,
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(21.dp),
                            tintColor = colors.surfaceContainer,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Exit search",
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search settings, models, memory...", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = colors.onSurfaceVariant)
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.surfaceContainerLow.copy(alpha = 0.65f),
                                unfocusedContainerColor = colors.surfaceContainerLow.copy(alpha = 0.45f),
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.40f)
                            )
                        )
                    }
                } else {
                    // Main Settings Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
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
                                "Settings",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp
                                ),
                                color = colors.onSurface
                            )
                        }

                        // Search Button in Top Bar
                        LiquidGlassButton(
                            onClick = { isSearchActive = true },
                            hazeState = hazeState,
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(21.dp),
                            tintColor = colors.surfaceContainer,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search settings",
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Body content: Main Menu or Submenu or Search Results
                Box(modifier = Modifier.weight(1f)) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        // Search Results List
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (searchResults.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.SearchOff,
                                            contentDescription = null,
                                            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Text(
                                            "No settings matching \"$searchQuery\"",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "${searchResults.size} result(s) found",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                                )

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .glassEffect(
                                            hazeState = hazeState,
                                            shape = RoundedCornerShape(20.dp),
                                            blurRadius = 20.dp,
                                            tintColor = colors.surfaceContainer.copy(alpha = 0.52f),
                                            borderAlpha = 0.35f
                                        ),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        searchResults.forEachIndexed { index, item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        currentSubMenu = item.targetSubMenu
                                                        isSearchActive = false
                                                        searchQuery = ""
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.category,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                        color = colors.primary,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = item.title,
                                                        style = MaterialTheme.typography.bodyLarge.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp
                                                        ),
                                                        color = colors.onSurface
                                                    )
                                                    Text(
                                                        text = item.subtitle,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                        color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            if (index < searchResults.lastIndex) {
                                                HorizontalDivider(
                                                    color = colors.outlineVariant.copy(alpha = 0.20f),
                                                    thickness = 0.6.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        AnimatedContent(
                            targetState = currentSubMenu,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (targetState != null && initialState == null) {
                                    // Opening a submenu: slide in from right, fade in
                                    (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) + fadeIn(tween(220)))
                                        .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(280)) + fadeOut(tween(160)))
                                } else if (targetState == null && initialState != null) {
                                    // Closing submenu / returning to main menu: slide in from left, fade in
                                    (slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(280)) + fadeIn(tween(220)))
                                        .togetherWith(slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) + fadeOut(tween(160)))
                                } else {
                                    fadeIn(tween(180)).togetherWith(fadeOut(tween(180)))
                                }
                            },
                            label = "settings_menu_navigation_anim"
                        ) { subMenu ->
                            if (subMenu == null) {
                                // Main Hierarchical Settings Menu (matches reference images)
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(mainSettingsScrollState),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                            // Solus 100% On-Device AI Banner Card (matching Image 1)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentSubMenu = SettingsSubMenu.PRIVACY }
                                    .glassEffect(
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(20.dp),
                                        blurRadius = 20.dp,
                                        tintColor = colors.surfaceContainer.copy(alpha = 0.52f),
                                        borderAlpha = 0.38f
                                    ),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = colors.primary.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Memory,
                                                contentDescription = null,
                                                tint = colors.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            "Solus · 100% On-Device AI",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface
                                        )
                                        Text(
                                            "Private, local intelligence running directly on your phone hardware. Everything on this device works without an account.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = colors.onSurfaceVariant.copy(alpha = 0.50f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Category: APP
                            SettingsGroupCard(title = "APP", hazeState = hazeState) {
                                SettingsNavRow(
                                    title = "Appearance",
                                    trailingValue = if (settingsData.enableDarkMode) "Dark" else "Light",
                                    icon = Icons.Default.Palette,
                                    iconTint = Color(0xFFAB47BC),
                                    onClick = { currentSubMenu = SettingsSubMenu.APPEARANCE }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Chat defaults",
                                    subtitle = if (settingsData.autoUnloadMinutes > 0) "Auto-unload ${settingsData.autoUnloadMinutes}m" else "Auto-unload Off",
                                    icon = Icons.Default.Tune,
                                    iconTint = Color(0xFF42A5F5),
                                    onClick = { currentSubMenu = SettingsSubMenu.CHAT_DEFAULTS }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Voice & Speech",
                                    subtitle = "TinyVoca neural voice (${settingsData.ttsVoice}), ${settingsData.ttsSpeed}x",
                                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                                    iconTint = Color(0xFF29B6F6),
                                    onClick = { currentSubMenu = SettingsSubMenu.VOICE }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Personas",
                                    trailingValue = defaultPersonas.firstOrNull { it.id == settingsData.selectedPersonaId }?.title?.substringAfter(" ") ?: "Default",
                                    icon = Icons.Default.Person,
                                    iconTint = Color(0xFFFFA726),
                                    onClick = { currentSubMenu = SettingsSubMenu.PERSONAS }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Memory",
                                    trailingValue = if (settingsData.enablePersistentMemory) "$activeMemoryCount active" else "Off",
                                    icon = Icons.Default.Psychology,
                                    iconTint = Color(0xFFEC407A),
                                    onClick = { currentSubMenu = SettingsSubMenu.MEMORY }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Accessibility",
                                    subtitle = "Reduce motion, touch targets",
                                    icon = Icons.Default.Accessibility,
                                    iconTint = Color(0xFF26A69A),
                                    onClick = { currentSubMenu = SettingsSubMenu.ACCESSIBILITY }
                                )
                            }

                            // Category: AGENT & TOOLS
                            SettingsGroupCard(title = "AGENT & TOOLS", hazeState = hazeState) {
                                SettingsNavRow(
                                    title = "Agent and tools",
                                    trailingValue = if (settingsData.enableToolCalling) "On" else "Off",
                                    icon = Icons.Default.SmartToy,
                                    iconTint = Color(0xFF66BB6A),
                                    onClick = { currentSubMenu = SettingsSubMenu.AGENT_TOOLS }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Web search",
                                    trailingValue = if (settingsData.enableWebSearch) "On" else "Off",
                                    icon = Icons.Default.Language,
                                    iconTint = Color(0xFFA684FF),
                                    onClick = { currentSubMenu = SettingsSubMenu.WEB_SEARCH }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Knowledge Base (RAG)",
                                    trailingValue = if (settingsData.enableRag) "$ragDocCount docs" else "Off",
                                    icon = Icons.AutoMirrored.Filled.MenuBook,
                                    iconTint = Color(0xFF29B6F6),
                                    onClick = { currentSubMenu = SettingsSubMenu.RAG }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Telemetry & Thermal Guard",
                                    trailingValue = if (settingsData.enableTelemetryBar) "On" else "Off",
                                    icon = Icons.Default.Speed,
                                    iconTint = Color(0xFFFF7043),
                                    onClick = { currentSubMenu = SettingsSubMenu.TELEMETRY }
                                )
                            }

                            // Category: MODELS AND CONNECTIONS
                            SettingsGroupCard(title = "MODELS AND CONNECTIONS", hazeState = hazeState) {
                                SettingsNavRow(
                                    title = "On device engine",
                                    trailingValue = settingsData.llamaBackendPreference,
                                    icon = Icons.Default.Memory,
                                    iconTint = Color(0xFF9CCC65),
                                    onClick = { currentSubMenu = SettingsSubMenu.ENGINE }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Providers",
                                    trailingValue = if (settingsData.huggingFaceToken.isNotEmpty()) "Configured" else "Not set",
                                    icon = Icons.Default.VpnKey,
                                    iconTint = Color(0xFFFFCA28),
                                    onClick = { currentSubMenu = SettingsSubMenu.PROVIDERS }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Model profiles",
                                    trailingValue = "${mainViewModel.availableModels.size} models",
                                    icon = Icons.Default.SettingsSuggest,
                                    iconTint = Color(0xFF5C6BC0),
                                    onClick = { currentSubMenu = SettingsSubMenu.MODEL_PROFILES }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Storage and downloads",
                                    trailingValue = "Usage & cache",
                                    icon = Icons.Default.Storage,
                                    iconTint = Color(0xFF78909C),
                                    onClick = { currentSubMenu = SettingsSubMenu.STORAGE }
                                )
                            }

                            // Category: DATA
                            SettingsGroupCard(title = "DATA", hazeState = hazeState) {
                                SettingsNavRow(
                                    title = "Backup and import",
                                    trailingValue = "Export / restore",
                                    icon = Icons.Default.CloudUpload,
                                    iconTint = Color(0xFF26C6DA),
                                    onClick = { currentSubMenu = SettingsSubMenu.BACKUP }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "Privacy & Architecture",
                                    trailingValue = "100% offline",
                                    icon = Icons.Default.Shield,
                                    iconTint = Color(0xFF81C784),
                                    onClick = { currentSubMenu = SettingsSubMenu.PRIVACY }
                                )
                            }

                            // Category: HELP
                            SettingsGroupCard(title = "HELP", hazeState = hazeState) {
                                SettingsNavRow(
                                    title = "Troubleshooting",
                                    trailingValue = if (crashReports.isEmpty()) "Operational ✓" else "${crashReports.size} reports",
                                    icon = Icons.Default.BugReport,
                                    iconTint = if (crashReports.isEmpty()) Color(0xFF66BB6A) else Color(0xFFEF5350),
                                    onClick = { currentSubMenu = SettingsSubMenu.TROUBLESHOOTING }
                                )
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.20f), thickness = 0.6.dp)
                                SettingsNavRow(
                                    title = "About Solus",
                                    trailingValue = BuildConfig.VERSION_NAME,
                                    icon = Icons.Default.Info,
                                    iconTint = colors.primary,
                                    onClick = { currentSubMenu = SettingsSubMenu.ABOUT }
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    } else {
                        // Individual Submenu View
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (subMenu) {
                                SettingsSubMenu.APPEARANCE -> {
                                    SettingsCategory(title = "Visual Themes", hazeState = hazeState) {
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
                                }

                                SettingsSubMenu.CHAT_DEFAULTS -> {
                                    ChatDefaultsSubmenu(
                                        appSettings = appSettings,
                                        settingsData = settingsData,
                                        hazeState = hazeState
                                    )
                                }

                                SettingsSubMenu.VOICE -> {
                                    VoiceSettingsSubmenu(
                                        appSettings = appSettings,
                                        settingsData = settingsData,
                                        hazeState = hazeState
                                    )
                                }

                                SettingsSubMenu.WEB_SEARCH -> {
                                    WebSearchSubmenu(
                                        appSettings = appSettings,
                                        settingsData = settingsData,
                                        hazeState = hazeState
                                    )
                                }

                                SettingsSubMenu.PERSONAS -> {
                                    // Separate Dedicated Persona Menu
                                    SettingsCategory(title = "Active Persona", hazeState = hazeState) {
                                        Text(
                                            "Select the active persona to guide model responses, tone, and system instructions across all chats.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant.copy(alpha = 0.85f)
                                        )

                                        defaultPersonas.forEach { persona ->
                                            val isSelected = persona.id == settingsData.selectedPersonaId
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = if (isSelected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerLow,
                                                border = BorderStroke(
                                                    if (isSelected) 1.5.dp else 0.8.dp,
                                                    if (isSelected) colors.primary else colors.outlineVariant.copy(alpha = 0.35f)
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        appSettings.updateSettings { it.copy(selectedPersonaId = persona.id) }
                                                    }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(14.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            RadioButton(
                                                                selected = isSelected,
                                                                onClick = { appSettings.updateSettings { it.copy(selectedPersonaId = persona.id) } }
                                                            )
                                                            Text(
                                                                text = persona.title,
                                                                style = MaterialTheme.typography.titleSmall,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                                color = if (isSelected) colors.primary else colors.onSurface
                                                            )
                                                        }
                                                        if (persona.systemPrompt.isNotBlank()) {
                                                            LiquidGlassButton(
                                                                onClick = {
                                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                                    val clip = ClipData.newPlainText("Persona Prompt", persona.systemPrompt)
                                                                    clipboard?.setPrimaryClip(clip)
                                                                    Toast.makeText(context, "${persona.title} prompt copied", Toast.LENGTH_SHORT).show()
                                                                },
                                                                hazeState = hazeState,
                                                                modifier = Modifier.height(30.dp),
                                                                shape = RoundedCornerShape(8.dp),
                                                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                            ) {
                                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("Copy", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = if (persona.systemPrompt.isNotBlank()) persona.systemPrompt else "Default conversational model persona without custom system instructions.",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontFamily = if (persona.systemPrompt.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                                                            fontSize = 12.sp
                                                        ),
                                                        color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                                                        modifier = Modifier.padding(start = 36.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    SettingsCategory(title = "Custom Model Instructions", hazeState = hazeState) {
                                        Text(
                                            "You can also define custom persistent instructions and context limits for each individual model in Model Profiles.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant.copy(alpha = 0.82f)
                                        )
                                        LiquidGlassButton(
                                            onClick = { currentSubMenu = SettingsSubMenu.MODEL_PROFILES },
                                            hazeState = hazeState,
                                            modifier = Modifier.fillMaxWidth().height(42.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            tintColor = colors.primary.copy(alpha = 0.20f)
                                        ) {
                                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Configure Model Profiles", style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                SettingsSubMenu.MEMORY -> {
                                    SettingsCategory(title = "Persistent Memory", hazeState = hazeState) {
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
                                                            label = { Text("$activeMemoryCount active", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                                                            shape = RoundedCornerShape(10.dp),
                                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                                containerColor = colors.surfaceContainer,
                                                                labelColor = colors.primary
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
                                                            .border(0.9.dp, colors.primary.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                        shape = RoundedCornerShape(12.dp),
                                                        tintColor = colors.primary.copy(alpha = 0.20f)
                                                    ) {
                                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text("Manage Saved Memories", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                SettingsSubMenu.ACCESSIBILITY -> {
                                    WorkspaceAccessibilitySection(hazeState = hazeState)
                                }

                                SettingsSubMenu.AGENT_TOOLS -> {
                                    SettingsCategory(title = "Agent & Tool Execution", hazeState = hazeState) {
                                        SettingsSwitchRow(
                                            title = "Tool calling (experimental)",
                                            subtitle = "Calculator, local date/time and enabled document search for compatible LiteRT-LM and GGUF models. Changes apply to your next message.",
                                            checked = settingsData.enableToolCalling,
                                            onCheckedChange = { enabled -> appSettings.updateSettings { it.copy(enableToolCalling = enabled) } }
                                        )
                                        OutlinedButton(onClick = { showToolSkills = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Manage tool skills")
                                        }
                                    }
                                }

                                SettingsSubMenu.RAG -> {
                                    SettingsCategory(title = "Knowledge Base & Documents", hazeState = hazeState) {
                                        SettingsSwitchRow(
                                            title = "Enable RAG Knowledge Base",
                                            subtitle = "Semantically search indexed documents and inject relevant context into chats.",
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
                                                    "On: ${neuralSpec.displayName}, fully offline. Finds passages by meaning, not just words"
                                                } else {
                                                    "Downloads a ${com.shounak.localmeshai.utils.ByteSizeLabel.wholeMib(neuralSpec.downloadBytes)} English search model. Uses extra RAM while indexing and searching; unloads after idle."
                                                }
                                            }
                                            SettingsSwitchRow(
                                                title = "Neural Semantic Search",
                                                subtitle = neuralSubtitle,
                                                checked = neuralOn,
                                                enabled = !switching,
                                                onCheckedChange = { enable ->
                                                    if (!enable) appSettings.updateSettings { it.copy(embeddingModelPromptSkipped = true) }
                                                    ragManager.requestEmbeddingModel(
                                                        if (enable) neuralSpec.id else com.shounak.localmeshai.rag.embedding.EmbeddingModels.LEXICAL_ID
                                                    )
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
                                                            color = colors.onSurface
                                                        )
                                                        val docSuffix = if (ragDocCount == 1) "doc" else "docs"
                                                        val chunkSuffix = if (ragChunkCount == 1) "chunk" else "chunks"
                                                        val ragBadgeText = when {
                                                            ragIndexNeedsRecovery -> "Needs recovery"
                                                            ragDocCount == 0 -> "0 docs • 0 chunks"
                                                            else -> "$ragDocCount $docSuffix • $ragChunkCount $chunkSuffix"
                                                        }
                                                        SuggestionChip(
                                                            onClick = { showRagDialog = true },
                                                            label = { Text(ragBadgeText, style = MaterialTheme.typography.labelSmall) },
                                                            shape = RoundedCornerShape(10.dp),
                                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                                containerColor = colors.surfaceContainer,
                                                                labelColor = if (ragIndexNeedsRecovery) colors.error else colors.primary
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
                                                        modifier = Modifier.fillMaxWidth().height(44.dp).border(0.9.dp, colors.primary.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                        shape = RoundedCornerShape(12.dp),
                                                        tintColor = colors.primary.copy(alpha = 0.20f)
                                                    ) {
                                                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text("Manage Knowledge Base & Documents", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
                                                    }
                                                    if (ragChunkCount > 0) {
                                                        LiquidGlassButton(
                                                            onClick = {
                                                                ragClearScope.launch {
                                                                    val removal = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                                        runCatching { ragManager.clearKnowledgeBase() }.getOrElse { com.shounak.localmeshai.rag.RagRemovalResult(false, it.message) }
                                                                    }
                                                                    ragDocCount = ragManager.indexedDocuments.size
                                                                    ragChunkCount = ragManager.totalIndexedChunks
                                                                    if (!removal.success) {
                                                                        Toast.makeText(context, "Could not clear documents: ${removal.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                                                                    }
                                                                }
                                                            },
                                                            hazeState = hazeState,
                                                            modifier = Modifier.fillMaxWidth().height(42.dp).border(0.8.dp, colors.error.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            tintColor = colors.error.copy(alpha = 0.16f)
                                                        ) {
                                                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.error)
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Clear RAG Knowledge Base", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.error)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                SettingsSubMenu.TELEMETRY -> {
                                    SettingsCategory(title = "Telemetry & Thermal Guard", hazeState = hazeState) {
                                        SettingsSwitchRow(
                                            title = "Performance Telemetry Bar",
                                            subtitle = "Show generation speed (t/s) and latency (ms) header pill",
                                            checked = settingsData.enableTelemetryBar,
                                            onCheckedChange = { enabled -> appSettings.updateSettings { it.copy(enableTelemetryBar = enabled) } }
                                        )
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                                        SettingsSwitchRow(
                                            title = "Battery Temperature (°C)",
                                            subtitle = "Display live battery thermal reading in telemetry bar",
                                            checked = settingsData.showThermalGuard,
                                            onCheckedChange = { enabled -> appSettings.updateSettings { it.copy(showThermalGuard = enabled) } }
                                        )
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                                        SettingsSwitchRow(
                                            title = "System Memory Indicator",
                                            subtitle = "Display available RAM (GB free) in telemetry bar",
                                            checked = settingsData.showRamGuard,
                                            onCheckedChange = { enabled -> appSettings.updateSettings { it.copy(showRamGuard = enabled) } }
                                        )
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                                        SettingsSwitchRow(
                                            title = "Solus Bench Rating Button",
                                            subtitle = "Show device performance benchmark button on model cards",
                                            checked = settingsData.enableSolusBench,
                                            onCheckedChange = { enabled -> appSettings.updateSettings { it.copy(enableSolusBench = enabled) } }
                                        )
                                    }
                                }

                                SettingsSubMenu.ENGINE -> {
                                    SettingsCategory(title = "Hardware & Accelerator", hazeState = hazeState) {
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
                                                val isSelected = settingsData.llamaBackendPreference.equals(backendPref, ignoreCase = true)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = { appSettings.updateSettings { it.copy(llamaBackendPreference = backendPref) } },
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

                                    SettingsCategory(title = "Runtime Speed Tuner", hazeState = hazeState) {
                                        val selectedTextPath by mainViewModel.selectedTextModelPath.collectAsState()
                                        val selectedVisionPath by mainViewModel.selectedVisionModelPath.collectAsState()
                                        val tuningPath = selectedTextPath ?: selectedVisionPath
                                        val tuningModel = mainViewModel.availableModels.firstOrNull { it.localPath == tuningPath || it.id == tuningPath }
                                        val canTuneSpeed = com.shounak.localmeshai.ai.RuntimeSpeedTuner.supports(tuningModel)
                                        LiquidGlassButton(
                                            hazeState = hazeState,
                                            enabled = !tuningSpeed && canTuneSpeed,
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = {
                                                val model = tuningModel ?: return@LiquidGlassButton
                                                tuningSpeed = true
                                                speedStatus = "Starting speed measurements…"
                                                speedScope.launch {
                                                    try {
                                                        speedStatus = com.shounak.localmeshai.ai.RuntimeSpeedTuner.run(context, model) { message ->
                                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { speedStatus = message }
                                                        } + " Reload the model to apply."
                                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                                    catch (_: OutOfMemoryError) { speedStatus = "Not enough memory to tune this model. Lower context size." }
                                                    catch (failure: Exception) { speedStatus = failure.message ?: "Speed tuning failed" }
                                                    finally { tuningSpeed = false }
                                                }
                                            }
                                        ) { Text(if (tuningSpeed) "Tuning…" else "Tune speed") }
                                        Text(
                                            "Temporarily releases active runtime. Tests 2, 4 and 6 CPU threads when memory allows. LiteRT GPU must pass matching-output probes before Auto uses it.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                        if (!canTuneSpeed) {
                                            Text(
                                                tuningModel?.loadRestriction?.reason ?: "Select a downloaded GGUF or LiteRT-LM model to tune speed.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                        speedStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.primary) }
                                    }
                                }

                                SettingsSubMenu.PROVIDERS -> {
                                    SettingsCategory(title = "Hugging Face Access Token", hazeState = hazeState) {
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
                                                        IconButton(onClick = { hfTokenDraft = ""; showLastChar = false }) {
                                                            Icon(Icons.Default.Clear, contentDescription = "Clear token", tint = colors.onSurfaceVariant)
                                                        }
                                                    }
                                                    IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                                        Icon(
                                                            imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                            contentDescription = if (isTokenVisible) "Hide token" else "Show token",
                                                            tint = if (isTokenVisible) colors.primary else colors.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            visualTransformation = if (isTokenVisible) VisualTransformation.None else LastCharPasswordVisualTransformation(showLastChar = showLastChar),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = colors.surfaceContainerLow,
                                                unfocusedContainerColor = colors.surfaceContainerLow,
                                                focusedBorderColor = colors.primary,
                                                unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.65f)
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
                                                modifier = Modifier.weight(1.2f).height(42.dp).border(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                shape = RoundedCornerShape(12.dp),
                                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f)
                                            ) {
                                                Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("How to create token", maxLines = 1, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                            }

                                            val saveEnabled = hfTokenDraft.trim() != settingsData.huggingFaceToken
                                            LiquidGlassButton(
                                                onClick = {
                                                    runCatching { mainViewModel.setHuggingFaceToken(hfTokenDraft.trim()) }
                                                        .onFailure { Toast.makeText(context, "Could not save token.", Toast.LENGTH_LONG).show() }
                                                },
                                                enabled = saveEnabled,
                                                hazeState = hazeState,
                                                modifier = Modifier.weight(1f).height(42.dp).border(1.dp, if (saveEnabled) colors.primary.copy(alpha = 0.60f) else colors.outlineVariant.copy(alpha = 0.30f), RoundedCornerShape(12.dp)),
                                                shape = RoundedCornerShape(12.dp),
                                                tintColor = if (saveEnabled) colors.primary.copy(alpha = 0.25f) else colors.surfaceContainerHighest.copy(alpha = 0.20f)
                                            ) {
                                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (saveEnabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Save Token", maxLines = 1, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (saveEnabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f))
                                            }
                                        }
                                    }
                                }

                                SettingsSubMenu.MODEL_PROFILES -> {
                                    WorkspaceProfilesSection(models = mainViewModel.availableModels.toList(), hazeState = hazeState)
                                }

                                SettingsSubMenu.STORAGE -> {
                                    WorkspaceStorageSection(hazeState = hazeState)
                                }

                                SettingsSubMenu.BACKUP -> {
                                    WorkspaceBackupSection(hazeState = hazeState)
                                }

                                SettingsSubMenu.PRIVACY -> {
                                    SettingsCategory(title = "100% On-Device AI Architecture", hazeState = hazeState) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = colors.surfaceContainerLow,
                                            border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(Icons.Default.Shield, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                                                    Text("Zero Cloud Processing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                                                }
                                                Text(
                                                    "All text generation, vision analysis, memory queries, and RAG document embeddings execute 100% locally on your phone hardware. No model inputs or outputs are ever transmitted to any remote cloud server.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant.copy(alpha = 0.85f)
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = colors.surfaceContainerLow,
                                            border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(Icons.Default.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                                                    Text("Isolated Local Sandboxing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                                                }
                                                Text(
                                                    "All conversations, attachments, long-term memories, and API keys are stored in encrypted private application sandbox storage and never shared with other applications.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant.copy(alpha = 0.85f)
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = colors.surfaceContainerLow,
                                            border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                                                    Text("True Offline Freedom", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                                                }
                                                Text(
                                                    "Solus functions completely without an internet connection or phone signal once models are downloaded. Airplane mode is fully supported.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    }
                                }

                                SettingsSubMenu.TROUBLESHOOTING -> {
                                    SettingsCategory(title = "Diagnostics & Crash Reports", hazeState = hazeState) {
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
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (crashReports.isEmpty()) Icons.Default.CheckCircle else Icons.Default.BugReport,
                                                        contentDescription = null,
                                                        tint = if (crashReports.isEmpty()) colors.primary else colors.error,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(
                                                            text = if (crashReports.isEmpty()) "All systems operational ✓" else "${crashReports.size} crash report(s) saved",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = colors.onSurface
                                                        )
                                                        Text(
                                                            text = if (crashReports.isEmpty()) "No crashes recorded. Engine runtimes are stable." else "Latest crash: ${crashReports.first().formattedDate}",
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
                                                            modifier = Modifier.weight(1f).height(42.dp).border(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f)
                                                        ) {
                                                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("See Reports", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                                        }
                                                        LiquidGlassButton(
                                                            onClick = {
                                                                val latest = crashReports.first()
                                                                CrashReportManager.sendToGitHub(context, latest.details, latest.title)
                                                            },
                                                            hazeState = hazeState,
                                                            modifier = Modifier.weight(1f).height(42.dp).border(0.9.dp, colors.error.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            tintColor = colors.error.copy(alpha = 0.22f)
                                                        ) {
                                                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.error)
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Send to Repo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.error)
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
                                                            modifier = Modifier.fillMaxWidth().height(42.dp).border(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f)
                                                        ) {
                                                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text("Report Bug / Issue to Repo", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                SettingsSubMenu.ABOUT -> {
                                    val updateState by mainViewModel.updateState.collectAsState()
                                    val updateDownloadStatus by mainViewModel.updateDownloadStatus.collectAsState()
                                    val isCheckingForUpdates by mainViewModel.isCheckingForUpdates.collectAsState()

                                    SettingsCategory(title = "About Solus", hazeState = hazeState) {
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
                                                    modifier = Modifier.fillMaxWidth().height(42.dp).border(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                                    shape = RoundedCornerShape(12.dp),
                                                    tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f)
                                                ) {
                                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("GitHub: ShounakPatra/Solus", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
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
                                            modifier = Modifier.fillMaxWidth().height(42.dp).border(1.dp, colors.primary.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                            shape = RoundedCornerShape(12.dp),
                                            tintColor = colors.primary.copy(alpha = 0.20f)
                                        ) {
                                            if (isCheckingForUpdates) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Checking for updates…", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Check for Updates Now", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
                                            }
                                        }

                                        // Update state banner
                                        when (val result = updateState) {
                                            is AppUpdateManager.UpdateCheckResult.UpdateAvailable -> {
                                                Surface(
                                                    shape = RoundedCornerShape(14.dp),
                                                    color = colors.primary.copy(alpha = 0.12f),
                                                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.40f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text("Update Available: v${result.updateInfo.latestVersion}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                                                        Text(result.updateInfo.releaseNotes.take(180) + if (result.updateInfo.releaseNotes.length > 180) "…" else "", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            LiquidGlassButton(
                                                                onClick = {
                                                                    val url = result.updateInfo.downloadUrl ?: result.updateInfo.htmlUrl
                                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                                    context.startActivity(intent)
                                                                },
                                                                hazeState = hazeState,
                                                                modifier = Modifier.weight(1f).height(40.dp),
                                                                shape = RoundedCornerShape(10.dp),
                                                                tintColor = colors.primary.copy(alpha = 0.35f)
                                                            ) {
                                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text("Download")
                                                            }
                                                            LiquidGlassButton(
                                                                onClick = { mainViewModel.dismissUpdateState() },
                                                                hazeState = hazeState,
                                                                modifier = Modifier.height(40.dp),
                                                                shape = RoundedCornerShape(10.dp),
                                                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f)
                                                            ) {
                                                                Text("Dismiss")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            is AppUpdateManager.UpdateCheckResult.UpToDate -> {
                                                Text("✓ Solus is up to date (${result.currentVersion})", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                                            }
                                            is AppUpdateManager.UpdateCheckResult.Error -> {
                                                Text("Update check failed: ${result.message}", style = MaterialTheme.typography.bodySmall, color = colors.error)
                                            }
                                            null -> {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}

@Composable
fun SettingsGroupCard(
    title: String,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(20.dp)
    val cardTint = colors.surfaceContainer.copy(alpha = 0.52f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp
            ),
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .glassEffect(
                    hazeState = hazeState,
                    shape = cardShape,
                    blurRadius = 20.dp,
                    tintColor = cardTint,
                    borderAlpha = 0.35f
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    trailingValue: String? = null,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = iconTint.copy(alpha = 0.12f),
            border = BorderStroke(0.8.dp, iconTint.copy(alpha = 0.25f)),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = colors.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!trailingValue.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = trailingValue,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = colors.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp)
        )
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
    val cardTint = colors.surfaceContainer.copy(alpha = 0.52f)

    val cleanTitle = remember(title) {
        title.replace(Regex("^[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\p{Emoji}\\s]+"), "").trim().uppercase()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (cleanTitle.isNotBlank()) {
            Text(
                text = cleanTitle,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp
                ),
                color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp)
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .glassEffect(
                    hazeState = hazeState,
                    shape = cardShape,
                    blurRadius = 20.dp,
                    tintColor = cardTint,
                    borderAlpha = 0.35f
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
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
        SettingsSwitch(checked, onCheckedChange, enabled)
    }
}

@Composable
internal fun SettingsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
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
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }

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
                                    .border(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            }

                            LiquidGlassButton(
                                onClick = { CrashReportManager.shareCrashReport(context, report) },
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .border(0.8.dp, colors.outlineVariant.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.surfaceContainerHighest.copy(alpha = 0.35f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            }

                            LiquidGlassButton(
                                onClick = { onSendReport(report) },
                                hazeState = hazeState,
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(42.dp)
                                    .border(1.dp, colors.primary.copy(alpha = 0.60f), RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = colors.primary.copy(alpha = 0.25f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Send to Repo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
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
                            .border(0.9.dp, colors.error.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = colors.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.error)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear All Crash Reports", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.error)
                    }
                }
            }
        }
    }
}

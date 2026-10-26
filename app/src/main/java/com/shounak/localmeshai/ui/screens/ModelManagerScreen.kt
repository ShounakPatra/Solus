package com.shounak.localmeshai.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.utils.LocalModelImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.animation.AnimatedVisibility
import com.shounak.localmeshai.ui.components.SlidingGlassTab
import com.shounak.localmeshai.ui.components.SlidingGlassTabs
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.shounak.localmeshai.utils.ErrorClassifier
import com.shounak.localmeshai.utils.CrashReportManager
import com.shounak.localmeshai.utils.glassmorphic
import com.shounak.localmeshai.utils.glassEffect
import com.shounak.localmeshai.utils.GlassDispersionCard
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.animatedGlassHalo
import com.shounak.localmeshai.utils.fluidReveal
import com.shounak.localmeshai.utils.jellyOnTouch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.ai.GgufDiagnosticRunner
import com.shounak.localmeshai.ai.GgufDiagnosticReport
import com.shounak.localmeshai.ai.GgufBenchmarkRunner
import com.shounak.localmeshai.ai.NonGgufBenchmarkRunner
import com.shounak.localmeshai.ai.BenchmarkProgress
import com.shounak.localmeshai.ai.BenchmarkResult
import com.shounak.localmeshai.ai.GgufGpuValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.ui.theme.ModelTheme
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.DeviceUtils
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Parameter-count buckets shown as filter chips on the Models tab.
 * `All` matches every model; the others only match models whose parameter
 * count (extracted from the model name or size string) falls into the range.
 */
enum class SizeTier(val display: String) {
    All("All"),
    Small("<3B"),
    Medium("3B–<7B"),
    Large("7B–<12B"),
    XLarge("12B–<20B"),
    Huge("20B+");

    fun contains(paramsB: Float?): Boolean {
        if (this == All) return true
        val p = paramsB ?: return false
        if (!p.isFinite() || p < 0f) return false
        return when (this) {
            Small -> p < 3f
            Medium -> p >= 3f && p < 7f
            Large -> p >= 7f && p < 12f
            XLarge -> p >= 12f && p < 20f
            Huge -> p >= 20f
            All -> true
        }
    }
}

/**
 * Source filter selector on the Models tab: "All models" or "Imported" (custom imported models).
 */
enum class ModelSourceFilter(val display: String) {
    All("All models"),
    Imported("Imported");
}


@Composable
private fun ModelSectionTitle(
    title: String,
    delayMillis: Int,
    enterFromEnd: Boolean = false
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 4.dp)
            .fluidReveal(
                delayMillis = delayMillis,
                initialScale = 0.98f,
                initialYOffset = 4.dp,
                initialXOffset = if (enterFromEnd) 22.dp else (-22).dp,
                initialRotationZ = if (enterFromEnd) 0.65f else -0.65f
            )
    )
}

/**
 * Best-effort parameter count in billions. Tries the model name first
 * (e.g. "Qwen 3.5 0.8B", "Gemma 4 E2B", "InternLM3-8B", "Qwen3-30B-A3B"),
 * File sizes in GB/MB do not establish a parameter count.
 * Returns null when the model name has no parameter count.
 */
private fun ModelInfo.parametersBillions(): Float? {
    val nameMatch = Regex("""(\d+(?:\.\d+)?)\s*([BM])(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE).find(name)
    if (nameMatch != null) {
        val count = nameMatch.groupValues[1].toFloatOrNull() ?: return null
        return if (nameMatch.groupValues[2].equals("M", ignoreCase = true)) count / 1000f else count
    }
    return null
}

/**
 * True when the model name matches the MoE "XB-AYB" naming convention
 * (e.g. "Qwen3-30B-A3B", "Gemma 4 26B-A4B", "LFM2.5-8B-A1B").
 */
private fun ModelInfo.isMixtureOfExperts(): Boolean {
    return Regex("""\d+(?:\.\d+)?B-A\d+(?:\.\d+)?B?""").containsMatchIn(name)
}

internal fun ModelInfo.isPinnedDownloadCard(): Boolean {
    return when (status) {
        ModelStatus.Failed -> true
        ModelStatus.Downloading, ModelStatus.Paused -> true
        ModelStatus.Available -> true
        ModelStatus.Blocked -> localPath != null
        else -> false
    }
}

internal fun ModelInfo.downloadTime(): Long {
    if (downloadedAt > 0L) return downloadedAt
    val bundleTime = bundle?.addedAt ?: 0L
    if (bundleTime > 0L) return bundleTime
    val path = localPath ?: return 0L
    return try {
        val file = File(path)
        if (file.exists()) file.lastModified() else 0L
    } catch (_: Exception) {
        0L
    }
}

internal fun ModelInfo.downloadedSectionSortRank(): Int {
    return when (status) {
        ModelStatus.Failed -> 0
        ModelStatus.Downloading -> 1
        ModelStatus.Paused -> 2
        ModelStatus.Available -> 3
        ModelStatus.Blocked -> 3
        else -> 4
    }
}

/** A new completed download temporarily leads the successful-load MRU list. */
internal fun orderDownloadedModels(models: List<ModelInfo>, usedAt: Map<String, Long>): List<ModelInfo> {
    val used = models.filter { it.status == ModelStatus.Available && it.id in usedAt }.sortedByDescending { usedAt[it.id] }
    val newestDownload = models.filter { it.status == ModelStatus.Available }
        .maxByOrNull { it.downloadTime() }
        ?.takeIf { it.downloadTime() > (used.firstOrNull()?.let { model -> usedAt[model.id] } ?: 0L) }
    val promoted = (listOfNotNull(newestDownload) + used).distinctBy { it.id }
    val promotedIds = promoted.map { it.id }.toSet()
    return promoted + models.filter { it.id !in promotedIds }.sortedWith(
        compareBy<ModelInfo> { it.downloadedSectionSortRank() }.thenByDescending { it.downloadTime() }.thenBy { it.id }
    )
}

/**
 * Combined search + tier filter used to scope which models show up
 * on the Models tab. Supports multi-word matching across name, id,
 * description, filename, backend, and tags.
 */
internal fun ModelInfo.matches(query: String, tier: SizeTier): Boolean {
    if (!tier.contains(parametersBillions())) return false
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    val words = q.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (words.isEmpty()) return true

    val cleanQuery = q.replace(Regex("""[^a-z0-9]"""), "")
    val cleanName = name.lowercase().replace(Regex("""[^a-z0-9]"""), "")
    val cleanId = id.lowercase().replace(Regex("""[^a-z0-9]"""), "")
    val nameLower = name.lowercase()
    val idLower = id.lowercase()
    val descLower = description.lowercase()
    val fileLower = fileName.lowercase()
    val backendLower = backend.lowercase()
    val typeLower = type.label.lowercase()

    // Fast path: direct substring match
    if (nameLower.contains(q) || idLower.contains(q) || descLower.contains(q) ||
        fileLower.contains(q) || backendLower.contains(q) || typeLower.contains(q)) {
        return true
    }

    // Normalized alphanumeric match (e.g. "llama3" matches "Llama 3")
    if (cleanQuery.isNotEmpty() && (cleanName.contains(cleanQuery) || cleanId.contains(cleanQuery))) {
        return true
    }

    // Match if all searched words are found across model metadata
    return words.all { word ->
        val cleanWord = word.replace(Regex("""[^a-z0-9]"""), "")
        nameLower.contains(word) ||
            idLower.contains(word) ||
            descLower.contains(word) ||
            fileLower.contains(word) ||
            backendLower.contains(word) ||
            typeLower.contains(word) ||
            (word == "moe" && isMixtureOfExperts()) ||
            (isCustom && (word == "imported" || word == "import" || q == "imported" || q == "import")) ||
            (cleanWord.isNotEmpty() && cleanName.contains(cleanWord))
    }
}

/**
 * Calculates search relevance score for ordering search results.
 * Higher score = closer match shown topmost.
 */
internal fun ModelInfo.searchRelevance(query: String): Int {
    val q = query.trim().lowercase()
    if (q.isBlank()) return 0
    val modelName = name.lowercase()
    val modelId = id.lowercase()
    val cleanQuery = q.replace(Regex("""[^a-z0-9]"""), "")
    val cleanName = modelName.replace(Regex("""[^a-z0-9]"""), "")
    val words = q.split(Regex("""\s+""")).filter { it.isNotBlank() }

    var score = 0

    // Exact name match
    if (modelName == q || (cleanQuery.isNotEmpty() && cleanName == cleanQuery)) {
        score += 1000
    }
    // Name starts with query
    else if (modelName.startsWith(q) || (cleanQuery.isNotEmpty() && cleanName.startsWith(cleanQuery))) {
        score += 800
    }
    // Name contains entire query as contiguous substring
    else if (modelName.contains(q) || (cleanQuery.isNotEmpty() && cleanName.contains(cleanQuery))) {
        score += 600
    }
    // All search words in name
    else if (words.isNotEmpty() && words.all { modelName.contains(it) }) {
        score += 500
    }
    // Model ID exact or contains
    else if (modelId == q) {
        score += 450
    } else if (modelId.contains(q)) {
        score += 400
    } else if (words.isNotEmpty() && words.all { modelId.contains(it) }) {
        score += 350
    }
    // Description contains query
    else if (description.lowercase().contains(q)) {
        score += 200
    } else if (words.isNotEmpty() && words.all { description.lowercase().contains(it) }) {
        score += 150
    } else if (isCustom && (q == "imported" || q == "import" || words.any { it == "imported" || it == "import" })) {
        score += 250
    } else {
        score += 100
    }

    return score
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun ModelManagerScreen(
    mainViewModel: MainViewModel = viewModel(),
    hazeState: HazeState,
    onGoToChat: (() -> Unit)? = null,
    bottomContentInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val modelUsedAt by com.shounak.localmeshai.utils.ModelUsageStore.get(context).usedAt.collectAsState()
    val selectedTextModel by mainViewModel.selectedTextModelPath.collectAsState()
    val selectedVisionModel by mainViewModel.selectedVisionModelPath.collectAsState()
    val unloadingModelIds by mainViewModel.unloadingModelIds.collectAsState()
    val huggingFaceToken by mainViewModel.huggingFaceToken.collectAsState()
    val appSettingsData by mainViewModel.appSettingsData.collectAsState()
    val enableDynamicThemes = appSettingsData.enableDynamicThemes

    val hiddenIds by mainViewModel.hiddenFromPresentationIds.collectAsState()

    // Search + tier + source filter state. rememberSaveable so they survive config
    // changes (rotation, dark mode toggle, etc.).
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTier by rememberSaveable { mutableStateOf(SizeTier.All) }
    var selectedSourceFilter by rememberSaveable { mutableStateOf(ModelSourceFilter.All) }

    val isSearching = searchQuery.isNotBlank()

    val visibleAvailableModels = mainViewModel.availableModels.filter { it.id !in hiddenIds }
    val filteredSourceModels = when (selectedSourceFilter) {
        ModelSourceFilter.All -> visibleAvailableModels
        ModelSourceFilter.Imported -> visibleAvailableModels.filter { it.isCustom }
    }
    val filteredModels = filteredSourceModels.filter { it.matches(searchQuery, selectedTier) }

    // Active downloads and local files are pinned to the top so progress/status
    // does not jump around between catalog sections.
    val downloadedModels = orderDownloadedModels(filteredModels.filter { it.isPinnedDownloadCard() }, modelUsedAt)
    val downloadedIds = downloadedModels.map { it.id }.toSet()

    val textModels = filteredModels.filter { it.id !in downloadedIds && it.type == ModelType.Text }
    val visionModels = filteredModels.filter { it.id !in downloadedIds && it.type == ModelType.Vision }

    val matchingModels = if (!isSearching) {
        emptyList()
    } else {
        filteredModels.sortedWith(
            compareByDescending<ModelInfo> { it.searchRelevance(searchQuery) }
                .thenBy { it.name }
        )
    }

    val filterActive = isSearching || selectedTier != SizeTier.All || selectedSourceFilter != ModelSourceFilter.All
    val hasMatches = if (isSearching) {
        matchingModels.isNotEmpty()
    } else {
        filteredModels.isNotEmpty()
    }
    val showEmpty = filterActive && !hasMatches

    val readyTextModels = textModels.filter {
        it.id !in downloadedIds &&
            it.status != ModelStatus.NeedsConversion &&
            it.status != ModelStatus.ComingSoon &&
            it.status != ModelStatus.Blocked &&
            !it.requiresHuggingFaceToken
    }
    val readyVisionModels = visionModels.filter {
        it.id !in downloadedIds &&
            it.status != ModelStatus.NeedsConversion &&
            it.status != ModelStatus.ComingSoon &&
            it.status != ModelStatus.Blocked &&
            !it.requiresHuggingFaceToken
    }

    val recommendedModels = (readyTextModels + readyVisionModels)
        .filter { it.isRecommended }
        .sortedWith(
            compareBy<ModelInfo> { model ->
                if (model.id == "gemma4_e2b_litertlm" || model.name.contains("Gemma 4 E2B", ignoreCase = true)) 0 else 1
            }
        )
    val recommendedIds = recommendedModels.map { it.id }.toSet()
    val standardTextModels = readyTextModels.filter { it.id !in recommendedIds }
    val standardVisionModels = readyVisionModels.filter { it.id !in recommendedIds }
    val gatedTextModels = textModels.filter {
        it.id !in downloadedIds &&
            it.status != ModelStatus.NeedsConversion &&
            it.status != ModelStatus.ComingSoon &&
            it.status != ModelStatus.Blocked &&
            it.requiresHuggingFaceToken
    }
    val blockedTextModels = textModels.filter { it.id !in downloadedIds && it.status == ModelStatus.Blocked }
    val futureDenseTextModels = textModels.filter {
        it.id !in downloadedIds && (it.status == ModelStatus.NeedsConversion || it.status == ModelStatus.ComingSoon) && !it.isMixtureOfExperts()
    }
    val futureMoeTextModels = textModels.filter {
        it.id !in downloadedIds && (it.status == ModelStatus.NeedsConversion || it.status == ModelStatus.ComingSoon) && it.isMixtureOfExperts()
    }
    val gatedVisionModels = visionModels.filter {
        it.id !in downloadedIds &&
            it.status != ModelStatus.NeedsConversion &&
            it.status != ModelStatus.ComingSoon &&
            it.status != ModelStatus.Blocked &&
            it.requiresHuggingFaceToken
    }
    val blockedVisionModels = visionModels.filter { it.id !in downloadedIds && it.status == ModelStatus.Blocked }
    val futureDenseVisionModels = visionModels.filter {
        it.id !in downloadedIds && (it.status == ModelStatus.NeedsConversion || it.status == ModelStatus.ComingSoon) && !it.isMixtureOfExperts()
    }
    val futureMoeVisionModels = visionModels.filter {
        it.id !in downloadedIds && (it.status == ModelStatus.NeedsConversion || it.status == ModelStatus.ComingSoon) && it.isMixtureOfExperts()
    }

    val listState = rememberLazyListState()
    val isKeyboardOpen = WindowInsets.isImeVisible
    var showAddModelSheet by rememberSaveable { mutableStateOf(false) }
    var addModelSheetSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val areAnimationsDisabled = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0.0f
        }.getOrDefault(false)
    }

    val itemFadeInSpec = if (areAnimationsDisabled) null else tween<Float>(durationMillis = 220)
    val itemFadeOutSpec = if (areAnimationsDisabled) null else tween<Float>(durationMillis = 180)
    val itemPlacementSpec = if (areAnimationsDisabled) null else tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 260)

    var isAddModelExpanded by rememberSaveable {
        mutableStateOf(listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
    }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        var accumulatedScroll = 0
        val thresholdPx = with(density) { 16.dp.roundToPx() }

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (currentIndex, currentOffset, isScrolling) ->
            if (currentIndex == 0 && currentOffset == 0) {
                if (!isAddModelExpanded) isAddModelExpanded = true
                accumulatedScroll = 0
                previousIndex = 0
                previousOffset = 0
                return@collect
            }

            val delta = if (currentIndex == previousIndex) {
                currentOffset - previousOffset
            } else if (currentIndex > previousIndex) {
                thresholdPx + 1
            } else {
                -(thresholdPx + 1)
            }

            previousIndex = currentIndex
            previousOffset = currentOffset

            if (isScrolling) {
                if (delta > 0) {
                    if (accumulatedScroll < 0) accumulatedScroll = 0
                    accumulatedScroll += delta
                    if (accumulatedScroll >= thresholdPx && isAddModelExpanded) {
                        isAddModelExpanded = false
                    }
                } else if (delta < 0) {
                    if (accumulatedScroll > 0) accumulatedScroll = 0
                    accumulatedScroll += delta
                    if (accumulatedScroll <= -thresholdPx && !isAddModelExpanded) {
                        isAddModelExpanded = true
                    }
                }
            } else {
                accumulatedScroll = 0
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = if (isKeyboardOpen) 24.dp else bottomContentInset + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item(key = "title_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Model Manager",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                LiquidGlassButton(
                    onClick = { mainViewModel.openSettings(false) },
                    hazeState = hazeState,
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(21.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
                }
            }
        }

        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        stickyHeader(key = "search_header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(vertical = 4.dp)
            ) {
                SearchAndFilterRow(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    selectedTier = selectedTier,
                    onTierChange = { selectedTier = it },
                    selectedSourceFilter = selectedSourceFilter,
                    onSourceFilterChange = { selectedSourceFilter = it },
                    hazeState = hazeState
                )
            }
        }

        if (!isSearching) {
            item(key = "document_search_model") {
                com.shounak.localmeshai.ui.components.DocumentSearchModelCard(hazeState = hazeState)
            }

            item(key = "storage_summary") {
                // Storage usage summary
                val downloadedForSummary = mainViewModel.availableModels.filter {
                    it.localPath != null &&
                        (it.status == ModelStatus.Available ||
                            it.status == ModelStatus.Blocked ||
                            it.status == ModelStatus.Failed)
                }
                StorageSummaryCard(downloadedModels = downloadedForSummary, hazeState = hazeState)
            }

            item(key = "device_hardware") {
                DeviceHardwareCard(hazeState = hazeState)
            }

            item(key = "privacy_card") {
                PrivacyCard(hazeState = hazeState)
            }
        }

        if (isSearching) {
            if (showEmpty) {
                item(key = "empty_matching_models") {
                    NoModelsMatchCard(
                        searchQuery = searchQuery,
                        selectedTier = selectedTier,
                        selectedSourceFilter = selectedSourceFilter,
                        onClear = {
                            searchQuery = ""
                            selectedTier = SizeTier.All
                            selectedSourceFilter = ModelSourceFilter.All
                        },
                        hazeState = hazeState
                    )
                }
            } else {
                itemsIndexed(
                    matchingModels,
                    key = { _, model -> model.id },
                    contentType = { _, _ -> "model_card" }
                ) { _, model ->
                    val isSelected = when (model.type) {
                        ModelType.Text -> selectedTextModel != null && (selectedTextModel == model.localPath || selectedTextModel == model.id)
                        ModelType.Vision -> selectedVisionModel != null && (selectedVisionModel == model.localPath || selectedVisionModel == model.id)
                    }
                    ModelItem(
                        model = model,
                        isSelected = isSelected,
                        modifier = Modifier.animateItem(
                            fadeInSpec = itemFadeInSpec,
                            fadeOutSpec = itemFadeOutSpec,
                            placementSpec = itemPlacementSpec
                        ),
                        isRemoving = mainViewModel.removingModelIds.contains(model.id),
                        isUnloading = unloadingModelIds.contains(model.id),
                        onUnload = { mainViewModel.unloadModel(model.id) },
                        onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                        onAction = {
                            when {
                                model.status == ModelStatus.Available && (model.localPath != null || model.isStorageImport) -> {
                                    val targetPath = model.localPath ?: model.id
                                    when (model.type) {
                                        ModelType.Text -> mainViewModel.selectTextModel(targetPath)
                                        ModelType.Vision -> mainViewModel.selectVisionModel(targetPath)
                                    }
                                }
                                model.status == ModelStatus.Paused || model.status == ModelStatus.Failed -> {
                                    mainViewModel.resumeDownload(model.id)
                                }
                                model.status == ModelStatus.Downloading -> { /* active download */ }
                                model.status == ModelStatus.Blocked -> {
                                    if (model.localPath != null) {
                                        mainViewModel.tryModelAnyway(model.id)
                                        onGoToChat?.invoke()
                                    } else {
                                        mainViewModel.startDownload(model.id)
                                    }
                                }
                                else -> mainViewModel.startDownload(model.id)
                            }
                        },
                        onPause = { mainViewModel.pauseDownload(model.id) },
                        onCancel = { mainViewModel.cancelDownload(model.id) },
                        onDelete = if (model.status == ModelStatus.NeedsConversion || model.status == ModelStatus.ComingSoon) null else { { mainViewModel.deleteModel(model.id) } },
                        onUnsafeTry = {
                            mainViewModel.tryModelAnyway(model.id)
                            onGoToChat?.invoke()
                        },
                        onRelinkBundle = { importResult -> mainViewModel.relinkModelBundle(model.id, importResult) },
                        isHfTokenBlank = appSettingsData.huggingFaceToken.isBlank(),
                        onOpenSettings = { mainViewModel.openSettings(false) },
                        enableDynamicThemes = enableDynamicThemes,
                        hazeState = hazeState
                    )
                }
            }
        } else {
            if (showEmpty) {
                item(key = "empty_all_models") {
                    NoModelsMatchCard(
                        searchQuery = searchQuery,
                        selectedTier = selectedTier,
                        selectedSourceFilter = selectedSourceFilter,
                        onClear = {
                            searchQuery = ""
                            selectedTier = SizeTier.All
                            selectedSourceFilter = ModelSourceFilter.All
                        },
                        hazeState = hazeState
                    )
                }
            }

        if (downloadedModels.isNotEmpty()) {
            item(key = "section_downloaded") {
                ModelSectionTitle("Downloaded models", delayMillis = 40)
            }
            itemsIndexed(
                downloadedModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                val isSelected = when (model.type) {
                    ModelType.Text -> selectedTextModel != null && (selectedTextModel == model.localPath || selectedTextModel == model.id)
                    ModelType.Vision -> selectedVisionModel != null && (selectedVisionModel == model.localPath || selectedVisionModel == model.id)
                }
                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isUnloading = unloadingModelIds.contains(model.id),
                    onUnload = { mainViewModel.unloadModel(model.id) },
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        if (model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) {
                            mainViewModel.resumeDownload(model.id)
                        } else if (model.status == ModelStatus.Blocked && model.localPath == null && !model.isStorageImport) {
                            mainViewModel.startDownload(model.id)
                        } else if (model.localPath != null || model.isStorageImport) {
                            val targetPath = model.localPath ?: model.id
                            when (model.type) {
                                ModelType.Text -> mainViewModel.selectTextModel(targetPath)
                                ModelType.Vision -> mainViewModel.selectVisionModel(targetPath)
                            }
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    onUnsafeTry = {
                        mainViewModel.tryModelAnyway(model.id)
                        onGoToChat?.invoke()
                    },
                    onRelinkBundle = { importResult -> mainViewModel.relinkModelBundle(model.id, importResult) },
                    isHfTokenBlank = appSettingsData.huggingFaceToken.isBlank(),
                    onOpenSettings = { mainViewModel.openSettings(false) },
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (recommendedModels.isNotEmpty()) {
            item(key = "section_recommended") {
                ModelSectionTitle("Recommended downloads", delayMillis = 65, enterFromEnd = true)
            }

            itemsIndexed(
                recommendedModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                val isSelected = (model.localPath != null) && when (model.type) {
                    ModelType.Text -> selectedTextModel != null && (selectedTextModel == model.localPath || selectedTextModel == model.id)
                    ModelType.Vision -> selectedVisionModel != null && (selectedVisionModel == model.localPath || selectedVisionModel == model.id)
                }

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    isUnloading = unloadingModelIds.contains(model.id),
                    onUnload = { mainViewModel.unloadModel(model.id) },
                    onOpenPage = model.modelPageUrl?.let { url ->
                        { openUrl(context, url) }
                    },
                    onAction = {
                        if (model.status == ModelStatus.Available && model.localPath != null) {
                            when (model.type) {
                                ModelType.Text -> mainViewModel.selectTextModel(model.localPath)
                                ModelType.Vision -> mainViewModel.selectVisionModel(model.localPath)
                            }
                        } else if (model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) {
                            mainViewModel.resumeDownload(model.id)
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    isHfTokenBlank = appSettingsData.huggingFaceToken.isBlank(),
                    onOpenSettings = { mainViewModel.openSettings(false) },
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (standardTextModels.isNotEmpty()) {
            item(key = "section_standard_text") {
                ModelSectionTitle("Text models", delayMillis = 80)
            }

            itemsIndexed(
                standardTextModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                val isSelected = (model.localPath != null) &&
                    selectedTextModel != null && (selectedTextModel == model.localPath || selectedTextModel == model.id)

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    isUnloading = unloadingModelIds.contains(model.id),
                    onUnload = { mainViewModel.unloadModel(model.id) },
                    onOpenPage = model.modelPageUrl?.let { url ->
                        { openUrl(context, url) }
                    },
                    onAction = {
                        if (model.status == ModelStatus.Available && model.localPath != null) {
                            if (model.type == ModelType.Text) {
                                mainViewModel.selectTextModel(model.localPath)
                            }
                        } else if (model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) {
                            mainViewModel.resumeDownload(model.id)
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    isHfTokenBlank = appSettingsData.huggingFaceToken.isBlank(),
                    onOpenSettings = { mainViewModel.openSettings(false) },
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (standardVisionModels.isNotEmpty()) {
            item(key = "section_standard_vision") {
                ModelSectionTitle("Image and audio models", delayMillis = 95, enterFromEnd = true)
            }
            itemsIndexed(
                standardVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                val isSelected = model.localPath != null &&
                    selectedVisionModel != null && (selectedVisionModel == model.localPath || selectedVisionModel == model.id)

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    isUnloading = unloadingModelIds.contains(model.id),
                    onUnload = { mainViewModel.unloadModel(model.id) },
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        if (model.status == ModelStatus.Available && model.localPath != null) {
                            mainViewModel.selectVisionModel(model.localPath)
                        } else if (model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) {
                            mainViewModel.resumeDownload(model.id)
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    isHfTokenBlank = appSettingsData.huggingFaceToken.isBlank(),
                    onOpenSettings = { mainViewModel.openSettings(false) },
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (gatedTextModels.isNotEmpty() || gatedVisionModels.isNotEmpty()) {
            item(key = "section_gated") {
                ModelSectionTitle("Requires license or Hugging Face token", delayMillis = 110)
            }
            itemsIndexed(
                gatedTextModels + gatedVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                val isSelected = model.localPath != null && when (model.type) {
                    ModelType.Text -> selectedTextModel != null && (selectedTextModel == model.localPath || selectedTextModel == model.id)
                    ModelType.Vision -> selectedVisionModel != null && (selectedVisionModel == model.localPath || selectedVisionModel == model.id)
                }

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    isUnloading = unloadingModelIds.contains(model.id),
                    onUnload = { mainViewModel.unloadModel(model.id) },
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        when {
                            model.status == ModelStatus.Available && model.localPath != null -> {
                                when (model.type) {
                                    ModelType.Text -> mainViewModel.selectTextModel(model.localPath)
                                    ModelType.Vision -> mainViewModel.selectVisionModel(model.localPath)
                                }
                            }
                            model.status == ModelStatus.Paused || model.status == ModelStatus.Failed -> mainViewModel.resumeDownload(model.id)
                            else -> mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    isHfTokenBlank = appSettingsData.huggingFaceToken.isBlank(),
                    onOpenSettings = { mainViewModel.openSettings(false) },
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (blockedTextModels.isNotEmpty() || blockedVisionModels.isNotEmpty()) {
            item(key = "section_blocked") {
                ModelSectionTitle("Blocked by device safety profile", delayMillis = 125, enterFromEnd = true)
            }
            itemsIndexed(
                blockedTextModels + blockedVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                val isSelected = model.localPath != null && when (model.type) {
                    ModelType.Text -> selectedTextModel != null && (selectedTextModel == model.localPath || selectedTextModel == model.id)
                    ModelType.Vision -> selectedVisionModel != null && (selectedVisionModel == model.localPath || selectedVisionModel == model.id)
                }
                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    isUnloading = unloadingModelIds.contains(model.id),
                    onUnload = { mainViewModel.unloadModel(model.id) },
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        if (model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) {
                            mainViewModel.resumeDownload(model.id)
                        } else if (model.localPath != null) {
                            mainViewModel.tryModelAnyway(model.id)
                            onGoToChat?.invoke()
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    onUnsafeTry = {
                        mainViewModel.tryModelAnyway(model.id)
                        onGoToChat?.invoke()
                    },
                    onRelinkBundle = { importResult -> mainViewModel.relinkModelBundle(model.id, importResult) },
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (futureDenseTextModels.isNotEmpty() || futureDenseVisionModels.isNotEmpty()) {
            item(key = "section_future_dense") {
                ModelSectionTitle("Future Android builds", delayMillis = 140)
            }
            itemsIndexed(
                futureDenseTextModels + futureDenseVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                ModelItem(
                    model = model,
                    isSelected = false,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = null,
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }

        if (futureMoeTextModels.isNotEmpty() || futureMoeVisionModels.isNotEmpty()) {
            item(key = "section_future_moe") {
                ModelSectionTitle("MoE future builds", delayMillis = 155, enterFromEnd = true)
            }
            itemsIndexed(
                futureMoeTextModels + futureMoeVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
                ModelItem(
                    model = model,
                    isSelected = false,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        fadeOutSpec = itemFadeOutSpec,
                        placementSpec = itemPlacementSpec
                    ),
                    isRemoving = mainViewModel.removingModelIds.contains(model.id),
                    isCancelling = mainViewModel.cancellingModelIds.contains(model.id),
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = null,
                    enableDynamicThemes = enableDynamicThemes,
                    hazeState = hazeState
                )
            }
        }
    }
}

    val animDuration = if (areAnimationsDisabled) 0 else 200
    val addFabShape = RoundedCornerShape(28.dp)
    val addFabInteractionSource = remember { MutableInteractionSource() }
    val isAddFabPressed by addFabInteractionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val addFabScale by animateFloatAsState(
        targetValue = if (isAddFabPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "add_model_scale"
    )

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = bottomContentInset + 20.dp)
            .height(56.dp)
            .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
            .graphicsLayer {
                scaleX = addFabScale
                scaleY = addFabScale
            }
            .clip(addFabShape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    blurRadius = 26.dp,
                    tint = HazeTint(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                    noiseFactor = 0.08f
                )
            )
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                shape = addFabShape
            )
            .animateContentSize(
                animationSpec = if (areAnimationsDisabled) androidx.compose.animation.core.snap()
                else tween(durationMillis = animDuration)
            )
            .clickable(
                interactionSource = addFabInteractionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val sessionId = java.util.UUID.randomUUID().toString()
                addModelSheetSessionId = sessionId
                mainViewModel.openImportSheet(sessionId)
                showAddModelSheet = true
            }
            .semantics {
                contentDescription = "Add model"
                role = Role.Button
            }
            .padding(
                if (isAddModelExpanded) {
                    PaddingValues(start = 16.dp, end = 20.dp)
                } else {
                    PaddingValues(horizontal = 16.dp)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            AnimatedVisibility(
                visible = isAddModelExpanded,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = if (areAnimationsDisabled) androidx.compose.animation.core.snap()
                    else tween(durationMillis = animDuration)
                ) + androidx.compose.animation.expandHorizontally(
                    animationSpec = if (areAnimationsDisabled) androidx.compose.animation.core.snap()
                    else tween(durationMillis = animDuration)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = if (areAnimationsDisabled) androidx.compose.animation.core.snap()
                    else tween(durationMillis = if (animDuration > 50) animDuration - 50 else 0)
                ) + androidx.compose.animation.shrinkHorizontally(
                    animationSpec = if (areAnimationsDisabled) androidx.compose.animation.core.snap()
                    else tween(durationMillis = if (animDuration > 50) animDuration - 50 else 0)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add model",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (showAddModelSheet) {
        val currentSessionId = addModelSheetSessionId ?: rememberSaveable { java.util.UUID.randomUUID().toString() }.also { addModelSheetSessionId = it }
        AddModelSheet(
            onDismiss = {
                if (addModelSheetSessionId == currentSessionId) {
                    showAddModelSheet = false
                    addModelSheetSessionId = null
                    mainViewModel.consumeCompletionEvent(currentSessionId)
                    mainViewModel.dismissImportSheet(currentSessionId)
                }
            },
            mainViewModel = mainViewModel,
            sheetSessionId = currentSessionId,
            hazeState = hazeState
        )
    }
}
}

@Composable
private fun SearchAndFilterRow(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedTier: SizeTier,
    onTierChange: (SizeTier) -> Unit,
    selectedSourceFilter: ModelSourceFilter,
    onSourceFilterChange: (ModelSourceFilter) -> Unit,
    hazeState: HazeState
) {
    val searchShape = RoundedCornerShape(14.dp)
    val searchTint = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .animatedGlassHalo(shape = searchShape, alpha = 0.035f, durationMillis = 4_800)
                .glassEffect(
                    hazeState = hazeState,
                    shape = searchShape,
                    blurRadius = 22.dp,
                    tintColor = searchTint,
                    borderAlpha = 0.34f
                ),
            placeholder = { Text("Search models") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            shape = searchShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            )
        )

        SlidingGlassTabs(
            tabs = ModelSourceFilter.entries.map { SlidingGlassTab(it.display) },
            selectedIndex = selectedSourceFilter.ordinal,
            onSelect = { onSourceFilterChange(ModelSourceFilter.entries[it]) },
            hazeState = hazeState,
            cornerRadius = 12.dp,
            verticalPadding = 8.dp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SizeTier.entries.forEach { tier ->
                val selected = selectedTier == tier
                val chipScale by animateFloatAsState(
                    targetValue = if (selected) 1.06f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = 470f,
                        visibilityThreshold = 0.001f
                    ),
                    label = "model_filter_chip_scale"
                )
                LiquidGlassButton(
                    onClick = { onTierChange(tier) },
                    hazeState = hazeState,
                    modifier = Modifier
                        .height(36.dp)
                        .graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                        },
                    shape = RoundedCornerShape(10.dp),
                    tintColor = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text(
                        tier.display,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun NoModelsMatchCard(
    searchQuery: String,
    selectedTier: SizeTier,
    selectedSourceFilter: ModelSourceFilter = ModelSourceFilter.All,
    onClear: () -> Unit,
    hazeState: HazeState
) {
    val tintColor = MaterialTheme.colorScheme.surfaceContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 90,
                initialScale = 0.91f,
                initialYOffset = 12.dp,
                initialRotationZ = 0.8f
            )
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = tintColor),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "No models match your filter",
                style = MaterialTheme.typography.titleSmall
            )
            val summary = buildString {
                if (selectedSourceFilter != ModelSourceFilter.All) append("source ${selectedSourceFilter.display}")
                if (searchQuery.isNotBlank()) {
                    if (isNotEmpty()) append(" + ")
                    append("search \"$searchQuery\"")
                }
                if (selectedTier != SizeTier.All) {
                    if (isNotEmpty()) append(" + ")
                    append("tier ${selectedTier.display}")
                }
            }
            Text(
                "Nothing found for $summary. Try clearing the filter to see all models.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onClear) {
                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear filter")
            }
        }
    }
}

@Composable
private fun StorageSummaryCard(downloadedModels: List<ModelInfo>, hazeState: HazeState) {
    val storagePaths = downloadedModels.mapNotNull { it.localPath }.distinct().sorted()
    val totalBytes by androidx.compose.runtime.produceState(0L, storagePaths) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            storagePaths.sumOf { path ->
                val file = java.io.File(path)
                when {
                    file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    file.isFile -> file.length()
                    else -> 0L
                }
            }
        }
    }
    val cardTint = MaterialTheme.colorScheme.surfaceContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 60,
                initialYOffset = 20.dp,
                initialXOffset = (-18).dp,
                initialRotationZ = -0.6f
            )
            .animatedGlassHalo(enabled = downloadedModels.isNotEmpty(), shape = MaterialTheme.shapes.medium, alpha = 0.04f, durationMillis = 5_000)
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = cardTint),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Downloaded models",
                    style = MaterialTheme.typography.titleSmall
                )
                if (downloadedModels.isEmpty()) {
                    Text(
                        "No models downloaded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${downloadedModels.size} model${if (downloadedModels.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (downloadedModels.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        formatBytes(totalBytes),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(isCompatible: Boolean, message: String, hazeState: HazeState) {
    val tintColor = if (isCompatible) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val borderAlpha = if (isCompatible) 0.35f else 0.45f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 100,
                initialYOffset = 20.dp,
                initialXOffset = 18.dp,
                initialRotationZ = 0.6f
            )
            .animatedGlassHalo(shape = MaterialTheme.shapes.medium, alpha = if (isCompatible) 0.045f else 0.03f, durationMillis = 4_600)
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 20.dp, tintColor = tintColor, borderAlpha = borderAlpha),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCompatible) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isCompatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DeviceHardwareCard(hazeState: HazeState) {
    val context = LocalContext.current
    var ramInfo by remember { mutableStateOf(DeviceUtils.getRamMemoryInfo(context)) }
    val socInfo = remember { DeviceUtils.getDeviceSoCInfo(context) }
    val cardTint = MaterialTheme.colorScheme.surfaceContainer

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000L)
            ramInfo = DeviceUtils.getRamMemoryInfo(context)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 80,
                initialYOffset = 20.dp,
                initialXOffset = 18.dp,
                initialRotationZ = 0.5f
            )
            .animatedGlassHalo(
                enabled = true,
                shape = MaterialTheme.shapes.medium,
                alpha = 0.035f,
                durationMillis = 4_800
            )
            .glassEffect(
                hazeState = hazeState,
                shape = MaterialTheme.shapes.medium,
                blurRadius = 16.dp,
                tintColor = cardTint
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Device SoC Model",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Processor & Memory",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = socInfo.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (socInfo.details.isNotBlank()) {
                            Text(
                                text = socInfo.details,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.2f GiB free", ramInfo.availableGb),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Live RAM utilization bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { ramInfo.usedPercentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        ramInfo.usedPercentage > 0.88f -> MaterialTheme.colorScheme.error
                        ramInfo.usedPercentage > 0.75f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Available: ${String.format(Locale.US, "%.2f", ramInfo.availableGb)} GiB / Total: ${String.format(Locale.US, "%.1f", ramInfo.totalGb)} GiB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(ramInfo.usedPercentage * 100).toInt()}% used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard(hazeState: HazeState) {
    val cardTint = MaterialTheme.colorScheme.surfaceContainerLow
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 140,
                initialYOffset = 20.dp,
                initialXOffset = (-18).dp,
                initialRotationZ = -0.55f
            )
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = cardTint),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Private offline inference", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Downloads use the network once. Chat and image questions run from local files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun AccessCard(
    token: String,
    onTokenSave: (String) -> Unit,
    onOpenTokenTutorial: () -> Unit,
    hazeState: HazeState
) {
    var tokenDraft by rememberSaveable(token) { mutableStateOf(token) }
    val cardTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val coroutineScope = rememberCoroutineScope()
    val isKeyboardOpen = WindowInsets.isImeVisible
    val tokenBringIntoViewRequester = remember { BringIntoViewRequester() }
    var isTokenFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isKeyboardOpen, isTokenFocused) {
        if (isKeyboardOpen && isTokenFocused) {
            delay(160)
            tokenBringIntoViewRequester.bringIntoView()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 180,
                initialYOffset = 20.dp,
                initialXOffset = 18.dp,
                initialRotationZ = 0.55f
            )
            .animatedGlassHalo(shape = MaterialTheme.shapes.medium, alpha = 0.035f, durationMillis = 5_200)
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = cardTint),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .bringIntoViewRequester(tokenBringIntoViewRequester),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Access", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        isTokenFocused = focusState.isFocused
                        if (focusState.isFocused) {
                            coroutineScope.launch {
                                delay(160)
                                tokenBringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
                singleLine = true,
                label = { Text("Hugging Face read token") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenTokenTutorial) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("How to create token")
                }
                Spacer(modifier = Modifier.width(8.dp))
                LiquidGlassButton(
                    onClick = { onTokenSave(tokenDraft) },
                    enabled = tokenDraft.trim() != token,
                    hazeState = hazeState,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}




@Composable
private fun GlassLabelChip(
    label: String,
    accentColor: Color
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(30.dp)
            .background(accentColor.copy(alpha = 0.12f), shape)
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.32f),
                shape = shape
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(accentColor.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LiquidProgressBar(
    progress: Float
) {
    val shape = RoundedCornerShape(6.dp)
    val targetProgress = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 360f),
        label = "liquid_download_progress"
    )
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(colors.surfaceContainerHighest)
            .border(
                width = 1.dp,
                color = colors.outlineVariant.copy(alpha = 0.85f),
                shape = shape
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.82f),
                            colors.primary
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelItem(
    model: ModelInfo,
    isSelected: Boolean,
    onOpenPage: (() -> Unit)?,
    onAction: () -> Unit,
    onPause: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onUnsafeDownload: (() -> Unit)? = null,
    onUnsafeTry: (() -> Unit)? = null,
    onRelinkBundle: ((LocalModelImporter.ImportResult) -> Result<Unit>)? = null,
    isHfTokenBlank: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    enableDynamicThemes: Boolean = true,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    isRemoving: Boolean = false,
    isCancelling: Boolean = false,
    isUnloading: Boolean = false,
    onUnload: (() -> Unit)? = null
) {
    val isFuturePlaceholder = model.isFuturePlaceholder
    val canTryAnyway = model.status == ModelStatus.Blocked &&
        (model.localPath != null || model.isStorageImport) &&
        model.canTryAnyway &&
        onUnsafeTry != null
    val modelFamilyAccent = remember(model.id, model.name, enableDynamicThemes) {
        if (enableDynamicThemes) ModelTheme.getAccentColor(model.id, model.name) else Color(0xFF3B82F6)
    }
    val targetStatusColor = when (model.status) {
        ModelStatus.Available -> modelFamilyAccent
        ModelStatus.Downloading -> modelFamilyAccent
        ModelStatus.Paused -> MaterialTheme.colorScheme.secondary
        ModelStatus.Failed -> MaterialTheme.colorScheme.error
        ModelStatus.Blocked -> MaterialTheme.colorScheme.error
        ModelStatus.NeedsConversion -> MaterialTheme.colorScheme.outline
        ModelStatus.ComingSoon -> MaterialTheme.colorScheme.outline
        ModelStatus.NotDownloaded -> modelFamilyAccent.copy(alpha = 0.85f)
    }
    val statusColor by animateColorAsState(
        targetValue = targetStatusColor,
        animationSpec = tween(240),
        label = "model_status_color"
    )

    val itemShape = MaterialTheme.shapes.large
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var relinkError by remember { mutableStateOf<String?>(null) }
    val relinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty() && onRelinkBundle != null) {
            coroutineScope.launch {
                try {
                    val bundleResult = withContext(Dispatchers.IO) {
                        LocalModelImporter.importFromUris(context, uris)
                    }
                    bundleResult.onSuccess { importResult ->
                        val relinkResult = onRelinkBundle(importResult)
                        relinkResult.onSuccess {
                            relinkError = null
                        }.onFailure { error ->
                            Log.e("ModelItem", "Failed to relink bundle: ${error.message}")
                            relinkError = error.message ?: "Failed to relink model files"
                        }
                    }.onFailure { error ->
                        Log.e("ModelItem", "Failed to relink bundle: ${error.message}")
                        relinkError = error.message ?: "Failed to inspect model files"
                    }
                } catch (e: Exception) {
                    Log.e("ModelItem", "Exception relinking bundle", e)
                    relinkError = e.message ?: "Failed to relink model files"
                }
            }
        }
    }
    val targetTintColor = if (enableDynamicThemes) {
        if (isSelected) {
            modelFamilyAccent.copy(alpha = 0.24f)
        } else {
            modelFamilyAccent.copy(alpha = 0.09f)
        }
    } else {
        if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    }
    val tintColor by animateColorAsState(
        targetValue = targetTintColor,
        animationSpec = tween(260),
        label = "model_card_tint"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.68f else if (enableDynamicThemes) 0.36f else 0.24f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 430f),
        label = "model_card_border"
    )
    val selectionMotion by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = 400f,
            visibilityThreshold = 0.001f
        ),
        label = "model_card_selection_motion"
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = 1f + selectionMotion * 0.012f
                scaleY = 1f + selectionMotion * 0.012f
                translationY = -selectionMotion * 2.5f * density
                rotationZ = selectionMotion * -0.18f
            }
            .jellyOnTouch(sensitivity = 1.45f)
            .glassEffect(
                hazeState = hazeState,
                shape = itemShape,
                blurRadius = 16.dp,
                tintColor = tintColor,
                borderAlpha = borderAlpha,
                borderColor = if (enableDynamicThemes) modelFamilyAccent else null
            )
            .animatedGlassHalo(
                enabled = isSelected,
                shape = itemShape,
                alpha = 0.08f,
                durationMillis = 4_400,
                accentColor = if (enableDynamicThemes) modelFamilyAccent else null
            ),
        shape = itemShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                androidx.compose.animation.Crossfade(
                    targetState = model.status,
                    animationSpec = tween(210),
                    label = "model_status_icon"
                ) { visibleStatus ->
                    Icon(
                        imageVector = when (visibleStatus) {
                            ModelStatus.Available -> Icons.Default.CheckCircle
                            ModelStatus.Blocked -> Icons.Default.Info
                            else -> Icons.Default.Download
                        },
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                scaleX = 1f + selectionMotion * 0.12f
                                scaleY = 1f + selectionMotion * 0.12f
                                rotationZ = selectionMotion * 8f
                            },
                        tint = statusColor
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${model.taskLabel}  •  ${model.displaySize}  •  ${model.displayBackend}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        model.deviceTarget,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassLabelChip(
                    label = if (isSelected) "Selected" else if (model.hasMissingFiles) "Missing files" else model.status.label,
                    accentColor = statusColor
                )
                GlassLabelChip(
                    label = model.contextLengthLabel,
                    accentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.isRecommended) {
                    GlassLabelChip(
                        label = "Recommended",
                        accentColor = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (model.requiresHuggingFaceToken) {
                    GlassLabelChip(
                        label = "Token needed",
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                }
                if (model.isCustom) {
                    GlassLabelChip(
                        label = "Imported",
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                }
                if (model.isStorageImport) {
                    GlassLabelChip(
                        label = "Phone Storage",
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                }
                if (model.isMultimodal) {
                    GlassLabelChip(
                        label = "Multimodal",
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                }
                if (model.isSplit) {
                    GlassLabelChip(
                        label = "Split GGUF",
                        accentColor = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Text(
                model.displayDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (
                model.status == ModelStatus.Downloading ||
                ((model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) && model.downloadedBytes > 0L)
            ) {
                val cardAccent = if (model.status == ModelStatus.Failed) MaterialTheme.colorScheme.error else modelFamilyAccent
                val cardTitle = when {
                    model.hasMissingFiles -> "Missing files"
                    model.status == ModelStatus.Paused -> "Paused — download kept"
                    model.status == ModelStatus.Downloading -> "Downloading Model…"
                    model.status == ModelStatus.Failed -> model.failureLabel
                    else -> "Download Status"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = cardAccent.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, cardAccent.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header row with pulse badge & speed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(cardAccent, RoundedCornerShape(4.dp))
                                )
                                Text(
                                    text = cardTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = cardAccent
                                )
                            }
                            if (model.status == ModelStatus.Downloading && model.bytesPerSecond > 0L) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = modelFamilyAccent.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "⚡ ${formatBytes(model.bytesPerSecond)}/s",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = modelFamilyAccent
                                    )
                                }
                            }
                        }

                        // Progress bar with glow
                        LiquidProgressBar(progress = model.progress)

                        // Stats footer row: Size + ETA
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (model.totalBytes > 0L) {
                                    "${(model.progress * 100).toInt()}% • ${formatBytes(model.downloadedBytes)} / ${formatBytes(model.totalBytes)}"
                                } else {
                                    "${formatBytes(model.downloadedBytes)} downloaded"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (model.status == ModelStatus.Downloading && model.etaString.isNotBlank()) {
                                Text(
                                    text = "⏱️ ETA: ${model.etaString}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            val displayError = relinkError ?: model.errorMessage?.takeIf { it != "Paused — download kept" }
            displayError?.let { error ->
                val classified = remember(error, model.loadRestriction?.category) {
                    ErrorClassifier.classify(error, model.loadRestriction?.category)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = classified?.category?.icon ?: Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = classified?.headline ?: "Error",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            classified?.category?.categoryName?.let { catName ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = catName,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        ClickableUrlText(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth(),
                            onOpenUrl = { url -> openUrl(context, url) }
                        )

                        classified?.suggestion?.let { tip ->
                            Text(
                                text = "💡 $tip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (classified?.isOpenSettingsAction == true && onOpenSettings != null) {
                                OutlinedButton(
                                    onClick = { onOpenSettings.invoke() },
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open Settings to add token", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (classified?.isSendCrashReportAction == true) {
                                OutlinedButton(
                                    onClick = {
                                        val fullReport = CrashReportManager.recordModelCrash(
                                            context = context,
                                            modelId = model.id,
                                            modelName = model.name,
                                            errorMessage = error,
                                            runtimeInfo = "Status: ${model.status} | File: ${model.fileName} | Size: ${model.size}"
                                        )
                                        CrashReportManager.sendToGitHub(
                                            context = context,
                                            reportText = fullReport.details,
                                            titleHint = "[Model Crash] ${model.name}"
                                        )
                                    },
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Report Crash to Repo", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Delete button — shown for models that have local files, storage imports, custom models, or paused/failed downloads
                val isPaused = model.status == ModelStatus.Paused
                val canDelete = onDelete != null && (
                    model.localPath != null ||
                    model.isStorageImport ||
                    model.isCustom ||
                    isPaused ||
                    (model.status == ModelStatus.Failed && model.downloadedBytes > 0L)
                ) && model.status in listOf(
                    ModelStatus.Available, ModelStatus.Paused, ModelStatus.Failed, ModelStatus.Blocked
                )
                var showDeleteConfirm by remember { mutableStateOf(false) }
                val isCustomWithoutFile = (model.isCustom && model.localPath == null) || isPaused

                if (showDeleteConfirm) {
                    DeleteModelGlassDialog(
                        modelName = model.name,
                        isStorageImport = model.isStorageImport,
                        isCustomWithoutFile = isCustomWithoutFile,
                        isPaused = isPaused,
                        hazeState = hazeState,
                        onDismissRequest = { showDeleteConfirm = false },
                        onConfirm = {
                            showDeleteConfirm = false
                            onDelete?.invoke()
                        }
                    )
                }

                if (isRemoving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(40.dp)
                            .padding(horizontal = 10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Removing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (canDelete) {
                    LiquidGlassButton(
                        onClick = { showDeleteConfirm = true },
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isCustomWithoutFile) "Remove" else "Delete")
                    }
                }

                if (model.isStorageImport && onRelinkBundle != null) {
                    LiquidGlassButton(
                        onClick = { relinkLauncher.launch(arrayOf("*/*")) },
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Relink files")
                    }
                }

                var showBenchDialog by remember { mutableStateOf(false) }
                var benchAudioInput by remember { mutableStateOf(false) }
                var isRunningBench by remember { mutableStateOf(false) }
                var isStoppingBench by remember { mutableStateOf(false) }
                var benchProgress by remember { mutableStateOf<BenchmarkProgress?>(null) }
                var benchResult by remember { mutableStateOf<BenchmarkResult?>(null) }
                var benchError by remember { mutableStateOf<String?>(null) }
                var benchJob by remember { mutableStateOf<Job?>(null) }
                val benchScope = rememberCoroutineScope()

                if (showBenchDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!isRunningBench && !isStoppingBench) {
                                showBenchDialog = false
                            }
                        },
                        title = {
                            val isGguf = (model.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(model.fileName)) && !model.supportsVision && !model.supportsAudio
                            Text(if (isGguf) "⚡ CPU / GPU Performance Check" else "⚡ Performance Benchmark")
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Model: ${model.name}", style = MaterialTheme.typography.titleSmall)
                                Text("Backend: ${model.displayBackend}", style = MaterialTheme.typography.bodySmall)
                                if (model.supportsVision && model.supportsAudio && !isRunningBench && !isStoppingBench) {
                                    Row {
                                        TextButton(onClick = { benchAudioInput = false; benchResult = null }) { Text(if (!benchAudioInput) "Image ✓" else "Image") }
                                        TextButton(onClick = { benchAudioInput = true; benchResult = null }) { Text(if (benchAudioInput) "Audio ✓" else "Audio") }
                                    }
                                }

                                if (isRunningBench || isStoppingBench) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        LinearProgressIndicator(
                                            progress = {
                                                val p = benchProgress
                                                if (p != null && p.totalSteps > 0) p.currentStep.toFloat() / p.totalSteps.toFloat() else 0f
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            if (isStoppingBench) "Stopping benchmark..." else (benchProgress?.phase ?: "Starting benchmark..."),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (!benchProgress?.detail.isNullOrBlank()) {
                                            Text(
                                                benchProgress!!.detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else if (benchError != null) {
                                    Text(
                                        "❌ Benchmark Error: $benchError",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else if (benchResult != null) {
                                    val r = benchResult!!
                                    val isGguf = (model.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(model.fileName)) && !model.supportsVision && !model.supportsAudio
                                    Text(
                                        if (isGguf) {
                                            if (r.qualifiesVulkan) "✅ Vulkan Qualified" else "ℹ️ CPU Preferred"
                                        } else {
                                            "⚡ Benchmark Completed"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isGguf && r.qualifiesVulkan) MaterialTheme.colorScheme.primary else if (!isGguf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(r.summary, style = MaterialTheme.typography.bodySmall)
                                    HorizontalDivider()
                                    if (r.taskLatencyMedianMs != null) {
                                        Text("• Warm task latency: ${r.taskLatencyMedianMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                                        Text("• Cold load + task: ${r.cpuColdLoadFtMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                                    } else if (isGguf) {
                                        Text("• Warm TTFT: CPU ${r.cpuWarmTtftMedianMs.toInt()} ms vs GPU ${r.vulkanWarmTtftMedianMs.toInt()} ms (${String.format(Locale.US, "%.1f", r.warmTtftImprovementPercent)}% diff)", style = MaterialTheme.typography.bodySmall)
                                        Text("• Cold Load + First Token: CPU ${r.cpuColdLoadFtMs.toInt()} ms vs GPU ${r.vulkanColdLoadFtMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                                        Text("• Sustained Decode: CPU ${String.format(Locale.US, "%.1f", r.cpuDecodeTokPerSec)} t/s vs GPU ${String.format(Locale.US, "%.1f", r.vulkanDecodeTokPerSec)} t/s", style = MaterialTheme.typography.bodySmall)
                                        Text("• Frame Time p95: CPU ${String.format(Locale.US, "%.1f", r.cpuFrameP95Ms)} ms vs GPU ${String.format(Locale.US, "%.1f", r.vulkanFrameP95Ms)} ms", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Text("• Warm TTFT: ${r.cpuWarmTtftMedianMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                                        Text("• Cold Load + First Token: ${r.cpuColdLoadFtMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                                        Text("• Sustained Decode: ${String.format(Locale.US, "%.1f", r.cpuDecodeTokPerSec)} t/s", style = MaterialTheme.typography.bodySmall)
                                        if (r.uiFramesMeasured) Text("• Frame Time p95: ${String.format(Locale.US, "%.1f", r.cpuFrameP95Ms)} ms", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (r.uiFramesMeasured) Text("• Max UI Render Stall: ${r.maxRenderStallMs} ms", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    val isGguf = (model.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(model.fileName)) && !model.supportsVision && !model.supportsAudio
                                    if (model.supportsVision || model.supportsAudio || model.bundle?.capabilities?.task == com.shounak.localmeshai.models.ModelTask.ImageGeneration) {
                                        Text("Runs one cold and three warm tasks with synthetic inputs. Measures completion latency, not output quality.", style = MaterialTheme.typography.bodySmall)
                                    } else if (isGguf && (model.localPath != null || model.bundle != null)) {
                                        Text(
                                            "Runs 5 cold samples and 20 warm samples across CPU and Vulkan to verify if Vulkan improves latency by >= 10% without frame stalls or regressions.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else if (model.localPath != null || model.bundle != null) {
                                        Text(
                                            "Runs cold load and warm prompt evaluations to measure Time to First Token (TTFT), sustained decode throughput (t/s), and UI frame smoothness.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        Text(
                                            "Status: ${model.status}\nContext: ${model.contextWindowTokens ?: 2048} tokens",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            if (isRunningBench || isStoppingBench) {
                                TextButton(
                                    enabled = !isStoppingBench,
                                    onClick = {
                                        isStoppingBench = true
                                        benchJob?.cancel()
                                    }
                                ) {
                                    Text(if (isStoppingBench) "Stopping..." else "Cancel")
                                }
                            } else {
                                if (benchResult == null && (model.localPath != null || model.bundle != null)) {
                                    TextButton(
                                        enabled = !isRunningBench && !isStoppingBench && benchJob?.isActive != true,
                                        onClick = {
                                            isRunningBench = true
                                            benchResult = null
                                            benchError = null
                                            benchJob = benchScope.launch {
                                                try {
                                                    val isGguf = (model.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(model.fileName)) && !model.supportsVision && !model.supportsAudio
                                                    val res = if (model.supportsVision || model.supportsAudio || model.bundle?.capabilities?.task == com.shounak.localmeshai.models.ModelTask.ImageGeneration) {
                                                        com.shounak.localmeshai.ai.MediaBenchmarkRunner.run(context, model, { p -> benchProgress = p }, useAudio = benchAudioInput)
                                                    } else if (isGguf) {
                                                        if (model.bundle != null) {
                                                            GgufBenchmarkRunner.runBenchmark(
                                                                context = context,
                                                                bundle = model.bundle,
                                                                onProgress = { p -> benchProgress = p }
                                                            )
                                                        } else {
                                                            val localPath = model.localPath ?: return@launch
                                                            val sha = GgufGpuValidator.resolveModelSha256(
                                                                modelPath = localPath,
                                                                modelId = model.id,
                                                                context = context
                                                            )
                                                            GgufBenchmarkRunner.runBenchmark(
                                                                context = context,
                                                                modelPath = localPath,
                                                                modelSha256 = sha,
                                                                onProgress = { p -> benchProgress = p }
                                                            )
                                                        }
                                                    } else {
                                                        NonGgufBenchmarkRunner.runBenchmark(
                                                            context = context,
                                                            modelPath = model.localPath ?: model.bundle?.mainArtifacts?.firstOrNull()?.source?.location ?: "",
                                                            modelId = model.id,
                                                            onProgress = { p -> benchProgress = p }
                                                        )
                                                    }
                                                    benchResult = res
                                                } catch (t: Throwable) {
                                                    if (t !is CancellationException) {
                                                        Log.e("ModelManagerScreen", "Benchmark failed", t)
                                                        benchError = t.message ?: "Benchmark failed: ${t.javaClass.simpleName}"
                                                    }
                                                } finally {
                                                    isRunningBench = false
                                                    isStoppingBench = false
                                                    benchProgress = null
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Run Check")
                                    }
                                } else {
                                    TextButton(onClick = { showBenchDialog = false }) {
                                        Text("Close")
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            if (!isRunningBench && !isStoppingBench && benchResult != null) {
                                TextButton(
                                    onClick = {
                                        benchResult = null
                                        benchError = null
                                    }
                                ) {
                                    Text("Re-test")
                                }
                            }
                        }
                    )
                }

                if (model.status == ModelStatus.Available) {
                    LiquidGlassButton(
                        enabled = !isRunningBench && !isStoppingBench,
                        onClick = {
                            benchError = null
                            showBenchDialog = true
                        },
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bench")
                    }
                }

                var showGgufDiagDialog by remember { mutableStateOf(false) }
                var isRunningGgufDiag by remember { mutableStateOf(false) }
                var isStoppingGgufDiag by remember { mutableStateOf(false) }
                var ggufDiagReport by remember { mutableStateOf<GgufDiagnosticReport?>(null) }
                var ggufDiagError by remember { mutableStateOf<String?>(null) }
                var ggufDiagJob by remember { mutableStateOf<Job?>(null) }
                val diagScope = rememberCoroutineScope()

                if (showGgufDiagDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!isRunningGgufDiag && !isStoppingGgufDiag) showGgufDiagDialog = false },
                        title = { Text("🔬 GGUF Native Diagnostics") },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isRunningGgufDiag || isStoppingGgufDiag) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Text(
                                            if (isStoppingGgufDiag) "Stopping native diagnostics..." else "Running native GGUF diagnostics (inspect → load → prompt decode → token generation → free)...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                } else if (ggufDiagError != null) {
                                    Text(
                                        "❌ Diagnostic Error: $ggufDiagError",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else if (ggufDiagReport != null) {
                                    val rep = ggufDiagReport!!
                                    Text(
                                        rep.toMarkdown(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            if (isRunningGgufDiag || isStoppingGgufDiag) {
                                TextButton(
                                    enabled = !isStoppingGgufDiag,
                                    onClick = {
                                        isStoppingGgufDiag = true
                                        ggufDiagJob?.cancel()
                                    }
                                ) {
                                    Text(if (isStoppingGgufDiag) "Stopping..." else "Cancel")
                                }
                            } else {
                                TextButton(onClick = { showGgufDiagDialog = false }) {
                                    Text("Close")
                                }
                            }
                        },
                        dismissButton = {
                            if (!isRunningGgufDiag && !isStoppingGgufDiag && ggufDiagReport != null) {
                                @Suppress("DEPRECATION")
                                val clipboard = LocalClipboardManager.current
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(ggufDiagReport!!.toMarkdown()))
                                }) {
                                    Text("Copy Report")
                                }
                            }
                        }
                    )
                }

                val isGgufForDiag = model.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(model.fileName)
                if (model.status == ModelStatus.Available && (model.localPath != null || model.bundle != null) && isGgufForDiag) {
                    LiquidGlassButton(
                        enabled = !isRunningGgufDiag && !isStoppingGgufDiag,
                        onClick = {
                            showGgufDiagDialog = true
                            isRunningGgufDiag = true
                            ggufDiagReport = null
                            ggufDiagError = null
                            ggufDiagJob = diagScope.launch {
                                try {
                                    val rep = if (model.bundle != null) {
                                        GgufDiagnosticRunner.runDiagnostics(context, model.bundle)
                                    } else {
                                        GgufDiagnosticRunner.runDiagnostics(context, model.localPath!!)
                                    }
                                    ggufDiagReport = rep
                                } catch (t: Throwable) {
                                    if (t !is CancellationException) {
                                        Log.e("ModelManagerScreen", "Diagnostics failed", t)
                                        ggufDiagError = t.message ?: "Diagnostic failed: ${t.javaClass.simpleName}"
                                    }
                                } finally {
                                    isRunningGgufDiag = false
                                    isStoppingGgufDiag = false
                                }
                            }
                        },
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Diag")
                    }
                }

                if (onOpenPage != null) {
                    LiquidGlassButton(
                        onClick = onOpenPage,
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Page")
                    }
                }

                if (
                    model.status == ModelStatus.Downloading &&
                    onCancel != null
                ) {
                    var localCancelling by remember { mutableStateOf(false) }
                    val activeCancelling = isCancelling || localCancelling
                    LiquidGlassButton(
                        onClick = {
                            localCancelling = true
                            onCancel()
                        },
                        enabled = !activeCancelling,
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        if (activeCancelling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancelling…")
                        } else {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel download")
                        }
                    }
                }

                var showRiskyTryConfirm by remember { mutableStateOf(false) }
                if (showRiskyTryConfirm) {
                    RiskyModelActionDialog(
                        modelName = model.name,
                        title = "Try anyway?",
                        message = "This can crash or close the app during native model initialization. A successful retry clears a previous crash block on this device; another crash keeps it blocked.",
                        confirmLabel = "Try Anyway",
                        hazeState = hazeState,
                        onDismissRequest = { showRiskyTryConfirm = false },
                        onConfirm = {
                            showRiskyTryConfirm = false
                            onUnsafeTry?.invoke()
                        }
                    )
                }

                var showHfTokenPromptDialog by remember { mutableStateOf(false) }
                if (showHfTokenPromptDialog) {
                    HfTokenRequiredDialog(
                        modelName = model.name,
                        hazeState = hazeState,
                        onDismissRequest = { showHfTokenPromptDialog = false },
                        onOpenSettings = { onOpenSettings?.invoke() }
                    )
                }

                val isButtonEnabled = when {
                    isUnloading -> false
                    isSelected -> onUnload != null
                    isFuturePlaceholder -> false
                    model.status == ModelStatus.Blocked -> canTryAnyway
                    else -> true
                }

                LiquidGlassButton(
                    onClick = {
                        when {
                            isSelected -> onUnload?.invoke()
                            model.status == ModelStatus.Downloading -> onPause()
                            canTryAnyway -> showRiskyTryConfirm = true
                            model.requiresHuggingFaceToken && isHfTokenBlank && model.localPath == null -> showHfTokenPromptDialog = true
                            else -> onAction()
                        }
                    },
                    enabled = isButtonEnabled,
                    hazeState = hazeState,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    tintColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
                    },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = when {
                            isUnloading -> Icons.Default.HourglassTop
                            isSelected -> Icons.Default.Eject
                            model.status == ModelStatus.Available -> Icons.Default.CheckCircle
                            model.status == ModelStatus.Downloading -> Icons.Default.Pause
                            model.status == ModelStatus.Blocked -> if (canTryAnyway) Icons.Default.Info else Icons.Default.Download
                            else -> Icons.Default.Download
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            isUnloading -> "Ejecting…"
                            isSelected -> "Eject"
                            model.status == ModelStatus.Downloading -> "Pause"
                            model.hasMissingFiles -> "Fetch files"
                            model.status == ModelStatus.Paused -> "Resume"
                            model.status == ModelStatus.Available -> "Select"
                            model.status == ModelStatus.Failed -> "Retry"
                            canTryAnyway -> "Try Anyway"
                            model.status == ModelStatus.Blocked -> "Blocked"
                            isFuturePlaceholder && model.status == ModelStatus.NeedsConversion -> "Future"
                            isFuturePlaceholder && model.status == ModelStatus.ComingSoon -> "Soon"
                            else -> "Download"
                        }
                    )
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ClickableUrlText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedText = remember(text, color, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            UrlRegex.findAll(text).forEach { match ->
                val rawUrl = match.value
                val url = rawUrl.trimEnd('.', ',', ';', '!', '?')
                val trailingText = rawUrl.substring(url.length)
                append(text.substring(cursor, match.range.first))
                pushStringAnnotation(tag = UrlAnnotationTag, annotation = url)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(url)
                }
                pop()
                append(trailingText)
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }

    ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(color = color),
        onClick = { offset ->
            annotatedText
                .getStringAnnotations(tag = UrlAnnotationTag, start = offset, end = offset)
                .firstOrNull()
                ?.let { annotation -> onOpenUrl(annotation.item) }
        }
    )
}

@Composable
private fun DeleteModelGlassDialog(
    modelName: String,
    isStorageImport: Boolean = false,
    isCustomWithoutFile: Boolean = false,
    isPaused: Boolean = false,
    hazeState: HazeState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassDispersionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .widthIn(max = 520.dp)
                    .fluidReveal(initialScale = 0.88f, initialYOffset = 16.dp)
                    .animatedGlassHalo(alpha = 0.06f, durationMillis = 4_800),
                hazeState = hazeState,
                cornerRadius = 20.dp,
                blurRadius = 34.dp,
                refractionStrength = 0.18f,
                dispersionAmount = 0.034f,
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                borderAlpha = 0.50f,
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 14.dp),
                animatedCaustics = true
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when {
                            isPaused -> "Remove downloaded data?"
                            isCustomWithoutFile -> "Remove imported model?"
                            isStorageImport -> "Unlink model?"
                            else -> "Delete model?"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isPaused -> "Partial download for \"$modelName\" will be removed. You can start the download again at any time."
                            isCustomWithoutFile -> "\"$modelName\" will be removed from Solus. Original files will not be modified, and you can re-add or download it later."
                            isStorageImport -> "\"$modelName\" will be unlinked from Solus. Original files on your device storage will not be modified or deleted."
                            else -> "\"$modelName\" will be deleted from device storage. You can re-download it later."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidGlassButton(
                            onClick = onDismissRequest,
                            hazeState = hazeState,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        LiquidGlassButton(
                            onClick = onConfirm,
                            hazeState = hazeState,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.30f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                when {
                                    isPaused || isCustomWithoutFile -> "Remove"
                                    isStorageImport -> "Unlink"
                                    else -> "Delete"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskyModelActionDialog(
    modelName: String,
    title: String,
    message: String,
    confirmLabel: String,
    hazeState: HazeState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassDispersionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .widthIn(max = 540.dp)
                    .fluidReveal(initialScale = 0.88f, initialYOffset = 16.dp)
                    .animatedGlassHalo(alpha = 0.07f, durationMillis = 4_800),
                hazeState = hazeState,
                cornerRadius = 20.dp,
                blurRadius = 34.dp,
                refractionStrength = 0.18f,
                dispersionAmount = 0.034f,
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                borderAlpha = 0.50f,
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 14.dp),
                animatedCaustics = true
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "\"$modelName\" is outside the safe device profile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LiquidGlassButton(
                            onClick = onDismissRequest,
                            hazeState = hazeState,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Text("Cancel")
                        }
                        LiquidGlassButton(
                            onClick = onConfirm,
                            hazeState = hazeState,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.30f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HfTokenRequiredDialog(
    modelName: String,
    hazeState: HazeState,
    onDismissRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Token Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Hugging Face Access Token needed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "$modelName requires a Hugging Face Access Token to download.\n\nPlease open Settings (⚙️) → Hugging Face Access Token to paste your read token.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("Cancel")
                    }

                    LiquidGlassButton(
                        onClick = {
                            onDismissRequest()
                            onOpenSettings()
                        },
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Settings")
                    }
                }
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.brave.browser")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to default browser if Brave is not installed
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}

private val UrlRegex = Regex("""https?://[^\s)>\]]+""")
private const val UrlAnnotationTag = "URL"

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

private const val HF_TOKEN_TUTORIAL_URL = "https://youtu.be/il58zFv0tmU?si=5o_gE8p-JqwWkekY"

package com.shounak.localmeshai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.ClickableText
import com.shounak.localmeshai.utils.glassmorphic
import com.shounak.localmeshai.utils.glassEffect
import com.shounak.localmeshai.utils.GlassDispersionCard
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.animatedGlassHalo
import com.shounak.localmeshai.utils.fluidReveal
import com.shounak.localmeshai.utils.jellyOnTouch
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.DeviceUtils
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
    Small("0B–2B"),
    Medium("3B–5B"),
    Large("7B–10B"),
    XLarge("12B–15B"),
    Huge("20B–35B");

    fun contains(paramsB: Float?): Boolean {
        if (this == All) return true
        val p = paramsB ?: return false
        return when (this) {
            Small -> p < 3f
            Medium -> p in 3f..6.99f
            Large -> p in 7f..11.99f
            XLarge -> p in 12f..19.99f
            Huge -> p >= 20f
            All -> true
        }
    }
}

/**
 * Best-effort parameter count in billions. Tries the model name first
 * (e.g. "Qwen 3.5 0.8B", "Gemma 4 E2B", "InternLM3-8B", "Qwen3-30B-A3B"),
 * then falls back to the first GB/MB figure in the size string.
 * Returns null when the model has no size signal (e.g. user-added custom
 * models with no size set) — those are hidden by non-`All` tier filters.
 */
private fun ModelInfo.parametersBillions(): Float? {
    val nameMatch = Regex("""(\d+(?:\.\d+)?)B""").find(name)
    if (nameMatch != null) {
        return nameMatch.groupValues[1].toFloatOrNull()
    }
    val sizeMatch = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB)""").find(size)
    if (sizeMatch != null) {
        val number = sizeMatch.groupValues[1].toFloatOrNull() ?: return null
        val unit = sizeMatch.groupValues[2]
        return if (unit == "MB") number / 1024f else number
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

private fun ModelInfo.isPinnedDownloadCard(): Boolean {
    return when (status) {
        ModelStatus.Downloading, ModelStatus.Paused -> true
        ModelStatus.Available -> true
        ModelStatus.Blocked -> isDownloadedBlockedLiteRtLm()
        else -> false
    }
}

private fun ModelInfo.isDownloadedBlockedLiteRtLm(): Boolean {
    return status == ModelStatus.Blocked &&
        localPath != null &&
        (fileName.endsWith(".litertlm", ignoreCase = true) ||
            localPath.endsWith(".litertlm", ignoreCase = true))
}

private fun ModelInfo.downloadedSectionSortRank(): Int {
    return when (status) {
        ModelStatus.Downloading -> 0
        ModelStatus.Paused -> 1
        ModelStatus.Available -> 2
        ModelStatus.Blocked -> 3
        else -> 4
    }
}

/**
 * Combined search + tier filter used to scope which models show up
 * on the Models tab.
 */
private fun ModelInfo.matches(query: String, tier: SizeTier): Boolean {
    if (!tier.contains(parametersBillions())) return false
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return name.lowercase().contains(q) ||
        description.lowercase().contains(q) ||
        id.lowercase().contains(q)
}

@Composable
fun ModelManagerScreen(mainViewModel: MainViewModel = viewModel(), hazeState: HazeState) {
    val context = LocalContext.current
    val (isCompatible, compatibilityMessage) = remember { DeviceUtils.isDeviceCompatible(context) }
    val selectedTextModel by mainViewModel.selectedTextModelPath.collectAsState()
    val selectedVisionModel by mainViewModel.selectedVisionModelPath.collectAsState()
    val huggingFaceToken by mainViewModel.huggingFaceToken.collectAsState()

    // Search + tier filter state. rememberSaveable so they survive config
    // changes (rotation, dark mode toggle, etc.).
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTier by rememberSaveable { mutableStateOf(SizeTier.All) }

    val allTextModels = mainViewModel.availableModels.filter { it.type == ModelType.Text }
    val allVisionModels = mainViewModel.availableModels.filter { it.type == ModelType.Vision }
    val textModels = allTextModels.filter { it.matches(searchQuery, selectedTier) }
    val visionModels = allVisionModels.filter { it.matches(searchQuery, selectedTier) }

    val filterActive = searchQuery.isNotBlank() || selectedTier != SizeTier.All
    val hasMatches = (textModels + visionModels).isNotEmpty()
    val showEmpty = filterActive && !hasMatches

    // Active downloads and local files are pinned to the top so progress/status
    // does not jump around between catalog sections.
    val downloadedModels = (textModels + visionModels)
        .filter { it.isPinnedDownloadCard() }
        .sortedBy { it.downloadedSectionSortRank() }
    val downloadedIds = downloadedModels.map { it.id }.toSet()

    val readyTextModels = textModels.filter {
        it.id !in downloadedIds &&
            it.status != ModelStatus.NeedsConversion &&
            it.status != ModelStatus.ComingSoon &&
            it.status != ModelStatus.Blocked &&
            !it.requiresHuggingFaceToken
    }
    val recommendedTextModels = readyTextModels.filter { it.isRecommended }
    val standardTextModels = readyTextModels.filterNot { it.isRecommended }
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
    val readyVisionModels = visionModels.filter {
        it.id !in downloadedIds &&
            it.status != ModelStatus.NeedsConversion &&
            it.status != ModelStatus.ComingSoon &&
            it.status != ModelStatus.Blocked &&
            !it.requiresHuggingFaceToken
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DeviceCard(isCompatible = isCompatible, message = compatibilityMessage, hazeState = hazeState)
        }
        item {
            // Storage usage summary
            val downloadedModels = mainViewModel.availableModels.filter {
                it.localPath != null &&
                    (it.status == ModelStatus.Available ||
                        it.status == ModelStatus.Blocked ||
                        it.status == ModelStatus.Failed)
            }
            StorageSummaryCard(downloadedModels = downloadedModels, hazeState = hazeState)
        }

        item {
            AccessCard(
                token = huggingFaceToken,
                onTokenSave = mainViewModel::setHuggingFaceToken,
                onOpenTokenTutorial = { openUrl(context, HF_TOKEN_TUTORIAL_URL) },
                hazeState = hazeState
            )
        }

        item {
            PrivacyCard(hazeState = hazeState)
        }

        item {
            SearchAndFilterRow(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                selectedTier = selectedTier,
                onTierChange = { selectedTier = it },
                hazeState = hazeState
            )
        }

        if (showEmpty) {
            item {
                NoModelsMatchCard(
                    searchQuery = searchQuery,
                    selectedTier = selectedTier,
                    onClear = {
                        searchQuery = ""
                        selectedTier = SizeTier.All
                    },
                    hazeState = hazeState
                )
            }
        }

        if (downloadedModels.isNotEmpty()) {
            item {
                Text(
                    "Downloaded models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            itemsIndexed(downloadedModels, key = { _, model -> model.id }) { index, model ->
                val isSelected = model.localPath != null && when (model.type) {
                    ModelType.Text -> selectedTextModel == model.localPath
                    ModelType.Vision -> selectedVisionModel == model.localPath
                }
                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        if (model.status == ModelStatus.Paused) {
                            mainViewModel.resumeDownload(model.id)
                        } else if (model.localPath != null) {
                            when (model.type) {
                                ModelType.Text -> mainViewModel.selectTextModel(model.localPath)
                                ModelType.Vision -> mainViewModel.selectVisionModel(model.localPath)
                            }
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    onUnsafeDownload = { mainViewModel.startDownloadAnyway(model.id) },
                    onUnsafeTry = { mainViewModel.tryModelAnyway(model.id) },
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (recommendedTextModels.isNotEmpty()) {
            item {
                Text(
                    "Recommended downloads",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(recommendedTextModels, key = { _, model -> model.id }) { index, model ->
                val isSelected = (model.localPath != null) &&
                    selectedTextModel == model.localPath

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    onOpenPage = model.modelPageUrl?.let { url ->
                        { openUrl(context, url) }
                    },
                    onAction = {
                        if (model.status == ModelStatus.Available && model.localPath != null) {
                            if (model.type == ModelType.Text) {
                                mainViewModel.selectTextModel(model.localPath)
                            }
                        } else if (model.status == ModelStatus.Paused) {
                            mainViewModel.resumeDownload(model.id)
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (standardTextModels.isNotEmpty()) {
            item {
                Text(
                    "Text models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(standardTextModels, key = { _, model -> model.id }) { index, model ->
                val isSelected = (model.localPath != null) &&
                    selectedTextModel == model.localPath

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    onOpenPage = model.modelPageUrl?.let { url ->
                        { openUrl(context, url) }
                    },
                    onAction = {
                        if (model.status == ModelStatus.Available && model.localPath != null) {
                            if (model.type == ModelType.Text) {
                                mainViewModel.selectTextModel(model.localPath)
                            }
                        } else if (model.status == ModelStatus.Paused) {
                            mainViewModel.resumeDownload(model.id)
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (readyVisionModels.isNotEmpty()) {
            item {
                Text(
                    "Image and audio models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            itemsIndexed(readyVisionModels, key = { _, model -> model.id }) { index, model ->
                val isSelected = model.localPath != null && selectedVisionModel == model.localPath

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        if (model.status == ModelStatus.Available && model.localPath != null) {
                            mainViewModel.selectVisionModel(model.localPath)
                        } else if (model.status == ModelStatus.Paused) {
                            mainViewModel.resumeDownload(model.id)
                        } else {
                            mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (gatedTextModels.isNotEmpty() || gatedVisionModels.isNotEmpty()) {
            item {
                Text(
                    "Requires license or Hugging Face token",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            itemsIndexed(gatedTextModels + gatedVisionModels, key = { _, model -> model.id }) { index, model ->
                val isSelected = model.localPath != null && when (model.type) {
                    ModelType.Text -> selectedTextModel == model.localPath
                    ModelType.Vision -> selectedVisionModel == model.localPath
                }

                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = {
                        when {
                            model.status == ModelStatus.Available && model.localPath != null -> {
                                when (model.type) {
                                    ModelType.Text -> mainViewModel.selectTextModel(model.localPath)
                                    ModelType.Vision -> mainViewModel.selectVisionModel(model.localPath)
                                }
                            }
                            model.status == ModelStatus.Paused -> mainViewModel.resumeDownload(model.id)
                            else -> mainViewModel.startDownload(model.id)
                        }
                    },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (blockedTextModels.isNotEmpty() || blockedVisionModels.isNotEmpty()) {
            item {
                Text(
                    "Blocked by device safety profile",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            itemsIndexed(blockedTextModels + blockedVisionModels, key = { _, model -> model.id }) { index, model ->
                ModelItem(
                    model = model,
                    isSelected = false,
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = { mainViewModel.deleteModel(model.id) },
                    onUnsafeDownload = { mainViewModel.startDownloadAnyway(model.id) },
                    onUnsafeTry = { mainViewModel.tryModelAnyway(model.id) },
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (futureDenseTextModels.isNotEmpty() || futureDenseVisionModels.isNotEmpty()) {
            item {
                Text(
                    "Future Android builds",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            itemsIndexed(futureDenseTextModels + futureDenseVisionModels, key = { _, model -> model.id }) { index, model ->
                ModelItem(
                    model = model,
                    isSelected = false,
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = null,
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        if (futureMoeTextModels.isNotEmpty() || futureMoeVisionModels.isNotEmpty()) {
            item {
                Text(
                    "MoE future builds",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            itemsIndexed(futureMoeTextModels + futureMoeVisionModels, key = { _, model -> model.id }) { index, model ->
                ModelItem(
                    model = model,
                    isSelected = false,
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = null,
                    entryDelayMs = (index * 30).coerceAtMost(250),
                    hazeState = hazeState
                )
            }
        }

        item {
            CustomModelCard(
                onAddModel = { name, url, type ->
                    mainViewModel.addCustomModel(name = name, url = url, type = type)
                },
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
    hazeState: HazeState
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val searchShape = RoundedCornerShape(24.dp)
    val searchTint = if (isDark) Color.White.copy(alpha = 0.055f) else Color.White.copy(alpha = 0.52f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .fluidReveal(delayMillis = 80, initialYOffset = 10.dp)
                .animatedGlassHalo(alpha = 0.035f, durationMillis = 4_800)
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
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "model_filter_chip_scale"
                )
                LiquidGlassButton(
                    onClick = { onTierChange(tier) },
                    hazeState = hazeState,
                    modifier = Modifier
                        .height(42.dp)
                        .graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                        },
                    shape = RoundedCornerShape(18.dp),
                    tintColor = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    } else {
                        Color.White.copy(alpha = if (isDark) 0.045f else 0.34f)
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(
                        tier.display,
                        color = if (selected) {
                            Color.White
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
    onClear: () -> Unit,
    hazeState: HazeState
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val tintColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = 90, initialYOffset = 12.dp)
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
                if (searchQuery.isNotBlank()) append("search \"$searchQuery\"")
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
    val totalBytes = remember(downloadedModels) {
        downloadedModels.sumOf { model ->
            val path = model.localPath ?: return@sumOf 0L
            val file = java.io.File(path)
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                file.isFile -> file.length()
                else -> 0L
            }
        }
    }
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardTint = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = 60, initialYOffset = 20.dp)
            .animatedGlassHalo(enabled = downloadedModels.isNotEmpty(), alpha = 0.04f, durationMillis = 5_000)
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val tintColor = if (isCompatible) {
        if (isDark) Color(0xFF0A84FF).copy(alpha = 0.15f) else Color(0xFF007AFF).copy(alpha = 0.15f)
    } else {
        if (isDark) Color(0xFFFF453A).copy(alpha = 0.15f) else Color(0xFFFF3B30).copy(alpha = 0.15f)
    }
    val borderAlpha = if (isCompatible) 0.35f else 0.45f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = 100, initialYOffset = 20.dp)
            .animatedGlassHalo(alpha = if (isCompatible) 0.045f else 0.03f, durationMillis = 4_600)
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
                modifier = Modifier.size(24.dp)
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
private fun PrivacyCard(hazeState: HazeState) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardTint = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = 140, initialYOffset = 20.dp)
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

@Composable
private fun AccessCard(
    token: String,
    onTokenSave: (String) -> Unit,
    onOpenTokenTutorial: () -> Unit,
    hazeState: HazeState
) {
    var tokenDraft by rememberSaveable(token) { mutableStateOf(token) }
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardTint = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = 180, initialYOffset = 20.dp)
            .animatedGlassHalo(alpha = 0.035f, durationMillis = 5_200)
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = cardTint),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Access", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Hugging Face read token") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent
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
                    shape = RoundedCornerShape(20.dp),
                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
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
private fun CustomModelCard(onAddModel: (String, String, ModelType) -> Unit, hazeState: HazeState) {
    var modelName by rememberSaveable { mutableStateOf("") }
    var modelUrl by rememberSaveable { mutableStateOf("") }
    var textSelected by rememberSaveable { mutableStateOf(false) }
    var imageSelected by rememberSaveable { mutableStateOf(false) }
    val hasTypeSelection = textSelected || imageSelected
    val selectedTypeCount = (if (textSelected) 1 else 0) + (if (imageSelected) 1 else 0)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardTint = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = 220, initialYOffset = 20.dp)
            .glassEffect(hazeState = hazeState, shape = MaterialTheme.shapes.medium, blurRadius = 16.dp, tintColor = cardTint),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Custom model", style = MaterialTheme.typography.titleSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CustomModelTypeButton(
                    label = "Text",
                    selected = textSelected,
                    isDark = isDark,
                    hazeState = hazeState,
                    onClick = { textSelected = !textSelected }
                )
                CustomModelTypeButton(
                    label = "Image",
                    selected = imageSelected,
                    isDark = isDark,
                    hazeState = hazeState,
                    onClick = { imageSelected = !imageSelected }
                )
            }

            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent
                )
            )
            OutlinedTextField(
                value = modelUrl,
                onValueChange = { modelUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Direct .task, .litertlm, or .tflite URL") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent
                )
            )
            LiquidGlassButton(
                onClick = {
                    if (textSelected) {
                        onAddModel(modelName, modelUrl, ModelType.Text)
                    }
                    if (imageSelected) {
                        onAddModel(modelName, modelUrl, ModelType.Vision)
                    }
                    modelName = ""
                    modelUrl = ""
                    textSelected = false
                    imageSelected = false
                },
                enabled = modelUrl.isNotBlank() && hasTypeSelection,
                modifier = Modifier.align(Alignment.End),
                hazeState = hazeState,
                shape = RoundedCornerShape(20.dp),
                tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when (selectedTypeCount) {
                        0 -> "Select type"
                        2 -> "Add both"
                        else -> "Add"
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomModelTypeButton(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    hazeState: HazeState,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val accent = MaterialTheme.colorScheme.primary
    LiquidGlassButton(
        onClick = onClick,
        hazeState = hazeState,
        modifier = Modifier
            .height(42.dp)
            .border(
                width = if (selected) 1.4.dp else 0.7.dp,
                color = if (selected) {
                    accent.copy(alpha = 0.86f)
                } else {
                    Color.White.copy(alpha = if (isDark) 0.12f else 0.24f)
                },
                shape = shape
            ),
        shape = shape,
        tintColor = if (selected) {
            accent.copy(alpha = 0.40f)
        } else {
            Color.White.copy(alpha = if (isDark) 0.035f else 0.18f)
        },
        contentPadding = PaddingValues(horizontal = if (selected) 14.dp else 18.dp, vertical = 0.dp)
    ) {
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) {
                accent
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            }
        )
    }
}

@Composable
private fun GlassLabelChip(
    label: String,
    hazeState: HazeState,
    accentColor: Color
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .glassEffect(
                hazeState = hazeState,
                shape = shape,
                blurRadius = 20.dp,
                tintColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.58f),
                borderAlpha = 0.48f
            )
            .padding(horizontal = 12.dp),
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
            style = MaterialTheme.typography.labelMedium,
            color = if (isDark) Color.White.copy(alpha = 0.86f) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LiquidProgressBar(
    progress: Float,
    hazeState: HazeState
) {
    val shape = RoundedCornerShape(999.dp)
    val targetProgress = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 420),
        label = "liquid_download_progress"
    )
    val waveTransition = rememberInfiniteTransition(label = "liquid_download_wave")
    val waveShift by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1_450), repeatMode = RepeatMode.Restart),
        label = "liquid_download_wave_shift"
    )
    val completionPulse by animateFloatAsState(
        targetValue = if (targetProgress >= 0.999f) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "liquid_download_complete_pulse"
    )
    val liquidPrimary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .graphicsLayer {
                scaleX = 1f + completionPulse * 0.06f
                scaleY = 1f + completionPulse * 0.04f
            }
            .glassEffect(
                hazeState = hazeState,
                shape = shape,
                blurRadius = 12.dp,
                tintColor = Color.White.copy(alpha = 0.08f),
                borderAlpha = 0.22f
            )
            .padding(2.dp)
    ) {
        Canvas(
            modifier = Modifier
                .clip(shape)
                .matchParentSize()
        ) {
            val fillTop = size.height * (1f - animatedProgress)
            val amplitude = size.height * 0.24f * (1f - animatedProgress).coerceIn(0f, 1f)
            val waveLength = size.width / 1.35f
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, fillTop)
                var x = 0f
                while (x <= size.width + 8f) {
                    val phase = ((x / waveLength) + waveShift) * 2f * PI.toFloat()
                    val y = fillTop + sin(phase) * amplitude
                    lineTo(x, y)
                    x += 8f
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF8BE9FF),
                        liquidPrimary,
                        Color(0xFFFF7CE8)
                    )
                )
            )
            if (completionPulse > 0f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f * (1f - completionPulse)),
                    radius = size.maxDimension * completionPulse,
                    center = center
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.20f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f)
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
    entryDelayMs: Int = 0,
    hazeState: HazeState
) {
    val isFuturePlaceholder = model.isFuturePlaceholder
    val isCrashBlocked = model.errorMessage?.contains("crashed during native initialization", ignoreCase = true) == true
    val canDownloadAnyway = model.status == ModelStatus.Blocked &&
        model.localPath == null &&
        model.hasDownloadUrl &&
        !isCrashBlocked &&
        onUnsafeDownload != null
    val canTryAnyway = model.status == ModelStatus.Blocked &&
        model.localPath != null &&
        !isCrashBlocked &&
        onUnsafeTry != null
    val statusColor = when (model.status) {
        ModelStatus.Available -> MaterialTheme.colorScheme.primary
        ModelStatus.Downloading -> MaterialTheme.colorScheme.tertiary
        ModelStatus.Paused -> MaterialTheme.colorScheme.secondary
        ModelStatus.Failed -> MaterialTheme.colorScheme.error
        ModelStatus.Blocked -> MaterialTheme.colorScheme.error
        ModelStatus.NeedsConversion -> MaterialTheme.colorScheme.outline
        ModelStatus.ComingSoon -> MaterialTheme.colorScheme.outline
        ModelStatus.NotDownloaded -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val itemShape = RoundedCornerShape(28.dp)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current
    val tintColor = if (isSelected) {
        if (isDark) Color(0xFF0A84FF).copy(alpha = 0.12f) else Color(0xFF007AFF).copy(alpha = 0.12f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f)
    }
    val borderAlpha = if (isSelected) 0.6f else 0.25f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = entryDelayMs, initialYOffset = 12.dp)
            .animatedGlassHalo(enabled = isSelected, alpha = 0.055f, durationMillis = 4_400)
            .jellyOnTouch(sensitivity = 1.45f)
            .glassEffect(hazeState = hazeState, shape = itemShape, blurRadius = 16.dp, tintColor = tintColor, borderAlpha = borderAlpha),
        shape = itemShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = when (model.status) {
                        ModelStatus.Available -> Icons.Default.CheckCircle
                        ModelStatus.Blocked -> Icons.Default.Info
                        else -> Icons.Default.Download
                    },
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${model.type.label} | ${model.displaySize} | ${model.backend}",
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
                    label = if (isSelected) "Selected" else model.status.label,
                    hazeState = hazeState,
                    accentColor = statusColor
                )
                GlassLabelChip(
                    label = model.contextLengthLabel,
                    hazeState = hazeState,
                    accentColor = MaterialTheme.colorScheme.primary
                )
                if (model.isRecommended) {
                    GlassLabelChip(
                        label = "Recommended",
                        hazeState = hazeState,
                        accentColor = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (model.requiresHuggingFaceToken) {
                    GlassLabelChip(
                        label = "Token needed",
                        hazeState = hazeState,
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (model.status == ModelStatus.Downloading) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiquidProgressBar(progress = model.progress, hazeState = hazeState)
                    val progressText = if (model.totalBytes > 0L) {
                        "${(model.progress * 100).toInt()}%  |  ${formatBytes(model.downloadedBytes)} / ${formatBytes(model.totalBytes)}  |  ${formatBytes(model.bytesPerSecond)}/s"
                    } else {
                        "${formatBytes(model.downloadedBytes)} downloaded  |  ${formatBytes(model.bytesPerSecond)}/s"
                    }
                    Text(
                        progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            model.errorMessage?.let { error ->
                ClickableUrlText(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenUrl = { url -> openUrl(context, url) }
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Delete button — shown for models that have local files
                val canDelete = onDelete != null && model.localPath != null &&
                    model.status in listOf(
                        ModelStatus.Available, ModelStatus.Paused, ModelStatus.Failed, ModelStatus.Blocked
                    )
                var showDeleteConfirm by remember { mutableStateOf(false) }

                if (showDeleteConfirm) {
                    DeleteModelGlassDialog(
                        modelName = model.name,
                        hazeState = hazeState,
                        onDismissRequest = { showDeleteConfirm = false },
                        onConfirm = {
                            showDeleteConfirm = false
                            onDelete?.invoke()
                        }
                    )
                }

                if (canDelete) {
                    LiquidGlassButton(
                        onClick = { showDeleteConfirm = true },
                        hazeState = hazeState,
                        shape = RoundedCornerShape(20.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete")
                    }
                }

                if (onOpenPage != null) {
                    LiquidGlassButton(
                        onClick = onOpenPage,
                        hazeState = hazeState,
                        shape = RoundedCornerShape(20.dp),
                        tintColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Page")
                    }
                }

                if (model.status in listOf(ModelStatus.Downloading, ModelStatus.Paused) && onCancel != null) {
                    LiquidGlassButton(
                        onClick = onCancel,
                        hazeState = hazeState,
                        shape = RoundedCornerShape(20.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel")
                    }
                }

                var showRiskyDownloadConfirm by remember { mutableStateOf(false) }
                var showRiskyTryConfirm by remember { mutableStateOf(false) }
                if (showRiskyDownloadConfirm) {
                    RiskyModelActionDialog(
                        modelName = model.name,
                        title = "Download anyway?",
                        message = "This model is blocked for this Android device profile. Downloading anyway can use a lot of storage and the model may still fail or crash during initialization.",
                        confirmLabel = "Download Anyway",
                        hazeState = hazeState,
                        onDismissRequest = { showRiskyDownloadConfirm = false },
                        onConfirm = {
                            showRiskyDownloadConfirm = false
                            onUnsafeDownload?.invoke()
                        }
                    )
                }
                if (showRiskyTryConfirm) {
                    RiskyModelActionDialog(
                        modelName = model.name,
                        title = "Try anyway?",
                        message = "This can crash or close the app during native LiteRT-LM initialization. If that happens, the app will block this model on the next launch.",
                        confirmLabel = "Try Anyway",
                        hazeState = hazeState,
                        onDismissRequest = { showRiskyTryConfirm = false },
                        onConfirm = {
                            showRiskyTryConfirm = false
                            onUnsafeTry?.invoke()
                        }
                    )
                }

                LiquidGlassButton(
                    onClick = when {
                        model.status == ModelStatus.Downloading -> onPause
                        canDownloadAnyway -> ({ showRiskyDownloadConfirm = true })
                        canTryAnyway -> ({ showRiskyTryConfirm = true })
                        else -> onAction
                    },
                    enabled = !isSelected &&
                        !isFuturePlaceholder &&
                        (model.status != ModelStatus.Blocked || canDownloadAnyway || canTryAnyway),
                    hazeState = hazeState,
                    shape = RoundedCornerShape(22.dp),
                    tintColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = when (model.status) {
                            ModelStatus.Available -> Icons.Default.CheckCircle
                            ModelStatus.Downloading -> Icons.Default.Pause
                            ModelStatus.Blocked -> if (canTryAnyway) Icons.Default.Info else Icons.Default.Download
                            else -> Icons.Default.Download
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            isSelected -> "Selected"
                            model.status == ModelStatus.Downloading -> "Pause"
                            model.status == ModelStatus.Paused -> "Resume"
                            model.status == ModelStatus.Available -> "Select"
                            model.status == ModelStatus.Failed -> "Retry"
                            canDownloadAnyway -> "Download Anyway"
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
    hazeState: HazeState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
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
                cornerRadius = 32.dp,
                blurRadius = 34.dp,
                refractionStrength = 0.18f,
                dispersionAmount = 0.034f,
                tintColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.70f),
                borderAlpha = 0.50f,
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 14.dp),
                animatedCaustics = true
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Delete model?",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "\"$modelName\" will be deleted from device storage. You can re-download it later.",
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
                            shape = RoundedCornerShape(20.dp),
                            tintColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        LiquidGlassButton(
                            onClick = onConfirm,
                            hazeState = hazeState,
                            shape = RoundedCornerShape(20.dp),
                            tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.30f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete")
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
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
                cornerRadius = 32.dp,
                blurRadius = 34.dp,
                refractionStrength = 0.18f,
                dispersionAmount = 0.034f,
                tintColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.70f),
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
                            shape = RoundedCornerShape(20.dp),
                            tintColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text("Cancel")
                        }
                        LiquidGlassButton(
                            onClick = onConfirm,
                            hazeState = hazeState,
                            shape = RoundedCornerShape(20.dp),
                            tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.30f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
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
    val units = arrayOf("B", "KB", "MB", "GB")
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

private const val HF_TOKEN_TUTORIAL_URL = "https://youtu.be/uBSbgQ1qPHI?si=Ghpa-Lrid-NlHP2P"

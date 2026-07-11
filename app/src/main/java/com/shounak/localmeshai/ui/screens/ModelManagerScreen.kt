package com.shounak.localmeshai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
        ModelStatus.Failed -> downloadedBytes > 0L
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
        ModelStatus.Failed -> 2
        ModelStatus.Available -> 3
        ModelStatus.Blocked -> 4
        else -> 5
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
    val downloadedModels = (allTextModels + allVisionModels)
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
                ModelSectionTitle("Downloaded models", delayMillis = 40)
            }
            itemsIndexed(
                downloadedModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
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
                    hazeState = hazeState
                )
            }
        }

        if (recommendedTextModels.isNotEmpty()) {
            item {
                ModelSectionTitle("Recommended downloads", delayMillis = 65, enterFromEnd = true)
            }

            itemsIndexed(
                recommendedTextModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
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
                    hazeState = hazeState
                )
            }
        }

        if (standardTextModels.isNotEmpty()) {
            item {
                ModelSectionTitle("Text models", delayMillis = 80)
            }

            itemsIndexed(
                standardTextModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
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
                    hazeState = hazeState
                )
            }
        }

        if (readyVisionModels.isNotEmpty()) {
            item {
                ModelSectionTitle("Image and audio models", delayMillis = 95, enterFromEnd = true)
            }
            itemsIndexed(
                readyVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
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
                    hazeState = hazeState
                )
            }
        }

        if (gatedTextModels.isNotEmpty() || gatedVisionModels.isNotEmpty()) {
            item {
                ModelSectionTitle("Requires license or Hugging Face token", delayMillis = 110)
            }
            itemsIndexed(
                gatedTextModels + gatedVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
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
                    hazeState = hazeState
                )
            }
        }

        if (blockedTextModels.isNotEmpty() || blockedVisionModels.isNotEmpty()) {
            item {
                ModelSectionTitle("Blocked by device safety profile", delayMillis = 125, enterFromEnd = true)
            }
            itemsIndexed(
                blockedTextModels + blockedVisionModels,
                key = { _, model -> model.id },
                contentType = { _, _ -> "model_card" }
            ) { _, model ->
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
                    hazeState = hazeState
                )
            }
        }

        if (futureDenseTextModels.isNotEmpty() || futureDenseVisionModels.isNotEmpty()) {
            item {
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
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = null,
                    hazeState = hazeState
                )
            }
        }

        if (futureMoeTextModels.isNotEmpty() || futureMoeVisionModels.isNotEmpty()) {
            item {
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
                    onOpenPage = model.modelPageUrl?.let { url -> { openUrl(context, url) } },
                    onAction = { mainViewModel.startDownload(model.id) },
                    onPause = { mainViewModel.pauseDownload(model.id) },
                    onCancel = { mainViewModel.cancelDownload(model.id) },
                    onDelete = null,
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
    val searchShape = RoundedCornerShape(14.dp)
    val searchTint = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .fluidReveal(
                    delayMillis = 80,
                    initialYOffset = 10.dp,
                    initialXOffset = (-16).dp,
                    initialRotationZ = -0.45f
                )
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

@Composable
private fun AccessCard(
    token: String,
    onTokenSave: (String) -> Unit,
    onOpenTokenTutorial: () -> Unit,
    hazeState: HazeState
) {
    var tokenDraft by rememberSaveable(token) { mutableStateOf(token) }
    val cardTint = MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 180,
                initialYOffset = 20.dp,
                initialXOffset = 18.dp,
                initialRotationZ = 0.55f
            )
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
private fun CustomModelCard(onAddModel: (String, String, ModelType) -> Unit, hazeState: HazeState) {
    var modelName by rememberSaveable { mutableStateOf("") }
    var modelUrl by rememberSaveable { mutableStateOf("") }
    var textSelected by rememberSaveable { mutableStateOf(false) }
    var imageSelected by rememberSaveable { mutableStateOf(false) }
    val hasTypeSelection = textSelected || imageSelected
    val selectedTypeCount = (if (textSelected) 1 else 0) + (if (imageSelected) 1 else 0)
    val cardTint = MaterialTheme.colorScheme.surfaceContainerLow

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                delayMillis = 220,
                initialScale = 0.94f,
                initialYOffset = 20.dp,
                initialRotationZ = -0.75f
            )
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
                    hazeState = hazeState,
                    onClick = { textSelected = !textSelected }
                )
                CustomModelTypeButton(
                    label = "Image",
                    selected = imageSelected,
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
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                modifier = Modifier.align(Alignment.End).height(44.dp),
                hazeState = hazeState,
                shape = RoundedCornerShape(12.dp),
                tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
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
    hazeState: HazeState,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = MaterialTheme.colorScheme.primary
    LiquidGlassButton(
        onClick = onClick,
        hazeState = hazeState,
        modifier = Modifier
            .height(40.dp)
            .border(
                width = if (selected) 1.4.dp else 0.7.dp,
                color = if (selected) {
                    accent.copy(alpha = 0.86f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape
            ),
        shape = shape,
        tintColor = if (selected) {
            accent.copy(alpha = 0.40f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
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
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
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
    val targetStatusColor = when (model.status) {
        ModelStatus.Available -> MaterialTheme.colorScheme.tertiary
        ModelStatus.Downloading -> MaterialTheme.colorScheme.primary
        ModelStatus.Paused -> MaterialTheme.colorScheme.secondary
        ModelStatus.Failed -> MaterialTheme.colorScheme.error
        ModelStatus.Blocked -> MaterialTheme.colorScheme.error
        ModelStatus.NeedsConversion -> MaterialTheme.colorScheme.outline
        ModelStatus.ComingSoon -> MaterialTheme.colorScheme.outline
        ModelStatus.NotDownloaded -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusColor by animateColorAsState(
        targetValue = targetStatusColor,
        animationSpec = tween(240),
        label = "model_status_color"
    )

    val itemShape = MaterialTheme.shapes.large
    val context = LocalContext.current
    val targetTintColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val tintColor by animateColorAsState(
        targetValue = targetTintColor,
        animationSpec = tween(260),
        label = "model_card_tint"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.6f else 0.25f,
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
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(
                initialScale = 0.94f,
                initialYOffset = 18.dp,
                initialXOffset = if ((model.id.hashCode() and 1) == 0) (-16).dp else 16.dp,
                initialRotationZ = if ((model.id.hashCode() and 1) == 0) -0.8f else 0.8f
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.68f,
                    stiffness = 430f
                )
            )
            .graphicsLayer {
                scaleX = 1f + selectionMotion * 0.012f
                scaleY = 1f + selectionMotion * 0.012f
                translationY = -selectionMotion * 2.5f * density
                rotationZ = selectionMotion * -0.18f
            }
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
                        "${model.type.label}  •  ${model.displaySize}  •  ${model.backend}",
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
            }

            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (
                model.status == ModelStatus.Downloading ||
                ((model.status == ModelStatus.Paused || model.status == ModelStatus.Failed) && model.downloadedBytes > 0L)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiquidProgressBar(progress = model.progress)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (model.totalBytes > 0L) {
                                "${(model.progress * 100).toInt()}%  •  ${formatBytes(model.downloadedBytes)} / ${formatBytes(model.totalBytes)}"
                            } else {
                                "${formatBytes(model.downloadedBytes)} downloaded"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (model.status == ModelStatus.Downloading) {
                            Text(
                                "${formatBytes(model.bytesPerSecond)}/s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
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
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
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
                    (model.status in listOf(ModelStatus.Downloading, ModelStatus.Paused) ||
                        (model.status == ModelStatus.Failed && model.downloadedBytes > 0L)) &&
                    onCancel != null
                ) {
                    LiquidGlassButton(
                        onClick = onCancel,
                        hazeState = hazeState,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
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

package com.shounak.localmeshai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.shounak.localmeshai.ui.components.SlidingGlassTab
import com.shounak.localmeshai.ui.components.SlidingGlassTabs
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.shounak.localmeshai.utils.ModelOperationError
import com.shounak.localmeshai.utils.ModelErrorReason
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.shounak.localmeshai.utils.glassEffect
import com.shounak.localmeshai.utils.LiquidGlassButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.wrapContentHeight
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.ui.viewmodels.HfResolutionUiState
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.LocalModelImporter
import com.shounak.localmeshai.utils.ModelVariant
import com.shounak.localmeshai.utils.RepoFile
import com.shounak.localmeshai.utils.ResolvedRepoInfo
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelSheet(
    onDismiss: () -> Unit,
    mainViewModel: MainViewModel,
    sheetSessionId: String,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var isDismissing by remember { mutableStateOf(false) }

    val performDismiss: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                try {
                    sheetState.hide()
                } finally {
                    onDismiss()
                }
            }
        }
    }

    val completionEvent by mainViewModel.importCompletionEvent.collectAsState()
    LaunchedEffect(sheetSessionId) {
        mainViewModel.openImportSheet(sheetSessionId)
    }
    LaunchedEffect(completionEvent) {
        val event = completionEvent
        if (event != null && event.sheetSessionId == sheetSessionId) {
            performDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            performDismiss()
        },
        sheetState = sheetState,
        // Material already handles IME padding. Its Surface also owns the bottom
        // safe inset, outside our glass content, so paint that area as well.
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .glassEffect(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                    borderAlpha = 0.40f
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Model",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            performDismiss()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                SlidingGlassTabs(
                    tabs = listOf(
                        SlidingGlassTab("Phone files", Icons.Default.FolderOpen),
                        SlidingGlassTab("Hugging Face / URL", Icons.Default.CloudDownload)
                    ),
                    selectedIndex = selectedTabIndex,
                    onSelect = { selectedTabIndex = it },
                    hazeState = hazeState
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = if (com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion) androidx.compose.animation.core.snap() else spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ))
                ) {
                    when (selectedTabIndex) {
                        0 -> PhoneImportSection(
                            mainViewModel = mainViewModel,
                            sheetSessionId = sheetSessionId,
                            hazeState = hazeState
                        )
                        1 -> HuggingFaceImportSection(
                            mainViewModel = mainViewModel,
                            sheetSessionId = sheetSessionId,
                            hazeState = hazeState,
                            onSuccess = {
                                performDismiss()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PhoneImportSection(
    mainViewModel: MainViewModel,
    sheetSessionId: String,
    hazeState: HazeState
) {
    val coroutineScope = rememberCoroutineScope()
    val isInspecting by mainViewModel.isImportInspecting.collectAsState()
    val inspectionStatus by mainViewModel.importInspectionStatus.collectAsState()
    val importError by mainViewModel.importInspectionError.collectAsState()
    val inspectedResult by mainViewModel.pendingImportDraft.collectAsState()
    val isRegistering by mainViewModel.isRegisteringLocalModel.collectAsState()
    val registrationError by mainViewModel.localModelRegistrationError.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            mainViewModel.inspectAndSetPhoneUris(uris, sheetSessionId)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Select model weights together with their configuration, tokenizer and processor files. Supported ONNX and safetensors pipelines include text, image classification, speech transcription and image generation. For pipelines with component folders, import a .zip or .solusmodel archive to preserve the folder structure. Compatibility depends on the architecture and tensor interface.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LiquidGlassButton(
            onClick = {
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            hazeState = hazeState,
            enabled = !isInspecting && !isRegistering,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (inspectedResult != null) "Choose different files" else "Select model files from phone")
        }

        if (isInspecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(inspectionStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }

        val effectiveError = importError ?: registrationError
        if (effectiveError != null) {
            val view = LocalView.current
            LaunchedEffect(effectiveError) {
                @Suppress("DEPRECATION")
                view.announceForAccessibility("${effectiveError.userTitle}: ${effectiveError.userMessage}")
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(
                        hazeState = hazeState,
                        shape = RoundedCornerShape(16.dp),
                        tintColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        borderAlpha = 0.40f
                    )
                    .semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = effectiveError.userTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = effectiveError.userMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )

                    val details = effectiveError.sanitizedDetails
                    if (!details.isNullOrBlank()) {
                        var showDetails by rememberSaveable(effectiveError) { mutableStateOf(false) }
                        TextButton(
                            onClick = { showDetails = !showDetails },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = if (showDetails) "Hide details" else "Details",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (showDetails) {
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    LiquidGlassButton(
                        onClick = {
                            if (effectiveError.reason == ModelErrorReason.RegistrationFailed && inspectedResult != null) {
                                mainViewModel.registerPendingDraft(sheetSessionId)
                            } else {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                        },
                        hazeState = hazeState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                    ) {
                        Text(effectiveError.actionText)
                    }
                }
            }
        }

        inspectedResult?.let { result ->
            val bundle = result.bundle
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(
                        hazeState = hazeState,
                        shape = RoundedCornerShape(16.dp),
                        tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        borderAlpha = 0.35f
                    ),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Inspected Model Ready",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Name: ${bundle.displayName}", style = MaterialTheme.typography.bodyMedium)
                    Text("Backend: ${bundle.backend}", style = MaterialTheme.typography.bodySmall)

                    val caps = mutableListOf<String>()
                    if (bundle.capabilities.supportsText) caps.add("Text generation")
                    if (bundle.capabilities.supportsVision) caps.add("Vision / Image")
                    if (bundle.capabilities.supportsAudio) caps.add("Audio")
                    Text("Capabilities: ${caps.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)

                    val sizeMb = bundle.totalSizeBytes / (1024 * 1024)
                    val sizeStr = if (sizeMb > 1024) String.format(Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"
                    Text("Total Size: $sizeStr (${bundle.allArtifacts.size} file${if (bundle.allArtifacts.size > 1) "s" else ""})", style = MaterialTheme.typography.bodySmall)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Artifacts:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    bundle.allArtifacts.forEach { artifact ->
                        Text("• [${artifact.role.name}] ${artifact.displayName}", style = MaterialTheme.typography.labelSmall)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LiquidGlassButton(
                        onClick = {
                            mainViewModel.registerPendingDraft(sheetSessionId)
                        },
                        hazeState = hazeState,
                        enabled = !isRegistering && !isInspecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    ) {
                        if (isRegistering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Registering...")
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Register Model")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceImportSection(
    mainViewModel: MainViewModel,
    sheetSessionId: String,
    hazeState: HazeState,
    onSuccess: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val inputUrl by mainViewModel.hfInputUrl.collectAsState()
    val hfState by mainViewModel.hfResolutionState.collectAsState()

    // Fallback direct URL state
    val directName by mainViewModel.directName.collectAsState()
    val directType by mainViewModel.directType.collectAsState()
    val directSha256 by mainViewModel.directSha256.collectAsState()

    val selectedVariant by mainViewModel.hfSelectedVariant.collectAsState()
    val selectedProjector by mainViewModel.hfSelectedProjector.collectAsState()
    val isSubmittingVariant by mainViewModel.isSubmittingVariantDownload.collectAsState()
    val variantSubmissionError by mainViewModel.variantSubmissionError.collectAsState()
    var actionError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Enter a Hugging Face repository, folder, or model URL. Supports GGUF, .task, .litertlm, .onnx, .tflite and .safetensors. Export requirements are checked before downloading weights. Companion files depend on the export; supported self-contained TFLite models use embedded metadata.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = inputUrl,
                onValueChange = {
                    mainViewModel.updateHfInputUrl(it)
                    actionError = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp),
                label = { Text("Repository or model URL") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            LiquidGlassButton(
                onClick = {
                    actionError = null
                    mainViewModel.submitLinkImport(inputUrl, sheetSessionId)
                },
                hazeState = hazeState,
                enabled = inputUrl.isNotBlank() && hfState !is HfResolutionUiState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(12.dp),
                tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text("Add & download")
            }
        }

        when (val state = hfState) {
            is HfResolutionUiState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Resolving repository and discovering variants...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is HfResolutionUiState.Error -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassEffect(
                            hazeState = hazeState,
                            shape = RoundedCornerShape(16.dp),
                            tintColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                            borderAlpha = 0.40f
                        ),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Resolution Notice",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        if (state.isAuthError) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { mainViewModel.openSettings(false) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Open Settings for HF Token")
                            }
                        }
                    }
                }
            }

            is HfResolutionUiState.Success -> {
                val repoInfo = state.repoInfo
                val variants = state.variants

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassEffect(
                            hazeState = hazeState,
                            shape = RoundedCornerShape(16.dp),
                            tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            borderAlpha = 0.35f
                        ),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Repository: ${repoInfo.repoId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Revision: ${repoInfo.commitSha.take(10)} (${repoInfo.requestedRevision})",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Select Model Quantization / Variant:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            variants.forEach { variant ->
                                val isSelected = selectedVariant == variant
                                val sizeMb = variant.totalSizeBytes / (1024 * 1024)
                                val sizeStr = if (sizeMb > 1024) String.format(Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"
                                val shardInfo = if (variant.isSplit) "${variant.mainFiles.size} shards" else "${variant.mainFiles.size + variant.companionFiles.size} file(s)"

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .background(
                                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 0.5.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            mainViewModel.selectHfVariant(variant)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = variant.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "${variant.format.backendLabel} • $shardInfo • $sizeStr",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Projector selection if companion projectors available
                        selectedVariant?.let { variant ->
                            if (variant.availableProjectors.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Vision / Multimodal Projector:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Option: None
                                    val isNoneSelected = selectedProjector == null
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 48.dp)
                                            .background(
                                                color = if (isNoneSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f) else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = if (isNoneSelected) 1.5.dp else 0.5.dp,
                                                color = if (isNoneSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { mainViewModel.selectHfProjector(null) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("None (Text-only inference)", style = MaterialTheme.typography.bodyMedium)
                                        if (isNoneSelected) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                        }
                                    }

                                    variant.availableProjectors.forEach { proj ->
                                        val isProjSelected = selectedProjector?.path == proj.path
                                        val projMb = proj.sizeBytes / (1024 * 1024)
                                        val projSizeStr = if (projMb > 1024) String.format(Locale.US, "%.1f GB", projMb / 1024f) else "$projMb MB"

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 48.dp)
                                                .background(
                                                    color = if (isProjSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f) else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    width = if (isProjSelected) 1.5.dp else 0.5.dp,
                                                    color = if (isProjSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable { mainViewModel.selectHfProjector(proj) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(proj.fileName, style = MaterialTheme.typography.bodyMedium)
                                                Text(projSizeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (isProjSelected) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Complete artifact list preview
                        selectedVariant?.let { variant ->
                            val artifactsToDownload = mutableListOf<Pair<String, Long>>()
                            variant.downloadFiles.forEach { file ->
                                artifactsToDownload.add(file.path to file.sizeBytes)
                            }
                            selectedProjector?.let { proj ->
                                artifactsToDownload.add(proj.fileName to proj.sizeBytes)
                            }

                            val totalBytes = artifactsToDownload.sumOf { it.second }
                            val totalMb = totalBytes / (1024 * 1024)
                            val totalStr = if (totalMb > 1024) String.format(Locale.US, "%.1f GB", totalMb / 1024f) else "$totalMb MB"

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Artifacts to download ($totalStr total):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            artifactsToDownload.forEach { (name, size) ->
                                val sMb = size / (1024 * 1024)
                                val sStr = if (size in 1..1048575) "${(size + 1023) / 1024} KB" else if (size <= 0) "size unknown" else if (sMb > 1024) String.format(Locale.US, "%.1f GB", sMb / 1024f) else "$sMb MB"
                                Text("• $name ($sStr)", style = MaterialTheme.typography.labelSmall)
                            }

                            val displayError = variantSubmissionError ?: actionError
                            if (displayError != null) {
                                Text(displayError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LiquidGlassButton(
                                onClick = {
                                    actionError = null
                                    mainViewModel.downloadSelectedHuggingFaceVariant(sheetSessionId)
                                },
                                hazeState = hazeState,
                                enabled = !isSubmittingVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            ) {
                                if (isSubmittingVariant) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Starting download...")
                                } else {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download $totalStr Bundle")
                                }
                            }
                        }
                        if (selectedVariant == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .glassEffect(
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(14.dp),
                                        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                                        borderAlpha = 0.25f
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Select a model variant to download",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HfResolutionUiState.Idle -> {
                // Show direct download option when input is a supported direct file format
                val cleanUrl = inputUrl.trim()
                val isDirectFormat = cleanUrl.isNotBlank() && (
                    cleanUrl.endsWith(".gguf", ignoreCase = true) ||
                    cleanUrl.endsWith(".tflite", ignoreCase = true) ||
                    cleanUrl.endsWith(".task", ignoreCase = true) ||
                    cleanUrl.endsWith(".litertlm", ignoreCase = true) ||
                    cleanUrl.endsWith(".zip", ignoreCase = true) ||
                    cleanUrl.contains(".gguf?", ignoreCase = true) ||
                    cleanUrl.contains(".tflite?", ignoreCase = true) ||
                    cleanUrl.contains(".task?", ignoreCase = true) ||
                    cleanUrl.contains(".litertlm?", ignoreCase = true)
                )
                if (isDirectFormat) {
                    val isSubmittingDirect by mainViewModel.isSubmittingDirectDownload.collectAsState()
                    val directError by mainViewModel.directSubmissionError.collectAsState()
                    DirectDownloadFallbackCard(
                        inputUrl = cleanUrl,
                        directName = directName,
                        onNameChange = { mainViewModel.updateDirectName(it) },
                        directType = directType,
                        onTypeChange = { mainViewModel.updateDirectType(it) },
                        directSha256 = directSha256,
                        onShaChange = { mainViewModel.updateDirectSha256(it) },
                        hazeState = hazeState,
                        isSubmitting = isSubmittingDirect,
                        errorMessage = directError ?: actionError,
                        onSubmit = {
                            actionError = null
                            mainViewModel.clearDirectSubmissionError()
                            val cleanSha = directSha256.trim().ifBlank { null }
                            mainViewModel.submitDirectDownload(
                                sheetSessionId = sheetSessionId,
                                name = directName,
                                url = cleanUrl,
                                type = directType,
                                sha256 = cleanSha
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectDownloadFallbackCard(
    inputUrl: String,
    directName: String,
    onNameChange: (String) -> Unit,
    directType: ModelType,
    onTypeChange: (ModelType) -> Unit,
    directSha256: String,
    onShaChange: (String) -> Unit,
    hazeState: HazeState,
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(16.dp),
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                borderAlpha = 0.35f
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Direct Model Download",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Direct HTTP model URLs: .gguf, .task, .litertlm, .onnx, .tflite, .safetensors. Portable models also require config.json and tokenizer.json at the same URL folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedTextField(
                value = directName,
                onValueChange = onNameChange,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                label = { Text("Model Name (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTypeChange(ModelType.Text) },
                    enabled = !isSubmitting,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = if (directType == ModelType.Text) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text("Text (LLM)")
                }
                OutlinedButton(
                    onClick = { onTypeChange(ModelType.Vision) },
                    enabled = !isSubmitting,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = if (directType == ModelType.Vision) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text("Vision / Multimodal")
                }
            }

            OutlinedTextField(
                value = directSha256,
                onValueChange = onShaChange,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                label = { Text("SHA-256 Checksum (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            LiquidGlassButton(
                onClick = onSubmit,
                hazeState = hazeState,
                enabled = inputUrl.isNotBlank() && !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Adding and Enqueueing...")
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add and Download Model")
                }
            }
        }
    }
}

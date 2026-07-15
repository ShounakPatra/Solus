package com.shounak.localmeshai.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import com.shounak.localmeshai.utils.glassmorphic
import com.shounak.localmeshai.utils.glassEffect
import com.shounak.localmeshai.utils.GlassDispersionCard
import com.shounak.localmeshai.utils.GlassDropdownMenu
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelAnswerFormatter
import com.shounak.localmeshai.utils.ModelAnswerSegment
import com.shounak.localmeshai.utils.ThinkingTextUtils
import com.shounak.localmeshai.ui.components.ModelMathCard
import com.shounak.localmeshai.utils.fluidReveal
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import androidx.compose.ui.text.font.FontFamily
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageGenScreen(
    visionViewModel: VisionViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel(),
    hazeState: HazeState
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isKeyboardOpen = WindowInsets.isImeVisible
    var question by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var fullscreenMessageIndex by remember { mutableStateOf<Int?>(null) }
    val answer by visionViewModel.answer.collectAsState()
    val currentSessionId by visionViewModel.currentSessionId.collectAsState()
    val isInitializing by visionViewModel.isInitializing.collectAsState()
    val isAnalyzing by visionViewModel.isAnalyzing.collectAsState()
    val isStopping by visionViewModel.isStopping.collectAsState()
    val isModelReady by visionViewModel.isModelReady.collectAsState()
    val visionError by visionViewModel.error.collectAsState()
    val selectedVisionModelPath by mainViewModel.selectedVisionModelPath.collectAsState()
    val selectedVisionModel = selectedVisionModelPath?.let { path ->
        mainViewModel.availableModels.firstOrNull { it.localPath == path }
    }
    val selectedUnsafeOverride = selectedVisionModel?.id?.let { modelId ->
        mainViewModel.unsafeInitOverrideIds.contains(modelId)
    } == true
    val downloadedVisionModels = mainViewModel.availableModels.filter {
        it.type == ModelType.Vision &&
            (it.status == ModelStatus.Available ||
                (it.status == ModelStatus.Blocked && mainViewModel.unsafeInitOverrideIds.contains(it.id))) &&
            it.localPath != null
    }
    val messages = visionViewModel.messages
    val isBusy = isInitializing || isAnalyzing || isStopping
    val isInputLocked = isAnalyzing || isStopping

    LaunchedEffect(Unit) {
        pruneCameraInputCache(context)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        selectedBitmap = loadBitmap(context, uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        selectedFileUri = uri
        selectedFileName = getFileName(context, uri)
    }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraTempUri?.let { uri ->
                selectedBitmap = loadBitmap(context, uri)
            }
        }
    }

    if (showHistory) {
        ImageChatHistoryDialog(
            sessions = visionViewModel.imageChatSessions,
            currentSessionId = currentSessionId,
            onSelect = { sessionId ->
                val session = visionViewModel.selectChatSession(sessionId)
                if (session != null) {
                    question = ""
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                }
                showHistory = false
            },
            onNewChat = {
                visionViewModel.startNewChat()
                question = ""
                selectedBitmap = null
                selectedFileUri = null
                selectedFileName = null
                showHistory = false
            },
            onDelete = visionViewModel::deleteChatSession,
            onClearAll = {
                visionViewModel.clearHistory()
                question = ""
                selectedBitmap = null
                selectedFileUri = null
                selectedFileName = null
                showHistory = false
            },
            onDismiss = { showHistory = false },
            hazeState = hazeState
        )
    }

    LaunchedEffect(selectedVisionModelPath, selectedVisionModel?.id, selectedUnsafeOverride) {
        if (selectedVisionModelPath == null) {
            visionViewModel.uninitializeModel()
        } else {
            selectedVisionModelPath?.let { path ->
                visionViewModel.initModel(
                    path = path,
                    modelId = selectedVisionModel?.id ?: "",
                    modelName = selectedVisionModel?.name ?: "",
                    modelSize = selectedVisionModel?.size ?: "",
                    contextWindowTokens = selectedVisionModel?.effectiveContextWindowTokens,
                    allowUnsafeOverride = selectedUnsafeOverride
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
        ) {
            VisionControlRow(
                models = downloadedVisionModels,
                selectedPath = selectedVisionModelPath,
                hasHistory = answer.isNotBlank() || visionViewModel.imageChatSessions.isNotEmpty(),
                onSelectModel = mainViewModel::selectVisionModel,
                onNewChat = {
                    visionViewModel.startNewChat()
                    question = ""
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                },
                onHistory = { showHistory = true },
                hazeState = hazeState
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                selectedVisionModelPath?.let { path ->
                    ImageMetricRow(modelName = path.substringAfterLast("/"))
                }

                visionError?.let { ImageErrorCard(it) }

                // Middle area: large Answer area covering full upper part, matching Chats tab
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        selectedVisionModelPath == null -> {
                            EmptyImageState(
                                title = "No vision model selected",
                                subtitle = if (downloadedVisionModels.isEmpty()) {
                                    "Open Models and download a phone-ready vision model."
                                } else {
                                    "Choose an image model above to begin."
                                },
                                hazeState = hazeState
                            )
                        }
                        !isModelReady -> {
                            EmptyImageState(
                                title = if (isInitializing) "Initialising image model" else "Image model is not ready",
                                subtitle = if (isInitializing) "This usually takes a moment." else "Choose the model again or select another downloaded model.",
                                hazeState = hazeState
                            )
                        }
                        messages.isEmpty() -> {
                            EmptyImageState(
                                title = "No conversation yet",
                                subtitle = "Add an image or file below and ask a question.",
                                hazeState = hazeState
                            )
                        }
                        else -> {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(
                                        messages,
                                        key = { _, message -> message.id },
                                        contentType = { _, message ->
                                            if (message.isUser) "vision_user" else "vision_assistant"
                                        }
                                    ) { index, message ->
                                        VisionChatBubble(
                                            text = message.text,
                                            isUser = message.isUser,
                                            bitmap = message.bitmap,
                                            hazeState = hazeState,
                                            isStreaming = !message.isUser && isAnalyzing && index == messages.lastIndex,
                                            entryDelayMs = if (index == messages.lastIndex) 25 else 0,
                                            onFullscreenClick = { fullscreenMessageIndex = index }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Initialising / Analyzing / Stopping progress overlay
                    if (isInitializing || isAnalyzing || isStopping) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            VisionLiquidProgressBar(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = when {
                                    isInitializing -> "Initialising image model safely…"
                                    isStopping -> "Stopping image model…"
                                    else -> "Reading input…"
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Bottom input group: glassmorphic composer matching ChatScreen
                val composerCorner = if (isKeyboardOpen) 18.dp else 20.dp
                val composerTint = MaterialTheme.colorScheme.surfaceContainerHigh
                GlassDispersionCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(bottom = if (isKeyboardOpen) 0.dp else 2.dp),
                    hazeState = hazeState,
                    cornerRadius = composerCorner,
                    blurRadius = 34.dp,
                    refractionStrength = 0.16f,
                    dispersionAmount = 0.030f,
                    tintColor = composerTint,
                    borderAlpha = 0.48f,
                    contentPadding = PaddingValues(10.dp),
                    animatedCaustics = false
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Compact image preview inside chat box
                        if (selectedBitmap != null && !selectedBitmap!!.isRecycled) {
                            var previewTilted by remember(selectedBitmap) { mutableStateOf(false) }
                            LaunchedEffect(selectedBitmap) {
                                previewTilted = true
                            }
                            val previewTilt by animateFloatAsState(
                                targetValue = if (previewTilted) -1.5f else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "vision_preview_tilt"
                            )
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .size(72.dp)
                                    .fluidReveal(initialScale = 0.72f, initialYOffset = 8.dp)
                                    .graphicsLayer { rotationZ = previewTilt }
                            ) {
                                Image(
                                    bitmap = selectedBitmap!!.asImageBitmap(),
                                    contentDescription = "Selected image preview",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { selectedBitmap = null },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 6.dp, y = (-6).dp)
                                        .size(20.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove image",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Compact file preview inside chat box
                        if (selectedFileUri != null) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .fluidReveal(initialScale = 0.94f, initialYOffset = 6.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(end = 24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = "Attached file",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = selectedFileName ?: "Attached file",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        selectedFileUri = null
                                        selectedFileName = null
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp)
                                        .size(20.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove file",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        VisionComposerActions(
                            canSend = selectedVisionModelPath != null && !isInputLocked && isModelReady && ((selectedBitmap != null && !selectedBitmap!!.isRecycled) || selectedFileUri != null),
                            isAnalyzing = isAnalyzing,
                            hasAttachment = (selectedBitmap != null && !selectedBitmap!!.isRecycled) || selectedFileUri != null,
                            isFileReadingSupported = selectedVisionModelPath?.let { path ->
                                path.endsWith(".task", ignoreCase = true) || path.endsWith(".litertlm", ignoreCase = true)
                            } ?: true,
                            hazeState = hazeState,
                            onCameraSelect = {
                                val uri = getTempImageUri(context)
                                cameraTempUri = uri
                                cameraLauncher.launch(uri)
                            },
                            onPhotosSelect = { imagePicker.launch("image/*") },
                            onFilesSelect = { filePicker.launch(arrayOf("*/*")) },
                            onSend = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                visionViewModel.ask(selectedBitmap, question, selectedFileUri, selectedFileName)
                                question = ""
                                selectedBitmap = null
                                selectedFileUri = null
                                selectedFileName = null
                            },
                            onStop = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                visionViewModel.stopAnalyzing()
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    text = "Ask something about the image/file…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            enabled = !isInputLocked,
                            minLines = 2,
                            maxLines = 5,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            if (fullscreenMessageIndex != null) {
                val index = fullscreenMessageIndex!!
                VisionFullscreenAnswerPanel(
                    messageProvider = { messages.getOrNull(index) },
                    onClose = { fullscreenMessageIndex = null },
                    hazeState = hazeState
                )
            }
        }
    }
}

@Composable
fun VisionFullscreenAnswerPanel(
    messageProvider: () -> VisionChatMessage?,
    onClose: () -> Unit,
    hazeState: HazeState
) {
    val message = messageProvider() ?: return
    val context = LocalContext.current
    val title = if (message.isUser) "Your question" else "Model response"
    val parsedContent = remember(message.text) {
        ThinkingTextUtils.parse(message.text, allowActiveThinking = false)
    }
    var panelVisible by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) {
        panelVisible = true
    }
    val panelOffsetY by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (panelVisible) 0.dp else 80.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "vision_fullscreen_answer_offset"
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (panelVisible) 1f else 0f,
        animationSpec = tween(200),
        label = "vision_fullscreen_answer_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f))
    ) {
        GlassDispersionCard(
            hazeState = hazeState,
            modifier = Modifier
                .fillMaxSize()
                .offset(y = panelOffsetY)
                .graphicsLayer { alpha = panelAlpha }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            cornerRadius = 20.dp,
            blurRadius = 38.dp,
            refractionStrength = 0.17f,
            dispersionAmount = 0.032f,
            tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            borderAlpha = 0.56f,
            contentPadding = PaddingValues(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    LiquidGlassButton(
                        onClick = onClose,
                        hazeState = hazeState,
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close fullscreen",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (message.isUser && message.bitmap != null && !message.bitmap.isRecycled) {
                            Image(
                                bitmap = message.bitmap.asImageBitmap(),
                                contentDescription = "Query image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        
                        if (!message.isUser && parsedContent.thinkingText != null) {
                            ThinkingProcessCard(
                                thinkingText = parsedContent.thinkingText,
                                isThinkingActive = parsedContent.isThinkingActive
                            )
                        }

                        val rawVisibleText = parsedContent.finalResponseText.ifBlank { if (message.isUser) "" else "Answers will appear here." }
                        val visibleText = if (message.isUser) rawVisibleText else rawVisibleText.normalizeModelAnswerText()
                        val answerSegments = remember(visibleText, message.isUser) {
                            if (message.isUser) listOf(ModelAnswerSegment.Text(visibleText))
                            else ModelAnswerFormatter.parseSafely(visibleText)
                        }
                        answerSegments.forEach { segment ->
                            when (segment) {
                                is ModelAnswerSegment.Text -> Text(
                                    text = segment.text.toAsteriskEmphasisText(),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                is ModelAnswerSegment.Code -> CodeBlock(
                                    code = segment.code,
                                    language = segment.language,
                                    context = context,
                                    hazeState = hazeState
                                )
                                is ModelAnswerSegment.DisplayMath ->
                                    ModelMathCard(latex = segment.latex, hazeState = hazeState)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap {
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
    return bitmap.copy(Bitmap.Config.ARGB_8888, false)
}

@Composable
private fun VisionControlRow(
    models: List<ModelInfo>,
    selectedPath: String?,
    hasHistory: Boolean,
    onSelectModel: (String) -> Unit,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
    hazeState: HazeState
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VisionModelPicker(
            models = models,
            selectedPath = selectedPath,
            onSelect = onSelectModel,
            modifier = Modifier.weight(1f),
            hazeState = hazeState
        )
        LiquidGlassButton(
            onClick = onNewChat,
            hazeState = hazeState,
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "New image chat", modifier = Modifier.size(26.dp))
        }
        LiquidGlassButton(
            onClick = onHistory,
            hazeState = hazeState,
            enabled = hasHistory,
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = "Image chat history", modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
private fun VisionModelPicker(
    models: List<ModelInfo>,
    selectedPath: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = models.firstOrNull { it.localPath == selectedPath }?.name ?: "Choose image model"
    val pickerTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "vision_model_picker_arrow_rotation"
    )

    Box(modifier = modifier) {
        LiquidGlassButton(
            onClick = { expanded = !expanded },
            hazeState = hazeState,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .dropdownTriggerBounce(expanded),
            enabled = models.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
            tintColor = pickerTint,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
        ) {
            Text(selectedName, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation)
            )
        }
        GlassDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, hazeState = hazeState) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.name) },
                    onClick = {
                        expanded = false
                        model.localPath?.let(onSelect)
                    }
                )
            }
        }
    }
}

@Composable
fun ImageChatHistoryDialog(
    sessions: List<VisionChatSession>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    hazeState: HazeState
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassDispersionCard(
            hazeState = hazeState,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            cornerRadius = 20.dp,
            blurRadius = 34.dp,
            refractionStrength = 0.16f,
            dispersionAmount = 0.030f,
            tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            borderAlpha = 0.52f,
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Image chat history", style = MaterialTheme.typography.titleMedium)
                    LiquidGlassButton(
                        onClick = onDismiss,
                        hazeState = hazeState,
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(19.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close history", modifier = Modifier.size(18.dp))
                    }
                }

                if (sessions.isEmpty()) {
                    Text("No image questions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                            val selected = session.id == currentSessionId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fluidReveal(
                                        delayMillis = (index * 35).coerceAtMost(280),
                                        initialScale = 0.90f,
                                        initialYOffset = 14.dp,
                                        initialXOffset = if (index % 2 == 0) (-22).dp else 22.dp,
                                        initialRotationZ = if (index % 2 == 0) -1.2f else 1.2f
                                    )
                                    .clickable { onSelect(session.id) }
                                    .glassEffect(
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(20.dp),
                                        blurRadius = 18.dp,
                                        tintColor = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer
                                        },
                                        borderAlpha = if (selected) 0.48f else 0.26f
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            session.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1
                                        )
                                        Text(
                                            if (selected) "Current" else session.answer.take(72),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                    LiquidGlassButton(
                                        onClick = { onDelete(session.id) },
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(18.dp),
                                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    LiquidGlassButton(
                        onClick = onClearAll,
                        hazeState = hazeState,
                        enabled = sessions.isNotEmpty(),
                        shape = RoundedCornerShape(18.dp),
                        tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear all", color = MaterialTheme.colorScheme.error)
                    }
                    LiquidGlassButton(
                        onClick = onNewChat,
                        hazeState = hazeState,
                        shape = RoundedCornerShape(18.dp),
                        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("New chat")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageMetricRow(modelName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(onClick = {}, label = { Text(modelName) })
    }
}

@Composable
private fun EmptyImageState(title: String, subtitle: String, hazeState: HazeState) {
    val cardTint = MaterialTheme.colorScheme.surfaceContainer
    GlassDispersionCard(
        hazeState = hazeState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        cornerRadius = 20.dp,
        tintColor = cardTint
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QuestionAnswer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ImageErrorCard(message: String) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun UniquePlusIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val strokeWidth = 3.5.dp.toPx()
            val barLength = width * 0.6f
            
            // Draw outer rotating dashed ring
            drawCircle(
                color = tint.copy(alpha = 0.15f),
                radius = width * 0.45f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                )
            )
            
            // Draw horizontal bar with gradient
            drawLine(
                brush = Brush.horizontalGradient(listOf(primaryColor, secondaryColor)),
                start = androidx.compose.ui.geometry.Offset((width - barLength) / 2f, height / 2f),
                end = androidx.compose.ui.geometry.Offset((width + barLength) / 2f, height / 2f),
                strokeWidth = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            
            // Draw vertical bar with gradient
            drawLine(
                brush = Brush.verticalGradient(listOf(secondaryColor, tertiaryColor)),
                start = androidx.compose.ui.geometry.Offset(width / 2f, (height - barLength) / 2f),
                end = androidx.compose.ui.geometry.Offset(width / 2f, (height + barLength) / 2f),
                strokeWidth = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            
            // Draw glowing center dot
            drawCircle(
                color = Color.White,
                radius = strokeWidth * 0.4f
            )
        }
    }
}

@Composable
private fun VisionLiquidProgressBar(modifier: Modifier = Modifier) {
    val shift = 0.5f
    val shimmerStart = -1.2f + shift * 2.4f
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
    ) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            cornerRadius = corner
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.30f),
                    accentColor.copy(alpha = 0.78f),
                    accentColor,
                    accentColor.copy(alpha = 0.30f)
                ),
                startX = size.width * shimmerStart,
                endX = size.width * (shimmerStart + 1.2f)
            ),
            cornerRadius = corner
        )
        drawRoundRect(color = borderColor, cornerRadius = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

@Composable
private fun VisionComposerActions(
    canSend: Boolean,
    isAnalyzing: Boolean,
    hasAttachment: Boolean,
    isFileReadingSupported: Boolean,
    hazeState: HazeState,
    onCameraSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onFilesSelect: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sendAnimationScope = rememberCoroutineScope()
    val rocketFlight = remember { Animatable(0f) }
    var isRocketFlying by remember { mutableStateOf(false) }
    val sendAnimationDensity = LocalDensity.current
    val addIconRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "vision_attachment_icon_rotation"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            LiquidGlassButton(
                onClick = { expanded = !expanded },
                hazeState = hazeState,
                enabled = true,
                modifier = Modifier
                    .size(48.dp)
                    .dropdownTriggerBounce(expanded),
                shape = RoundedCornerShape(24.dp),
                tintColor = if (hasAttachment || expanded) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = if (expanded) "Close media options" else "Add image or file",
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(addIconRotation),
                    tint = if (hasAttachment || expanded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                hazeState = hazeState
            ) {
                listOf(
                    VisionAttachmentMenuSpec(
                        title = "Camera",
                        icon = Icons.Default.CameraAlt,
                        enabled = true,
                        onClick = onCameraSelect
                    ),
                    VisionAttachmentMenuSpec(
                        title = "Photos",
                        icon = Icons.Default.PhotoLibrary,
                        enabled = true,
                        onClick = onPhotosSelect
                    ),
                    VisionAttachmentMenuSpec(
                        title = "Files",
                        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                        enabled = isFileReadingSupported,
                        showUnsupported = !isFileReadingSupported,
                        onClick = onFilesSelect
                    )
                ).forEachIndexed { index, item ->
                    DropdownMenuItem(
                        modifier = Modifier.fluidReveal(
                            delayMillis = index * 55,
                            initialScale = 0.88f,
                            initialYOffset = 10.dp
                        ),
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(item.title)
                                if (item.showUnsupported) {
                                    Text(
                                        text = "(Unsupported)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        enabled = item.enabled,
                        onClick = {
                            expanded = false
                            item.onClick()
                        }
                    )
                }
            }
        }
        LiquidGlassButton(
            onClick = {
                when {
                    isRocketFlying -> Unit
                    isAnalyzing -> onStop()
                    canSend -> sendAnimationScope.launch {
                        isRocketFlying = true
                        rocketFlight.snapTo(0f)
                        rocketFlight.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 190)
                        )
                        onSend()
                        rocketFlight.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        isRocketFlying = false
                    }
                }
            },
            hazeState = hazeState,
            enabled = isRocketFlying || isAnalyzing || canSend,
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    val flight = rocketFlight.value
                    translationX = with(sendAnimationDensity) { (flight * 8f).dp.toPx() }
                    translationY = with(sendAnimationDensity) { (-flight * 36f).dp.toPx() }
                    rotationZ = flight * 12f
                    scaleX = 1f - flight * 0.16f
                    scaleY = 1f - flight * 0.16f
                },
            shape = RoundedCornerShape(24.dp),
            tintColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isAnalyzing || canSend) 0.34f else 0.10f),
            contentPadding = PaddingValues(0.dp)
        ) {
            androidx.compose.animation.Crossfade(
                targetState = isAnalyzing && !isRocketFlying,
                animationSpec = tween(180),
                label = "vision_send_stop_icon"
            ) { analyzing ->
                if (analyzing) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop analyzing", modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        Icons.Default.RocketLaunch,
                        contentDescription = "Send question",
                        modifier = Modifier
                            .size(21.dp)
                            .graphicsLayer {
                                val flight = rocketFlight.value
                                translationY = with(sendAnimationDensity) { (-flight * 8f).dp.toPx() }
                                rotationZ = flight * 10f
                            }
                    )
                }
            }
        }
    }
}

private data class VisionAttachmentMenuSpec(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean,
    val showUnsupported: Boolean = false,
    val onClick: () -> Unit
)

private fun String.toAsteriskEmphasisText() = buildAnnotatedString {
    val source = this@toAsteriskEmphasisText
    var index = 0
    while (index < source.length) {
        val markerIndex = source.indexOf('*', startIndex = index)
        if (markerIndex == -1) {
            append(source.substring(index))
            break
        }

        val marker = when {
            source.startsWith("***", markerIndex) -> "***"
            source.startsWith("**", markerIndex) -> "**"
            else -> "*"
        }
        val contentStart = markerIndex + marker.length
        val end = source.indexOf(marker, startIndex = contentStart)
        if (end == -1) {
            append(source.substring(index))
            break
        }

        val emphasized = source.substring(contentStart, end)
        if (emphasized.isBlank()) {
            append(source.substring(index, markerIndex))
        } else {
            append(source.substring(index, markerIndex))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(emphasized)
            }
        }
        index = end + marker.length
    }
}

private fun String.normalizeModelAnswerText(): String {
    // Preserve LaTeX command prefixes (for example \times and \neq) until the
    // answer formatter processes them.
    var normalized = ModelOutputSanitizer.clean(this)
    normalized = ModelAnswerFormatter.normalizeEscapedModelText(normalized)

    normalized = normalized.replace(Regex("""(?m)^[ \t]*(?:[*-][ \t]+)+\*\*(.+?)\*\*""")) { match ->
        "**${match.groupValues[1]}**"
    }
    normalized = normalized.replace(Regex("""(?m)^[ \t]*(?:[*-][ \t]+)+\*(.+?:)""")) { match ->
        "**${match.groupValues[1]}**"
    }
    normalized = normalized.replace(Regex("""(?m)^[ \t]*\*[ \t]+\*(?=\S)"""), "")
    return normalized.trimStart('\n', '\r')
}

private val MessageActionButtonReserveWidth = 76.dp

@Composable
private fun Modifier.dropdownTriggerBounce(expanded: Boolean): Modifier {
    val scaleX by animateFloatAsState(
        targetValue = if (expanded) 1.055f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dropdown_trigger_scale_x"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (expanded) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dropdown_trigger_scale_y"
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) -2.4f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "dropdown_trigger_rotation"
    )
    val lift by animateFloatAsState(
        targetValue = if (expanded) -2.2f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "dropdown_trigger_lift"
    )

    return graphicsLayer {
        this.scaleX = scaleX
        this.scaleY = scaleY
        rotationZ = rotation
        translationY = lift * density
    }
}

private fun AnnotatedString.trimStartLayoutWhitespace(): AnnotatedString {
    var start = 0
    while (start < text.length && text[start].isWhitespace()) {
        start++
    }
    return if (start == 0) this else subSequence(start, text.length)
}

@Composable
private fun AdaptiveMessageText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val annotatedText = remember(text) { text.toAsteriskEmphasisText() }
    if (annotatedText.text.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val textStyle = LocalTextStyle.current
        val textMeasurer = rememberTextMeasurer()
        val fullWidthPx = with(density) { maxWidth.roundToPx() }
        val firstLineWidthPx = with(density) {
            (maxWidth - MessageActionButtonReserveWidth).coerceAtLeast(1.dp).roundToPx()
        }
        val firstLineLayout = remember(annotatedText, textStyle, firstLineWidthPx, fullWidthPx) {
            if (firstLineWidthPx >= fullWidthPx) {
                null
            } else {
                textMeasurer.measure(
                    text = annotatedText,
                    style = textStyle,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = firstLineWidthPx)
                )
            }
        }
        val splitIndex = remember(firstLineLayout, annotatedText) {
            firstLineLayout
                ?.takeIf { it.lineCount > 0 }
                ?.getLineEnd(lineIndex = 0, visibleEnd = true)
                ?.coerceIn(0, annotatedText.text.length)
                ?: annotatedText.text.length
        }
        if (splitIndex >= annotatedText.text.length) {
            Text(
                text = annotatedText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = MessageActionButtonReserveWidth),
                color = color,
                textAlign = TextAlign.Start
            )
        } else {
            val firstLine = remember(annotatedText, splitIndex) {
                annotatedText.subSequence(0, splitIndex)
            }
            val remainingText = remember(annotatedText, splitIndex) {
                annotatedText
                    .subSequence(splitIndex, annotatedText.text.length)
                    .trimStartLayoutWhitespace()
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (firstLine.text.isNotEmpty()) {
                    Text(
                        text = firstLine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = MessageActionButtonReserveWidth),
                        color = color,
                        textAlign = TextAlign.Start
                    )
                }
                if (remainingText.text.isNotEmpty()) {
                    Text(
                        text = remainingText,
                        modifier = Modifier.fillMaxWidth(),
                        color = color,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    code: String,
    language: String,
    context: android.content.Context,
    hazeState: HazeState
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }

    val codeTint = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(initialScale = 0.96f, initialYOffset = 8.dp)
            .glassEffect(hazeState = hazeState, shape = RoundedCornerShape(12.dp), blurRadius = 10.dp, tintColor = codeTint)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language.ifBlank { "code" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LiquidGlassButton(
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                        copied = true
                    },
                    hazeState = hazeState,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    if (copied) {
                        Text(
                            "Copied!",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            11.5f, androidx.compose.ui.unit.TextUnitType.Sp
                        ),
                        lineHeight = androidx.compose.ui.unit.TextUnit(
                            18f, androidx.compose.ui.unit.TextUnitType.Sp
                        )
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ThinkingProcessCard(
    thinkingText: String,
    isThinkingActive: Boolean
) {
    var isExpanded by remember { mutableStateOf(isThinkingActive) }
    LaunchedEffect(isThinkingActive) {
        if (isThinkingActive) {
            isExpanded = true
        }
    }
    
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHighest
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardColor, RoundedCornerShape(12.dp))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isThinkingActive) "Thinking process…" else "Thinking process",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (isExpanded) 180f else 0f)
            )
        }
        
        if (isExpanded) {
            Text(
                text = thinkingText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    hazeState: HazeState,
    tintColor: Color,
    onClick: () -> Unit
) {
    LiquidGlassButton(
        onClick = onClick,
        hazeState = hazeState,
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(17.dp),
        tintColor = tintColor.copy(alpha = 0.18f),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = tintColor
        )
    }
}

private fun String.isStreamingPlaceholderText(): Boolean {
    return trim() in setOf("Generating…", "Generating...", "…", "Reading input…", "Reading input...")
}

@Composable
private fun StreamingDotsIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    val dots = remember { List(3) { Animatable(0.35f) } }
    LaunchedEffect(Unit) {
        dots.forEachIndexed { index, dot ->
            launch {
                kotlinx.coroutines.delay(index * 90L)
                while (true) {
                    dot.animateTo(1f, tween(durationMillis = 220))
                    dot.animateTo(0.35f, tween(durationMillis = 260))
                    kotlinx.coroutines.delay(80L)
                }
            }
        }
    }

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { dot ->
            val value = dot.value
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        alpha = value
                        translationY = (1f - value) * 7f
                        scaleX = 0.82f + value * 0.18f
                        scaleY = 0.82f + value * 0.18f
                    }
                    .background(color.copy(alpha = 0.86f), RoundedCornerShape(50))
            )
        }
    }
}

@Composable
fun VisionChatBubble(
    text: String,
    isUser: Boolean,
    bitmap: Bitmap? = null,
    hazeState: HazeState,
    isStreaming: Boolean = false,
    entryDelayMs: Int = 0,
    onFullscreenClick: () -> Unit
) {
    val context = LocalContext.current
    val entryProgress = remember { Animatable(if (entryDelayMs > 0) 0f else 1f) }
    LaunchedEffect(entryDelayMs) {
        if (entryDelayMs <= 0) {
            entryProgress.snapTo(1f)
            return@LaunchedEffect
        }
        if (entryDelayMs > 0) kotlinx.coroutines.delay(entryDelayMs.toLong())
        entryProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = if (isUser) 0.62f else 0.70f,
                stiffness = if (isUser) 460f else 400f,
                visibilityThreshold = 0.001f
            )
        )
    }
    val parsedContent = remember(text, isStreaming) {
        ThinkingTextUtils.parse(text, allowActiveThinking = isStreaming)
    }
    val displayText = remember(parsedContent.finalResponseText, isUser) {
        if (isUser) parsedContent.finalResponseText else parsedContent.finalResponseText.normalizeModelAnswerText()
    }
    val segments = remember(displayText, isUser) {
        if (isUser) listOf(ModelAnswerSegment.Text(displayText))
        else ModelAnswerFormatter.parseSafely(displayText)
    }
    val showStreamingDots = isStreaming && displayText.isStreamingPlaceholderText()
    val hasRichBlock = segments.any {
        it is ModelAnswerSegment.Code || it is ModelAnswerSegment.DisplayMath
    }
    val hasTopImage = isUser && bitmap != null && !bitmap.isRecycled
    val needsTopActionClearance = hasRichBlock || hasTopImage || (!isUser && parsedContent.thinkingText != null)
    val entryValue = entryProgress.value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .graphicsLayer {
                alpha = entryValue
                translationY = (1f - entryValue) * if (isUser) 24f else 16f
                translationX = (1f - entryValue) * if (isUser) 18f else -10f
                scaleX = 0.97f + entryValue * 0.03f
                scaleY = 0.97f + entryValue * 0.03f
                rotationZ = (1f - entryValue) * if (isUser) 0.85f else -0.6f
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        val userShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        val userColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)

        val aiShape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
        val aiColor = MaterialTheme.colorScheme.surfaceContainerHigh

        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .glassEffect(
                    hazeState = hazeState,
                    shape = if (isUser) userShape else aiShape,
                    blurRadius = 16.dp,
                    tintColor = if (isUser) userColor else aiColor,
                    borderAlpha = if (isUser) 0.35f else 0.2f
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 14.dp,
                        top = if (needsTopActionClearance) 40.dp else 14.dp,
                        end = 14.dp,
                        bottom = 14.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasTopImage) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Query image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (!isUser && parsedContent.thinkingText != null) {
                    ThinkingProcessCard(
                        thinkingText = parsedContent.thinkingText,
                        isThinkingActive = parsedContent.isThinkingActive
                    )
                }

                if (showStreamingDots) {
                    StreamingDotsIndicator(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    segments.forEach { segment ->
                        when (segment) {
                            is ModelAnswerSegment.Text -> {
                                if (segment.text.isNotBlank()) {
                                    val visibleText = segment.text.trim('\n')
                                    AdaptiveMessageText(
                                        text = visibleText,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            is ModelAnswerSegment.Code -> {
                                CodeBlock(
                                    code = segment.code,
                                    language = segment.language,
                                    context = context,
                                    hazeState = hazeState
                                )
                            }
                            is ModelAnswerSegment.DisplayMath -> {
                                ModelMathCard(latex = segment.latex, hazeState = hazeState)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MessageToolButton(
                    icon = Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen message",
                    hazeState = hazeState,
                    tintColor = MaterialTheme.colorScheme.primary,
                    onClick = onFullscreenClick,
                )
                MessageToolButton(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Copy message",
                    hazeState = hazeState,
                    tintColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val copyText = if (isUser) text else ModelOutputSanitizer.clean(text)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", copyText))
                    }
                )
            }
        }
    }
}

fun getTempImageUri(context: android.content.Context): Uri {
    pruneCameraInputCache(context)
    val cacheDir = context.cacheDir.resolve("camera_inputs").apply { mkdirs() }
    val file = java.io.File.createTempFile("tmp_camera_", ".jpg", cacheDir)
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "com.shounak.localmeshai.fileprovider",
        file
    )
}

fun pruneCameraInputCache(context: android.content.Context) {
    val dir = context.cacheDir.resolve("camera_inputs")
    if (!dir.exists()) return
    val now = System.currentTimeMillis()
    val files = dir.listFiles()?.filter { it.isFile } ?: return
    files
        .filter { now - it.lastModified() > 24L * 60L * 60L * 1000L }
        .forEach { runCatching { it.delete() } }
    files
        .sortedByDescending { it.lastModified() }
        .drop(16)
        .forEach { runCatching { it.delete() } }
}

fun getFileName(context: android.content.Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "document"
}

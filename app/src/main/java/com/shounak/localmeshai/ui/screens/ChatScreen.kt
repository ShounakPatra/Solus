package com.shounak.localmeshai.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.shounak.localmeshai.utils.animatedGlassHalo
import com.shounak.localmeshai.utils.fluidReveal
import com.shounak.localmeshai.utils.jellyOnTouch
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import androidx.compose.ui.text.font.FontFamily
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.ChatViewModel
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.sqrt

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
    // Do not globally decode literal \n/\r/\t sequences here: LaTeX commands
    // such as \times, \neq and \rho begin with those characters and were being
    // corrupted before the math formatter could see them.
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = viewModel(),
    visionViewModel: VisionViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel(),
    hazeState: HazeState
) {
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAudioName by remember { mutableStateOf<String?>(null) }
    var selectedAudioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var recordingInputLevel by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var recordingSpeechActivity by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var recordingElapsedMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var fullscreenMessageIndex by remember { mutableStateOf<Int?>(null) }
    var fullscreenVisionMessageIndex by remember { mutableStateOf<Int?>(null) }
    val isInitializing by chatViewModel.isInitializing.collectAsState()
    val isGenerating by chatViewModel.isGenerating.collectAsState()
    val isModelReady by chatViewModel.isModelReady.collectAsState()
    val thinkingMode by chatViewModel.thinkingMode.collectAsState()
    val textState by chatViewModel.draftText.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val selectedTextModelPath by mainViewModel.selectedTextModelPath.collectAsState()
    val selectedVisionModelPath by mainViewModel.selectedVisionModelPath.collectAsState()
    val visionCurrentSessionId by visionViewModel.currentSessionId.collectAsState()
    val isVisionInitializing by visionViewModel.isInitializing.collectAsState()
    val isVisionAnalyzing by visionViewModel.isAnalyzing.collectAsState()
    val isVisionStopping by visionViewModel.isStopping.collectAsState()
    val isVisionModelReady by visionViewModel.isModelReady.collectAsState()
    val visionError by visionViewModel.error.collectAsState()
    val selectedModelPath = selectedTextModelPath ?: selectedVisionModelPath
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isKeyboardOpen = WindowInsets.isImeVisible
    val downloadedChatModels = mainViewModel.availableModels.filter {
        it.type in listOf(ModelType.Text, ModelType.Vision) &&
            (it.status == ModelStatus.Available ||
                (it.status == ModelStatus.Blocked && mainViewModel.unsafeInitOverrideIds.contains(it.id))) &&
            it.localPath != null
    }
    val selectedModel = selectedModelPath?.let { path ->
        mainViewModel.availableModels.firstOrNull { it.localPath == path }
    }
    val selectedUnsafeOverride = selectedModel?.id?.let { modelId ->
        mainViewModel.unsafeInitOverrideIds.contains(modelId)
    } == true
    val selectedSupportsAttachments = selectedModel?.type == ModelType.Vision
    val selectedVisionModelSupportsFiles = selectedVisionModelPath?.let { path ->
        path.endsWith(".task", ignoreCase = true) || path.endsWith(".litertlm", ignoreCase = true)
    } ?: false
    val selectedSupportsAudio = selectedSupportsAttachments && remember(
        selectedModel?.localPath,
        selectedModel?.id,
        selectedModel?.supportsAudioInput
    ) {
        selectedModel.supportsAudioInputByMetadata()
    }
    val selectedSupportsThinking = remember(
        selectedModel?.id,
        selectedModel?.name,
        selectedModel?.fileName,
        selectedModel?.backend,
        selectedModel?.type,
        selectedModel?.supportsThinkingMode
    ) {
        selectedModel.supportsThinkingMode()
    }
    val isCurrentModelReady = if (selectedSupportsAttachments) isVisionModelReady else isModelReady
    val isCurrentModelResponding = if (selectedSupportsAttachments) {
        isVisionAnalyzing || isVisionStopping
    } else {
        isGenerating
    }
    val isCurrentModelBusy = if (selectedSupportsAttachments) {
        isVisionInitializing || isVisionAnalyzing || isVisionStopping
    } else {
        isInitializing || isGenerating
    }
    val hasVisionAttachment = (selectedBitmap != null && selectedBitmap?.isRecycled == false) ||
        selectedFileUri != null ||
        selectedAudioUri != null ||
        selectedAudioBytes?.isNotEmpty() == true
    val canEditDraft = !isVisionStopping
    val textMessages = chatViewModel.messages
    val visionMessages = visionViewModel.messages
    val hasTextHistory = textMessages.isNotEmpty() || chatViewModel.chatSessions.isNotEmpty()
    val hasVisionHistory = visionMessages.isNotEmpty() || visionViewModel.imageChatSessions.isNotEmpty()
    val showVisionHistory = selectedSupportsAttachments ||
        (!hasTextHistory && hasVisionHistory) ||
        (selectedModelPath == null && hasVisionHistory)
    val activeMessageCount = if (selectedSupportsAttachments) visionMessages.size else textMessages.size
    val activeLastMessageText = if (selectedSupportsAttachments) {
        visionMessages.lastOrNull()?.text
    } else {
        textMessages.lastOrNull()?.text
    }
    val activeError = if (selectedSupportsAttachments) visionError else error
    val audioRecordLimitMs = 30_000L

    LaunchedEffect(Unit) {
        pruneCameraInputCache(context)
    }

    DisposableEffect(context, visionViewModel) {
        val lifecycleOwner = context.findLifecycleOwner()
        if (lifecycleOwner == null) {
            onDispose { visionViewModel.flushVisibleConversation() }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    visionViewModel.flushVisibleConversation()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                visionViewModel.flushVisibleConversation()
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        selectedBitmap = loadBitmap(context, uri)
        selectedFileUri = null
        selectedFileName = null
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        selectedFileUri = uri
        selectedFileName = getFileName(context, uri)
        selectedBitmap = null
    }
    fun resetRecordingMeters() {
        recordingInputLevel = 0f
        recordingSpeechActivity = 0f
        recordingElapsedMs = 0L
    }

    fun cancelMicRecording() {
        recordingJob?.cancel()
        recordingJob = null
        isRecordingAudio = false
        resetRecordingMeters()
    }

    fun startMicRecording() {
        if (!selectedSupportsAudio || isRecordingAudio) return
        recordingJob?.cancel()
        recordingJob = scope.launch {
            isRecordingAudio = true
            selectedAudioUri = null
            selectedAudioName = null
            selectedAudioBytes = null
            resetRecordingMeters()
            try {
                val recording = recordPcm16Mono(
                    context = context,
                    durationMs = audioRecordLimitMs,
                    onLevel = { inputLevel, speechActivity, elapsedMs ->
                        recordingInputLevel = inputLevel
                        recordingSpeechActivity = speechActivity
                        recordingElapsedMs = elapsedMs
                    }
                )
                selectedAudioBytes = recording.bytes
                selectedAudioName = recording.label
            } catch (_: CancellationException) {
                selectedAudioUri = null
                selectedAudioBytes = null
                selectedAudioName = null
            } catch (throwable: Throwable) {
                selectedAudioUri = null
                selectedAudioBytes = null
                selectedAudioName = null
            chatViewModel.setDraftText(textState.ifBlank { "Audio recording failed: ${throwable.message ?: "microphone unavailable"}" })
            } finally {
                isRecordingAudio = false
                resetRecordingMeters()
                recordingJob = null
            }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startMicRecording()
        } else {
            chatViewModel.setDraftText(textState.ifBlank { "Microphone permission is required to record audio." })
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraTempUri?.let { uri ->
                selectedBitmap = loadBitmap(context, uri)
                selectedFileUri = null
                selectedFileName = null
            }
        }
    }
    // Show scroll-to-bottom FAB when not at the bottom
    val showScrollFab by remember {
        derivedStateOf {
            listState.canScrollForward
        }
    }
    // Show scroll-to-top FAB when not at the top
    val showScrollTopFab by remember {
        derivedStateOf {
            listState.canScrollBackward
        }
    }

    LaunchedEffect(selectedTextModelPath, selectedModel?.id, selectedUnsafeOverride) {
        if (selectedTextModelPath == null) {
            chatViewModel.uninitializeModel()
        } else {
            selectedTextModelPath?.let { path ->
                chatViewModel.initModel(
                    path = path,
                    modelId = selectedModel?.id ?: "",
                    modelName = selectedModel?.name ?: "",
                    modelSize = selectedModel?.size ?: "",
                    allowUnsafeOverride = selectedUnsafeOverride
                )
            }
        }
    }

    LaunchedEffect(selectedVisionModelPath, selectedModel?.id, selectedSupportsAudio, selectedUnsafeOverride) {
        if (selectedVisionModelPath == null) {
            visionViewModel.uninitializeModel()
        } else {
            selectedVisionModelPath?.let { path ->
                visionViewModel.initModel(
                    path = path,
                    modelId = selectedModel?.id ?: "",
                    modelName = selectedModel?.name ?: "",
                    modelSize = selectedModel?.size ?: "",
                    contextWindowTokens = selectedModel?.effectiveContextWindowTokens,
                    supportsAudioInput = selectedSupportsAudio,
                    allowUnsafeOverride = selectedUnsafeOverride
                )
            }
        }
    }

    LaunchedEffect(selectedModel?.id, selectedSupportsThinking, thinkingMode) {
        if (!selectedSupportsThinking && thinkingMode) {
            chatViewModel.setThinkingMode(false)
        }
    }

    var followLatest by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }
            .collect { (isScrolling, isAtBottom) ->
                when {
                    isAtBottom -> followLatest = true
                    isScrolling -> followLatest = false
                }
            }
    }

    // Only auto-scroll when a new message is added if we were already at bottom.
    LaunchedEffect(activeMessageCount, selectedSupportsAttachments) {
        if (activeMessageCount > 0 && followLatest) {
            listState.animateScrollToItem(activeMessageCount - 1, scrollOffset = 100_000)
        }
    }

    LaunchedEffect(activeLastMessageText, selectedSupportsAttachments) {
        if (followLatest && activeMessageCount > 0) {
            listState.scrollToItem(activeMessageCount - 1, scrollOffset = 100_000)
        }
    }

    if (showHistory) {
        if (showVisionHistory) {
            ImageChatHistoryDialog(
                sessions = visionViewModel.imageChatSessions,
                currentSessionId = visionCurrentSessionId,
                onSelect = { sessionId ->
                    visionViewModel.selectChatSession(sessionId)
                    chatViewModel.clearDraftText()
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                    selectedAudioUri = null
                    selectedAudioName = null
                    selectedAudioBytes = null
                    showHistory = false
                },
                onNewChat = {
                    visionViewModel.startNewChat()
                    chatViewModel.clearDraftText()
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                    selectedAudioUri = null
                    selectedAudioName = null
                    selectedAudioBytes = null
                    showHistory = false
                },
                onDelete = visionViewModel::deleteChatSession,
                onClearAll = { showClearConfirm = true },
                onDismiss = { showHistory = false },
                hazeState = hazeState
            )
        } else {
            ChatHistoryDialog(
                sessions = chatViewModel.chatSessions,
                currentSessionId = currentSessionId,
                onSelect = { sessionId ->
                    chatViewModel.selectChatSession(sessionId)
                    chatViewModel.clearDraftText()
                    showHistory = false
                },
                onNewChat = {
                    chatViewModel.startNewChat()
                    chatViewModel.clearDraftText()
                    showHistory = false
                },
                onDelete = chatViewModel::deleteChatSession,
                onClearAll = {
                    showClearConfirm = true
                },
                onDismiss = { showHistory = false },
                hazeState = hazeState
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all history?") },
            text = { Text("This will permanently delete all chat sessions. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        if (showVisionHistory) {
                            visionViewModel.clearHistory()
                        } else {
                            chatViewModel.clearHistory()
                        }
                        showClearConfirm = false
                        showHistory = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val chatBackground = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceContainerLowest,
            MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Chat owns an opaque, full-size background from its first frame.
            // This prevents window/pager surfaces from showing through while its
            // model and reveal animations initialise.
            .background(chatBackground)
            .imePadding(),
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp)
                .fluidReveal(
                    delayMillis = 35,
                    initialScale = 0.985f,
                    initialYOffset = 5.dp,
                    initialXOffset = (-18).dp,
                    initialRotationZ = -0.65f
                )
        ) {
            ChatControlRow(
                models = downloadedChatModels,
                selectedPath = selectedModelPath,
                hasHistory = hasTextHistory || hasVisionHistory,
                hasMessages = if (selectedSupportsAttachments) visionMessages.isNotEmpty() else textMessages.isNotEmpty(),
                onSelectModel = { model ->
                    model.localPath?.let { path ->
                        if (model.type == ModelType.Vision) {
                            mainViewModel.selectVisionModel(path)
                        } else {
                            mainViewModel.selectTextModel(path)
                        }
                    }
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                    selectedAudioUri = null
                    selectedAudioName = null
                    selectedAudioBytes = null
                },
                onNewChat = {
                    if (selectedSupportsAttachments) {
                        visionViewModel.startNewChat()
                    } else {
                        chatViewModel.startNewChat()
                    }
                    chatViewModel.clearDraftText()
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                    selectedAudioUri = null
                    selectedAudioName = null
                    selectedAudioBytes = null
                },
                onHistory = { showHistory = true },
                onShare = {
                    if (selectedSupportsAttachments) {
                        shareVisionConversation(context, visionMessages.toList())
                    } else {
                        shareConversation(context, textMessages.toList())
                    }
                },
                hazeState = hazeState
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = activeError != null,
                    enter = androidx.compose.animation.fadeIn(tween(180)) +
                        androidx.compose.animation.slideInHorizontally(
                            initialOffsetX = { -it / 3 },
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 480f)
                        ),
                    exit = androidx.compose.animation.fadeOut(tween(120)) +
                        androidx.compose.animation.shrinkVertically(
                            animationSpec = spring(dampingRatio = 0.92f, stiffness = 620f)
                        )
                ) {
                    activeError?.let { ErrorCard(it) }
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        selectedModelPath == null -> {
                            EmptyState(
                                title = "No model selected",
                                subtitle = if (downloadedChatModels.isEmpty()) {
                                    "Open Models and download a phone-ready model."
                                } else {
                                    "Choose a model above to begin."
                                },
                                hazeState = hazeState
                            )
                        }
                        !isCurrentModelReady -> {
                            EmptyState(
                                title = if (isCurrentModelBusy) "Initialising model" else "Model is not ready",
                                subtitle = if (isCurrentModelBusy) "This usually takes a moment." else "Choose the model again or select another downloaded model.",
                                hazeState = hazeState
                            )
                        }
                        selectedSupportsAttachments && visionMessages.isEmpty() -> {
                            EmptyState(
                                title = "Ask with images or files",
                                subtitle = "Use the + inside the chat box to add camera, photos, or files.",
                                hazeState = hazeState
                            )
                        }
                        !selectedSupportsAttachments && textMessages.isEmpty() -> {
                            // Show starter prompt suggestions when model is ready but chat is empty
                            StarterPrompts(
                                hazeState = hazeState,
                                onPromptSelected = { prompt ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    chatViewModel.sendMessage(prompt)
                                }
                            )
                        }
                        else -> {
                            // Wrap the entire list in SelectionContainer so text selection works
                            SelectionContainer {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (selectedSupportsAttachments) {
                                        itemsIndexed(
                                            visionMessages,
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
                                                isStreaming = !message.isUser && isVisionAnalyzing && index == visionMessages.lastIndex,
                                                entryDelayMs = if (index == visionMessages.lastIndex) 25 else 0,
                                                onFullscreenClick = { fullscreenVisionMessageIndex = index }
                                            )
                                        }
                                    } else {
                                        itemsIndexed(
                                            textMessages,
                                            key = { _, message -> message.id },
                                            contentType = { _, message ->
                                                if (message.isUser) "text_user" else "text_assistant"
                                            }
                                        ) { index, message ->
                                            ChatBubble(
                                                text = message.text,
                                                isUser = message.isUser,
                                                hazeState = hazeState,
                                                isStreaming = !message.isUser && isGenerating && index == textMessages.lastIndex,
                                                entryDelayMs = if (index == textMessages.lastIndex) 25 else 0,
                                                onFullscreenClick = { fullscreenMessageIndex = index }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Stacked scroll FABs (BottomEnd, BoxScope) ─────────────────
                    // Placed here — outside the when{} branches — so that .align()
                    // is called directly inside BoxScope where it is valid.
                    // Both buttons are only meaningful when there are messages.
                    val hasMessages = if (selectedSupportsAttachments) {
                        visionMessages.isNotEmpty()
                    } else {
                        textMessages.isNotEmpty()
                    }
                    if (hasMessages) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Scroll-to-top FAB — vanishes when already at the top
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showScrollTopFab,
                                enter = androidx.compose.animation.scaleIn(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) + androidx.compose.animation.fadeIn(animationSpec = tween(150)),
                                exit = androidx.compose.animation.scaleOut(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) + androidx.compose.animation.fadeOut(animationSpec = tween(120))
                            ) {
                                LiquidGlassButton(
                                    onClick = {
                                        scope.launch {
                                            listState.animateScrollToItem(0)
                                        }
                                    },
                                    modifier = Modifier.size(46.dp),
                                    hazeState = hazeState,
                                    shape = RoundedCornerShape(23.dp),
                                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Scroll to top",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Scroll-to-bottom FAB — vanishes when already at the bottom
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showScrollFab,
                                enter = androidx.compose.animation.scaleIn(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) + androidx.compose.animation.fadeIn(animationSpec = tween(150)),
                                exit = androidx.compose.animation.scaleOut(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) + androidx.compose.animation.fadeOut(animationSpec = tween(120))
                            ) {
                                LiquidGlassButton(
                                    onClick = {
                                        scope.launch {
                                            val lastIndex = if (selectedSupportsAttachments) {
                                                visionMessages.lastIndex
                                            } else {
                                                textMessages.lastIndex
                                            }
                                            if (lastIndex >= 0) {
                                                listState.animateScrollToItem(lastIndex, scrollOffset = 100_000)
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(46.dp),
                                    hazeState = hazeState,
                                    shape = RoundedCornerShape(23.dp),
                                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Scroll to latest",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Initialising / Generating overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isCurrentModelBusy,
                        enter = androidx.compose.animation.fadeIn(tween(180)) +
                            androidx.compose.animation.slideInVertically(
                                initialOffsetY = { -it },
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f)
                            ),
                        exit = androidx.compose.animation.fadeOut(tween(130)) +
                            androidx.compose.animation.slideOutVertically(
                                targetOffsetY = { -it / 2 },
                                animationSpec = spring(dampingRatio = 0.92f, stiffness = 620f)
                            ),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = when {
                                    selectedSupportsAttachments && isVisionInitializing -> "Initialising multimodal model safely…"
                                    selectedSupportsAttachments && isVisionAnalyzing -> "Reading input…"
                                    selectedSupportsAttachments && isVisionStopping -> "Stopping multimodal model…"
                                    selectedSupportsAttachments -> "Initialising multimodal model safely…"
                                    isInitializing -> "Initialising model safely…"
                                    else -> "Generating live response…"
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // ── Chat input box with integrated thinking toggle ──
                // IME padding is applied to the composer surface only. When the
                // keyboard is open the decorative bottom gap is removed so the
                // text field sits directly above the keyboard.
                val composerCorner by animateDpAsState(
                    targetValue = if (isKeyboardOpen) 28.dp else 30.dp,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 520f),
                    label = "chat_composer_corner"
                )
                val composerMaxHeight by animateDpAsState(
                    targetValue = if (hasVisionAttachment) 380.dp else 280.dp,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                    label = "chat_composer_max_height"
                )
                val composerTint = MaterialTheme.colorScheme.surfaceContainerHigh
                GlassDispersionCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = composerMaxHeight)
                        .fluidReveal(
                            delayMillis = 90,
                            initialScale = 0.94f,
                            initialYOffset = 18.dp,
                            initialXOffset = 8.dp,
                            initialRotationZ = 0.55f
                        )
                        .animatedGlassHalo(alpha = 0.045f, durationMillis = 4_800)
                        .padding(bottom = 0.dp),
                    hazeState = hazeState,
                    cornerRadius = composerCorner,
                    blurRadius = 34.dp,
                    refractionStrength = 0.16f,
                    dispersionAmount = 0.030f,
                    tintColor = composerTint,
                    borderAlpha = 0.48f,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                    animatedCaustics = true
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (selectedBitmap != null && selectedBitmap?.isRecycled == false) {
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
                                label = "chat_preview_tilt"
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
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                LiquidGlassButton(
                                    onClick = { selectedBitmap = null },
                                    hazeState = hazeState,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 7.dp, y = (-7).dp)
                                        .size(24.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.size(13.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (selectedFileUri != null) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .fluidReveal(initialScale = 0.94f, initialYOffset = 6.dp)
                                    .glassEffect(
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 14.dp,
                                        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        borderAlpha = 0.28f
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(end = 28.dp)
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
                                LiquidGlassButton(
                                    onClick = {
                                        selectedFileUri = null
                                        selectedFileName = null
                                    },
                                    hazeState = hazeState,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp)
                                        .size(24.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove file", modifier = Modifier.size(13.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if ((selectedAudioUri != null || selectedAudioBytes?.isNotEmpty() == true) && !isRecordingAudio) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .fluidReveal(initialScale = 0.94f, initialYOffset = 6.dp)
                                    .glassEffect(
                                        hazeState = hazeState,
                                        shape = RoundedCornerShape(18.dp),
                                        blurRadius = 16.dp,
                                        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        borderAlpha = 0.34f
                                    )
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(end = 28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Audiotrack,
                                        contentDescription = "Attached audio",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = when {
                                            isRecordingAudio -> "Recording mic audio..."
                                            else -> selectedAudioName ?: "Attached audio"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LiquidGlassButton(
                                    onClick = {
                                        selectedAudioUri = null
                                        selectedAudioName = null
                                        selectedAudioBytes = null
                                    },
                                    hazeState = hazeState,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp)
                                        .size(24.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove audio", modifier = Modifier.size(13.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        ChatComposerActions(
                            thinkingMode = thinkingMode,
                            canSend = selectedModelPath != null &&
                                !isCurrentModelResponding &&
                                isCurrentModelReady &&
                                if (selectedSupportsAttachments) {
                                    hasVisionAttachment || textState.isNotBlank()
                                } else {
                                    textState.isNotBlank()
                                },
                            isGenerating = if (selectedSupportsAttachments) isVisionAnalyzing else isGenerating,
                            canThink = selectedSupportsThinking && !isCurrentModelResponding,
                            supportsAttachments = selectedSupportsAttachments,
                            hasAttachment = hasVisionAttachment,
                            isFileReadingSupported = selectedVisionModelSupportsFiles,
                            supportsAudioInput = selectedSupportsAudio,
                            isRecordingAudio = isRecordingAudio,
                            recordingInputLevel = recordingInputLevel,
                            recordingSpeechActivity = recordingSpeechActivity,
                            recordingElapsedMs = recordingElapsedMs,
                            recordingLimitMs = audioRecordLimitMs,
                            hazeState = hazeState,
                            onToggleThinking = { enabled -> chatViewModel.setThinkingMode(enabled) },
                            onCameraSelect = {
                                val uri = getTempImageUri(context)
                                cameraTempUri = uri
                                cameraLauncher.launch(uri)
                            },
                            onPhotosSelect = { imagePicker.launch("image/*") },
                            onFilesSelect = { filePicker.launch(arrayOf("*/*")) },
                            onMicRecord = {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    startMicRecording()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onMicUnavailable = {
                                chatViewModel.setDraftText(textState.ifBlank {
                                    when {
                                        selectedModelPath == null -> "Choose an audio-capable multimodal model first."
                                        selectedModel?.type != ModelType.Vision -> "${selectedModel?.name ?: "Selected model"} is a text-only model. Raw audio input needs a multimodal model with audio encoders, such as a converted Phi 4 Multimodal or Whisper pipeline."
                                        else -> "${selectedModel?.name ?: "Selected model"} does not include audio encoder assets in this downloaded bundle."
                                    }
                                })
                            },
                            onCancelRecording = { cancelMicRecording() },
                            onSend = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (selectedSupportsAttachments) {
                                    visionViewModel.ask(
                                        bitmap = selectedBitmap,
                                        question = textState,
                                        fileUri = selectedFileUri,
                                        fileName = selectedFileName,
                                        audioUri = selectedAudioUri,
                                        audioName = selectedAudioName,
                                        audioBytes = selectedAudioBytes,
                                        thinkingMode = thinkingMode && selectedSupportsThinking
                                    )
                                    selectedBitmap = null
                                    selectedFileUri = null
                                    selectedFileName = null
                                    selectedAudioUri = null
                                    selectedAudioName = null
                                    selectedAudioBytes = null
                                } else {
                                    chatViewModel.sendMessage(textState)
                                }
                                chatViewModel.clearDraftText()
                            },
                            onStop = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (selectedSupportsAttachments) {
                                    visionViewModel.stopAnalyzing()
                                } else {
                                    chatViewModel.stopGenerating()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(5.dp))

                        // Character counter below input
                        val charCount = textState.length

                        OutlinedTextField(
                            value = textState,
                            onValueChange = chatViewModel::setDraftText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 68.dp, max = 176.dp),
                            placeholder = {
                                Text(
                                    text = if (selectedSupportsAttachments) {
                                        "Ask about an image, file, or audio…"
                                    } else {
                                        "Ask something…"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            enabled = canEditDraft,
                            minLines = 2,
                            maxLines = 6,
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
                            ),
                            supportingText = if (charCount > 0) ({
                                Text(
                                    "$charCount chars",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }) else null
                        )
                    }
                }
            }

            if (fullscreenMessageIndex != null) {
                val index = fullscreenMessageIndex!!
                FullscreenAnswerPanel(
                    messageProvider = { chatViewModel.messages.getOrNull(index) },
                    onClose = { fullscreenMessageIndex = null },
                    hazeState = hazeState
                )
            }
            if (fullscreenVisionMessageIndex != null) {
                val index = fullscreenVisionMessageIndex!!
                VisionFullscreenAnswerPanel(
                    messageProvider = { visionViewModel.messages.getOrNull(index) },
                    onClose = { fullscreenVisionMessageIndex = null },
                    hazeState = hazeState
                )
            }
        }
    }
}

private fun shareConversation(context: android.content.Context, messages: List<ChatMessage>) {
    if (messages.isEmpty()) return
    val transcript = messages.joinToString(separator = "\n\n") { msg ->
        val speaker = if (msg.isUser) "You" else "AI"
        "$speaker:\n${msg.text}"
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, transcript)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Solus conversation")
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, "Share conversation")
    )
}

private fun shareVisionConversation(context: android.content.Context, messages: List<VisionChatMessage>) {
    if (messages.isEmpty()) return
    val transcript = messages.joinToString(separator = "\n\n") { msg ->
        val speaker = if (msg.isUser) "You" else "AI"
        "$speaker:\n${msg.text}"
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, transcript)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Solus image conversation")
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, "Share conversation")
    )
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? {
    return when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.findLifecycleOwner()
        else -> null
    }
}

private fun ModelInfo?.supportsAudioInputByMetadata(): Boolean {
    val model = this ?: return false
    if (model.type != ModelType.Vision) return false
    val localPath = model.localPath ?: return false
    val localFile = File(localPath)
    if (model.supportsAudioInput && localFile.extension.equals("litertlm", ignoreCase = true)) {
        return true
    }
    return localFile.containsAudioModelAssets()
}

private fun ModelInfo?.supportsThinkingMode(): Boolean {
    val model = this ?: return false
    if (model.supportsThinkingMode) return true
    val searchString = listOf(
        model.id,
        model.name,
        model.fileName,
        model.backend
    ).joinToString(separator = "|").lowercase(Locale.US)
    return ThinkingModelMarkers.any { marker -> searchString.contains(marker) }
}

private val ThinkingModelMarkers = listOf(
    "qwen3",
    "qwen 3",
    "deepseek",
    "reasoning",
    "mimo",
    "exaone",
    "gemma 4",
    "gemma4",
    "gemma-4"
)

private fun File.containsAudioModelAssets(): Boolean {
    if (!exists()) return false
    val audioMarkers = listOf("AUDIO_ENCODER", "AUDIO_ADAPTER", "AUDIO_EMBEDDER", "AUDIO")
    return when {
        isDirectory -> walkTopDown()
            .take(80)
            .any { file -> audioMarkers.any { marker -> file.name.contains(marker, ignoreCase = true) } }
        else -> runCatching {
            ZipFile(this).use { zip ->
                zip.entries().asSequence().any { entry ->
                    audioMarkers.any { marker -> entry.name.contains(marker, ignoreCase = true) }
                }
            }
        }.getOrElse {
            containsAnyAsciiMarker(audioMarkers)
        }
    }
}

private fun File.containsAnyAsciiMarker(markers: List<String>, scanBytes: Int = 256 * 1024): Boolean {
    if (!isFile || length() <= 0L) return false
    val markerBytes = markers.map { it.uppercase(Locale.US).toByteArray(Charsets.US_ASCII) }
    return runCatching {
        inputStream().use { input ->
            val buffer = ByteArray(minOf(length(), scanBytes.toLong()).toInt())
            val read = input.read(buffer)
            read > 0 && markerBytes.any { marker -> buffer.indexOf(marker, read) >= 0 }
        }
    }.getOrDefault(false)
}

private fun ByteArray.indexOf(needle: ByteArray, length: Int): Int {
    if (needle.isEmpty() || length < needle.size) return -1
    val maxStart = length - needle.size
    for (start in 0..maxStart) {
        var matches = true
        for (index in needle.indices) {
            val candidate = this[start + index].toInt().let { byte ->
                if (byte in 97..122) byte - 32 else byte
            }.toByte()
            if (candidate != needle[index]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}

private data class RecordedPcmAudio(
    val bytes: ByteArray,
    val sampleRateHz: Int,
    val label: String
)

@SuppressLint("MissingPermission")
private suspend fun recordPcm16Mono(
    context: Context,
    durationMs: Long = 30_000L,
    onLevel: suspend (inputLevel: Float, speechActivity: Float, elapsedMs: Long) -> Unit = { _, _, _ -> }
): RecordedPcmAudio = withContext(Dispatchers.IO) {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Microphone permission denied.")
    }

    val sampleRateHz = 16_000
    val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBufferSize <= 0) {
        throw IllegalStateException("Microphone does not support 16 kHz PCM recording.")
    }

    val bufferSize = max(minBufferSize, sampleRateHz / 2)
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        throw IllegalStateException("Could not initialise microphone.")
    }

    val output = ByteArrayOutputStream(sampleRateHz * 2 * (durationMs / 1_000L).toInt())
    val buffer = ByteArray(bufferSize)
    var speechActivity = 0f
    var lastMeterUpdateMs = 0L
    try {
        recorder.startRecording()
        val startedAt = System.nanoTime()
        val durationNs = durationMs * 1_000_000L
        while (System.nanoTime() - startedAt < durationNs) {
            currentCoroutineContext().ensureActive()
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                output.write(buffer, 0, read)
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtMost(durationMs)
                val inputLevel = pcm16RmsLevel(buffer, read)
                speechActivity = if (inputLevel > 0.08f) {
                    (speechActivity + 0.18f).coerceAtMost(1f)
                } else {
                    (speechActivity - 0.08f).coerceAtLeast(0f)
                }
                if (elapsedMs - lastMeterUpdateMs >= 60L || elapsedMs >= durationMs) {
                    withContext(Dispatchers.Main.immediate) {
                        onLevel(inputLevel, speechActivity, elapsedMs)
                    }
                    lastMeterUpdateMs = elapsedMs
                }
            }
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }

    val bytes = output.toByteArray()
    if (bytes.isEmpty()) {
        throw IllegalStateException("No microphone audio was captured.")
    }
    RecordedPcmAudio(
        bytes = bytes,
        sampleRateHz = sampleRateHz,
        label = "Mic recording (${sampleRateHz / 1_000} kHz PCM)"
    )
}

private fun pcm16RmsLevel(buffer: ByteArray, read: Int): Float {
    if (read <= 1) return 0f
    var sum = 0.0
    var count = 0
    var index = 0
    while (index + 1 < read) {
        val low = buffer[index].toInt() and 0xFF
        val high = buffer[index + 1].toInt()
        val sample = (high shl 8) or low
        val normalized = sample / 32768.0
        sum += normalized * normalized
        count++
        index += 2
    }
    if (count == 0) return 0f
    return (sqrt(sum / count) * 3.2).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun StarterPrompts(hazeState: HazeState, onPromptSelected: (String) -> Unit) {
    val prompts = listOf(
        "💡 Explain quantum computing in simple terms",
        "🧐 What are 5 things I can do to improve my focus?",
        "✍️ Write a short poem about the night sky",
        "📝 Summarise the key ideas of stoic philosophy",
        "📊 Compare Python and Kotlin for mobile development",
        "🌍 What causes the Northern Lights?"
    )
    val chipTint = MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Start a private conversation",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "Everything runs on-device. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            prompts.forEachIndexed { index, prompt ->
                Box(
                    modifier = Modifier
                        .fluidReveal(
                            delayMillis = index * 45,
                            initialScale = 0.88f,
                            initialYOffset = 10.dp,
                            initialXOffset = if (index % 2 == 0) (-18).dp else 18.dp,
                            initialRotationZ = if (index % 2 == 0) -1.1f else 1.1f
                        )
                        .jellyOnTouch(sensitivity = 1.8f)
                        .clickable { onPromptSelected(prompt.substringAfter(" ")) }
                        .glassEffect(
                            hazeState = hazeState, 
                            shape = RoundedCornerShape(18.dp), 
                            blurRadius = 12.dp, 
                            tintColor = chipTint,
                            borderAlpha = 0.25f
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatComposerActions(
    thinkingMode: Boolean,
    canSend: Boolean,
    isGenerating: Boolean,
    canThink: Boolean,
    supportsAttachments: Boolean,
    hasAttachment: Boolean,
    isFileReadingSupported: Boolean,
    supportsAudioInput: Boolean,
    isRecordingAudio: Boolean,
    recordingInputLevel: Float,
    recordingSpeechActivity: Float,
    recordingElapsedMs: Long,
    recordingLimitMs: Long,
    hazeState: HazeState,
    onToggleThinking: (Boolean) -> Unit,
    onCameraSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onFilesSelect: () -> Unit,
    onMicRecord: () -> Unit,
    onMicUnavailable: () -> Unit,
    onCancelRecording: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    val sendAnimationScope = rememberCoroutineScope()
    val rocketFlight = remember { Animatable(0f) }
    var isRocketFlying by remember { mutableStateOf(false) }
    val sendAnimationDensity = LocalDensity.current
    val attachmentMenuOpen = attachmentMenuExpanded && !isRecordingAudio
    LaunchedEffect(supportsAttachments, isRecordingAudio) {
        if (!supportsAttachments || isRecordingAudio) {
            attachmentMenuExpanded = false
        }
    }
    val attachmentIconRotation by animateFloatAsState(
        targetValue = if (attachmentMenuOpen) 45f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "attachment_menu_icon_rotation"
    )
    val thinkActive = canThink && thinkingMode
    val thinkContentColor = when {
        !canThink -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        thinkActive -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = attachmentMenuOpen,
            enter = androidx.compose.animation.fadeIn(animationSpec = tween(120)) +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.82f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) +
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { -it / 2 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            exit = androidx.compose.animation.fadeOut(animationSpec = tween(100)) +
                androidx.compose.animation.scaleOut(
                    targetScale = 0.88f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            AttachmentInlineMenu(
                hazeState = hazeState,
                isFileReadingSupported = isFileReadingSupported,
                onCameraSelect = {
                    attachmentMenuExpanded = false
                    onCameraSelect()
                },
                onPhotosSelect = {
                    attachmentMenuExpanded = false
                    onPhotosSelect()
                },
                onFilesSelect = {
                    attachmentMenuExpanded = false
                    onFilesSelect()
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                LiquidGlassButton(
                    onClick = {
                        if (isRecordingAudio) {
                            onCancelRecording()
                        } else {
                            attachmentMenuExpanded = !attachmentMenuExpanded
                        }
                    },
                    hazeState = hazeState,
                    enabled = isRecordingAudio || supportsAttachments,
                    modifier = Modifier
                        .size(42.dp)
                        .dropdownTriggerBounce(attachmentMenuOpen),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 14.dp),
                    tintColor = when {
                        isRecordingAudio -> MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                        hasAttachment || attachmentMenuOpen -> MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = if (isRecordingAudio) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (isRecordingAudio) {
                            "Cancel recording"
                        } else if (attachmentMenuOpen) {
                            "Close attachments"
                        } else if (supportsAttachments) {
                            "Add image, camera, or file"
                        } else {
                            "Selected model does not support attachments"
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(attachmentIconRotation),
                        tint = when {
                            isRecordingAudio -> MaterialTheme.colorScheme.error
                            supportsAttachments -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                        }
                    )
                }
            }
            if (isRecordingAudio) {
                AudioRecordingWaveform(
                    inputLevel = recordingInputLevel,
                    speechActivity = recordingSpeechActivity,
                    elapsedMs = recordingElapsedMs,
                    limitMs = recordingLimitMs,
                    hazeState = hazeState,
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                )
            } else {
                val canRecordAudio = supportsAttachments && supportsAudioInput
                LiquidGlassButton(
                onClick = {
                    if (canRecordAudio) {
                        onMicRecord()
                    } else {
                        onMicUnavailable()
                    }
                },
                hazeState = hazeState,
                enabled = !isGenerating,
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 22.dp),
                tintColor = if (canRecordAudio) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = if (canRecordAudio) "Record audio" else "Selected model does not support raw audio input",
                    tint = if (canRecordAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.size(19.dp)
                )
            }
            LiquidGlassButton(
                onClick = { onToggleThinking(!thinkingMode) },
                hazeState = hazeState,
                enabled = canThink,
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(min = 112.dp)
                    .dropdownTriggerBounce(thinkActive),
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 14.dp),
                tintColor = when {
                    thinkActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                    canThink -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = if (thinkActive) "Disable thinking mode" else "Enable thinking mode",
                    tint = thinkContentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (thinkActive) "Think on" else "Think off",
                    style = MaterialTheme.typography.labelMedium,
                    color = thinkContentColor,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        LiquidGlassButton(
            onClick = {
                when {
                    isRocketFlying -> Unit
                    isGenerating -> onStop()
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
            enabled = isRocketFlying || isGenerating || canSend,
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
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 26.dp),
            tintColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isGenerating || canSend) 0.34f else 0.10f),
            contentPadding = PaddingValues(0.dp)
        ) {
            androidx.compose.animation.Crossfade(
                targetState = isGenerating && !isRocketFlying,
                animationSpec = tween(180),
                label = "chat_send_stop_icon"
            ) { generating ->
                if (generating) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop responding", modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        Icons.Default.RocketLaunch,
                        contentDescription = "Send",
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
}

@Composable
private fun AttachmentInlineMenu(
    hazeState: HazeState,
    isFileReadingSupported: Boolean,
    onCameraSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onFilesSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 196.dp, max = 250.dp)
            .fluidReveal(initialScale = 0.90f, initialYOffset = 8.dp)
            .glassEffect(
                hazeState = hazeState,
                shape = RoundedCornerShape(14.dp),
                blurRadius = 24.dp,
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                borderAlpha = 0.42f
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            AttachmentMenuSpec(
                icon = Icons.Default.CameraAlt,
                title = "Camera",
                enabled = true,
                onClick = onCameraSelect
            ),
            AttachmentMenuSpec(
                icon = Icons.Default.PhotoLibrary,
                title = "Photos",
                enabled = true,
                onClick = onPhotosSelect
            ),
            AttachmentMenuSpec(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = "Files",
                subtitle = if (isFileReadingSupported) null else "Needs multimodal file reader",
                enabled = isFileReadingSupported,
                onClick = onFilesSelect
            )
        ).forEachIndexed { index, item ->
            AttachmentInlineMenuItem(
                icon = item.icon,
                title = item.title,
                subtitle = item.subtitle,
                enabled = item.enabled,
                index = index,
                onClick = item.onClick
            )
        }
    }
}

private data class AttachmentMenuSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun AttachmentInlineMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean,
    index: Int = 0,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fluidReveal(delayMillis = index * 55, initialScale = 0.88f, initialYOffset = 10.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(enabled = enabled) { onClick() }
            .graphicsLayer { alpha = if (enabled) 1f else 0.54f }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = contentColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AudioRecordingWaveform(
    inputLevel: Float,
    speechActivity: Float,
    elapsedMs: Long,
    limitMs: Long,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val level = inputLevel.coerceIn(0f, 1f)
    val speech = speechActivity.coerceIn(0f, 1f)
    val seconds = (elapsedMs / 1_000L).coerceAtMost(limitMs / 1_000L)
    val wavePrimaryColor = MaterialTheme.colorScheme.primary
    val waveErrorColor = MaterialTheme.colorScheme.error
    GlassDispersionCard(
        hazeState = hazeState,
        modifier = modifier,
        cornerRadius = 22.dp,
        blurRadius = 18.dp,
        refractionStrength = 0.14f,
        dispersionAmount = 0.025f,
        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
        borderAlpha = 0.42f,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recording - max ${limitMs / 1_000L}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.95f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${seconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(17.dp)
            ) {
                val bars = 28
                val gap = 2.2.dp.toPx()
                val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(2f)
                val centerY = size.height / 2f
                val combined = (level * 0.72f + speech * 0.28f).coerceIn(0f, 1f)
                repeat(bars) { index ->
                    val pulse = (((index * 17) % 11) / 10f).coerceIn(0.16f, 1f)
                    val height = size.height * (0.18f + combined * pulse * 0.82f)
                    val x = index * (barWidth + gap) + barWidth / 2f
                    val tint = if (index % 3 == 0) {
                        wavePrimaryColor
                    } else {
                        waveErrorColor
                    }.copy(alpha = 0.48f + combined * 0.42f)
                    drawLine(
                        color = tint,
                        start = Offset(x, centerY - height / 2f),
                        end = Offset(x, centerY + height / 2f),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioLevelMeter(
                    label = "Speech",
                    value = speech,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary
                )
                AudioLevelMeter(
                    label = "Level",
                    value = level,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AudioLevelMeter(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    color: Color
) {
    val clamped = value.coerceIn(0f, 1f)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.04f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(color.copy(alpha = 0.82f))
            )
        }
    }
}

@Composable
private fun ChatControlRow(
    models: List<ModelInfo>,
    selectedPath: String?,
    hasHistory: Boolean,
    hasMessages: Boolean,
    onSelectModel: (ModelInfo) -> Unit,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
    onShare: () -> Unit,
    hazeState: HazeState
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChatModelPicker(
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
            Icon(Icons.Default.Add, contentDescription = "New chat", modifier = Modifier.size(26.dp))
        }
        LiquidGlassButton(
            onClick = onHistory,
            hazeState = hazeState,
            enabled = hasHistory,
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = "Chat history", modifier = Modifier.size(25.dp))
        }
        LiquidGlassButton(
            onClick = onShare,
            hazeState = hazeState,
            enabled = hasMessages,
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share conversation", modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ChatModelPicker(
    models: List<ModelInfo>,
    selectedPath: String?,
    onSelect: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.localPath == selectedPath }
    val selectedName = selected?.let { "${it.name} · ${it.type.label}" } ?: "Choose model"
    val pickerTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "chat_model_picker_arrow_rotation"
    )

    Box(modifier = modifier) {
        LiquidGlassButton(
            onClick = { expanded = !expanded },
            hazeState = hazeState,
            enabled = models.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .dropdownTriggerBounce(expanded),
            shape = RoundedCornerShape(20.dp),
            tintColor = pickerTint,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
        ) {
            Text(
                selectedName,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation)
            )
        }
        GlassDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, hazeState = hazeState) {
            models.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("${model.name} · ${model.type.label}") },
                    onClick = {
                        expanded = false
                        onSelect(model)
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricRow(modelName: String, lastDuration: Long, tokensPerSecond: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(onClick = {}, label = { Text(modelName) })
        if (lastDuration > 0L) {
            val timeLabel = if (lastDuration >= 1_000L) {
                String.format(Locale.US, "%.1f s", lastDuration / 1_000.0)
            } else {
                "${lastDuration} ms"
            }
            AssistChip(onClick = {}, label = { Text(timeLabel) })
            AssistChip(onClick = {}, label = { Text(String.format(Locale.US, "%.1f tok/s", tokensPerSecond)) })
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, hazeState: HazeState) {
    val cardTint = MaterialTheme.colorScheme.surfaceContainer
    GlassDispersionCard(
        hazeState = hazeState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 230.dp)
            .fluidReveal(delayMillis = 70, initialYOffset = 16.dp)
            .animatedGlassHalo(alpha = 0.04f, durationMillis = 4_900)
            .padding(horizontal = 16.dp),
        cornerRadius = 28.dp,
        tintColor = cardTint,
        animatedCaustics = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 38.dp, horizontal = 20.dp),
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
                    imageVector = Icons.Default.Psychology,
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
private fun ErrorCard(message: String) {
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
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "thinking_process_arrow_rotation"
    )
    
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
                    .rotate(arrowRotation)
            )
        }
        
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + androidx.compose.animation.fadeOut()
        ) {
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
private fun ChatBubble(
    text: String,
    isUser: Boolean,
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
                dampingRatio = if (isUser) 0.64f else 0.72f,
                stiffness = if (isUser) 470f else 410f,
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
    val needsTopActionClearance = hasRichBlock || (!isUser && parsedContent.thinkingText != null)
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
                rotationZ = (1f - entryValue) * if (isUser) 0.65f else -0.42f
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

            // Row of actions (Fullscreen + Copy) in top-right corner
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

/**
 * Renders a single fenced code block with:
 * - Dark surface background distinct from the chat bubble
 * - Language label + dedicated copy button in the header
 * - Horizontally scrollable monospace content area
 */
@Composable
private fun CodeBlock(
    code: String,
    language: String,
    context: android.content.Context,
    hazeState: HazeState
) {
    var copied by remember { mutableStateOf(false) }
    // Auto-reset "Copied!" label after 1.5 s
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
            // ── Header ─────────────────────────────────────────────────────
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
                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── Code content ───────────────────────────────────────────────
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
private fun ChatHistoryDialog(
    sessions: List<ChatSession>,
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
                .wrapContentHeight()
                .fluidReveal(initialScale = 0.94f, initialYOffset = 18.dp)
                .animatedGlassHalo(alpha = 0.06f, durationMillis = 4_200),
            cornerRadius = 20.dp,
            blurRadius = 34.dp,
            refractionStrength = 0.16f,
            dispersionAmount = 0.030f,
            tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            borderAlpha = 0.52f,
            contentPadding = PaddingValues(16.dp),
            animatedCaustics = true
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Chat history", style = MaterialTheme.typography.titleMedium)
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
                    Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            "${session.messages.size} message${if (session.messages.size != 1) "s" else ""}${if (selected) " | Current" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun FullscreenAnswerPanel(
    messageProvider: () -> ChatMessage?,
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
        label = "fullscreen_answer_offset"
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (panelVisible) 1f else 0f,
        animationSpec = tween(200),
        label = "fullscreen_answer_alpha"
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
                        shape = RoundedCornerShape(21.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close fullscreen answer", modifier = Modifier.size(20.dp))
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

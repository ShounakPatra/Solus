package com.shounak.localmeshai.ui.screens

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import com.shounak.localmeshai.ai.RequestProgress
import com.shounak.localmeshai.ai.ModelLoadState
import com.shounak.localmeshai.ai.ModelLoadStage
import com.shounak.localmeshai.ui.models.rememberMessagePresentation
import com.shounak.localmeshai.models.ModelValidationState
import com.shounak.localmeshai.models.ModelCapabilitySnapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.models.ModelCapabilityPolicy
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.ThinkingModeConfig

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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedButton
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.utils.CrashReportManager
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
import com.shounak.localmeshai.ui.components.InlineMathListItem
import com.shounak.localmeshai.ui.components.QuickSuggestionPills
import com.shounak.localmeshai.ui.components.InferenceTelemetryBar
import com.shounak.localmeshai.ui.components.SystemPromptPresetsBar
import com.shounak.localmeshai.ui.theme.ModelTheme
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.animatedGlassHalo
import com.shounak.localmeshai.utils.fluidReveal
import com.shounak.localmeshai.utils.jellyOnTouch
import com.shounak.localmeshai.utils.AudioUtils
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.pow
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import com.shounak.localmeshai.utils.ChatDateFormatter
import com.shounak.localmeshai.ui.components.AudioMessagePlayer
import com.shounak.localmeshai.utils.AttachmentViewerUtils
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.sqrt

enum class HistorySessionType {
    Text,
    Vision
}

private fun String.toAsteriskEmphasisText() = buildAnnotatedString {
    val source = this@toAsteriskEmphasisText
    var index = 0
    while (index < source.length) {
        val asteriskIndex = source.indexOf('*', startIndex = index)
        val codeIndex = source.indexOf('`', startIndex = index)
        val markerIndex = when {
            asteriskIndex < 0 -> codeIndex
            codeIndex < 0 -> asteriskIndex
            else -> minOf(asteriskIndex, codeIndex)
        }
        if (markerIndex == -1) {
            append(source.substring(index))
            break
        }

        if (source[markerIndex] == '`') {
            val end = source.indexOf('`', startIndex = markerIndex + 1)
            if (end == -1) {
                append(source.substring(index))
                break
            }
            append(source.substring(index, markerIndex))
            val code = source.substring(markerIndex + 1, end)
            if (code.isBlank()) append("``") else withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            ) {
                append(code)
            }
            index = end + 1
            continue
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

@Composable
private fun AdaptiveMessageText(text: String, color: Color, modifier: Modifier = Modifier) {
    val annotatedText = remember(text) { text.toAsteriskEmphasisText() }
    if (annotatedText.text.isNotEmpty()) Text(text = annotatedText, color = color,
        modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Start)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = viewModel(),
    visionViewModel: VisionViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel(),
    hazeState: HazeState,
    bottomContentInset: androidx.compose.ui.unit.Dp = 0.dp,
    retainedScrollController: com.shounak.localmeshai.ui.models.ChatScrollController? = null
) {
    var selectedBitmap by chatViewModel.composerAttachments.bitmap
    var selectedFileUri by chatViewModel.composerAttachments.fileUri
    var selectedFileName by chatViewModel.composerAttachments.fileName
    var selectedAudioUri by chatViewModel.composerAttachments.audioUri
    var selectedAudioName by chatViewModel.composerAttachments.audioName
    var selectedAudioBytes by chatViewModel.composerAttachments.audioBytes
    var isRecordingAudio by remember { mutableStateOf(false) }
    var isRecordingPaused by remember { mutableStateOf(false) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var recordingController by remember { mutableStateOf<MicRecordingController?>(null) }
    var recordingLoudnessHistory by remember { mutableStateOf(List(32) { 0.08f }) }
    var isDecodingAudioFile by remember { mutableStateOf(false) }
    var recordingInputLevel by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var recordingSpeechActivity by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var recordingElapsedMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var cameraTempFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val appSettingsData by mainViewModel.appSettingsData.collectAsState()
    var fullscreenMessageIndex by remember { mutableStateOf<Int?>(null) }
    var fullscreenVisionMessageIndex by remember { mutableStateOf<Int?>(null) }
    val modelLoadState by chatViewModel.modelLoadState.collectAsState()
    val isInitializing by chatViewModel.isInitializing.collectAsState()
    val isGenerating by chatViewModel.isGenerating.collectAsState()
    val isDeletingTextHistory by chatViewModel.isDeletingHistory.collectAsState()
    val isDeletingVisionHistory by visionViewModel.isDeletingHistory.collectAsState()
    val isChatStopping by chatViewModel.isStopping.collectAsState()
    val isModelReady by chatViewModel.isModelReady.collectAsState()
    val thinkingMode by chatViewModel.thinkingMode.collectAsState()
    val temporaryText by chatViewModel.temporaryChat.collectAsState()
    val temporaryVision by visionViewModel.temporaryChat.collectAsState()
    val workspacePreferences = com.shounak.localmeshai.features.WorkspacePreferences.get(LocalContext.current)
    val workspaceRevision by workspacePreferences.revision.collectAsState()
    val textState by chatViewModel.draftText.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val pendingSessionId by chatViewModel.pendingSessionId.collectAsState()
    val chatTokensPerSecond by chatViewModel.tokensPerSecond.collectAsState()
    val chatLastInferenceMs by chatViewModel.lastInferenceTime.collectAsState()
    val chatActiveBackend by chatViewModel.activeBackend.collectAsState()
    val visionTokensPerSecond by visionViewModel.tokensPerSecond.collectAsState()
    val visionLastInferenceMs by visionViewModel.lastInferenceTime.collectAsState()
    val visionActiveBackend by visionViewModel.activeBackend.collectAsState()
    val selectedTextModelPath by mainViewModel.selectedTextModelPath.collectAsState()
    val selectedVisionModelPath by mainViewModel.selectedVisionModelPath.collectAsState()
    val visionCurrentSessionId by visionViewModel.currentSessionId.collectAsState()
    val isVisionInitializing by visionViewModel.isInitializing.collectAsState()
    val isVisionAnalyzing by visionViewModel.isAnalyzing.collectAsState()
    val isVisionStopping by visionViewModel.isStopping.collectAsState()
    val isVisionModelReady by visionViewModel.isModelReady.collectAsState()
    val visionError by visionViewModel.error.collectAsState()
    var activeHistoryChatType by rememberSaveable { mutableStateOf<HistorySessionType?>(null) }
    val selectedModelPath = when (activeHistoryChatType) {
        HistorySessionType.Vision -> selectedVisionModelPath ?: selectedTextModelPath
        HistorySessionType.Text -> selectedTextModelPath ?: selectedVisionModelPath
        null -> selectedTextModelPath ?: selectedVisionModelPath
    }
    val listState = retainedScrollController?.listState ?: rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        launch {
            chatViewModel.memoryFeedbackEvent.collect { feedback ->
                android.widget.Toast.makeText(context, "🧠 $feedback", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        launch {
            visionViewModel.memoryFeedbackEvent.collect { feedback ->
                android.widget.Toast.makeText(context, "🧠 $feedback", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val isKeyboardOpen = WindowInsets.isImeVisible
    val validationStates by mainViewModel.modelValidationStates.collectAsState()
    val chatRuntimeCapabilities by chatViewModel.runtimeCapabilities.collectAsState()
    val visionRuntimeCapabilities by visionViewModel.runtimeCapabilities.collectAsState()
    val downloadedChatModels = mainViewModel.availableModels.filter {
        it.type in listOf(ModelType.Text, ModelType.Vision) &&
            (it.localPath != null || it.bundle != null) &&
            it.status in listOf(ModelStatus.Available, ModelStatus.Blocked, ModelStatus.Failed)
    }
    val selectedModel = selectedModelPath?.let { path ->
        mainViewModel.availableModels.firstOrNull { it.id == path || it.localPath == path }
    }
    val selectedUnsafeOverride = selectedModel?.id?.let { modelId ->
        mainViewModel.unsafeInitOverrideIds.contains(modelId) || InitCrashGuard.hasSuccessfulRetry(context, modelId)
    } == true
    val selectedRetryRevision = selectedModel?.id?.let { mainViewModel.modelRetryRevisions[it] } ?: 0
    val selectedSupportsAttachments = when (activeHistoryChatType) {
        HistorySessionType.Text -> false
        HistorySessionType.Vision -> true
        else -> selectedVisionModelPath != null || selectedModel?.isMultimodal == true
    }
    val selectedVisionModelSupportsFiles = selectedVisionModelPath?.let { path ->
        path.endsWith(".task", ignoreCase = true) || path.endsWith(".litertlm", ignoreCase = true) || path.endsWith(".gguf", ignoreCase = true)
    } ?: false
    val runtimeSnapshot = (if (selectedSupportsAttachments) visionRuntimeCapabilities else chatRuntimeCapabilities)
        ?.takeIf { it.modelId == selectedModel?.id && it.artifactRevision == ModelCapabilitySnapshot.revision(selectedModel.bundle, selectedModelPath.orEmpty()) }
    val selectedDeclaredAudio = selectedModel?.supportsAudio == true
    val selectedSupportsAudio = runtimeSnapshot?.capabilities?.supportsAudio == true
    val isCurrentModelReady = if (selectedSupportsAttachments) isVisionModelReady else isModelReady
    val selectedThinkingCapability = if (isCurrentModelReady) runtimeSnapshot?.capabilities?.thinking ?: ThinkingCapability.Unknown
        else selectedModel?.thinkingCapability ?: ThinkingCapability.Unknown
    val isCheckingThinking = selectedModel != null && validationStates[selectedModel.id] in setOf(ModelValidationState.Pending, ModelValidationState.Checking)
    val selectedSupportsThinking = selectedThinkingCapability in setOf(ThinkingCapability.Toggleable, ThinkingCapability.ModelControlled)
    val isCurrentModelResponding = if (selectedSupportsAttachments) {
        isVisionAnalyzing || isVisionStopping
    } else {
        isGenerating
    }
    val isCurrentModelBusy = if (selectedSupportsAttachments) {
        isVisionInitializing || isVisionAnalyzing || isVisionStopping
    } else {
        isInitializing || isGenerating || modelLoadState is ModelLoadState.Loading || modelLoadState is ModelLoadState.Stopping || modelLoadState is ModelLoadState.ShutdownStalled
    }
    val hasVisionAttachment = (selectedBitmap != null && selectedBitmap?.isRecycled == false) ||
        selectedFileUri != null ||
        selectedAudioUri != null ||
        selectedAudioBytes?.isNotEmpty() == true
    val canEditDraft = !isVisionStopping
    val textMessages = chatViewModel.messages
    val visionMessages = visionViewModel.messages
    val textSourceIds by remember(textMessages) { derivedStateOf {
        com.shounak.localmeshai.rag.ChatDocumentScope.ids(textMessages.map { it.isUser to it.ragDocumentId })
    } }
    val visionSourceIds by remember(visionMessages) { derivedStateOf {
        com.shounak.localmeshai.rag.ChatDocumentScope.ids(visionMessages.map { it.isUser to it.ragDocumentId })
    } }
    val effectiveSupportsAttachments = when {
        activeHistoryChatType == HistorySessionType.Vision -> true
        activeHistoryChatType == HistorySessionType.Text -> false
        selectedModel?.isMultimodal == true -> true
        selectedModel != null && !selectedModel.isMultimodal -> false
        else -> visionMessages.isNotEmpty() && textMessages.isEmpty()
    }
    val hasTextHistory = textMessages.isNotEmpty() || chatViewModel.chatSessions.isNotEmpty()
    val hasVisionHistory = visionMessages.isNotEmpty() || visionViewModel.imageChatSessions.isNotEmpty()
    val activeMessageCount = if (effectiveSupportsAttachments) visionMessages.size else textMessages.size
    val activeError = if (effectiveSupportsAttachments) visionError else error
    val audioRecordLimitMs = 30_000L

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
        scope.launch(Dispatchers.IO) {
            try {
                val bmp = loadBitmap(context, uri)
                withContext(Dispatchers.Main) {
                    selectedBitmap = bmp
                    selectedFileUri = null
                    selectedFileName = null
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    chatViewModel.setDraftText(textState.ifBlank { "Failed to load image: ${e.message ?: "corrupted or unsupported format"}" })
                }
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        selectedFileUri = uri
        selectedFileName = getFileName(context, uri)
        selectedBitmap = null
    }
    val audioFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isDecodingAudioFile = true
            try {
                val decoded = AudioUtils.decodeAudioToPcm16Wav(context, uri, maxDurationMs = audioRecordLimitMs, rejectLongInput = true)
                selectedAudioUri = decoded.cacheUri
                selectedAudioBytes = decoded.wavBytes
                selectedAudioName = decoded.label
                selectedBitmap = null
                selectedFileUri = null
                selectedFileName = null
            } catch (e: Throwable) {
                android.util.Log.e("ChatScreen", "Failed to decode audio file", e)
                Toast.makeText(context, "Could not process audio: ${e.localizedMessage ?: "unsupported format"}", Toast.LENGTH_LONG).show()
            } finally {
                isDecodingAudioFile = false
            }
        }
    }
    var isIngestingDocument by remember { mutableStateOf(false) }
    DisposableEffect(selectedAudioUri) {
        val file = selectedAudioUri?.takeIf { it.scheme == "file" }?.path?.let(::File)
        val lease = file?.let(com.shounak.localmeshai.utils.ActiveInputFiles::protect)
        onDispose { lease?.close() }
    }
    LaunchedEffect(selectedBitmap, selectedSupportsAttachments) {
        visionViewModel.stageImageAttachment(selectedBitmap.takeIf { selectedSupportsAttachments })
    }

    LaunchedEffect(selectedFileUri, selectedFileName, selectedSupportsAttachments) {
        val uri = selectedFileUri
        if (uri == null) {
            chatViewModel.cancelStagedDocument()
            visionViewModel.cancelStagedDocument()
        } else if (selectedSupportsAttachments) {
            chatViewModel.cancelStagedDocument()
            visionViewModel.stageDocumentAttachment(uri, selectedFileName ?: "document")
        } else {
            visionViewModel.cancelStagedDocument()
            chatViewModel.stageDocumentAttachment(uri, selectedFileName ?: "document")
        }
    }

    var attachmentMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var composerBounds by remember { mutableStateOf(Rect.Zero) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun updateComposerBounds() {
        val root = rootCoordinates
        val card = cardCoordinates
        if (root != null && card != null && root.isAttached && card.isAttached) {
            composerBounds = root.localBoundingBoxOf(card)
        }
    }

    BackHandler(enabled = attachmentMenuExpanded) {
        attachmentMenuExpanded = false
    }
    fun resetRecordingMeters() {
        recordingInputLevel = 0f
        recordingSpeechActivity = 0f
        recordingElapsedMs = 0L
        recordingLoudnessHistory = List(32) { 0.08f }
        recordingController = null
    }

    fun cancelMicRecording() {
        recordingController?.cancel()
        recordingJob?.cancel()
        recordingJob = null
        isRecordingAudio = false
        isRecordingPaused = false
        resetRecordingMeters()
    }

    fun stopAndSaveMicRecording() {
        recordingController?.stop()
        isRecordingPaused = false
    }

    fun togglePauseMicRecording() {
        val paused = recordingController?.togglePause() ?: false
        isRecordingPaused = paused
    }

    fun startMicRecording() {
        if (!selectedSupportsAudio || isRecordingAudio) return
        recordingJob?.cancel()
        val controller = MicRecordingController()
        recordingController = controller
        isRecordingPaused = false
        recordingJob = scope.launch {
            isRecordingAudio = true
            selectedAudioUri = null
            selectedAudioName = null
            selectedAudioBytes = null
            resetRecordingMeters()
            recordingController = controller
            try {
                val recording = recordPcm16Mono(
                    context = context,
                    controller = controller,
                    durationMs = audioRecordLimitMs,
                    onLevel = { inputLevel, speechActivity, elapsedMs ->
                        recordingInputLevel = inputLevel
                        recordingSpeechActivity = speechActivity
                        recordingElapsedMs = elapsedMs
                        recordingLoudnessHistory = (recordingLoudnessHistory.drop(1) + inputLevel)
                    }
                )
                selectedAudioBytes = recording.wavBytes
                selectedAudioUri = recording.cacheUri
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
                isRecordingPaused = false
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
        val filePath = cameraTempFilePath
        val targetFile = filePath?.let { File(it) }
        if (success && targetFile != null && targetFile.exists()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        targetFile
                    )
                    val bmp = loadBitmap(context, uri)
                    withContext(Dispatchers.Main) {
                        selectedBitmap = bmp
                        selectedFileUri = null
                        selectedFileName = null
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        chatViewModel.setDraftText(textState.ifBlank { "Failed to load photo: ${e.message ?: "corrupted image"}" })
                    }
                } finally {
                    com.shounak.localmeshai.utils.ActiveInputFiles.release(targetFile)
                    targetFile.delete()
                    withContext(Dispatchers.Main) {
                        cameraTempFilePath = null
                    }
                }
            }
        } else {
            if (targetFile != null) {
                scope.launch(Dispatchers.IO) {
                    com.shounak.localmeshai.utils.ActiveInputFiles.release(targetFile)
                    targetFile.delete()
                    withContext(Dispatchers.Main) {
                        cameraTempFilePath = null
                    }
                }
            } else {
                cameraTempFilePath = null
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

    val visibleTextModelPath = selectedModelPath.takeUnless { effectiveSupportsAttachments }
    val visibleVisionModelPath = selectedModelPath.takeIf { effectiveSupportsAttachments }
    LaunchedEffect(visibleTextModelPath, selectedModel?.id, selectedUnsafeOverride, selectedRetryRevision) {
        val shouldLoad = chatViewModel.automaticModelSelection.shouldLoad(
            visibleTextModelPath, selectedModel?.id, selectedUnsafeOverride, selectedRetryRevision)
        if (visibleTextModelPath == null) {
            chatViewModel.uninitializeModel()
        } else if (shouldLoad) {
            visibleTextModelPath.let { path ->
                chatViewModel.initModel(
                    path = path,
                    modelId = selectedModel?.id ?: "",
                    modelName = selectedModel?.name ?: "",
                    modelSize = selectedModel?.size ?: "",
                    contextWindowTokens = selectedModel?.effectiveContextWindowTokens,
                    supportsThinkingMode = selectedSupportsThinking,
                    allowUnsafeOverride = selectedUnsafeOverride,
                    bundle = selectedModel?.bundle
                )
            }
        }
    }

    LaunchedEffect(visibleVisionModelPath, selectedModel?.id, selectedUnsafeOverride, selectedRetryRevision) {
        val shouldLoad = visionViewModel.automaticModelSelection.shouldLoad(
            visibleVisionModelPath, selectedModel?.id, selectedUnsafeOverride, selectedRetryRevision)
        if (visibleVisionModelPath == null) {
            visionViewModel.uninitializeModel()
        } else if (shouldLoad) {
            visibleVisionModelPath.let { path ->
                visionViewModel.initModel(
                    path = path,
                    modelId = selectedModel?.id ?: "",
                    modelName = selectedModel?.name ?: "",
                    modelSize = selectedModel?.size ?: "",
                    contextWindowTokens = selectedModel?.effectiveContextWindowTokens,
                    supportsAudioInput = selectedDeclaredAudio,
                    supportsThinkingMode = selectedSupportsThinking,
                    allowUnsafeOverride = selectedUnsafeOverride,
                    bundle = selectedModel?.bundle
                )
            }
        }
    }

    LaunchedEffect(selectedModel?.id, selectedThinkingCapability, thinkingMode) {
        if (selectedThinkingCapability == ThinkingCapability.Unsupported && thinkingMode) {
            chatViewModel.setThinkingMode(false)
            visionViewModel.setThinkingMode(false)
        }
    }

    val scrollController = retainedScrollController ?: com.shounak.localmeshai.ui.models.rememberChatScrollController(listState, scope)
    DisposableEffect(scrollController) {
        onDispose { scrollController.cancelPendingScroll() }
    }
    val effectiveSessionId = if (effectiveSupportsAttachments) visionCurrentSessionId else currentSessionId
    val currentModeKey = if (effectiveSupportsAttachments) "vision" else "text"

    LaunchedEffect(effectiveSessionId, currentModeKey) {
        scrollController.bindConversation(effectiveSessionId, currentModeKey)
    }

    LaunchedEffect(listState) {
        scrollController.observeUserInteractions()
    }

    LaunchedEffect(listState) {
        scrollController.observeScrollPosition()
    }

    // Only auto-scroll when a new message is added if auto-follow is active
    LaunchedEffect(activeMessageCount, effectiveSupportsAttachments, effectiveSessionId) {
        if (activeMessageCount > 0) {
            scrollController.onNewMessageAdded(activeMessageCount)
        }
    }

    val isAnyStreaming = if (effectiveSupportsAttachments) isVisionAnalyzing else isGenerating
    val activeChatReqId by chatViewModel.activeRequestIdState.collectAsState()
    val activeVisionReqId by visionViewModel.activeRequestIdState.collectAsState()

    // Follow streaming content growth coalesced to at most once every 100ms
    LaunchedEffect(isAnyStreaming) {
        scrollController.followStreaming(isAnyStreaming) {
            if (effectiveSupportsAttachments) visionMessages.size else textMessages.size
        }
    }

    // Terminal layout adjustment: once generation ends, yield one frame for rich presentation layout then align
    LaunchedEffect(isAnyStreaming) {
        if (!isAnyStreaming) {
            scrollController.onStreamingEnded {
                if (effectiveSupportsAttachments) visionMessages.size else textMessages.size
            }
        }
    }

    if (showHistory) {
        ChatHistoryDialog(
            textSessions = chatViewModel.chatSessions,
            visionSessions = visionViewModel.imageChatSessions,
            currentTextSessionId = currentSessionId,
            currentVisionSessionId = visionCurrentSessionId,
            isVisionModeActive = effectiveSupportsAttachments,
            onSelectTextSession = { sessionId ->
                attachmentMenuExpanded = false
                activeHistoryChatType = HistorySessionType.Text
                chatViewModel.selectChatSession(sessionId)
                chatViewModel.clearDraftText()
                selectedBitmap = null
                selectedFileUri = null
                selectedFileName = null
                selectedAudioUri = null
                selectedAudioName = null
                selectedAudioBytes = null
                if (selectedModel?.isMultimodal == true) {
                    val preferredTextModel = downloadedChatModels.firstOrNull { !it.isMultimodal && it.status == ModelStatus.Available }
                    preferredTextModel?.let { model ->
                        mainViewModel.selectTextModel(model.localPath ?: model.id)
                    }
                }
                showHistory = false
            },
            onSelectVisionSession = { sessionId ->
                attachmentMenuExpanded = false
                activeHistoryChatType = HistorySessionType.Vision
                visionViewModel.selectChatSession(sessionId)
                chatViewModel.clearDraftText()
                selectedBitmap = null
                selectedFileUri = null
                selectedFileName = null
                selectedAudioUri = null
                selectedAudioName = null
                selectedAudioBytes = null
                if (selectedModel?.isMultimodal != true) {
                    val preferredVisionModel = downloadedChatModels.firstOrNull { it.isMultimodal && it.status == ModelStatus.Available }
                    preferredVisionModel?.let { model ->
                        mainViewModel.selectVisionModel(model.localPath ?: model.id)
                    }
                }
                showHistory = false
            },
            onNewChat = {
                cancelMicRecording()
                attachmentMenuExpanded = false
                activeHistoryChatType = null
                visionViewModel.startNewChat()
                chatViewModel.startNewChat()
                chatViewModel.clearDraftText()
                selectedBitmap = null
                selectedFileUri = null
                selectedFileName = null
                selectedAudioUri?.path?.let { p ->
                    val f = File(p)
                    if (f.exists() && f.parentFile?.name == "multimodal_inputs") {
                        runCatching { f.delete() }
                    }
                }
                selectedAudioUri = null
                selectedAudioName = null
                selectedAudioBytes = null
                cameraTempFilePath?.let { path ->
                    val file = File(path)
                    com.shounak.localmeshai.utils.ActiveInputFiles.release(file)
                    file.delete()
                    cameraTempFilePath = null
                }
                showHistory = false
            },
            onDeleteTextSession = chatViewModel::deleteChatSession,
            onDeleteVisionSession = visionViewModel::deleteChatSession,
            onClearAll = { showClearConfirm = true },
            onDismiss = { showHistory = false },
            hazeState = hazeState,
            onSettings = if (appSettingsData.enableAutoHideBottomBar) ({ mainViewModel.openSettings(false) }) else null
        )
    }

    if (showClearConfirm) {
        com.shounak.localmeshai.features.GlassActionDialog(
            hazeState = hazeState, onDismiss = { showClearConfirm = false },
            title = "Clear all history?", confirmLabel = "Clear all", destructive = true,
            onConfirm = {
                chatViewModel.clearHistory()
                visionViewModel.clearHistory()
                showClearConfirm = false
                showHistory = false
            }
        ) { Text("This will permanently delete all chat sessions and their uploaded media. This cannot be undone.") }
    }

    val activeProfile = remember(workspaceRevision, selectedModel?.id) { workspacePreferences.profile(selectedModel?.id.orEmpty()) }
    val initializedSnapshot = if (effectiveSupportsAttachments) visionRuntimeCapabilities else chatRuntimeCapabilities
    val contextLimit = initializedSnapshot?.contextTokensFor(
        selectedModel?.id.orEmpty(),
        ModelCapabilitySnapshot.revision(selectedModel?.bundle, selectedModelPath.orEmpty()),
        ready = if (effectiveSupportsAttachments) isVisionModelReady && !isVisionInitializing && !isVisionStopping
            else isModelReady && !isInitializing && !isChatStopping
    )
    val contextTail = if (effectiveSupportsAttachments) visionMessages.lastOrNull()?.text.orEmpty() else textMessages.lastOrNull()?.text.orEmpty()
    val contextBudget = remember(effectiveSessionId, effectiveSupportsAttachments, activeProfile, contextLimit, activeMessageCount, contextTail.length / 128, textState) {
        com.shounak.localmeshai.features.ContextBudgets.summarize(
            if (effectiveSupportsAttachments) visionMessages.map { it.text } else textMessages.map { it.text },
            textState, activeProfile.systemPrompt, contextLimit ?: 512)
    }

    val showContextIndicator = contextLimit != null && selectedModel != null

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
            // A single inset follows the IME animation and never falls below the
            // floating navigation bar's resting space. Do not add dock padding too.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets(bottom = bottomContentInset)))
            .onGloballyPositioned {
                rootCoordinates = it
                updateComposerBounds()
            }
            .pointerInput(attachmentMenuExpanded) {
                if (!attachmentMenuExpanded) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val isTouchDown = event.type == PointerEventType.Press ||
                            event.changes.any { it.pressed && !it.previousPressed }
                        if (isTouchDown) {
                            val change = event.changes.firstOrNull() ?: continue
                            updateComposerBounds()
                            if (composerBounds != Rect.Zero && !composerBounds.contains(change.position)) {
                                attachmentMenuExpanded = false
                                change.consume()
                            }
                        }
                    }
                }
            },
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
                validationStates = validationStates,
                selectedPath = selectedModelPath,
                hasHistory = hasTextHistory || hasVisionHistory,
                onSelectModel = { model ->
                    attachmentMenuExpanded = false
                    val isVisionTarget = model.isMultimodal
                    activeHistoryChatType = null
                    val target = model.localPath ?: model.id
                    if (isVisionTarget) {
                        mainViewModel.selectVisionModel(target)
                    } else {
                        mainViewModel.selectTextModel(target)
                    }
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                    selectedAudioUri = null
                    selectedAudioName = null
                    selectedAudioBytes = null
                },
                onTryAnyway = { model ->
                    attachmentMenuExpanded = false
                    mainViewModel.tryModelAnyway(model.id)
                },
                onNewChat = {
                    cancelMicRecording()
                    attachmentMenuExpanded = false
                    activeHistoryChatType = null
                    visionViewModel.startNewChat()
                    chatViewModel.startNewChat()
                    chatViewModel.clearDraftText()
                    selectedBitmap = null
                    selectedFileUri = null
                    selectedFileName = null
                    selectedAudioUri = null
                    selectedAudioName = null
                    selectedAudioBytes = null
                },
                onHistory = { showHistory = true },
                onSettings = if (appSettingsData.enableAutoHideBottomBar) ({ mainViewModel.openSettings(false) }) else null,
                hazeState = hazeState
            )
        }

        if (selectedModelPath != null && appSettingsData.enableTelemetryBar) {
            val guards by produceState<Pair<Float?, Long?>>(
                initialValue = null to null,
                key1 = listOf(selectedModel?.id, isModelReady, isVisionModelReady,
                    appSettingsData.showThermalGuard, appSettingsData.showRamGuard)
            ) {
                while (true) {
                    value = withContext(Dispatchers.IO) {
                        (if (appSettingsData.showThermalGuard) DeviceUtils.getBatteryTemperatureCelsius(context) else null) to
                            (if (appSettingsData.showRamGuard) DeviceUtils.getAvailableRamMb(context) else null)
                    }
                    delay(5_000L)
                }
            }
            val (batteryTempC, availableRamMb) = guards
            val modelAccentColor = remember(selectedModel?.id, appSettingsData.enableDynamicThemes) {
                if (appSettingsData.enableDynamicThemes) ModelTheme.getAccentColor(selectedModel?.id) else Color(0xFF3B82F6)
            }

            val activeBackendFromVm = if (effectiveSupportsAttachments) visionActiveBackend else chatActiveBackend
            val effectiveBackend = activeBackendFromVm ?: selectedModel?.backend ?: "Local GPU/CPU"

            val latestMessageMetrics = if (effectiveSupportsAttachments) {
                visionMessages.lastOrNull { !it.isUser && it.metrics != null }?.metrics
            } else {
                textMessages.lastOrNull { !it.isUser && it.metrics != null }?.metrics
            }

            val effectiveTokensPerSecond = when {
                effectiveSupportsAttachments && visionTokensPerSecond > 0f -> visionTokensPerSecond
                !effectiveSupportsAttachments && chatTokensPerSecond > 0f -> chatTokensPerSecond
                latestMessageMetrics?.decodeThroughputTokPerSec != null && latestMessageMetrics.decodeThroughputTokPerSec > 0.0 -> latestMessageMetrics.decodeThroughputTokPerSec.toFloat()
                effectiveSupportsAttachments -> visionTokensPerSecond
                else -> chatTokensPerSecond
            }

            val effectiveLastInferenceMs = when {
                effectiveSupportsAttachments && visionLastInferenceMs > 0L -> visionLastInferenceMs
                !effectiveSupportsAttachments && chatLastInferenceMs > 0L -> chatLastInferenceMs
                latestMessageMetrics?.decodeDurationMs != null && latestMessageMetrics.decodeDurationMs > 0L -> latestMessageMetrics.decodeDurationMs
                effectiveSupportsAttachments -> visionLastInferenceMs
                else -> chatLastInferenceMs
            }

            val runtimeStatus = if (effectiveSupportsAttachments) {
                when {
                    selectedVisionModelPath == null -> "Not loaded"
                    isVisionStopping -> "Stopping"
                    isVisionInitializing -> "Preparing…"
                    !isVisionModelReady && (activeError != null || visionError != null) -> "Load failed"
                    isVisionModelReady -> "Ready"
                    else -> "Not loaded"
                }
            } else {
                when {
                    selectedTextModelPath == null -> "Not loaded"
                    isChatStopping -> "Stopping"
                    isInitializing || modelLoadState is ModelLoadState.Loading -> "Preparing…"
                    modelLoadState is ModelLoadState.Failed || (!isModelReady && activeError != null) -> "Load failed"
                    isModelReady -> "Ready"
                    else -> "Not loaded"
                }
            }

            InferenceTelemetryBar(
                tokensPerSecond = effectiveTokensPerSecond,
                lastInferenceMs = effectiveLastInferenceMs,
                runtimeStatus = runtimeStatus,
                activeBackend = effectiveBackend,
                batteryTempC = batteryTempC,
                availableRamMb = availableRamMb,
                accentColor = modelAccentColor,
                contextIndicator = if (showContextIndicator) {
                    { com.shounak.localmeshai.features.ContextUsageIndicator(contextBudget) }
                } else null,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }


        Spacer(modifier = Modifier.height(4.dp))

                var selectedPersonaId by rememberSaveable { mutableStateOf("default") }
                val modelAccentColor = remember(selectedModel?.id, appSettingsData.enableDynamicThemes) {
                    if (appSettingsData.enableDynamicThemes) ModelTheme.getAccentColor(selectedModel?.id) else Color(0xFF3B82F6)
                }
                val hasMessagesInChat = if (effectiveSupportsAttachments) visionMessages.isNotEmpty() else textMessages.isNotEmpty()

        val isNewChat = activeMessageCount == 0 && effectiveSessionId == null &&
            (effectiveSupportsAttachments || pendingSessionId == null)
        val hideComposerShortcuts = isKeyboardOpen && attachmentMenuExpanded
        val showFloatingControls = !hasMessagesInChat && (isNewChat || appSettingsData.enablePersonaPresets ||
            (appSettingsData.enableSuggestionPills && textState.isBlank() && selectedModelPath != null && !isCurrentModelBusy))
        var floatingDockHeight by remember { mutableStateOf(220.dp) }
        val floatingDockDensity = LocalDensity.current
        val floatingBottomInset = floatingDockHeight + 8.dp

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

                if (mainViewModel.retryingModelIds.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Checking model for retry…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Initialising / Generating banner - placed inside Column above message Box so it never overlaps chat items
                androidx.compose.animation.AnimatedVisibility(
                    visible = isCurrentModelBusy,
                    enter = androidx.compose.animation.fadeIn(tween(180)) +
                        androidx.compose.animation.expandVertically(
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f)
                        ),
                    exit = androidx.compose.animation.fadeOut(tween(130)) +
                        androidx.compose.animation.shrinkVertically(
                            animationSpec = spring(dampingRatio = 0.92f, stiffness = 620f)
                        ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val activeState = modelLoadState
                        val progressFraction = (activeState as? ModelLoadState.Loading)?.progressFraction
                        if (progressFraction != null && progressFraction > 0f) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val loadingText = when {
                                selectedSupportsAttachments && isVisionInitializing -> "Initialising multimodal model safely…"
                                selectedSupportsAttachments && isVisionAnalyzing -> "Reading input…"
                                selectedSupportsAttachments && isVisionStopping -> "Stopping multimodal model…"
                                selectedSupportsAttachments -> "Initialising multimodal model safely…"
                                activeState is ModelLoadState.Loading -> {
                                    val stageName = when (val st = activeState.stage) {
                                        ModelLoadStage.WaitingForRuntime -> "Waiting for runtime"
                                        ModelLoadStage.VerifyingFile -> "Verifying model file"
                                        ModelLoadStage.General -> "Initialising model"
                                        ModelLoadStage.CpuLoading -> "Loading CPU model"
                                        ModelLoadStage.GpuLoading -> "Loading GPU model"
                                        is ModelLoadStage.CpuReference -> "Probing CPU reference (${st.promptIndex + 1}/3, pos ${st.position}/8)"
                                        is ModelLoadStage.GpuProbe -> "Probing GPU backend (${st.promptIndex + 1}/3, pos ${st.position}/8)"
                                        is ModelLoadStage.ContextPreparation -> if (st.backend.isNotBlank()) "Preparing context on ${st.backend}" else "Preparing context"
                                    }
                                    val sec = activeState.elapsedMs / 1000
                                    val pct = progressFraction?.let { " ${(it * 100).toInt()}%" } ?: ""
                                    if (activeState.isStalledWarning) {
                                        "$stageName$pct (${sec}s elapsed - still working)"
                                    } else {
                                        "$stageName$pct (${sec}s)"
                                    }
                                }
                                activeState is ModelLoadState.Stopping -> "Stopping model…"
                                activeState is ModelLoadState.ShutdownStalled -> "Native runtime shutdown stalled. Please restart the app."
                                isInitializing -> "Initialising model safely…"
                                else -> "Generating live response…"
                            }
                            Text(
                                text = loadingText,
                                modifier = Modifier.weight(1f, fill = false),
                                textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activeState is ModelLoadState.ShutdownStalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            if ((!selectedSupportsAttachments && (isInitializing || activeState is ModelLoadState.Loading)) ||
                                (selectedSupportsAttachments && (isVisionInitializing || isVisionAnalyzing))) {
                                TextButton(
                                    onClick = {
                                        if (selectedSupportsAttachments) {
                                            visionViewModel.stopAnalyzing()
                                        } else {
                                            chatViewModel.cancelModelInitialization()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // Pending hold only when messages were not loaded into the list yet.
                // Once textMessages/visionMessages are non-empty, the list branch wins.
                val canShowChatMessages = selectedModelPath != null || currentSessionId != null
                val isPendingChatHold = !effectiveSupportsAttachments &&
                    pendingSessionId != null &&
                    selectedModelPath == null &&
                    textMessages.isEmpty()

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                    when {
                        // 1. Prioritise showing messages if they exist (history with no model active).
                        (effectiveSupportsAttachments && visionMessages.isNotEmpty()) ||
                            (!effectiveSupportsAttachments && textMessages.isNotEmpty()) -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = floatingBottomInset + 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (effectiveSupportsAttachments) {
                                    itemsIndexed(
                                        visionMessages,
                                        key = { _, message -> message.id },
                                        contentType = { _, message ->
                                            if (message.isUser) "vision_user" else "vision_assistant"
                                        }
                                    ) { index, message ->
                                        val isStreaming = !message.isUser && activeVisionReqId != null && message.requestId == activeVisionReqId && message.terminalStatus == null
                                        val scopedPassages = message.ragPassages.filter { it.documentId in visionSourceIds }
                                        VisionChatBubble(
                                            text = message.text,
                                            isUser = message.isUser,
                                            bitmap = message.bitmap,
                                            imagePath = message.imagePath,
                                            audioPath = message.audioPath,
                                            audioName = message.audioName,
                                            audioDurationMs = message.audioDurationMs,
                                            documentName = message.documentName,
                                            documentPath = message.documentPath,
                                            progress = message.progress,
                                            hazeState = hazeState,
                                            isStreaming = isStreaming,
                                            messageId = message.id,
                                            requestId = message.requestId.orEmpty(),
                                            attemptId = message.attemptId.toLong(),
                                            revision = message.revision,
                                            reasoningText = message.reasoningText,
                                            toolUses = message.toolUses,
                                            isThinkingActive = message.isThinkingActive,
                                            responseFormatVersion = message.responseFormatVersion,
                                            terminalStatus = message.terminalStatus,
                                            entryDelayMs = if (index == visionMessages.lastIndex) 25 else 0,
                                            ragPassages = scopedPassages,
                                            onEditPrompt = if (message.isUser && selectedModel != null && !isGenerating && !isVisionAnalyzing && !isInitializing && !isVisionInitializing && !isChatStopping && !isVisionStopping) {
                                                { updated -> visionViewModel.editAndRegenerate(message.id, updated) }
                                            } else null,
                                            onRegenerate = if (!message.isUser && !isStreaming && message.errorMessage == null && selectedModel != null && !isGenerating && !isVisionAnalyzing && !isInitializing && !isVisionInitializing && !isChatStopping && !isVisionStopping) {
                                                val promptId = message.userMessageId ?: visionMessages.take(index).lastOrNull { it.isUser }?.id
                                                promptId?.let { id -> { visionViewModel.editAndRegenerate(id) } }
                                            } else null,
                                            ragSources = scopedPassages.map { it.documentName }.distinct(),
                                            ragChunkCount = message.ragChunkCount,
                                            ragTopMatchPct = message.ragTopMatchPct,
                                            errorMessage = message.errorMessage,
                                            onRetry = if (!message.isUser && index == visionMessages.lastIndex && (message.errorMessage != null || message.terminalStatus == RequestTerminalStatus.Failed || message.text.startsWith("⚠️ Error:"))) {
                                                val userMsgId = message.userMessageId ?: visionMessages.take(index).lastOrNull { it.isUser }?.id
                                                {
                                                    val currentSessId = visionViewModel.currentSessionId.value
                                                    if (currentSessId != null && userMsgId != null) {
                                                        visionViewModel.retryMessage(currentSessId, userMsgId)
                                                    } else {
                                                        visionViewModel.retryLastFailedMessage()
                                                    }
                                                }
                                            } else null,
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
                                        val isStreaming = !message.isUser && activeChatReqId != null && message.requestId == activeChatReqId && message.terminalStatus == null
                                        val scopedPassages = message.ragPassages.filter { it.documentId in textSourceIds }
                                        ChatBubble(
                                            text = message.text,
                                            isUser = message.isUser,
                                            hazeState = hazeState,
                                            progress = message.progress,
                                            isStreaming = isStreaming,
                                            messageId = message.id,
                                            requestId = message.requestId.orEmpty(),
                                            attemptId = message.attemptId.toLong(),
                                            revision = message.revision,
                                            reasoningText = message.reasoningText,
                                            toolUses = message.toolUses,
                                            isThinkingActive = message.isThinkingActive,
                                            responseFormatVersion = message.responseFormatVersion,
                                            terminalStatus = message.terminalStatus,
                                            entryDelayMs = if (index == textMessages.lastIndex) 25 else 0,
                                            ragPassages = scopedPassages,
                                            onEditPrompt = if (message.isUser && selectedModel != null && !isGenerating && !isVisionAnalyzing && !isInitializing && !isVisionInitializing && !isChatStopping && !isVisionStopping) {
                                                { updated -> chatViewModel.editAndRegenerate(message.id, updated) }
                                            } else null,
                                            onRegenerate = if (!message.isUser && !isStreaming && message.errorMessage == null && selectedModel != null && !isGenerating && !isVisionAnalyzing && !isInitializing && !isVisionInitializing && !isChatStopping && !isVisionStopping) {
                                                val promptId = message.userMessageId ?: textMessages.take(index).lastOrNull { it.isUser }?.id
                                                promptId?.let { id -> { chatViewModel.editAndRegenerate(id) } }
                                            } else null,
                                            ragSources = scopedPassages.map { it.documentName }.distinct(),
                                            ragChunkCount = message.ragChunkCount,
                                            ragTopMatchPct = message.ragTopMatchPct,
                                            documentName = message.documentName,
                                            documentPath = message.documentPath,
                                            errorMessage = message.errorMessage,
                                            onRetry = if (!message.isUser && index == textMessages.lastIndex && (message.errorMessage != null || message.terminalStatus == RequestTerminalStatus.Failed || message.text.startsWith("⚠️ Error:"))) {
                                                val userMsgId = message.userMessageId ?: textMessages.take(index).lastOrNull { it.isUser }?.id
                                                {
                                                    val currentSessId = chatViewModel.currentSessionId.value
                                                    if (currentSessId != null && userMsgId != null) {
                                                        chatViewModel.retryMessage(currentSessId, userMsgId)
                                                    } else {
                                                        chatViewModel.retryLastFailedMessage()
                                                    }
                                                }
                                            } else null,
                                            onFullscreenClick = { fullscreenMessageIndex = index }
                                        )
                                    }
                                }
                                item(key = "bottom_anchor_${effectiveSessionId}_$currentModeKey") {
                                    Spacer(modifier = Modifier.height(1.dp))
                                }
                            }
                        }
                        // 2. Fallbacks and empty states
                        isPendingChatHold -> {
                            EmptyState(
                                title = "Chat loaded",
                                subtitle = "Initialise a model to continue",
                                hazeState = hazeState,
                                bottomInset = floatingBottomInset
                            )
                        }
                        selectedModelPath == null -> {
                            EmptyState(
                                title = "No model selected",
                                subtitle = if (downloadedChatModels.none { it.status == ModelStatus.Available }) {
                                    "Open Models and download a phone-ready model."
                                } else {
                                    "Choose a model above to begin."
                                },
                                hazeState = hazeState,
                                bottomInset = floatingBottomInset
                            )
                        }
                        !canShowChatMessages -> {
                            EmptyState(
                                title = if (isCurrentModelBusy) "Initialising model" else "Model is not ready",
                                subtitle = if (isCurrentModelBusy) "This usually takes a moment." else "Send a message to load the selected model.",
                                hazeState = hazeState,
                                bottomInset = floatingBottomInset
                            )
                        }
                        effectiveSupportsAttachments && visionMessages.isEmpty() -> {
                            EmptyState(
                                title = "Ask with images or files",
                                subtitle = "Use the + inside the chat box to add camera, photos, or files.",
                                hazeState = hazeState,
                                bottomInset = floatingBottomInset
                            )
                        }
                        else -> {
                            // Model ready / session open, but chat is empty — starter prompts
                            StarterPrompts(
                                hazeState = hazeState,
                                bottomInset = floatingBottomInset,
                                onPromptSelected = { prompt ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    chatViewModel.sendMessage(prompt)
                                }
                            )
                        }
                    }

                    }

                    // ── Stacked scroll FABs (BottomEnd, BoxScope) ─────────────────
                    // Placed here — outside the when{} branches — so that .align()
                    // is called directly inside BoxScope where it is valid.
                    // Both buttons are only meaningful when the message list is shown.
                    val hasMessages = if (effectiveSupportsAttachments) {
                        visionMessages.isNotEmpty()
                    } else {
                        textMessages.isNotEmpty()
                    }
                    if (hasMessages) {
                        ScrollFabCluster(
                            showTop = showScrollTopFab,
                            showBottom = showScrollFab,
                            hazeState = hazeState,
                            onScrollTop = {
                                scrollController.scrollToTop()
                            },
                            onScrollBottom = {
                                val anchorIndex = if (effectiveSupportsAttachments) visionMessages.size else textMessages.size
                                scrollController.scrollToBottom(anchorIndex)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = floatingBottomInset + 8.dp)
                        )
                    }

                    // One transparent dock overlays the scrollable content. Only its individual
                    // controls and the rounded composer paint a surface.
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onSizeChanged { floatingDockHeight = with(floatingDockDensity) { it.height.toDp() } },
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (showFloatingControls) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (isNewChat) {
                                Box(Modifier.padding(horizontal = 12.dp)) {
                                    com.shounak.localmeshai.features.TemporaryChatToggle(
                                        temporary = if (effectiveSupportsAttachments) temporaryVision else temporaryText,
                                        busy = isGenerating || isVisionAnalyzing || isInitializing || isVisionInitializing || isChatStopping || isVisionStopping || isDeletingTextHistory || isDeletingVisionHistory,
                                        hazeState = hazeState,
                                        onTemporaryChange = { enabled ->
                                            val changed = com.shounak.localmeshai.features.ConversationPrivacy.setNewDraftMode(
                                                enabled,
                                                active = if (effectiveSupportsAttachments) visionViewModel::setTemporaryChat else chatViewModel::setTemporaryChat,
                                                other = if (effectiveSupportsAttachments) chatViewModel::setTemporaryChat else visionViewModel::setTemporaryChat)
                                            if (changed) Toast.makeText(context,
                                                if (enabled) "Temporary chat on — history and memories won't be saved"
                                                else "Temporary chat off — this conversation will be saved", Toast.LENGTH_SHORT).show()
                                        })
                                }
                            }
                if (!hideComposerShortcuts && !hasMessagesInChat && appSettingsData.enablePersonaPresets) {
                    SystemPromptPresetsBar(
                        hazeState = hazeState,
                        selectedPersonaId = selectedPersonaId,
                        onSelectPersona = { persona ->
                            selectedPersonaId = persona.id
                            if (persona.systemPrompt.isNotBlank()) {
                                chatViewModel.setDraftText("${persona.systemPrompt}\n\nTask: ")
                            } else {
                                chatViewModel.setDraftText("")
                            }
                        },
                        accentColor = modelAccentColor,
                        modifier = Modifier
                    )
                }

                QuickSuggestionPills(
                    hazeState = hazeState,
                    isVisible = !hideComposerShortcuts && appSettingsData.enableSuggestionPills && !hasMessagesInChat && textState.isBlank() && selectedModelPath != null && !isCurrentModelBusy,
                    onSelectSuggestion = { prompt ->
                        chatViewModel.setDraftText(prompt)
                    },
                    modifier = Modifier
                )

                        }
                    }



                // ── Chat input box with integrated thinking toggle ──
                // Root IME insets keep this floating dock above the keyboard.
                // Blur the content behind the rounded floating composer.
                val composerCorner by animateDpAsState(
                    targetValue = if (isKeyboardOpen) 28.dp else 30.dp,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 520f),
                    label = "chat_composer_corner"
                )
                val composerMaxHeight by animateDpAsState(
                    targetValue = when {
                        isRecordingAudio -> 240.dp
                        hasVisionAttachment && attachmentMenuExpanded -> if (isKeyboardOpen) 460.dp else 560.dp
                        attachmentMenuExpanded -> if (isKeyboardOpen) 390.dp else 460.dp
                        hasVisionAttachment -> if (isKeyboardOpen) 320.dp else 380.dp
                        else -> 280.dp
                    },
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                    label = "chat_composer_max_height"
                )
                val composerTint = MaterialTheme.colorScheme.surfaceContainerHigh
                val composerShape = RoundedCornerShape(composerCorner)
                Box(
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
                        .padding(bottom = if (isKeyboardOpen) 2.dp else 4.dp)
                        .clip(composerShape)
                        // Register the whole panel for hit testing so blank glass areas
                        // cannot activate prompt suggestions underneath. Children keep
                        // handling their own taps, text selection and scrolling.
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .onGloballyPositioned {
                            cardCoordinates = it
                            updateComposerBounds()
                        }
                ) {
                    // Keep the backdrop separate from the text field and animated controls.
                    // Their graphics layers must not become part of the blur effect's bounds.
                    Box(
                        Modifier.matchParentSize()
                            .clip(composerShape)
                            .hazeEffect(state = hazeState, style = HazeStyle(
                                backgroundColor = MaterialTheme.colorScheme.surface,
                                blurRadius = 28.dp,
                                tint = HazeTint(composerTint.copy(alpha = 0.28f)),
                                noiseFactor = 0.04f
                            ))
                            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.48f), composerShape)
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
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
                                    onClick = {
                                        selectedBitmap = null
                                        cameraTempFilePath?.let { path ->
                                            val file = File(path)
                                            com.shounak.localmeshai.utils.ActiveInputFiles.release(file)
                                            file.delete()
                                            cameraTempFilePath = null
                                        }
                                    },
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
                                    Column {
                                        Text(
                                            text = selectedFileName ?: "Attached file",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "RAG Document Knowledge Base",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
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

                        if (isIngestingDocument) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Indexing document into RAG Knowledge Base...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (isDecodingAudioFile) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Converting audio file to 16kHz WAV...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if ((selectedAudioUri != null || selectedAudioBytes?.isNotEmpty() == true) && !isRecordingAudio) {
                            val audioPreviewPath = remember(selectedAudioUri, selectedAudioBytes) {
                                val uriPath = selectedAudioUri?.path
                                if (!uriPath.isNullOrBlank() && File(uriPath).exists()) {
                                    uriPath
                                } else if (selectedAudioBytes != null && selectedAudioBytes!!.isNotEmpty()) {
                                    try {
                                        val cacheDir = File(context.cacheDir, "multimodal_inputs").apply { mkdirs() }
                                        val tempFile = File(cacheDir, "preview_audio_${System.currentTimeMillis()}.wav")
                                        tempFile.writeBytes(selectedAudioBytes!!)
                                        tempFile.absolutePath
                                    } catch (_: Exception) {
                                        null
                                    }
                                } else null
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                            ) {
                                if (!audioPreviewPath.isNullOrBlank()) {
                                    AudioMessagePlayer(
                                        audioPath = audioPreviewPath,
                                        audioName = selectedAudioName ?: "Voice recording",
                                        initialDurationMs = 0L,
                                        isUser = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = 28.dp)
                                    )
                                } else {
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
                                        Column {
                                            Text(
                                                text = selectedAudioName ?: "Attached audio",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Offline Audio",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                LiquidGlassButton(
                                    onClick = {
                                        selectedAudioUri?.path?.let { p ->
                                            val f = File(p)
                                            if (f.exists() && f.parentFile?.name == "multimodal_inputs") {
                                                runCatching { f.delete() }
                                            }
                                        }
                                        selectedAudioUri = null
                                        selectedAudioName = null
                                        selectedAudioBytes = null
                                    },
                                    hazeState = hazeState,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 6.dp, y = (-6).dp)
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

                        if (isRecordingAudio) {
                            RecordingComposerPanel(
                                loudnessHistory = recordingLoudnessHistory,
                                elapsedMs = recordingElapsedMs,
                                limitMs = audioRecordLimitMs,
                                isPaused = isRecordingPaused,
                                onTogglePause = { togglePauseMicRecording() },
                                onCancel = { cancelMicRecording() },
                                onDone = { stopAndSaveMicRecording() }
                            )
                        } else {
                            ChatComposerActions(
                                modifier = if (hideComposerShortcuts) Modifier.weight(1f, fill = false) else Modifier,
                                compactAttachmentMenu = hideComposerShortcuts,
                                thinkingMode = thinkingMode,
                                thinkingCapability = selectedThinkingCapability,
                                isCheckingThinking = isCheckingThinking,
                                canSend = !isDeletingTextHistory && !isDeletingVisionHistory && selectedModelPath != null &&
                                    !isCurrentModelResponding &&
                                    !isDecodingAudioFile &&
                                    (hasVisionAttachment || textState.isNotBlank()),
                                isGenerating = if (effectiveSupportsAttachments) isVisionAnalyzing else (isGenerating || isInitializing),
                                canThink = isCurrentModelReady && runtimeSnapshot != null && selectedSupportsThinking && !isCurrentModelResponding,
                                supportsAttachments = selectedModelPath != null,
                                hasAttachment = hasVisionAttachment,
                                supportsVision = runtimeSnapshot?.capabilities?.supportsVision == true,
                                supportsAudioInput = selectedSupportsAudio,
                                visionDisabledReason = when {
                                    selectedModel == null -> "Select a model"
                                    !isCurrentModelReady -> "Initialize the model to use images"
                                    runtimeSnapshot?.capabilities?.supportsVision != true -> "Image input unavailable in this runtime"
                                    else -> null
                                },
                                hazeState = hazeState,
                                attachmentMenuExpanded = attachmentMenuExpanded,
                                onToggleAttachmentMenu = { attachmentMenuExpanded = it },
                                onToggleThinking = { enabled ->
                                    chatViewModel.setThinkingMode(enabled)
                                    visionViewModel.setThinkingMode(enabled)
                                },
                                onCameraSelect = {
                                    val request = createCameraCaptureRequest(context)
                                    cameraTempFilePath = request.file.absolutePath
                                    cameraLauncher.launch(request.uri)
                                },
                                onPhotosSelect = { imagePicker.launch("image/*") },
                                onAudioSelect = { audioFilePicker.launch(arrayOf("audio/*", "application/ogg")) },
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
                                            selectedModel?.type != ModelType.Vision -> "${selectedModel?.name ?: "Selected model"} is a text-only model. Raw audio input needs a multimodal model with audio encoders, such as Gemma 4 or Gemma 3n."
                                            else -> "${selectedModel.name} does not include audio encoder assets in this downloaded bundle."
                                        }
                                    })
                                },
                                onSend = {
                                    attachmentMenuExpanded = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val documentUri = selectedFileUri
                                    val documentName = selectedFileName ?: "document"
                                    val accepted = if (effectiveSupportsAttachments) {
                                        val reqThinking = ThinkingModeConfig.resolveRequestThinking(selectedThinkingCapability, thinkingMode)
                                        visionViewModel.ask(
                                            bitmap = selectedBitmap,
                                            question = textState.trim(),
                                            fileUri = documentUri,
                                            fileName = if (documentUri != null) documentName else null,
                                            audioUri = selectedAudioUri,
                                            audioName = selectedAudioName,
                                            audioBytes = selectedAudioBytes,
                                            thinkingMode = reqThinking)
                                    } else {
                                        chatViewModel.sendMessage(
                                            text = textState.trim(), documentUri = documentUri,
                                            documentName = if (documentUri != null) documentName else null)
                                    }
                                    if (accepted) {
                                        scrollController.onUserMessageSent()
                                        selectedBitmap = null
                                        selectedFileUri = null
                                        selectedFileName = null
                                        selectedAudioUri = null
                                        selectedAudioName = null
                                        selectedAudioBytes = null
                                        chatViewModel.clearDraftText()
                                    }
                                },
                                onStop = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (effectiveSupportsAttachments) {
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

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? {
    return when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.findLifecycleOwner()
        else -> null
    }
}

private fun ModelInfo?.supportsAudioInputByMetadata(): Boolean {
    val model = this ?: return false
    return model.supportsAudio
}

private fun ModelInfo?.supportsThinkingMode(): Boolean {
    val model = this ?: return false
    return ThinkingModeConfig.resolveThinkingCapability(model) != ThinkingCapability.Unsupported
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

private class MicRecordingController {
    @Volatile
    var isStopRequested: Boolean = false
        private set

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    fun stop() {
        isStopRequested = true
    }

    fun cancel() {
        isCancelled = true
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun togglePause(): Boolean {
        isPaused = !isPaused
        return isPaused
    }
}

private data class RecordedPcmAudio(
    val wavBytes: ByteArray,
    val sampleRateHz: Int,
    val label: String,
    val cacheUri: Uri
)

@SuppressLint("MissingPermission")
private suspend fun recordPcm16Mono(
    context: Context,
    controller: MicRecordingController,
    durationMs: Long = 30_000L,
    onLevel: suspend (inputLevel: Float, speechActivity: Float, elapsedMs: Long) -> Unit = { _, _, _ -> }
): RecordedPcmAudio = withContext(Dispatchers.IO) {
    val recorder = com.shounak.localmeshai.utils.PcmRecorder(context)
    val result = recorder.record(
        maxDurationMs = durationMs,
        isStopRequested = { controller.isStopRequested },
        isCancelled = { controller.isCancelled },
        isPaused = { controller.isPaused },
        onLevel = onLevel
    )
    when (result) {
        is com.shounak.localmeshai.utils.PcmRecordingResult.Success -> {
            val cacheFile = result.cacheFile ?: error("Cache file was not generated")
            val elapsedSeconds = (result.durationMs / 1000L).coerceAtLeast(1L)
            RecordedPcmAudio(
                wavBytes = result.wavBytes,
                sampleRateHz = result.sampleRateHz,
                label = "Voice recording (${elapsedSeconds}s)",
                cacheUri = Uri.fromFile(cacheFile)
            )
        }
        is com.shounak.localmeshai.utils.PcmRecordingResult.Failure -> {
            if (result.errorType == com.shounak.localmeshai.utils.PcmRecorderErrorType.RECORDING_FAILED &&
                result.message.contains("cancelled", ignoreCase = true)
            ) {
                throw CancellationException(result.message)
            }
            val userMsg = com.shounak.localmeshai.utils.AudioUtils.getRecorderErrorMessage(result.errorType)
            throw IllegalStateException(userMsg, result.cause)
        }
    }
}

private fun pcm16RmsLevel(buffer: ByteArray, read: Int): Float {
    return AudioUtils.pcm16RmsLevel(buffer, read)
}

@Composable
private fun StarterPrompts(hazeState: HazeState, bottomInset: Dp = 0.dp, onPromptSelected: (String) -> Unit) {
    val prompts = listOf(
        "💡 Explain quantum computing in simple terms",
        "🧐 What are 5 things I can do to improve my focus?",
        "✍️ Write a short poem about the night sky",
        "📝 Summarise the key ideas of stoic philosophy",
        "📊 Compare Python and Kotlin for mobile development",
        "🌍 What causes the Northern Lights?"
    )
    val chipTint = MaterialTheme.colorScheme.surfaceContainer
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 16.dp + bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "starter_title") {
            Text("Start a private conversation", style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
        }
        item(key = "starter_description") {
            Text("Everything runs on-device. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        items(prompts.size, key = { prompts[it] }) { index ->
            val prompt = prompts[index]
            Box(
                modifier = Modifier.fillMaxWidth()
                    .fluidReveal(delayMillis = index * 45, initialScale = 0.88f,
                        initialYOffset = 10.dp,
                        initialXOffset = if (index % 2 == 0) (-18).dp else 18.dp,
                        initialRotationZ = if (index % 2 == 0) -1.1f else 1.1f)
                    .jellyOnTouch(sensitivity = 1.8f)
                    .glassEffect(hazeState = hazeState, shape = RoundedCornerShape(18.dp),
                        blurRadius = 12.dp, tintColor = chipTint, borderAlpha = 0.25f)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onPromptSelected(prompt.substringAfter(" ")) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(prompt, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ChatComposerActions(
    modifier: Modifier = Modifier,
    compactAttachmentMenu: Boolean = false,
    thinkingMode: Boolean,
    thinkingCapability: ThinkingCapability = ThinkingCapability.Unknown,
    isCheckingThinking: Boolean = false,
    canSend: Boolean,
    isGenerating: Boolean,
    canThink: Boolean,
    supportsAttachments: Boolean,
    hasAttachment: Boolean,
    supportsVision: Boolean,
    supportsAudioInput: Boolean,
    visionDisabledReason: String? = null,
    hazeState: HazeState,
    attachmentMenuExpanded: Boolean,
    onToggleAttachmentMenu: (Boolean) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onCameraSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onAudioSelect: () -> Unit,
    onFilesSelect: () -> Unit,
    onMicRecord: () -> Unit,
    onMicUnavailable: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val sendAnimationScope = rememberCoroutineScope()
    val rocketFlight = remember { Animatable(0f) }
    var isRocketFlying by remember { mutableStateOf(false) }
    val sendAnimationDensity = LocalDensity.current
    val attachmentMenuOpen = attachmentMenuExpanded
    LaunchedEffect(supportsAttachments) {
        if (!supportsAttachments) {
            onToggleAttachmentMenu(false)
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
    val isToggleable = thinkingCapability == ThinkingCapability.Toggleable
    val isModelControlled = thinkingCapability == ThinkingCapability.ModelControlled
    val isUnknown = thinkingCapability == ThinkingCapability.Unknown
    val isUnsupported = thinkingCapability == ThinkingCapability.Unsupported

    val thinkButtonEnabled = canThink && isToggleable
    val thinkActive = when {
        isModelControlled -> true
        isToggleable -> thinkingMode
        else -> false
    }
    val thinkButtonText = when {
        isModelControlled -> "Thinking (model controlled)"
        isUnknown || isUnsupported -> "Think"
        thinkActive -> "Think on"
        else -> "Think off"
    }
    val thinkContentColor = when {
        isUnknown || isUnsupported -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        thinkActive -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = attachmentMenuOpen,
            modifier = if (compactAttachmentMenu) Modifier.weight(1f, fill = false) else Modifier,
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
            Box(Modifier.verticalScroll(rememberScrollState())) {
            AttachmentInlineMenu(
                hazeState = hazeState,
                supportsVision = supportsVision,
                supportsAudio = supportsAudioInput,
                visionDisabledReason = visionDisabledReason,
                onCameraSelect = {
                    onToggleAttachmentMenu(false)
                    onCameraSelect()
                },
                onPhotosSelect = {
                    onToggleAttachmentMenu(false)
                    onPhotosSelect()
                },
                onAudioSelect = {
                    onToggleAttachmentMenu(false)
                    onAudioSelect()
                },
                onFilesSelect = {
                    onToggleAttachmentMenu(false)
                    onFilesSelect()
                }
            )
        }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                LiquidGlassButton(
                    onClick = {
                        onToggleAttachmentMenu(!attachmentMenuExpanded)
                    },
                    hazeState = hazeState,
                    enabled = supportsAttachments,
                    modifier = Modifier
                        .size(42.dp)
                        .dropdownTriggerBounce(attachmentMenuOpen),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 14.dp),
                    tintColor = when {
                        hasAttachment || attachmentMenuOpen -> MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (attachmentMenuOpen) {
                            "Close attachments"
                        } else if (supportsAttachments) {
                            "Add image, audio, or file"
                        } else {
                            "Selected model does not support attachments"
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(attachmentIconRotation),
                        tint = if (supportsAttachments) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                    )
                }
            }

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
                onClick = {
                    if (thinkButtonEnabled) {
                        onToggleThinking(!thinkingMode)
                    }
                },
                hazeState = hazeState,
                enabled = thinkButtonEnabled,
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(min = 112.dp)
                    .dropdownTriggerBounce(thinkActive),
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 14.dp),
                tintColor = when {
                    thinkActive -> MaterialTheme.colorScheme.primaryContainer
                    thinkButtonEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = when {
                        isModelControlled -> "Model-controlled thinking"
                        isUnknown && isCheckingThinking -> "Checking thinking capability"
                        isUnknown -> "Thinking capability has not been verified"
                        isUnsupported -> "Selected model does not support thinking mode"
                        thinkActive -> "Disable thinking mode"
                        else -> "Enable thinking mode"
                    },
                    tint = thinkContentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = thinkButtonText,
                    style = MaterialTheme.typography.labelMedium,
                    color = thinkContentColor,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.weight(1f))

            LiquidGlassButton(
                onClick = {
                    when {
                        isGenerating -> onStop()
                        canSend -> {
                            onSend()
                            sendAnimationScope.launch {
                                rocketFlight.snapTo(0f)
                                rocketFlight.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(durationMillis = 190)
                                )
                                rocketFlight.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    }
                },
                hazeState = hazeState,
                enabled = isGenerating || canSend,
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
                    targetState = isGenerating,
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
    supportsVision: Boolean,
    supportsAudio: Boolean,
    visionDisabledReason: String? = null,
    onCameraSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onAudioSelect: () -> Unit,
    onFilesSelect: () -> Unit
) {
    val menuShape = RoundedCornerShape(14.dp)
    val menuTint = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .widthIn(min = 196.dp, max = 250.dp)
            .fluidReveal(initialScale = 0.90f, initialYOffset = 8.dp)
            .clip(menuShape)
    ) {
        Box(
            Modifier.matchParentSize()
                .clip(menuShape)
                .hazeEffect(state = hazeState, style = HazeStyle(
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    blurRadius = 24.dp,
                    tint = HazeTint(menuTint.copy(alpha = 0.28f)),
                    noiseFactor = 0.04f
                ))
                .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), menuShape)
        )
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val isVisionEnabled = supportsVision && visionDisabledReason == null
            listOf(
                AttachmentMenuSpec(
                    icon = Icons.Default.CameraAlt,
                    title = "Camera",
                    subtitle = visionDisabledReason,
                    enabled = isVisionEnabled,
                    onClick = onCameraSelect
                ),
                AttachmentMenuSpec(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Photos",
                    subtitle = visionDisabledReason,
                    enabled = isVisionEnabled,
                    onClick = onPhotosSelect
                ),
                AttachmentMenuSpec(
                    icon = Icons.Default.Audiotrack,
                    title = "Audio file",
                    subtitle = if (supportsAudio) "WAV, MP3, AAC, M4A" else "Audio-capable model required",
                    enabled = supportsAudio,
                    onClick = onAudioSelect
                ),
                AttachmentMenuSpec(
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    title = "Files (RAG)",
                    subtitle = "PDF, DOCX, TXT, Code",
                    enabled = true,
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
private fun RecordingComposerPanel(
    loudnessHistory: List<Float>,
    elapsedMs: Long,
    limitMs: Long,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val currentSeconds = (elapsedMs / 1000L).coerceAtMost(limitMs / 1000L)
    val totalSeconds = limitMs / 1000L
    val timerText = String.format(Locale.US, "%02d:%02d / %02d:%02d", currentSeconds / 60, currentSeconds % 60, totalSeconds / 60, totalSeconds % 60)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status header: Pulsing recording dot + Live label + Digital monospace timer
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        if (!isPaused) {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                    }
                    .clip(CircleShape)
                    .background(if (isPaused) Color(0xFFFFA000) else MaterialTheme.colorScheme.error)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isPaused) "Recording paused" else "Recording audio…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isPaused) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = timerText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (isPaused) Color(0xFFFFA000) else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }

        // Live Dynamic Loudness Bars Canvas
        AudioLoudnessBarsCanvas(
            loudnessHistory = if (isPaused) loudnessHistory.map { it * 0.35f } else loudnessHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Action controls: Discard (Cancel), Pause/Resume, and Save (Done)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilledTonalButton(
                onClick = onCancel,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel recording",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            FilledTonalButton(
                onClick = onTogglePause,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isPaused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "Resume recording" else "Pause recording",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPaused) "Resume" else "Pause",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Finish and attach recording",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AudioLoudnessBarsCanvas(
    loudnessHistory: List<Float>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val minBarHeightDp = 4.dp
    val density = LocalDensity.current

    Canvas(modifier = modifier) {
        val barCount = loudnessHistory.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val totalGaps = gap * (barCount - 1)
        val barWidth = ((size.width - totalGaps) / barCount).coerceAtLeast(2f)
        val centerY = size.height / 2f
        val minHeightPx = with(density) { minBarHeightDp.toPx() }
        val maxHeightPx = size.height * 0.92f

        for (i in 0 until barCount) {
            val loudness = loudnessHistory.getOrElse(i) { 0.05f }.coerceIn(0f, 1f)
            val dynamicHeight = minHeightPx + (maxHeightPx - minHeightPx) * loudness
            val x = i * (barWidth + gap) + barWidth / 2f

            val barColor = if (loudness > 0.45f) {
                tertiaryColor.copy(alpha = (0.6f + loudness * 0.4f).coerceIn(0f, 1f))
            } else {
                primaryColor.copy(alpha = (0.4f + loudness * 0.6f).coerceIn(0f, 1f))
            }

            drawLine(
                color = barColor,
                start = Offset(x, centerY - dynamicHeight / 2f),
                end = Offset(x, centerY + dynamicHeight / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ChatControlRow(
    models: List<ModelInfo>,
    validationStates: Map<String, ModelValidationState>,
    selectedPath: String?,
    hasHistory: Boolean,
    onSelectModel: (ModelInfo) -> Unit,
    onTryAnyway: (ModelInfo) -> Unit,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
    onSettings: (() -> Unit)? = null,
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
            validationStates = validationStates,
            onTryAnyway = onTryAnyway,
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

        onSettings?.let { openSettings ->
            LiquidGlassButton(
                onClick = openSettings,
                hazeState = hazeState,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(23.dp))
            }
        }
    }
}

@Composable
private fun ChatModelPicker(
    models: List<ModelInfo>,
    validationStates: Map<String, ModelValidationState>,
    selectedPath: String?,
    onSelect: (ModelInfo) -> Unit,
    onTryAnyway: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState
) {
    var expanded by remember { mutableStateOf(false) }
    var blockedModelForDetails by remember { mutableStateOf<ModelInfo?>(null) }
    var failedModelForDetails by remember { mutableStateOf<ModelInfo?>(null) }
    val selected = models.firstOrNull { it.id == selectedPath || it.localPath == selectedPath }
    val selectedName = selected?.let { "${it.name} · ${it.type.label}" } ?: "Choose model"
    val pickerTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var triggerWidth by remember { mutableStateOf(0.dp) }
    val popupWidth = minOf(triggerWidth.takeIf { it > 0.dp } ?: 280.dp, (configuration.screenWidthDp.dp - 32.dp).coerceAtLeast(1.dp))
    val popupHeight = minOf(360.dp, configuration.screenHeightDp.dp / 2)

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "chat_model_picker_arrow_rotation"
    )

    Box(modifier = modifier.onSizeChanged { triggerWidth = with(density) { it.width.toDp() } }) {
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
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation)
            )
        }
        GlassDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, hazeState = hazeState,
            modifier = Modifier.width(popupWidth).heightIn(max = popupHeight)) {
            models.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(model.name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    model.supportsVision && model.supportsAudio -> "Images and audio"
                                    model.supportsVision -> "Images"
                                    model.supportsAudio -> "Audio"
                                    else -> "Text"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            val validation = validationStates[model.id]
                            if (validation == ModelValidationState.Pending || validation == ModelValidationState.Checking) {
                                Text("Checking model…", style = MaterialTheme.typography.labelSmall)
                            }
                            val stateLabel = when (model.status) {
                                ModelStatus.Blocked -> "Blocked · ${model.loadRestriction?.reason ?: "Device restriction"}"
                                ModelStatus.Failed -> "Needs attention · ${model.errorMessage ?: "Validation error"}"
                                ModelStatus.Downloading -> "Checking · In progress"
                                else -> null
                            }
                            if (stateLabel != null) {
                                Text(
                                    text = stateLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        when (model.status) {
                            ModelStatus.Blocked -> {
                                blockedModelForDetails = model
                            }
                            ModelStatus.Failed -> {
                                failedModelForDetails = model
                            }
                            else -> {
                                onSelect(model)
                            }
                        }
                    }
                )
            }
        }
    }

    blockedModelForDetails?.let { blockedModel ->
        val isCrash = blockedModel.isCrashBlocked
        val canTry = blockedModel.canTryAnyway
        Dialog(onDismissRequest = { blockedModelForDetails = null }) {
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
                        .widthIn(max = 540.dp),
                    hazeState = hazeState,
                    cornerRadius = 20.dp,
                    blurRadius = 34.dp,
                    tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    borderAlpha = 0.50f,
                    contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (isCrash) "Model Blocked" else "Model Load Restriction",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = blockedModel.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val restrictionReason = blockedModel.loadRestriction?.reason
                            ?: blockedModel.errorMessage
                            ?: "This model cannot run safely on this device due to system limitations."
                        Text(
                            text = restrictionReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isCrash) {
                            Text(
                                text = "This model previously crashed the app during native initialization. Try Anyway may crash the app again. A successful retry clears the device block.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (canTry) {
                            Text(
                                text = "Trying this model anyway may cause out-of-memory errors or app instability.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            LiquidGlassButton(
                                onClick = { blockedModelForDetails = null },
                                hazeState = hazeState,
                                modifier = Modifier.height(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                            ) {
                                Text("Cancel")
                            }
                            if (canTry) {
                                LiquidGlassButton(
                                    onClick = {
                                        val m = blockedModel
                                        blockedModelForDetails = null
                                        onTryAnyway(m)
                                    },
                                    hazeState = hazeState,
                                    modifier = Modifier.height(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    tintColor = MaterialTheme.colorScheme.error.copy(alpha = 0.30f),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Try Anyway")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    failedModelForDetails?.let { failedModel ->
        Dialog(onDismissRequest = { failedModelForDetails = null }) {
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
                        .widthIn(max = 540.dp),
                    hazeState = hazeState,
                    cornerRadius = 20.dp,
                    blurRadius = 34.dp,
                    tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    borderAlpha = 0.50f,
                    contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Model Needs Attention",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = failedModel.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = failedModel.errorMessage ?: "Validation failed for this model file. It may be incomplete, corrupted, or an unsupported architecture variant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            LiquidGlassButton(
                                onClick = { failedModelForDetails = null },
                                hazeState = hazeState,
                                modifier = Modifier.height(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
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

private val ScrollFabSize = 46.dp
private val ScrollFabGap = 8.dp
/** Vertical distance between stacked FAB centres / dock slots. */
private val ScrollFabStep = ScrollFabSize + ScrollFabGap

/**
 * Two-slot scroll FAB stack with spring-docked motion.
 *
 * - Both visible: top in the upper slot, bottom in the lower dock.
 * - Only top (user at chat bottom): top eases **down** into the dock.
 * - Only bottom (user at chat top): bottom **stays** in the lower dock (never takes the top slot).
 *
 * Enter/exit use opposite directional flourishes so each button feels unique.
 */
@Composable
private fun ScrollFabCluster(
    showTop: Boolean,
    showBottom: Boolean,
    hazeState: HazeState,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val stepPx = with(density) { ScrollFabStep.toPx() }

    // Stack always reserves two slots so the top FAB can dock without layout jumps.
    val stackHeight = ScrollFabSize * 2 + ScrollFabGap

    // Offset from the TOP of the stack box.
    // Upper slot = 0.dp, dock (lower) slot = ScrollFabStep.
    val topTargetY = when {
        showTop && showBottom -> 0.dp
        showTop -> ScrollFabStep // slide down into dock when bottom FAB is gone
        else -> 0.dp
    }
    // Bottom FAB always occupies the lower dock — never moves into the upper slot.
    val bottomTargetY = ScrollFabStep

    val topY by animateDpAsState(
        targetValue = topTargetY,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = 260f,
            visibilityThreshold = 0.1.dp
        ),
        label = "scroll_top_fab_dock_y"
    )
    val bottomY by animateDpAsState(
        targetValue = bottomTargetY,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 360f,
            visibilityThreshold = 0.1.dp
        ),
        label = "scroll_bottom_fab_dock_y"
    )

    // Liquid settle only while the top FAB relocates between slots.
    val topTravel by animateFloatAsState(
        targetValue = if (showTop && showBottom) 0f else if (showTop) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 220f),
        label = "scroll_top_fab_travel"
    )

    Box(
        modifier = modifier
            .width(ScrollFabSize)
            .height(stackHeight)
    ) {
        // ── Scroll-to-top ────────────────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible = showTop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = topY)
                .graphicsLayer {
                    // Mild arc + jelly while relocating to/from dock.
                    val t = topTravel
                    translationX = -kotlin.math.sin(t * Math.PI.toFloat()) * stepPx * 0.08f
                    rotationZ = (1f - t) * -6f + t * 4f
                    scaleX = 1f + kotlin.math.sin(t * Math.PI.toFloat()) * 0.06f
                    scaleY = 1f - kotlin.math.sin(t * Math.PI.toFloat()) * 0.05f
                },
            enter = androidx.compose.animation.fadeIn(tween(200)) +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.55f,
                    animationSpec = spring(
                        dampingRatio = 0.55f,
                        stiffness = 380f
                    )
                ) +
                androidx.compose.animation.slideInVertically(
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 320f),
                    initialOffsetY = { -it }
                ) +
                androidx.compose.animation.expandVertically(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
                    expandFrom = Alignment.Top
                ),
            exit = androidx.compose.animation.fadeOut(tween(150)) +
                androidx.compose.animation.scaleOut(
                    targetScale = 0.6f,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f)
                ) +
                androidx.compose.animation.slideOutVertically(
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                    targetOffsetY = { -it / 2 }
                ) +
                androidx.compose.animation.shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f),
                    shrinkTowards = Alignment.Top
                )
        ) {
            ScrollHazeFab(
                onClick = onScrollTop,
                hazeState = hazeState,
                tintAlpha = 0.18f,
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Scroll to top"
            )
        }

        // ── Scroll-to-bottom ─────────────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible = showBottom,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = bottomY),
            enter = androidx.compose.animation.fadeIn(tween(200)) +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.55f,
                    animationSpec = spring(
                        dampingRatio = 0.55f,
                        stiffness = 380f
                    )
                ) +
                androidx.compose.animation.slideInVertically(
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 320f),
                    initialOffsetY = { it }
                ) +
                androidx.compose.animation.expandVertically(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
                    expandFrom = Alignment.Bottom
                ),
            exit = androidx.compose.animation.fadeOut(tween(150)) +
                androidx.compose.animation.scaleOut(
                    targetScale = 0.6f,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f)
                ) +
                androidx.compose.animation.slideOutVertically(
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                    targetOffsetY = { it / 2 }
                ) +
                androidx.compose.animation.shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f),
                    shrinkTowards = Alignment.Bottom
                )
        ) {
            ScrollHazeFab(
                onClick = onScrollBottom,
                hazeState = hazeState,
                tintAlpha = 0.26f,
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to latest"
            )
        }
    }
}

/**
 * Circular scroll FAB with real Haze background blur.
 *
 * [LiquidGlassButton] / [glassEffect] paint an opaque scrim that hides Haze, and an
 * unclipped [hazeEffect] draws square corners. This path clips to a circle first, then
 * applies Haze only — no solid glass fill on top.
 */
@Composable
private fun ScrollHazeFab(
    onClick: () -> Unit,
    hazeState: HazeState,
    tintAlpha: Float,
    icon: ImageVector,
    contentDescription: String
) {
    val shape = RoundedCornerShape(23.dp)
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scroll_haze_fab_scale"
    )

    Box(
        modifier = Modifier
            .size(ScrollFabSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Clip first so Haze draws as a circle, not a square.
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = colors.surface,
                    blurRadius = 28.dp,
                    tint = HazeTint(colors.primary.copy(alpha = tintAlpha)),
                    noiseFactor = 0.08f
                )
            )
            .border(
                width = 0.8.dp,
                color = colors.outline.copy(alpha = 0.42f),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurface
        )
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, hazeState: HazeState, bottomInset: Dp = 0.dp) {
    val cardTint = MaterialTheme.colorScheme.surfaceContainer
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        item {
    GlassDispersionCard(
        hazeState = hazeState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 230.dp)
            .fluidReveal(delayMillis = 70, initialYOffset = 16.dp)
            .animatedGlassHalo(shape = RoundedCornerShape(28.dp), alpha = 0.04f, durationMillis = 4_900)
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
    isThinkingActive: Boolean,
    messageId: String = ""
) {
    if (thinkingText.isBlank() && !isThinkingActive) {
        return
    }
    var isExpanded by rememberSaveable(messageId) { mutableStateOf(isThinkingActive) }
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
    return trim() in setOf(
        "Generating…", "Generating...", "…", "Reading input…", "Reading input...",
        "Loading model…", "Loading model...", "Preparing input…", "Preparing input...",
        "Validating backend…", "Validating backend...", "Processing prompt…", "Processing prompt..."
    )
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
    progress: RequestProgress? = null,
    isStreaming: Boolean = false,
    messageId: String = "",
    requestId: String = "",
    attemptId: Long = 0L,
    revision: Int = 0,
    reasoningText: String? = null,
    toolUses: List<com.shounak.localmeshai.ai.tools.ToolUse> = emptyList(),
    isThinkingActive: Boolean = false,
    responseFormatVersion: Int = 0,
    terminalStatus: RequestTerminalStatus? = null,
    entryDelayMs: Int = 0,
    ragPassages: List<com.shounak.localmeshai.features.RagPassage> = emptyList(),
    onEditPrompt: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    ragSources: List<String> = emptyList(),
    ragChunkCount: Int = 0,
    ragTopMatchPct: Int = 0,
    documentName: String? = null,
    documentPath: String? = null,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    onFullscreenClick: () -> Unit
) {
    val context = LocalContext.current
    val reduceMotion = com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion
    val entryProgress = remember { Animatable(if (entryDelayMs > 0) 0f else 1f) }
    LaunchedEffect(entryDelayMs, reduceMotion) {
        if (reduceMotion || entryDelayMs <= 0) {
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
    val presentation = rememberMessagePresentation(
        text = text,
        isUser = isUser,
        isStreaming = isStreaming,
        messageId = messageId,
        requestId = requestId,
        attemptId = attemptId,
        revision = revision,
        reasoningText = reasoningText,
        isThinkingActive = isThinkingActive,
        responseFormatVersion = responseFormatVersion
    )
    val parsedContent = presentation.parsedContent
    val displayText = presentation.displayText
    val segments = presentation.segments
    val showStreamingDots = presentation.showStreamingDots
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
                        top = 14.dp,
                        end = 14.dp,
                        bottom = 14.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isUser && !documentName.isNullOrBlank()) {
                    DocumentAttachmentCard(
                        documentName = documentName,
                        documentPath = documentPath,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!isUser && toolUses.isNotEmpty()) ToolUseChip(toolUses, messageId)
                if (!isUser && presentation.showThinking) {
                    ThinkingProcessCard(
                        thinkingText = presentation.reasoningText.orEmpty(),
                        isThinkingActive = presentation.parsedThinking.isThinkingActive,
                        messageId = messageId
                    )
                }

                if (!isUser && progress != null && displayText.isStreamingPlaceholderText()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        StreamingDotsIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = when (progress) {
                                is RequestProgress.PreparingInput -> "Preparing input…"
                                is RequestProgress.LoadingModel -> "Loading model…"
                                is RequestProgress.ValidatingBackend -> "Validating backend…"
                                is RequestProgress.RecoveringOnCpu -> "GPU failed; retrying on CPU…"
                                is RequestProgress.ProcessingPrompt -> "Processing prompt…"
                                is RequestProgress.UsingTools -> "Working with tools…"
                                is RequestProgress.RetryingWithoutTools -> "Retrying this answer without tools; tools will retry next turn…"
                                is RequestProgress.StoppingAfterTimeout -> "Input timed out; stopping model…"
                                is RequestProgress.Generating -> "Generating…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (showStreamingDots) {
                    StreamingDotsIndicator(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    if (isStreaming) {
                        Text(
                            text = displayText.trim('\n'),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    } else if (!isUser && terminalStatus == RequestTerminalStatus.Stopped && displayText.isBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Stopped before an answer was generated",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                segments.forEach { segment ->
                                    when (segment) {
                                        is ModelAnswerSegment.Text -> {
                                            if (segment.text.isNotBlank() && (!segment.text.startsWith("⚠️ Error:") || errorMessage != null)) {
                                                AdaptiveMessageText(
                                                    text = segment.text.trim('\n'),
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
                                        is ModelAnswerSegment.InlineMathListItem -> {
                                            InlineMathListItem(
                                                parts = segment.parts,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!isUser) com.shounak.localmeshai.features.SourcePassagesButton(ragPassages, hazeState)
                    if (!isUser && ragSources.isNotEmpty() && ragPassages.isEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "📚 Sourced from ${ragSources.joinToString(", ")} ($ragChunkCount chunks, $ragTopMatchPct% match)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    val hasError = !isUser && (errorMessage != null || displayText.startsWith("⚠️ Error:"))
                    if (hasError) {
                        val effectiveError = errorMessage ?: displayText
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = effectiveError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (onRetry != null) {
                                        FilledTonalButton(
                                            onClick = onRetry,
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Retry",
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val reports = CrashReportManager.getAllReports(context)
                                            val matching = reports.firstOrNull { it.isNativeCrash } ?: reports.firstOrNull()
                                            if (matching != null) {
                                                CrashReportManager.sendToGitHub(context, matching.details, matching.title)
                                            } else {
                                                CrashReportManager.openNewIssue(
                                                    context = context,
                                                    title = "[Model Inference Error] ${effectiveError.take(60)}",
                                                    body = "### Error Summary\n$effectiveError\n\n### Complete Device Specifications\n${DeviceUtils.formatFullDeviceDiagnostics(context)}"
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Report Crash to Repo", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }


        }
        if (!isUser || onEditPrompt != null || onRegenerate != null) {
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
            ) {
                com.shounak.localmeshai.features.MessageRevisionActions(text, onEditPrompt, onRegenerate, hazeState)
                if (!isUser) {
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

private enum class HistoryFilterType {
    All,
    Text,
    Vision
}

private sealed interface UnifiedChatSessionItem {
    val id: String
    val title: String
    val updatedAt: Long
    val type: HistorySessionType

    data class TextSession(
        val session: ChatSession
    ) : UnifiedChatSessionItem {
        override val id: String get() = session.id
        override val title: String get() = session.title
        override val updatedAt: Long get() = session.updatedAt
        override val type: HistorySessionType get() = HistorySessionType.Text
    }

    data class VisionSession(
        val session: VisionChatSession
    ) : UnifiedChatSessionItem {
        override val id: String get() = session.id
        override val title: String get() = session.title
        override val updatedAt: Long get() = session.updatedAt
        override val type: HistorySessionType get() = HistorySessionType.Vision
    }
}

private data class SessionTagVisuals(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color
)

@Composable
private fun SessionTypeTag(
    type: HistorySessionType,
    modifier: Modifier = Modifier
) {
    val visuals = when (type) {
        HistorySessionType.Vision -> {
            val baseColor = MaterialTheme.colorScheme.primary
            val container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            SessionTagVisuals(
                label = "Vision",
                icon = Icons.Default.Visibility,
                containerColor = container,
                contentColor = baseColor,
                borderColor = baseColor.copy(alpha = 0.38f)
            )
        }
        HistorySessionType.Text -> {
            val baseColor = MaterialTheme.colorScheme.secondary
            val container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            SessionTagVisuals(
                label = "Text",
                icon = Icons.AutoMirrored.Filled.Chat,
                containerColor = container,
                contentColor = baseColor,
                borderColor = baseColor.copy(alpha = 0.38f)
            )
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = visuals.containerColor,
        border = BorderStroke(1.dp, visuals.borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = visuals.icon,
                contentDescription = null,
                tint = visuals.contentColor,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = visuals.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp
                ),
                color = visuals.contentColor
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatHistoryDialog(
    textSessions: List<ChatSession>,
    visionSessions: List<VisionChatSession>,
    currentTextSessionId: String?,
    currentVisionSessionId: String?,
    isVisionModeActive: Boolean,
    onSelectTextSession: (String) -> Unit,
    onSelectVisionSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteTextSession: (String) -> Unit,
    onDeleteVisionSession: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    hazeState: HazeState,
    onSettings: (() -> Unit)? = null
) {
    val historyPreferences = com.shounak.localmeshai.features.WorkspacePreferences.get(LocalContext.current)
    val metadataRevision by historyPreferences.revision.collectAsState()
    var historyQuery by rememberSaveable { mutableStateOf("") }
    val dialogMaxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.82f
    var filterType by rememberSaveable { mutableStateOf(HistoryFilterType.All) }
    // SnapshotStateList keeps its identity when a row is removed or updated. Use
    // immutable values as remember keys so the open dialog rebuilds its rows.
    val activeTextSessions = textSessions.toList()
    val activeVisionSessions = visionSessions.toList()

    val mergedSessions = remember(activeTextSessions, activeVisionSessions, filterType, historyQuery, metadataRevision) {
        val textItems = activeTextSessions.map { UnifiedChatSessionItem.TextSession(it) }
        val visionItems = activeVisionSessions.map { UnifiedChatSessionItem.VisionSession(it) }
        val combined = when (filterType) {
            HistoryFilterType.All -> textItems + visionItems
            HistoryFilterType.Text -> textItems
            HistoryFilterType.Vision -> visionItems
        }
        combined.filter { item ->
            if (historyQuery.isBlank()) return@filter true
            val title = historyPreferences.title(item.type.name.lowercase(), item.id, item.title)
            title.contains(historyQuery, ignoreCase = true) || when (item) {
                is UnifiedChatSessionItem.TextSession -> item.session.messages.any { it.text.contains(historyQuery, ignoreCase = true) }
                is UnifiedChatSessionItem.VisionSession -> item.session.messages.any { it.text.contains(historyQuery, ignoreCase = true) } ||
                    item.session.question.contains(historyQuery, ignoreCase = true) || item.session.answer.contains(historyQuery, ignoreCase = true)
            }
        }.sortedWith(
            compareByDescending<UnifiedChatSessionItem> { historyPreferences.pinned(it.type.name.lowercase(), it.id) }
                .thenByDescending { it.updatedAt }
                .thenBy { historyPreferences.title(it.type.name.lowercase(), it.id, it.title) }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f)
                .glassEffect(hazeState = hazeState, shape = RoundedCornerShape(20.dp),
                    tintColor = MaterialTheme.colorScheme.surfaceContainerHigh, borderAlpha = 0.40f),
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(modifier = Modifier.padding(16.dp).heightIn(max = dialogMaxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Chat history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    onSettings?.let { open ->
                        LiquidGlassButton(onClick = { onDismiss(); open() }, hazeState = hazeState, modifier = Modifier.size(38.dp),
                            shape = RoundedCornerShape(19.dp), contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                        }
                    }
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

                OutlinedTextField(value = historyQuery, onValueChange = { historyQuery = it },
                    label = { Text("Search conversations") }, placeholder = { Text("Titles or message contents") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                com.shounak.localmeshai.ui.components.SlidingGlassTabs(
                    tabs = listOf(
                        com.shounak.localmeshai.ui.components.SlidingGlassTab("All (${activeTextSessions.size + activeVisionSessions.size})"),
                        com.shounak.localmeshai.ui.components.SlidingGlassTab("Text (${activeTextSessions.size})"),
                        com.shounak.localmeshai.ui.components.SlidingGlassTab("Vision (${activeVisionSessions.size})")),
                    selectedIndex = filterType.ordinal, onSelect = { filterType = HistoryFilterType.entries[it] }, hazeState = hazeState
                )

                if (mergedSessions.isEmpty()) {
                    Text(
                        if (historyQuery.isNotBlank()) "No conversations match your search." else when (filterType) {
                            HistoryFilterType.All -> "No messages yet."
                            HistoryFilterType.Text -> "No text chats yet."
                            HistoryFilterType.Vision -> "No vision chats yet."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(mergedSessions, key = { _, item -> "${item.type}_${item.id}" }) { index, item ->
                            val selected = when (item) {
                                is UnifiedChatSessionItem.TextSession -> item.id == currentTextSessionId && !isVisionModeActive
                                is UnifiedChatSessionItem.VisionSession -> item.id == currentVisionSessionId && isVisionModeActive
                            }
                            Box(
                                modifier = Modifier
                                    .then(if (com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion) Modifier else Modifier.animateItem())
                                    .fillMaxWidth()
                                    .fluidReveal(
                                        delayMillis = (index * 35).coerceAtMost(280),
                                        initialScale = 0.90f,
                                        initialYOffset = 14.dp,
                                        initialXOffset = if (index % 2 == 0) (-22).dp else 22.dp,
                                        initialRotationZ = if (index % 2 == 0) -1.2f else 1.2f
                                    )
                                    .clickable {
                                        when (item) {
                                            is UnifiedChatSessionItem.TextSession -> onSelectTextSession(item.id)
                                            is UnifiedChatSessionItem.VisionSession -> onSelectVisionSession(item.id)
                                        }
                                    }
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
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val title = historyPreferences.title(item.type.name.lowercase(), item.id, item.title).ifBlank { "Untitled session" }
                                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            SessionTypeTag(type = item.type)
                                            if (historyPreferences.pinned(item.type.name.lowercase(), item.id)) Text("Pinned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            val count = when (item) {
                                                is UnifiedChatSessionItem.TextSession -> item.session.messages.size
                                                is UnifiedChatSessionItem.VisionSession -> item.session.messages.size
                                            }
                                            Text("$count messages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(ChatDateFormatter.formatChatDate(item.updatedAt), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    com.shounak.localmeshai.features.HistoryEntryActions(item.type.name.lowercase(), item.id, item.title, hazeState) {
                                        when (item) {
                                            is UnifiedChatSessionItem.TextSession -> onDeleteTextSession(item.id)
                                            is UnifiedChatSessionItem.VisionSession -> onDeleteVisionSession(item.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val hasAnySessions = activeTextSessions.isNotEmpty() || activeVisionSessions.isNotEmpty()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidGlassButton(
                        onClick = onClearAll,
                        hazeState = hazeState,
                        enabled = hasAnySessions,
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
    val presentation = rememberMessagePresentation(
        messageId = message.id,
        text = message.text,
        reasoningText = message.reasoningText,
        revision = message.revision,
        isUser = message.isUser,
        isStreaming = false,
        responseFormatVersion = message.responseFormatVersion
    )
    val parsedContent = presentation.parsedContent
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
                        if (!message.isUser && message.toolUses.isNotEmpty()) ToolUseChip(message.toolUses, message.id)
                        if (!message.isUser && presentation.showThinking) {
                            ThinkingProcessCard(
                                thinkingText = presentation.reasoningText.orEmpty(),
                                isThinkingActive = presentation.parsedThinking.isThinkingActive,
                                messageId = message.id
                            )
                        }
                        if (!message.isUser && message.terminalStatus == RequestTerminalStatus.Stopped && presentation.displayText.isBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Stopped before an answer was generated",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        val rawVisibleText = presentation.displayText.ifBlank { if (message.isUser) "" else "Answers will appear here." }
                        val answerSegments = if (message.isUser || presentation.displayText.isBlank()) {
                            listOf(ModelAnswerSegment.Text(rawVisibleText))
                        } else {
                            presentation.segments
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
                                is ModelAnswerSegment.InlineMathListItem ->
                                    InlineMathListItem(
                                        parts = segment.parts,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToolUseChip(uses: List<com.shounak.localmeshai.ai.tools.ToolUse>, messageId: String) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Used ${uses.map { it.name }.distinct().joinToString()} ${if (expanded) "▴" else "▾"}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (expanded) uses.forEach { use ->
                val arguments = runCatching { org.json.JSONObject(use.arguments).optString("expression", use.arguments) }.getOrDefault(use.arguments)
                Text("${use.name}: $arguments = ${use.result}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolSkillResultContent(use)
            }
        }
    }
}

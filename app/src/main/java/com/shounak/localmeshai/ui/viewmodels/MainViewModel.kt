package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.models.CustomModelDefinition
import com.shounak.localmeshai.models.CustomModelStore
import com.shounak.localmeshai.models.ModelCatalog
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.services.DownloadController
import com.shounak.localmeshai.services.ModelDownloadService
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.HfInputKind
import com.shounak.localmeshai.utils.HuggingFaceModelResolver
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LocalModelImporter
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelDownloadUrlPolicy
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.FileHeaderSniffer
import com.shounak.localmeshai.utils.GgufBundleInspector
import com.shounak.localmeshai.utils.GgufArtifactInspection
import com.shounak.localmeshai.utils.NonGgufBundleInspector
import com.shounak.localmeshai.utils.StagedArtifactItem
import com.shounak.localmeshai.utils.ModelSourceResolver
import com.shounak.localmeshai.utils.ModelVariant
import com.shounak.localmeshai.utils.RepoFile
import com.shounak.localmeshai.utils.ResolvedRepoInfo
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.shounak.localmeshai.utils.PhoneImportSession
import com.shounak.localmeshai.utils.ModelOperationError
import com.shounak.localmeshai.utils.ImportPresentationCoordinator
import com.shounak.localmeshai.utils.ModelErrorReason
import com.shounak.localmeshai.utils.ModelOperationStage
import kotlinx.coroutines.cancel
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ModelInputClassification {
    HuggingFace,
    DirectDownload,
    Invalid
}

sealed interface HfResolutionUiState {
    object Idle : HfResolutionUiState
    data class Loading(val input: String) : HfResolutionUiState
    data class Success(
        val repoInfo: ResolvedRepoInfo,
        val variants: List<ModelVariant>,
        val preselectedVariant: ModelVariant?
    ) : HfResolutionUiState
    data class Error(val message: String, val isAuthError: Boolean = false) : HfResolutionUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDownloader = ModelDownloader(application)
    
    private val _selectedTextModelPath = MutableStateFlow<String?>(null)
    val selectedTextModelPath = _selectedTextModelPath.asStateFlow()

    private val _selectedVisionModelPath = MutableStateFlow<String?>(null)
    val selectedVisionModelPath = _selectedVisionModelPath.asStateFlow()

    private val textSelectionRevision = java.util.concurrent.atomic.AtomicLong(0L)
    private val visionSelectionRevision = java.util.concurrent.atomic.AtomicLong(0L)

    private val _hfResolutionState = MutableStateFlow<HfResolutionUiState>(HfResolutionUiState.Idle)
    val hfResolutionState = _hfResolutionState.asStateFlow()

    private val _isSubmittingVariantDownload = MutableStateFlow(false)
    val isSubmittingVariantDownload = _isSubmittingVariantDownload.asStateFlow()

    private val _variantSubmissionError = MutableStateFlow<String?>(null)
    val variantSubmissionError = _variantSubmissionError.asStateFlow()

    private var hfResolutionJob: Job? = null

    // Form state retention for AddModelSheet
    private val _hfInputUrl = MutableStateFlow("")
    val hfInputUrl = _hfInputUrl.asStateFlow()

    private val _hfSelectedVariant = MutableStateFlow<ModelVariant?>(null)
    val hfSelectedVariant = _hfSelectedVariant.asStateFlow()

    private val _hfSelectedProjector = MutableStateFlow<RepoFile?>(null)
    val hfSelectedProjector = _hfSelectedProjector.asStateFlow()

    private val _directName = MutableStateFlow("")
    val directName = _directName.asStateFlow()

    private val _directType = MutableStateFlow(ModelType.Text)
    val directType = _directType.asStateFlow()

    private val _directSha256 = MutableStateFlow("")
    val directSha256 = _directSha256.asStateFlow()

    fun updateHfInputUrl(url: String) {
        if (_hfInputUrl.value != url) {
            _hfInputUrl.value = url
            resetHfResolution()
            _hfSelectedVariant.value = null
            _hfSelectedProjector.value = null
        }
    }

    fun selectHfVariant(variant: ModelVariant?) {
        _hfSelectedVariant.value = variant
        _hfSelectedProjector.value = variant?.projectorFile
    }

    fun selectHfProjector(projector: RepoFile?) {
        _hfSelectedProjector.value = projector
    }

    fun updateDirectName(name: String) {
        _directName.value = name
    }

    fun updateDirectType(type: ModelType) {
        _directType.value = type
    }

    fun updateDirectSha256(sha256: String) {
        _directSha256.value = sha256
    }

    fun resetHfResolution() {
        hfResolutionJob?.cancel()
        hfResolutionJob = null
        _hfResolutionState.value = HfResolutionUiState.Idle
    }

    private val _pendingImportDraft = MutableStateFlow<LocalModelImporter.ImportResult?>(null)
    val pendingImportDraft = _pendingImportDraft.asStateFlow()

    private val _isImportInspecting = MutableStateFlow(false)
    val isImportInspecting = _isImportInspecting.asStateFlow()

    private val _importInspectionStatus = MutableStateFlow("")
    val importInspectionStatus = _importInspectionStatus.asStateFlow()

    private val _importInspectionError = MutableStateFlow<ModelOperationError?>(null)
    val importInspectionError = _importInspectionError.asStateFlow()

    private val _isRegisteringLocalModel = MutableStateFlow(false)
    val isRegisteringLocalModel = _isRegisteringLocalModel.asStateFlow()

    private val _localModelRegistrationError = MutableStateFlow<ModelOperationError?>(null)
    val localModelRegistrationError = _localModelRegistrationError.asStateFlow()

    data class ImportCompletionEvent(
        val sheetSessionId: String,
        val operationId: String,
        val modelId: String
    )

    private val _importCompletionEvent = MutableStateFlow<ImportCompletionEvent?>(null)
    val importCompletionEvent = _importCompletionEvent.asStateFlow()

    fun consumeCompletionEvent(sheetSessionId: String, operationId: String? = null): Boolean {
        val current = _importCompletionEvent.value ?: return false
        if (current.sheetSessionId == sheetSessionId && (operationId == null || current.operationId == operationId)) {
            _importCompletionEvent.value = null
            return true
        }
        return false
    }

    private val phoneImportSession = PhoneImportSession(
        releasePermission = { uriString ->
            runCatching {
                LocalModelImporter.rollbackPermissions(getApplication(), listOf(android.net.Uri.parse(uriString)))
            }
        },
        isUriRegistered = { uriString ->
            getActiveBundleUriStrings().contains(uriString)
        }
    )
    private var importInspectionJob: Job? = null
    private var registrationJob: Job? = null
    private var currentInspectionRequestId: Long = 0L
    private val inspectionGate = Mutex()
    private val registrationGate = Mutex()
    private val presentationCoordinator = ImportPresentationCoordinator()
    private val submissionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun getSubmissionLock(modelId: String): Mutex =
        submissionLocks.computeIfAbsent(modelId) { Mutex() }

    data class VerifiedChecksumRecord(
        val modelId: String,
        val sourceIdentity: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val verifiedSha256: String,
        val expectedSha256: String
    )
    private val verifiedChecksumCache = java.util.concurrent.ConcurrentHashMap<String, VerifiedChecksumRecord>()

    data class InspectedBundleOutcome(
        val bundle: ModelBundle,
        val capabilities: ModelCapabilities,
        val contextLength: Int,
        val loadRestriction: com.shounak.localmeshai.models.LoadRestriction?,
        val isMultimodal: Boolean,
        val isValid: Boolean,
        val error: String? = null
    )

    private fun getArtifactRevision(model: ModelInfo, target: File?): String {
        val b = model.bundle
        return if (b != null && b.allArtifacts.isNotEmpty()) {
            b.allArtifacts.joinToString(";") { "${it.displayName}:${it.sizeBytes}:${it.lastModified}" }
        } else if (target != null && target.exists()) {
            "${target.absolutePath}:${target.length()}:${target.lastModified()}"
        } else {
            model.localPath.orEmpty()
        }
    }

    private suspend fun inspectBundleAuthoritatively(
        model: ModelInfo,
        targetFile: File? = null
    ): InspectedBundleOutcome = withContext(Dispatchers.IO) {
        val existingBundle = model.bundle
        val bundleToInspect = if (existingBundle != null) {
            existingBundle
        } else if (targetFile != null && targetFile.exists()) {
            val runnableFile = if (targetFile.isDirectory) {
                runCatching {
                    ModelArtifactStorage.resolveRunnableInDirectory(targetFile, isText = (model.type == ModelType.Text))
                }.getOrNull() ?: targetFile
            } else targetFile

            val format = when {
                model.fileName.endsWith(".litertlm", ignoreCase = true) || runnableFile.name.endsWith(".litertlm", ignoreCase = true) -> ModelFormat.LiteRtLm
                model.fileName.endsWith(".task", ignoreCase = true) || runnableFile.name.endsWith(".task", ignoreCase = true) -> ModelFormat.MediaPipeTask
                else -> ModelFormat.GGUF
            }
            ModelBundle(
                id = model.id,
                displayName = model.name,
                mainArtifacts = listOf(
                    ModelArtifact(
                        role = ArtifactRole.Main,
                        displayName = runnableFile.name,
                        source = ArtifactSource.AppManaged(runnableFile.absolutePath),
                        sizeBytes = runnableFile.length(),
                        lastModified = runnableFile.lastModified()
                    )
                ),
                format = format
            )
        } else {
            return@withContext InspectedBundleOutcome(
                bundle = existingBundle ?: ModelBundle(id = model.id, displayName = model.name, mainArtifacts = emptyList()),
                capabilities = existingBundle?.capabilities ?: ModelCapabilities(),
                contextLength = model.contextWindowTokens ?: 2048,
                loadRestriction = model.loadRestriction,
                isMultimodal = model.isMultimodal,
                isValid = false,
                error = "No artifact or bundle found to inspect"
            )
        }

        try {
            ModelSourceResolver.resolve(getApplication(), bundleToInspect).use { lease ->
                when (bundleToInspect.format) {
                    ModelFormat.GGUF -> {
                        val mainInspections = mutableListOf<GgufArtifactInspection>()
                        for ((index, artifact) in bundleToInspect.mainArtifacts.withIndex()) {
                            val path = lease.paths.getOrNull(index) ?: continue
                            val len = artifact.sizeBytes.takeIf { it > 0L } ?: File(path).length()
                            val insp = GgufBundleInspector.inspectArtifact(path, artifact.displayName, len)
                            mainInspections.add(insp)
                        }
                        var projectorInspection: GgufArtifactInspection? = null
                        if (bundleToInspect.projectorArtifact != null && lease.projectorPath != null) {
                            val projPath = lease.projectorPath!!
                            val projArt = bundleToInspect.projectorArtifact
                            val len = projArt.sizeBytes.takeIf { it > 0L } ?: File(projPath).length()
                            projectorInspection = GgufBundleInspector.inspectArtifact(projPath, projArt.displayName, len)
                        }

                        val allInspections = if (projectorInspection != null) mainInspections + projectorInspection else mainInspections
                        val expectedRoles = bundleToInspect.allArtifacts.associate { it.displayName to it.role }
                        val bundleRes = GgufBundleInspector.validateBundle(allInspections, expectedRoles)
                        if (bundleRes.isValid) {
                            val c = bundleRes.capabilities
                            val ctx = if (bundleRes.contextLength > 0) bundleRes.contextLength else (model.contextWindowTokens ?: 2048)
                            val updatedBundle = bundleToInspect.copy(
                                capabilities = c,
                                contextLength = ctx
                            )
                            InspectedBundleOutcome(
                                bundle = updatedBundle,
                                capabilities = c,
                                contextLength = ctx,
                                loadRestriction = bundleToInspect.loadRestriction,
                                isMultimodal = c.supportsVision || c.supportsAudio,
                                isValid = true
                            )
                        } else {
                            InspectedBundleOutcome(
                                bundle = bundleToInspect,
                                capabilities = bundleToInspect.capabilities,
                                contextLength = model.contextWindowTokens ?: 2048,
                                loadRestriction = bundleToInspect.loadRestriction,
                                isMultimodal = bundleToInspect.capabilities.supportsVision || bundleToInspect.capabilities.supportsAudio,
                                isValid = false,
                                error = bundleRes.errorMessage ?: "GGUF bundle validation failed"
                            )
                        }
                    }
                    ModelFormat.MediaPipeTask -> {
                        val path = lease.paths.firstOrNull() ?: return@withContext InspectedBundleOutcome(
                            bundle = bundleToInspect,
                            capabilities = bundleToInspect.capabilities,
                            contextLength = model.contextWindowTokens ?: 2048,
                            loadRestriction = bundleToInspect.loadRestriction,
                            isMultimodal = bundleToInspect.capabilities.supportsVision || bundleToInspect.capabilities.supportsAudio,
                            isValid = false,
                            error = "MediaPipe task path missing"
                        )
                        val insp = NonGgufBundleInspector.inspectMediaPipeTask(File(path))
                        if (insp.isValid) {
                            val c = insp.capabilities
                            val ctx = if (c.contextLength > 0) c.contextLength else (model.contextWindowTokens ?: 2048)
                            val updatedBundle = bundleToInspect.copy(
                                capabilities = c,
                                contextLength = ctx
                            )
                            InspectedBundleOutcome(
                                bundle = updatedBundle,
                                capabilities = c,
                                contextLength = ctx,
                                loadRestriction = insp.loadRestriction ?: bundleToInspect.loadRestriction,
                                isMultimodal = c.supportsVision || c.supportsAudio,
                                isValid = true
                            )
                        } else {
                            InspectedBundleOutcome(
                                bundle = bundleToInspect,
                                capabilities = bundleToInspect.capabilities,
                                contextLength = model.contextWindowTokens ?: 2048,
                                loadRestriction = bundleToInspect.loadRestriction,
                                isMultimodal = bundleToInspect.capabilities.supportsVision || bundleToInspect.capabilities.supportsAudio,
                                isValid = false,
                                error = insp.errorMessage ?: "MediaPipe task inspection failed"
                            )
                        }
                    }
                    ModelFormat.LiteRtLm -> {
                        val path = lease.paths.firstOrNull() ?: return@withContext InspectedBundleOutcome(
                            bundle = bundleToInspect,
                            capabilities = bundleToInspect.capabilities,
                            contextLength = model.contextWindowTokens ?: 2048,
                            loadRestriction = bundleToInspect.loadRestriction,
                            isMultimodal = bundleToInspect.capabilities.supportsVision || bundleToInspect.capabilities.supportsAudio,
                            isValid = false,
                            error = "LiteRT-LM path missing"
                        )
                        val insp = NonGgufBundleInspector.inspectLiteRtLm(File(path))
                        if (insp.isValid) {
                            val c = insp.capabilities
                            val ctx = if (c.contextLength > 0) c.contextLength else (model.contextWindowTokens ?: 2048)
                            val updatedBundle = bundleToInspect.copy(
                                capabilities = c,
                                contextLength = ctx,
                                loadRestriction = insp.loadRestriction ?: bundleToInspect.loadRestriction
                            )
                            InspectedBundleOutcome(
                                bundle = updatedBundle,
                                capabilities = c,
                                contextLength = ctx,
                                loadRestriction = insp.loadRestriction ?: bundleToInspect.loadRestriction,
                                isMultimodal = c.supportsVision || c.supportsAudio,
                                isValid = true
                            )
                        } else {
                            InspectedBundleOutcome(
                                bundle = bundleToInspect,
                                capabilities = bundleToInspect.capabilities,
                                contextLength = model.contextWindowTokens ?: 2048,
                                loadRestriction = bundleToInspect.loadRestriction,
                                isMultimodal = bundleToInspect.capabilities.supportsVision || bundleToInspect.capabilities.supportsAudio,
                                isValid = false,
                                error = insp.errorMessage ?: "LiteRT-LM inspection failed"
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("MainViewModel", "Authoritative bundle inspection threw for ${model.id}: ${t.message}")
            InspectedBundleOutcome(
                bundle = bundleToInspect,
                capabilities = bundleToInspect.capabilities,
                contextLength = model.contextWindowTokens ?: 2048,
                loadRestriction = bundleToInspect.loadRestriction,
                isMultimodal = model.isMultimodal,
                isValid = false,
                error = t.message
            )
        }
    }

    val hiddenFromPresentationIds = presentationCoordinator.hiddenFromPresentationIds

    fun openImportSheet(sheetSessionId: String) {
        presentationCoordinator.openImportSheet(sheetSessionId)
    }

    fun isSheetSessionOpen(sheetSessionId: String): Boolean {
        return presentationCoordinator.isSheetSessionOpen(sheetSessionId)
    }

    fun isSessionActive(sheetSessionId: String): Boolean {
        return presentationCoordinator.isSessionActive(sheetSessionId)
    }

    private fun hideModelForSession(sheetSessionId: String, modelId: String, isNewModel: Boolean) {
        presentationCoordinator.hideModelForSession(sheetSessionId, modelId, isNewModel)
    }

    fun unhideModel(modelId: String) {
        presentationCoordinator.unhideModel(modelId)
    }

    fun unhideAllModels() {
        presentationCoordinator.unhideAllModels()
    }

    fun setPendingImportDraft(draft: LocalModelImporter.ImportResult?) {
        viewModelScope.launch {
            if (_pendingImportDraft.value != null && _pendingImportDraft.value !== draft) {
                _pendingImportDraft.value?.draftStagingDir?.deleteRecursively()
            }
            phoneImportSession.replaceDraft(draft)
            _pendingImportDraft.value = draft
            _importInspectionError.value = null
        }
    }

    fun clearPendingImportDraft() {
        setPendingImportDraft(null)
    }

    fun inspectAndSetPhoneUris(uris: List<android.net.Uri>, sheetSessionId: String = "") {
        if (uris.isEmpty()) return
        if (sheetSessionId.isBlank() || !isSheetSessionOpen(sheetSessionId) || !isSessionActive(sheetSessionId)) {
            return
        }
        val requestId = ++currentInspectionRequestId
        viewModelScope.launch {
            inspectionGate.withLock {
                if (phoneImportSession.isTeardownStarted) return@launch
                if (sheetSessionId.isBlank() || !isSheetSessionOpen(sheetSessionId) || !isSessionActive(sheetSessionId)) return@launch
                if (requestId != currentInspectionRequestId) return@launch

                val previousJob = importInspectionJob
                if (previousJob?.isActive == true) {
                    _isImportInspecting.value = true
                    _importInspectionStatus.value = "Cancelling previous inspection..."
                    previousJob.cancelAndJoin()
                }

                if (phoneImportSession.isTeardownStarted) return@launch
                if (sheetSessionId.isBlank() || !isSheetSessionOpen(sheetSessionId) || !isSessionActive(sheetSessionId)) return@launch
                if (requestId != currentInspectionRequestId) return@launch

                _importInspectionError.value = null
                _localModelRegistrationError.value = null

                val canStart = phoneImportSession.startInspection()
                if (!canStart) {
                    _importInspectionError.value = ModelOperationError(
                        reason = ModelErrorReason.Unknown,
                        stage = ModelOperationStage.Inspection,
                        diagnosticDetails = "Cannot inspect files while registration is in progress or session is closing."
                    )
                    return@launch
                }
                _isImportInspecting.value = true
                _importInspectionStatus.value = "Inspecting model files..."

                val job = launch {
                    try {
                        val result = LocalModelImporter.importFromUris(
                            context = getApplication(),
                            uris = uris,
                            onProgress = { progress ->
                                if (requestId == currentInspectionRequestId) {
                                    _importInspectionStatus.value = progress
                                }
                            },
                            onGrantAcquired = { uri ->
                                withContext(NonCancellable) {
                                    phoneImportSession.recordGrant(uri.toString())
                                }
                            }
                        )
                        if (requestId != currentInspectionRequestId) {
                            withContext(NonCancellable) {
                                phoneImportSession.cancelInspection()
                            }
                            return@launch
                        }
                        result.onSuccess { r ->
                            phoneImportSession.finishInspection(r)
                            _pendingImportDraft.value = r
                        }.onFailure { err ->
                            withContext(NonCancellable) {
                                phoneImportSession.cancelInspection()
                            }
                            val opErr = err as? ModelOperationError ?: ModelOperationError(
                                reason = ModelErrorReason.Unknown,
                                stage = ModelOperationStage.Inspection,
                                diagnosticDetails = err.message ?: "Failed to inspect selected files."
                            )
                            _importInspectionError.value = opErr
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        withContext(NonCancellable) {
                            phoneImportSession.cancelInspection()
                        }
                        throw e
                    } catch (e: Exception) {
                        withContext(NonCancellable) {
                            phoneImportSession.cancelInspection()
                            if (requestId == currentInspectionRequestId) {
                                _importInspectionError.value = ModelOperationError(
                                    reason = ModelErrorReason.Unknown,
                                    stage = ModelOperationStage.Inspection,
                                    diagnosticDetails = e.message ?: "Unexpected error during file inspection."
                                )
                            }
                        }
                    } finally {
                        if (requestId == currentInspectionRequestId) {
                            _isImportInspecting.value = false
                        }
                    }
                }
                importInspectionJob = job
            }
        }
    }

    fun dismissImportSheet(sheetSessionId: String = "") {
        val wasActive = presentationCoordinator.dismissImportSheet(sheetSessionId)
        if (!wasActive && sheetSessionId.isNotBlank()) {
            // Older session presentation records were removed; do not cancel newer active session
            return
        }
        viewModelScope.launch {
            inspectionGate.withLock {
                val inspJob = importInspectionJob
                importInspectionJob = null
                currentInspectionRequestId++
                _isImportInspecting.value = true
                _importInspectionStatus.value = "Closing..."
                try {
                    inspJob?.cancelAndJoin()
                } finally {
                    _isImportInspecting.value = false
                    _importInspectionStatus.value = ""
                    _importInspectionError.value = null
                    _localModelRegistrationError.value = null
                }
                val immediatelyDismissed = phoneImportSession.requestDismiss()
                if (immediatelyDismissed) {
                    _pendingImportDraft.value?.draftStagingDir?.deleteRecursively()
                    _pendingImportDraft.value = null
                }
            }
        }
    }

    fun registerPendingDraft(sheetSessionId: String) {
        viewModelScope.launch {
            if (!registrationGate.tryLock()) {
                return@launch
            }
            val reservedDraft: LocalModelImporter.ImportResult
            try {
                if (registrationJob?.isActive == true) {
                    return@launch
                }
                val reserved = phoneImportSession.reserveDraftForRegistration()
                val draft = reserved?.result ?: _pendingImportDraft.value
                if (reserved == null || draft == null) {
                    return@launch
                }
                reservedDraft = draft
                _isRegisteringLocalModel.value = true
                _localModelRegistrationError.value = null
                _importInspectionError.value = null
            } finally {
                registrationGate.unlock()
            }

            val job = coroutineContext[Job]
            registrationJob = job

            val bundle = reservedDraft.bundle
            val bundleUris = bundle.allArtifacts.mapNotNull {
                (it.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString
            }.toSet()

            var regError: ModelOperationError? = null
            try {
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.IO) {
                            val storeState = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).customModelStoreState.value
                            if (storeState is com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired) {
                                throw IllegalStateException("Store recovery required")
                            }
                            val transaction = if (reservedDraft.stagedArtifactItem != null) {
                                val stagedItem = reservedDraft.stagedArtifactItem
                                modelDownloader.artifactStorage.beginTransaction(
                                    modelId = bundle.id,
                                    stagedItems = listOf(stagedItem),
                                    primaryItemName = stagedItem.relativeTargetName,
                                    sourceIdentity = "phone_import_${bundle.id}",
                                    packageType = "ArtifactBundle",
                                    capabilities = bundle.capabilities,
                                    format = bundle.format
                                )
                            } else null

                            val effectiveBundle: ModelBundle
                            val primaryLocalPath: String
                            val primaryArtifact: ModelArtifact
                            val effectiveType: ModelType
                            val sizeStr: String
                            try {
                                if (transaction != null) {
                                    val publishedFile = transaction.primaryFile
                                    val stagedItem = reservedDraft.stagedArtifactItem!!
                                    val publishedArtifact = ModelArtifact(
                                        role = ArtifactRole.Main,
                                        displayName = stagedItem.relativeTargetName,
                                        source = ArtifactSource.AppManaged(publishedFile.absolutePath),
                                        sizeBytes = publishedFile.length(),
                                        lastModified = publishedFile.lastModified()
                                    )
                                    primaryLocalPath = publishedFile.absolutePath
                                    effectiveBundle = bundle.copy(
                                        mainArtifacts = listOf(publishedArtifact),
                                        capabilities = bundle.capabilities.copy(thinkingArtifactIdentity = publishedFile.absolutePath)
                                    )
                                } else {
                                    val primary = bundle.mainArtifacts.firstOrNull()
                                        ?: throw IllegalArgumentException("Imported bundle has no main artifacts")
                                    primaryLocalPath = primary.source.location
                                    effectiveBundle = bundle
                                }

                                primaryArtifact = effectiveBundle.mainArtifacts.first()
                                effectiveType = if (effectiveBundle.capabilities.supportsVision || effectiveBundle.capabilities.supportsAudio) ModelType.Vision else ModelType.Text
                                val sizeMb = effectiveBundle.totalSizeBytes / (1024 * 1024)
                                sizeStr = if (sizeMb > 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"

                                val definition = CustomModelDefinition(
                                    id = effectiveBundle.id,
                                    name = effectiveBundle.displayName,
                                    url = "",
                                    type = effectiveType,
                                    fileName = primaryArtifact.displayName,
                                    packageType = ModelPackage.SingleFile,
                                    description = effectiveBundle.description,
                                    backend = effectiveBundle.backend,
                                    bundle = effectiveBundle
                                )
                                val saved = customModelStore.add(definition, isExplicitReimport = true)
                                if (!saved) {
                                    transaction?.rollback()
                                    throw IllegalStateException("Failed to persist model definition to custom model store")
                                }

                                // Durable registration boundary established: customModelStore now references the new generation!
                                // Journal/finalization failure must preserve that generation for recovery; it must not invoke destructive rollback.
                                try {
                                    transaction?.commit()
                                    reservedDraft.draftStagingDir?.deleteRecursively()
                                } catch (commitError: Throwable) {
                                    android.util.Log.e("MainViewModel", "Commit failed after durable registration for ${effectiveBundle.id}", commitError)
                                    ModelArtifactStorage.recordRecoveryRequired(
                                        effectiveBundle.id,
                                        "Commit failed after durable registration: ${commitError.message}"
                                    )
                                }
                            } catch (t: Throwable) {
                                val storeRecord = runCatching { customModelStore.getById(bundle.id) }.getOrNull()
                                val isReferencedByStore = storeRecord?.bundle?.allArtifacts?.any { art ->
                                    val loc = art.source.location
                                    transaction != null && loc.contains(transaction.newGenerationDirName)
                                } == true

                                if (!isReferencedByStore) {
                                    transaction?.rollback()
                                }
                                throw t
                            }

                            val rawModelInfo = ModelInfo(
                                id = effectiveBundle.id,
                                name = effectiveBundle.displayName,
                                size = sizeStr,
                                status = ModelStatus.Available,
                                progress = 1f,
                                localPath = primaryLocalPath,
                                type = effectiveType,
                                fileName = primaryArtifact.displayName,
                                packageType = ModelPackage.SingleFile,
                                description = effectiveBundle.description,
                                backend = effectiveBundle.backend,
                                deviceTarget = "All supported chipsets",
                                bundle = effectiveBundle,
                                supportsThinkingMode = (effectiveBundle.capabilities.thinking == ThinkingCapability.ModelControlled || effectiveBundle.capabilities.thinking == ThinkingCapability.Toggleable),
                                supportsVisionInput = effectiveBundle.capabilities.supportsVision,
                                supportsAudioInput = effectiveBundle.capabilities.supportsAudio,
                                contextWindowTokens = effectiveBundle.contextLength.takeIf { it > 0 }
                            )
                            val modelInfo = com.shounak.localmeshai.models.ModelLoadRestrictionPolicy.projectCompletedModel(getApplication(), rawModelInfo)

                            val isNewModel = availableModels.none { it.id == effectiveBundle.id }
                            hideModelForSession(sheetSessionId, effectiveBundle.id, isNewModel)

                            withContext(Dispatchers.Main.immediate) {
                                val existingIdx = availableModels.indexOfFirst { it.id == effectiveBundle.id }
                                if (existingIdx >= 0) {
                                    availableModels[existingIdx] = modelInfo
                                } else {
                                    availableModels.add(modelInfo)
                                }
                            }
                        }
                        phoneImportSession.onRegistrationSuccess(bundleUris)
                        _importCompletionEvent.value = ImportCompletionEvent(
                            sheetSessionId = sheetSessionId,
                            operationId = java.util.UUID.randomUUID().toString(),
                            modelId = bundle.id
                        )
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        phoneImportSession.onRegistrationFailure()
                        reservedDraft.draftStagingDir?.deleteRecursively()
                        regError = ModelOperationError(
                            reason = ModelErrorReason.RegistrationFailed,
                            stage = ModelOperationStage.Registration,
                            affectedFilenames = listOf(bundle.displayName),
                            diagnosticDetails = e.message ?: "Failed to persist model."
                        )
                    }
                }
            } finally {
                _isRegisteringLocalModel.value = false
                _localModelRegistrationError.value = regError
                _pendingImportDraft.value = null
            }
        }
    }

    val appSettings = com.shounak.localmeshai.utils.AppSettings.getInstance(application)
    val appSettingsData = appSettings.settings

    private val settingsPrefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _huggingFaceToken = MutableStateFlow(settingsPrefs.getString(KEY_HF_TOKEN, "").orEmpty())
    val huggingFaceToken = _huggingFaceToken.asStateFlow()

    private val customModelStore = CustomModelStore(application.filesDir)

    val availableModels = mutableStateListOf(*ModelCatalog.defaultModels.toTypedArray())
    val unsafeInitOverrideIds = mutableStateListOf<String>()
    val removingModelIds = mutableStateListOf<String>()
    val cancellingModelIds = mutableStateListOf<String>()
    private val _unloadingModelIds = MutableStateFlow<Set<String>>(emptySet())
    val unloadingModelIds = _unloadingModelIds.asStateFlow()

    private val inFlightValidations = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val validatedArtifactGenerations = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val latestSnapshotGenerations = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val inFlightValidationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val startupRecoveryDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    sealed class UpdateDownloadStatus {
        object Idle : UpdateDownloadStatus()
        data class Downloading(val progress: Float) : UpdateDownloadStatus()
        data class ReadyToInstall(val apkFile: File) : UpdateDownloadStatus()
        object Installing : UpdateDownloadStatus()
        data class Error(val message: String) : UpdateDownloadStatus()
    }

    private val _updateState = MutableStateFlow<com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult?>(null)
    val updateState = _updateState.asStateFlow()

    private val _updateDownloadStatus = MutableStateFlow<UpdateDownloadStatus>(UpdateDownloadStatus.Idle)
    val updateDownloadStatus = _updateDownloadStatus.asStateFlow()

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates = _isCheckingForUpdates.asStateFlow()

    private val _crashReports = MutableStateFlow<List<com.shounak.localmeshai.utils.CrashReport>>(emptyList())
    val crashReports = _crashReports.asStateFlow()

    private val _pendingCrashPrompt = MutableStateFlow<com.shounak.localmeshai.utils.CrashReport?>(null)
    val pendingCrashPrompt = _pendingCrashPrompt.asStateFlow()

    private val _settingsDialogVisible = MutableStateFlow(false)
    val settingsDialogVisible = _settingsDialogVisible.asStateFlow()

    private val _scrollToAboutRequested = MutableStateFlow(false)
    val scrollToAboutRequested = _scrollToAboutRequested.asStateFlow()

    init {
        DownloadStateStore.initialize(application)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat) {
            clearSelectedTextModel()
        }
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision) {
            clearSelectedVisionModel()
        }
        DownloadController.getInstance(application).inferenceShutdownAwaiter = { modelId ->
            val target = availableModels.firstOrNull { it.id == modelId }
            var shutdownOk = true
            val isTextActive = target != null && _selectedTextModelPath.value != null && (_selectedTextModelPath.value == target.localPath || _selectedTextModelPath.value == target.id || _selectedTextModelPath.value == modelId)
            val isVisionActive = target != null && _selectedVisionModelPath.value != null && (_selectedVisionModelPath.value == target.localPath || _selectedVisionModelPath.value == target.id || _selectedVisionModelPath.value == modelId)
            if (isTextActive || isVisionActive) {
                val res = ModelRuntimeCoordinator.releaseAll()
                if (res == com.shounak.localmeshai.ai.ShutdownResult.Success) {
                    if (isTextActive) _selectedTextModelPath.value = null
                    if (isVisionActive) _selectedVisionModelPath.value = null
                } else {
                    shutdownOk = false
                }
            }
            shutdownOk
        }
        observeDownloadState()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.shounak.localmeshai.utils.ModelRemovalCoordinator.recoverInterruptedDeletions(
                    getApplication(),
                    customModelStore,
                    modelDownloader
                )
                restoreCustomModels()
                checkLocalModels()
                reconcilePersistedDownloads()
            } finally {
                startupRecoveryDeferred.complete(Unit)
            }
            refreshCrashReports()
            checkPendingCrashReport()
        }

        viewModelScope.launch {
            appSettingsData.map { it.autoCheckUpdates }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        com.shounak.localmeshai.utils.AppUpdateManager.scheduleBackgroundUpdateCheck(getApplication())
                    } else {
                        com.shounak.localmeshai.utils.AppUpdateManager.cancelBackgroundUpdateCheck(getApplication())
                        com.shounak.localmeshai.utils.AppUpdateManager.cancelUpdateNotification(getApplication())
                    }
                }
        }

        if (appSettingsData.value.autoCheckUpdates) {
            checkForUpdates(silent = true)
        }
    }

    fun checkPendingCrashReport() {
        _pendingCrashPrompt.value = com.shounak.localmeshai.utils.CrashReportManager.getPendingCrashReportForPrompt(getApplication())
    }

    fun dismissPendingCrashPrompt() {
        _pendingCrashPrompt.value = null
        com.shounak.localmeshai.utils.CrashReportManager.clearPendingCrashPrompt(getApplication())
    }

    fun sendPendingCrashReport(context: Context? = null) {
        val report = _pendingCrashPrompt.value ?: return
        val targetContext = context ?: getApplication()
        dismissPendingCrashPrompt()
        com.shounak.localmeshai.utils.CrashReportManager.sendToGitHub(
            context = targetContext,
            reportText = report.details,
            titleHint = report.title
        )
    }

    fun refreshCrashReports() {
        _crashReports.value = com.shounak.localmeshai.utils.CrashReportManager.getAllReports(getApplication())
    }

    fun clearCrashReports() {
        com.shounak.localmeshai.utils.CrashReportManager.clearAllReports(getApplication())
        refreshCrashReports()
    }

    private var updateDownloadJob: kotlinx.coroutines.Job? = null
    private var updateCheckJob: kotlinx.coroutines.Job? = null

    fun checkForUpdates(silent: Boolean = false) {
        if (_isCheckingForUpdates.value) return
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            _isCheckingForUpdates.value = true
            val result = com.shounak.localmeshai.utils.AppUpdateManager.checkForUpdates(
                currentVersionName = com.shounak.localmeshai.BuildConfig.VERSION_NAME,
                context = getApplication()
            )
            _isCheckingForUpdates.value = false
            if (!silent || result is com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpdateAvailable) {
                _updateState.value = result
            }

            if (result is com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpToDate) {
                com.shounak.localmeshai.utils.AppUpdateManager.deleteDownloadedApks(getApplication())
                com.shounak.localmeshai.utils.AppUpdateManager.cancelUpdateNotification(getApplication())
            } else if (result is com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpdateAvailable) {
                if (appSettingsData.value.autoCheckUpdates) {
                    com.shounak.localmeshai.utils.AppUpdateManager.sendUpdateAvailableNotification(
                        context = getApplication(),
                        updateInfo = result.updateInfo
                    )
                }
            }
        }
    }

    fun downloadAndInstallUpdate(version: String, downloadUrl: String) {
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            _updateDownloadStatus.value = UpdateDownloadStatus.Downloading(0f)
            val result = com.shounak.localmeshai.utils.AppUpdateManager.downloadApk(
                context = getApplication(),
                downloadUrl = downloadUrl,
                version = version,
                onProgress = { progress ->
                    _updateDownloadStatus.value = UpdateDownloadStatus.Downloading(progress)
                }
            )
            result.fold(
                onSuccess = { apkFile ->
                    _updateDownloadStatus.value = UpdateDownloadStatus.ReadyToInstall(apkFile)
                    val launched = com.shounak.localmeshai.utils.AppUpdateManager.installApk(getApplication(), apkFile)
                    if (launched) {
                        _updateDownloadStatus.value = UpdateDownloadStatus.Installing
                    } else {
                        _updateDownloadStatus.value = UpdateDownloadStatus.Error("Failed to verify or launch package installer")
                    }
                },
                onFailure = { error ->
                    _updateDownloadStatus.value = UpdateDownloadStatus.Error(error.localizedMessage ?: "Download failed")
                }
            )
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        _updateDownloadStatus.value = UpdateDownloadStatus.Idle
    }

    fun dismissUpdateState() {
        cancelUpdateDownload()
        _updateState.value = null
        _updateDownloadStatus.value = UpdateDownloadStatus.Idle
    }

    fun openSettings(scrollToAbout: Boolean = false) {
        _settingsDialogVisible.value = true
        _scrollToAboutRequested.value = scrollToAbout
    }

    fun dismissSettings() {
        _settingsDialogVisible.value = false
        _scrollToAboutRequested.value = false
    }

    fun onScrolledToAbout() {
        _scrollToAboutRequested.value = false
    }

    fun setUpdateAvailable(updateInfo: com.shounak.localmeshai.utils.AppUpdateManager.UpdateInfo) {
        _updateState.value = com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpdateAvailable(updateInfo)
    }

    private suspend fun reconcilePersistedDownloads() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        DownloadStateStore.snapshots.value.values.forEach { snapshot ->
            val model = availableModels.firstOrNull { it.id == snapshot.modelId } ?: return@forEach
            val target = modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
            if (target != null && target.exists() && (target.isDirectory || target.length() > 0L)) {
                val validationError = localModelValidationError(model, target)
                val fileTime = target.lastModified()
                val dlTime = if (snapshot.downloadedAt > 0L) snapshot.downloadedAt else fileTime
                DownloadStateStore.update(
                    snapshot.copy(
                        status = if (validationError == null) ModelStatus.Available else ModelStatus.Failed,
                        progress = if (validationError == null) 1f else snapshot.progress,
                        downloadedBytes = if (target.isFile) target.length() else snapshot.downloadedBytes,
                        bytesPerSecond = 0L,
                        localPath = target.absolutePath,
                        errorMessage = validationError,
                        downloadedAt = dlTime
                    )
                )
                return@forEach
            }
            if (snapshot.status != ModelStatus.Downloading) return@forEach
            val downloadUrl = model.url ?: return@forEach
            ModelDownloadService.start(
                context = getApplication(),
                modelId = model.id,
                name = model.name,
                url = downloadUrl,
                fileName = model.fileName,
                packageType = model.packageType,
                token = _huggingFaceToken.value.ifBlank { null },
                sha256 = model.sha256
            )
        }
    }

    private suspend fun checkLocalModels() {
        withContext(Dispatchers.IO) {
            val modelsToCheck = availableModels.toList()
            for (model in modelsToCheck) {
                if (model.isFuturePlaceholder) continue
                if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeleted(model.id)) continue
                val target = modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
                if (target != null && target.exists() && (target.isDirectory || target.length() > 0L)) {
                    val inspectedRevision = getArtifactRevision(model, target)
                    val validationError = localModelValidationError(model, target)
                    val updatedModel = if (validationError != null) {
                        model.copy(
                            status = ModelStatus.Failed,
                            progress = 0f,
                            localPath = target.absolutePath,
                            errorMessage = validationError
                        )
                    } else {
                        val fileTime = target.lastModified()
                        val existingSnapshot = DownloadStateStore.get(model.id)
                        val dlTime = if ((existingSnapshot?.downloadedAt ?: 0L) > 0L) existingSnapshot!!.downloadedAt else fileTime

                        val outcome = inspectBundleAuthoritatively(model, target)
                        val effectiveBundle = if (outcome.isValid) outcome.bundle else model.bundle
                        val effectiveCaps = if (outcome.isValid) outcome.capabilities else (effectiveBundle?.capabilities ?: ModelCapabilities())
                        val effectiveThinking = (effectiveCaps.thinking == ThinkingCapability.ModelControlled || effectiveCaps.thinking == ThinkingCapability.Toggleable)
                        val effectiveVision = effectiveCaps.supportsVision
                        val effectiveAudio = effectiveCaps.supportsAudio
                        val effectiveContext = if (outcome.isValid && outcome.contextLength > 0) outcome.contextLength else (model.contextWindowTokens ?: 2048)
                        val effectiveType = if (effectiveVision || effectiveAudio) ModelType.Vision else ModelType.Text

                        val rawCompleted = model.copy(
                            status = if (outcome.isValid) ModelStatus.Available else (if (outcome.error != null) ModelStatus.Failed else ModelStatus.Available),
                            progress = 1f,
                            localPath = target.absolutePath,
                            errorMessage = if (outcome.isValid) null else outcome.error,
                            downloadedAt = dlTime,
                            bundle = effectiveBundle,
                            type = effectiveType,
                            supportsThinkingMode = effectiveThinking,
                            supportsVisionInput = effectiveVision,
                            supportsAudioInput = effectiveAudio,
                            contextWindowTokens = effectiveContext
                        )
                        com.shounak.localmeshai.models.ModelLoadRestrictionPolicy.projectCompletedModel(getApplication(), rawCompleted)
                    }

                    withContext(Dispatchers.Main.immediate) {
                        val idx = availableModels.indexOfFirst { it.id == updatedModel.id }
                        if (idx >= 0) {
                            val current = availableModels[idx]
                            val currentRev = getArtifactRevision(current, target)
                            if (currentRev == inspectedRevision) {
                                availableModels[idx] = updatedModel
                            }
                        }
                    }
                }
            }
        }
    }

    fun selectTextModel(path: String) {
        val model = availableModels.firstOrNull { it.id == path || it.localPath == path }
        if (model != null && ModelArtifactStorage.isRecoveryRequired(model.id)) {
            val reason = ModelArtifactStorage.getRecoveryRequiredReason(model.id)
            val updated = model.copy(
                status = ModelStatus.Failed,
                errorMessage = "Model files require recovery reconciliation: ${reason ?: "interrupted transaction"}. Loading is blocked."
            )
            val idx = availableModels.indexOfFirst { it.id == model.id }
            if (idx >= 0) availableModels[idx] = updated
            _selectedTextModelPath.value = null
            textSelectionRevision.incrementAndGet()
            return
        }
        val isCrashBlocked = model?.isCrashBlocked == true || (model != null && InitCrashGuard.isModelBlocked(getApplication(), model.id))
        val isUnsafeAllowed = model != null && !isCrashBlocked && unsafeInitOverrideIds.contains(model.id)
        if (model != null && (model.status == ModelStatus.Failed || (model.status == ModelStatus.Blocked && !isUnsafeAllowed))) {
            _selectedTextModelPath.value = null
            textSelectionRevision.incrementAndGet()
            return
        }
        val targetPath = model?.localPath ?: path
        if (model?.isMultimodal == true) {
            _selectedVisionModelPath.value = targetPath
            visionSelectionRevision.incrementAndGet()
            _selectedTextModelPath.value = null
            textSelectionRevision.incrementAndGet()
            return
        }
        _selectedTextModelPath.value = targetPath
        textSelectionRevision.incrementAndGet()
        _selectedVisionModelPath.value = null
        visionSelectionRevision.incrementAndGet()
    }

    fun selectVisionModel(path: String) {
        val model = availableModels.firstOrNull { it.id == path || it.localPath == path }
        if (model != null && ModelArtifactStorage.isRecoveryRequired(model.id)) {
            val reason = ModelArtifactStorage.getRecoveryRequiredReason(model.id)
            val updated = model.copy(
                status = ModelStatus.Failed,
                errorMessage = "Model files require recovery reconciliation: ${reason ?: "interrupted transaction"}. Loading is blocked."
            )
            val idx = availableModels.indexOfFirst { it.id == model.id }
            if (idx >= 0) availableModels[idx] = updated
            _selectedVisionModelPath.value = null
            visionSelectionRevision.incrementAndGet()
            return
        }
        val isCrashBlocked = model?.isCrashBlocked == true || (model != null && InitCrashGuard.isModelBlocked(getApplication(), model.id))
        val isUnsafeAllowed = model != null && !isCrashBlocked && unsafeInitOverrideIds.contains(model.id)
        if (model != null && (model.status == ModelStatus.Failed || (model.status == ModelStatus.Blocked && !isUnsafeAllowed))) {
            _selectedVisionModelPath.value = null
            visionSelectionRevision.incrementAndGet()
            return
        }
        val targetPath = model?.localPath ?: path
        if (model != null && !model.isMultimodal) {
            _selectedTextModelPath.value = targetPath
            textSelectionRevision.incrementAndGet()
            _selectedVisionModelPath.value = null
            visionSelectionRevision.incrementAndGet()
            return
        }
        _selectedVisionModelPath.value = targetPath
        visionSelectionRevision.incrementAndGet()
        _selectedTextModelPath.value = null
        textSelectionRevision.incrementAndGet()
    }

    /** Called by ChatViewModel when the coordinator force-releases the chat model. */
    fun clearSelectedTextModel() {
        _selectedTextModelPath.value = null
        textSelectionRevision.incrementAndGet()
    }

    /** Called by VisionViewModel when the coordinator force-releases the vision model. */
    fun clearSelectedVisionModel() {
        _selectedVisionModelPath.value = null
        visionSelectionRevision.incrementAndGet()
    }

    fun unloadModel(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        if (_unloadingModelIds.value.contains(modelId)) return
        _unloadingModelIds.update { it + modelId }
        viewModelScope.launch {
            try {
                val isTextSelected = _selectedTextModelPath.value != null &&
                    (_selectedTextModelPath.value == model.localPath || _selectedTextModelPath.value == model.id || _selectedTextModelPath.value == modelId)
                val isVisionSelected = _selectedVisionModelPath.value != null &&
                    (_selectedVisionModelPath.value == model.localPath || _selectedVisionModelPath.value == model.id || _selectedVisionModelPath.value == modelId)

                val isTextRuntimeActive = isTextSelected && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat)
                val isVisionRuntimeActive = isVisionSelected && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision)

                // 1. Text model handling
                if (isTextSelected) {
                    val capturedChatRevision = textSelectionRevision.get()
                    val expectedChatToken = ModelRuntimeCoordinator.getActiveToken(ModelRuntimeOwner.Chat)
                    val expectedChatOpId = ModelRuntimeCoordinator.getActiveLoadOperationId(ModelRuntimeOwner.Chat).orEmpty()

                    if (!isTextRuntimeActive) {
                        // Already unloaded in native runtime: clear selection if revision still matches
                        if (textSelectionRevision.get() == capturedChatRevision) {
                            _selectedTextModelPath.value = null
                            textSelectionRevision.incrementAndGet()
                        }
                    } else {
                        val res = ModelRuntimeCoordinator.releaseIfActive(
                            owner = ModelRuntimeOwner.Chat,
                            expectedModelId = modelId,
                            expectedModelPath = model.localPath.orEmpty(),
                            expectedLoadOperationId = expectedChatOpId,
                            expectedToken = expectedChatToken
                        )
                        when (res) {
                            is com.shounak.localmeshai.ai.ShutdownResult.Success -> {
                                if (textSelectionRevision.get() == capturedChatRevision) {
                                    _selectedTextModelPath.value = null
                                    textSelectionRevision.incrementAndGet()
                                }
                                updateModel(modelId) { it.copy(errorMessage = null) }
                            }
                            is com.shounak.localmeshai.ai.ShutdownResult.TimedOut -> {
                                val msg = "Eject timed out. Native engine did not respond within 10s. Please restart the app."
                                android.util.Log.w("MainViewModel", "Chat eject timed out for $modelId")
                                updateModel(modelId) { it.copy(errorMessage = msg) }
                            }
                            is com.shounak.localmeshai.ai.ShutdownResult.Failed -> {
                                val err = res.error
                                val msg = "Eject failed: ${err.message ?: err.toString()}"
                                android.util.Log.e("MainViewModel", "Chat eject failed for $modelId: $msg", err)
                                updateModel(modelId) { it.copy(errorMessage = msg) }
                            }
                            is com.shounak.localmeshai.ai.ShutdownResult.StaleNoOp -> {
                                // A stale result must NEVER clear selection
                            }
                        }
                    }
                }

                // 2. Vision model handling
                if (isVisionSelected) {
                    val capturedVisionRevision = visionSelectionRevision.get()
                    val expectedVisionToken = ModelRuntimeCoordinator.getActiveToken(ModelRuntimeOwner.Vision)
                    val expectedVisionOpId = ModelRuntimeCoordinator.getActiveLoadOperationId(ModelRuntimeOwner.Vision).orEmpty()

                    if (!isVisionRuntimeActive) {
                        // Already unloaded in native runtime: clear selection if revision still matches
                        if (visionSelectionRevision.get() == capturedVisionRevision) {
                            _selectedVisionModelPath.value = null
                            visionSelectionRevision.incrementAndGet()
                        }
                    } else {
                        val res = ModelRuntimeCoordinator.releaseIfActive(
                            owner = ModelRuntimeOwner.Vision,
                            expectedModelId = modelId,
                            expectedModelPath = model.localPath.orEmpty(),
                            expectedLoadOperationId = expectedVisionOpId,
                            expectedToken = expectedVisionToken
                        )
                        when (res) {
                            is com.shounak.localmeshai.ai.ShutdownResult.Success -> {
                                if (visionSelectionRevision.get() == capturedVisionRevision) {
                                    _selectedVisionModelPath.value = null
                                    visionSelectionRevision.incrementAndGet()
                                }
                                updateModel(modelId) { it.copy(errorMessage = null) }
                            }
                            is com.shounak.localmeshai.ai.ShutdownResult.TimedOut -> {
                                val msg = "Eject timed out. Native vision engine did not respond within 10s. Please restart the app."
                                android.util.Log.w("MainViewModel", "Vision eject timed out for $modelId")
                                updateModel(modelId) { it.copy(errorMessage = msg) }
                            }
                            is com.shounak.localmeshai.ai.ShutdownResult.Failed -> {
                                val err = res.error
                                val isMediaPipeRestart = err.message?.contains("restart", ignoreCase = true) == true
                                val msg = if (isMediaPipeRestart) {
                                    "Eject requires app restart: MediaPipe vision engine retains native session state."
                                } else {
                                    "Eject failed: ${err.message ?: err.toString()}"
                                }
                                android.util.Log.e("MainViewModel", "Vision eject failed for $modelId: $msg", err)
                                updateModel(modelId) { it.copy(errorMessage = msg) }
                            }
                            is com.shounak.localmeshai.ai.ShutdownResult.StaleNoOp -> {
                                // A stale result must NEVER clear selection
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MainViewModel", "Error ejecting model $modelId", e)
                updateModel(modelId) { it.copy(errorMessage = "Eject error: ${e.message}") }
            } finally {
                _unloadingModelIds.update { it - modelId }
            }
        }
    }

    fun setHuggingFaceToken(token: String) {
        val cleanToken = token.trim()
        _huggingFaceToken.value = cleanToken
        settingsPrefs.edit().putString(KEY_HF_TOKEN, cleanToken).apply()
    }

    fun startDownload(modelId: String): Result<Unit> {
        val model = availableModels.firstOrNull { it.id == modelId }
            ?: return Result.failure(IllegalArgumentException("Model not found: $modelId"))
        if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(modelId) || com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(modelId)) {
            val msg = "Model is pending deletion or has been removed."
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.Failed,
                    errorMessage = msg
                )
            }
            return Result.failure(IllegalStateException(msg))
        }
        if (model.status == ModelStatus.Available && model.localPath != null) {
            return Result.success(Unit)
        }
        if (model.isFuturePlaceholder) {
            val msg = "No Android-ready .task or .litertlm artifact is available for this model yet."
            updateModel(modelId) {
                it.copy(errorMessage = msg)
            }
            return Result.failure(IllegalStateException(msg))
        }
        val downloadUrl = model.url ?: model.downloadSpec?.artifacts?.firstOrNull()?.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            val msg = "Add a direct model URL before downloading this model."
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.Failed,
                    errorMessage = msg
                )
            }
            return Result.failure(IllegalStateException(msg))
        }
        if (model.requiresHuggingFaceToken && _huggingFaceToken.value.isBlank()) {
            val msg = "This model requires a Hugging Face token. Open Settings (⚙️) → Hugging Face Access Token to paste your token."
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.NotDownloaded,
                    errorMessage = msg
                )
            }
            return Result.failure(IllegalStateException(msg))
        }

        if (!com.shounak.localmeshai.utils.NetworkUtils.isConnected(getApplication())) {
            val msg = "No internet connection. Please connect to Wi-Fi or mobile data to download models."
            updateModel(modelId) {
                it.copy(
                    status = if (it.downloadedBytes > 0L) ModelStatus.Paused else ModelStatus.NotDownloaded,
                    errorMessage = msg
                )
            }
            return Result.failure(java.io.IOException(msg))
        }

        updateModel(modelId) {
            it.copy(
                status = ModelStatus.Downloading,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = -1L,
                bytesPerSecond = 0L,
                errorMessage = null,
                loadRestriction = null
            )
        }

        return try {
            ModelDownloadService.start(
                context = getApplication(),
                modelId = model.id,
                name = model.name,
                url = downloadUrl,
                fileName = model.fileName,
                packageType = model.packageType,
                token = _huggingFaceToken.value.ifBlank { null },
                sha256 = model.sha256,
                downloadSpec = model.downloadSpec,
                retainStagingOnCancel = model.isCustom
            )
            Result.success(Unit)
        } catch (e: Exception) {
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.Failed,
                    errorMessage = "Download enqueue failed: ${e.message}. Tap to retry."
                )
            }
            Result.failure(e)
        }
    }

    fun tryModelAnyway(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val isCrash = InitCrashGuard.isModelBlocked(getApplication(), model.id) ||
                model.loadRestriction?.category == com.shounak.localmeshai.models.LoadRestrictionCategory.PreviousCrash
            if (isCrash) {
                withContext(Dispatchers.Main.immediate) {
                    updateModel(modelId) {
                        it.copy(
                            status = ModelStatus.Blocked,
                            loadRestriction = com.shounak.localmeshai.models.LoadRestriction(
                                reason = InitCrashGuard.blockedModelMessage(),
                                category = com.shounak.localmeshai.models.LoadRestrictionCategory.PreviousCrash
                            ),
                            errorMessage = InitCrashGuard.blockedModelMessage()
                        )
                    }
                }
                return@launch
            }

            if (model.isStorageImport) {
                val bundle = model.bundle
                if (bundle == null || !ModelSourceResolver.checkBundleAccessible(getApplication(), bundle)) {
                    withContext(Dispatchers.Main.immediate) {
                        updateModel(modelId) {
                            it.copy(
                                status = ModelStatus.Failed,
                                errorMessage = "Files moved or storage permission revoked. Please relink files."
                            )
                        }
                    }
                    return@launch
                }
            } else {
                val target = modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
                if (target == null || !target.exists() || (target.isFile && target.length() < MIN_VALID_MODEL_BYTES)) {
                    withContext(Dispatchers.Main.immediate) {
                        updateModel(modelId) {
                            it.copy(
                                status = if (it.downloadedBytes > 0L) ModelStatus.Paused else ModelStatus.NotDownloaded,
                                errorMessage = "Model file is missing or incomplete. Download or resume the file first."
                            )
                        }
                    }
                    return@launch
                }
                val validationError = localModelValidationError(model, target)
                if (validationError != null) {
                    withContext(Dispatchers.Main.immediate) {
                        updateModel(modelId) {
                            it.copy(
                                status = ModelStatus.Failed,
                                errorMessage = validationError
                            )
                        }
                    }
                    return@launch
                }
            }

            val targetPath = model.localPath
                ?: modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)?.takeIf { it.exists() }?.absolutePath
            if (targetPath.isNullOrBlank()) {
                return@launch
            }

            withContext(Dispatchers.Main.immediate) {
                if (model.localPath.isNullOrBlank()) {
                    updateModel(modelId) { it.copy(localPath = targetPath) }
                }
                if (!unsafeInitOverrideIds.contains(model.id)) {
                    unsafeInitOverrideIds.add(model.id)
                }
                when (model.type) {
                    ModelType.Text -> selectTextModel(targetPath)
                    ModelType.Vision -> selectVisionModel(targetPath)
                }
            }
        }
    }

    fun pauseDownload(modelId: String) {
        ModelDownloadService.pause(getApplication(), modelId)
    }

    fun cancelDownload(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        if (cancellingModelIds.contains(modelId)) return
        cancellingModelIds.add(modelId)
        viewModelScope.launch {
            try {
                unsafeInitOverrideIds.remove(model.id)
                if (model.isCustom) {
                    DownloadController.getInstance(getApplication()).cancelDownload(model.id, retainStaging = true)
                } else {
                    DownloadController.getInstance(getApplication()).cancelDownload(model.id)
                }
            } finally {
                cancellingModelIds.remove(modelId)
            }
        }
    }

    fun resumeDownload(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        if (!com.shounak.localmeshai.utils.NetworkUtils.isConnected(getApplication())) {
            updateModel(modelId) {
                it.copy(
                    errorMessage = "No internet connection. Please connect to Wi-Fi or mobile data to resume download."
                )
            }
            return
        }
        val downloadUrl = model.url ?: model.downloadSpec?.artifacts?.firstOrNull()?.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.Failed,
                    errorMessage = "No valid download URL found to resume this model."
                )
            }
            return
        }
        updateModel(modelId) {
            it.copy(
                status = ModelStatus.Downloading,
                errorMessage = null
            )
        }
        ModelDownloadService.resume(
            context = getApplication(),
            modelId = model.id,
            name = model.name,
            url = downloadUrl,
            fileName = model.fileName,
            packageType = model.packageType,
            token = _huggingFaceToken.value.ifBlank { null },
            sha256 = model.sha256,
            downloadSpec = model.downloadSpec
        )
    }

    fun deleteModel(modelId: String) {
        if (removingModelIds.contains(modelId)) return
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        removingModelIds.add(modelId)
        viewModelScope.launch {
            try {
                val retained = availableModels.filter { it.id != model.id }
                val isModelSelected: (String) -> Boolean = { id ->
                    val textSelected = _selectedTextModelPath.value != null &&
                        (_selectedTextModelPath.value == model.localPath || _selectedTextModelPath.value == model.id || _selectedTextModelPath.value == id)
                    val visionSelected = _selectedVisionModelPath.value != null &&
                        (_selectedVisionModelPath.value == model.localPath || _selectedVisionModelPath.value == model.id || _selectedVisionModelPath.value == id)
                    textSelected || visionSelected
                }
                val clearSelection: () -> Unit = {
                    if (_selectedTextModelPath.value != null &&
                        (_selectedTextModelPath.value == model.localPath || _selectedTextModelPath.value == model.id)) {
                        _selectedTextModelPath.value = null
                    }
                    if (_selectedVisionModelPath.value != null &&
                        (_selectedVisionModelPath.value == model.localPath || _selectedVisionModelPath.value == model.id)) {
                        _selectedVisionModelPath.value = null
                    }
                }

                val result = com.shounak.localmeshai.utils.ModelRemovalCoordinator.executeRemovalSequence(
                    context = getApplication(),
                    model = model,
                    customModelStore = customModelStore,
                    downloader = modelDownloader,
                    isModelSelected = isModelSelected,
                    clearSelection = clearSelection,
                    retainedModels = retained
                )

                if (result.isSuccess) {
                    unsafeInitOverrideIds.remove(model.id)
                    val shouldRemoveFromStore = model.isCustom || model.isStorageImport || model.id.startsWith("custom_")
                    if (shouldRemoveFromStore) {
                        availableModels.removeAll { it.id == model.id }
                    } else {
                        updateModel(modelId) {
                            it.copy(
                                status = ModelStatus.NotDownloaded,
                                loadRestriction = null,
                                progress = 0f,
                                downloadedBytes = 0L,
                                totalBytes = -1L,
                                bytesPerSecond = 0L,
                                localPath = null,
                                errorMessage = null,
                                downloadedAt = 0L
                            )
                        }
                    }
                } else {
                    val error = result.error
                    val failureMsg = "Failed to remove model: ${error?.message ?: "Unknown error"}"
                    updateModel(modelId) {
                        it.copy(errorMessage = failureMsg)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MainViewModel", "Error in deleteModel for $modelId", e)
                updateModel(modelId) {
                    it.copy(errorMessage = "Cannot remove model: ${e.message ?: "Unknown error"}")
                }
            } finally {
                removingModelIds.remove(modelId)
            }
        }
    }

    private suspend fun restoreCustomModels() {
        val loadState = customModelStore.loadAsync()
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication())
            .updateCustomModelStoreState(loadState)
        val isStoreAvailable = (loadState is com.shounak.localmeshai.data.StoreLoadState.Ready)
        val storeForRecovery = if (isStoreAvailable) customModelStore else null
        modelDownloader.artifactStorage.recoverPendingTransactions(storeForRecovery, isStoreAvailable = isStoreAvailable)
        if (loadState !is com.shounak.localmeshai.data.StoreLoadState.Ready) {
            return
        }
        val customDefinitions = customModelStore.getAll()
        val toAdd = mutableListOf<ModelInfo>()
        for (def in customDefinitions) {
            if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(def.id)) continue
            if (availableModels.none { it.id == def.id }) {
                val isStorage = def.bundle?.isStorageImport == true
                var publishedBundle = if (!isStorage) modelDownloader.artifactStorage.getPublishedBundle(def.id) else null
                var isAppManagedBundle = !isStorage && publishedBundle != null
                var effectiveBundle = if (isStorage) def.bundle else publishedBundle

                if (!isStorage && publishedBundle == null && def.bundle != null) {
                    if (ModelSourceResolver.checkBundleAccessible(getApplication(), def.bundle)) {
                        val migratedBundle = runCatching {
                            val stagingDir = modelDownloader.artifactStorage.getStagingDir(def.id)
                            stagingDir.mkdirs()
                            val stagedItems = def.bundle.allArtifacts.mapIndexed { idx, artifact ->
                                val src = (artifact.source as? ArtifactSource.AppManaged)?.path ?: return@mapIndexed null
                                val srcFile = File(src)
                                if (!srcFile.exists()) return@mapIndexed null
                                val stagedFile = File(stagingDir, "${ModelArtifactStorage.sanitizeFileName(srcFile.name)}.tmp")
                                srcFile.copyTo(stagedFile, overwrite = true)
                                StagedArtifactItem(
                                    stagedFile = stagedFile,
                                    relativeTargetName = ModelArtifactStorage.sanitizeFileName(srcFile.name),
                                    isPrimary = artifact.role == ArtifactRole.Main,
                                    role = artifact.role,
                                    order = idx
                                )
                            }.filterNotNull()

                            if (stagedItems.isNotEmpty()) {
                                val primary = stagedItems.firstOrNull { it.isPrimary } ?: stagedItems.first()
                                val transaction = modelDownloader.artifactStorage.beginTransaction(
                                    modelId = def.id,
                                    stagedItems = stagedItems,
                                    primaryItemName = primary.relativeTargetName,
                                    sourceIdentity = "restored_import_${def.id}",
                                    packageType = "ArtifactBundle",
                                    capabilities = def.bundle.capabilities,
                                    format = def.bundle.format
                                )
                                try {
                                    val newBundle = modelDownloader.artifactStorage.getPublishedBundle(def.id)
                                    if (newBundle != null) {
                                        val updated = customModelStore.updateBundle(def.id, newBundle)
                                        if (updated) {
                                            try {
                                                transaction.commit()
                                                for (artifact in def.bundle.allArtifacts) {
                                                    val oldPath = (artifact.source as? ArtifactSource.AppManaged)?.path
                                                    if (oldPath != null) {
                                                        val otherReferencing = customDefinitions.filter { it.id != def.id }.any { other ->
                                                            other.bundle?.allArtifacts?.any { (it.source as? ArtifactSource.AppManaged)?.path == oldPath } == true
                                                        }
                                                        if (!otherReferencing) {
                                                            runCatching { File(oldPath).delete() }
                                                        }
                                                    }
                                                }
                                            } catch (commitError: Throwable) {
                                                android.util.Log.e("MainViewModel", "Commit failed after store update for ${def.id}", commitError)
                                                ModelArtifactStorage.recordRecoveryRequired(
                                                    def.id,
                                                    "Commit failed after store update: ${commitError.message}"
                                                )
                                            }
                                            newBundle
                                        } else {
                                            transaction.rollback()
                                            null
                                        }
                                    } else {
                                        transaction.rollback()
                                        null
                                    }
                                } catch (e: Throwable) {
                                    transaction.rollback()
                                    null
                                }
                            } else null
                        }.getOrNull()

                        publishedBundle = migratedBundle ?: def.bundle
                        isAppManagedBundle = true
                        effectiveBundle = publishedBundle
                    }
                }

                val isRecoveryRequired = ModelArtifactStorage.isRecoveryRequired(def.id)
                val recoveryReason = if (isRecoveryRequired) ModelArtifactStorage.getRecoveryRequiredReason(def.id) else null

                val isAccessible = when {
                    isStorage && effectiveBundle != null -> ModelSourceResolver.checkBundleAccessible(getApplication(), effectiveBundle)
                    isAppManagedBundle -> true
                    else -> false
                }
                val totalBytes = effectiveBundle?.totalSizeBytes ?: def.bundle?.totalSizeBytes ?: 0L
                val savedSnapshot = DownloadStateStore.get(def.id)
                val partialBytes = if (!isStorage && !isAppManagedBundle) {
                    maxOf(savedSnapshot?.downloadedBytes ?: 0L, modelDownloader.getPartialDownloadBytes(def.id))
                } else 0L

                val sizeMb = totalBytes / (1024 * 1024)
                val sizeStr = if (totalBytes > 0L) {
                    if (sizeMb > 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"
                } else "Custom"
                val initialStatus = when {
                    isRecoveryRequired -> ModelStatus.Failed
                    isStorage && isAccessible -> ModelStatus.Available
                    isStorage && !isAccessible -> ModelStatus.Failed
                    isAppManagedBundle -> ModelStatus.Available
                    savedSnapshot?.status == ModelStatus.Paused || partialBytes > 0L -> ModelStatus.Paused
                    savedSnapshot != null && savedSnapshot.status != ModelStatus.NotDownloaded -> savedSnapshot.status
                    else -> ModelStatus.NotDownloaded
                }
                val initialError = when {
                    isRecoveryRequired -> "Model files require recovery reconciliation: ${recoveryReason ?: "interrupted transaction"}. Loading is blocked."
                    isStorage && !isAccessible -> "Files moved or storage permission revoked. Please relink files."
                    initialStatus == ModelStatus.Paused -> savedSnapshot?.errorMessage ?: "Paused — download kept"
                    else -> savedSnapshot?.errorMessage
                }
                val initialProgress = when {
                    (isStorage && isAccessible) || isAppManagedBundle -> 1f
                    initialStatus == ModelStatus.Paused -> {
                        if (totalBytes > 0L) (partialBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                        else savedSnapshot?.progress ?: 0f
                    }
                    else -> savedSnapshot?.progress ?: 0f
                }
                val localPath = when {
                    isStorage -> effectiveBundle?.mainArtifacts?.firstOrNull()?.source?.location ?: def.id
                    isAppManagedBundle -> publishedBundle?.mainArtifacts?.firstOrNull()?.source?.location ?: modelDownloader.artifactStorage.getPublishedArtifact(def.id)?.absolutePath
                    else -> null
                }
                val effectiveType = if (effectiveBundle?.capabilities?.supportsVision == true || effectiveBundle?.capabilities?.supportsAudio == true || def.bundle?.capabilities?.supportsVision == true) ModelType.Vision else def.type
                val rawModel = ModelInfo(
                    id = def.id,
                    name = def.name,
                    size = sizeStr,
                    status = initialStatus,
                    progress = initialProgress,
                    downloadedBytes = if (initialStatus == ModelStatus.Paused) partialBytes else savedSnapshot?.downloadedBytes ?: 0L,
                    totalBytes = if (totalBytes > 0L) totalBytes else savedSnapshot?.totalBytes ?: -1L,
                    localPath = localPath,
                    errorMessage = initialError,
                    type = effectiveType,
                    fileName = def.fileName,
                    packageType = def.packageType,
                    description = def.description,
                    backend = def.backend,
                    deviceTarget = def.deviceTarget,
                    url = def.url,
                    sha256 = def.sha256,
                    requiresHuggingFaceToken = ModelDownloadUrlPolicy.requiresHuggingFaceToken(def.url),
                    bundle = effectiveBundle,
                    supportsThinkingMode = (effectiveBundle?.capabilities?.thinking == ThinkingCapability.ModelControlled || effectiveBundle?.capabilities?.thinking == ThinkingCapability.Toggleable || def.bundle?.capabilities?.thinking == ThinkingCapability.ModelControlled || def.bundle?.capabilities?.thinking == ThinkingCapability.Toggleable),
                    supportsVisionInput = (effectiveBundle?.capabilities?.supportsVision == true || def.bundle?.capabilities?.supportsVision == true),
                    supportsAudioInput = (effectiveBundle?.capabilities?.supportsAudio == true || def.bundle?.capabilities?.supportsAudio == true),
                    contextWindowTokens = effectiveBundle?.contextLength?.takeIf { it > 0 },
                    downloadSpec = def.downloadSpec,
                    modelPageUrl = resolveModelPageUrl(def.downloadSpec, def.url)
                )
                val finalModel = if (initialStatus == ModelStatus.Available) {
                    com.shounak.localmeshai.models.ModelLoadRestrictionPolicy.projectCompletedModel(getApplication(), rawModel)
                } else {
                    rawModel
                }
                toAdd.add(finalModel)
            }
        }
        if (toAdd.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                for (model in toAdd) {
                    if (availableModels.none { it.id == model.id }) {
                        availableModels.add(model)
                    }
                }
            }

            val modelsNeedingRefresh = toAdd.filter { model ->
                val caps = model.bundle?.capabilities
                caps == null ||
                    caps.thinkingDetectorVersion < ThinkingModeConfig.DETECTOR_VERSION ||
                    caps.thinkingArtifactIdentity.isBlank()
            }
            if (modelsNeedingRefresh.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    for (model in modelsNeedingRefresh) {
                        val outcome = inspectBundleAuthoritatively(model)
                        if (outcome.isValid) {
                            val refreshedBundle = outcome.bundle
                            val refreshedCaps = outcome.capabilities
                            val updated = customModelStore.updateBundle(model.id, refreshedBundle)
                            if (updated) {
                                val effectiveType = if (refreshedCaps.supportsVision || refreshedCaps.supportsAudio) ModelType.Vision else model.type
                                val supportsThinking = (refreshedCaps.thinking == ThinkingCapability.ModelControlled || refreshedCaps.thinking == ThinkingCapability.Toggleable)
                                val updatedModel = model.copy(
                                    bundle = refreshedBundle,
                                    type = effectiveType,
                                    supportsThinkingMode = supportsThinking,
                                    supportsVisionInput = refreshedCaps.supportsVision,
                                    supportsAudioInput = refreshedCaps.supportsAudio,
                                    contextWindowTokens = refreshedBundle.contextLength.takeIf { it > 0 }
                                )
                                val projected = com.shounak.localmeshai.models.ModelLoadRestrictionPolicy.projectCompletedModel(getApplication<Application>(), updatedModel)
                                withContext(Dispatchers.Main.immediate) {
                                    val idx = availableModels.indexOfFirst { it.id == model.id }
                                    if (idx >= 0) {
                                        val current = availableModels[idx]
                                        val currentRev = getArtifactRevision(current, null)
                                        val inspectedRev = getArtifactRevision(model, null)
                                        if (currentRev == inspectedRev) {
                                            availableModels[idx] = projected
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

    fun getActiveBundleUriStrings(excludeModelId: String? = null): Set<String> {
        val uris = mutableSetOf<String>()
        val customDefs = customModelStore.getAll()
        for (def in customDefs) {
            if (excludeModelId != null && def.id == excludeModelId) continue
            def.bundle?.allArtifacts?.forEach { artifact ->
                (artifact.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString?.let {
                    uris.add(it)
                }
            }
        }
        for (model in availableModels) {
            if (excludeModelId != null && model.id == excludeModelId) continue
            model.bundle?.allArtifacts?.forEach { artifact ->
                (artifact.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString?.let {
                    uris.add(it)
                }
            }
        }
        return uris
    }

    suspend fun importLocalModelBundle(importResult: LocalModelImporter.ImportResult): Result<Unit> = withContext(Dispatchers.IO) {
        val bundle = importResult.bundle
        if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(bundle.id)) {
            LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
            return@withContext Result.failure(IllegalStateException("Model is currently undergoing removal."))
        }
        val storeState = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).customModelStoreState.value
        if (storeState is com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired) {
            LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
            return@withContext Result.failure(IllegalStateException("Store recovery required"))
        }

        val primaryArtifact = bundle.mainArtifacts.firstOrNull()
        if (primaryArtifact == null) {
            LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
            return@withContext Result.failure(IllegalArgumentException("Imported bundle has no main artifacts"))
        }

        val effectiveType = if (bundle.capabilities.supportsVision || bundle.capabilities.supportsAudio) ModelType.Vision else ModelType.Text
        val sizeMb = bundle.totalSizeBytes / (1024 * 1024)
        val sizeStr = if (sizeMb > 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"

        val definition = CustomModelDefinition(
            id = bundle.id,
            name = bundle.displayName,
            url = "",
            type = effectiveType,
            fileName = primaryArtifact.displayName,
            packageType = ModelPackage.SingleFile,
            description = bundle.description,
            backend = bundle.backend,
            bundle = bundle
        )

        val saved = customModelStore.add(definition, isExplicitReimport = true)
        if (!saved) {
            LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
            return@withContext Result.failure(IllegalStateException("Failed to persist model definition to custom model store"))
        }

        val modelInfo = ModelInfo(
            id = bundle.id,
            name = bundle.displayName,
            size = sizeStr,
            status = ModelStatus.Available,
            progress = 1f,
            localPath = primaryArtifact.source.location,
            type = effectiveType,
            fileName = primaryArtifact.displayName,
            packageType = ModelPackage.SingleFile,
            description = bundle.description,
            backend = bundle.backend,
            deviceTarget = "All supported chipsets",
            bundle = bundle,
            supportsThinkingMode = (bundle.capabilities.thinking == ThinkingCapability.ModelControlled || bundle.capabilities.thinking == ThinkingCapability.Toggleable),
            supportsVisionInput = bundle.capabilities.supportsVision,
            supportsAudioInput = bundle.capabilities.supportsAudio,
            contextWindowTokens = bundle.contextLength.takeIf { it > 0 }
        )

        withContext(Dispatchers.Main.immediate) {
            val existingIdx = availableModels.indexOfFirst { it.id == bundle.id }
            if (existingIdx >= 0) {
                availableModels[existingIdx] = modelInfo
            } else {
                availableModels.add(modelInfo)
            }
            _pendingImportDraft.value = null
        }
        Result.success(Unit)
    }

    fun importLocalModelBundle(bundle: com.shounak.localmeshai.models.ModelBundle) {
        viewModelScope.launch {
            importLocalModelBundle(LocalModelImporter.ImportResult(bundle, emptyList()))
        }
    }

    fun relinkModelBundle(existingModelId: String, importResult: LocalModelImporter.ImportResult): Result<Unit> {
        val replacementBundle = importResult.bundle
        val storeState = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).customModelStoreState.value
        if (storeState is com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired) {
            LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
            return Result.failure(IllegalStateException("Store recovery required"))
        }

        val existingDef = customModelStore.getById(existingModelId)
            ?: run {
                LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
                return Result.failure(IllegalArgumentException("Model with ID $existingModelId not found"))
            }

        val primaryArtifact = replacementBundle.mainArtifacts.firstOrNull()
            ?: run {
                LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
                return Result.failure(IllegalArgumentException("Replacement bundle has no main artifacts"))
            }

        val bundleWithExistingId = replacementBundle.copy(id = existingModelId)
        val saved = customModelStore.updateBundle(existingModelId, bundleWithExistingId)
        if (!saved) {
            LocalModelImporter.rollbackPermissions(getApplication(), importResult.newlyAcquiredGrants)
            return Result.failure(IllegalStateException("Failed to persist relinked model definition"))
        }

        val oldUriStrings = existingDef.bundle?.allArtifacts?.mapNotNull {
            (it.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString
        } ?: emptyList()

        val newUriStrings = replacementBundle.allArtifacts.mapNotNull {
            (it.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString
        }.toSet()

        val otherRegisteredUris = getActiveBundleUriStrings(excludeModelId = existingModelId)
        val urisToRelease = oldUriStrings
            .filter { uriStr -> uriStr !in newUriStrings && !otherRegisteredUris.contains(uriStr) }
            .map { android.net.Uri.parse(it) }

        if (urisToRelease.isNotEmpty()) {
            LocalModelImporter.rollbackPermissions(getApplication(), urisToRelease)
        }

        val effectiveType = if (bundleWithExistingId.capabilities.supportsVision || bundleWithExistingId.capabilities.supportsAudio) ModelType.Vision else ModelType.Text
        val sizeMb = bundleWithExistingId.totalSizeBytes / (1024 * 1024)
        val sizeStr = if (sizeMb > 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"
        val oldLocation = existingDef.bundle?.mainArtifacts?.firstOrNull()?.source?.location

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            updateModel(existingModelId) { model ->
                val rawModel = model.copy(
                    name = bundleWithExistingId.displayName,
                    size = sizeStr,
                    status = ModelStatus.Available,
                    progress = 1f,
                    localPath = primaryArtifact.source.location,
                    errorMessage = null,
                    type = effectiveType,
                    fileName = primaryArtifact.displayName,
                    packageType = ModelPackage.SingleFile,
                    description = bundleWithExistingId.description,
                    backend = bundleWithExistingId.backend,
                    deviceTarget = "All supported chipsets",
                    bundle = bundleWithExistingId,
                    supportsThinkingMode = (bundleWithExistingId.capabilities.thinking == ThinkingCapability.ModelControlled || bundleWithExistingId.capabilities.thinking == ThinkingCapability.Toggleable),
                    supportsVisionInput = bundleWithExistingId.capabilities.supportsVision,
                    supportsAudioInput = bundleWithExistingId.capabilities.supportsAudio,
                    contextWindowTokens = bundleWithExistingId.contextLength.takeIf { it > 0 }
                )
                com.shounak.localmeshai.models.ModelLoadRestrictionPolicy.projectCompletedModel(getApplication(), rawModel)
            }
            if (_selectedTextModelPath.value == existingModelId || (_selectedTextModelPath.value != null && _selectedTextModelPath.value == oldLocation)) {
                _selectedTextModelPath.value = primaryArtifact.source.location
            }
            if (_selectedVisionModelPath.value == existingModelId || (_selectedVisionModelPath.value != null && _selectedVisionModelPath.value == oldLocation)) {
                _selectedVisionModelPath.value = primaryArtifact.source.location
            }
        }

        return Result.success(Unit)
    }

    fun relinkModelBundle(existingModelId: String, replacementBundle: com.shounak.localmeshai.models.ModelBundle): Result<Unit> {
        return relinkModelBundle(existingModelId, LocalModelImporter.ImportResult(replacementBundle, emptyList()))
    }

    fun classifyModelInput(input: String): ModelInputClassification = Companion.classifyModelInput(input)

    fun submitLinkImport(input: String, sheetSessionId: String = "") {
        val clean = input.trim()
        if (clean.isBlank()) {
            resetHfResolution()
            return
        }

        val classification = classifyModelInput(clean)
        when (classification) {
            ModelInputClassification.Invalid -> {
                _hfResolutionState.value = HfResolutionUiState.Error(
                    "Please enter a valid Hugging Face repository or a direct link to a supported model file (.gguf, .task, .litertlm, .tflite)."
                )
                return
            }
            ModelInputClassification.DirectDownload -> {
                hfResolutionJob?.cancel()
                hfResolutionJob = viewModelScope.launch(Dispatchers.IO) {
                    _hfResolutionState.value = HfResolutionUiState.Loading("Starting direct download...")
                    val isTflite = clean.endsWith(".tflite", ignoreCase = true) || clean.contains(".tflite?", ignoreCase = true)
                    val inferredType = if (isTflite) ModelType.Vision else ModelType.Text
                    val addResult = addCustomModel(
                        name = "",
                        url = clean,
                        type = inferredType,
                        sha256 = null,
                        sheetSessionId = sheetSessionId
                    )
                    addResult.onSuccess { model ->
                        _hfResolutionState.value = HfResolutionUiState.Idle
                        val opId = java.util.UUID.randomUUID().toString()
                        if (sheetSessionId.isNotBlank()) {
                            _importCompletionEvent.value = ImportCompletionEvent(
                                sheetSessionId = sheetSessionId,
                                operationId = opId,
                                modelId = model.id
                            )
                        }
                    }.onFailure { err ->
                        _hfResolutionState.value = HfResolutionUiState.Error(err.message ?: "Failed to add direct download model.")
                    }
                }
                return
            }
            ModelInputClassification.HuggingFace -> {
                hfResolutionJob?.cancel()
                hfResolutionJob = viewModelScope.launch(Dispatchers.IO) {
                    _hfResolutionState.value = HfResolutionUiState.Loading(clean)
                    _hfSelectedVariant.value = null
                    _hfSelectedProjector.value = null
                    try {
                        val parsed = HuggingFaceModelResolver.parseInput(clean)
                        if (parsed.repoId.isBlank()) {
                            _hfResolutionState.value = HfResolutionUiState.Error("Invalid Hugging Face URL or repository ID.")
                            return@launch
                        }

                        val token = _huggingFaceToken.value.ifBlank { null }
                        val repoInfo = HuggingFaceModelResolver.resolveRepository(clean, token).getOrThrow()
                        val allVariants = HuggingFaceModelResolver.groupVariants(repoInfo)
                        val selection = try {
                            HuggingFaceModelResolver.selectVariants(parsed, allVariants)
                        } catch (e: NoSuchElementException) {
                            _hfResolutionState.value = HfResolutionUiState.Error(e.message ?: "No matching GGUF model files found in repository ${repoInfo.repoId}.")
                            return@launch
                        }

                        val preselected = selection.preselectedVariant
                        if (parsed.kind == HfInputKind.FILE && preselected != null) {
                            val projector = preselected.projectorFile
                            if (preselected.availableProjectors.isEmpty() || projector != null) {
                                val result = registerAndDownloadHuggingFaceSpec(
                                    repoInfo = repoInfo,
                                    variant = preselected,
                                    selectedProjector = projector,
                                    sheetSessionId = sheetSessionId
                                )
                                result.onSuccess { model ->
                                    _hfResolutionState.value = HfResolutionUiState.Idle
                                    val opId = java.util.UUID.randomUUID().toString()
                                    if (sheetSessionId.isNotBlank()) {
                                        _importCompletionEvent.value = ImportCompletionEvent(
                                            sheetSessionId = sheetSessionId,
                                            operationId = opId,
                                            modelId = model.id
                                        )
                                    }
                                }.onFailure { err ->
                                    _hfResolutionState.value = HfResolutionUiState.Error(err.message ?: "Failed to add model.")
                                }
                            } else {
                                _hfSelectedVariant.value = preselected
                                _hfSelectedProjector.value = null
                                _hfResolutionState.value = HfResolutionUiState.Success(
                                    repoInfo = repoInfo,
                                    variants = selection.variants,
                                    preselectedVariant = preselected
                                )
                            }
                        } else {
                            _hfSelectedVariant.value = if (parsed.kind == HfInputKind.FILE) preselected else null
                            _hfSelectedProjector.value = if (parsed.kind == HfInputKind.FILE) preselected?.projectorFile else null
                            _hfResolutionState.value = HfResolutionUiState.Success(
                                repoInfo = repoInfo,
                                variants = selection.variants,
                                preselectedVariant = if (parsed.kind == HfInputKind.FILE) preselected else null
                            )
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        val msg = e.message ?: "Failed to resolve repository"
                        val isAuth = msg.contains("token", ignoreCase = true) || msg.contains("401") || msg.contains("403") || msg.contains("access denied", ignoreCase = true)
                        _hfResolutionState.value = HfResolutionUiState.Error(msg, isAuthError = isAuth)
                    }
                }
            }
        }
    }

    fun resolveHuggingFace(input: String) {
        submitLinkImport(input, "")
    }

    fun downloadSelectedHuggingFaceVariant(sheetSessionId: String = "") {
        val state = _hfResolutionState.value as? HfResolutionUiState.Success ?: return
        val variant = _hfSelectedVariant.value ?: return
        val projector = _hfSelectedProjector.value
        viewModelScope.launch {
            _isSubmittingVariantDownload.value = true
            _variantSubmissionError.value = null
            try {
                val result = registerAndDownloadHuggingFaceSpec(
                    repoInfo = state.repoInfo,
                    variant = variant,
                    selectedProjector = projector,
                    sheetSessionId = sheetSessionId
                )
                result.onSuccess { model ->
                    _hfResolutionState.value = HfResolutionUiState.Idle
                    val opId = java.util.UUID.randomUUID().toString()
                    if (sheetSessionId.isNotBlank()) {
                        _importCompletionEvent.value = ImportCompletionEvent(
                            sheetSessionId = sheetSessionId,
                            operationId = opId,
                            modelId = model.id
                        )
                    }
                }.onFailure { err ->
                    _variantSubmissionError.value = err.message ?: "Failed to add model."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _variantSubmissionError.value = e.message ?: "Failed to add model."
            } finally {
                _isSubmittingVariantDownload.value = false
            }
        }
    }

    suspend fun registerAndDownloadHuggingFaceSpec(
        repoInfo: ResolvedRepoInfo,
        variant: ModelVariant,
        selectedProjector: RepoFile? = null,
        sheetSessionId: String = ""
    ): Result<ModelInfo> = withContext(Dispatchers.IO) {
        val storeState = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).customModelStoreState.value
        if (storeState is com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired) {
            return@withContext Result.failure(IllegalStateException("Store recovery required"))
        }

        val effectiveType = if (selectedProjector != null) ModelType.Vision else ModelType.Text
        val mainPathsKey = variant.mainFiles.joinToString(";") { it.path }
        val projPathKey = selectedProjector?.path.orEmpty()
        val identityKey = "hf://${repoInfo.repoId}@${repoInfo.commitSha}/$mainPathsKey:$projPathKey"
        val modelId = CustomModelStore.generateCustomModelId(identityKey, effectiveType)
        if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(modelId)) {
            return@withContext Result.failure(IllegalStateException("Model is currently undergoing removal."))
        }

        getSubmissionLock(modelId).withLock {
            val existingModel = availableModels.firstOrNull { it.id == modelId }
            if (existingModel != null) {
                when (existingModel.status) {
                    ModelStatus.Available -> {
                        return@withLock Result.failure(IllegalStateException("This model has already been added and is ready to use."))
                    }
                    ModelStatus.Downloading -> {
                        return@withLock Result.success(existingModel)
                    }
                    ModelStatus.Paused -> {
                        withContext(Dispatchers.Main.immediate) {
                            resumeDownload(modelId)
                        }
                        return@withLock Result.success(existingModel)
                    }
                    else -> {
                        // NotDownloaded, Failed, Blocked: proceed
                    }
                }
            }

            val isNewModel = existingModel == null
            val downloadSpec = HuggingFaceModelResolver.createDownloadSpec(repoInfo, variant, modelId, selectedProjector)
            val firstArtifact = downloadSpec.artifacts.firstOrNull()
                ?: return@withLock Result.failure(IllegalArgumentException("Specification has no artifacts"))

            val totalBytes = downloadSpec.artifacts.sumOf { it.expectedSizeBytes }
            val sizeMb = totalBytes / (1024 * 1024)
            val sizeStr = if (sizeMb > 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"

            val definition = CustomModelDefinition(
                id = modelId,
                name = variant.name,
                url = firstArtifact.downloadUrl,
                type = effectiveType,
                fileName = firstArtifact.fileName,
                packageType = ModelPackage.SingleFile,
                description = "Hugging Face model from ${repoInfo.repoId} (${variant.mainFiles.size} file${if (variant.mainFiles.size > 1) "s" else ""})",
                backend = "llama.cpp GGUF",
                downloadSpec = downloadSpec
            )

            withContext(NonCancellable) {
                val saved = customModelStore.add(definition, isExplicitReimport = true)
                if (!saved) {
                    return@withContext Result.failure(IllegalStateException("Failed to persist model definition to custom model store"))
                }

                val modelInfo = ModelInfo(
                    id = modelId,
                    name = variant.name,
                    size = sizeStr,
                    status = ModelStatus.NotDownloaded,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = totalBytes,
                    localPath = null,
                    type = effectiveType,
                    fileName = firstArtifact.fileName,
                    packageType = ModelPackage.SingleFile,
                    description = definition.description,
                    backend = definition.backend,
                    deviceTarget = "All supported chipsets",
                    url = firstArtifact.downloadUrl,
                    downloadSpec = downloadSpec,
                    modelPageUrl = resolveModelPageUrl(downloadSpec, firstArtifact.downloadUrl),
                    requiresHuggingFaceToken = false
                )

                hideModelForSession(sheetSessionId, modelId, isNewModel)

                withContext(Dispatchers.Main.immediate) {
                    val existingIdx = availableModels.indexOfFirst { it.id == modelId }
                    if (existingIdx >= 0) {
                        availableModels[existingIdx] = modelInfo
                    } else {
                        availableModels.add(modelInfo)
                    }
                }

                try {
                    val downloadRes = withContext(Dispatchers.Main.immediate) {
                        startDownload(modelId)
                    }
                    downloadRes.getOrThrow()
                } catch (e: Exception) {
                    unhideModel(modelId)
                    updateModel(modelId) {
                        it.copy(
                            status = ModelStatus.Failed,
                            errorMessage = "Download enqueue failed: ${e.message}. Tap to retry."
                        )
                    }
                    return@withContext Result.failure(e)
                }

                Result.success(modelInfo)
            }
        }
    }

    fun normalizeCustomModelUrl(input: String): String = ModelDownloadUrlPolicy.normalizeUrl(input)

    private val _isSubmittingDirectDownload = MutableStateFlow(false)
    val isSubmittingDirectDownload = _isSubmittingDirectDownload.asStateFlow()

    private val _directSubmissionError = MutableStateFlow<String?>(null)
    val directSubmissionError = _directSubmissionError.asStateFlow()

    fun clearDirectSubmissionError() {
        _directSubmissionError.value = null
    }

    fun submitDirectDownload(
        sheetSessionId: String,
        name: String,
        url: String,
        type: ModelType,
        sha256: String? = null
    ) {
        viewModelScope.launch {
            _isSubmittingDirectDownload.value = true
            _directSubmissionError.value = null
            try {
                val result = addCustomModel(
                    name = name,
                    url = url,
                    type = type,
                    sha256 = sha256,
                    sheetSessionId = sheetSessionId
                )
                result.onSuccess { model ->
                    val opId = java.util.UUID.randomUUID().toString()
                    if (sheetSessionId.isNotBlank()) {
                        _importCompletionEvent.value = ImportCompletionEvent(
                            sheetSessionId = sheetSessionId,
                            operationId = opId,
                            modelId = model.id
                        )
                    }
                }.onFailure { err ->
                    _directSubmissionError.value = err.message ?: "Failed to add model."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _directSubmissionError.value = e.message ?: "Failed to add model."
            } finally {
                _isSubmittingDirectDownload.value = false
            }
        }
    }

    suspend fun addCustomModel(
        name: String,
        url: String,
        type: ModelType,
        sha256: String? = null,
        sheetSessionId: String = ""
    ): Result<ModelInfo> {
        val storeState = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).customModelStoreState.value
        if (storeState is com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired) {
            return Result.failure(IllegalStateException("Store recovery required."))
        }
        val normalizedUrl = normalizeCustomModelUrl(url)
        if (normalizedUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Model URL cannot be blank."))
        }
        val rawFileName = inferFileName(normalizedUrl, type)
        val isGguf = rawFileName.endsWith(".gguf", ignoreCase = true)
        val effectiveType = if (isGguf) ModelType.Text else type
        val fileName = if (isGguf) rawFileName else customTypedFileName(rawFileName, effectiveType)
        val packageType = if (fileName.endsWith(".zip", ignoreCase = true)) {
            ModelPackage.ZipDirectory
        } else {
            ModelPackage.SingleFile
        }
        val id = CustomModelStore.generateCustomModelId(normalizedUrl, effectiveType)
        if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(id)) {
            return Result.failure(IllegalStateException("Model is currently undergoing removal."))
        }

        val submissionLock = getSubmissionLock(id)
        return submissionLock.withLock {
            val existingIndex = availableModels.indexOfFirst { it.id == id }
            val existingModel = if (existingIndex >= 0) availableModels[existingIndex] else null
            if (existingModel != null) {
                when (existingModel.status) {
                    ModelStatus.Available -> {
                        return@withLock Result.failure(IllegalStateException("This model has already been added and is ready to use."))
                    }
                    ModelStatus.Downloading -> {
                        return@withLock Result.success(existingModel)
                    }
                    ModelStatus.Paused -> {
                        resumeDownload(id)
                        return@withLock Result.success(existingModel)
                    }
                    else -> {
                        // proceed
                    }
                }
            }

            val isNewModel = existingModel == null
            val displayName = name.trim().ifBlank {
                fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
            }

            val backendName = when {
                isGguf -> "llama.cpp GGUF"
                effectiveType == ModelType.Vision -> if (fileName.endsWith(".tflite", ignoreCase = true)) "TensorFlow Lite" else "On-device multimodal GPU"
                fileName.endsWith(".litertlm", ignoreCase = true) -> "LiteRT-LM GPU"
                else -> "MediaPipe LLM CPU-safe"
            }

            val descriptionText = when {
                isGguf -> "Custom GGUF model running locally via native llama.cpp backend."
                effectiveType == ModelType.Vision -> "User supplied image, audio, or multimodal model."
                else -> "User supplied .task or .litertlm text model."
            }

            val definition = CustomModelDefinition(
                id = id,
                name = displayName,
                url = normalizedUrl,
                type = effectiveType,
                fileName = fileName,
                packageType = packageType,
                sha256 = sha256?.trim()?.ifBlank { null },
                description = descriptionText,
                backend = backendName
            )

            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) {
                    customModelStore.add(definition, isExplicitReimport = true)
                }
                if (!saved) {
                    return@withContext Result.failure(IllegalStateException("Failed to persist model definition to custom model store."))
                }

                val model = ModelInfo(
                    id = id,
                    name = displayName,
                    size = "Custom",
                    status = ModelStatus.NotDownloaded,
                    type = effectiveType,
                    fileName = fileName,
                    packageType = packageType,
                    description = descriptionText,
                    backend = backendName,
                    deviceTarget = "All supported chipsets",
                    url = normalizedUrl,
                    sha256 = sha256?.trim()?.ifBlank { null },
                    modelPageUrl = resolveModelPageUrl(null, normalizedUrl),
                    requiresHuggingFaceToken = false
                )

                hideModelForSession(sheetSessionId, id, isNewModel)

                withContext(Dispatchers.Main.immediate) {
                    if (existingIndex >= 0) {
                        availableModels[existingIndex] = model
                    } else {
                        availableModels.add(model)
                    }
                }

                try {
                    val downloadRes = withContext(Dispatchers.Main.immediate) {
                        startDownload(id)
                    }
                    downloadRes.getOrThrow()
                } catch (e: Exception) {
                    unhideModel(id)
                    updateModel(id) {
                        it.copy(
                            status = ModelStatus.Failed,
                            errorMessage = "Download enqueue failed: ${e.message}. Tap to retry."
                        )
                    }
                    return@withContext Result.failure(e)
                }

                Result.success(model)
            }
        }
    }

    private fun customTypedFileName(fileName: String, type: ModelType): String {
        val suffix = when (type) {
            ModelType.Text -> "text"
            ModelType.Vision -> "image"
        }
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        val typedBaseName = if (baseName.endsWith("-$suffix", ignoreCase = true)) {
            baseName
        } else {
            "$baseName-$suffix"
        }
        return typedBaseName + extension
    }

    private fun observeDownloadState() {
        viewModelScope.launch {
            startupRecoveryDeferred.await()
            DownloadStateStore.snapshots.collect { snapshots ->
                snapshots.values.forEach { snapshot ->
                    val currentModel = availableModels.firstOrNull { it.id == snapshot.modelId } ?: return@forEach

                    if (snapshot.status == ModelStatus.Available) {
                        if (currentModel.status == ModelStatus.Available &&
                            currentModel.localPath != null &&
                            (snapshot.localPath == null || currentModel.localPath == snapshot.localPath)
                        ) {
                            return@forEach
                        }

                        val genKey = "${snapshot.localPath ?: currentModel.fileName}_${snapshot.downloadedBytes}_${snapshot.downloadedAt}"
                        latestSnapshotGenerations[snapshot.modelId] = genKey

                        val existingGenKey = inFlightValidations[snapshot.modelId]
                        if (existingGenKey == genKey) {
                            return@forEach
                        }

                        // Invalidate and cancel superseded validation for an older generation
                        inFlightValidationJobs[snapshot.modelId]?.cancel()
                        inFlightValidations[snapshot.modelId] = genKey

                        val valJob = viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val modelToValidate = availableModels.firstOrNull { it.id == snapshot.modelId } ?: return@launch
                                val targetFile = modelDownloader.getTargetFile(modelToValidate.id, modelToValidate.fileName, modelToValidate.packageType)
                                val fileGen = "${targetFile?.absolutePath}_${targetFile?.length()}_${targetFile?.lastModified()}"

                                val validationError = if (targetFile != null && targetFile.exists()) {
                                    if (validatedArtifactGenerations[modelToValidate.id] == fileGen) {
                                        null
                                    } else {
                                        localModelValidationError(modelToValidate, targetFile).also { err ->
                                            if (err == null) {
                                                validatedArtifactGenerations[modelToValidate.id] = fileGen
                                            }
                                        }
                                    }
                                } else {
                                    "Model file does not exist."
                                }

                                val bundleFromStorage = modelDownloader.artifactStorage.getPublishedBundle(modelToValidate.id) ?: modelToValidate.bundle

                                withContext(Dispatchers.Main.immediate) {
                                    if (latestSnapshotGenerations[snapshot.modelId] != genKey) return@withContext
                                    if (inFlightValidations[snapshot.modelId] != genKey) return@withContext
                                    val fresh = availableModels.firstOrNull { it.id == snapshot.modelId } ?: return@withContext
                                    if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(fresh.id) ||
                                        com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(fresh.id)) {
                                        return@withContext
                                    }

                                    updateModel(fresh.id) {
                                        if (validationError != null) {
                                            it.copy(
                                                status = ModelStatus.Failed,
                                                progress = snapshot.progress,
                                                downloadedBytes = snapshot.downloadedBytes,
                                                totalBytes = snapshot.totalBytes,
                                                bytesPerSecond = 0L,
                                                localPath = targetFile?.absolutePath,
                                                errorMessage = validationError,
                                                loadRestriction = null,
                                                bundle = bundleFromStorage ?: it.bundle
                                            )
                                        } else {
                                            val effectiveBundleForUpdate = bundleFromStorage ?: it.bundle
                                            val effectiveThinking = effectiveBundleForUpdate?.capabilities?.thinking?.let { c ->
                                                c == ThinkingCapability.ModelControlled || c == ThinkingCapability.Toggleable
                                            } ?: it.supportsThinkingMode
                                            val effectiveType = if (effectiveBundleForUpdate?.capabilities?.supportsVision == true || effectiveBundleForUpdate?.capabilities?.supportsAudio == true) ModelType.Vision else it.type
                                            val rawCompleted = it.copy(
                                                status = ModelStatus.Available,
                                                progress = 1f,
                                                downloadedBytes = snapshot.downloadedBytes,
                                                totalBytes = if (snapshot.totalBytes > 0L) snapshot.totalBytes else it.totalBytes,
                                                bytesPerSecond = 0L,
                                                localPath = snapshot.localPath ?: it.localPath ?: targetFile?.absolutePath,
                                                errorMessage = null,
                                                downloadedAt = if (snapshot.downloadedAt > 0L) snapshot.downloadedAt else (it.downloadedAt.takeIf { t -> t > 0L } ?: System.currentTimeMillis()),
                                                bundle = effectiveBundleForUpdate,
                                                type = effectiveType,
                                                supportsThinkingMode = effectiveThinking,
                                                supportsVisionInput = effectiveBundleForUpdate?.capabilities?.supportsVision ?: it.supportsVisionInput,
                                                supportsAudioInput = effectiveBundleForUpdate?.capabilities?.supportsAudio ?: it.supportsAudioInput
                                            )
                                            com.shounak.localmeshai.models.ModelLoadRestrictionPolicy.projectCompletedModel(getApplication(), rawCompleted)
                                        }
                                    }
                                }
                            } finally {
                                inFlightValidations.remove(snapshot.modelId, genKey)
                                inFlightValidationJobs.remove(snapshot.modelId)
                            }
                        }
                        inFlightValidationJobs[snapshot.modelId] = valJob
                    } else {
                        inFlightValidationJobs[snapshot.modelId]?.cancel()
                        inFlightValidations.remove(snapshot.modelId)
                        updateModel(snapshot.modelId) { it ->
                            if (it.status == snapshot.status &&
                                it.progress == snapshot.progress &&
                                it.downloadedBytes == snapshot.downloadedBytes &&
                                it.totalBytes == snapshot.totalBytes &&
                                it.bytesPerSecond == snapshot.bytesPerSecond &&
                                it.errorMessage == snapshot.errorMessage &&
                                it.localPath == snapshot.localPath
                            ) {
                                return@updateModel it
                            }
                            it.copy(
                                status = snapshot.status,
                                progress = snapshot.progress,
                                downloadedBytes = snapshot.downloadedBytes,
                                totalBytes = snapshot.totalBytes,
                                bytesPerSecond = snapshot.bytesPerSecond,
                                localPath = when (snapshot.status) {
                                    ModelStatus.NotDownloaded -> null
                                    else -> snapshot.localPath ?: it.localPath
                                },
                                errorMessage = snapshot.errorMessage,
                                loadRestriction = null,
                                downloadedAt = when {
                                    snapshot.status == ModelStatus.NotDownloaded -> 0L
                                    snapshot.downloadedAt > 0L -> snapshot.downloadedAt
                                    else -> it.downloadedAt
                                },
                                bundle = it.bundle
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateModel(modelId: String, transform: (ModelInfo) -> ModelInfo) {
        val index = availableModels.indexOfFirst { it.id == modelId }
        if (index != -1) {
            val old = availableModels[index]
            val newModel = transform(old)
            if (old !== newModel && old != newModel) {
                availableModels[index] = newModel
            }
        }
    }

    private fun localModelValidationError(model: ModelInfo, target: File): String? {
        if (!target.exists()) return "Model file does not exist."
        val runnableFile: File = if (target.isDirectory) {
            val isText = model.type == ModelType.Text
            try {
                ModelArtifactStorage.resolveRunnableInDirectory(target, isText = isText)
            } catch (e: Exception) {
                return e.message ?: "Invalid model bundle directory."
            }
        } else {
            target
        }

        if (runnableFile.length() < MIN_VALID_MODEL_BYTES) {
            return "Downloaded file is too small to be a valid model. Retry the download."
        }
        if (model.expectedFileSizeBytes > 0L && runnableFile.isFile) {
            val sizeError = LlamaCppEngine.validateGgufArtifactSize(runnableFile.absolutePath, model.expectedFileSizeBytes)
            if (sizeError != null) {
                return sizeError
            }
        }
        val expectedSha = model.sha256
        if (!expectedSha.isNullOrBlank() && !target.isDirectory) {
            val currentSize = runnableFile.length()
            val currentMtime = runnableFile.lastModified()
            val sourceIdentity = model.downloadSpec?.let { "spec:${it.repository}@${it.revision}" }
                ?: model.url
                ?: "model:${model.id}"

            val cached = verifiedChecksumCache[model.id]
            val isCachedValid = cached != null &&
                cached.expectedSha256.equals(expectedSha, ignoreCase = true) &&
                cached.verifiedSha256.equals(expectedSha, ignoreCase = true) &&
                cached.sourceIdentity == sourceIdentity &&
                cached.sizeBytes == currentSize &&
                cached.lastModified == currentMtime

            val manifest = modelDownloader.artifactStorage.getManifest(model.id)
            val isManifestValid = manifest != null &&
                manifest.checksumSha256 != null &&
                manifest.checksumSha256.equals(expectedSha, ignoreCase = true) &&
                manifest.sourceIdentity == sourceIdentity &&
                manifest.sizeBytes == currentSize

            val alreadyVerified = isCachedValid || isManifestValid
            if (!alreadyVerified) {
                if (!ModelDownloader.verifyFileSha256(runnableFile, expectedSha)) {
                    verifiedChecksumCache.remove(model.id)
                    return "SHA-256 checksum verification failed. The model file may be corrupt or incomplete."
                }
                verifiedChecksumCache[model.id] = VerifiedChecksumRecord(
                    modelId = model.id,
                    sourceIdentity = sourceIdentity,
                    sizeBytes = currentSize,
                    lastModified = currentMtime,
                    verifiedSha256 = expectedSha,
                    expectedSha256 = expectedSha
                )
            }
        }
        if (
            (model.fileName.endsWith(".task", ignoreCase = true) || runnableFile.name.endsWith(".task", ignoreCase = true))
        ) {
            val inspection = NonGgufBundleInspector.inspectMediaPipeTask(runnableFile)
            if (!inspection.isValid) {
                return inspection.errorMessage ?: "This .task file does not look like a valid MediaPipe task bundle."
            }
        }
        if (model.fileName.endsWith(".litertlm", ignoreCase = true) || runnableFile.name.endsWith(".litertlm", ignoreCase = true)) {
            val inspection = NonGgufBundleInspector.inspectLiteRtLm(runnableFile)
            if (!inspection.isValid) {
                return inspection.errorMessage ?: "This .litertlm file does not have a valid LiteRT-LM container header."
            }
        }
        if (model.fileName.endsWith(".gguf", ignoreCase = true) || runnableFile.name.endsWith(".gguf", ignoreCase = true)) {
            val ggufError = LlamaCppEngine.canLoadModel(runnableFile.absolutePath)
            if (ggufError != null) {
                return ggufError
            }
        }
        return null
    }

    private fun deviceBlockMessage(model: ModelInfo): String? {
        if (model.isFuturePlaceholder) {
            return null
        }
        val (allowed, reason) = DeviceUtils.checkModelSafetyProfile(
            context = getApplication(),
            modelInfo = model
        )
        return reason.takeUnless { allowed || it.isBlank() }
    }

    private fun canRetryBlockedModelOnLiteRtCpu(model: ModelInfo): Boolean {
        return DeviceUtils.canUseGemma4LiteRtCpuFallback(
            context = getApplication(),
            modelId = model.id,
            modelName = model.name,
            modelSize = model.size,
            fileName = model.fileName,
            isMultimodalLiteRt = model.type == ModelType.Vision ||
                model.supportsAudioInput ||
                model.backend.contains("vision", ignoreCase = true) ||
                model.backend.contains("multimodal", ignoreCase = true)
        )
    }

    private fun inferFileName(url: String, type: ModelType): String {
        val defaultName = when (type) {
            ModelType.Vision -> "custom-vision-model.tflite"
            ModelType.Text -> "custom-text-model.task"
        }
        val safeName = runCatching { ModelArtifactStorage.inferFileName(url, defaultName) }.getOrDefault(defaultName)
        return when {
            safeName.endsWith(".gguf", ignoreCase = true) -> safeName
            safeName.endsWith(".task", ignoreCase = true) -> safeName
            safeName.endsWith(".litertlm", ignoreCase = true) -> safeName
            safeName.endsWith(".tflite", ignoreCase = true) -> safeName
            safeName.endsWith(".zip", ignoreCase = true) -> safeName
            type == ModelType.Vision -> "$safeName.tflite"
            else -> "$safeName.task"
        }
    }

    companion object {
        private const val MIN_VALID_MODEL_BYTES = 1_000_000L
        private const val KEY_HF_TOKEN = "hugging_face_read_token"

        fun resolveModelPageUrl(downloadSpec: com.shounak.localmeshai.models.ModelDownloadSpec?, sourceUrl: String?): String? {
            val repo = downloadSpec?.repository?.trim().orEmpty()
            if (repo.isNotBlank()) {
                val parts = repo.split('/').filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    return "https://huggingface.co/${parts[0]}/${parts[1]}"
                }
            }
            val candidateUrl = sourceUrl ?: downloadSpec?.artifacts?.firstOrNull()?.downloadUrl
            if (!candidateUrl.isNullOrBlank()) {
                val uri = runCatching { java.net.URI(candidateUrl) }.getOrNull() ?: return null
                val host = uri.host?.lowercase() ?: return null
                if (host == "huggingface.co" || host == "www.huggingface.co") {
                    val segments = uri.path
                        .split('/')
                        .filter { it.isNotBlank() }
                    if (segments.size >= 2) {
                        return "https://huggingface.co/${segments[0]}/${segments[1]}"
                    }
                }
            }
            return null
        }

        fun normalizeCustomModelUrl(input: String): String = ModelDownloadUrlPolicy.normalizeUrl(input)

        fun classifyModelInput(input: String): ModelInputClassification {
            val clean = input.trim()
            if (clean.isBlank()) return ModelInputClassification.Invalid

            val directFormats = listOf(".task", ".litertlm", ".tflite", ".zip")

            val parsedUrl = clean.toHttpUrlOrNull()
            val path = parsedUrl?.encodedPath ?: clean

            // Direct formats (even when hosted on Hugging Face) route to DirectDownload!
            if (directFormats.any { ext -> path.endsWith(ext, ignoreCase = true) }) {
                return ModelInputClassification.DirectDownload
            }

            // Hugging Face repository, folder, or GGUF links route to HuggingFace bundle resolution
            if (ModelDownloadUrlPolicy.isExactHuggingFace(clean)) {
                return ModelInputClassification.HuggingFace
            }

            val hasScheme = clean.contains("://")
            if (!hasScheme) {
                val segments = clean.split('/').filter { it.isNotBlank() }
                if (segments.size >= 2 && !segments[0].contains(":") && !segments[0].contains("@")) {
                    return ModelInputClassification.HuggingFace
                }
                return ModelInputClassification.Invalid
            }

            if (parsedUrl == null || (parsedUrl.scheme != "https" && parsedUrl.scheme != "http")) {
                return ModelInputClassification.Invalid
            }

            // External direct file links (e.g. external .gguf)
            if (path.endsWith(".gguf", ignoreCase = true)) {
                return ModelInputClassification.DirectDownload
            }

            return ModelInputClassification.Invalid
        }
    }

    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCleared() {
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat, null)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision, null)
        val inspJob = importInspectionJob
        importInspectionJob = null
        val regJob = registrationJob
        registrationJob = null
        teardownScope.launch {
            try {
                phoneImportSession.startTeardown()
                inspJob?.cancelAndJoin()
                regJob?.join()
                phoneImportSession.dispose()
            } finally {
                teardownScope.cancel()
            }
        }
        super.onCleared()
    }
}

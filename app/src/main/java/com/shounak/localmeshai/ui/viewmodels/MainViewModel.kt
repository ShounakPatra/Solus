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
import com.shounak.localmeshai.services.DownloadController
import com.shounak.localmeshai.services.ModelDownloadService
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelDownloadUrlPolicy
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDownloader = ModelDownloader(application)
    
    private val _selectedTextModelPath = MutableStateFlow<String?>(null)
    val selectedTextModelPath = _selectedTextModelPath.asStateFlow()

    private val _selectedVisionModelPath = MutableStateFlow<String?>(null)
    val selectedVisionModelPath = _selectedVisionModelPath.asStateFlow()

    val appSettings = com.shounak.localmeshai.utils.AppSettings.getInstance(application)
    val appSettingsData = appSettings.settings

    private val settingsPrefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _huggingFaceToken = MutableStateFlow(settingsPrefs.getString(KEY_HF_TOKEN, "").orEmpty())
    val huggingFaceToken = _huggingFaceToken.asStateFlow()

    private val customModelStore = CustomModelStore(application.filesDir)

    val availableModels = mutableStateListOf(*ModelCatalog.defaultModels.toTypedArray())
    val unsafeInitOverrideIds = mutableStateListOf<String>()

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
            if (target != null) {
                if (_selectedTextModelPath.value == target.localPath) {
                    _selectedTextModelPath.value = null
                    val res = ModelRuntimeCoordinator.releaseAll()
                    if (res != com.shounak.localmeshai.ai.ShutdownResult.Success) {
                        shutdownOk = false
                    }
                }
                if (_selectedVisionModelPath.value == target.localPath) {
                    _selectedVisionModelPath.value = null
                    val res = ModelRuntimeCoordinator.releaseAll()
                    if (res != com.shounak.localmeshai.ai.ShutdownResult.Success) {
                        shutdownOk = false
                    }
                }
            }
            shutdownOk
        }
        applyDeviceModelGuards()
        observeDownloadState()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            restoreCustomModels()
            checkLocalModels()
            reconcilePersistedDownloads()
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
        val updates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val list = mutableListOf<Pair<Int, ModelInfo>>()
            availableModels.forEachIndexed { index, model ->
                if (model.isFuturePlaceholder) return@forEachIndexed
                val target = modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
                if (target != null && target.exists() && (target.isDirectory || target.length() > 0L)) {
                    val validationError = localModelValidationError(model, target)
                    if (validationError != null) {
                        list.add(index to model.copy(
                            status = ModelStatus.Failed,
                            progress = 0f,
                            localPath = null,
                            errorMessage = validationError
                        ))
                        return@forEachIndexed
                    }
                    if (canRetryBlockedModelOnLiteRtCpu(model)) {
                        InitCrashGuard.unblockModel(getApplication(), model.id)
                    }
                    val blocked = InitCrashGuard.isModelBlocked(getApplication(), model.id)
                    val deviceBlockMessage = deviceBlockMessage(model)
                    val blockMessage = when {
                        blocked -> InitCrashGuard.blockedModelMessage()
                        deviceBlockMessage != null -> deviceBlockMessage
                        else -> null
                    }
                    val fileTime = target.lastModified()
                    val existingSnapshot = DownloadStateStore.get(model.id)
                    val dlTime = if ((existingSnapshot?.downloadedAt ?: 0L) > 0L) existingSnapshot!!.downloadedAt else fileTime
                    list.add(index to model.copy(
                        status = if (blockMessage != null) ModelStatus.Blocked else ModelStatus.Available,
                        progress = 1f,
                        localPath = target.absolutePath,
                        errorMessage = blockMessage,
                        downloadedAt = dlTime
                    ))
                }
            }
            list
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            updates.forEach { (index, updatedModel) ->
                if (index in availableModels.indices) {
                    availableModels[index] = updatedModel
                }
            }
        }
    }

    private fun applyDeviceModelGuards() {
        availableModels.forEachIndexed { index, model ->
            val blockMessage = deviceBlockMessage(model) ?: return@forEachIndexed
            availableModels[index] = model.copy(
                status = ModelStatus.Blocked,
                errorMessage = blockMessage
            )
        }
    }

    fun selectTextModel(path: String) {
        val model = availableModels.firstOrNull { it.localPath == path }
        val isUnsafeAllowed = model != null && unsafeInitOverrideIds.contains(model.id)
        if (model != null && (model.status == ModelStatus.Failed || (model.status == ModelStatus.Blocked && !isUnsafeAllowed))) {
            _selectedTextModelPath.value = null
            return
        }
        val modelType = model?.type
        if (modelType == ModelType.Vision) {
            _selectedVisionModelPath.value = path
            _selectedTextModelPath.value = null
            return
        }
        _selectedTextModelPath.value = path
        _selectedVisionModelPath.value = null
    }

    fun selectVisionModel(path: String) {
        val model = availableModels.firstOrNull { it.localPath == path }
        val isUnsafeAllowed = model != null && unsafeInitOverrideIds.contains(model.id)
        if (model != null && (model.status == ModelStatus.Failed || (model.status == ModelStatus.Blocked && !isUnsafeAllowed))) {
            _selectedVisionModelPath.value = null
            return
        }
        val modelType = model?.type
        if (modelType == ModelType.Text) {
            _selectedTextModelPath.value = path
            _selectedVisionModelPath.value = null
            return
        }
        _selectedVisionModelPath.value = path
        _selectedTextModelPath.value = null
    }

    /** Called by ChatViewModel when the coordinator force-releases the chat model. */
    fun clearSelectedTextModel() {
        _selectedTextModelPath.value = null
    }

    /** Called by VisionViewModel when the coordinator force-releases the vision model. */
    fun clearSelectedVisionModel() {
        _selectedVisionModelPath.value = null
    }

    fun setHuggingFaceToken(token: String) {
        val cleanToken = token.trim()
        _huggingFaceToken.value = cleanToken
        settingsPrefs.edit().putString(KEY_HF_TOKEN, cleanToken).apply()
    }

    fun startDownload(modelId: String) {
        startDownloadInternal(modelId = modelId, ignoreDeviceGuard = false)
    }

    fun startDownloadAnyway(modelId: String) {
        if (!unsafeInitOverrideIds.contains(modelId)) {
            unsafeInitOverrideIds.add(modelId)
        }
        startDownloadInternal(modelId = modelId, ignoreDeviceGuard = true)
    }

    private fun startDownloadInternal(modelId: String, ignoreDeviceGuard: Boolean) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        if (model.status == ModelStatus.Available && model.localPath != null) {
            return
        }
        val isIgnored = ignoreDeviceGuard || unsafeInitOverrideIds.contains(modelId)
        if (InitCrashGuard.isModelBlocked(getApplication(), model.id)) {
            updateModel(modelId) {
                it.copy(errorMessage = InitCrashGuard.blockedModelMessage())
            }
            return
        }
        if (model.status == ModelStatus.Blocked && !isIgnored) {
            updateModel(modelId) {
                it.copy(errorMessage = model.errorMessage ?: InitCrashGuard.blockedModelMessage())
            }
            return
        }
        if (!isIgnored) {
            deviceBlockMessage(model)?.let { reason ->
                updateModel(modelId) {
                    it.copy(status = ModelStatus.Blocked, errorMessage = reason)
                }
                return
            }
        }
        if (model.isFuturePlaceholder) {
            updateModel(modelId) {
                it.copy(errorMessage = "No Android-ready .task or .litertlm artifact is available for this model yet.")
            }
            return
        }
        val downloadUrl = model.url
        if (downloadUrl.isNullOrBlank()) {
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.Failed,
                    errorMessage = "Add a direct model URL before downloading this model."
                )
            }
            return
        }
        if (model.requiresHuggingFaceToken && _huggingFaceToken.value.isBlank()) {
            updateModel(modelId) {
                it.copy(
                    status = ModelStatus.NotDownloaded,
                    errorMessage = "This model requires a Hugging Face token. Open Settings (⚙️) → Hugging Face Access Token to paste your token."
                )
            }
            return
        }

        if (!com.shounak.localmeshai.utils.NetworkUtils.isConnected(getApplication())) {
            updateModel(modelId) {
                it.copy(
                    status = if (it.downloadedBytes > 0L) ModelStatus.Paused else ModelStatus.NotDownloaded,
                    errorMessage = "No internet connection. Please connect to Wi-Fi or mobile data to download models."
                )
            }
            return
        }

        updateModel(modelId) {
            it.copy(
                status = ModelStatus.Downloading,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = -1L,
                bytesPerSecond = 0L,
                errorMessage = null
            )
        }

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

    fun tryModelAnyway(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        if (InitCrashGuard.isModelBlocked(getApplication(), model.id)) {
            updateModel(modelId) {
                it.copy(errorMessage = InitCrashGuard.blockedModelMessage())
            }
            return
        }
        val targetPath = model.localPath
            ?: modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)?.takeIf { it.exists() }?.absolutePath
        if (targetPath.isNullOrBlank()) {
            startDownloadAnyway(modelId)
            return
        }
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

    fun pauseDownload(modelId: String) {
        ModelDownloadService.pause(getApplication(), modelId)
    }

    fun cancelDownload(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        viewModelScope.launch {
            DownloadController.getInstance(getApplication()).cancelDownload(model.id)
            unsafeInitOverrideIds.remove(model.id)
            val blockMessage = when {
                InitCrashGuard.isModelBlocked(getApplication(), model.id) -> InitCrashGuard.blockedModelMessage()
                else -> deviceBlockMessage(model)
            }
            updateModel(modelId) {
                it.copy(
                    status = if (blockMessage != null) ModelStatus.Blocked else ModelStatus.NotDownloaded,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    bytesPerSecond = 0L,
                    localPath = null,
                    errorMessage = blockMessage,
                    downloadedAt = 0L
                )
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
        val downloadUrl = model.url ?: return
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
            sha256 = model.sha256
        )
    }

    fun deleteModel(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        viewModelScope.launch {
            try {
                // Await relevant inference shutdown if this model is active
                kotlinx.coroutines.withTimeout(10_000L) {
                    if (_selectedTextModelPath.value == model.localPath) {
                        _selectedTextModelPath.value = null
                        ModelRuntimeCoordinator.releaseAll()
                    }
                    if (_selectedVisionModelPath.value == model.localPath) {
                        _selectedVisionModelPath.value = null
                        ModelRuntimeCoordinator.releaseAll()
                    }
                }

                DownloadController.getInstance(getApplication()).removeModel(model.id)

                unsafeInitOverrideIds.remove(model.id)
                val blocked = InitCrashGuard.isModelBlocked(getApplication(), model.id)
                if (blocked && canRetryBlockedModelOnLiteRtCpu(model)) {
                    InitCrashGuard.unblockModel(getApplication(), model.id)
                }
                val stillBlocked = InitCrashGuard.isModelBlocked(getApplication(), model.id)
                val devBlockMsg = deviceBlockMessage(model)
                val blockMessage = when {
                    stillBlocked -> InitCrashGuard.blockedModelMessage()
                    devBlockMsg != null -> devBlockMsg
                    else -> null
                }
                updateModel(modelId) {
                    it.copy(
                        status = if (blockMessage != null) ModelStatus.Blocked else ModelStatus.NotDownloaded,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = -1L,
                        bytesPerSecond = 0L,
                        localPath = null,
                        errorMessage = blockMessage,
                        downloadedAt = 0L
                    )
                }
                if (model.id.startsWith("custom_")) {
                    customModelStore.remove(model.id)
                    availableModels.removeAll { it.id == model.id }
                }
            } catch (e: Exception) {
                updateModel(modelId) {
                    it.copy(errorMessage = "Cannot remove model: ${e.message ?: "inference shutdown stalled"}")
                }
            }
        }
    }

    private suspend fun restoreCustomModels() {
        val loadState = customModelStore.loadAsync()
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication())
            .updateCustomModelStoreState(loadState)
        if (loadState !is com.shounak.localmeshai.data.StoreLoadState.Ready) {
            return
        }
        val customDefinitions = customModelStore.getAll()
        val toAdd = mutableListOf<ModelInfo>()
        for (def in customDefinitions) {
            if (availableModels.none { it.id == def.id }) {
                toAdd.add(
                    ModelInfo(
                        id = def.id,
                        name = def.name,
                        size = "Custom",
                        status = ModelStatus.NotDownloaded,
                        type = def.type,
                        fileName = def.fileName,
                        packageType = def.packageType,
                        description = def.description,
                        backend = def.backend,
                        deviceTarget = def.deviceTarget,
                        url = def.url,
                        sha256 = def.sha256,
                        requiresHuggingFaceToken = ModelDownloadUrlPolicy.requiresHuggingFaceToken(def.url)
                    )
                )
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
        }
    }

    fun normalizeCustomModelUrl(input: String): String = ModelDownloadUrlPolicy.normalizeUrl(input)

    fun addCustomModel(name: String, url: String, type: ModelType, sha256: String? = null) {
        val storeState = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).customModelStoreState.value
        if (storeState is com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired) return
        val normalizedUrl = normalizeCustomModelUrl(url)
        if (normalizedUrl.isBlank()) return
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
        val saved = customModelStore.add(definition)
        if (!saved) return

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
            requiresHuggingFaceToken = ModelDownloadUrlPolicy.requiresHuggingFaceToken(normalizedUrl)
        )

        val existingIndex = availableModels.indexOfFirst { it.id == id }
        if (existingIndex >= 0) {
            availableModels[existingIndex] = model
        } else {
            availableModels.add(model)
        }
        startDownload(id)
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
            DownloadStateStore.snapshots.collect { snapshots ->
                snapshots.values.forEach { snapshot ->
                    updateModel(snapshot.modelId) {
                        val blocked = InitCrashGuard.isModelBlocked(getApplication(), it.id)
                        if (blocked && canRetryBlockedModelOnLiteRtCpu(it)) {
                            InitCrashGuard.unblockModel(getApplication(), it.id)
                        }
                        val stillBlocked = InitCrashGuard.isModelBlocked(getApplication(), it.id)
                        val deviceBlockMessage = if (snapshot.status == ModelStatus.Available) {
                            deviceBlockMessage(it)
                        } else {
                            null
                        }
                        val blockMessage = when {
                            stillBlocked -> InitCrashGuard.blockedModelMessage()
                            deviceBlockMessage != null -> deviceBlockMessage
                            else -> null
                        }
                        it.copy(
                            status = if (blockMessage != null) ModelStatus.Blocked else snapshot.status,
                            progress = snapshot.progress,
                            downloadedBytes = snapshot.downloadedBytes,
                            totalBytes = snapshot.totalBytes,
                            bytesPerSecond = snapshot.bytesPerSecond,
                            localPath = when (snapshot.status) {
                                ModelStatus.NotDownloaded -> null
                                else -> snapshot.localPath ?: it.localPath
                            },
                            errorMessage = blockMessage ?: snapshot.errorMessage,
                            downloadedAt = when {
                                snapshot.status == ModelStatus.NotDownloaded -> 0L
                                snapshot.downloadedAt > 0L -> snapshot.downloadedAt
                                else -> it.downloadedAt
                            }
                        )
                    }
                }
            }
        }
    }

    private fun updateModel(modelId: String, transform: (ModelInfo) -> ModelInfo) {
        val index = availableModels.indexOfFirst { it.id == modelId }
        if (index != -1) {
            availableModels[index] = transform(availableModels[index])
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
            if (!ModelDownloader.verifyFileSha256(runnableFile, expectedSha)) {
                return "SHA-256 checksum verification failed. The model file may be corrupt or incomplete."
            }
        }
        if (
            (model.fileName.endsWith(".task", ignoreCase = true) || runnableFile.name.endsWith(".task", ignoreCase = true)) &&
            !ModelDownloader.isLikelyTaskBundle(runnableFile)
        ) {
            return "This .task file does not look like a MediaPipe or LiteRT task bundle. Delete it and download a verified Android .task model."
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

        fun normalizeCustomModelUrl(input: String): String = ModelDownloadUrlPolicy.normalizeUrl(input)
    }

    override fun onCleared() {
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat, null)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision, null)
        super.onCleared()
    }
}

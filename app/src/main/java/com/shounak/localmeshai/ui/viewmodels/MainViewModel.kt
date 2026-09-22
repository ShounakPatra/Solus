package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.models.ModelCatalog
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.services.ModelDownloadService
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        DownloadStateStore.initialize(application)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat) {
            clearSelectedTextModel()
        }
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision) {
            clearSelectedVisionModel()
        }
        applyDeviceModelGuards()
        observeDownloadState()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            checkLocalModels()
            reconcilePersistedDownloads()
        }

        if (appSettingsData.value.autoCheckUpdates) {
            checkForUpdates(silent = true)
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_isCheckingForUpdates.value) return
        viewModelScope.launch {
            _isCheckingForUpdates.value = true
            val result = com.shounak.localmeshai.utils.AppUpdateManager.checkForUpdates(
                com.shounak.localmeshai.BuildConfig.VERSION_NAME
            )
            _isCheckingForUpdates.value = false
            if (!silent || result is com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpdateAvailable) {
                _updateState.value = result
            }

            if (result is com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpToDate) {
                com.shounak.localmeshai.utils.AppUpdateManager.deleteDownloadedApks(getApplication())
            } else if (result is com.shounak.localmeshai.utils.AppUpdateManager.UpdateCheckResult.UpdateAvailable) {
                val apkUrl = result.updateInfo.downloadUrl
                // Auto-download only if autoCheckUpdates is enabled
                if (appSettingsData.value.autoCheckUpdates && !apkUrl.isNullOrBlank()) {
                    downloadAndInstallUpdate(result.updateInfo.latestVersion, apkUrl)
                }
            }
        }
    }

    fun downloadAndInstallUpdate(version: String, downloadUrl: String) {
        if (_updateDownloadStatus.value is UpdateDownloadStatus.Downloading) return
        viewModelScope.launch {
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
                        _updateDownloadStatus.value = UpdateDownloadStatus.Error("Failed to launch package installer")
                    }
                },
                onFailure = { error ->
                    _updateDownloadStatus.value = UpdateDownloadStatus.Error(error.localizedMessage ?: "Download failed")
                }
            )
        }
    }

    fun dismissUpdateState() {
        _updateState.value = null
        _updateDownloadStatus.value = UpdateDownloadStatus.Idle
    }

    private suspend fun reconcilePersistedDownloads() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        DownloadStateStore.snapshots.value.values.forEach { snapshot ->
            val model = availableModels.firstOrNull { it.id == snapshot.modelId } ?: return@forEach
            val target = modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
            if (target.exists() && (target.isDirectory || target.length() > 0L)) {
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
                if (target.exists() && (target.isDirectory || target.length() > 0L)) {
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
        if (model != null && (model.status == ModelStatus.Failed || model.status == ModelStatus.Blocked)) {
            _selectedTextModelPath.value = null
            return
        }
        appSettings.updateSettings { it.copy(llamaBackendPreference = "AUTO") }
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
        if (model != null && (model.status == ModelStatus.Failed || model.status == ModelStatus.Blocked)) {
            _selectedVisionModelPath.value = null
            return
        }
        appSettings.updateSettings { it.copy(llamaBackendPreference = "AUTO") }
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
        startDownloadInternal(modelId = modelId, ignoreDeviceGuard = true)
    }

    private fun startDownloadInternal(modelId: String, ignoreDeviceGuard: Boolean) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        if (model.status == ModelStatus.Available && model.localPath != null) {
            return
        }
        if (InitCrashGuard.isModelBlocked(getApplication(), model.id)) {
            updateModel(modelId) {
                it.copy(errorMessage = InitCrashGuard.blockedModelMessage())
            }
            return
        }
        if (model.status == ModelStatus.Blocked && !ignoreDeviceGuard) {
            updateModel(modelId) {
                it.copy(errorMessage = model.errorMessage ?: InitCrashGuard.blockedModelMessage())
            }
            return
        }
        if (!ignoreDeviceGuard) {
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
        val localPath = model.localPath
        if (localPath.isNullOrBlank()) {
            startDownloadAnyway(modelId)
            return
        }
        if (!unsafeInitOverrideIds.contains(model.id)) {
            unsafeInitOverrideIds.add(model.id)
        }
        when (model.type) {
            ModelType.Text -> selectTextModel(localPath)
            ModelType.Vision -> selectVisionModel(localPath)
        }
    }

    fun pauseDownload(modelId: String) {
        ModelDownloadService.pause(getApplication(), modelId)
    }

    fun cancelDownload(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        ModelDownloadService.cancel(
            context = getApplication(),
            modelId = model.id,
            name = model.name,
            url = model.url.orEmpty(),
            fileName = model.fileName,
            packageType = model.packageType,
            token = _huggingFaceToken.value.ifBlank { null }
        )
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
                errorMessage = blockMessage
            )
        }
    }

    fun resumeDownload(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        val downloadUrl = model.url ?: return
        ModelDownloadService.resume(
            context = getApplication(),
            modelId = model.id,
            name = model.name,
            url = downloadUrl,
            fileName = model.fileName,
            packageType = model.packageType,
            token = _huggingFaceToken.value.ifBlank { null }
        )
    }

    fun deleteModel(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        // Cancel any in-progress download first.
        if (model.status == ModelStatus.Downloading) {
            ModelDownloadService.pause(getApplication(), modelId)
        }
        // Delete the local file / directory.
        val target = model.localPath?.let { File(it) }
            ?: modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
        runCatching {
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        }
        // Clear selection if this model was active.
        if (_selectedTextModelPath.value == model.localPath) {
            _selectedTextModelPath.value = null
        }
        if (_selectedVisionModelPath.value == model.localPath) {
            _selectedVisionModelPath.value = null
        }
        // Reset the model to NotDownloaded or Blocked.
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
    }

    fun normalizeCustomModelUrl(input: String): String {
        var clean = input.trim()
        if (clean.isBlank()) return ""

        // If it's a Hugging Face web URL with /blob/, replace /blob/ with /resolve/
        if (clean.contains("huggingface.co", ignoreCase = true)) {
            if (clean.contains("/blob/")) {
                clean = clean.replace("/blob/", "/resolve/")
            }
            if (!clean.contains("?download=true") && !clean.contains("&download=true")) {
                clean = if (clean.contains("?")) "$clean&download=true" else "$clean?download=true"
            }
            return clean
        }

        // Check if it matches shorthand "org/repo/filename"
        val parts = clean.split("/")
        if (parts.size >= 3 && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            val org = parts[0]
            val repo = parts[1]
            val rest = parts.drop(2).joinToString("/")
            return "https://huggingface.co/$org/$repo/resolve/main/$rest?download=true"
        }

        return clean
    }

    fun addCustomModel(name: String, url: String, type: ModelType, sha256: String? = null) {
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
        val id = "custom_${effectiveType.name.lowercase()}_${normalizedUrl.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL}"
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
            requiresHuggingFaceToken = normalizedUrl.contains("huggingface.co")
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
        if (target.isDirectory) return null
        if (target.length() < MIN_VALID_MODEL_BYTES) {
            return "Downloaded file is too small to be a valid model. Retry the download."
        }
        if (
            model.fileName.endsWith(".task", ignoreCase = true) &&
            !ModelDownloader.isLikelyTaskBundle(target)
        ) {
            return "This .task file does not look like a MediaPipe or LiteRT task bundle. Delete it and download a verified Android .task model."
        }
        if (model.fileName.endsWith(".gguf", ignoreCase = true)) {
            val ggufError = LlamaCppEngine.canLoadModel(target.absolutePath)
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
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val decoded = URLDecoder.decode(path.substringAfterLast('/'), StandardCharsets.UTF_8.name())
        val baseName = decoded.ifBlank {
            when (type) {
                ModelType.Vision -> "custom-vision-model.tflite"
                ModelType.Text -> "custom-text-model.task"
            }
        }

        return when {
            baseName.endsWith(".gguf", ignoreCase = true) -> baseName
            baseName.endsWith(".task", ignoreCase = true) -> baseName
            baseName.endsWith(".litertlm", ignoreCase = true) -> baseName
            baseName.endsWith(".tflite", ignoreCase = true) -> baseName
            baseName.endsWith(".zip", ignoreCase = true) -> baseName
            type == ModelType.Vision -> "$baseName.tflite"
            else -> "$baseName.task"
        }
    }

    companion object {
        private const val MIN_VALID_MODEL_BYTES = 1_000_000L
        private const val KEY_HF_TOKEN = "hugging_face_read_token"

        fun normalizeCustomModelUrl(input: String): String {
            var clean = input.trim()
            if (clean.isBlank()) return ""

            // If it's a Hugging Face web URL with /blob/, replace /blob/ with /resolve/
            if (clean.contains("huggingface.co", ignoreCase = true)) {
                if (clean.contains("/blob/")) {
                    clean = clean.replace("/blob/", "/resolve/")
                }
                if (!clean.contains("?download=true") && !clean.contains("&download=true")) {
                    clean = if (clean.contains("?")) "$clean&download=true" else "$clean?download=true"
                }
                return clean
            }

            // Check if it matches shorthand "org/repo/filename"
            val parts = clean.split("/")
            if (parts.size >= 3 && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
                val org = parts[0]
                val repo = parts[1]
                val rest = parts.drop(2).joinToString("/")
                return "https://huggingface.co/$org/$repo/resolve/main/$rest?download=true"
            }

            return clean
        }
    }

    override fun onCleared() {
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat, null)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision, null)
        super.onCleared()
    }
}

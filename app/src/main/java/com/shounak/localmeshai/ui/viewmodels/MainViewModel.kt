package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val settingsPrefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _huggingFaceToken = MutableStateFlow(settingsPrefs.getString(KEY_HF_TOKEN, "").orEmpty())
    val huggingFaceToken = _huggingFaceToken.asStateFlow()

    val availableModels = mutableStateListOf(*ModelCatalog.defaultModels.toTypedArray())
    val unsafeInitOverrideIds = mutableStateListOf<String>()

    init {
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat) {
            clearSelectedTextModel()
        }
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision) {
            clearSelectedVisionModel()
        }
        checkLocalModels()
        applyDeviceModelGuards()
        observeDownloadState()
    }

    private fun checkLocalModels() {
        availableModels.forEachIndexed { index, model ->
            if (model.isFuturePlaceholder) return@forEachIndexed
            val target = modelDownloader.getTargetFile(model.id, model.fileName, model.packageType)
            if (target.exists() && (target.isDirectory || target.length() > 0L)) {
                val validationError = localModelValidationError(model, target)
                if (validationError != null) {
                    availableModels[index] = model.copy(
                        status = ModelStatus.Failed,
                        progress = 0f,
                        localPath = null,
                        errorMessage = validationError
                    )
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
                availableModels[index] = model.copy(
                    status = if (blockMessage != null) ModelStatus.Blocked else ModelStatus.Available,
                    progress = 1f,
                    localPath = target.absolutePath,
                    errorMessage = blockMessage
                )
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
        val modelType = availableModels.firstOrNull { it.localPath == path }?.type
        if (modelType == ModelType.Vision) {
            _selectedVisionModelPath.value = path
            _selectedTextModelPath.value = null
            return
        }
        _selectedTextModelPath.value = path
        _selectedVisionModelPath.value = null
    }

    fun selectVisionModel(path: String) {
        val modelType = availableModels.firstOrNull { it.localPath == path }?.type
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
                    errorMessage = "Accept this model's Hugging Face license and paste a read token in Access before downloading."
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
            token = _huggingFaceToken.value.ifBlank { null }
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
                errorMessage = blockMessage
            )
        }
    }

    fun addCustomModel(name: String, url: String, type: ModelType) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val fileName = customTypedFileName(inferFileName(cleanUrl, type), type)
        val packageType = if (fileName.endsWith(".zip", ignoreCase = true)) {
            ModelPackage.ZipDirectory
        } else {
            ModelPackage.SingleFile
        }
        // Mask off the sign bit so the id is always non-negative.
        // Long.MIN_VALUE.absoluteValue is still Long.MIN_VALUE in Kotlin/Java,
        // which would yield a negative model id and break equality checks.
        val id = "custom_${type.name.lowercase()}_${cleanUrl.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL}"
        val displayName = name.trim().ifBlank {
            fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
        }

        val model = ModelInfo(
            id = id,
            name = displayName,
            size = "Custom",
            status = ModelStatus.NotDownloaded,
            type = type,
            fileName = fileName,
            packageType = packageType,
            description = if (type == ModelType.Vision) {
                "User supplied image, audio, or multimodal model."
            } else {
                "User supplied .task or .litertlm text model."
            },
            backend = when (type) {
                ModelType.Vision -> if (fileName.endsWith(".tflite", ignoreCase = true)) "TensorFlow Lite" else "On-device multimodal GPU"
                ModelType.Text -> if (fileName.endsWith(".litertlm", ignoreCase = true)) "LiteRT-LM GPU" else "MediaPipe LLM CPU-safe"
            },
            deviceTarget = "8 GB RAM, Snapdragon 6+ or Dimensity 7000+",
            url = cleanUrl,
            requiresHuggingFaceToken = cleanUrl.contains("huggingface.co")
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
                            errorMessage = blockMessage ?: snapshot.errorMessage
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
        return null
    }

    private fun deviceBlockMessage(model: ModelInfo): String? {
        if (model.isFuturePlaceholder) {
            return null
        }
        if (!model.fileName.endsWith(".litertlm", ignoreCase = true)) return null
        val (allowed, reason) = DeviceUtils.canInitializeLiteRtLm(
            context = getApplication(),
            modelId = model.id,
            modelName = model.name,
            modelSize = model.size,
            isVision = model.type == ModelType.Vision,
            fileName = model.fileName,
            backendLabel = model.backend,
            supportsAudioInput = model.supportsAudioInput
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
    }

    override fun onCleared() {
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat, null)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision, null)
        super.onCleared()
    }
}

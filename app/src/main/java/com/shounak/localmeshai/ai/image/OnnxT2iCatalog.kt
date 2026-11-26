package com.shounak.localmeshai.ai.image

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.DownloadArtifactSpec
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelDownloadSpec
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelTask
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.utils.OnnxBundleValidation
import com.shounak.localmeshai.utils.OnnxT2iGraphSpec
import java.io.File

/**
 * Resolved runnable single-graph ONNX Text-to-Image model on local storage.
 */
data class InstalledOnnxT2iModel(
    val id: String,
    val name: String,
    val onnxFile: File,
    val vocabFile: File?,
    val tokenizerJsonFile: File?,
    val graphSpec: OnnxT2iGraphSpec,
    val defaultNoiseScale: Float
)

/**
 * Registry and discovery helpers for offline single-graph ONNX Text-to-Image models.
 */
object OnnxT2iCatalog {
    const val MINIT2I_MODEL_ID = "minit2i-b16-mf-int8"
    const val MINIT2I_REPO = "cfahlgren1/MiniT2I-B-16-MF-compact-webgpu-int8"
    const val MINIT2I_URL_ONNX =
        "https://huggingface.co/cfahlgren1/MiniT2I-B-16-MF-compact-webgpu-int8/resolve/main/minit2i.onnx?download=true"
    const val MINIT2I_URL_DATA =
        "https://huggingface.co/cfahlgren1/MiniT2I-B-16-MF-compact-webgpu-int8/resolve/main/minit2i.onnx.data?download=true"
    const val MINIT2I_URL_VOCAB =
        "https://huggingface.co/cfahlgren1/MiniT2I-B-16-MF-compact-webgpu-int8/resolve/main/vocab.json?download=true"

    const val MINIT2I_SHA256_ONNX = "0e8dc9f96ce8fdb8aa484d5f99576b18a2e6677c4b5a80ce8c20ce9eadfe251f"
    const val MINIT2I_SHA256_DATA = "ad297f126bb08e3b6c5a2f9240f85c97216ac5d83cfc775bcf00cba5973ab38e"

    const val MINIT2I_SIZE_ONNX = 2_336_435L
    const val MINIT2I_SIZE_DATA = 310_480_256L
    const val MINIT2I_SIZE_VOCAB = 1_136_194L
    const val MINIT2I_TOTAL_SIZE_BYTES = MINIT2I_SIZE_ONNX + MINIT2I_SIZE_DATA + MINIT2I_SIZE_VOCAB

    val MINI_T2I_DOWNLOAD_SPEC: ModelDownloadSpec = ModelDownloadSpec(
        modelId = MINIT2I_MODEL_ID,
        repository = MINIT2I_REPO,
        revision = "main",
        artifacts = listOf(
            DownloadArtifactSpec(
                downloadUrl = MINIT2I_URL_ONNX,
                fileName = "minit2i.onnx",
                role = ArtifactRole.Main,
                expectedSizeBytes = MINIT2I_SIZE_ONNX,
                expectedSha256 = MINIT2I_SHA256_ONNX
            ),
            DownloadArtifactSpec(
                downloadUrl = MINIT2I_URL_DATA,
                fileName = "minit2i.onnx.data",
                role = ArtifactRole.Main,
                expectedSizeBytes = MINIT2I_SIZE_DATA,
                expectedSha256 = MINIT2I_SHA256_DATA
            ),
            DownloadArtifactSpec(
                downloadUrl = MINIT2I_URL_VOCAB,
                fileName = "vocab.json",
                role = ArtifactRole.Main,
                expectedSizeBytes = MINIT2I_SIZE_VOCAB,
                expectedSha256 = null
            )
        ),
        packageType = ModelPackage.SingleFile,
        capabilities = ModelCapabilities(
            supportsText = false,
            supportsVision = false,
            supportsAudio = false,
            task = ModelTask.ImageGeneration,
            contextLength = 256
        ),
        format = ModelFormat.Onnx
    )

    fun createCatalogModelInfo(): ModelInfo = ModelInfo(
        id = MINIT2I_MODEL_ID,
        name = "MiniT2I B/16 (512×512 Flow)",
        size = "314 MB",
        status = ModelStatus.NotDownloaded,
        type = ModelType.Text,
        fileName = "minit2i.onnx",
        description = "Compact 4-step Mean Flow single-graph ONNX Text-to-Image model with built-in T5 encoder (~313 MB total). Generates 512×512 images 100% offline.",
        backend = "ONNX Runtime XNNPACK",
        deviceTarget = "8 GB+ RAM required",
        url = MINIT2I_URL_ONNX,
        modelPageUrl = "https://huggingface.co/$MINIT2I_REPO",
        sha256 = MINIT2I_SHA256_ONNX,
        packageType = ModelPackage.SingleFile,
        downloadSpec = MINI_T2I_DOWNLOAD_SPEC,
        supportsVisionInput = false,
        contextWindowTokens = 256
    )

    fun isSingleGraphT2iPath(path: String, bundle: com.shounak.localmeshai.models.ModelBundle? = null): Boolean {
        if (bundle?.capabilities?.task == ModelTask.ImageGeneration && bundle.format == ModelFormat.Onnx) return true
        val file = File(path)
        if (!file.isFile || !file.name.endsWith(".onnx", ignoreCase = true)) return false
        return OnnxBundleValidation.inspectSingleGraphT2i(file) != null
    }

    fun resolveInstalledFromPath(
        path: String,
        id: String,
        name: String,
        bundle: com.shounak.localmeshai.models.ModelBundle? = null
    ): InstalledOnnxT2iModel? {
        val onnxFile = File(path).takeIf { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
            ?: bundle?.allArtifacts?.firstOrNull { it.displayName.endsWith(".onnx", ignoreCase = true) }
                ?.source?.location?.let(::File)?.takeIf { it.isFile }
            ?: return null
        val graphSpec = OnnxBundleValidation.inspectSingleGraphT2i(onnxFile) ?: return null
        val dir = onnxFile.parentFile ?: return null
        val vocabFile = dir.resolve("vocab.json").takeIf { it.isFile }
        val tokenizerFile = dir.resolve("tokenizer.json").takeIf { it.isFile }
        if (vocabFile == null && tokenizerFile == null) return null

        val resolvedId = id.ifBlank { bundle?.id ?: onnxFile.nameWithoutExtension }
        val resolvedName = name.ifBlank { bundle?.displayName ?: onnxFile.nameWithoutExtension }
        val isMiniT2i = resolvedId == MINIT2I_MODEL_ID ||
            onnxFile.name.contains("minit2i", ignoreCase = true) ||
            resolvedName.contains("minit2i", ignoreCase = true)
        return InstalledOnnxT2iModel(
            id = resolvedId,
            name = resolvedName,
            onnxFile = onnxFile,
            vocabFile = vocabFile,
            tokenizerJsonFile = tokenizerFile,
            graphSpec = graphSpec,
            defaultNoiseScale = if (isMiniT2i) 2.0f else 1.0f
        )
    }

    fun isT2iEntry(model: ModelInfo): Boolean {
        if (model.id == MINIT2I_MODEL_ID) return true
        val task = model.bundle?.capabilities?.task ?: model.downloadSpec?.capabilities?.task
        return task == ModelTask.ImageGeneration
    }

    fun isSingleGraphT2iModel(model: ModelInfo): Boolean {
        if (isT2iEntry(model)) return true
        val format = model.bundle?.format ?: model.downloadSpec?.effectiveFormat
        if (format != ModelFormat.Onnx) return false
        val primary = model.localPath?.let(::File) ?: return false
        return primary.isFile && OnnxBundleValidation.inspectSingleGraphT2i(primary) != null
    }

    /**
     * Resolves an installed single-graph ONNX T2I [ModelInfo] into concrete local files and graph metadata.
     */
    fun resolveInstalled(model: ModelInfo): InstalledOnnxT2iModel? {
        if (model.status != ModelStatus.Available) return null
        val path = model.localPath
            ?: model.bundle?.allArtifacts?.firstOrNull { it.displayName.endsWith(".onnx", ignoreCase = true) }?.source?.location
            ?: return null
        val onnxFile = File(path)
        if (!onnxFile.isFile) return null
        val graphSpec = OnnxBundleValidation.inspectSingleGraphT2i(onnxFile) ?: return null
        val dir = onnxFile.parentFile ?: return null
        val vocabFile = dir.resolve("vocab.json").takeIf { it.isFile }
        val tokenizerFile = dir.resolve("tokenizer.json").takeIf { it.isFile }
        if (vocabFile == null && tokenizerFile == null) return null

        val isMiniT2i = model.id == MINIT2I_MODEL_ID ||
            onnxFile.name.contains("minit2i", ignoreCase = true) ||
            model.name.contains("minit2i", ignoreCase = true)
        return InstalledOnnxT2iModel(
            id = model.id,
            name = model.name,
            onnxFile = onnxFile,
            vocabFile = vocabFile,
            tokenizerJsonFile = tokenizerFile,
            graphSpec = graphSpec,
            defaultNoiseScale = if (isMiniT2i) 2.0f else 1.0f
        )
    }

    fun findInstalledModels(models: List<ModelInfo>): List<InstalledOnnxT2iModel> {
        val resolved = models.mapNotNull { model ->
            val task = model.bundle?.capabilities?.task ?: model.downloadSpec?.capabilities?.task
            if (model.id == MINIT2I_MODEL_ID || task == ModelTask.ImageGeneration) {
                resolveInstalled(model)
            } else null
        }
        return resolved.sortedByDescending { it.id == MINIT2I_MODEL_ID }
    }
}

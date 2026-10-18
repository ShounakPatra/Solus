package com.shounak.localmeshai.utils

import android.content.Context
import android.util.Log
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ThinkingCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Request-scoped chat-template controls for models with native thinking modes. */
object ThinkingModeConfig {
    const val ENABLE_THINKING_KEY = "enable_thinking"
    const val DETECTOR_VERSION = 6
    const val LITERTLM_RUNTIME_VERSION = "0.18.0"

    private data class ArtifactCacheKey(
        val modelId: String,
        val identity: String,
        val size: Long,
        val lastModified: Long,
        val detectorVersion: Int,
        val runtimeVersion: String = LITERTLM_RUNTIME_VERSION
    )

    private val capabilityCache = ConcurrentHashMap<ArtifactCacheKey, ThinkingCapability>()

    @androidx.annotation.VisibleForTesting
    fun clearCacheForTesting() {
        capabilityCache.clear()
    }

    fun resolveThinkingCapability(
        model: ModelInfo?,
        context: Context? = null
    ): ThinkingCapability {
        if (model == null) return ThinkingCapability.Unsupported
        return resolveThinkingCapability(
            bundle = model.bundle,
            localPath = model.localPath,
            supportsThinkingFlag = model.supportsThinkingMode,
            id = model.id,
            name = model.name,
            backend = model.backend,
            fileName = model.fileName,
            isCustom = model.isCustom,
            downloadSpec = model.downloadSpec,
            context = context
        )
    }

    suspend fun resolveThinkingCapabilityAsync(
        model: ModelInfo?,
        context: Context? = null
    ): ThinkingCapability = withContext(Dispatchers.IO) {
        resolveThinkingCapability(model, context)
    }

    fun resolveThinkingCapability(
        bundle: ModelBundle?,
        localPath: String? = null,
        supportsThinkingFlag: Boolean = false,
        id: String = "",
        name: String = "",
        backend: String = "",
        fileName: String = "",
        isCustom: Boolean = false,
        downloadSpecCap: ThinkingCapability? = null,
        downloadSpec: com.shounak.localmeshai.models.ModelDownloadSpec? = null,
        context: Context? = null
    ): ThinkingCapability {
        val isGguf = bundle?.isGguf ?: (ChatRuntimePolicy.isGgufModel(fileName) || backend.contains("GGUF", ignoreCase = true))

        // 1. Artifact identity & current revision
        val mainArtifact = bundle?.mainArtifacts?.firstOrNull()
        val currentIdentity = (mainArtifact?.source as? ArtifactSource.AppManaged)?.path
            ?: (mainArtifact?.source as? ArtifactSource.PersistedUri)?.uriString
            ?: localPath ?: id

        val (freshSize, freshMtime) = if (context != null && mainArtifact != null) {
            runCatching {
                when (val s = mainArtifact.source) {
                    is ArtifactSource.AppManaged -> {
                        val f = File(s.path)
                        if (f.exists()) Pair(f.length(), f.lastModified()) else null
                    }
                    is ArtifactSource.PersistedUri -> {
                        val uri = android.net.Uri.parse(s.uriString)
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            val stat = android.system.Os.fstat(pfd.fileDescriptor)
                            Pair(stat.st_size, stat.st_mtime)
                        }
                    }
                }
            }.getOrNull() ?: Pair(mainArtifact.sizeBytes, mainArtifact.lastModified)
        } else if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.exists()) Pair(f.length(), f.lastModified()) else Pair(mainArtifact?.sizeBytes ?: 0L, mainArtifact?.lastModified ?: 0L)
        } else {
            Pair(mainArtifact?.sizeBytes ?: 0L, mainArtifact?.lastModified ?: 0L)
        }

        val effectiveId = bundle?.id?.ifBlank { id } ?: id
        val isCatalog = effectiveId.isNotBlank() && com.shounak.localmeshai.models.ModelCatalog.defaultModels.any { it.id == effectiveId }
        val effectiveIsCustom = isCustom || !isCatalog

        val cacheKey = ArtifactCacheKey(
            modelId = effectiveId,
            identity = currentIdentity,
            size = freshSize,
            lastModified = freshMtime,
            detectorVersion = DETECTOR_VERSION
        )
        capabilityCache[cacheKey]?.let { return it }

        // 2. Check bundle or spec persisted capability against current revision
        val persistedCaps = bundle?.capabilities
        val isRevisionUnchanged = if (freshSize > 0L && (mainArtifact?.sizeBytes ?: 0L) > 0L) {
            freshSize == mainArtifact?.sizeBytes &&
                (freshMtime <= 0L || mainArtifact.lastModified <= 0L || freshMtime == mainArtifact.lastModified)
        } else true

        if (persistedCaps != null) {
            val isPersistedAuthoritative = isRevisionUnchanged &&
                persistedCaps.thinkingArtifactIdentity.isNotBlank() &&
                persistedCaps.thinkingArtifactIdentity == currentIdentity &&
                persistedCaps.thinkingDetectorVersion >= DETECTOR_VERSION

            if (isPersistedAuthoritative) {
                val cap = persistedCaps.thinking
                if (cap != ThinkingCapability.Unknown) {
                    capabilityCache[cacheKey] = cap
                    return cap
                } else if (!supportsThinkingFlag) {
                    capabilityCache[cacheKey] = ThinkingCapability.Unknown
                    return ThinkingCapability.Unknown
                }
            }
        }

        val specCaps = downloadSpec?.capabilities
        if (specCaps != null) {
            val isSpecAuthoritative = isRevisionUnchanged &&
                specCaps.thinkingArtifactIdentity.isNotBlank() &&
                specCaps.thinkingArtifactIdentity == currentIdentity &&
                specCaps.thinkingDetectorVersion >= DETECTOR_VERSION

            if (isSpecAuthoritative) {
                val cap = specCaps.thinking
                if (cap != ThinkingCapability.Unknown) {
                    capabilityCache[cacheKey] = cap
                    return cap
                } else if (!supportsThinkingFlag) {
                    capabilityCache[cacheKey] = ThinkingCapability.Unknown
                    return ThinkingCapability.Unknown
                }
            }
        }

        if (downloadSpecCap != null && downloadSpecCap != ThinkingCapability.Unknown) {
            capabilityCache[cacheKey] = downloadSpecCap
            return downloadSpecCap
        }

        // 3. Artifact inspection from available local file
        val source = mainArtifact?.source
        if (source != null && context != null) {
            try {
                val (resolvedPath, closeable) = ModelSourceResolver.resolveSource(context, source)
                try {
                    val file = File(resolvedPath)
                    if (file.exists() && file.length() > 0L) {
                        val resolved = if (isGguf) {
                            val inspection = GgufBundleInspector.inspectArtifact(
                                location = resolvedPath,
                                displayName = mainArtifact.displayName.ifBlank { name },
                                sizeBytes = file.length()
                            )
                            parseInspectionCapability(inspection.thinkingCapability, supportsThinkingFlag)
                        } else {
                            val inspection = NonGgufBundleInspector.inspectFile(file)
                            inspection.capabilities.thinking
                        }
                        if (resolved != ThinkingCapability.Unknown) {
                            capabilityCache[cacheKey] = resolved
                            return resolved
                        }
                    }
                } finally {
                    try { closeable?.close() } catch (_: Throwable) {}
                }
            } catch (e: Exception) {
                Log.w("ThinkingModeConfig", "Inspection failed from source for $id: ${e.message}")
            }
        }

        val targetPath = (source as? ArtifactSource.AppManaged)?.path ?: localPath
        if (!targetPath.isNullOrBlank()) {
            val file = File(targetPath)
            if (file.exists() && file.length() > 0L) {
                try {
                    val resolved = if (isGguf) {
                        val displayName = mainArtifact?.displayName ?: name.ifBlank { file.name }
                        val inspection = GgufBundleInspector.inspectArtifact(
                            location = file.absolutePath,
                            displayName = displayName,
                            sizeBytes = file.length()
                        )
                        parseInspectionCapability(inspection.thinkingCapability, supportsThinkingFlag)
                    } else {
                        val inspection = NonGgufBundleInspector.inspectFile(file)
                        inspection.capabilities.thinking
                    }
                    if (resolved != ThinkingCapability.Unknown) {
                        capabilityCache[cacheKey] = resolved
                        return resolved
                    }
                } catch (e: Exception) {
                    Log.w("ThinkingModeConfig", "Inspection failed for $targetPath: ${e.message}")
                }
            }
        }

        // 4. Fallback for uninspected or pending download models
        if (supportsThinkingFlag) {
            return ThinkingCapability.Toggleable
        }

        if (!effectiveIsCustom) {
            return ThinkingCapability.Unsupported
        }

        return ThinkingCapability.Unknown
    }

    private fun parseInspectionCapability(
        rawCapability: String,
        supportsThinkingFlag: Boolean
    ): ThinkingCapability {
        return when (rawCapability.lowercase()) {
            "toggleable" -> ThinkingCapability.Toggleable
            "modelcontrolled", "model_controlled" -> ThinkingCapability.ModelControlled
            "unsupported" -> ThinkingCapability.Unsupported
            else -> if (supportsThinkingFlag) ThinkingCapability.Toggleable else ThinkingCapability.Unknown
        }
    }

    fun resolveRequestThinking(
        capability: ThinkingCapability,
        userPreference: Boolean
    ): Boolean = when (capability) {
        ThinkingCapability.Unsupported -> false
        ThinkingCapability.ModelControlled -> true
        ThinkingCapability.Toggleable -> userPreference
        ThinkingCapability.Unknown -> false
    }

    fun supportsTextNoThinkingSwitch(
        capability: ThinkingCapability
    ): Boolean = capability == ThinkingCapability.Toggleable

    fun supportsTextNoThinkingSwitch(
        modelPath: String = "",
        effectiveId: String = "",
        modelName: String = "",
        capability: ThinkingCapability? = null
    ): Boolean {
        if (capability != null && capability != ThinkingCapability.Unknown) {
            return capability == ThinkingCapability.Toggleable
        }
        return false
    }

    fun isThinkingSupported(
        capability: ThinkingCapability
    ): Boolean = capability == ThinkingCapability.Toggleable || capability == ThinkingCapability.ModelControlled

    fun isThinkingSupported(
        modelPath: String = "",
        effectiveId: String = "",
        modelName: String = "",
        supportsThinkingFlag: Boolean = false,
        capability: ThinkingCapability? = null
    ): Boolean {
        if (capability != null && capability != ThinkingCapability.Unknown) {
            return capability == ThinkingCapability.Toggleable || capability == ThinkingCapability.ModelControlled
        }
        return supportsThinkingFlag
    }

    fun liteRtExtraContext(
        capability: ThinkingCapability,
        userPreference: Boolean
    ): Map<String, Any> {
        return when (capability) {
            ThinkingCapability.Toggleable -> mapOf(ENABLE_THINKING_KEY to userPreference)
            ThinkingCapability.ModelControlled -> mapOf(ENABLE_THINKING_KEY to true)
            ThinkingCapability.Unsupported, ThinkingCapability.Unknown -> emptyMap()
        }
    }

    fun liteRtExtraContext(
        isDefaultThinkingModel: Boolean,
        thinkingMode: Boolean
    ): Map<String, Any> {
        return if (isDefaultThinkingModel) {
            mapOf(ENABLE_THINKING_KEY to thinkingMode)
        } else {
            emptyMap()
        }
    }
}

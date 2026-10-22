package com.shounak.localmeshai.models

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class ArtifactRole {
    Main,
    Shard,
    Projector
}

sealed interface ArtifactSource {
    val location: String

    data class AppManaged(val path: String) : ArtifactSource {
        override val location: String get() = path
    }

    data class PersistedUri(val uriString: String) : ArtifactSource {
        override val location: String get() = uriString
    }

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@ArtifactSource) {
            is AppManaged -> {
                put("type", "AppManaged")
                put("path", path)
            }
            is PersistedUri -> {
                put("type", "PersistedUri")
                put("uriString", uriString)
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ArtifactSource {
            return when (json.optString("type")) {
                "PersistedUri" -> PersistedUri(json.getString("uriString"))
                else -> AppManaged(json.getString("path"))
            }
        }
    }
}

@Immutable
data class ModelArtifact(
    val role: ArtifactRole,
    val displayName: String,
    val source: ArtifactSource,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.name)
        put("displayName", displayName)
        put("source", source.toJson())
        put("sizeBytes", sizeBytes)
        put("lastModified", lastModified)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelArtifact {
            return ModelArtifact(
                role = ArtifactRole.valueOf(json.getString("role")),
                displayName = json.getString("displayName"),
                source = ArtifactSource.fromJson(json.getJSONObject("source")),
                sizeBytes = json.optLong("sizeBytes", 0L),
                lastModified = json.optLong("lastModified", 0L)
            )
        }
    }
}

enum class ThinkingCapability {
    Unknown,
    Unsupported,
    Toggleable,
    ModelControlled
}

enum class ThinkingControlStrategy { None, NativeTemplate, QwenSoftSwitch }

enum class ModelTask(val id: String) {
    Chat("text_generation"), ImageClassification("image_classification"),
    SpeechRecognition("speech_recognition"), ImageGeneration("image_generation"), Unknown("unknown")
}

@Immutable
data class ModelCapabilities(
    val supportsText: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val thinking: ThinkingCapability = ThinkingCapability.Unknown,
    val thinkingDetectorVersion: Int = 0,
    val thinkingArtifactIdentity: String = "",
    val contextLength: Int = 0,
    val visionProjectorMissing: Boolean = false,
    val thinkingControl: ThinkingControlStrategy = ThinkingControlStrategy.None,
    val reasoningChannels: Set<String> = emptySet(),
    val reasoningPrefill: Boolean = false,
    val task: ModelTask = ModelTask.Chat
) {
    val isMediaModel: Boolean get() = supportsVision || supportsAudio || task == ModelTask.ImageGeneration
    fun toJson(): JSONObject = JSONObject().apply {
        put("task", task.id)
        put("supportsText", supportsText)
        put("supportsVision", supportsVision)
        put("supportsAudio", supportsAudio)
        put("thinking", thinking.name)
        put("thinkingControl", thinkingControl.name)
        put("reasoningChannels", JSONArray(reasoningChannels.toList()))
        put("reasoningPrefill", reasoningPrefill)
        put("thinkingDetectorVersion", thinkingDetectorVersion)
        put("thinkingArtifactIdentity", thinkingArtifactIdentity)
        put("contextLength", contextLength)
        put("visionProjectorMissing", visionProjectorMissing)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelCapabilities {
            val thinkingCap = json.optString("thinking").let { name ->
                ThinkingCapability.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ThinkingCapability.Unknown
            }
            return ModelCapabilities(
                task = ModelTask.entries.firstOrNull { it.id == json.optString("task") } ?: ModelTask.Chat,
                supportsText = json.optBoolean("supportsText", true),
                supportsVision = json.optBoolean("supportsVision", false),
                supportsAudio = json.optBoolean("supportsAudio", false),
                thinking = thinkingCap,
                thinkingDetectorVersion = json.optInt("thinkingDetectorVersion", 0),
                thinkingArtifactIdentity = json.optString("thinkingArtifactIdentity", ""),
                contextLength = json.optInt("contextLength", 0),
                visionProjectorMissing = json.optBoolean("visionProjectorMissing", false),
                thinkingControl = ThinkingControlStrategy.entries.firstOrNull { it.name == json.optString("thinkingControl") } ?: ThinkingControlStrategy.None,
                reasoningChannels = json.optJSONArray("reasoningChannels")?.let { names -> (0 until names.length()).map { names.getString(it) }.toSet() } ?: emptySet(),
                reasoningPrefill = json.optBoolean("reasoningPrefill", false)
            )
        }
    }
}

enum class ModelFormat {
    GGUF,
    LiteRtLm,
    MediaPipeTask,
    Onnx,
    TensorFlowLite,
    Safetensors;

    val isPortable: Boolean get() = this in setOf(Onnx, TensorFlowLite, Safetensors)

    val backendLabel: String get() = when (this) {
        GGUF -> "llama.cpp GGUF"
        LiteRtLm -> "LiteRT-LM GPU"
        MediaPipeTask -> "MediaPipe LLM CPU-safe"
        Onnx -> "ONNX Runtime CPU"
        TensorFlowLite -> "TensorFlow Lite CPU"
        Safetensors -> "Candle CPU"
    }

    companion object {
        fun fromFileName(name: String): ModelFormat? = when (name.substringAfterLast('.').lowercase()) {
            "gguf" -> GGUF
            "litertlm" -> LiteRtLm
            "task" -> MediaPipeTask
            "onnx" -> Onnx
            "tflite" -> TensorFlowLite
            "safetensors" -> Safetensors
            else -> null
        }
    }
}

@Immutable
data class ModelBundle(
    val id: String,
    val displayName: String,
    val mainArtifacts: List<ModelArtifact>,
    val projectorArtifact: ModelArtifact? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val description: String = "",
    val backend: String = "llama.cpp GGUF",
    val addedAt: Long = System.currentTimeMillis(),
    val contextLength: Int = capabilities.contextLength,
    val format: ModelFormat = ModelFormat.GGUF,
    val loadRestriction: LoadRestriction? = null
) {
    val totalSizeBytes: Long
        get() = mainArtifacts.sumOf { it.sizeBytes } + (projectorArtifact?.sizeBytes ?: 0L)

    val isSplit: Boolean
        get() = format == ModelFormat.GGUF && mainArtifacts.size > 1

    val isMultimodal: Boolean
        get() = projectorArtifact != null || capabilities.supportsVision || capabilities.supportsAudio

    val allArtifacts: List<ModelArtifact>
        get() = if (projectorArtifact != null) mainArtifacts + projectorArtifact else mainArtifacts

    val isStorageImport: Boolean
        get() = mainArtifacts.any { it.source is ArtifactSource.PersistedUri }

    val isGguf: Boolean
        get() = format == ModelFormat.GGUF

    val isLiteRt: Boolean
        get() = format == ModelFormat.LiteRtLm

    val isMediaPipe: Boolean
        get() = format == ModelFormat.MediaPipeTask

    fun companionSummary(): String = "${mainArtifacts.size}_parts_${projectorArtifact?.displayName ?: "none"}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        val mainArr = JSONArray()
        mainArtifacts.forEach { mainArr.put(it.toJson()) }
        put("mainArtifacts", mainArr)
        if (projectorArtifact != null) {
            put("projectorArtifact", projectorArtifact.toJson())
        }
        put("capabilities", capabilities.toJson())
        put("description", description)
        put("backend", backend)
        put("addedAt", addedAt)
        put("contextLength", contextLength)
        put("format", format.name)
        if (loadRestriction != null) {
            val resObj = JSONObject().apply {
                put("reason", loadRestriction.reason)
                put("category", loadRestriction.category.name)
            }
            put("loadRestriction", resObj)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ModelBundle {
            val mainList = mutableListOf<ModelArtifact>()
            val mainArr = json.getJSONArray("mainArtifacts")
            for (i in 0 until mainArr.length()) {
                mainList.add(ModelArtifact.fromJson(mainArr.getJSONObject(i)))
            }
            val proj = if (json.has("projectorArtifact") && !json.isNull("projectorArtifact")) {
                ModelArtifact.fromJson(json.getJSONObject("projectorArtifact"))
            } else null
            val caps = if (json.has("capabilities")) {
                ModelCapabilities.fromJson(json.getJSONObject("capabilities"))
            } else ModelCapabilities()
            val ctxLen = json.optInt("contextLength", caps.contextLength)
            val effectiveCaps = if (caps.contextLength == 0 && ctxLen > 0) caps.copy(contextLength = ctxLen) else caps
            val formatStr = json.optString("format", "GGUF")
            val format = ModelFormat.entries.firstOrNull { it.name.equals(formatStr, ignoreCase = true) } ?: ModelFormat.GGUF
            val restriction = if (json.has("loadRestriction") && !json.isNull("loadRestriction")) {
                val resObj = json.getJSONObject("loadRestriction")
                val cat = LoadRestrictionCategory.valueOf(resObj.getString("category"))
                LoadRestriction(reason = resObj.getString("reason"), category = cat)
            } else null

            return ModelBundle(
                id = json.getString("id"),
                displayName = json.getString("displayName"),
                mainArtifacts = mainList,
                projectorArtifact = proj,
                capabilities = effectiveCaps,
                description = json.optString("description", ""),
                backend = json.optString("backend", "llama.cpp GGUF"),
                addedAt = json.optLong("addedAt", System.currentTimeMillis()),
                contextLength = if (ctxLen > 0) ctxLen else caps.contextLength,
                format = format,
                loadRestriction = restriction
            )
        }
    }
}

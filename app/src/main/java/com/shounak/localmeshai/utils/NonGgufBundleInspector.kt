package com.shounak.localmeshai.utils

import android.util.Log
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ThinkingCapability
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Inspection result for non-GGUF model artifacts (.task and .litertlm).
 */
data class NonGgufBundleInspection(
    val isValid: Boolean,
    val format: ModelFormat,
    val capabilities: ModelCapabilities,
    val primaryArtifactName: String,
    val errorMessage: String? = null,
    val diagnosticDetails: String? = null,
    val backend: String = if (format == ModelFormat.MediaPipeTask) "MediaPipe LLM CPU-safe" else "LiteRT-LM GPU",
    val loadRestriction: com.shounak.localmeshai.models.LoadRestriction? = null
)

sealed interface NativeModelInfoResult {
    data class Success(
        val isEmbedding: Boolean,
        val supportsVision: Boolean,
        val supportsAudio: Boolean,
        val supportsThinking: Boolean?,
        val maxContextTokens: Int
    ) : NativeModelInfoResult

    object Unavailable : NativeModelInfoResult
    data class Incompatible(val reason: String) : NativeModelInfoResult
    data class Corrupt(val message: String) : NativeModelInfoResult
}

interface NativeModelInfoReader {
    fun inspect(filePath: String): NativeModelInfoResult
}

class DefaultNativeModelInfoReader : NativeModelInfoReader {
    override fun inspect(filePath: String): NativeModelInfoResult {
        return try {
            com.google.ai.edge.litertlm.ModelInfo.from(filePath).use { modelInfo ->
                val isEmbedding = (modelInfo.modelType == com.google.ai.edge.litertlm.ModelType.EMBEDDING)
                val modalities = modelInfo.inputModalities()
                val supportsVision = modalities.vision
                val supportsAudio = modalities.audio
                val supportsThinking = if (modelInfo is com.google.ai.edge.litertlm.ModelInfo.Llm) {
                    runCatching { modelInfo.supportsThinking() }.getOrNull()
                } else null
                val maxTokens = runCatching { modelInfo.maxContextTokens() }.getOrDefault(0)

                NativeModelInfoResult.Success(
                    isEmbedding = isEmbedding,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsThinking = supportsThinking,
                    maxContextTokens = maxTokens
                )
            }
        } catch (e: UnsatisfiedLinkError) {
            NativeModelInfoResult.Unavailable
        } catch (e: NoClassDefFoundError) {
            NativeModelInfoResult.Unavailable
        } catch (e: LinkageError) {
            NativeModelInfoResult.Unavailable
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("corrupt", ignoreCase = true) ||
                msg.contains("truncated", ignoreCase = true) ||
                msg.contains("invalid flatbuffer", ignoreCase = true) ||
                msg.contains("magic mismatch", ignoreCase = true) ||
                msg.contains("malformed", ignoreCase = true)
            ) {
                NativeModelInfoResult.Corrupt(msg.ifBlank { "Corrupt model container" })
            } else {
                NativeModelInfoResult.Incompatible(msg.ifBlank { "Incompatible model runtime: ${e.javaClass.simpleName}" })
            }
        } catch (_: Throwable) {
            NativeModelInfoResult.Unavailable
        }
    }
}

/**
 * Shared non-GGUF bundle inspector and container validator.
 * Validates MediaPipe Task (.task) and LiteRT-LM (.litertlm) containers, verifying archive
 * structures, section bounds, required weights/tokenizer components, and deriving capabilities
 * via structured metadata parsing without arbitrary binary substring heuristics.
 */
object NonGgufBundleInspector {
    private const val TAG = "NonGgufBundleInspector"
    const val DETECTOR_VERSION = 9

    @Volatile
    var nativeModelInfoReader: NativeModelInfoReader = DefaultNativeModelInfoReader()

    private val LITERTLM_MAGIC = byteArrayOf('L'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte())
    private val TFL3_MAGIC = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

    /**
     * Inspects a non-GGUF file, validating its internal container structure and components.
     */
    fun inspectFile(file: File, expectedFormat: ModelFormat? = null, verifiedSha256: String? = null): NonGgufBundleInspection {
        if (!file.exists() || !file.isFile || file.length() < 32L) {
            val fmt = expectedFormat ?: ModelFormat.MediaPipeTask
            return NonGgufBundleInspection(
                isValid = false,
                format = fmt,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "File does not exist or is too small (${file.length()} bytes).",
                diagnosticDetails = "Minimum container size is 32 bytes."
            )
        }

        // Determine format if not specified
        val format = detectFormat(file) ?: expectedFormat ?: ModelFormat.MediaPipeTask

        return when (format) {
            ModelFormat.Onnx, ModelFormat.TensorFlowLite, ModelFormat.Safetensors -> runCatching {
                val root = PortableModelProfile.root(file)
                val companions = root.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(root).invariantSeparatorsPath }
                NonGgufBundleInspection(true, format, PortableModelBundle.inspect(file, format, companions, file.relativeTo(root).invariantSeparatorsPath), file.name, backend = format.backendLabel,
                    loadRestriction = PortableModelBundle.runtimeRestriction(file, format, companions))
            }.getOrElse {
                NonGgufBundleInspection(false, format, ModelCapabilities(), file.name, errorMessage = it.message, backend = format.backendLabel)
            }
            ModelFormat.LiteRtLm -> runCatching { inspectLiteRtLm(file, verifiedSha256) }.getOrElse { t ->
                Log.w(TAG, "LiteRT-LM inspection threw for ${file.name}: ${t.message}")
                NonGgufBundleInspection(
                    isValid = false,
                    format = ModelFormat.LiteRtLm,
                    capabilities = defaultCapabilities(),
                    primaryArtifactName = file.name,
                    errorMessage = "LiteRT-LM container could not be parsed safely: ${t.message ?: t.javaClass.simpleName}",
                    diagnosticDetails = "Container parser rejected malformed header metadata."
                )
            }
            ModelFormat.MediaPipeTask -> runCatching { inspectMediaPipeTask(file) }.getOrElse { t ->
                Log.w(TAG, "MediaPipe task inspection threw for ${file.name}: ${t.message}")
                NonGgufBundleInspection(
                    isValid = false,
                    format = ModelFormat.MediaPipeTask,
                    capabilities = defaultCapabilities(),
                    primaryArtifactName = file.name,
                    errorMessage = "MediaPipe task bundle could not be parsed safely: ${t.message ?: t.javaClass.simpleName}",
                    diagnosticDetails = "Archive parser rejected malformed metadata."
                )
            }
            ModelFormat.GGUF -> NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.GGUF,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "GGUF models must be inspected using GgufBundleInspector."
            )
        }
    }

    /** Container bytes decide routing; extensions are only picker/download hints. */
    fun detectFormat(file: File): ModelFormat? = runCatching {
        val buffer = ByteArray(2048)
        val count = file.inputStream().use { it.read(buffer) }
        val bytes = buffer.copyOf(count.coerceAtLeast(0))
        when {
            bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "GGUF" -> ModelFormat.GGUF
            bytes.size >= 8 && String(bytes, 0, 8, StandardCharsets.US_ASCII) == "LITERTLM" -> ModelFormat.LiteRtLm
            bytes.size >= 8 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "TFL3" && file.extension.equals("tflite", true) -> ModelFormat.TensorFlowLite
            file.extension.equals("safetensors", true) && runCatching { PortableModelBundle.validateSafetensors(file) }.isSuccess -> ModelFormat.Safetensors
            file.extension.equals("onnx", true) && bytes.firstOrNull()?.let { (it.toInt() and 7) in setOf(0, 1, 2, 5) } == true -> ModelFormat.Onnx
            FileHeaderSniffer.isZip(bytes) -> ModelFormat.MediaPipeTask
            else -> null
        }
    }.getOrNull()

    private fun isLiteRtLmMagic(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8)
                val read = fis.read(buf)
                read == 8 && buf.contentEquals(LITERTLM_MAGIC)
            }
        }.getOrDefault(false)
    }

    /**
     * Inspects MediaPipe Task bundle (.task):
     * Requires valid ZIP container containing:
     * 1. LLM model weights/graph (TF_LITE_PREFILL_DECODE or entry ending in .tflite with valid size)
     * 2. Tokenizer (TOKENIZER_MODEL or entry named tokenizer.model/tokenizer.json with valid size)
     * 3. Vision component recognized ONLY if valid vision encoder entry is present with size > 0
     * 4. Audio component recognized ONLY if valid audio encoder entry is present with size > 0
     * 5. Thinking capability derived from structured metadata if present
     */
    fun inspectMediaPipeTask(file: File): NonGgufBundleInspection {
        val entryNames = mutableListOf<String>()
        var hasLlmModel = false
        var hasTokenizer = false
        var hasVisionEncoder = false
        var hasAudioEncoder = false
        var metadataJson: org.json.JSONObject? = null
        var mediaPipeMeta: DecodedMediaPipeMetadata? = null

        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    entryNames.add(name)
                    val lower = name.lowercase(Locale.ROOT)
                    val validSize = entry.size > 0L || entry.compressedSize > 0L

                    val baseName = name.substringAfterLast('/').lowercase(Locale.ROOT)
                    val llmRoles = setOf("tf_lite_prefill_decode", "model.tflite", "llm.tflite", "prefill_decode.tflite", "model.bin")
                    if (validSize && baseName in llmRoles && TaskBundleValidation.graph(zip, entry)) hasLlmModel = true
                    val isTokenizer = baseName in setOf("tokenizer_model", "tokenizer.model", "tokenizer.json", "sentencepiece.model", "tokenizer.spm")
                    if (validSize && isTokenizer && TaskBundleValidation.tokenizer(zip, entry)) hasTokenizer = true

                    // 3. Vision component check - MUST be a dedicated encoder file with valid size AND TFL3 magic
                    if (validSize && (
                        name.contains("TF_LITE_VISION_ENCODER") ||
                        lower == "vision_encoder.tflite" ||
                        lower == "image_encoder.tflite" ||
                        lower.endsWith("/vision_encoder.tflite") ||
                        lower.endsWith("/image_encoder.tflite")
                    )) {
                        val hasTfl3 = TaskBundleValidation.graph(zip, entry)
                        if (hasTfl3) {
                            hasVisionEncoder = true
                        }
                    }

                    // 4. Audio component check - MUST be a dedicated encoder file with valid size
                    if (validSize && (
                        name.contains("TF_LITE_AUDIO_ENCODER") ||
                        lower == "audio_encoder.tflite" ||
                        lower.endsWith("/audio_encoder.tflite")
                    )) {
                        val hasTfl3 = TaskBundleValidation.graph(zip, entry)
                        if (hasTfl3) {
                            hasAudioEncoder = true
                        }
                    }

                    // 5. Metadata inspection (parse as JSON or protobuf rather than arbitrary binary text search)
                    if (name.contains("METADATA") || lower.contains("metadata.json")) {
                        val sample = TaskBundleValidation.metadata(zip, entry)
                        val text = String(sample, StandardCharsets.UTF_8).trim()
                        if (text.startsWith("{")) metadataJson = org.json.JSONObject(text)
                        else mediaPipeMeta = parseMediaPipeMetadataProto(sample)

                    }
                }
            }
        } catch (e: Exception) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.MediaPipeTask,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "This .task file is not a valid or readable ZIP task bundle: ${e.message}",
                diagnosticDetails = "Archive traversal failed."
            )
        }

        // Validate required components
        if (!hasLlmModel) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.MediaPipeTask,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Incomplete or invalid MediaPipe task bundle: missing or invalid language-model graph (expected TF_LITE_PREFILL_DECODE or model.tflite).",
                diagnosticDetails = "Archive entries found: ${entryNames.take(10).joinToString(", ")}"
            )
        }

        if (!hasTokenizer) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.MediaPipeTask,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Incomplete MediaPipe task bundle: missing or invalid tokenizer component (expected a SentencePiece model or tokenizer JSON).",
                diagnosticDetails = "Archive entries found: ${entryNames.take(10).joinToString(", ")}"
            )
        }

        val thinking = when {
            metadataJson?.optBoolean("enable_thinking", false) == true -> ThinkingCapability.ModelControlled
            metadataJson?.optString("thinking_mode", "").equals("model_controlled", ignoreCase = true) -> ThinkingCapability.ModelControlled
            metadataJson?.optString("thinking", "")?.isNotBlank() == true -> ThinkingCapability.ModelControlled
            mediaPipeMeta?.thinkingCapability != null && mediaPipeMeta.thinkingCapability != ThinkingCapability.Unknown -> mediaPipeMeta.thinkingCapability
            else -> ThinkingCapability.Unknown
        }

        val contextLength = mediaPipeMeta?.maxSeqLen ?: 0

        val caps = ModelCapabilities(
            supportsText = true,
            supportsVision = hasVisionEncoder,
            supportsAudio = hasAudioEncoder,
            thinking = thinking,
            thinkingDetectorVersion = DETECTOR_VERSION,
            thinkingArtifactIdentity = file.absolutePath,
            contextLength = contextLength
        )

        return NonGgufBundleInspection(
            isValid = true,
            format = ModelFormat.MediaPipeTask,
            capabilities = caps,
            primaryArtifactName = file.name
        )
    }

    /**
     * Inspects LiteRT-LM container (.litertlm):
     * Follows official LiteRT-LM container reader specification (litertlm_read.cc).
     * Validates:
     * - Magic "LITERTLM" at offset 0
     * - Major version <= 1
     * - Header end offset bounds >= 32 and <= file.length()
     * - FlatBuffer root offset within header bounds
     * - Parses FlatBuffer SectionMetadata table and validates section bounds
     * - Reads and decodes LlmMetadataProto section for structured capabilities (modalities, channels, templates)
     */
    fun inspectLiteRtLm(file: File, verifiedSha256: String? = null): NonGgufBundleInspection = try {
        inspectLiteRtLmContainer(file, verifiedSha256)
    } catch (error: Exception) {
        NonGgufBundleInspection(false, ModelFormat.LiteRtLm, defaultCapabilities(), file.name,
            errorMessage = "Invalid LiteRT model container: ${error.message}")
    }

    private fun inspectLiteRtLmContainer(file: File, verifiedSha256: String?): NonGgufBundleInspection {
        val fileLength = file.length()
        if (fileLength < 32L) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "LiteRT-LM container is too small ($fileLength bytes, minimum 32 bytes required).",
                diagnosticDetails = "File header truncated."
            )
        }

        val headerBytes = ByteArray(32)
        try {
            FileInputStream(file).use { fis ->
                val read = fis.read(headerBytes)
                if (read < 32) {
                    return NonGgufBundleInspection(
                        isValid = false,
                        format = ModelFormat.LiteRtLm,
                        capabilities = defaultCapabilities(),
                        primaryArtifactName = file.name,
                        errorMessage = "Could not read 32-byte LiteRT-LM container header.",
                        diagnosticDetails = "Read $read bytes."
                    )
                }
            }
        } catch (e: Exception) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Failed to read LiteRT-LM file: ${e.message}"
            )
        }

        // Check magic
        val hasLiteRtLmMagic = (0..7).all { headerBytes[it] == LITERTLM_MAGIC[it] }
        if (!hasLiteRtLmMagic) {
            val hasTfl3 = (4..7).all { headerBytes[it] == TFL3_MAGIC[it - 4] }
            val msg = if (hasTfl3) {
                "File is a raw TensorFlow Lite flatbuffer (.tflite), not a LiteRT-LM container bundle."
            } else {
                "File is not a valid LiteRT-LM container (missing LITERTLM header magic)."
            }
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = msg,
                diagnosticDetails = "Header magic mismatch."
            )
        }

        val majorVersion = readUInt32LE(headerBytes, 8)
        val minorVersion = readUInt32LE(headerBytes, 12)
        val patchVersion = readUInt32LE(headerBytes, 16)
        // Bytes 20..23 are padding
        val headerEndOffset = readUInt64LE(headerBytes, 24)

        if (majorVersion > 1L) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Unsupported LiteRT-LM container version $majorVersion.$minorVersion.$patchVersion (expected version 1.x).",
                diagnosticDetails = "Incompatible container version."
            )
        }

        val maxHeaderPayload = 8 * 1024 * 1024L // 8MB
        if (headerEndOffset < 32L || headerEndOffset > fileLength || (headerEndOffset - 32L) > maxHeaderPayload) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Invalid or oversized LiteRT-LM header end offset ($headerEndOffset bytes, max payload 8MB).",
                diagnosticDetails = "Header end offset out of bounds."
            )
        }

        if (fileLength <= headerEndOffset) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "LiteRT-LM container is truncated: no model weights section after header (headerEndOffset=$headerEndOffset, file size=$fileLength).",
                diagnosticDetails = "Container sections missing."
            )
        }

        // Read FlatBuffer header payload from byte 32 to headerEndOffset
        val headerDataSize = (headerEndOffset - 32L).toInt()
        val headerFbData = ByteArray(headerDataSize)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(32L)
                raf.readFully(headerFbData)
            }
        } catch (e: Exception) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Failed to read LiteRT-LM FlatBuffer header payload: ${e.message}"
            )
        }

        // Parse FlatBuffer SectionMetadata
        val sectionList = parseFlatBufferSections(headerFbData, headerEndOffset, fileLength)
        if (sectionList == null) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Malformed LiteRT-LM FlatBuffer section table or header metadata.",
                diagnosticDetails = "FlatBuffer parsing failed or section bounds invalid."
            )
        }

        var hasTfLiteModel = false
        var hasTokenizer = false
        var metadataSection: FlatBufferSection? = null

        for (section in sectionList) {
            when (section.dataType) {
                3 -> hasTfLiteModel = true // TFLiteModel
                4, 6 -> hasTokenizer = true // SP_Tokenizer, HF_Tokenizer_Zlib
                5 -> metadataSection = section // LlmMetadataProto
            }
        }

        if (!hasTfLiteModel) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Incomplete LiteRT-LM container: missing TFLiteModel section.",
                diagnosticDetails = "Sections found: ${sectionList.map { it.dataType }}"
            )
        }

        if (!hasTokenizer) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.LiteRtLm,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Incomplete LiteRT-LM container: missing tokenizer section (SentencePiece or HuggingFace).",
                diagnosticDetails = "Sections found: ${sectionList.map { it.dataType }}"
            )
        }

        var supportsVision = false
        var supportsAudio = false
        var thinking = ThinkingCapability.Unknown
        var declaredThinking: Boolean? = null
        var reasoningChannels = emptySet<String>()
        var reasoningPrefill = false
        var contextLength = 2048

        // If LlmMetadataProto section exists, parse it directly from file
        if (metadataSection != null) {
            val protoLength = metadataSection.endOffset - metadataSection.beginOffset
            require(protoLength in 1..4_194_304L) { "LiteRT metadata is empty or exceeds 4 MiB" }
            val protoSize = protoLength.toInt()
            if (protoSize in 1..4_194_304) { // Up to 4 MB metadata
                try {
                    val protoBytes = ByteArray(protoSize)
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(metadataSection.beginOffset)
                        raf.readFully(protoBytes)
                    }
                    val meta = parseLlmMetadataProto(protoBytes)
                    supportsVision = meta.supportsVision
                    supportsAudio = meta.supportsAudio
                    thinking = meta.thinkingCapability
                    declaredThinking = meta.declaredThinking
                    reasoningChannels = meta.reasoningChannels
                    reasoningPrefill = meta.reasoningPrefill
                    if (meta.maxNumTokens > 0) {
                        contextLength = meta.maxNumTokens
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid LiteRT model metadata: ${e.message}", e)
                }
            }
        }

        var restriction: com.shounak.localmeshai.models.LoadRestriction? = null
        when (val nativeResult = nativeModelInfoReader.inspect(file.absolutePath)) {
            is NativeModelInfoResult.Success -> {
                if (nativeResult.isEmbedding) {
                    return NonGgufBundleInspection(
                        isValid = false,
                        format = ModelFormat.LiteRtLm,
                        capabilities = defaultCapabilities(),
                        primaryArtifactName = file.name,
                        errorMessage = "Unsupported model type: LiteRT-LM EMBEDDING models cannot be used for generative chat.",
                        diagnosticDetails = "ModelType is EMBEDDING"
                    )
                }
                // Authoritative modalities replace guesses directly (NOT OR-ed)
                supportsVision = nativeResult.supportsVision
                supportsAudio = nativeResult.supportsAudio
                if (nativeResult.supportsThinking == true) {
                    if (thinking != ThinkingCapability.Toggleable) {
                        thinking = ThinkingCapability.ModelControlled
                    }
                } else if (declaredThinking == false && thinking == ThinkingCapability.Unknown) {
                    thinking = ThinkingCapability.Unsupported
                }
                if (nativeResult.maxContextTokens > 0) {
                    contextLength = nativeResult.maxContextTokens
                }
            }
            is NativeModelInfoResult.Incompatible -> {
                restriction = com.shounak.localmeshai.models.LoadRestriction(
                    reason = "Hardware or runtime incompatible: ${nativeResult.reason}",
                    category = com.shounak.localmeshai.models.LoadRestrictionCategory.RuntimeIncompatibility
                )
            }
            is NativeModelInfoResult.Corrupt -> {
                return NonGgufBundleInspection(
                    isValid = false,
                    format = ModelFormat.LiteRtLm,
                    capabilities = defaultCapabilities(),
                    primaryArtifactName = file.name,
                    errorMessage = "LiteRT-LM model file is corrupt or unreadable: ${nativeResult.message}",
                    diagnosticDetails = "Native model validation failed."
                )
            }
            is NativeModelInfoResult.Unavailable -> {
                // Unavailable on host JVM without JNI; fall back to container-parsed capabilities
            }
        }

        val legacyProfile = if (thinking == ThinkingCapability.Unknown && declaredThinking == null) {
            LegacyThinkingProfiles.controlFor(file, verifiedSha256)
        } else com.shounak.localmeshai.models.ThinkingControlStrategy.None
        if (legacyProfile != com.shounak.localmeshai.models.ThinkingControlStrategy.None) thinking = ThinkingCapability.Toggleable

        val caps = ModelCapabilities(
            supportsText = true,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            thinking = thinking,
            reasoningChannels = reasoningChannels,
            reasoningPrefill = reasoningPrefill,
            thinkingControl = when {
                legacyProfile != com.shounak.localmeshai.models.ThinkingControlStrategy.None -> legacyProfile
                thinking == ThinkingCapability.Toggleable -> com.shounak.localmeshai.models.ThinkingControlStrategy.NativeTemplate
                else -> com.shounak.localmeshai.models.ThinkingControlStrategy.None
            },
            thinkingDetectorVersion = DETECTOR_VERSION,
            thinkingArtifactIdentity = file.absolutePath,
            contextLength = contextLength
        )

        return NonGgufBundleInspection(
            isValid = true,
            format = ModelFormat.LiteRtLm,
            capabilities = caps,
            primaryArtifactName = file.name,
            loadRestriction = restriction
        )
    }

    private data class FlatBufferSection(
        val beginOffset: Long,
        val endOffset: Long,
        val dataType: Int
    )

    /**
     * Parses the FlatBuffer header and extracts SectionObject entries following litertlm_header_schema.fbs:
     * - root LiteRTLMMetaData (field 1: section_metadata)
     * - SectionMetadata (field 0: objects vector)
     * - SectionObject (field 1: begin_offset, field 2: end_offset, field 3: data_type)
     */
    private fun parseFlatBufferSections(
        bytes: ByteArray,
        headerEndOffset: Long,
        fileLength: Long
    ): List<FlatBufferSection>? {
        if (bytes.size < 4) return null
        val rootOffset = readUInt32LE(bytes, 0).toInt()
        if (rootOffset < 4 || rootOffset >= bytes.size) return null

        // In LiteRTLMMetaData root table:
        if (rootOffset + 4 > bytes.size) return null
        val vtableOffset = readInt32LE(bytes, rootOffset)
        val vtablePos = rootOffset - vtableOffset
        if (vtablePos < 0 || vtablePos + 2 > bytes.size) return null
        val vtableSize = readUInt16LE(bytes, vtablePos)
        if (vtablePos + vtableSize > bytes.size) return null

        // Field 1 is section_metadata (vtable offset 6: 4 + 1 * 2)
        if (vtableSize < 8) return emptyList()
        val secMetaFieldOffset = readUInt16LE(bytes, vtablePos + 6)
        if (secMetaFieldOffset == 0) return emptyList()

        val secMetaPosBase = rootOffset + secMetaFieldOffset
        if (secMetaPosBase + 4 > bytes.size) return null
        val secMetaTableOffset = readUInt32LE(bytes, secMetaPosBase).toInt()
        val secMetaTablePos = secMetaPosBase + secMetaTableOffset
        if (secMetaTablePos < 0 || secMetaTablePos + 4 > bytes.size) return null

        // In SectionMetadata table:
        val secVtableOffset = readInt32LE(bytes, secMetaTablePos)
        val secVtablePos = secMetaTablePos - secVtableOffset
        if (secVtablePos < 0 || secVtablePos + 2 > bytes.size) return null
        val secVtableSize = readUInt16LE(bytes, secVtablePos)

        // Field 0 is objects vector (vtable offset 4)
        if (secVtableSize < 6) return emptyList()
        val objectsFieldOffset = readUInt16LE(bytes, secVtablePos + 4)
        if (objectsFieldOffset == 0) return emptyList()

        val vecPosBase = secMetaTablePos + objectsFieldOffset
        if (vecPosBase + 4 > bytes.size) return null
        val vecOffset = readUInt32LE(bytes, vecPosBase).toInt()
        val vecStart = vecPosBase + vecOffset
        if (vecStart < 0 || vecStart + 4 > bytes.size) return null

        val count = readUInt32LE(bytes, vecStart).toInt()
        if (count <= 0 || count > 100) return null
        if (vecStart + 4 + count.toLong() * 4L > bytes.size.toLong()) return null

        val sections = mutableListOf<FlatBufferSection>()
        for (i in 0 until count) {
            val elemPos = vecStart + 4 + i * 4
            if (elemPos + 4 > bytes.size) return null
            val elemOffset = readUInt32LE(bytes, elemPos).toInt()
            val sectionTablePos = elemPos + elemOffset
            if (sectionTablePos < 0 || sectionTablePos + 4 > bytes.size) return null

            val sVtableOffset = readInt32LE(bytes, sectionTablePos)
            val sVtablePos = sectionTablePos - sVtableOffset
            if (sVtablePos < 0 || sVtablePos + 2 > bytes.size) return null
            val sVtableSize = readUInt16LE(bytes, sVtablePos)

            // SectionObject fields:
            // field 1: begin_offset (vtable offset 6)
            // field 2: end_offset (vtable offset 8)
            // field 3: data_type (vtable offset 10)
            val beginOffsetField = if (sVtableSize >= 8) readUInt16LE(bytes, sVtablePos + 6) else 0
            val endOffsetField = if (sVtableSize >= 10) readUInt16LE(bytes, sVtablePos + 8) else 0
            val dataTypeField = if (sVtableSize >= 12) readUInt16LE(bytes, sVtablePos + 10) else 0

            val beginOffset = if (beginOffsetField != 0 && sectionTablePos + beginOffsetField + 8 <= bytes.size) {
                readUInt64LE(bytes, sectionTablePos + beginOffsetField)
            } else 0L

            val endOffset = if (endOffsetField != 0 && sectionTablePos + endOffsetField + 8 <= bytes.size) {
                readUInt64LE(bytes, sectionTablePos + endOffsetField)
            } else 0L

            val dataType = if (dataTypeField != 0 && sectionTablePos + dataTypeField < bytes.size) {
                bytes[sectionTablePos + dataTypeField].toInt() and 0xFF
            } else 0

            // Validate section bounds
            if (beginOffset < headerEndOffset || endOffset > fileLength || beginOffset > endOffset) {
                return null
            }

            sections.add(FlatBufferSection(beginOffset, endOffset, dataType))
        }

        return sections
    }

    private data class DecodedMediaPipeMetadata(
        val maxSeqLen: Int = 0,
        val startToken: String? = null,
        val stopTokens: List<String> = emptyList(),
        val thinkingCapability: ThinkingCapability = ThinkingCapability.Unknown
    )

    /**
     * Parses the protobuf binary data of odml.infra.proto.LlmParameters
     * without external generated code dependencies.
     */
    private fun parseMediaPipeMetadataProto(bytes: ByteArray): DecodedMediaPipeMetadata {
        var maxSeqLen = 0
        var startToken: String? = null
        val stopTokens = mutableListOf<String>()
        val promptStrings = mutableListOf<String>()
        fun promptTemplate(data: ByteArray) {
            val reader = ProtoReader(data.inputStream(), data.size.toLong())
            while (true) {
                val field = reader.next() ?: break
                if (field.wire == 2 && field.number in 1..3) promptStrings += String(reader.bytes(131072), StandardCharsets.UTF_8)
                else reader.skip(field)
            }
        }
        val reader = ProtoReader(bytes.inputStream(), bytes.size.toLong())
        while (true) {
            val field = reader.next() ?: break
            when {
                field.number == 1 && field.wire == 2 -> {
                    val data = reader.bytes(131072)
                    val params = ProtoReader(data.inputStream(), data.size.toLong())
                    while (true) {
                        val parameter = params.next() ?: break
                        if (parameter.number == 2 && parameter.wire == 0) {
                            val value = params.varint()
                            require(value in 1..Int.MAX_VALUE.toLong()) { "Invalid model context length" }
                            maxSeqLen = value.toInt()
                        } else params.skip(parameter)
                    }
                }
                field.number == 5 && field.wire == 2 -> stopTokens += String(reader.bytes(131072), StandardCharsets.UTF_8)
                field.number == 6 && field.wire == 2 -> startToken = String(reader.bytes(131072), StandardCharsets.UTF_8)
                field.number == 8 && field.wire == 2 -> promptTemplate(reader.bytes(131072))
                field.number == 17 && field.wire == 2 -> {
                    val data = reader.bytes(131072)
                    val templates = ProtoReader(data.inputStream(), data.size.toLong())
                    while (true) {
                        val template = templates.next() ?: break
                        if (template.number in 1..3 && template.wire == 2) promptTemplate(templates.bytes(131072))
                        else templates.skip(template)
                    }
                }
                else -> reader.skip(field)
            }
        }
        val hasThinkingTags = (promptStrings + stopTokens + listOfNotNull(startToken)).any {
            it.contains("<think>") || it.contains("</think>")
        }
        // MediaPipe prompt prefix/suffix strings are not Jinja expressions or an SDK toggle.
        return DecodedMediaPipeMetadata(maxSeqLen, startToken, stopTokens,
            if (hasThinkingTags) ThinkingCapability.ModelControlled else ThinkingCapability.Unknown)
    }

    private data class DecodedLlmMetadata(
        val maxNumTokens: Int = 0,
        val supportsVision: Boolean = false,
        val supportsAudio: Boolean = false,
        val thinkingCapability: ThinkingCapability = ThinkingCapability.Unknown,
        val jinjaTemplate: String? = null,
        val declaredThinking: Boolean? = null,
        val reasoningChannels: Set<String> = emptySet(),
        val reasoningPrefill: Boolean = false
    )

    /**
     * Parses the protobuf binary data of litert.lm.proto.LlmMetadata
     * without external generated code dependencies.
     */
    internal fun parseLiteRtThinkingForTesting(bytes: ByteArray): ThinkingCapability = parseLlmMetadataProto(bytes).thinkingCapability

    private fun parseLlmMetadataProto(bytes: ByteArray): DecodedLlmMetadata {
        val limit = 4 * 1024 * 1024
        var maxTokens = 0
        var vision = false
        var audio = false
        var jinja: String? = null
        var declared: Boolean? = null
        var reasoning = false
        val reasoningNames = mutableSetOf<String>()
        val promptStrings = mutableListOf<String>()
        var modelPrefix = ""
        fun nested(data: ByteArray, block: (ProtoReader, ProtoReader.Field) -> Unit) {
            val reader = ProtoReader(data.inputStream(), data.size.toLong())
            while (true) { val field = reader.next() ?: break; block(reader, field) }
        }
        nested(bytes) { reader, field ->
            when {
                field.number == 5 && field.wire == 0 -> {
                    val value = reader.varint()
                    require(value in 0..Int.MAX_VALUE.toLong()) { "Invalid LiteRT context length" }
                    maxTokens = value.toInt()
                }
                field.number == 11 && field.wire == 0 -> {
                    val value = reader.varint()
                    require(value == 0L || value == 1L) { "Invalid thinking flag" }
                    declared = value == 1L
                }
                field.number == 7 && field.wire == 2 -> jinja = String(reader.bytes(limit), StandardCharsets.UTF_8)
                field.number == 3 && field.wire == 2 -> nested(reader.bytes(limit)) { roles, role ->
                    if (role.number in 1..3 && role.wire == 2) nested(roles.bytes(limit)) { affixes, affix ->
                        if (affix.number in 1..2 && affix.wire == 2) {
                            val text = String(affixes.bytes(limit), StandardCharsets.UTF_8)
                            promptStrings += text
                            if (role.number == 2 && affix.number == 1) modelPrefix = text
                        }
                        else affixes.skip(affix)
                    } else roles.skip(role)
                }
                field.number == 6 && field.wire == 2 -> nested(reader.bytes(limit)) { types, type ->
                    if (type.wire == 2) {
                        vision = vision || type.number in setOf(2, 4, 8, 9)
                        audio = audio || type.number in setOf(2, 8)
                    }
                    types.skip(type)
                }
                field.number == 8 && field.wire == 2 -> {
                    var name = ""
                    var isReasoning = false
                    nested(reader.bytes(limit)) { channels, channel ->
                        if (channel.wire == 2 && channel.number in 1..3) {
                            val text = String(channels.bytes(limit), StandardCharsets.UTF_8)
                            if (channel.number == 1) name = text
                            isReasoning = isReasoning || text.contains("<think>") || text.contains("</think>")
                        } else if (channel.number == 4 && channel.wire == 0) {
                            val value = channels.varint()
                            require(value in 0..1L) { "Invalid reasoning channel flag" }
                            isReasoning = isReasoning || value == 1L
                        } else channels.skip(channel)
                    }
                    isReasoning = isReasoning || name.lowercase(Locale.ROOT) in setOf("thought", "thinking", "reasoning", "analysis")
                    reasoning = reasoning || isReasoning
                    if (isReasoning && name.isNotBlank()) reasoningNames += name
                }
                else -> reader.skip(field)
            }
        }
        val templateReasoning = (promptStrings + listOfNotNull(jinja)).any { it.contains("<think>") || it.contains("</think>") }
        val capability = when {
            jinja?.contains("enable_thinking") == true -> ThinkingCapability.Toggleable
            reasoning || templateReasoning || declared == true -> ThinkingCapability.ModelControlled
            declared == false -> ThinkingCapability.Unsupported
            else -> ThinkingCapability.Unknown
        }
        // Jinja overrides legacy affixes. Never infer an active branch from tag substrings
        // inside a Jinja program; structured native channels remain authoritative there.
        val reasoningPrefill = jinja == null && ThinkingTextUtils.parse(modelPrefix, true).isThinkingActive
        return DecodedLlmMetadata(maxTokens, vision, audio, capability, jinja, declared, reasoningNames, reasoningPrefill)
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt()) shl 24)
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readUInt64LE(bytes: ByteArray, offset: Int): Long {
        val low = readUInt32LE(bytes, offset)
        val high = readUInt32LE(bytes, offset + 4)
        return (low and 0xFFFFFFFFL) or ((high and 0xFFFFFFFFL) shl 32)
    }

    private fun defaultCapabilities(): ModelCapabilities {
        return ModelCapabilities(
            supportsText = true,
            supportsVision = false,
            supportsAudio = false,
            thinking = ThinkingCapability.Unknown,
            thinkingDetectorVersion = DETECTOR_VERSION
        )
    }
}

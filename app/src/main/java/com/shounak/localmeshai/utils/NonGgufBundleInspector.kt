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
    const val DETECTOR_VERSION = 6

    @Volatile
    var nativeModelInfoReader: NativeModelInfoReader = DefaultNativeModelInfoReader()

    private val LITERTLM_MAGIC = byteArrayOf('L'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte())
    private val TFL3_MAGIC = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

    /**
     * Inspects a non-GGUF file, validating its internal container structure and components.
     */
    fun inspectFile(file: File, expectedFormat: ModelFormat? = null): NonGgufBundleInspection {
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
        val format = expectedFormat ?: run {
            if (isLiteRtLmMagic(file)) ModelFormat.LiteRtLm else ModelFormat.MediaPipeTask
        }

        return when (format) {
            ModelFormat.LiteRtLm -> runCatching { inspectLiteRtLm(file) }.getOrElse { t ->
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

                    // 1. LLM weights/graph check (must have positive size AND TFL3 magic at bytes 4..7)
                    if (validSize && (
                        name.contains("TF_LITE_PREFILL_DECODE") ||
                        lower.endsWith(".tflite") ||
                        lower.contains("prefill_decode") ||
                        lower.contains("model.bin")
                    )) {
                        val hasTfl3 = runCatching {
                            zip.getInputStream(entry).use { stream ->
                                val header = ByteArray(8)
                                val read = stream.read(header)
                                read == 8 && (4..7).all { header[it] == TFL3_MAGIC[it - 4] } && entry.size >= 32L
                            }
                        }.getOrDefault(false)
                        if (hasTfl3) {
                            hasLlmModel = true
                        }
                    }

                    // 2. Tokenizer check (must have positive size and readable data)
                    if (validSize && (
                        name.contains("TOKENIZER_MODEL") ||
                        lower.contains("tokenizer.model") ||
                        lower.contains("tokenizer.json") ||
                        lower.contains("sentencepiece") ||
                        lower.endsWith(".spm") ||
                        lower.endsWith(".vocab")
                    )) {
                        val canRead = runCatching {
                            zip.getInputStream(entry).use { stream ->
                                val sample = ByteArray(4)
                                stream.read(sample) > 0
                            }
                        }.getOrDefault(false)
                        if (canRead) {
                            hasTokenizer = true
                        }
                    }

                    // 3. Vision component check - MUST be a dedicated encoder file with valid size AND TFL3 magic
                    if (validSize && (
                        name.contains("TF_LITE_VISION_ENCODER") ||
                        lower == "vision_encoder.tflite" ||
                        lower == "image_encoder.tflite" ||
                        lower.endsWith("/vision_encoder.tflite") ||
                        lower.endsWith("/image_encoder.tflite")
                    )) {
                        val hasTfl3 = runCatching {
                            zip.getInputStream(entry).use { stream ->
                                val header = ByteArray(8)
                                val read = stream.read(header)
                                read == 8 && (4..7).all { header[it] == TFL3_MAGIC[it - 4] } && entry.size >= 32L
                            }
                        }.getOrDefault(false)
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
                        val hasTfl3 = runCatching {
                            zip.getInputStream(entry).use { stream ->
                                val header = ByteArray(8)
                                val read = stream.read(header)
                                read == 8 && (4..7).all { header[it] == TFL3_MAGIC[it - 4] } && entry.size >= 32L
                            }
                        }.getOrDefault(false)
                        if (hasTfl3 || entry.size > 1024L) {
                            hasAudioEncoder = true
                        }
                    }

                    // 5. Metadata inspection (parse as JSON or protobuf rather than arbitrary binary text search)
                    if (name.contains("METADATA") || lower.contains("metadata.json")) {
                        runCatching {
                            zip.getInputStream(entry).use { stream ->
                                val sample = ByteArray(minOf(entry.size.coerceAtLeast(0L), 131072L).toInt())
                                val read = stream.read(sample)
                                if (read > 0) {
                                    val text = String(sample, 0, read, StandardCharsets.UTF_8).trim()
                                    if (text.startsWith("{")) {
                                        metadataJson = org.json.JSONObject(text)
                                    } else {
                                        mediaPipeMeta = parseMediaPipeMetadataProto(sample.copyOf(read))
                                    }
                                }
                            }
                        }
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
                errorMessage = "Incomplete or invalid MediaPipe task bundle: missing LLM model component with valid TFL3 header (expected TF_LITE_PREFILL_DECODE or .tflite).",
                diagnosticDetails = "Archive entries found: ${entryNames.take(10).joinToString(", ")}"
            )
        }

        if (!hasTokenizer) {
            return NonGgufBundleInspection(
                isValid = false,
                format = ModelFormat.MediaPipeTask,
                capabilities = defaultCapabilities(),
                primaryArtifactName = file.name,
                errorMessage = "Incomplete MediaPipe task bundle: missing tokenizer component (expected TOKENIZER_MODEL or tokenizer.model).",
                diagnosticDetails = "Archive entries found: ${entryNames.take(10).joinToString(", ")}"
            )
        }

        val thinking = when {
            metadataJson?.optBoolean("enable_thinking", false) == true -> ThinkingCapability.Toggleable
            metadataJson?.optString("thinking_mode", "").equals("model_controlled", ignoreCase = true) -> ThinkingCapability.ModelControlled
            metadataJson?.optString("thinking", "")?.isNotBlank() == true -> ThinkingCapability.ModelControlled
            mediaPipeMeta?.thinkingCapability != null && mediaPipeMeta.thinkingCapability != ThinkingCapability.Unknown -> mediaPipeMeta.thinkingCapability
            else -> ThinkingCapability.Unknown
        }

        val contextLength = if ((mediaPipeMeta?.maxSeqLen ?: 0) > 0) mediaPipeMeta!!.maxSeqLen else 2048

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
    fun inspectLiteRtLm(file: File): NonGgufBundleInspection {
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
        var contextLength = 2048

        // If LlmMetadataProto section exists, parse it directly from file
        if (metadataSection != null) {
            val protoSize = (metadataSection.endOffset - metadataSection.beginOffset).toInt()
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
                    if (meta.maxNumTokens > 0) {
                        contextLength = meta.maxNumTokens
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error decoding LlmMetadataProto section: ${e.message}")
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
                } else if (nativeResult.supportsThinking == false && thinking == ThinkingCapability.Unknown) {
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

        val caps = ModelCapabilities(
            supportsText = true,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            thinking = thinking,
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
        var pos = 0
        var maxSeqLen = 0
        var startToken: String? = null
        val stopTokens = mutableListOf<String>()
        val promptTemplates = mutableListOf<String>()

        while (pos < bytes.size) {
            val tagAndWire = readVarint(bytes, pos) ?: break
            pos = tagAndWire.second
            val tag = tagAndWire.first
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()

            when (wireType) {
                0 -> { // Varint
                    val value = readVarint(bytes, pos) ?: break
                    pos = value.second
                }
                1 -> { // 64-bit
                    if (pos + 8 > bytes.size) break
                    pos += 8
                }
                2 -> { // Length-delimited
                    val lenRes = readVarint(bytes, pos) ?: break
                    pos = lenRes.second
                    val len = lenRes.first.toInt()
                    if (pos + len > bytes.size) break

                    when (fieldNumber) {
                        1 -> { // transformer_parameters (TransformerParameters)
                            var tPos = pos
                            val endT = pos + len
                            while (tPos < endT) {
                                val tTag = readVarint(bytes, tPos) ?: break
                                tPos = tTag.second
                                val tField = (tTag.first ushr 3).toInt()
                                val tWire = (tTag.first and 0x7L).toInt()
                                if (tWire == 0) {
                                    val tVal = readVarint(bytes, tPos) ?: break
                                    tPos = tVal.second
                                    if (tField == 1) { // max_seq_len
                                        maxSeqLen = tVal.first.toInt()
                                    }
                                } else {
                                    tPos = skipWire(bytes, tPos, tWire, endT)
                                }
                            }
                        }
                        5 -> { // stop_tokens (repeated string)
                            val token = String(bytes, pos, len, StandardCharsets.UTF_8)
                            stopTokens.add(token)
                        }
                        6 -> { // start_token (string)
                            startToken = String(bytes, pos, len, StandardCharsets.UTF_8)
                        }
                        17 -> { // prompt_templates (repeated PromptTemplate)
                            val templateContent = String(bytes, pos, len, StandardCharsets.UTF_8)
                            promptTemplates.add(templateContent)
                        }
                    }
                    pos += len
                }
                5 -> { // 32-bit
                    if (pos + 4 > bytes.size) break
                    pos += 4
                }
                else -> break
            }
        }

        val hasThinkingToggle = promptTemplates.any { it.contains("enable_thinking") }
        val hasThinkingTags = promptTemplates.any { it.contains("<think>") } ||
            stopTokens.any { it.contains("<think>") || it.contains("</think>") } ||
            (startToken?.contains("<think>") == true)

        val thinkingCapability = when {
            hasThinkingToggle -> ThinkingCapability.Toggleable
            hasThinkingTags -> ThinkingCapability.ModelControlled
            else -> ThinkingCapability.Unknown
        }

        return DecodedMediaPipeMetadata(
            maxSeqLen = maxSeqLen,
            startToken = startToken,
            stopTokens = stopTokens,
            thinkingCapability = thinkingCapability
        )
    }

    private data class DecodedLlmMetadata(
        val maxNumTokens: Int = 0,
        val supportsVision: Boolean = false,
        val supportsAudio: Boolean = false,
        val thinkingCapability: ThinkingCapability = ThinkingCapability.Unknown,
        val jinjaTemplate: String? = null
    )

    /**
     * Parses the protobuf binary data of litert.lm.proto.LlmMetadata
     * without external generated code dependencies.
     */
    private fun parseLlmMetadataProto(bytes: ByteArray): DecodedLlmMetadata {
        var pos = 0
        var maxNumTokens = 0
        var supportsVision = false
        var supportsAudio = false
        var jinjaTemplate: String? = null
        val channelNames = mutableListOf<String>()
        val channelStarts = mutableListOf<String>()

        while (pos < bytes.size) {
            val tagAndWire = readVarint(bytes, pos) ?: break
            pos = tagAndWire.second
            val tag = tagAndWire.first
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()

            when (wireType) {
                0 -> { // Varint
                    val value = readVarint(bytes, pos) ?: break
                    pos = value.second
                    if (fieldNumber == 5) {
                        maxNumTokens = value.first.toInt()
                    }
                }
                1 -> { // 64-bit fixed
                    if (pos < 0 || pos > bytes.size) break
                    // 64-bit fixed field: must fit entirely before advancing.
                    if (pos + 8 > bytes.size) break
                    pos += 8
                }
                2 -> { // Length-delimited
                    val lenRes = readVarint(bytes, pos) ?: break
                    pos = lenRes.second
                    val len = lenRes.first.toInt()
                    if (pos + len > bytes.size) break

                    when (fieldNumber) {
                        6 -> { // llm_model_type (LlmModelType)
                            var subPos = pos
                            val endSub = pos + len
                            while (subPos < endSub) {
                                val sTag = readVarint(bytes, subPos) ?: break
                                subPos = sTag.second
                                val sField = (sTag.first ushr 3).toInt()
                                val sWire = (sTag.first and 0x7L).toInt()

                                // Model types with vision: 2 (Gemma3N), 4 (Gemma3), 8 (Gemma4), 9 (FastVlm)
                                if (sField in listOf(2, 4, 8, 9)) {
                                    supportsVision = true
                                }
                                // Model types with audio: 2 (Gemma3N), 8 (Gemma4)
                                if (sField in listOf(2, 8)) {
                                    supportsAudio = true
                                }

                                subPos = skipWire(bytes, subPos, sWire, endSub)
                            }
                        }
                        7 -> { // jinja_prompt_template
                            jinjaTemplate = String(bytes, pos, len, StandardCharsets.UTF_8)
                        }
                        8 -> { // channels (Channel)
                            var cPos = pos
                            val endC = pos + len
                            var cName = ""
                            var cStart = ""
                            while (cPos < endC) {
                                val cTag = readVarint(bytes, cPos) ?: break
                                cPos = cTag.second
                                val cField = (cTag.first ushr 3).toInt()
                                val cWire = (cTag.first and 0x7L).toInt()

                                if (cWire == 2) {
                                    val cLenRes = readVarint(bytes, cPos) ?: break
                                    cPos = cLenRes.second
                                    val cLen = cLenRes.first.toInt()
                                    if (cPos + cLen <= endC) {
                                        val str = String(bytes, cPos, cLen, StandardCharsets.UTF_8)
                                        if (cField == 1) cName = str
                                        if (cField == 2) cStart = str
                                    }
                                    cPos += cLen
                                } else {
                                    cPos = skipWire(bytes, cPos, cWire, endC)
                                }
                            }
                            if (cName.isNotBlank()) channelNames.add(cName)
                            if (cStart.isNotBlank()) channelStarts.add(cStart)
                        }
                    }
                    pos += len
                }
                5 -> { // 32-bit
                    if (pos < 0 || pos + 4 > bytes.size) break
                    pos += 4
                }
                else -> break
            }
        }

        val hasThinkingChannel = channelNames.any {
            it.equals("thought", ignoreCase = true) || it.equals("thinking", ignoreCase = true)
        } || channelStarts.any { it.contains("<think>") }

        val templateHasThinkingToggle = jinjaTemplate?.contains("enable_thinking") == true
        val templateHasThinkingTags = jinjaTemplate?.contains("<think>") == true

        val thinkingCapability = when {
            templateHasThinkingToggle -> ThinkingCapability.Toggleable
            hasThinkingChannel || templateHasThinkingTags -> ThinkingCapability.ModelControlled
            jinjaTemplate != null && !templateHasThinkingToggle && !templateHasThinkingTags -> ThinkingCapability.Unsupported
            else -> ThinkingCapability.Unknown
        }

        return DecodedLlmMetadata(
            maxNumTokens = maxNumTokens,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            thinkingCapability = thinkingCapability,
            jinjaTemplate = jinjaTemplate
        )
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt()
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) {
                return Pair(result, pos)
            }
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    private fun skipWire(bytes: ByteArray, pos: Int, wireType: Int, limit: Int): Int {
        var p = pos
        when (wireType) {
            0 -> {
                while (p < limit && (bytes[p++].toInt() and 0x80) != 0) {}
            }
            1 -> p = minOf(p + 8, limit)
            2 -> {
                val lenRes = readVarint(bytes, p) ?: return limit
                p = minOf(lenRes.second + lenRes.first.toInt(), limit)
            }
            5 -> p = minOf(p + 4, limit)
            else -> p = limit
        }
        return p
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

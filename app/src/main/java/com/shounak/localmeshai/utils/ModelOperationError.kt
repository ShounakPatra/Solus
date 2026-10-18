package com.shounak.localmeshai.utils

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Stage of the model import, registration, or removal operation where the error occurred.
 */
enum class ModelOperationStage {
    SelectionValidation,
    Inspection,
    Registration,
    Removal
}

/**
 * Structured taxonomy of model operation failures.
 */
enum class ModelErrorReason {
    /** XML, image, PDF, or another non-model file was selected. */
    NonModelFile,

    /** Empty or unreadable GGUF header (incomplete, 0 bytes, or corrupted magic). */
    EmptyOrDamagedHeader,

    /** Unsupported architecture, quantization, or runtime format. */
    UnsupportedFormat,

    /** .task or .litertlm file selected for phone storage import. */
    UnsupportedStorageFormat,

    /** Split model missing one or more shards. */
    MissingShards,
    /** Multiple distinct models selected together. */
    UnrelatedModelsSelected,

    /** Companion projector selected without its main model file. */
    ProjectorWithoutMain,

    /** Storage provider denied permission, file moved, or unseekable pipe. */
    StorageAccessDenied,

    /** Failure persisting custom model definition. */
    RegistrationFailed,

    /** Failure shutting down runtime, unlinking, or cleaning store entry. */
    RemovalFailed,

    /** Fallback for unanticipated failures. */
    Unknown
}

/**
 * Structured error representing an actionable model operation failure.
 */
data class ModelOperationError(
    val reason: ModelErrorReason,
    val stage: ModelOperationStage,
    val affectedFilenames: List<String> = emptyList(),
    val foundCount: Int? = null,
    val requiredCount: Int? = null,
    val missingParts: List<Int> = emptyList(),
    val architectureOrFormat: String? = null,
    val detectedFileType: String? = null,
    val diagnosticDetails: String? = null
) : Exception(diagnosticDetails ?: reason.name) {

    /**
     * Clear, actionable heading for this error condition.
     */
    val userTitle: String
        get() = when (reason) {
            ModelErrorReason.NonModelFile -> "Choose a supported model"
            ModelErrorReason.EmptyOrDamagedHeader -> "This model file can’t be read"
            ModelErrorReason.UnsupportedFormat -> "This model isn’t supported yet"
            ModelErrorReason.UnsupportedStorageFormat -> "Unsupported model format"
            ModelErrorReason.MissingShards -> "Some model parts are missing"
            ModelErrorReason.UnrelatedModelsSelected -> "Select one model at a time"
            ModelErrorReason.ProjectorWithoutMain -> "Main model file missing"
            ModelErrorReason.StorageAccessDenied -> "Can’t access this file"
            ModelErrorReason.RegistrationFailed -> "Couldn’t save this model"
            ModelErrorReason.RemovalFailed -> "Couldn’t remove this model"
            ModelErrorReason.Unknown -> "Couldn’t import this model"
        }

    /**
     * Informative user-facing explanation of the problem.
     */
    val userMessage: String
        get() {
            val primaryFile = affectedFilenames.firstOrNull() ?: "Selected file"
            return when (reason) {
                ModelErrorReason.NonModelFile -> {
                    val typeDesc = detectedFileType ?: "a non-model file"
                    "“$primaryFile” is $typeDesc, not a supported model file (.gguf, .task, .litertlm)."
                }
                ModelErrorReason.EmptyOrDamagedHeader -> {
                    "This model file can’t be read — it may be incomplete or damaged."
                }
                ModelErrorReason.UnsupportedFormat -> {
                    val arch = architectureOrFormat?.takeIf { it.isNotBlank() } ?: "unknown"
                    "This model isn’t supported yet — format or architecture “$arch” is not compatible with the runtime."
                }
                ModelErrorReason.UnsupportedStorageFormat -> {
                    diagnosticDetails?.takeIf { it.isNotBlank() }
                        ?: "This model format (${architectureOrFormat ?: "unknown"}) is not supported on this device."
                }
                ModelErrorReason.MissingShards -> {
                    val found = foundCount ?: affectedFilenames.size
                    val total = requiredCount ?: "?"
                    val missing = if (missingParts.isNotEmpty()) {
                        " Missing parts: " + missingParts.map { it + 1 }.joinToString(", ")
                    } else ""
                    "Some model parts are missing — found $found of $total parts.$missing"
                }
                ModelErrorReason.UnrelatedModelsSelected -> {
                    "Select one model at a time — its shards and optional projector may be selected together."
                }
                ModelErrorReason.ProjectorWithoutMain -> {
                    "Main model file missing — select the model and its companion projector."
                }
                ModelErrorReason.StorageAccessDenied -> {
                    "Can’t access “$primaryFile”. Try selecting it again or save it locally first."
                }
                ModelErrorReason.RegistrationFailed -> {
                    "Couldn’t save this model — registration was not completed."
                }
                ModelErrorReason.RemovalFailed -> {
                    val detail = sanitizedDetails?.takeIf { it.isNotBlank() }
                    if (detail != null) {
                        "Couldn’t remove this model: $detail"
                    } else {
                        "Couldn’t remove this model. Try again."
                    }
                }
                ModelErrorReason.Unknown -> {
                    "Couldn’t import this model. Try selecting the files again."
                }
            }
        }

    /**
     * Primary action prompt for this error.
     */
    val actionText: String
        get() = when (reason) {
            ModelErrorReason.NonModelFile -> "Choose model files"
            ModelErrorReason.EmptyOrDamagedHeader -> "Choose another file"
            ModelErrorReason.UnsupportedFormat -> "Choose another model"
            ModelErrorReason.UnsupportedStorageFormat -> "Choose model files"
            ModelErrorReason.MissingShards -> "Select all model parts"
            ModelErrorReason.UnrelatedModelsSelected -> "Choose model files"
            ModelErrorReason.ProjectorWithoutMain -> "Choose model files"
            ModelErrorReason.StorageAccessDenied -> "Choose files again"
            ModelErrorReason.RegistrationFailed -> "Choose files again"
            ModelErrorReason.RemovalFailed -> "Retry"
            ModelErrorReason.Unknown -> "Choose model files"
        }

    /**
     * Diagnostic details with stack traces and sensitive tokens scrubbed.
     */
    val sanitizedDetails: String?
        get() = sanitizeDiagnosticDetails(diagnosticDetails)

    companion object {
        /**
         * Scrubs access tokens, authorization headers, and JVM stack traces from diagnostics.
         */
        fun sanitizeDiagnosticDetails(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            var cleaned = raw
                .replace(Regex("""(?i)(Bearer\s+[A-Za-z0-9\-._~+/]+=*)"""), "[REDACTED_AUTH]")
                .replace(Regex("""\bhf_[A-Za-z0-9]{20,}\b"""), "[REDACTED_HF_TOKEN]")
                .replace(Regex("""(?i)(password|token|secret)\s*[:=]\s*[^\s,;]+"""), "$1=[REDACTED]")
                .lines()
                .filter { line ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("at ") &&
                        !trimmed.startsWith("Caused by:") &&
                        !trimmed.contains(".kt:") &&
                        !trimmed.contains(".java:")
                }
                .joinToString("\n")
                .trim()
            return cleaned.ifBlank { null }
        }
    }
}

/**
 * Lightweight header sniffer to identify GGUF vs non-model files (XML, images, PDF, etc.)
 * without invoking native model loading.
 */
/**
 * Lightweight header sniffer to identify GGUF, MediaPipe Task, and LiteRT-LM models vs non-model files (XML, images, PDF, etc.)
 * without invoking full native model loading.
 */
object FileHeaderSniffer {
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
    private val PDF_MAGIC = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte())
    private val ZIP_MAGIC = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())
    private val ZIP_EMPTY_MAGIC = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x05.toByte(), 0x06.toByte())
    private val TFL3_MAGIC = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

    private val TASK_MARKERS = listOf(
        "TFL3".toByteArray(StandardCharsets.US_ASCII),
        "TF_LITE".toByteArray(StandardCharsets.US_ASCII),
        "TFLITE".toByteArray(StandardCharsets.US_ASCII),
        "mediapipe".toByteArray(StandardCharsets.US_ASCII),
        "MediaPipe".toByteArray(StandardCharsets.US_ASCII)
    )

    sealed class SniffResult {
        object ValidGguf : SniffResult()
        object ValidMediaPipeTask : SniffResult()
        object ValidLiteRtLm : SniffResult()
        data class NonModel(val fileTypeDescription: String, val detail: String) : SniffResult()
        data class EmptyOrDamaged(val detail: String) : SniffResult()
        data class UnsupportedStorageFormat(val detail: String = "This model format is not supported on this device.") : SniffResult()
    }

    fun isZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if ((bytes[0] == ZIP_MAGIC[0] && bytes[1] == ZIP_MAGIC[1] && bytes[2] == ZIP_MAGIC[2] && bytes[3] == ZIP_MAGIC[3]) ||
            (bytes[0] == ZIP_EMPTY_MAGIC[0] && bytes[1] == ZIP_EMPTY_MAGIC[1] && bytes[2] == ZIP_EMPTY_MAGIC[2] && bytes[3] == ZIP_EMPTY_MAGIC[3])
        ) {
            return true
        }
        // Support alignment padding where ZIP signature starts at an aligned offset (e.g. 4, 8, 16, 64)
        val maxOffset = minOf(bytes.size - 4, 4096)
        for (i in 1..maxOffset) {
            if (bytes[i] == ZIP_MAGIC[0] && bytes[i + 1] == ZIP_MAGIC[1] && bytes[i + 2] == ZIP_MAGIC[2] && bytes[i + 3] == ZIP_MAGIC[3]) {
                return true
            }
        }
        return false
    }

    fun hasModelStructure(bytes: ByteArray): Boolean {
        return TASK_MARKERS.any { marker -> bytesIndexOf(bytes, marker) >= 0 }
    }

    private fun bytesIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val maxStart = haystack.size - needle.size
        for (i in 0..maxStart) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    fun sniffHeader(bytes: ByteArray, sizeBytes: Long, fileName: String): SniffResult {
        if (sizeBytes == 0L || bytes.isEmpty()) {
            return SniffResult.EmptyOrDamaged("File is empty (0 bytes)")
        }

        if (bytes.size >= 4 &&
            bytes[0] == GGUF_MAGIC[0] &&
            bytes[1] == GGUF_MAGIC[1] &&
            bytes[2] == GGUF_MAGIC[2] &&
            bytes[3] == GGUF_MAGIC[3]
        ) {
            return SniffResult.ValidGguf
        }

        if (bytes.size >= 8 &&
            bytes[0] == 'L'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'T'.code.toByte() &&
            bytes[3] == 'E'.code.toByte() &&
            bytes[4] == 'R'.code.toByte() &&
            bytes[5] == 'T'.code.toByte() &&
            bytes[6] == 'L'.code.toByte() &&
            bytes[7] == 'M'.code.toByte()
        ) {
            return SniffResult.ValidLiteRtLm
        }

        if (bytes.size >= 8 &&
            bytes[4] == 'T'.code.toByte() &&
            bytes[5] == 'F'.code.toByte() &&
            bytes[6] == 'L'.code.toByte() &&
            bytes[7] == '3'.code.toByte()
        ) {
            return SniffResult.NonModel("a raw TensorFlow Lite model", "LiteRT-LM and MediaPipe runtimes require a packaged LITERTLM or .task bundle, not a raw .tflite flatbuffer")
        }

        if (bytes.size < 4) {
            return SniffResult.EmptyOrDamaged("Header buffer too small (${bytes.size} bytes)")
        }

        val lowerName = fileName.lowercase(Locale.ROOT)

        // Check for PDF
        if (bytes[0] == PDF_MAGIC[0] && bytes[1] == PDF_MAGIC[1] && bytes[2] == PDF_MAGIC[2] && bytes[3] == PDF_MAGIC[3]) {
            return SniffResult.NonModel("a PDF document", "File starts with %PDF header")
        }

        // Check for PNG
        if (bytes[0] == PNG_MAGIC[0] && bytes[1] == PNG_MAGIC[1] && bytes[2] == PNG_MAGIC[2] && bytes[3] == PNG_MAGIC[3]) {
            return SniffResult.NonModel("a PNG image", "File starts with PNG signature")
        }

        // Check for JPEG
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return SniffResult.NonModel("a JPEG image", "File starts with JPEG signature")
        }

        // Check for GIF
        if (bytes.size >= 6) {
            val gifHeader = String(bytes.copyOfRange(0, 6), StandardCharsets.US_ASCII)
            if (gifHeader == "GIF87a" || gifHeader == "GIF89a") {
                return SniffResult.NonModel("a GIF image", "File starts with GIF header")
            }
        }

        // Check for XML or text-based files
        val sampleOffset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val sampleText = runCatching {
            String(bytes, sampleOffset, (bytes.size - sampleOffset).coerceAtMost(1024), StandardCharsets.UTF_8)
        }.getOrNull()?.trim() ?: ""

        val lowerSample = sampleText.lowercase(Locale.ROOT)
        val isXml = lowerSample.startsWith("<?xml") ||
            lowerSample.startsWith("<!doctype") ||
            (lowerSample.startsWith("<") && (
                lowerSample.contains("preferences") ||
                lowerSample.contains("map>") ||
                lowerSample.contains("html") ||
                lowerSample.contains("resources") ||
                lowerSample.contains("manifest") ||
                lowerSample.contains("xml") ||
                lowerSample.contains("xmlns")
            )) ||
            (lowerName.endsWith(".xml") && lowerSample.startsWith("<"))

        if (isXml) {
            return SniffResult.NonModel("an XML file", "File contains XML markup")
        }

        if (lowerSample.startsWith("{") && (lowerSample.contains("\":") || lowerSample.endsWith("}"))) {
            return SniffResult.NonModel("a JSON file", "File contains JSON data")
        }

        // Check MediaPipe .task files
        if (lowerName.endsWith(".task")) {
            val isZipArchive = isZip(bytes)
            if (!isZipArchive) {
                return SniffResult.EmptyOrDamaged("File does not start with valid MediaPipe task container header (expected ZIP header)")
            }
            val sampleAscii = String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.ISO_8859_1).lowercase(Locale.ROOT)
            val isCorruptNonModelZip = sampleAscii.contains("word/") ||
                sampleAscii.contains("classes.dex") ||
                (sampleAscii.contains("androidmanifest.xml") && !sampleAscii.contains(".tflite") && !sampleAscii.contains("tf_lite"))
            if (isCorruptNonModelZip) {
                return SniffResult.NonModel("a ZIP/APK archive", "ZIP archive lacks required MediaPipe task bundle structure")
            }
            return SniffResult.ValidMediaPipeTask
        }

        // Check LiteRT-LM .litertlm files
        if (lowerName.endsWith(".litertlm")) {
            val hasLiteRtLmMagic = bytes.size >= 8 &&
                bytes[0] == 'L'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'T'.code.toByte() &&
                bytes[3] == 'E'.code.toByte() &&
                bytes[4] == 'R'.code.toByte() &&
                bytes[5] == 'T'.code.toByte() &&
                bytes[6] == 'L'.code.toByte() &&
                bytes[7] == 'M'.code.toByte()

            val hasTfl3Identifier = bytes.size >= 8 &&
                bytes[4] == 'T'.code.toByte() &&
                bytes[5] == 'F'.code.toByte() &&
                bytes[6] == 'L'.code.toByte() &&
                bytes[7] == '3'.code.toByte()

            if (isZip(bytes)) {
                return SniffResult.NonModel("a ZIP/APK archive", "LiteRT-LM models use the LITERTLM container format, not ZIP archives")
            }

            if (hasLiteRtLmMagic) {
                return SniffResult.ValidLiteRtLm
            }
            if (hasTfl3Identifier) {
                return SniffResult.NonModel("a raw TensorFlow Lite model", "LiteRT-LM runtime requires a packaged LITERTLM bundle, not a raw .tflite flatbuffer")
            }
            return SniffResult.EmptyOrDamaged("File does not start with valid LiteRT-LM container header (expected LITERTLM magic)")
        }

        // Check for generic ZIP/APK that isn't a recognized model
        if (isZip(bytes)) {
            return SniffResult.NonModel("a ZIP/APK archive", "File starts with PK zip header")
        }

        when {
            lowerName.endsWith(".xml") -> return SniffResult.NonModel("an XML file", "File has .xml extension")
            lowerName.endsWith(".pdf") -> return SniffResult.NonModel("a PDF document", "File has .pdf extension")
            lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ->
                return SniffResult.NonModel("an image file", "File is an image")
            lowerName.endsWith(".zip") || lowerName.endsWith(".tar") || lowerName.endsWith(".gz") ->
                return SniffResult.NonModel("an archive file", "File is an archive")
            lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".json") ->
                return SniffResult.NonModel("a text document", "File is a text document")
        }

        // Distinguish an unsupported file type from a damaged file named .gguf
        if (lowerName.endsWith(".gguf")) {
            val printableCount = bytes.take(256).count { it in 32..126 || it == 10.toByte() || it == 13.toByte() || it == 9.toByte() }
            if (printableCount > 200 && sampleText.startsWith("<")) {
                return SniffResult.NonModel("an XML file", "Renamed XML file ending in .gguf")
            } else if (printableCount > 220) {
                return SniffResult.NonModel("a text file", "Renamed text file ending in .gguf")
            }
            return SniffResult.EmptyOrDamaged("File does not start with GGUF magic bytes (found: 0x${bytes.take(4).joinToString("") { "%02x".format(it) }})")
        }

        return SniffResult.NonModel("an unsupported file type", "File does not match supported model format (.gguf, .task, .litertlm)")
    }
}

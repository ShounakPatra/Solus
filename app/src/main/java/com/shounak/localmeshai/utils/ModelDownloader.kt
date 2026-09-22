package com.shounak.localmeshai.utils

import android.content.Context
import android.os.Environment
import com.shounak.localmeshai.models.ModelPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long
)

data class DownloadResult(
    val localPath: String,
    val bytesDownloaded: Long
)

class ModelDownloader(private val context: Context) {
    private val modelRoot: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads"))
            .resolve("models")
            .apply { mkdirs() }

    fun getTargetFile(modelId: String, fileName: String, packageType: ModelPackage): File {
        return when (packageType) {
            ModelPackage.SingleFile -> modelRoot.resolve(fileName)
            ModelPackage.ZipDirectory -> modelRoot.resolve(modelId)
        }
    }

    fun getPartialDownloadBytes(modelId: String): Long {
        return modelRoot.resolve("$modelId.download")
            .takeIf { it.exists() && it.isFile }
            ?.length()
            ?: 0L
    }

    fun deleteDownload(modelId: String, fileName: String, packageType: ModelPackage) {
        val target = getTargetFile(modelId, fileName, packageType)
        val tempFile = modelRoot.resolve("$modelId.download")
        runCatching {
            if (target.isDirectory) {
                target.deleteRecursively()
            } else {
                target.delete()
            }
        }
        runCatching { tempFile.delete() }
    }

    suspend fun downloadModel(
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        bearerToken: String?,
        expectedSha256: String? = null,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var retryCount = 0
        while (true) {
            try {
                return@withContext downloadModelOnce(
                    url = url,
                    modelId = modelId,
                    fileName = fileName,
                    packageType = packageType,
                    bearerToken = bearerToken,
                    expectedSha256 = expectedSha256,
                    onProgress = onProgress
                )
            } catch (exception: IOException) {
                if (!exception.isTransientDownloadFailure() || retryCount >= MAX_DOWNLOAD_RETRIES) {
                    throw exception
                }
                retryCount++
                val tempFile = modelRoot.resolve("$modelId.download")
                val downloadedBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
                onProgress(
                    DownloadProgress(
                        progress = 0f,
                        downloadedBytes = downloadedBytes,
                        totalBytes = -1L,
                        bytesPerSecond = 0L
                    )
                )
                delay(RETRY_BASE_DELAY_MS * retryCount)
            }
        }
        throw IOException("Download retry loop exited unexpectedly.")
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private suspend fun downloadModelOnce(
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        bearerToken: String?,
        expectedSha256: String? = null,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IOException("Insecure HTTP connections are blocked for model downloads. URL must use HTTPS.")
        }

        val target = getTargetFile(modelId, fileName, packageType)
        val tempFile = modelRoot.resolve("$modelId.download")
        val existingBytes = tempFile.takeIf { it.exists() }?.length()?.takeIf { it > 0L } ?: 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "Solus/1.0 Android")

        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val uri = runCatching { URI(url) }.getOrNull()
        if (!bearerToken.isNullOrBlank() && uri?.host?.endsWith("huggingface.co") == true) {
            requestBuilder.header("Authorization", "Bearer ${bearerToken.trim()}")
        }

        val call = httpClient.newCall(requestBuilder.build())
        val response = call.execute()

        val responseCode = response.code
        if (!response.isSuccessful) {
            val serverMessage = runCatching {
                response.body.string().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MAX_ERROR_MESSAGE_CHARS)
            }.getOrDefault("")
            response.close()
            throw IOException(downloadErrorMessage(url, responseCode, serverMessage))
        }

        val body = response.body

        val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
        if (contentType.contains("text/html") || contentType.contains("application/json")) {
            val serverMessage = runCatching {
                body.string().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MAX_ERROR_MESSAGE_CHARS)
            }.getOrDefault("")
            response.close()
            throw IOException("Model host returned a web page instead of a model file. $serverMessage")
        }

        val append = existingBytes > 0L && responseCode == 206
        if (existingBytes > 0L && !append) {
            tempFile.delete()
        }

        val contentLength = body.contentLength()
        val totalBytes = if (append && contentLength > 0L) {
            existingBytes + contentLength
        } else {
            contentLength
        }
        var downloaded = if (append) existingBytes else 0L
        val startedAt = System.currentTimeMillis()
        var lastProgressAt = 0L
        var lastSpeedAt = startedAt
        var lastSpeedBytes = downloaded
        var currentSpeed = 0L

        suspend fun emitProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastProgressAt < PROGRESS_EMIT_INTERVAL_MS) return

            val speedElapsedMs = now - lastSpeedAt
            if (speedElapsedMs >= SPEED_SAMPLE_INTERVAL_MS) {
                val speedBytes = (downloaded - lastSpeedBytes).coerceAtLeast(0L)
                currentSpeed = speedBytes * 1000L / speedElapsedMs.coerceAtLeast(1L)
                lastSpeedAt = now
                lastSpeedBytes = downloaded
            }

            val progress = if (totalBytes > 0L) {
                (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            lastProgressAt = now
            onProgress(DownloadProgress(progress, downloaded, totalBytes, currentSpeed))
        }

        emitProgress(force = true)

        try {
            response.use { resp ->
                resp.body.byteStream().use { input ->
                    FileOutputStream(tempFile, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            emitProgress()
                        }
                    }
                }
            }
        } finally {
            call.cancel()
        }
        emitProgress(force = true)

        if (downloaded == 0L) {
            tempFile.delete()
            throw IOException("Downloaded file is empty.")
        }
        if (downloaded < MIN_VALID_MODEL_BYTES) {
            tempFile.delete()
            throw IOException("Downloaded file is too small to be a valid model.")
        }
        if (
            packageType == ModelPackage.SingleFile &&
            fileName.endsWith(".task", ignoreCase = true) &&
            !isLikelyTaskBundle(tempFile)
        ) {
            val message = invalidTaskFileMessage(tempFile)
            tempFile.delete()
            throw IOException(message)
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actualSha256 = computeSha256(tempFile)
            if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                tempFile.delete()
                throw IOException("SHA-256 checksum verification failed. Expected: ${expectedSha256.trim()}, Actual: $actualSha256")
            }
        }

        val localPath = when (packageType) {
            ModelPackage.SingleFile -> {
                target.delete()
                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                target.absolutePath
            }

            ModelPackage.ZipDirectory -> {
                target.deleteRecursively()
                target.mkdirs()
                unzipModel(tempFile, target)
                tempFile.delete()
                val extracted = resolveExtractedModelDirectory(target)
                extracted.setLastModified(System.currentTimeMillis())
                extracted.absolutePath
            }
        }

        return DownloadResult(localPath = localPath, bytesDownloaded = downloaded)
    }

    private fun unzipModel(zipFile: File, targetDirectory: File) {
        val canonicalTarget = targetDirectory.canonicalFile
        var totalUncompressedBytes = 0L
        val maxAllowedBytes = 16L * 1024L * 1024L * 1024L // 16 GB max total model size guard

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                val safeName = entry.name.replace('\\', '/')
                val outputFile = File(canonicalTarget, safeName).canonicalFile
                if (!outputFile.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw IOException("Blocked unsafe ZIP entry path traversal: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read == -1) break
                            totalUncompressedBytes += read
                            if (totalUncompressedBytes > maxAllowedBytes) {
                                throw IOException("ZIP archive uncompressed size exceeds maximum safety limit (16 GB). Download aborted.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun resolveExtractedModelDirectory(root: File): File {
        val canonicalRoot = root.canonicalFile
        val children = root.listFiles()
            ?.filterNot { it.name == "__MACOSX" || it.name == ".DS_Store" }
            .orEmpty()
        val directories = children.filter { it.isDirectory }
        val files = children.filter { it.isFile }

        val resolved = if (files.isEmpty() && directories.size == 1) directories.first() else root
        val canonicalResolved = resolved.canonicalFile
        if (!canonicalResolved.path.startsWith(canonicalRoot.path)) {
            throw IOException("Extracted model directory path traversal detected.")
        }
        return canonicalResolved
    }

    private fun downloadErrorMessage(url: String, responseCode: Int, serverMessage: String): String {
        val suffix = serverMessage.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return when {
            responseCode == 401 || responseCode == 403 -> {
                if (url.contains("huggingface.co")) {
                    val pageInstruction = huggingFaceModelPageUrl(url)
                        ?.let { " Visit $it to ask for access." }
                        .orEmpty()
                    "Hugging Face denied access. Open Settings (⚙️) → Hugging Face Access Token to enter your read token.$pageInstruction$suffix"
                } else {
                    "Server denied the model download (HTTP $responseCode).$suffix"
                }
            }
            responseCode == 404 -> "Model file was not found at the configured URL.$suffix"
            else -> "Model download failed with HTTP $responseCode.$suffix"
        }
    }

    private fun huggingFaceModelPageUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("huggingface.co", ignoreCase = true)) return null
        val segments = uri.path
            .split('/')
            .filter { it.isNotBlank() }
        if (segments.size < 2) return null
        return "https://huggingface.co/${segments[0]}/${segments[1]}"
    }

    private fun IOException.isTransientDownloadFailure(): Boolean {
        val message = localizedMessage.orEmpty().lowercase(Locale.US)
        return message.contains("software caused connection abort") ||
            message.contains("connection reset") ||
            message.contains("connection aborted") ||
            message.contains("broken pipe") ||
            message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("unexpected end of stream") ||
            message.contains("connection closed")
    }

    private fun invalidTaskFileMessage(file: File): String {
        val preview = runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(96)
                val read = input.read(bytes)
                bytes.take(read.coerceAtLeast(0)).joinToString(separator = "") { byte ->
                    val charCode = byte.toInt() and 0xFF
                    if (charCode in 32..126) charCode.toChar().toString() else "."
                }.trim('.')
            }
        }.getOrDefault("")
        val detail = preview.takeIf { it.isNotBlank() }?.let { " First bytes: $it" }.orEmpty()
        return "Downloaded .task does not look like a MediaPipe or LiteRT task bundle. Use a verified Android .task URL and retry.$detail"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 6
        private const val MIN_VALID_MODEL_BYTES = 1_000_000L
        private const val MAX_ERROR_MESSAGE_CHARS = 600
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
        private const val SPEED_SAMPLE_INTERVAL_MS = 500L
        private const val MAX_DOWNLOAD_RETRIES = 5
        private const val RETRY_BASE_DELAY_MS = 1_500L

        fun computeSha256(file: File): String {
            if (!file.exists() || !file.isFile) {
                throw IOException("Cannot compute checksum: file does not exist or is not a regular file")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = digest.digest()
            val sb = java.lang.StringBuilder(hashBytes.size * 2)
            for (b in hashBytes) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            return sb.toString()
        }

        fun verifyFileSha256(file: File, expectedSha256: String): Boolean {
            if (expectedSha256.isBlank()) return true
            return try {
                val actual = computeSha256(file)
                actual.equals(expectedSha256.trim(), ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

        fun isLikelyTaskBundle(file: File): Boolean {
            if (!file.isFile || file.length() < 4L) return false
            return isZipArchive(file) || containsKnownTaskMarker(file)
        }

        private fun isZipArchive(file: File): Boolean {
            return runCatching {
                ZipFile(file).use { zip ->
                    zip.entries().hasMoreElements()
                }
            }.getOrDefault(false)
        }

        private fun containsKnownTaskMarker(file: File): Boolean {
            return runCatching {
                file.inputStream().use { input ->
                    val bytesToRead = minOf(file.length(), TASK_MARKER_SCAN_BYTES.toLong()).toInt()
                    val bytes = ByteArray(bytesToRead)
                    val read = input.read(bytes)
                    read > 0 && TASK_MARKERS.any { marker -> bytes.indexOf(marker, read) >= 0 }
                }
            }.getOrDefault(false)
        }

        private fun ByteArray.indexOf(needle: ByteArray, length: Int): Int {
            if (needle.isEmpty() || length < needle.size) return -1
            val maxStart = length - needle.size
            for (start in 0..maxStart) {
                var matches = true
                for (index in needle.indices) {
                    if (this[start + index] != needle[index]) {
                        matches = false
                        break
                    }
                }
                if (matches) return start
            }
            return -1
        }

        fun isLikelyGgufFile(file: File): Boolean {
            if (!file.isFile || file.length() < 4L) return false
            return runCatching {
                file.inputStream().use { input ->
                    val bytes = ByteArray(4)
                    val read = input.read(bytes)
                    read == 4 && (bytes[0] == 0x47.toByte() && bytes[1] == 0x47.toByte() && bytes[2] == 0x55.toByte() && bytes[3] == 0x46.toByte())
                }
            }.getOrDefault(false)
        }

        private const val TASK_MARKER_SCAN_BYTES = 16 * 1024
        private val TASK_MARKERS = listOf(
            "TFL3".toByteArray(Charsets.US_ASCII),
            "TF_LITE".toByteArray(Charsets.US_ASCII),
            "TFLITE".toByteArray(Charsets.US_ASCII),
            "mediapipe".toByteArray(Charsets.US_ASCII),
            "MediaPipe".toByteArray(Charsets.US_ASCII),
            "GGUF".toByteArray(Charsets.US_ASCII)
        )
    }
}

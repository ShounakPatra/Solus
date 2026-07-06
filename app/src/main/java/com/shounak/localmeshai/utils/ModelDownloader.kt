package com.shounak.localmeshai.utils

import android.content.Context
import android.os.Environment
import com.shounak.localmeshai.models.ModelPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
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

    private suspend fun downloadModelOnce(
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        bearerToken: String?,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        val target = getTargetFile(modelId, fileName, packageType)
        val tempFile = modelRoot.resolve("$modelId.download")
        val existingBytes = tempFile.takeIf { it.exists() }?.length()?.takeIf { it > 0L } ?: 0L
        val connection = openConnection(url, bearerToken, rangeStart = existingBytes.takeIf { it > 0L })
        val responseCode = connection.responseCode

        if (responseCode !in 200..299) {
            val serverMessage = readServerMessage(connection)
            connection.disconnect()
            throw IOException(downloadErrorMessage(url, responseCode, serverMessage))
        }

        val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
        if (contentType.contains("text/html") || contentType.contains("application/json")) {
            val serverMessage = readServerMessage(connection)
            connection.disconnect()
            throw IOException("Model host returned a web page instead of a model file. $serverMessage")
        }

        val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
        if (existingBytes > 0L && !append) {
            tempFile.delete()
        }

        val totalBytes = if (append && connection.contentLengthLong > 0L) {
            existingBytes + connection.contentLengthLong
        } else {
            connection.contentLengthLong
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
            connection.inputStream.use { input ->
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
        } finally {
            connection.disconnect()
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

        val localPath = when (packageType) {
            ModelPackage.SingleFile -> {
                target.delete()
                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }
                target.absolutePath
            }

            ModelPackage.ZipDirectory -> {
                target.deleteRecursively()
                target.mkdirs()
                unzipModel(tempFile, target)
                tempFile.delete()
                resolveExtractedModelDirectory(target).absolutePath
            }
        }

        return DownloadResult(localPath = localPath, bytesDownloaded = downloaded)
    }

    private fun openConnection(
        downloadUrl: String,
        bearerToken: String?,
        redirects: Int = 0,
        rangeStart: Long? = null
    ): HttpURLConnection {
        if (redirects > MAX_REDIRECTS) {
            throw IOException("Too many redirects while downloading model.")
        }

        val url = URL(downloadUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Solus/1.0 Android")
            if (rangeStart != null && rangeStart > 0L) {
                setRequestProperty("Range", "bytes=$rangeStart-")
            }
            if (!bearerToken.isNullOrBlank() && url.host.endsWith("huggingface.co")) {
                setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }
        }

        val responseCode = connection.responseCode
        if (responseCode in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: throw IOException("Download redirect did not include a destination.")
            connection.disconnect()
            val redirected = URL(url, location).toString()
            return openConnection(redirected, bearerToken, redirects + 1, rangeStart)
        }

        return connection
    }

    private fun unzipModel(zipFile: File, targetDirectory: File) {
        val canonicalTarget = targetDirectory.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val safeName = entry.name.replace('\\', '/')
                val outputFile = File(canonicalTarget, safeName).canonicalFile
                if (!outputFile.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw IOException("Blocked unsafe ZIP entry: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun resolveExtractedModelDirectory(root: File): File {
        val children = root.listFiles()
            ?.filterNot { it.name == "__MACOSX" || it.name == ".DS_Store" }
            .orEmpty()
        val directories = children.filter { it.isDirectory }
        val files = children.filter { it.isFile }

        return if (files.isEmpty() && directories.size == 1) directories.first() else root
    }

    private fun readServerMessage(connection: HttpURLConnection): String {
        return runCatching {
            val stream = connection.errorStream ?: connection.inputStream
            stream.bufferedReader().use { reader ->
                reader.readText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            }.take(MAX_ERROR_MESSAGE_CHARS)
        }.getOrDefault("")
    }

    private fun downloadErrorMessage(url: String, responseCode: Int, serverMessage: String): String {
        val suffix = serverMessage.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return when {
            responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN -> {
                if (url.contains("huggingface.co")) {
                    val pageInstruction = huggingFaceModelPageUrl(url)
                        ?.let { " Visit $it to ask for access." }
                        .orEmpty()
                    "Hugging Face denied access. Accept the model license and paste a read token before downloading.$pageInstruction$suffix"
                } else {
                    "Server denied the model download (HTTP $responseCode).$suffix"
                }
            }
            responseCode == HttpURLConnection.HTTP_NOT_FOUND -> "Model file was not found at the configured URL.$suffix"
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

        private const val TASK_MARKER_SCAN_BYTES = 16 * 1024
        private val TASK_MARKERS = listOf(
            "TFL3".toByteArray(Charsets.US_ASCII),
            "TF_LITE".toByteArray(Charsets.US_ASCII),
            "TFLITE".toByteArray(Charsets.US_ASCII),
            "mediapipe".toByteArray(Charsets.US_ASCII),
            "MediaPipe".toByteArray(Charsets.US_ASCII)
        )
    }
}

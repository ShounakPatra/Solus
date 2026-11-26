package com.shounak.localmeshai.ai.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class VoiceRemoteArtifact(
    val id: String,
    val relativeUrl: String,
    val localFileName: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String
)

data class VoiceModelSpec(
    val id: String,
    val displayName: String,
    val repository: String,
    val revision: String,
    val description: String,
    val artifacts: List<VoiceRemoteArtifact>
) {
    val totalSizeBytes: Long
        get() = artifacts.sumOf { it.expectedSizeBytes }

    fun artifactUrls(artifact: VoiceRemoteArtifact): List<String> = listOf(
        "https://huggingface.co/$repository/resolve/$revision/${artifact.relativeUrl}?download=true"
    )
}

data class VoiceAssetsDownloadStatus(
    val isDownloading: Boolean = false,
    val currentModelName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Registry and resumable SHA-256 verified downloader for offline voice assets
 * (Silero VAD ONNX + Whisper Tiny EN quantized ONNX) stored strictly in app-private files.
 */
object VoiceModelCatalog {
    const val SILERO_VAD_ID = "silero-vad-onnx"
    const val WHISPER_TINY_EN_ID = "whisper-tiny-en-onnx"

    val SILERO_VAD = VoiceModelSpec(
        id = SILERO_VAD_ID,
        displayName = "Silero VAD ONNX",
        repository = "DictionLabs/silero-vad-onnx",
        revision = "main",
        description = "Lightweight MIT-licensed neural voice activity detector (~2.3 MB) for hands-free speech boundary & barge-in detection.",
        artifacts = listOf(
            VoiceRemoteArtifact(
                id = "silero_vad_onnx",
                relativeUrl = "silero_vad.onnx",
                localFileName = "silero_vad.onnx",
                expectedSizeBytes = 2_327_524L,
                expectedSha256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
            )
        )
    )

    val WHISPER_TINY_EN = VoiceModelSpec(
        id = WHISPER_TINY_EN_ID,
        displayName = "Whisper Tiny EN (INT8 ONNX)",
        repository = "Xenova/whisper-tiny.en",
        revision = "main",
        description = "Compact 43 MB quantized Whisper speech-to-text ONNX pipeline (encoder, merged decoder, tokenizer, config).",
        artifacts = listOf(
            VoiceRemoteArtifact(
                id = "whisper_encoder_int8",
                relativeUrl = "onnx/encoder_model_quantized.onnx",
                localFileName = "encoder_model_quantized.onnx",
                expectedSizeBytes = 10_124_913L,
                expectedSha256 = "8cc3c6f8563d1b3fbd2c5af9f64c2bed8b020bc593c402d1ef53b9f08fbf1b90"
            ),
            VoiceRemoteArtifact(
                id = "whisper_decoder_merged_int8",
                relativeUrl = "onnx/decoder_model_merged_quantized.onnx",
                localFileName = "decoder_model_merged_quantized.onnx",
                expectedSizeBytes = 30_727_382L,
                expectedSha256 = "dbb2e063b7fbc41d9803b9698f93ecb035c50cbb3fb87b56cb131e4a5eb99059"
            ),
            VoiceRemoteArtifact(
                id = "whisper_tokenizer",
                relativeUrl = "tokenizer.json",
                localFileName = "tokenizer.json",
                expectedSizeBytes = 2_128_494L,
                expectedSha256 = "c6ee8f089220a5b1188f6426456772572671c6141ae007eecb83c6a8349f5deb"
            ),
            VoiceRemoteArtifact(
                id = "whisper_config",
                relativeUrl = "config.json",
                localFileName = "config.json",
                expectedSizeBytes = 2_202L,
                expectedSha256 = "37a1073be00d19118c06557896c7c148598f4d8277edc0f5bc07c9f5554839f1"
            )
        )
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _downloadStatus = MutableStateFlow(VoiceAssetsDownloadStatus())
    val downloadStatus: StateFlow<VoiceAssetsDownloadStatus> = _downloadStatus.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun directory(context: Context, spec: VoiceModelSpec): File =
        File(context.filesDir, "voice/${spec.id}")

    fun sileroVadFile(context: Context): File =
        File(directory(context, SILERO_VAD), "silero_vad.onnx")

    fun whisperDirectory(context: Context): File =
        directory(context, WHISPER_TINY_EN)

    fun isInstalled(context: Context, spec: VoiceModelSpec): Boolean =
        isInstalled(directory(context, spec), spec)

    internal fun isInstalled(dir: File, spec: VoiceModelSpec): Boolean =
        spec.artifacts.all { art ->
            val f = File(dir, art.localFileName)
            f.isFile && f.length() == art.expectedSizeBytes
        }

    fun partialDownloadedBytes(context: Context, spec: VoiceModelSpec): Long {
        val dir = directory(context, spec)
        return spec.artifacts.sumOf { art ->
            val target = File(dir, art.localFileName)
            val part = File(dir, "${art.localFileName}.part")
            when {
                target.isFile && target.length() == art.expectedSizeBytes -> target.length()
                part.isFile -> part.length().coerceAtMost(art.expectedSizeBytes)
                else -> 0L
            }
        }
    }

    fun delete(context: Context, spec: VoiceModelSpec): Boolean {
        val dir = directory(context, spec)
        val ok = !dir.exists() || dir.deleteRecursively()
        if (ok) _revision.value += 1L
        return ok
    }

    suspend fun downloadMissingAssets(
        context: Context,
        specs: List<VoiceModelSpec>,
        bearerToken: String? = null
    ) = withContext(Dispatchers.IO) {
        val pending = specs.filterNot { isInstalled(context, it) }
        if (pending.isEmpty()) return@withContext
        val grandTotal = pending.sumOf { it.totalSizeBytes }
        var completedAcrossSpecs = 0L
        _downloadStatus.value = VoiceAssetsDownloadStatus(
            isDownloading = true,
            currentModelName = pending.first().displayName,
            bytesDownloaded = 0L,
            totalBytes = grandTotal
        )
        try {
            for (spec in pending) {
                currentCoroutineContext().ensureActive()
                downloadSpec(
                    dir = directory(context, spec),
                    spec = spec,
                    bearerToken = bearerToken
                ) { specDone, _ ->
                    _downloadStatus.value = VoiceAssetsDownloadStatus(
                        isDownloading = true,
                        currentModelName = spec.displayName,
                        bytesDownloaded = completedAcrossSpecs + specDone,
                        totalBytes = grandTotal
                    )
                }
                completedAcrossSpecs += spec.totalSizeBytes
                _revision.value += 1L
            }
            _downloadStatus.value = VoiceAssetsDownloadStatus(
                isDownloading = false,
                currentModelName = "",
                bytesDownloaded = grandTotal,
                totalBytes = grandTotal
            )
        } catch (t: Throwable) {
            _downloadStatus.value = VoiceAssetsDownloadStatus(
                isDownloading = false,
                errorMessage = t.message ?: "Voice asset download failed."
            )
            throw t
        }
    }

    internal suspend fun downloadSpec(
        dir: File,
        spec: VoiceModelSpec,
        bearerToken: String? = null,
        client: OkHttpClient = httpClient,
        urlsFor: (VoiceRemoteArtifact) -> List<String> = spec::artifactUrls,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create directory ${dir.absolutePath}")
        }
        val total = spec.totalSizeBytes
        var done = 0L
        for (artifact in spec.artifacts) {
            currentCoroutineContext().ensureActive()
            val target = File(dir, artifact.localFileName)
            if (target.isFile && target.length() == artifact.expectedSizeBytes && sha256(target) == artifact.expectedSha256) {
                done += artifact.expectedSizeBytes
                onProgress(done, total)
                continue
            }
            val part = File(dir, "${artifact.localFileName}.part")
            val urls = urlsFor(artifact)
            require(urls.isNotEmpty()) { "No URLs for ${artifact.localFileName}" }
            var lastError: IOException? = null
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                try {
                    fetchVerifiedResumable(client, url, artifact, part, bearerToken) { written ->
                        onProgress(done + written, total)
                    }
                    lastError = null
                    break
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (lastError != null) throw lastError
            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace ${target.name}")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            done += artifact.expectedSizeBytes
            onProgress(done, total)
        }
    }

    private suspend fun fetchVerifiedResumable(
        client: OkHttpClient,
        url: String,
        artifact: VoiceRemoteArtifact,
        part: File,
        bearerToken: String?,
        onBytes: (Long) -> Unit
    ) {
        var existingBytes = if (part.isFile) part.length() else 0L
        if (existingBytes > artifact.expectedSizeBytes) {
            part.delete()
            existingBytes = 0L
        }
        val reqBuilder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${bearerToken.trim()}")
        }
        if (existingBytes > 0L && existingBytes < artifact.expectedSizeBytes) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }
        client.newCall(reqBuilder.build()).execute().use { response ->
            val isPartial = response.code == 206
            if (!response.isSuccessful) {
                if (response.code == 416) {
                    part.delete()
                }
                throw IOException("HTTP ${response.code} downloading ${artifact.localFileName}")
            }
            val append = isPartial && existingBytes > 0L
            var written = if (append) existingBytes else 0L
            val body = response.body
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
                out.flush()
            }
        }
        val actualLength = part.length()
        if (actualLength != artifact.expectedSizeBytes) {
            part.delete()
            throw IOException("Size mismatch for ${artifact.localFileName}: expected ${artifact.expectedSizeBytes}, got $actualLength")
        }
        val actualSha = sha256(part)
        if (!actualSha.equals(artifact.expectedSha256, ignoreCase = true)) {
            part.delete()
            throw IOException("SHA-256 verification failed for ${artifact.localFileName}")
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

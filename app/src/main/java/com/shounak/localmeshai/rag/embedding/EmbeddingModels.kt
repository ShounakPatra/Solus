package com.shounak.localmeshai.rag.embedding

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import com.shounak.localmeshai.utils.abortTransportOnCancellation

/**
 * Catalog of optional on-device embedding models for RAG.
 *
 * Files are pinned to an immutable Hugging Face commit and verified by SHA-256,
 * the same policy the main model downloader uses.
 */
object EmbeddingModels {

    data class RemoteFile(
        val remotePath: String,
        val localName: String,
        val sizeBytes: Long,
        val sha256: String,
        /** Asset name of this file on the Solus GitHub release used as a mirror. */
        val mirrorName: String = localName
    )

    data class Spec(
        val id: String,
        val displayName: String,
        val repository: String,
        val revision: String,
        val dimension: Int,
        val queryPrefix: String,
        val calibrationFloor: Float,
        val calibrationSpan: Float,
        val model: RemoteFile,
        val vocab: RemoteFile,
        /** Release-asset folder tried when Hugging Face is unreachable; same SHA-256 checks apply. */
        val mirrorBaseUrl: String? = null
    ) {
        val files: List<RemoteFile> get() = listOf(model, vocab)
        val downloadBytes: Long get() = files.sumOf { it.sizeBytes }
        fun url(file: RemoteFile) = "https://huggingface.co/$repository/resolve/$revision/${file.remotePath}"
        fun urls(file: RemoteFile): List<String> = listOfNotNull(url(file), mirrorBaseUrl?.let { "$it/${file.mirrorName}" })
    }

    /** Built-in lexical hashing engine. Needs no download. */
    const val LEXICAL_ID = "universal-fnv1a-384"

    /**
     * MinishLab potion-base-2M, static Model2Vec ONNX export. 2M parameters, 64 dimensions.
     * Pinned to revision 389b9f64be5aa4ae7a6bc6fe95ef20ce485ae5da.
     * Calibration maps 0.20..0.70 raw cosine onto the app's 0..1 relevance scale.
     */
    val POTION_BASE_2M = Spec(
        id = "potion-base-2M",
        displayName = "potion-base-2M",
        repository = "minishlab/potion-base-2M",
        revision = "389b9f64be5aa4ae7a6bc6fe95ef20ce485ae5da",
        dimension = 64,
        queryPrefix = "",
        calibrationFloor = 0.15f,
        calibrationSpan = 0.50f,
        model = RemoteFile(
            remotePath = "onnx/model.onnx",
            localName = "model.onnx",
            sizeBytes = 7_563_349L,
            sha256 = "92d4a7576de7d39055924b4d9d3979c8c3b2de272010f76f99e2ac14c7b2e5a8"
        ),
        vocab = RemoteFile(
            remotePath = "vocab.txt",
            localName = "vocab.txt",
            sizeBytes = 219_690L,
            sha256 = "1394523a67ddd404a825428018c0582a6998bcfa044ecbcbf1f4d71adb94c61c"
        )
    )

    @Deprecated("Replaced by POTION_BASE_2M", ReplaceWith("POTION_BASE_2M"))
    val BGE_SMALL_EN_V15 = POTION_BASE_2M

    val all: List<Spec> = listOf(POTION_BASE_2M)

    fun find(id: String): Spec? = if (id == "bge-small-en-v1.5-int8") POTION_BASE_2M else all.firstOrNull { it.id == id }

    fun directory(context: Context, spec: Spec): File = File(context.filesDir, "embeddings/${spec.id}")

    fun isInstalled(context: Context, spec: Spec): Boolean = isInstalled(directory(context, spec), spec)

    internal fun isInstalled(dir: File, spec: Spec): Boolean =
        spec.files.all { File(dir, it.localName).let { f -> f.isFile && f.length() == it.sizeBytes } }

    fun createEngine(context: Context, spec: Spec): SemanticEmbeddingEngine = createEngine(directory(context, spec), spec)

    internal fun createEngine(dir: File, spec: Spec): SemanticEmbeddingEngine = OnnxBertEmbeddingEngine(
        modelFile = File(dir, spec.model.localName),
        vocabFile = File(dir, spec.vocab.localName),
        algorithmId = spec.id,
        dimension = spec.dimension,
        queryPrefix = spec.queryPrefix,
        calibrationFloor = spec.calibrationFloor,
        calibrationSpan = spec.calibrationSpan
    )

    fun delete(context: Context, spec: Spec) {
        val dir = directory(context, spec)
        check(!dir.exists() || dir.deleteRecursively()) { "Could not remove ${spec.displayName} files. Retry deleting the search model." }
    }

    /**
     * Downloads every file of [spec] that is missing, verifying size and SHA-256 before an
     * atomic rename. [onProgress] receives (downloaded bytes, total bytes). Cancellable.
     */
    suspend fun download(
        context: Context,
        spec: Spec,
        client: OkHttpClient = defaultClient,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) = download(directory(context, spec), spec, client, onProgress)

    internal suspend fun download(
        dir: File,
        spec: Spec,
        client: OkHttpClient,
        onProgress: (Long, Long) -> Unit,
        urlsFor: (RemoteFile) -> List<String> = spec::urls
    ) = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        val total = spec.downloadBytes
        var done = 0L
        for (file in spec.files) {
            coroutineContext.ensureActive()
            val target = File(dir, file.localName)
            if (target.isFile && target.length() == file.sizeBytes && sha256(target) == file.sha256) {
                done += file.sizeBytes
                onProgress(done, total)
                continue
            }
            val partial = File(dir, file.localName + ".part")
            try {
                var lastError: IOException? = null
                require(urlsFor(file).isNotEmpty()) { "No download sources" }
                for (url in urlsFor(file)) {
                    coroutineContext.ensureActive()
                    try {
                        fetchVerified(client, url, file, partial) { written -> onProgress(done + written, total) }
                        lastError = null
                        break
                    } catch (e: IOException) {
                        partial.delete()
                        lastError = e
                    }
                }
                lastError?.let { throw it }
                try {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                done += file.sizeBytes
                onProgress(done, total)
            } finally {
                partial.delete()
            }
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun fetchVerified(
        client: OkHttpClient,
        url: String,
        file: RemoteFile,
        partial: File,
        onBytes: (Long) -> Unit
    ) {
        partial.delete()
        val call = client.newCall(Request.Builder().url(url).build())
        val cancellation = coroutineContext[Job]?.abortTransportOnCancellation { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}) for ${file.remotePath}")
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > file.sizeBytes) throw IOException("${file.remotePath} is larger than expected")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onBytes(written)
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (written != file.sizeBytes || hash != file.sha256) throw IOException("Checksum mismatch for ${file.remotePath}")
            }
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw error
        } finally { cancellation?.dispose() }
    }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}

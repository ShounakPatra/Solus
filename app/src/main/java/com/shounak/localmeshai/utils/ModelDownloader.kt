package com.shounak.localmeshai.utils

import android.content.Context
import android.os.Environment
import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelDownloadSpec
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

data class ContentRange(
    val unit: String,
    val start: Long?,
    val end: Long?,
    val total: Long?
) {
    companion object {
        fun parse(header: String?): ContentRange? {
            if (header.isNullOrBlank()) return null
            val parts = header.trim().split(' ', limit = 2)
            if (parts.size != 2) return null
            val unit = parts[0]
            val rangeAndTotal = parts[1].split('/', limit = 2)
            val rangePart = rangeAndTotal[0]
            val totalPart = rangeAndTotal.getOrNull(1)

            val total = totalPart?.takeIf { it != "*" }?.toLongOrNull()
            if (rangePart == "*") {
                return ContentRange(unit, null, null, total)
            }
            val rangeIndices = rangePart.split('-', limit = 2)
            if (rangeIndices.size != 2) return null
            val start = rangeIndices[0].toLongOrNull()
            val end = rangeIndices[1].toLongOrNull()
            return ContentRange(unit, start, end, total)
        }
    }
}

open class ModelDownloader(
    private val context: Context? = null,
    private val customHttpClient: OkHttpClient? = null,
    val artifactStorage: ModelArtifactStorage = defaultStorage(context),
    val minValidModelBytes: Long = MIN_VALID_MODEL_BYTES
) {
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun cancelTransport(modelId: String) {
        activeCalls.remove(modelId)?.cancel()
    }

    val modelRoot: File
        get() = artifactStorage.rootDir

    fun getTargetFile(modelId: String, fileName: String, packageType: ModelPackage): File? {
        val published = artifactStorage.getPublishedArtifact(modelId)
        if (published != null) return published

        val safeName = runCatching { ModelArtifactStorage.sanitizeFileName(fileName) }.getOrNull() ?: return null
        val sanitizedId = runCatching { ModelArtifactStorage.sanitizeId(modelId) }.getOrNull() ?: return null

        when (packageType) {
            ModelPackage.SingleFile -> {
                val isolatedTarget = artifactStorage.getModelDir(sanitizedId, create = false).resolve(safeName)
                if (isolatedTarget.exists() && isolatedTarget.isFile && isolatedTarget.length() >= minValidModelBytes) {
                    return isolatedTarget
                }
                val legacy = modelRoot.resolve(safeName)
                if (legacy.exists() && legacy.isFile && legacy.length() >= minValidModelBytes) {
                    return legacy
                }
            }
            ModelPackage.ZipDirectory -> {
                // Never treat an unvalidated arbitrary directory as published
                return null
            }
        }
        return null
    }

    fun getPartialDownloadBytes(modelId: String): Long {
        val stagingDir = runCatching { artifactStorage.getStagingDir(modelId) }.getOrNull()
        val stagingTotal = stagingDir?.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".resume.json") && !it.name.endsWith(".meta") }
            ?.sumOf { it.length() } ?: 0L
        if (stagingTotal > 0L) return stagingTotal

        return modelRoot.resolve("$modelId.download")
            .takeIf { it.exists() && it.isFile }
            ?.length()
            ?: 0L
    }

    fun deleteStagingDownload(modelId: String, fileName: String? = null) {
        if (!fileName.isNullOrBlank()) {
            val stagingFile = runCatching { artifactStorage.getStagingFile(modelId, fileName) }.getOrNull()
            if (stagingFile != null && stagingFile.exists()) {
                stagingFile.delete()
                DownloadResumeMetadata.deleteFor(stagingFile)
            }
        }
        val stagingDir = runCatching { artifactStorage.getStagingDir(modelId) }.getOrNull()
        if (stagingDir != null && stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        val legacyTemp = modelRoot.resolve("$modelId.download")
        runCatching {
            if (legacyTemp.exists()) {
                legacyTemp.delete()
                DownloadResumeMetadata.deleteFor(legacyTemp)
            }
        }
    }

    fun deleteDownload(modelId: String, fileName: String, packageType: ModelPackage) {
        deleteStagingDownload(modelId, fileName)
        artifactStorage.deleteModel(modelId)
    }

    open suspend fun downloadModel(
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        bearerToken: String?,
        expectedSha256: String?,
        eTag: String?,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult = downloadModel(
        url = url,
        modelId = modelId,
        fileName = fileName,
        packageType = packageType,
        bearerToken = bearerToken,
        expectedSha256 = expectedSha256,
        eTag = eTag,
        publishImmediately = true,
        sourceIdentity = null,
        onProgress = onProgress
    )

    open suspend fun downloadModel(
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        bearerToken: String?,
        expectedSha256: String? = null,
        eTag: String? = null,
        publishImmediately: Boolean = true,
        sourceIdentity: String? = null,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (context != null && !NetworkUtils.isConnected(context)) {
            throw IOException("No internet connection. Please connect to Wi-Fi or mobile data to download models.")
        }
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
                    eTag = eTag,
                    publishImmediately = publishImmediately,
                    sourceIdentity = sourceIdentity,
                    onProgress = onProgress
                )
            } catch (exception: IOException) {
                if (NetworkUtils.isOfflineException(exception)) {
                    throw IOException("No internet connection. Please check your network and retry.")
                }
                if (!exception.isTransientDownloadFailure() || retryCount >= MAX_DOWNLOAD_RETRIES) {
                    if (NetworkUtils.isNetworkException(exception)) {
                        throw IOException("Connection timed out or network dropped while downloading. Tap Resume to continue.")
                    }
                    throw exception
                }
                retryCount++
                val downloadedBytes = getPartialDownloadBytes(modelId)
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

    open suspend fun downloadArtifactBundle(
        spec: ModelDownloadSpec,
        bearerToken: String?,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (context != null && !NetworkUtils.isConnected(context)) {
            throw IOException("No internet connection. Please connect to Wi-Fi or mobile data to download models.")
        }

        // Validate destination names: reject collisions
        val safeTargetNames = spec.artifacts.map { (if (spec.effectiveFormat.isPortable) PortableBundlePath.normalize(it.fileName) else ModelArtifactStorage.sanitizeFileName(it.fileName)).lowercase(Locale.ROOT) }
        if (safeTargetNames.distinct().size != safeTargetNames.size) {
            throw IOException("Duplicate artifact target file names in bundle specification")
        }

        val stagedItems = mutableListOf<StagedArtifactItem>()
        var totalExpectedBytes = spec.totalExpectedBytes
        val artifacts = PortableCompanionFiles.complete(spec).toMutableList()
        var completedArtifactsBytes = 0L
        val expectedSourceIdentity = "spec:${spec.repository}@${spec.revision}"

        var index = 0
        var standaloneTflite = false
        while (index < artifacts.size) {
            val artifact = artifacts[index]
            if (standaloneTflite && artifact.optional && artifact.fileName in PortableCompanionFiles.names) {
                index++
                continue
            }
            ensureActive()
            val targetStagingFile = artifactStorage.getStagingFile(spec.modelId, artifact.fileName)
            val savedMeta = DownloadResumeMetadata.loadFor(targetStagingFile)
            val hasExactExpectedSize = artifact.expectedSizeBytes > 0L && targetStagingFile.length() == artifact.expectedSizeBytes
            val hasExactExpectedSha = !artifact.expectedSha256.isNullOrBlank() && verifyFileSha256(targetStagingFile, artifact.expectedSha256)
            val hasMatchingLength = savedMeta != null && savedMeta.lastDownloadedBytes == targetStagingFile.length()
            val matchesSource = savedMeta == null || savedMeta.sourceIdentity == null || savedMeta.sourceIdentity == expectedSourceIdentity
            val hasVerifiedCompletion = savedMeta != null && savedMeta.isCompleted &&
                savedMeta.normalizedUrl == ModelDownloadUrlPolicy.normalizeUrl(artifact.downloadUrl) &&
                hasMatchingLength &&
                targetStagingFile.length() > 0L

            val isAlreadyComplete = targetStagingFile.exists() && targetStagingFile.length() > 0L && matchesSource && (
                (hasExactExpectedSize && (artifact.expectedSha256.isNullOrBlank() || hasExactExpectedSha)) ||
                (hasExactExpectedSha && (artifact.expectedSizeBytes <= 0L || hasExactExpectedSize)) ||
                (artifact.expectedSizeBytes <= 0L && artifact.expectedSha256.isNullOrBlank() && hasVerifiedCompletion)
            )

            val downloadedLocalPath: String
            val downloadedBytes: Long

            if (isAlreadyComplete) {
                downloadedLocalPath = targetStagingFile.absolutePath
                downloadedBytes = targetStagingFile.length()
                completedArtifactsBytes += downloadedBytes
                val aggTotal = if (totalExpectedBytes > 0L) totalExpectedBytes else -1L
                val aggProgress = if (aggTotal > 0L) {
                    (completedArtifactsBytes.toFloat() / aggTotal.toFloat()).coerceIn(0f, 1f)
                } else 1f
                onProgress(
                    DownloadProgress(
                        progress = aggProgress,
                        downloadedBytes = completedArtifactsBytes,
                        totalBytes = aggTotal,
                        bytesPerSecond = 0L
                    )
                )
            } else {
                val downloadedResult = try { downloadModel(
                    url = artifact.downloadUrl,
                    modelId = spec.modelId,
                    fileName = artifact.fileName,
                    packageType = ModelPackage.SingleFile,
                    bearerToken = bearerToken,
                    expectedSha256 = artifact.expectedSha256,
                    publishImmediately = false,
                    sourceIdentity = expectedSourceIdentity
                ) { perFileProgress ->
                    val currentAggregatedBytes = completedArtifactsBytes + perFileProgress.downloadedBytes
                    val aggTotal = if (totalExpectedBytes > 0L) totalExpectedBytes else -1L
                    val aggProgress = if (aggTotal > 0L) {
                        (currentAggregatedBytes.toFloat() / aggTotal.toFloat()).coerceIn(0f, 1f)
                    } else {
                        perFileProgress.progress
                    }
                    onProgress(
                        DownloadProgress(
                            progress = aggProgress,
                            downloadedBytes = currentAggregatedBytes,
                            totalBytes = aggTotal,
                            bytesPerSecond = perFileProgress.bytesPerSecond
                        )
                    )
                }
                } catch (e: java.io.IOException) {
                    if (artifact.optional && PortableCompanionFiles.dispensable(artifact.fileName) {
                            artifactStorage.getStagingFile(spec.modelId, it)
                        }) {
                        android.util.Log.w("ModelDownloader", "Optional companion ${artifact.fileName} unavailable", e)
                        index++
                        continue
                    }
                    if (e !is ModelDownloadHttpException) throw e
                    if (artifact.optional && e.statusCode == 404) {
                        index++
                        continue
                    }
                    if (spec.effectiveFormat.isPortable && e.statusCode == 404 && artifact.fileName in PortableCompanionFiles.names) {
                        throw MissingModelFilesException("${artifact.fileName} was not found at the model source. The downloaded weights are kept; retry to fetch the companion files or import a complete bundle.")
                    }
                    throw e
                }
                downloadedLocalPath = downloadedResult.localPath
                downloadedBytes = downloadedResult.bytesDownloaded
                completedArtifactsBytes += downloadedBytes
            }

            val isPrimary = (artifact.role == ArtifactRole.Main ||
                (index == 0 && spec.artifacts.none { it.role == ArtifactRole.Main }))
            stagedItems.add(
                StagedArtifactItem(
                    stagedFile = File(downloadedLocalPath),
                    relativeTargetName = artifact.fileName,
                    expectedSha256 = artifact.expectedSha256,
                    isPrimary = isPrimary,
                    role = artifact.role,
                    order = index
                )
            )
            if (index == 0 && spec.effectiveFormat == ModelFormat.TensorFlowLite &&
                spec.artifacts.none { it.fileName == "config.json" && !it.optional }) {
                standaloneTflite = TfliteExportInspector.file(File(downloadedLocalPath)).config != null
            }
            if (spec.effectiveFormat == ModelFormat.Onnx && artifact.fileName.endsWith(".onnx", true)) {
                val externalNames = OnnxBundleValidation.validate(File(downloadedLocalPath), checkExternal = false)
                for (name in externalNames) {
                    val relative = PortableBundlePath.normalize(name)
                    val targetName = artifact.fileName.substringBeforeLast('/', "").let { if (it.isBlank()) relative else "$it/$relative" }
                    if (artifacts.any { it.fileName == targetName }) continue
                    require(artifacts.none { it.fileName.equals(targetName, true) }) { "Colliding ONNX external-data path" }
                    val primaryUrl = artifact.downloadUrl.toHttpUrlOrNull() ?: error("Invalid ONNX download URL")
                    val externalUrl = requireNotNull(primaryUrl.resolve(relative))
                    artifacts.add(com.shounak.localmeshai.models.DownloadArtifactSpec(externalUrl.toString(), targetName))
                    totalExpectedBytes = 0L
                }
            }
            index++
        }

        // Validate complete shard membership
        val shardItems = stagedItems.filter { it.role == ArtifactRole.Shard }
        if (shardItems.isNotEmpty()) {
            val totalShards = spec.artifacts.firstOrNull { it.role == ArtifactRole.Shard }?.totalShards ?: shardItems.size
            if (shardItems.size != totalShards) {
                throw IOException("Incomplete shard bundle: expected $totalShards shards, but only ${shardItems.size} were downloaded.")
            }
        }

        var format = NonGgufBundleInspector.detectFormat(stagedItems.first().stagedFile) ?: spec.effectiveFormat
        require(format == ModelFormat.GGUF || format.isPortable || (stagedItems.size == 1 && stagedItems.none { it.role == ArtifactRole.Projector || it.role == ArtifactRole.Shard })) {
            "Only GGUF supports split shards and companion projectors. Select one LiteRT-LM or MediaPipe task bundle."
        }
        val validatedStagedItems = mutableListOf<StagedArtifactItem>()
        val capabilities: ModelCapabilities
        val primaryName: String
        var derivedLoadRestriction: com.shounak.localmeshai.models.LoadRestriction? = null

        if (format == ModelFormat.GGUF) {
            // Validate complete artifact bundle via native GgufBundleInspector before atomic publication
            val stagedFiles = stagedItems.map { it.stagedFile }
            val expectedRolesMap = stagedItems.associate { it.relativeTargetName to it.role }
            val expectedSizesMap = spec.artifacts.associate { it.fileName to it.expectedSizeBytes }
            val bundleValidation = GgufBundleInspector.inspectAndValidateFiles(
                files = stagedFiles,
                expectedRoles = expectedRolesMap,
                expectedSizes = expectedSizesMap,
                logicalNames = stagedItems.map { it.relativeTargetName }
            )
            if (!bundleValidation.isValid) {
                throw IOException("Bundle verification failed before publication: ${bundleValidation.errorMessage}")
            }

            val stagedByDisplayName = stagedItems.associateBy { it.relativeTargetName }
            bundleValidation.orderedMainArtifacts.forEachIndexed { order, art ->
                val orig = stagedByDisplayName[art.displayName] ?: error("Missing validated artifact ${art.displayName}")
                val isMain = bundleValidation.orderedMainArtifacts.size == 1
                validatedStagedItems.add(
                    orig.copy(
                        order = order,
                        isPrimary = (order == 0),
                        role = if (isMain) ArtifactRole.Main else ArtifactRole.Shard
                    )
                )
            }
            bundleValidation.projectorArtifact?.let { proj ->
                val orig = stagedByDisplayName[proj.displayName] ?: error("Missing validated projector ${proj.displayName}")
                validatedStagedItems.add(
                    orig.copy(
                        order = validatedStagedItems.size,
                        isPrimary = false,
                        role = ArtifactRole.Projector
                    )
                )
            }
            capabilities = bundleValidation.capabilities
            primaryName = bundleValidation.orderedMainArtifacts.first().displayName
        } else if (format.isPortable) {
            val files = stagedItems.associate { it.relativeTargetName to it.stagedFile }
            val primary = stagedItems.first()
            if (format != ModelFormat.TensorFlowLite && files["config.json"]?.isFile != true && files["model_index.json"]?.isFile != true) {
                throw MissingModelFilesException("config.json was not found at the model source. The downloaded weights are kept; retry to fetch the configuration or import a complete bundle.")
            }
            capabilities = PortableModelBundle.inspect(primary.stagedFile, format, files, primary.relativeTargetName)
            derivedLoadRestriction = PortableModelBundle.runtimeRestriction(primary.stagedFile, format, files)
            primaryName = primary.relativeTargetName
            validatedStagedItems.addAll(stagedItems.mapIndexed { index, item -> item.copy(order = index, isPrimary = index == 0) })
        } else {
            // Non-GGUF format (.task or .litertlm): validate container structure and derive real capabilities via NonGgufBundleInspector
            var derivedCapabilities: ModelCapabilities? = null
            stagedItems.forEachIndexed { order, item ->
                if (!item.stagedFile.exists() || item.stagedFile.length() <= 0L) {
                    throw IOException("Downloaded non-GGUF file is missing or empty: ${item.stagedFile.name}")
                }
                val inspection = NonGgufBundleInspector.inspectFile(item.stagedFile, format, item.expectedSha256)
                if (!inspection.isValid) {
                    throw IOException("Downloaded file '${item.relativeTargetName}' failed bundle validation for $format: ${inspection.errorMessage}")
                }
                if (order == 0) {
                    format = inspection.format
                    derivedCapabilities = inspection.capabilities
                    derivedLoadRestriction = inspection.loadRestriction
                }
                validatedStagedItems.add(
                    item.copy(
                        order = order,
                        isPrimary = (order == 0),
                        role = ArtifactRole.Main
                    )
                )
            }
            capabilities = derivedCapabilities ?: spec.capabilities
            primaryName = validatedStagedItems.first().relativeTargetName
        }

        val currentJob = coroutineContext[Job]
        val publishedPrimary = artifactStorage.publishArtifactBundle(
            modelId = spec.modelId,
            stagedItems = validatedStagedItems,
            primaryItemName = primaryName,
            sourceIdentity = expectedSourceIdentity,
            packageType = "ArtifactBundle",
            capabilities = capabilities,
            format = format,
            loadRestriction = derivedLoadRestriction,
            isCancelled = { currentJob?.isActive == false }
        )

        DownloadResult(
            localPath = publishedPrimary.absolutePath,
            bytesDownloaded = completedArtifactsBytes
        )
    }

    private fun getHttpClient(bearerToken: String?): OkHttpClient {
        val base = customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
        return ModelDownloadUrlPolicy.configureClient(base.newBuilder()) { bearerToken }
            .build()
    }

    private fun readBoundedResponseBody(body: ResponseBody?, maxBytes: Long = 4096L): String {
        if (body == null) return ""
        return runCatching {
            val source = body.source()
            source.request(maxBytes)
            val buffer = source.buffer
            val toRead = minOf(maxBytes, buffer.size)
            buffer.clone().readUtf8(toRead).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MAX_ERROR_MESSAGE_CHARS)
        }.getOrDefault("")
    }

    private suspend fun downloadModelOnce(
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        bearerToken: String?,
        expectedSha256: String? = null,
        eTag: String? = null,
        publishImmediately: Boolean = true,
        sourceIdentity: String? = null,
        onProgress: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        val parsedUrl = url.toHttpUrlOrNull()
        val isLoopback = parsedUrl != null && (parsedUrl.host == "localhost" || parsedUrl.host == "127.0.0.1" || parsedUrl.host == "::1")
        if (!url.startsWith("https://", ignoreCase = true) && !isLoopback) {
            throw IOException("Insecure HTTP connections are blocked for model downloads. URL must use HTTPS.")
        }

        val normalizedUrl = ModelDownloadUrlPolicy.normalizeUrl(url)
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        val legacyTempFile = modelRoot.resolve("$modelId.download")
        if (!stagingFile.exists() && legacyTempFile.exists() && legacyTempFile.isFile) {
            legacyTempFile.renameTo(stagingFile)
        }

        var existingBytes = if (stagingFile.exists()) stagingFile.length() else 0L
        val savedMeta = DownloadResumeMetadata.loadFor(stagingFile)
        var effectiveValidator: String? = eTag

        if (existingBytes > 0L) {
            if (savedMeta != null) {
                val isIncompatible = savedMeta.normalizedUrl != normalizedUrl ||
                    savedMeta.packageType != packageType ||
                    (!savedMeta.expectedSha256.isNullOrBlank() && !expectedSha256.isNullOrBlank() && !savedMeta.expectedSha256.equals(expectedSha256.trim(), ignoreCase = true))

                if (isIncompatible) {
                    stagingFile.delete()
                    DownloadResumeMetadata.deleteFor(stagingFile)
                    existingBytes = 0L
                    effectiveValidator = null
                } else if (effectiveValidator.isNullOrBlank()) {
                    effectiveValidator = savedMeta.validator
                }
            }
        }

        var restartAllowed = true

        while (true) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Solus/1.0 Android")

            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                if (!effectiveValidator.isNullOrBlank()) {
                    requestBuilder.header("If-Range", effectiveValidator)
                }
            }

            val client = getHttpClient(bearerToken)
            val call = client.newCall(requestBuilder.build())
            activeCalls[modelId] = call

            val job = coroutineContext[Job]
            val cancelReg = job?.abortTransportOnCancellation {
                activeCalls.remove(modelId, call)
                call.cancel()
            }

            val response = try {
                call.execute()
            } catch (e: IOException) {
                cancelReg?.dispose()
                activeCalls.remove(modelId, call)
                if (job?.isCancelled == true) {
                    throw kotlinx.coroutines.CancellationException("Download cancelled", e)
                }
                throw e
            }

            val responseCode = response.code

            if (responseCode == 416) {
                response.close()
                cancelReg?.dispose()
                activeCalls.remove(modelId, call)

                val contentRange = ContentRange.parse(response.header("Content-Range"))
                val isBytesUnit = contentRange == null || contentRange.unit.equals("bytes", ignoreCase = true)
                val totalFromHeader = if (isBytesUnit) (contentRange?.total ?: savedMeta?.expectedTotalBytes) else null
                val matchesExpectedTotal = totalFromHeader != null && totalFromHeader > 0L && existingBytes == totalFromHeader
                val matchesChecksum = if (!expectedSha256.isNullOrBlank()) {
                    verifyFileSha256(stagingFile, expectedSha256)
                } else false

                val isMetaMatch = savedMeta == null || (savedMeta.normalizedUrl == normalizedUrl && savedMeta.packageType == packageType)

                if (existingBytes > 0L && isMetaMatch && (matchesExpectedTotal || matchesChecksum)) {
                    onProgress(DownloadProgress(1f, existingBytes, existingBytes, 0L))
                    return handleDownloadSuccess(
                        stagingFile = stagingFile,
                        url = url,
                        modelId = modelId,
                        fileName = fileName,
                        packageType = packageType,
                        expectedSha256 = expectedSha256,
                        downloaded = existingBytes,
                        publishImmediately = publishImmediately,
                        sourceIdentity = sourceIdentity
                    )
                } else if (restartAllowed) {
                    restartAllowed = false
                    stagingFile.delete()
                    DownloadResumeMetadata.deleteFor(stagingFile)
                    existingBytes = 0L
                    effectiveValidator = null
                    continue
                } else {
                    stagingFile.delete()
                    DownloadResumeMetadata.deleteFor(stagingFile)
                    throw IOException("HTTP 416 Range Not Satisfiable: file representation mismatch after restart.")
                }
            }

            if (!response.isSuccessful) {
                val serverMessage = readBoundedResponseBody(response.body)
                response.close()
                cancelReg?.dispose()
                activeCalls.remove(modelId, call)
                throw ModelDownloadHttpException(responseCode, downloadErrorMessage(url, responseCode, serverMessage))
            }

            val body = response.body
            val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
            val isJsonCompanion = !publishImmediately && fileName.endsWith(".json", ignoreCase = true)
            if (contentType.contains("text/html") || (contentType.contains("application/json") && !isJsonCompanion)) {
                val serverMessage = readBoundedResponseBody(body)
                response.close()
                cancelReg?.dispose()
                activeCalls.remove(modelId, call)
                throw IOException("Model host returned a web page instead of a model file. $serverMessage")
            }

            var contentRange: ContentRange? = null
            if (responseCode == 206) {
                contentRange = ContentRange.parse(response.header("Content-Range"))
                val isRangeValid = contentRange != null &&
                    contentRange.unit.equals("bytes", ignoreCase = true) &&
                    contentRange.start != null &&
                    contentRange.start == existingBytes &&
                    (contentRange.end == null || contentRange.end >= contentRange.start)
                if (!isRangeValid) {
                    response.close()
                    cancelReg?.dispose()
                    activeCalls.remove(modelId, call)
                    if (restartAllowed) {
                        restartAllowed = false
                        stagingFile.delete()
                        DownloadResumeMetadata.deleteFor(stagingFile)
                        existingBytes = 0L
                        effectiveValidator = null
                        continue
                    } else {
                        stagingFile.delete()
                        DownloadResumeMetadata.deleteFor(stagingFile)
                        throw IOException("Server returned invalid Content-Range: ${response.header("Content-Range")}")
                    }
                }
            } else if (responseCode == 200 && existingBytes > 0L) {
                stagingFile.delete()
                DownloadResumeMetadata.deleteFor(stagingFile)
                existingBytes = 0L
            }

            val validator = response.header("ETag") ?: response.header("Last-Modified") ?: effectiveValidator
            val append = existingBytes > 0L && responseCode == 206
            val contentLength = body.contentLength()
            val totalBytes = if (append) {
                contentRange?.total ?: if (contentLength > 0L) existingBytes + contentLength else -1L
            } else {
                contentLength
            }

            val currentMeta = DownloadResumeMetadata(
                normalizedUrl = normalizedUrl,
                packageType = packageType,
                expectedSha256 = expectedSha256,
                validator = validator,
                expectedTotalBytes = totalBytes,
                lastDownloadedBytes = existingBytes,
                sourceIdentity = sourceIdentity
            )
            DownloadResumeMetadata.saveFor(stagingFile, currentMeta)

            var downloaded = if (append) existingBytes else 0L
            var bytesReadThisStream = 0L
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
                        FileOutputStream(stagingFile, append).use { output ->
                            CancellableHttpTransfer.transfer(
                                input = input,
                                output = output,
                                coroutineContext = coroutineContext,
                                isCancelled = { job?.isCancelled == true || call.isCanceled() },
                                onAbort = {
                                    activeCalls.remove(modelId, call)
                                    call.cancel()
                                }
                            ) { read, _ ->
                                downloaded += read
                                bytesReadThisStream += read
                                emitProgress()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (stagingFile.exists() && downloaded > 0L) {
                    DownloadResumeMetadata.saveFor(
                        stagingFile,
                        currentMeta.copy(lastDownloadedBytes = downloaded, updatedAt = System.currentTimeMillis())
                    )
                }
                throw e
            } finally {
                cancelReg?.dispose()
                activeCalls.remove(modelId, call)
                call.cancel()
            }

            if (contentLength > 0L && bytesReadThisStream != contentLength) {
                throw IOException("Incomplete range response received: got $bytesReadThisStream bytes, expected $contentLength bytes.")
            }
            if (contentRange?.end != null && (existingBytes + bytesReadThisStream) < (contentRange.end + 1)) {
                throw IOException("Incomplete Content-Range: stream closed before end index ${contentRange.end}.")
            }
            if (totalBytes > 0L && downloaded < totalBytes) {
                throw IOException("Truncated download: received $downloaded of $totalBytes total bytes.")
            }

            emitProgress(force = true)
            return handleDownloadSuccess(
                stagingFile = stagingFile,
                url = url,
                modelId = modelId,
                fileName = fileName,
                packageType = packageType,
                expectedSha256 = expectedSha256,
                downloaded = downloaded,
                publishImmediately = publishImmediately,
                sourceIdentity = sourceIdentity
            )
        }
    }

    private suspend fun handleDownloadSuccess(
        stagingFile: File,
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        expectedSha256: String?,
        downloaded: Long,
        publishImmediately: Boolean,
        sourceIdentity: String? = null
    ): DownloadResult {
        if (!publishImmediately) {
            if (downloaded == 0L) {
                stagingFile.delete()
                DownloadResumeMetadata.deleteFor(stagingFile)
                throw IOException("Downloaded file is empty.")
            }
            val currentJob = coroutineContext[Job]
            val isCancelled = { currentJob?.isActive == false }
            if (!expectedSha256.isNullOrBlank()) {
                val actualSha256 = computeSha256(stagingFile, isCancelled = isCancelled)
                if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                    stagingFile.delete()
                    DownloadResumeMetadata.deleteFor(stagingFile)
                    throw IOException("Checksum verification failed for $fileName: expected $expectedSha256, got $actualSha256")
                }
            }
            val existingMeta = DownloadResumeMetadata.loadFor(stagingFile) ?: DownloadResumeMetadata(
                normalizedUrl = ModelDownloadUrlPolicy.normalizeUrl(url),
                packageType = packageType,
                expectedSha256 = expectedSha256,
                expectedTotalBytes = downloaded,
                lastDownloadedBytes = downloaded,
                sourceIdentity = sourceIdentity
            )
            DownloadResumeMetadata.saveFor(
                stagingFile,
                existingMeta.copy(
                    isCompleted = true,
                    lastDownloadedBytes = downloaded,
                    updatedAt = System.currentTimeMillis(),
                    sourceIdentity = sourceIdentity ?: existingMeta.sourceIdentity
                )
            )
            return DownloadResult(
                localPath = stagingFile.absolutePath,
                bytesDownloaded = downloaded
            )
        }

        DownloadResumeMetadata.deleteFor(stagingFile)

        return publishCompletedDownload(
            stagingFile = stagingFile,
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = packageType,
            expectedSha256 = expectedSha256,
            downloaded = downloaded
        )
    }

    private suspend fun publishCompletedDownload(
        stagingFile: File,
        url: String,
        modelId: String,
        fileName: String,
        packageType: ModelPackage,
        expectedSha256: String?,
        downloaded: Long
    ): DownloadResult {
        if (downloaded == 0L) {
            stagingFile.delete()
            DownloadResumeMetadata.deleteFor(stagingFile)
            throw IOException("Downloaded file is empty.")
        }
        if (downloaded < minValidModelBytes) {
            stagingFile.delete()
            DownloadResumeMetadata.deleteFor(stagingFile)
            throw IOException("Downloaded file is too small to be a valid model.")
        }
        if (
            packageType == ModelPackage.SingleFile &&
            fileName.endsWith(".task", ignoreCase = true) &&
            !isLikelyTaskBundle(stagingFile)
        ) {
            val message = invalidTaskFileMessage(stagingFile)
            stagingFile.delete()
            DownloadResumeMetadata.deleteFor(stagingFile)
            throw IOException(message)
        }

        val currentJob = coroutineContext[Job]
        val isCancelled = { currentJob?.isActive == false }

        if (!expectedSha256.isNullOrBlank()) {
            val actualSha256 = computeSha256(stagingFile, isCancelled = isCancelled)
            if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                stagingFile.delete()
                DownloadResumeMetadata.deleteFor(stagingFile)
                throw IOException("SHA-256 checksum verification failed. Expected: ${expectedSha256.trim()}, Actual: $actualSha256")
            }
        }

        val localPath = when (packageType) {
            ModelPackage.SingleFile -> {
                val published = artifactStorage.publishArtifact(
                    modelId = modelId,
                    stagedSource = stagingFile,
                    relativeTargetName = fileName,
                    sourceIdentity = url,
                    expectedSha256 = expectedSha256,
                    isCancelled = isCancelled
                )
                published.setLastModified(System.currentTimeMillis())
                published.absolutePath
            }

            ModelPackage.ZipDirectory -> {
                val stagingExtractionDir = artifactStorage.getStagingDir(modelId).resolve("extracted")
                stagingExtractionDir.deleteRecursively()
                stagingExtractionDir.mkdirs()
                unzipModel(stagingFile, stagingExtractionDir, isCancelled = isCancelled)
                stagingFile.delete()
                val extracted = resolveExtractedModelDirectory(stagingExtractionDir)
                val isTextModel = fileName.endsWith(".task", ignoreCase = true) ||
                    fileName.endsWith(".litertlm", ignoreCase = true) ||
                    fileName.endsWith(".gguf", ignoreCase = true) ||
                    !fileName.contains("vision", ignoreCase = true)
                val published = artifactStorage.publishArtifact(
                    modelId = modelId,
                    stagedSource = extracted,
                    relativeTargetName = fileName,
                    sourceIdentity = url,
                    expectedSha256 = null,
                    archiveSha256 = expectedSha256,
                    isCancelled = isCancelled,
                    isTextModel = isTextModel
                )
                published.setLastModified(System.currentTimeMillis())
                published.absolutePath
            }
        }

        return DownloadResult(localPath = localPath, bytesDownloaded = downloaded)
    }

    private suspend fun unzipModel(
        zipFile: File,
        targetDirectory: File,
        isCancelled: (() -> Boolean)? = null
    ) {
        val canonicalTarget = targetDirectory.canonicalFile
        var totalUncompressedBytes = 0L
        val maxAllowedBytes = 16L * 1024L * 1024L * 1024L // 16 GB max total model size guard

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                if (isCancelled?.invoke() == true) {
                    throw kotlinx.coroutines.CancellationException("ZIP extraction cancelled")
                }
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
                            coroutineContext.ensureActive()
                            if (isCancelled?.invoke() == true) {
                                throw kotlinx.coroutines.CancellationException("ZIP extraction cancelled")
                            }
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
            responseCode == 404 -> "Model file was not found at the configured URL (HTTP 404).$suffix"
            responseCode == 429 -> "Download rate limit exceeded (HTTP 429). Please wait before trying again.$suffix"
            responseCode in 500..599 -> "Remote model host server error (HTTP $responseCode). Please try again later.$suffix"
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

        fun defaultStorage(context: Context?): ModelArtifactStorage {
            val root = if (context != null) {
                (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads"))
                    .resolve("models")
            } else {
                File(System.getProperty("java.io.tmpdir") ?: ".", "solus_models")
            }
            root.mkdirs()
            return ModelArtifactStorage(root)
        }

        fun computeSha256(
            file: File,
            isCancelled: (() -> Boolean)? = null,
            onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
        ): String = AppTrace.section("hashing") {
            if (!file.exists() || !file.isFile) {
                throw IOException("Cannot compute checksum: file does not exist or is not a regular file")
            }
            val totalBytes = file.length()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesTotalRead = 0L
            var lastProgressTimeMs = 0L
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled?.invoke() == true) {
                        throw kotlinx.coroutines.CancellationException("Checksum calculation cancelled")
                    }
                    digest.update(buffer, 0, bytesRead)
                    bytesTotalRead += bytesRead
                    val now = System.nanoTime() / 1_000_000L
                    if (bytesTotalRead == totalBytes || now - lastProgressTimeMs >= 100L) {
                        lastProgressTimeMs = now
                        onProgress?.invoke(bytesTotalRead, totalBytes)
                    }
                }
            }
            val hashBytes = digest.digest()
            val sb = java.lang.StringBuilder(hashBytes.size * 2)
            for (b in hashBytes) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            sb.toString()
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
            if (!file.isFile || file.length() < 32L) return false
            return runCatching {
                NonGgufBundleInspector.inspectFile(file).isValid
            }.getOrDefault(false)
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

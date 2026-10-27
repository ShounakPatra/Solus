package com.shounak.localmeshai.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ThinkingCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

object LocalModelImporter {
    private const val TAG = "LocalModelImporter"

    data class ImportResult(
        val bundle: ModelBundle,
        val newlyAcquiredGrants: List<Uri>,
        val stagedArtifactItem: StagedArtifactItem? = null,
        val draftStagingDir: File? = null,
        val stagedArtifactItems: List<StagedArtifactItem> = emptyList()
    )

    data class GgufMetadataHeader(
        val validGguf: Boolean,
        val architecture: String = "",
        val name: String = "",
        val splitNo: Int = -1,
        val splitCount: Int = -1,
        val isProjector: Boolean = false,
        val hasVision: Boolean = false,
        val hasAudio: Boolean = false,
        val trainContextLength: Int = 0,
        val thinkingCapability: String = "Unknown"
    )

    data class UriInspectionResult(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val header: GgufMetadataHeader
    )

    /**
     * Inspects a list of user-selected [Uri]s and returns an assembled and validated [ImportResult].
     * Pre-existing persistable grants are snapshotted beforehand, and ONLY newly acquired,
     * unreferenced grants are rolled back on inspection failure or cancellation.
     */
    suspend fun importFromUris(
        context: Context,
        uris: List<Uri>,
        onProgress: ((String) -> Unit)? = null,
        onGrantAcquired: (suspend (Uri) -> Unit)? = null
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No files were selected."))
        }

        // Portable text models require explicit companions selected in the same picker operation.
        // Copying them does not require persistent URI grants or seekable provider descriptors.
        try {
            val names = PortableModelImporter.names(context, uris)
            names.firstNotNullOfOrNull(PortableCompanionFiles::unsupported)?.let { throw IllegalArgumentException(it) }
            if (uris.size == 1 && names.single().substringAfterLast('.').lowercase() in setOf("zip", "solusmodel")) {
                return@withContext Result.success(PortableModelArchive.import(context, uris.single(), onProgress))
            }
            if (names.any { PortableModelBundle.format(it) != null }) {
                return@withContext Result.success(PortableModelImporter.import(context, uris, names, onProgress))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return@withContext Result.failure(t)
        }

        val preExistingGrants = runCatching {
            context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        }.getOrDefault(emptySet())
        val newlyAcquiredGrants = mutableListOf<Uri>()
        val inspections = mutableListOf<UriInspectionResult>()

        try {
            for ((index, uri) in uris.withIndex()) {
                ensureActive()
                onProgress?.invoke("Inspecting file ${index + 1} of ${uris.size}...")

                // 1. Query name, size, and modification timestamp from provider
                var displayName = "model_${index + 1}.gguf"
                var sizeBytes = 0L
                var lastModified = 0L
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                cursor.getString(nameIndex)?.let { displayName = it }
                            }
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1) {
                                sizeBytes = cursor.getLong(sizeIndex)
                            }
                            val modIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            if (modIndex != -1) {
                                lastModified = cursor.getLong(modIndex)
                            }
                        }
                    }
                }

                // 2. Take persistable read permission if not already held
                if (uri !in preExistingGrants) {
                    val permissionTaken = runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        true
                    }.getOrElse {
                        Log.w(TAG, "Failed to take persistable URI permission for $uri: ${it.message}")
                        false
                    }

                    if (!permissionTaken) {
                        if (onGrantAcquired == null) {
                            rollbackPermissions(context, newlyAcquiredGrants)
                        }
                        return@withContext Result.failure(
                            ModelOperationError(
                                reason = ModelErrorReason.StorageAccessDenied,
                                stage = ModelOperationStage.SelectionValidation,
                                affectedFilenames = listOf(displayName),
                                diagnosticDetails = "Failed to acquire persistable read permission for '$displayName'. The selected storage provider does not support persistent access."
                            )
                        )
                    }
                    newlyAcquiredGrants.add(uri)
                    withContext(NonCancellable) {
                        onGrantAcquired?.invoke(uri)
                    }
                }

                // 3. Open seekable file descriptor and verify seek capability
                val pfd = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }.getOrNull()

                if (pfd == null) {
                    if (onGrantAcquired == null) {
                        rollbackPermissions(context, newlyAcquiredGrants)
                    }
                    return@withContext Result.failure(
                        ModelOperationError(
                            reason = ModelErrorReason.StorageAccessDenied,
                            stage = ModelOperationStage.SelectionValidation,
                            affectedFilenames = listOf(displayName),
                            diagnosticDetails = "Failed to open file descriptor for '$displayName'. The file cannot be read from this storage provider."
                        )
                    )
                }

                try {
                    try {
                        android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                    } catch (t: Throwable) {
                        if (onGrantAcquired == null) {
                            rollbackPermissions(context, newlyAcquiredGrants)
                        }
                        return@withContext Result.failure(
                            ModelOperationError(
                                reason = ModelErrorReason.StorageAccessDenied,
                                stage = ModelOperationStage.SelectionValidation,
                                affectedFilenames = listOf(displayName),
                                diagnosticDetails = "'$displayName' is not seekable. Streaming or pipe-based storage providers are not supported."
                            )
                        )
                    }

                    if (sizeBytes <= 0L) {
                        val statSize = pfd.statSize
                        if (statSize > 0L) {
                            sizeBytes = statSize
                        }
                    }

                    // 4. Sniff file header before native inspection to catch XML, images, PDFs, etc.
                    val headerBuffer = ByteArray(2048)
                    val readResult: Result<Int> = runCatching {
                        val dupPfd = pfd.dup() ?: throw java.io.IOException("Failed to duplicate file descriptor for '$displayName'")
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(dupPfd).use { stream ->
                            stream.read(headerBuffer)
                        }
                    }

                    if (readResult.isFailure) {
                        if (onGrantAcquired == null) {
                            rollbackPermissions(context, newlyAcquiredGrants)
                        }
                        val err = readResult.exceptionOrNull()
                        return@withContext Result.failure(
                            ModelOperationError(
                                reason = ModelErrorReason.StorageAccessDenied,
                                stage = ModelOperationStage.SelectionValidation,
                                affectedFilenames = listOf(displayName),
                                diagnosticDetails = "Failed to read header from '$displayName': ${err?.message ?: "Access error"}"
                            )
                        )
                    }

                    val bytesRead = readResult.getOrDefault(0)
                    val actualBytes = if (bytesRead > 0) headerBuffer.copyOf(bytesRead) else ByteArray(0)
                    val sniffResult = FileHeaderSniffer.sniffHeader(actualBytes, sizeBytes, displayName)

                    when (sniffResult) {
                        is FileHeaderSniffer.SniffResult.UnsupportedStorageFormat -> {
                            if (onGrantAcquired == null) {
                                rollbackPermissions(context, newlyAcquiredGrants)
                            }
                            return@withContext Result.failure(
                                ModelOperationError(
                                    reason = ModelErrorReason.UnsupportedStorageFormat,
                                    stage = ModelOperationStage.SelectionValidation,
                                    affectedFilenames = listOf(displayName),
                                    diagnosticDetails = sniffResult.detail
                                )
                            )
                        }
                        is FileHeaderSniffer.SniffResult.NonModel -> {
                            if (onGrantAcquired == null) {
                                rollbackPermissions(context, newlyAcquiredGrants)
                            }
                            return@withContext Result.failure(
                                ModelOperationError(
                                    reason = ModelErrorReason.NonModelFile,
                                    stage = ModelOperationStage.SelectionValidation,
                                    affectedFilenames = listOf(displayName),
                                    detectedFileType = sniffResult.fileTypeDescription,
                                    diagnosticDetails = sniffResult.detail
                                )
                            )
                        }
                        is FileHeaderSniffer.SniffResult.EmptyOrDamaged -> {
                            if (onGrantAcquired == null) {
                                rollbackPermissions(context, newlyAcquiredGrants)
                            }
                            return@withContext Result.failure(
                                ModelOperationError(
                                    reason = ModelErrorReason.EmptyOrDamagedHeader,
                                    stage = ModelOperationStage.SelectionValidation,
                                    affectedFilenames = listOf(displayName),
                                    diagnosticDetails = sniffResult.detail
                                )
                            )
                        }
                        is FileHeaderSniffer.SniffResult.ValidMediaPipeTask,
                        is FileHeaderSniffer.SniffResult.ValidLiteRtLm -> {
                            if (uris.size > 1) {
                                if (onGrantAcquired == null) {
                                    rollbackPermissions(context, newlyAcquiredGrants)
                                }
                                return@withContext Result.failure(
                                    ModelOperationError(
                                        reason = ModelErrorReason.UnrelatedModelsSelected,
                                        stage = ModelOperationStage.Inspection,
                                        affectedFilenames = listOf(displayName),
                                        diagnosticDetails = "Only GGUF models support multiple split shards or companion projectors. Select one .task or .litertlm file at a time."
                                    )
                                )
                            }
                            if (!DeviceUtils.is64BitDevice()) {
                                if (onGrantAcquired == null) {
                                    rollbackPermissions(context, newlyAcquiredGrants)
                                }
                                return@withContext Result.failure(
                                    ModelOperationError(
                                        reason = ModelErrorReason.UnsupportedStorageFormat,
                                        stage = ModelOperationStage.Inspection,
                                        architectureOrFormat = if (sniffResult is FileHeaderSniffer.SniffResult.ValidMediaPipeTask) "MediaPipe" else "LiteRT",
                                        diagnosticDetails = "This device does not support 64-bit native runtimes required for this model format."
                                    )
                                )
                            }

                            val stableId = generateStorageModelId(listOf(uri.toString()))
                            val artifactStorage = ModelDownloader.defaultStorage(context)
                            val draftSessionId = "draft_${java.util.UUID.randomUUID().toString().take(8)}"
                            val stagingDir = artifactStorage.getStagingDir(draftSessionId)
                            stagingDir.mkdirs()
                            val safeName = ModelArtifactStorage.sanitizeFileName(displayName)
                            val stagedFile = File(stagingDir, safeName)

                            try {
                                context.contentResolver.openInputStream(uri)?.use { inStream ->
                                    stagedFile.outputStream().use { outStream ->
                                        inStream.copyTo(outStream)
                                    }
                                } ?: throw java.io.IOException("Could not open input stream for $displayName")
                            } catch (e: Exception) {
                                runCatching { stagingDir.deleteRecursively() }
                                if (onGrantAcquired == null) {
                                    rollbackPermissions(context, newlyAcquiredGrants)
                                }
                                return@withContext Result.failure(
                                    ModelOperationError(
                                        reason = ModelErrorReason.StorageAccessDenied,
                                        stage = ModelOperationStage.Inspection,
                                        affectedFilenames = listOf(displayName),
                                        diagnosticDetails = "Failed to copy model to local app storage: ${e.message}"
                                    )
                                )
                            }

                            val format = if (sniffResult is FileHeaderSniffer.SniffResult.ValidMediaPipeTask) ModelFormat.MediaPipeTask else ModelFormat.LiteRtLm
                            val inspection = NonGgufBundleInspector.inspectFile(stagedFile, format)
                            if (!inspection.isValid) {
                                runCatching { stagingDir.deleteRecursively() }
                                if (onGrantAcquired == null) {
                                    rollbackPermissions(context, newlyAcquiredGrants)
                                }
                                val reason = if (inspection.errorMessage?.contains("raw TensorFlow Lite", ignoreCase = true) == true) {
                                    ModelErrorReason.NonModelFile
                                } else {
                                    ModelErrorReason.EmptyOrDamagedHeader
                                }
                                return@withContext Result.failure(
                                    ModelOperationError(
                                        reason = reason,
                                        stage = ModelOperationStage.Inspection,
                                        affectedFilenames = listOf(displayName),
                                        diagnosticDetails = inspection.errorMessage ?: "Non-GGUF container validation failed."
                                    )
                                )
                            }

                            val backend = inspection.backend
                            val caps = inspection.capabilities

                            // Keep in staging as draft; do NOT publish until registration is confirmed
                            val stagedItem = StagedArtifactItem(
                                stagedFile = stagedFile,
                                relativeTargetName = safeName,
                                isPrimary = true,
                                role = ArtifactRole.Main,
                                order = 0
                            )

                            val draftArtifact = ModelArtifact(
                                role = ArtifactRole.Main,
                                displayName = safeName,
                                source = ArtifactSource.AppManaged(stagedFile.absolutePath),
                                sizeBytes = stagedFile.length(),
                                lastModified = stagedFile.lastModified()
                            )
                            val bundle = ModelBundle(
                                id = stableId,
                                displayName = displayName.substringBeforeLast('.').replace('-', ' ').replace('_', ' '),
                                mainArtifacts = listOf(draftArtifact),
                                projectorArtifact = null,
                                capabilities = caps,
                                description = if (format == ModelFormat.MediaPipeTask) "MediaPipe model imported from device storage." else "LiteRT model imported from device storage.",
                                backend = backend,
                                contextLength = caps.contextLength,
                                format = format,
                                loadRestriction = inspection.loadRestriction
                            )
                            // The model now lives in app storage, so the grant taken to copy it is
                            // never used again; holding it would leak one of the app's limited grants.
                            withContext(NonCancellable) {
                                rollbackPermissions(context, newlyAcquiredGrants)
                            }
                            return@withContext Result.success(
                                ImportResult(
                                    bundle = bundle,
                                    newlyAcquiredGrants = emptyList(),
                                    stagedArtifactItem = stagedItem,
                                    draftStagingDir = stagingDir
                                )
                            )
                        }
                        is FileHeaderSniffer.SniffResult.ValidGguf -> {
                            runCatching {
                                android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                            }
                        }
                    }

                    // 5. Sole authoritative native GGUF inspection via /proc/self/fd/<fd>
                    val fdPath = "/proc/self/fd/${pfd.fd}"
                    val inspection = try {
                        LlamaCppEngine.inspectModel(fdPath)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Native GGUF inspection threw exception for $displayName: ${t.message}")
                        null
                    }

                // Verify valid, supported, and no error
                if (inspection == null || !inspection.valid || !inspection.isSupported || inspection.error.isNotBlank()) {
                    if (onGrantAcquired == null) {
                        rollbackPermissions(context, newlyAcquiredGrants)
                    }
                    val reason = when {
                        inspection == null -> ModelErrorReason.EmptyOrDamagedHeader
                        !inspection.valid -> ModelErrorReason.EmptyOrDamagedHeader
                        !inspection.isSupported -> ModelErrorReason.UnsupportedFormat
                        else -> ModelErrorReason.UnsupportedFormat
                    }
                    val errorDetail = when {
                        inspection == null -> "Native inspection failed to run"
                        inspection.error.isNotBlank() -> inspection.error
                        !inspection.valid -> "Invalid or corrupted GGUF format"
                        !inspection.isSupported -> "Architecture '${inspection.architecture}' is not supported by runtime"
                        else -> "Unsupported model"
                    }
                    return@withContext Result.failure(
                        ModelOperationError(
                            reason = reason,
                            stage = ModelOperationStage.Inspection,
                            affectedFilenames = listOf(displayName),
                            architectureOrFormat = inspection?.architecture,
                            diagnosticDetails = "'$displayName' is not supported by native GGUF engine: $errorDetail."
                        )
                    )
                }

                val lowerArch = inspection.architecture.lowercase(Locale.ROOT)
                val lowerFile = displayName.lowercase(Locale.ROOT)
                val isProj = inspection.isProjector || lowerArch == "clip" || lowerArch == "projector" ||
                    lowerFile.contains("mmproj") || lowerFile.contains("projector")

                val header = GgufMetadataHeader(
                    validGguf = true,
                    architecture = inspection.architecture,
                    name = inspection.name.ifBlank { displayName.substringBeforeLast('.') },
                    splitNo = inspection.splitNo,
                    splitCount = inspection.splitCount,
                    isProjector = isProj,
                    hasVision = inspection.clipHasVisionEncoder,
                    hasAudio = inspection.clipHasAudioEncoder,
                    trainContextLength = inspection.trainContextLength,
                    thinkingCapability = inspection.thinkingCapability
                )

                inspections.add(UriInspectionResult(uri, displayName, sizeBytes, lastModified, header))
                } finally {
                    runCatching { pfd.close() }
                }
            }

            // 5. Delegate bundle validation and capability derivation to shared GgufBundleInspector
            val ggufInspections = inspections.map { item ->
                GgufArtifactInspection(
                    location = item.uri.toString(),
                    displayName = item.displayName,
                    sizeBytes = item.sizeBytes,
                    valid = item.header.validGguf,
                    isSupported = true,
                    architecture = item.header.architecture,
                    name = item.header.name,
                    splitNo = item.header.splitNo,
                    splitCount = item.header.splitCount,
                    isProjector = item.header.isProjector,
                    hasVision = item.header.hasVision,
                    hasAudio = item.header.hasAudio,
                    trainContextLength = item.header.trainContextLength,
                    thinkingCapability = item.header.thinkingCapability
                )
            }

            val bundleValidation = GgufBundleInspector.validateBundle(ggufInspections)
            if (!bundleValidation.isValid) {
                if (onGrantAcquired == null) {
                    rollbackPermissions(context, newlyAcquiredGrants)
                }
                val structured = bundleValidation.structuredError ?: ModelOperationError(
                    reason = ModelErrorReason.Unknown,
                    stage = ModelOperationStage.Inspection,
                    affectedFilenames = inspections.map { it.displayName },
                    diagnosticDetails = bundleValidation.errorMessage ?: "Bundle validation failed"
                )
                return@withContext Result.failure(structured)
            }

            val inspectionMap = inspections.associateBy { it.uri.toString() }
            val orderedMain = bundleValidation.orderedMainArtifacts.mapNotNull { inspectionMap[it.location] }
            val projector = bundleValidation.projectorArtifact?.let { inspectionMap[it.location] }

            // 6. Build model artifacts with actual provider modification and size metadata
            val mainArtifacts = orderedMain.mapIndexed { idx, item ->
                ModelArtifact(
                    role = if (orderedMain.size > 1) ArtifactRole.Shard else ArtifactRole.Main,
                    displayName = item.displayName,
                    source = ArtifactSource.PersistedUri(item.uri.toString()),
                    sizeBytes = item.sizeBytes,
                    lastModified = item.lastModified
                )
            }

            val projectorArtifact = projector?.let {
                ModelArtifact(
                    role = ArtifactRole.Projector,
                    displayName = it.displayName,
                    source = ArtifactSource.PersistedUri(it.uri.toString()),
                    sizeBytes = it.sizeBytes,
                    lastModified = it.lastModified
                )
            }

            val capabilities = bundleValidation.capabilities
            val primaryName = bundleValidation.primaryName

            val stableId = generateStorageModelId(orderedMain.map { it.uri.toString() })

            val bundle = ModelBundle(
                id = stableId,
                displayName = primaryName,
                mainArtifacts = mainArtifacts,
                projectorArtifact = projectorArtifact,
                capabilities = capabilities,
                description = "GGUF model imported from phone storage.",
                backend = "llama.cpp GGUF",
                contextLength = bundleValidation.contextLength.takeIf { it > 0 } ?: capabilities.contextLength
            )

            // Only files the bundle references are read later. Release the grants this call took
            // for any other selected file; grants that existed before the import are left alone.
            val referencedUris = (orderedMain.map { it.uri } + listOfNotNull(projector?.uri)).toSet()
            val unusedGrants = newlyAcquiredGrants.filterNot { it in referencedUris }
            if (unusedGrants.isNotEmpty()) {
                withContext(NonCancellable) {
                    rollbackPermissions(context, unusedGrants)
                }
            }
            Result.success(ImportResult(bundle = bundle, newlyAcquiredGrants = newlyAcquiredGrants.filter { it in referencedUris }))
        } catch (t: Throwable) {
            if (onGrantAcquired == null) {
                withContext(NonCancellable) {
                    rollbackPermissions(context, newlyAcquiredGrants)
                }
            }
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(t)
        }
    }

    fun rollbackPermissions(context: Context, uris: List<Uri>) {
        for (uri in uris) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                Log.w(TAG, "Failed to release persistable URI permission for $uri: ${it.message}")
            }
        }
    }

    fun generateStorageModelId(uriStrings: List<String>): String {
        val payload = uriStrings.sorted().joinToString("|").toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return "local_gguf_$hex"
    }
}

package com.shounak.localmeshai.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.shounak.localmeshai.ai.ShutdownResult
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.CustomModelStore
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DeletionRecord(
    val modelId: String,
    val generation: Long = 1L,
    val isPending: Boolean = true,
    val canonicalArtifactPaths: List<String> = emptyList(),
    val isStorageImport: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("modelId", modelId)
        put("generation", generation)
        put("isPending", isPending)
        put("canonicalArtifactPaths", JSONArray(canonicalArtifactPaths))
        put("isStorageImport", isStorageImport)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): DeletionRecord {
            val paths = mutableListOf<String>()
            val arr = json.optJSONArray("canonicalArtifactPaths")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    paths.add(arr.getString(i))
                }
            }
            return DeletionRecord(
                modelId = json.getString("modelId"),
                generation = json.optLong("generation", 1L),
                isPending = json.optBoolean("isPending", true),
                canonicalArtifactPaths = paths,
                isStorageImport = json.optBoolean("isStorageImport", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

sealed class RemovalResult {
    object Success : RemovalResult()
    data class Failure(val cause: Throwable) : RemovalResult()
    val isSuccess: Boolean get() = this is Success
    val error: Throwable? get() = (this as? Failure)?.cause
}

object ModelRemovalCoordinator {
    private const val TAG = "ModelRemovalCoordinator"
    private const val DELETIONS_FILE_NAME = "solus_model_deletions.json"

    private val lock = Any()
    private val inMemoryRecords = ConcurrentHashMap<String, DeletionRecord>()
    private val reservedModelIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var isInitialized = false
    @Volatile private var appContext: Context? = null

    @Volatile private var isRecoveryRequired = false
    val recoveryRequired: Boolean get() = isRecoveryRequired

    fun reserve(modelId: String): Boolean {
        return reservedModelIds.add(modelId)
    }

    fun unreserve(modelId: String) {
        reservedModelIds.remove(modelId)
    }

    fun isReserved(modelId: String): Boolean {
        return reservedModelIds.contains(modelId)
    }

    fun isModelDeleted(modelId: String): Boolean {
        val record = inMemoryRecords[modelId] ?: return false
        return !record.isPending
    }

    fun isModelDeletionPendingOrCompleted(modelId: String): Boolean {
        return inMemoryRecords.containsKey(modelId)
    }

    fun getDeletionRecord(modelId: String): DeletionRecord? {
        return inMemoryRecords[modelId]
    }

    private fun getStorageFile(context: Context): File {
        return File(context.filesDir, DELETIONS_FILE_NAME)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            val file = getStorageFile(context)
            val atomicFile = AtomicJsonFile<List<DeletionRecord>>(file)
            when (val res = atomicFile.read { content ->
                val arr = JSONArray(content)
                (0 until arr.length()).map { DeletionRecord.fromJson(arr.getJSONObject(it)) }
            }) {
                is AtomicReadResult.Success -> {
                    res.data.forEach { inMemoryRecords[it.modelId] = it }
                }
                is AtomicReadResult.Corrupt -> {
                    Log.e(TAG, "Corrupt model deletions journal: ${res.cause.message}", res.cause)
                    isRecoveryRequired = true
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    Log.e(TAG, "Unsupported version in model deletions journal: ${res.foundVersion}")
                    isRecoveryRequired = true
                }
                AtomicReadResult.Missing -> {
                    // Normal when no journal exists yet
                }
            }
            isInitialized = true
        }
    }

    private fun persistWithMutation(context: Context, mutate: (MutableMap<String, DeletionRecord>) -> Unit): Boolean {
        synchronized(lock) {
            val file = getStorageFile(context)
            val staged = HashMap(inMemoryRecords)
            mutate(staged)
            val atomicFile = AtomicJsonFile<Collection<DeletionRecord>>(file)
            val result = atomicFile.write(staged.values) { records ->
                val arr = JSONArray()
                records.forEach { arr.put(it.toJson()) }
                arr.toString()
            }
            if (result.isSuccess) {
                inMemoryRecords.clear()
                inMemoryRecords.putAll(staged)
                return true
            } else {
                Log.e(TAG, "Failed to persist deletion records", result.exceptionOrNull())
                return false
            }
        }
    }

    fun recordPendingDeletion(context: Context, record: DeletionRecord): Boolean {
        init(context)
        return persistWithMutation(context) { map ->
            map[record.modelId] = record
        }
    }

    fun recordPendingDeletion(record: DeletionRecord): Boolean {
        val ctx = appContext
        return if (ctx != null) {
            recordPendingDeletion(ctx, record)
        } else {
            inMemoryRecords[record.modelId] = record
            true
        }
    }

    fun markDeletionComplete(context: Context, modelId: String): Boolean {
        init(context)
        return persistWithMutation(context) { map ->
            val existing = map[modelId]
            if (existing != null) {
                map[modelId] = existing.copy(isPending = false)
            }
        }
    }

    fun clearDeletionRecord(context: Context, modelId: String): Boolean {
        init(context)
        return persistWithMutation(context) { map ->
            map.remove(modelId)
        }
    }

    fun clearDeletionRecord(modelId: String): Boolean {
        val ctx = appContext
        return if (ctx != null) {
            clearDeletionRecord(ctx, modelId)
        } else {
            inMemoryRecords.remove(modelId) != null
        }
    }

    suspend fun executeRemovalSequence(
        context: Context,
        model: ModelInfo,
        customModelStore: CustomModelStore,
        downloader: ModelDownloader,
        isModelSelected: (String) -> Boolean,
        clearSelection: () -> Unit,
        retainedModels: List<ModelInfo>
    ): RemovalResult = withContext(Dispatchers.IO) {
        init(context)
        if (isRecoveryRequired) {
            return@withContext RemovalResult.Failure(
                IllegalStateException("Model deletions journal requires recovery before modifying models.")
            )
        }
        if (!reserve(model.id)) {
            return@withContext RemovalResult.Failure(
                IllegalStateException("Model ${model.id} is already reserved for removal or busy.")
            )
        }
        try {
            // 1. Reserve and obtain truthful runtime shutdown if model is active
            if (isModelSelected(model.id)) {
                val shutdownResult = withTimeoutOrNull(10_000L) {
                    ModelRuntimeCoordinator.releaseAll()
                } ?: ShutdownResult.TimedOut
                if (shutdownResult !is ShutdownResult.Success) {
                    return@withContext RemovalResult.Failure(
                        IllegalStateException("Model runtime release failed ($shutdownResult). Model is in an unresolved native state.")
                    )
                }
                clearSelection()
            }

            // 2. Identify owned app-managed artifact paths
            val safeName = runCatching { ModelArtifactStorage.sanitizeFileName(model.fileName) }.getOrNull()
            val sanitizedId = runCatching { ModelArtifactStorage.sanitizeId(model.id) }.getOrNull() ?: model.id
            val isolatedDir = downloader.artifactStorage.getModelDir(sanitizedId, create = false)
            val stagingDir = downloader.artifactStorage.getStagingDir(sanitizedId)

            val canonicalPaths = mutableListOf<String>()
            if (isolatedDir.exists()) {
                canonicalPaths.add(isolatedDir.canonicalPath)
            }
            if (stagingDir.exists()) {
                canonicalPaths.add(stagingDir.canonicalPath)
            }

            // Check if legacy file exists and whether it is shared
            if (safeName != null) {
                val legacy = downloader.modelRoot.resolve(safeName)
                if (legacy.exists() && legacy.isFile) {
                    val isShared = retainedModels.any { other ->
                        other.fileName.equals(model.fileName, ignoreCase = true) ||
                            (other.localPath != null && File(other.localPath).canonicalPath == legacy.canonicalPath)
                    }
                    if (!isShared && !ModelArtifactStorage.isArtifactLeased(legacy) && legacy.canonicalPath.startsWith(downloader.modelRoot.canonicalPath)) {
                        canonicalPaths.add(legacy.canonicalPath)
                    }
                }
            }

            // 3. Durably record pending deletion before any destructive operations
            val existingRecord = inMemoryRecords[model.id]
            val nextGen = (existingRecord?.generation ?: 0L) + 1L
            val pendingRecord = DeletionRecord(
                modelId = model.id,
                generation = nextGen,
                isPending = true,
                canonicalArtifactPaths = canonicalPaths,
                isStorageImport = model.isStorageImport
            )
            val recorded = recordPendingDeletion(context, pendingRecord)
            if (!recorded) {
                return@withContext RemovalResult.Failure(
                    IllegalStateException("Failed to write durable pending deletion record")
                )
            }

            // 4. Cancel any ongoing downloads
            com.shounak.localmeshai.services.DownloadController.getInstance(context).removeModel(model.id)

            // 3. Remove custom definition durably
            val customDef = customModelStore.getRawById(model.id)
            val shouldRemoveFromStore = customDef != null || model.isCustom || model.isStorageImport || model.id.startsWith("custom_")
            if (shouldRemoveFromStore) {
                when (val removalRes = customModelStore.removeResult(model.id)) {
                    is com.shounak.localmeshai.models.StoreRemovalResult.Failed -> {
                        return@withContext RemovalResult.Failure(
                            IllegalStateException("Failed to remove model definition from persistent store: ${removalRes.error.message}")
                        )
                    }
                    is com.shounak.localmeshai.models.StoreRemovalResult.Removed,
                    is com.shounak.localmeshai.models.StoreRemovalResult.AlreadyAbsent -> {
                        // Successfully removed or already absent
                    }
                }
            }

            // Clear download store
            DownloadStateStore.remove(model.id)

            // Delete app-owned files and directories
            if (!model.isStorageImport) {
                var fileDeletionFailed = false
                var failedPath: String? = null
                for (path in canonicalPaths) {
                    val f = File(path)
                    if (f.exists()) {
                        val deleted = ModelArtifactStorage.reserveAndExecuteDeletion(f) {
                            if (it.isDirectory) it.deleteRecursively() else it.delete()
                        }
                        if (!deleted && f.exists()) {
                            fileDeletionFailed = true
                            failedPath = path
                            break
                        }
                    }
                }
                if (fileDeletionFailed) {
                    return@withContext RemovalResult.Failure(
                        java.io.IOException("Failed to delete model artifact: $failedPath. Pending deletion retained.")
                    )
                }
            } else {
                // Phone import: release persistable URI permissions if not shared
                val bundleUris = (customDef?.bundle ?: model.bundle)?.allArtifacts?.mapNotNull {
                    (it.source as? ArtifactSource.PersistedUri)?.uriString
                } ?: emptyList()

                if (bundleUris.isNotEmpty()) {
                    val otherUris = retainedModels.flatMap { other ->
                        other.bundle?.allArtifacts?.mapNotNull {
                            (it.source as? ArtifactSource.PersistedUri)?.uriString
                        } ?: emptyList()
                    }.toSet()

                    for (uriString in bundleUris) {
                        if (!otherUris.contains(uriString)) {
                            runCatching {
                                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                context.contentResolver.releasePersistableUriPermission(Uri.parse(uriString), flags)
                            }
                        }
                    }
                }
            }

            // 4. Mark deletion complete durably
            val marked = markDeletionComplete(context, model.id)
            if (!marked) {
                return@withContext RemovalResult.Failure(
                    IllegalStateException("Model files deleted but marking durable completion failed")
                )
            }

            RemovalResult.Success
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing removal sequence for ${model.id}", t)
            RemovalResult.Failure(t)
        } finally {
            unreserve(model.id)
        }
    }

    suspend fun recoverInterruptedDeletions(
        context: Context,
        customModelStore: CustomModelStore,
        downloader: ModelDownloader
    ) = withContext(Dispatchers.IO) {
        init(context)
        val pending = inMemoryRecords.values.filter { it.isPending }
        for (record in pending) {
            Log.i(TAG, "Recovering interrupted deletion for ${record.modelId}")
            try {
                // Generation-aware guard: if a newer import exists, do not delete it!
                val currentRawDef = customModelStore.getRawById(record.modelId)
                val currentAddedAt = currentRawDef?.addedAt ?: currentRawDef?.bundle?.addedAt ?: 0L
                if (currentRawDef != null && currentAddedAt > record.timestamp) {
                    Log.i(TAG, "Skipping deletion recovery for ${record.modelId} as newer import exists")
                    markDeletionComplete(context, record.modelId)
                    continue
                }

                // Clear store and download state
                val storeRes = customModelStore.removeResult(record.modelId)
                if (storeRes is com.shounak.localmeshai.models.StoreRemovalResult.Failed) {
                    Log.e(TAG, "Failed store removal during recovery for ${record.modelId}: ${storeRes.error}")
                    continue
                }
                DownloadStateStore.remove(record.modelId)

                // Delete owned paths
                var filesFailed = false
                if (!record.isStorageImport) {
                    for (path in record.canonicalArtifactPaths) {
                        val f = File(path)
                        if (f.exists()) {
                            val deleted = ModelArtifactStorage.reserveAndExecuteDeletion(f) {
                                if (it.isDirectory) it.deleteRecursively() else it.delete()
                            }
                            if (!deleted && f.exists()) {
                                filesFailed = true
                            }
                        }
                    }
                }
                if (!filesFailed) {
                    markDeletionComplete(context, record.modelId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover deletion for ${record.modelId}", e)
            }
        }
    }
}

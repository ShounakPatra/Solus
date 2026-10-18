package com.shounak.localmeshai.models

import com.shounak.localmeshai.data.StoreLoadState
import com.shounak.localmeshai.utils.AtomicFileTransaction
import com.shounak.localmeshai.utils.AtomicJsonFile
import com.shounak.localmeshai.utils.AtomicReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Immutable definition of a user-added custom model.
 * Does not contain transient progress or authentication tokens.
 */
data class CustomModelDefinition(
    val id: String,
    val name: String,
    val url: String = "",
    val type: ModelType,
    val fileName: String,
    val packageType: ModelPackage = ModelPackage.SingleFile,
    val sha256: String? = null,
    val description: String = "",
    val backend: String = "",
    val deviceTarget: String = "All supported chipsets",
    val addedAt: Long = System.currentTimeMillis(),
    val bundle: ModelBundle? = null,
    val downloadSpec: ModelDownloadSpec? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("type", type.name)
        put("fileName", fileName)
        put("packageType", packageType.name)
        put("sha256", sha256 ?: JSONObject.NULL)
        put("description", description)
        put("backend", backend)
        put("deviceTarget", deviceTarget)
        put("addedAt", addedAt)
        if (bundle != null) {
            put("bundle", bundle.toJson())
        }
        if (downloadSpec != null) {
            put("downloadSpec", downloadSpec.toJson())
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): CustomModelDefinition {
            val bundleObj = if (obj.has("bundle") && !obj.isNull("bundle")) {
                ModelBundle.fromJson(obj.getJSONObject("bundle"))
            } else null
            val specObj = if (obj.has("downloadSpec") && !obj.isNull("downloadSpec")) {
                ModelDownloadSpec.fromJson(obj.getJSONObject("downloadSpec"))
            } else null

            return CustomModelDefinition(
                id = obj.getString("id"),
                name = obj.getString("name"),
                url = obj.optString("url", ""),
                type = ModelType.valueOf(obj.getString("type")),
                fileName = obj.getString("fileName"),
                packageType = if (obj.has("packageType")) ModelPackage.valueOf(obj.getString("packageType")) else ModelPackage.SingleFile,
                sha256 = if (obj.isNull("sha256")) null else obj.getString("sha256"),
                description = obj.optString("description", ""),
                backend = obj.optString("backend", ""),
                deviceTarget = obj.optString("deviceTarget", "All supported chipsets"),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                bundle = bundleObj,
                downloadSpec = specObj
            )
        }
    }
}

sealed class StoreRemovalResult {
    object Removed : StoreRemovalResult()
    object AlreadyAbsent : StoreRemovalResult()
    data class Failed(val error: Throwable) : StoreRemovalResult()
    val isSuccess: Boolean get() = this is Removed || this is AlreadyAbsent
}

/**
 * Persistent store for custom model definitions.
 */
class CustomModelStore(
    private val storageDir: File,
    private val fileName: String = "solus_custom_models.json",
    private val transaction: AtomicFileTransaction? = null
) {
    private val file = File(storageDir, fileName)
    private val atomicFile = if (transaction != null) AtomicJsonFile<List<CustomModelDefinition>>(file, transaction) else AtomicJsonFile(file)

    @Volatile
    private var isLoaded: Boolean = false

    private val _loadState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val loadState: StateFlow<StoreLoadState> = _loadState.asStateFlow()

    @Volatile
    private var definitionsState: List<CustomModelDefinition> = emptyList()

    private fun ensureLoaded() {
        if (!isLoaded) {
            synchronized(globalLock) {
                if (!isLoaded) {
                    load()
                }
            }
        }
    }

    suspend fun loadAsync(): StoreLoadState = withContext(Dispatchers.IO) {
        synchronized(globalLock) {
            storageDir.mkdirs()
            when (val result = atomicFile.read { deserializeDefinitions(it) }) {
                is AtomicReadResult.Success -> {
                    definitionsState = result.data
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.Missing -> {
                    definitionsState = emptyList()
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.Corrupt -> {
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "Custom models file corrupt: ${result.cause.message}",
                        result.file
                    )
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "Custom models unsupported version ${result.foundVersion}",
                        result.file
                    )
                }
            }
            isLoaded = true
            _loadState.value
        }
    }

    fun getAll(): List<CustomModelDefinition> {
        ensureLoaded()
        synchronized(globalLock) {
            return definitionsState.filter { !com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(it.id) }
        }
    }

    fun getById(id: String): CustomModelDefinition? {
        ensureLoaded()
        synchronized(globalLock) {
            if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(id)) return null
            return definitionsState.firstOrNull { it.id == id }
        }
    }

    fun getRawById(id: String): CustomModelDefinition? {
        ensureLoaded()
        synchronized(globalLock) {
            return definitionsState.firstOrNull { it.id == id }
        }
    }

    fun add(definition: CustomModelDefinition, isExplicitReimport: Boolean = false): Boolean {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(globalLock) {
            if (_loadState.value is StoreLoadState.RecoveryRequired) {
                return false
            }
            if (!isExplicitReimport && (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(definition.id) ||
                com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(definition.id))) {
                return false
            }
            when (val refresh = atomicFile.read { deserializeDefinitions(it) }) {
                is AtomicReadResult.Success -> definitionsState = refresh.data
                is AtomicReadResult.Missing -> definitionsState = emptyList()
                is AtomicReadResult.Corrupt -> {
                    _loadState.value = StoreLoadState.RecoveryRequired("Custom models file corrupt: ${refresh.cause.message}", refresh.file)
                    return false
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    _loadState.value = StoreLoadState.RecoveryRequired("Custom models unsupported version ${refresh.foundVersion}", refresh.file)
                    return false
                }
            }
            val existingIndex = definitionsState.indexOfFirst { it.id == definition.id }
            val nextList = if (existingIndex >= 0) {
                definitionsState.toMutableList().apply { set(existingIndex, definition) }
            } else {
                definitionsState + definition
            }
            val result = atomicFile.write(nextList) { serializeDefinitions(it) }
            if (result.isSuccess) {
                if (isExplicitReimport) {
                    val cleared = com.shounak.localmeshai.utils.ModelRemovalCoordinator.clearDeletionRecord(definition.id)
                    if (!cleared) {
                        atomicFile.write(definitionsState) { serializeDefinitions(it) }
                        return false
                    }
                }
                definitionsState = nextList
                return true
            }
            return false
        }
    }

    fun updateBundle(id: String, updatedBundle: ModelBundle): Boolean {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(globalLock) {
            if (_loadState.value is StoreLoadState.RecoveryRequired) {
                return false
            }
            when (val refresh = atomicFile.read { deserializeDefinitions(it) }) {
                is AtomicReadResult.Success -> definitionsState = refresh.data
                is AtomicReadResult.Missing -> definitionsState = emptyList()
                is AtomicReadResult.Corrupt -> {
                    _loadState.value = StoreLoadState.RecoveryRequired("Custom models file corrupt: ${refresh.cause.message}", refresh.file)
                    return false
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    _loadState.value = StoreLoadState.RecoveryRequired("Custom models unsupported version ${refresh.foundVersion}", refresh.file)
                    return false
                }
            }
            val existingIndex = definitionsState.indexOfFirst { it.id == id }
            if (existingIndex < 0) return false
            val existing = definitionsState[existingIndex]
            val updated = existing.copy(
                bundle = updatedBundle.copy(id = id),
                name = updatedBundle.displayName.ifBlank { existing.name },
                fileName = updatedBundle.mainArtifacts.firstOrNull()?.displayName ?: existing.fileName,
                type = if (updatedBundle.capabilities.supportsVision || updatedBundle.capabilities.supportsAudio) ModelType.Vision else ModelType.Text,
                description = updatedBundle.description.ifBlank { existing.description },
                backend = updatedBundle.backend.ifBlank { existing.backend }
            )
            val nextList = definitionsState.toMutableList().apply { set(existingIndex, updated) }
            val result = atomicFile.write(nextList) { serializeDefinitions(it) }
            if (result.isSuccess) {
                definitionsState = nextList
                return true
            }
            return false
        }
    }

    fun removeResult(id: String): StoreRemovalResult {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(globalLock) {
            if (_loadState.value is StoreLoadState.RecoveryRequired) {
                return StoreRemovalResult.Failed(IllegalStateException("Store recovery required"))
            }
            when (val refresh = atomicFile.read { deserializeDefinitions(it) }) {
                is AtomicReadResult.Success -> definitionsState = refresh.data
                is AtomicReadResult.Missing -> definitionsState = emptyList()
                is AtomicReadResult.Corrupt -> {
                    _loadState.value = StoreLoadState.RecoveryRequired("Custom models file corrupt: ${refresh.cause.message}", refresh.file)
                    return StoreRemovalResult.Failed(refresh.cause)
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    _loadState.value = StoreLoadState.RecoveryRequired("Custom models unsupported version ${refresh.foundVersion}", refresh.file)
                    return StoreRemovalResult.Failed(IllegalStateException("Unsupported version ${refresh.foundVersion}"))
                }
            }
            val existingIndex = definitionsState.indexOfFirst { it.id == id }
            if (existingIndex < 0) return StoreRemovalResult.AlreadyAbsent
            val nextList = definitionsState.filter { it.id != id }
            val result = atomicFile.write(nextList) { serializeDefinitions(it) }
            if (result.isSuccess) {
                definitionsState = nextList
                return StoreRemovalResult.Removed
            }
            return StoreRemovalResult.Failed(result.exceptionOrNull() ?: java.io.IOException("Failed to write updated custom models"))
        }
    }

    fun remove(id: String): Boolean = removeResult(id).isSuccess

    fun clear(): Boolean {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(globalLock) {
            val nextList = emptyList<CustomModelDefinition>()
            val result = atomicFile.write(nextList) { serializeDefinitions(it) }
            if (result.isSuccess) {
                definitionsState = nextList
                return true
            }
            return false
        }
    }

    fun load() {
        synchronized(globalLock) {
            storageDir.mkdirs()
            when (val result = atomicFile.read { deserializeDefinitions(it) }) {
                is AtomicReadResult.Success -> {
                    definitionsState = result.data
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.Missing -> {
                    definitionsState = emptyList()
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.Corrupt -> {
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "Custom models file corrupt: ${result.cause.message}",
                        result.file
                    )
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "Custom models unsupported version ${result.foundVersion}",
                        result.file
                    )
                }
            }
            isLoaded = true
        }
    }

    companion object {
        val globalLock = Any()
        /**
         * Generates a stable deterministic custom model ID using SHA-256 of the normalized URL and model type.
         */
        fun generateCustomModelId(normalizedUrl: String, type: ModelType): String {
            val payload = "${normalizedUrl.trim()}|${type.name}".toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            val hex = digest.take(16).joinToString("") { "%02x".format(it) }
            return "custom_${type.name.lowercase(Locale.ROOT)}_$hex"
        }

        fun serializeDefinitions(definitions: List<CustomModelDefinition>): String {
            val array = JSONArray()
            definitions.forEach { array.put(it.toJson()) }
            return array.toString(2)
        }

        fun deserializeDefinitions(jsonStr: String): List<CustomModelDefinition> {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<CustomModelDefinition>()
            for (i in 0 until array.length()) {
                list.add(CustomModelDefinition.fromJson(array.getJSONObject(i)))
            }
            return list
        }
    }
}

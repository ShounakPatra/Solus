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
    val url: String,
    val type: ModelType,
    val fileName: String,
    val packageType: ModelPackage = ModelPackage.SingleFile,
    val sha256: String? = null,
    val description: String = "",
    val backend: String = "",
    val deviceTarget: String = "All supported chipsets",
    val addedAt: Long = System.currentTimeMillis()
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
    }

    companion object {
        fun fromJson(obj: JSONObject): CustomModelDefinition {
            return CustomModelDefinition(
                id = obj.getString("id"),
                name = obj.getString("name"),
                url = obj.getString("url"),
                type = ModelType.valueOf(obj.getString("type")),
                fileName = obj.getString("fileName"),
                packageType = if (obj.has("packageType")) ModelPackage.valueOf(obj.getString("packageType")) else ModelPackage.SingleFile,
                sha256 = if (obj.isNull("sha256")) null else obj.getString("sha256"),
                description = obj.optString("description", ""),
                backend = obj.optString("backend", ""),
                deviceTarget = obj.optString("deviceTarget", "All supported chipsets"),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis())
            )
        }
    }
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
    private val lock = Any()

    @Volatile
    private var isLoaded: Boolean = false

    private val _loadState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    val loadState: StateFlow<StoreLoadState> = _loadState.asStateFlow()

    @Volatile
    private var definitionsState: List<CustomModelDefinition> = emptyList()

    private fun ensureLoaded() {
        if (!isLoaded) {
            synchronized(lock) {
                if (!isLoaded) {
                    load()
                }
            }
        }
    }

    suspend fun loadAsync(): StoreLoadState = withContext(Dispatchers.IO) {
        synchronized(lock) {
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
        return definitionsState
    }

    fun getById(id: String): CustomModelDefinition? {
        ensureLoaded()
        return definitionsState.firstOrNull { it.id == id }
    }

    fun add(definition: CustomModelDefinition): Boolean {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(lock) {
            val existingIndex = definitionsState.indexOfFirst { it.id == definition.id }
            val nextList = if (existingIndex >= 0) {
                definitionsState.toMutableList().apply { set(existingIndex, definition) }
            } else {
                definitionsState + definition
            }
            val result = atomicFile.write(nextList) { serializeDefinitions(it) }
            if (result.isSuccess) {
                definitionsState = nextList
                return true
            }
            return false
        }
    }

    fun remove(id: String): Boolean {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(lock) {
            val nextList = definitionsState.filter { it.id != id }
            if (nextList.size == definitionsState.size) return false
            val result = atomicFile.write(nextList) { serializeDefinitions(it) }
            if (result.isSuccess) {
                definitionsState = nextList
                return true
            }
            return false
        }
    }

    fun clear(): Boolean {
        ensureLoaded()
        storageDir.mkdirs()
        synchronized(lock) {
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
        synchronized(lock) {
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

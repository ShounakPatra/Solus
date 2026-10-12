package com.shounak.localmeshai.memory

import android.content.Context
import com.shounak.localmeshai.data.StoreLoadState
import com.shounak.localmeshai.utils.AtomicFileTransaction
import com.shounak.localmeshai.utils.AtomicJsonFile
import com.shounak.localmeshai.utils.AtomicReadResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Interface for persistent memory storage.
 * Completely independent of RAG vector storage and LLM engines.
 */
interface MemoryStore {
    val loadState: StateFlow<StoreLoadState> get() = MutableStateFlow(StoreLoadState.Ready).asStateFlow()
    fun getAll(): List<MemoryEntry>
    fun getActive(): List<MemoryEntry>
    fun getById(id: String): MemoryEntry?
    fun add(entry: MemoryEntry): Boolean
    fun update(entry: MemoryEntry): Boolean
    fun delete(id: String): Boolean
    fun setEnabled(id: String, enabled: Boolean): Boolean
    fun clearAll()
    fun count(): Int
    fun persist()
    fun load()
    suspend fun loadAsync(): StoreLoadState = loadState.value
}

/**
 * Thread-safe persistent memory store with atomic file persistence.
 */
class PersistentMemoryStore(
    private val context: Context? = null,
    private val storageDir: File? = null,
    private val storageFileName: String = "solus_persistent_memory.json",
    private val transaction: AtomicFileTransaction? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MemoryStore {

    private val _loadState = MutableStateFlow<StoreLoadState>(StoreLoadState.Loading)
    override val loadState: StateFlow<StoreLoadState> = _loadState.asStateFlow()

    @Volatile
    private var isLoaded: Boolean = false

    @Volatile
    private var entriesState: List<MemoryEntry> = emptyList()
    private val lock = Any()

    var lastError: String? = null
        private set

    private val atomicFile: AtomicJsonFile<List<MemoryEntry>>? by lazy {
        val file = getStorageFile() ?: return@lazy null
        if (transaction != null) {
            AtomicJsonFile(file, transaction)
        } else {
            AtomicJsonFile(file)
        }
    }

    private fun ensureLoaded() {
        if (!isLoaded) {
            synchronized(lock) {
                if (!isLoaded) {
                    load()
                }
            }
        }
    }

    private fun getStorageFile(): File? {
        val dir = storageDir ?: context?.filesDir ?: return null
        return File(dir, storageFileName)
    }

    override fun getAll(): List<MemoryEntry> {
        ensureLoaded()
        return entriesState
    }

    override fun getActive(): List<MemoryEntry> {
        ensureLoaded()
        return entriesState.filter { it.isEnabled }
    }

    override fun getById(id: String): MemoryEntry? {
        ensureLoaded()
        return entriesState.firstOrNull { it.id == id }
    }

    override fun add(entry: MemoryEntry): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val trimmed = entry.content.trim()
            if (trimmed.isBlank()) return false
            if (entriesState.any { it.content.equals(trimmed, ignoreCase = true) }) {
                return false
            }
            val nextList = entriesState + entry.copy(content = trimmed)
            val success = persistSnapshot(nextList)
            if (success) {
                entriesState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun update(entry: MemoryEntry): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val index = entriesState.indexOfFirst { it.id == entry.id }
            if (index == -1) return false
            val trimmed = entry.content.trim()
            if (trimmed.isBlank()) return false
            val updated = entry.copy(content = trimmed, updatedAt = System.currentTimeMillis())
            val nextList = entriesState.toMutableList().apply { set(index, updated) }
            val success = persistSnapshot(nextList)
            if (success) {
                entriesState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun delete(id: String): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val nextList = entriesState.filter { it.id != id }
            if (nextList.size == entriesState.size) return false
            val success = persistSnapshot(nextList)
            if (success) {
                entriesState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun setEnabled(id: String, enabled: Boolean): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val index = entriesState.indexOfFirst { it.id == id }
            if (index == -1) return false
            val current = entriesState[index]
            val updated = current.copy(isEnabled = enabled, updatedAt = System.currentTimeMillis())
            val nextList = entriesState.toMutableList().apply { set(index, updated) }
            val success = persistSnapshot(nextList)
            if (success) {
                entriesState = nextList
                lastError = null
                return true
            }
            return false
        }
    }

    override fun clearAll() {
        ensureLoaded()
        synchronized(lock) {
            val nextList = emptyList<MemoryEntry>()
            val success = persistSnapshot(nextList)
            if (success) {
                entriesState = nextList
                lastError = null
            }
        }
    }

    override fun count(): Int {
        ensureLoaded()
        return entriesState.size
    }

    private fun persistSnapshot(snapshot: List<MemoryEntry>): Boolean {
        val af = atomicFile ?: return true
        val result = af.write(snapshot) { serializeEntries(it) }
        return if (result.isSuccess) {
            true
        } else {
            lastError = result.exceptionOrNull()?.message ?: "Failed to persist memory store"
            false
        }
    }

    override fun persist() {
        ensureLoaded()
        synchronized(lock) {
            persistSnapshot(entriesState)
        }
    }

    override fun load() {
        val af = atomicFile ?: run {
            _loadState.value = StoreLoadState.Ready
            isLoaded = true
            return
        }
        synchronized(lock) {
            val result = af.read { deserializeEntries(it) }
            when (result) {
                is AtomicReadResult.Success -> {
                    entriesState = result.data
                    lastError = null
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.Missing -> {
                    entriesState = emptyList()
                    lastError = null
                    _loadState.value = StoreLoadState.Ready
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    lastError = "Unsupported memory file version: ${result.foundVersion}"
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "Memory file uses newer version (${result.foundVersion} > ${result.maxSupportedVersion})",
                        result.file
                    )
                }
                is AtomicReadResult.Corrupt -> {
                    lastError = "Corrupt memory file detected: ${result.cause.message}"
                    _loadState.value = StoreLoadState.RecoveryRequired(
                        "Memory file is corrupt: ${result.cause.message}",
                        result.file
                    )
                }
            }
            isLoaded = true
        }
    }

    override suspend fun loadAsync(): StoreLoadState = withContext(ioDispatcher) {
        load()
        _loadState.value
    }

    companion object {
        fun serializeEntries(entries: List<MemoryEntry>): String {
            val jsonArray = JSONArray()
            for (entry in entries) {
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("content", entry.content)
                    put("category", entry.category.name)
                    put("isEnabled", entry.isEnabled)
                    put("createdAt", entry.createdAt)
                    put("updatedAt", entry.updatedAt)
                }
                jsonArray.put(obj)
            }
            return jsonArray.toString(2)
        }

        fun deserializeEntries(content: String): List<MemoryEntry> {
            val jsonArray = JSONArray(content)
            val list = mutableListOf<MemoryEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    MemoryEntry(
                        id = obj.getString("id"),
                        content = obj.getString("content"),
                        category = MemoryCategory.fromString(obj.optString("category", "GENERAL")),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            return list
        }
    }
}

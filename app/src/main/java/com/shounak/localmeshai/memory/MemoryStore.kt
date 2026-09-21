package com.shounak.localmeshai.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interface for persistent memory storage.
 * Completely independent of RAG vector storage and LLM engines.
 */
interface MemoryStore {
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
}

/**
 * Thread-safe persistent memory store with atomic file persistence.
 */
class PersistentMemoryStore(
    private val context: Context? = null,
    private val storageDir: File? = null,
    private val storageFileName: String = "solus_persistent_memory.json"
) : MemoryStore {

    private val entries = CopyOnWriteArrayList<MemoryEntry>()
    private val lock = Any()

    init {
        load()
    }

    private fun getStorageFile(): File? {
        val dir = storageDir ?: context?.filesDir ?: return null
        return File(dir, storageFileName)
    }

    override fun getAll(): List<MemoryEntry> {
        return entries.toList()
    }

    override fun getActive(): List<MemoryEntry> {
        return entries.filter { it.isEnabled }
    }

    override fun getById(id: String): MemoryEntry? {
        return entries.firstOrNull { it.id == id }
    }

    override fun add(entry: MemoryEntry): Boolean {
        synchronized(lock) {
            val trimmed = entry.content.trim()
            if (trimmed.isBlank()) return false
            if (entries.any { it.content.equals(trimmed, ignoreCase = true) }) {
                return false
            }
            entries.add(entry.copy(content = trimmed))
            persist()
            return true
        }
    }

    override fun update(entry: MemoryEntry): Boolean {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index == -1) return false
            val trimmed = entry.content.trim()
            if (trimmed.isBlank()) return false
            entries[index] = entry.copy(content = trimmed, updatedAt = System.currentTimeMillis())
            persist()
            return true
        }
    }

    override fun delete(id: String): Boolean {
        synchronized(lock) {
            val removed = entries.removeIf { it.id == id }
            if (removed) {
                persist()
            }
            return removed
        }
    }

    override fun setEnabled(id: String, enabled: Boolean): Boolean {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == id }
            if (index == -1) return false
            val current = entries[index]
            entries[index] = current.copy(isEnabled = enabled, updatedAt = System.currentTimeMillis())
            persist()
            return true
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            entries.clear()
            persist()
        }
    }

    override fun count(): Int = entries.size

    override fun persist() {
        val file = getStorageFile() ?: return
        try {
            val serialized = serializeEntries(entries)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(serialized)
            if (tempFile.exists()) {
                if (file.exists()) {
                    file.delete()
                }
                tempFile.renameTo(file)
            }
        } catch (_: Throwable) {
            // Gracefully handle any IO errors
        }
    }

    override fun load() {
        val file = getStorageFile() ?: return
        try {
            if (!file.exists()) return
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = deserializeEntries(content)
            synchronized(lock) {
                entries.clear()
                entries.addAll(loaded)
            }
        } catch (_: Throwable) {
            // Gracefully handle any load errors
        }
    }

    companion object {
        fun serializeEntries(entries: List<MemoryEntry>): String {
            try {
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
            } catch (_: Throwable) {
                // Fallback for JVM test environment where org.json is stubbed
            }

            val sb = StringBuilder()
            sb.append("[\n")
            for (i in entries.indices) {
                val e = entries[i]
                sb.append("  {\n")
                sb.append("    \"id\": \"").append(escape(e.id)).append("\",\n")
                sb.append("    \"content\": \"").append(escape(e.content)).append("\",\n")
                sb.append("    \"category\": \"").append(e.category.name).append("\",\n")
                sb.append("    \"isEnabled\": ").append(e.isEnabled).append(",\n")
                sb.append("    \"createdAt\": ").append(e.createdAt).append(",\n")
                sb.append("    \"updatedAt\": ").append(e.updatedAt).append("\n")
                sb.append("  }")
                if (i < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            return sb.toString()
        }

        fun deserializeEntries(content: String): List<MemoryEntry> {
            try {
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
            } catch (_: Throwable) {
                // Fallback for JVM test environment
            }

            val list = mutableListOf<MemoryEntry>()
            val objectRegex = Regex("""\{([^{}]+)\}""")
            for (match in objectRegex.findAll(content)) {
                val block = match.groupValues[1]
                val id = Regex(""""id"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(block)?.groupValues?.get(1) ?: continue
                val rawContent = Regex(""""content"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(block)?.groupValues?.get(1) ?: continue
                val categoryStr = Regex(""""category"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: "GENERAL"
                val isEnabled = Regex(""""isEnabled"\s*:\s*(true|false)""").find(block)?.groupValues?.get(1)?.toBoolean() ?: true
                val createdAt = Regex(""""createdAt"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
                val updatedAt = Regex(""""updatedAt"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()

                list.add(
                    MemoryEntry(
                        id = unescape(id),
                        content = unescape(rawContent),
                        category = MemoryCategory.fromString(categoryStr),
                        isEnabled = isEnabled,
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                )
            }
            return list
        }

        private fun escape(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun unescape(s: String): String {
            return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }
}

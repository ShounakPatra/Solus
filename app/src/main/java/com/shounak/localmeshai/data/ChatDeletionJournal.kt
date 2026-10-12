package com.shounak.localmeshai.data

import com.shounak.localmeshai.utils.AtomicJsonFile
import com.shounak.localmeshai.utils.AtomicReadResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ChatKind { Text, Vision }

data class JournalTombstone(
    val chatKind: ChatKind,
    val sessionId: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("chatKind", chatKind.name)
        put("sessionId", sessionId)
    }

    companion object {
        fun fromJson(obj: JSONObject): JournalTombstone {
            return JournalTombstone(
                chatKind = ChatKind.valueOf(obj.getString("chatKind")),
                sessionId = obj.getString("sessionId")
            )
        }
    }
}

data class DeletionJournalEntry(
    val id: String = UUID.randomUUID().toString(),
    val chatKind: ChatKind,
    val sessionId: String,
    val ownedResourcePaths: List<String>,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("chatKind", chatKind.name)
        put("sessionId", sessionId)
        put("ownedResourcePaths", JSONArray(ownedResourcePaths))
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): DeletionJournalEntry {
            val paths = mutableListOf<String>()
            val arr = obj.optJSONArray("ownedResourcePaths")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    paths.add(arr.getString(i))
                }
            }
            return DeletionJournalEntry(
                id = obj.getString("id"),
                chatKind = ChatKind.valueOf(obj.getString("chatKind")),
                sessionId = obj.getString("sessionId"),
                ownedResourcePaths = paths,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

internal data class JournalDocument(
    val version: Int = 2,
    val pending: List<DeletionJournalEntry> = emptyList(),
    val tombstones: List<JournalTombstone> = emptyList()
)

class ChatDeletionJournal(
    val rootDir: File
) {
    private val journalFile = File(rootDir, "chat_deletion_journal.json")
    private val atomicFile = AtomicJsonFile<JournalDocument>(journalFile)
    private val lock = ReentrantLock()

    // Process-wide tombstones to reject saves and hide deleted chats across loads
    private val tombstones = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var isLoaded = false
    @Volatile private var hasCorruptData = false

    // Disk-free constructor: NO rootDir.mkdirs() or loadTombstones() in init
    init {
        // Disk-free: lazy initialization upon first access
    }

    private fun tombstoneKey(chatKind: ChatKind, sessionId: String): String =
        "${chatKind.name}:$sessionId"

    fun ensureLoaded() = lock.withLock {
        if (isLoaded) return@withLock
        loadJournalInternal()
        isLoaded = true
    }

    fun hasRecoveryError(): Boolean = hasCorruptData

    fun isTombstoned(chatKind: ChatKind, sessionId: String): Boolean {
        if (!isLoaded) ensureLoaded()
        return tombstones.contains(tombstoneKey(chatKind, sessionId))
    }

    fun markTombstoned(chatKind: ChatKind, sessionId: String) {
        if (!isLoaded) ensureLoaded()
        tombstones.add(tombstoneKey(chatKind, sessionId))
    }

    private fun loadJournalInternal() {
        if (!journalFile.exists()) return
        when (val res = atomicFile.read { decodeDocument(it) }) {
            is AtomicReadResult.Success -> {
                hasCorruptData = false
                res.data.tombstones.forEach {
                    tombstones.add(tombstoneKey(it.chatKind, it.sessionId))
                }
                res.data.pending.forEach {
                    tombstones.add(tombstoneKey(it.chatKind, it.sessionId))
                }
            }
            is AtomicReadResult.Corrupt, is AtomicReadResult.UnsupportedVersion -> {
                hasCorruptData = true
                // Never overwrite corrupt data
            }
            is AtomicReadResult.Missing -> {
                // Not found, empty
            }
        }
    }

    fun recordPendingDeletion(entry: DeletionJournalEntry): Boolean = lock.withLock {
        ensureLoaded()
        if (hasCorruptData) {
            // Cannot safely record into a corrupted journal without risking data loss
            return false
        }
        rootDir.mkdirs()
        markTombstoned(entry.chatKind, entry.sessionId)
        val currentDoc = readCurrentDoc()
        val nextPending = currentDoc.pending.filter { it.sessionId != entry.sessionId || it.chatKind != entry.chatKind } + entry
        val nextTombstones = (currentDoc.tombstones.filter { it.sessionId != entry.sessionId || it.chatKind != entry.chatKind } +
                JournalTombstone(entry.chatKind, entry.sessionId)).distinct()

        val nextDoc = JournalDocument(version = 2, pending = nextPending, tombstones = nextTombstones)
        val writeResult = atomicFile.write(nextDoc) { encodeDocument(it) }
        writeResult.isSuccess
    }

    fun removeEntry(id: String): Boolean = lock.withLock {
        ensureLoaded()
        if (hasCorruptData) return false
        val currentDoc = readCurrentDoc()
        val nextPending = currentDoc.pending.filter { it.id != id }
        // Keep tombstones durably in the file!
        val nextDoc = currentDoc.copy(pending = nextPending)
        val writeResult = atomicFile.write(nextDoc) { encodeDocument(it) }
        writeResult.isSuccess
    }

    fun getPendingEntries(): List<DeletionJournalEntry> = lock.withLock {
        ensureLoaded()
        if (hasCorruptData) return emptyList()
        readCurrentDoc().pending
    }

    fun getAllTombstones(): List<JournalTombstone> = lock.withLock {
        ensureLoaded()
        if (hasCorruptData) return emptyList()
        readCurrentDoc().tombstones
    }

    fun reconcilePendingDeletions(onDeletePath: (String) -> Boolean): Boolean = lock.withLock {
        ensureLoaded()
        if (hasCorruptData) return false
        val pending = getPendingEntries()
        var allClean = true
        for (entry in pending) {
            var entryClean = true
            for (path in entry.ownedResourcePaths) {
                val ok = onDeletePath(path)
                if (!ok) {
                    entryClean = false
                    allClean = false
                }
            }
            if (entryClean) {
                removeEntry(entry.id)
            }
        }
        allClean
    }

    private fun readCurrentDoc(): JournalDocument {
        if (!journalFile.exists()) return JournalDocument()
        return when (val res = atomicFile.read { decodeDocument(it) }) {
            is AtomicReadResult.Success -> res.data
            else -> JournalDocument()
        }
    }

    private fun encodeDocument(doc: JournalDocument): String {
        val root = JSONObject()
        root.put("version", 2)
        val pendingArr = JSONArray()
        doc.pending.forEach { pendingArr.put(it.toJson()) }
        root.put("pending", pendingArr)

        val tombstonesArr = JSONArray()
        doc.tombstones.forEach { tombstonesArr.put(it.toJson()) }
        root.put("tombstones", tombstonesArr)
        return root.toString(2)
    }

    private fun decodeDocument(jsonStr: String): JournalDocument {
        val trimmed = jsonStr.trim()
        if (trimmed.isBlank()) return JournalDocument()

        if (trimmed.startsWith("[")) {
            // Legacy format: Array of DeletionJournalEntry
            val arr = JSONArray(trimmed)
            val pendingList = mutableListOf<DeletionJournalEntry>()
            val tombstoneList = mutableListOf<JournalTombstone>()
            for (i in 0 until arr.length()) {
                val entry = DeletionJournalEntry.fromJson(arr.getJSONObject(i))
                pendingList.add(entry)
                tombstoneList.add(JournalTombstone(entry.chatKind, entry.sessionId))
            }
            return JournalDocument(version = 1, pending = pendingList, tombstones = tombstoneList)
        } else {
            val root = JSONObject(trimmed)
            val ver = root.optInt("version", 2)
            val pendingList = mutableListOf<DeletionJournalEntry>()
            val pendingArr = root.optJSONArray("pending")
            if (pendingArr != null) {
                for (i in 0 until pendingArr.length()) {
                    pendingList.add(DeletionJournalEntry.fromJson(pendingArr.getJSONObject(i)))
                }
            }
            val tombstoneList = mutableListOf<JournalTombstone>()
            val tombstonesArr = root.optJSONArray("tombstones")
            if (tombstonesArr != null) {
                for (i in 0 until tombstonesArr.length()) {
                    tombstoneList.add(JournalTombstone.fromJson(tombstonesArr.getJSONObject(i)))
                }
            }
            return JournalDocument(version = ver, pending = pendingList, tombstones = tombstoneList)
        }
    }
}

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

private fun JSONObject.requiredJournalString(name: String): String {
    val value = get(name)
    require(value is String && value.isNotBlank()) { "Journal field $name must be a nonblank string" }
    return value
}

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
                chatKind = ChatKind.valueOf(obj.requiredJournalString("chatKind")),
                sessionId = obj.requiredJournalString("sessionId")
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
            val arr = obj.getJSONArray("ownedResourcePaths")
            for (i in 0 until arr.length()) {
                val path = arr.get(i)
                require(path is String && path.isNotBlank()) { "Owned resource path must be a nonblank string" }
                paths.add(path)
            }
            return DeletionJournalEntry(
                id = obj.requiredJournalString("id"),
                chatKind = ChatKind.valueOf(obj.requiredJournalString("chatKind")),
                sessionId = obj.requiredJournalString("sessionId"),
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

    /** Revalidate before accessing history; a failed read must not mean no deletions. */
    fun refreshForHistoryAccess(): Boolean = lock.withLock {
        loadJournalInternal()
        isLoaded = true
        !hasCorruptData
    }

    fun isTombstoned(chatKind: ChatKind, sessionId: String): Boolean {
        if (!isLoaded) ensureLoaded()
        return tombstones.contains(tombstoneKey(chatKind, sessionId))
    }

    fun markTombstoned(chatKind: ChatKind, sessionId: String) {
        if (!isLoaded) ensureLoaded()
        tombstones.add(tombstoneKey(chatKind, sessionId))
    }

    private fun loadJournalInternal() {
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
                // Initially absent is valid; removing a broken journal is not recovery.
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
        val currentDoc = readCurrentDoc()
        if (hasCorruptData) return false
        val nextPending = currentDoc.pending.filter { it.sessionId != entry.sessionId || it.chatKind != entry.chatKind } + entry
        val nextTombstones = (currentDoc.tombstones.filter { it.sessionId != entry.sessionId || it.chatKind != entry.chatKind } +
                JournalTombstone(entry.chatKind, entry.sessionId)).distinct()

        val nextDoc = JournalDocument(version = 2, pending = nextPending, tombstones = nextTombstones)
        val writeResult = atomicFile.write(nextDoc) { encodeDocument(it) }
        if (writeResult.isSuccess) markTombstoned(entry.chatKind, entry.sessionId)
        writeResult.isSuccess
    }

    fun removeEntry(id: String): Boolean = lock.withLock {
        ensureLoaded()
        if (hasCorruptData) return false
        val currentDoc = readCurrentDoc()
        if (hasCorruptData) return false
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
        if (hasCorruptData) return false
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
                if (!removeEntry(entry.id)) allClean = false
            }
        }
        allClean
    }

    private fun readCurrentDoc(): JournalDocument {
        return when (val res = atomicFile.read { decodeDocument(it) }) {
            is AtomicReadResult.Success -> res.data
            is AtomicReadResult.Missing -> JournalDocument()
            else -> {
                hasCorruptData = true
                JournalDocument()
            }
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
        require(trimmed.isNotEmpty()) { "Deletion journal is blank" }

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
            val rawVersion = root.get("version")
            require(rawVersion is Int || rawVersion is Long) { "Deletion journal version must be an integer" }
            val version = (rawVersion as Number).toLong()
            require(version in 1L..Int.MAX_VALUE.toLong()) { "Invalid deletion journal version" }
            val ver = version.toInt()
            if (ver > 2) throw com.shounak.localmeshai.utils.UnsupportedVersionException(ver, 2)
            val pendingList = mutableListOf<DeletionJournalEntry>()
            val pendingArr = root.getJSONArray("pending")
            for (i in 0 until pendingArr.length()) {
                pendingList.add(DeletionJournalEntry.fromJson(pendingArr.getJSONObject(i)))
            }
            val tombstoneList = mutableListOf<JournalTombstone>()
            // V1 stored pending entries only; V2 always writes both arrays.
            val tombstonesArr = if (ver == 1 && !root.has("tombstones")) JSONArray()
                else root.getJSONArray("tombstones")
            for (i in 0 until tombstonesArr.length()) {
                tombstoneList.add(JournalTombstone.fromJson(tombstonesArr.getJSONObject(i)))
            }
            return JournalDocument(version = ver, pending = pendingList, tombstones = tombstoneList)
        }
    }
}

package com.shounak.localmeshai.data

import android.content.SharedPreferences
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import com.shounak.localmeshai.utils.AtomicJsonFile
import com.shounak.localmeshai.utils.AtomicReadResult
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersistedChatUserRequest(
    val sessionId: String,
    val userMessageId: String,
    val text: String,
    val documentPath: String? = null,
    val documentName: String? = null
)

data class PersistedVisionUserRequest(
    val sessionId: String,
    val userMessageId: String,
    val question: String,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val audioName: String? = null,
    val audioDurationMs: Long = 0L,
    val documentName: String? = null,
    val documentPath: String? = null
)

sealed class DeletionOutcome {
    data object Completed : DeletionOutcome()
    data object PendingCleanup : DeletionOutcome()
    data class FailedBeforeCommit(val reason: String) : DeletionOutcome()
}

class ChatSessionRepository(
    val rootDir: File,
    private val legacyChatPrefs: SharedPreferences? = null,
    private val legacyVisionPrefs: SharedPreferences? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val lock = ReentrantLock()
    val chatFile: File = rootDir.resolve("chat_sessions_v2.json")
    val visionFile: File = rootDir.resolve("vision_sessions_v2.json")

    val attachmentStore: ChatAttachmentStore = ChatAttachmentStore(rootDir)
    val deletionJournal: ChatDeletionJournal = ChatDeletionJournal(rootDir)

    private val chatAtomicFile = AtomicJsonFile<List<ChatSession>>(chatFile)
    private val visionAtomicFile = AtomicJsonFile<List<VisionChatSession>>(visionFile)

    @Volatile private var isChatUnsupportedVersion = false
    @Volatile private var isVisionUnsupportedVersion = false
    @Volatile private var isChatCorrupt = false
    @Volatile private var isVisionCorrupt = false

    fun isChatStoreHealthy(): Boolean = !isChatUnsupportedVersion && !isChatCorrupt
    fun isVisionStoreHealthy(): Boolean = !isVisionUnsupportedVersion && !isVisionCorrupt

    companion object {
        @Volatile
        private var instance: ChatSessionRepository? = null

        fun getInstance(
            context: android.content.Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ChatSessionRepository {
            return instance ?: synchronized(this) {
                instance ?: ChatSessionRepository(
                    rootDir = context.applicationContext.filesDir,
                    legacyChatPrefs = context.applicationContext.getSharedPreferences("chat_history", android.content.Context.MODE_PRIVATE),
                    legacyVisionPrefs = context.applicationContext.getSharedPreferences("vision_chat_history", android.content.Context.MODE_PRIVATE),
                    ioDispatcher = ioDispatcher
                ).also { instance = it }
            }
        }
    }

    fun isTombstoned(chatKind: ChatKind, sessionId: String): Boolean {
        return deletionJournal.isTombstoned(chatKind, sessionId)
    }

    suspend fun loadChatSessionsAsync(): AtomicReadResult<List<ChatSession>> = withContext(ioDispatcher) {
        lock.withLock {
            val res = chatAtomicFile.read { json -> SessionCodec.decodeChatSessions(json) }
            when (res) {
                is AtomicReadResult.Success -> {
                    isChatUnsupportedVersion = false
                    isChatCorrupt = false
                    val filtered = res.data.filterNot { deletionJournal.isTombstoned(ChatKind.Text, it.id) }
                    AtomicReadResult.Success(filtered)
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    isChatUnsupportedVersion = true
                    res
                }
                is AtomicReadResult.Corrupt -> {
                    isChatCorrupt = true
                    res
                }
                is AtomicReadResult.Missing -> {
                    val legacyRaw = legacyChatPrefs?.getString("sessions_json", null)
                    if (!legacyRaw.isNullOrBlank()) {
                        val decodeTry = runCatching {
                            SessionCodec.decodeLegacyChatSessions(legacyRaw)
                        }
                        if (decodeTry.isFailure) {
                            isChatCorrupt = true
                            AtomicReadResult.Corrupt(
                                file = chatFile,
                                cause = decodeTry.exceptionOrNull() ?: IllegalStateException("Corrupt legacy chat data")
                            )
                        } else {
                            val migrated = decodeTry.getOrDefault(emptyList())
                                .filterNot { deletionJournal.isTombstoned(ChatKind.Text, it.id) }
                            if (migrated.isNotEmpty()) {
                                val saved = saveChatSessionsInternal(migrated)
                                if (saved) {
                                    legacyChatPrefs.edit().remove("sessions_json").apply()
                                }
                            }
                            isChatUnsupportedVersion = false
                            isChatCorrupt = false
                            AtomicReadResult.Success(migrated)
                        }
                    } else {
                        isChatUnsupportedVersion = false
                        isChatCorrupt = false
                        AtomicReadResult.Success(emptyList())
                    }
                }
            }
        }
    }

    fun loadChatSessions(): List<ChatSession> = lock.withLock {
        when (val res = chatAtomicFile.read { json -> SessionCodec.decodeChatSessions(json) }) {
            is AtomicReadResult.Success -> {
                isChatUnsupportedVersion = false
                isChatCorrupt = false
                res.data.filterNot { deletionJournal.isTombstoned(ChatKind.Text, it.id) }
            }
            is AtomicReadResult.UnsupportedVersion -> {
                isChatUnsupportedVersion = true
                emptyList()
            }
            is AtomicReadResult.Corrupt -> {
                isChatCorrupt = true
                emptyList()
            }
            is AtomicReadResult.Missing -> {
                val legacyRaw = legacyChatPrefs?.getString("sessions_json", null)
                if (!legacyRaw.isNullOrBlank()) {
                    runCatching {
                        val migrated = SessionCodec.decodeLegacyChatSessions(legacyRaw)
                            .filterNot { deletionJournal.isTombstoned(ChatKind.Text, it.id) }
                        if (migrated.isNotEmpty()) {
                            val saved = saveChatSessionsInternal(migrated)
                            if (saved) {
                                legacyChatPrefs?.let { it.edit().remove("sessions_json").apply() }
                            }
                        }
                        migrated
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        }
    }

    suspend fun saveChatSessionsAsync(sessions: List<ChatSession>): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            saveChatSessionsInternal(sessions)
        }
    }

    fun saveChatSessions(sessions: List<ChatSession>): Boolean = lock.withLock {
        saveChatSessionsInternal(sessions)
    }

    private fun saveChatSessionsInternal(sessions: List<ChatSession>): Boolean {
        if (isChatUnsupportedVersion) {
            return false
        }
        val sanitized = sessions.filterNot { deletionJournal.isTombstoned(ChatKind.Text, it.id) }
        return chatAtomicFile.write(sanitized) { SessionCodec.encodeChatSessions(it) }.isSuccess
    }

    suspend fun loadVisionSessionsAsync(): AtomicReadResult<List<VisionChatSession>> = withContext(ioDispatcher) {
        lock.withLock {
            val res = visionAtomicFile.read { json -> SessionCodec.decodeVisionSessions(json) }
            when (res) {
                is AtomicReadResult.Success -> {
                    isVisionUnsupportedVersion = false
                    isVisionCorrupt = false
                    val filtered = res.data.filterNot { deletionJournal.isTombstoned(ChatKind.Vision, it.id) }
                    AtomicReadResult.Success(filtered)
                }
                is AtomicReadResult.UnsupportedVersion -> {
                    isVisionUnsupportedVersion = true
                    res
                }
                is AtomicReadResult.Corrupt -> {
                    isVisionCorrupt = true
                    res
                }
                is AtomicReadResult.Missing -> {
                    val legacyRaw = legacyVisionPrefs?.getString("sessions_json", null)
                    if (!legacyRaw.isNullOrBlank()) {
                        val decodeTry = runCatching {
                            SessionCodec.decodeLegacyVisionSessions(legacyRaw)
                        }
                        if (decodeTry.isFailure) {
                            isVisionCorrupt = true
                            AtomicReadResult.Corrupt(
                                file = visionFile,
                                cause = decodeTry.exceptionOrNull() ?: IllegalStateException("Corrupt legacy vision data")
                            )
                        } else {
                            val migrated = decodeTry.getOrDefault(emptyList())
                                .filterNot { deletionJournal.isTombstoned(ChatKind.Vision, it.id) }
                            if (migrated.isNotEmpty()) {
                                val saved = saveVisionSessionsInternal(migrated)
                                if (saved) {
                                    legacyVisionPrefs.edit().remove("sessions_json").apply()
                                }
                            }
                            isVisionUnsupportedVersion = false
                            isVisionCorrupt = false
                            AtomicReadResult.Success(migrated)
                        }
                    } else {
                        isVisionUnsupportedVersion = false
                        isVisionCorrupt = false
                        AtomicReadResult.Success(emptyList())
                    }
                }
            }
        }
    }

    fun loadVisionSessions(): List<VisionChatSession> = lock.withLock {
        when (val res = visionAtomicFile.read { json -> SessionCodec.decodeVisionSessions(json) }) {
            is AtomicReadResult.Success -> {
                isVisionUnsupportedVersion = false
                isVisionCorrupt = false
                res.data.filterNot { deletionJournal.isTombstoned(ChatKind.Vision, it.id) }
            }
            is AtomicReadResult.UnsupportedVersion -> {
                isVisionUnsupportedVersion = true
                emptyList()
            }
            is AtomicReadResult.Corrupt -> {
                isVisionCorrupt = true
                emptyList()
            }
            is AtomicReadResult.Missing -> {
                val legacyRaw = legacyVisionPrefs?.getString("sessions_json", null)
                if (!legacyRaw.isNullOrBlank()) {
                    runCatching {
                        val migrated = SessionCodec.decodeLegacyVisionSessions(legacyRaw)
                            .filterNot { deletionJournal.isTombstoned(ChatKind.Vision, it.id) }
                        if (migrated.isNotEmpty()) {
                            val saved = saveVisionSessionsInternal(migrated)
                            if (saved) {
                                legacyVisionPrefs?.let { it.edit().remove("sessions_json").apply() }
                            }
                        }
                        migrated
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        }
    }

    suspend fun saveVisionSessionsAsync(sessions: List<VisionChatSession>): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            saveVisionSessionsInternal(sessions)
        }
    }

    fun saveVisionSessions(sessions: List<VisionChatSession>): Boolean = lock.withLock {
        saveVisionSessionsInternal(sessions)
    }

    private fun saveVisionSessionsInternal(sessions: List<VisionChatSession>): Boolean {
        if (isVisionUnsupportedVersion) {
            return false
        }
        val sanitized = sessions.filterNot { deletionJournal.isTombstoned(ChatKind.Vision, it.id) }
        return visionAtomicFile.write(sanitized) { SessionCodec.encodeVisionSessions(it) }.isSuccess
    }

    /**
     * Executes the complete transactional chat deletion sequence.
     * Records deletion journal, marks tombstone, commits session removal from JSON,
     * deletes physical owned attachments, and updates journal.
     */
    suspend fun deleteSessionAsync(chatKind: ChatKind, sessionId: String): DeletionOutcome = withContext(ioDispatcher) {
        lock.withLock {
            deletionJournal.ensureLoaded()

            // 1. Gather owned directory path (entire directory is recorded to guard any concurrent writes)
            val sessionDir = attachmentStore.getSessionAttachmentsDir(chatKind, sessionId, create = false)
            val ownedPaths = listOf(sessionDir.absolutePath)

            // 2. Journal and tombstone
            val entry = DeletionJournalEntry(
                chatKind = chatKind,
                sessionId = sessionId,
                ownedResourcePaths = ownedPaths
            )
            val journalRecorded = deletionJournal.recordPendingDeletion(entry)
            if (!journalRecorded) {
                return@withLock DeletionOutcome.FailedBeforeCommit("Failed to record deletion journal entry")
            }

            // 3. Commit removal from authoritative JSON store
            val saveOk = when (chatKind) {
                ChatKind.Text -> {
                    val remaining = loadChatSessions().filter { it.id != sessionId }
                    saveChatSessionsInternal(remaining)
                }
                ChatKind.Vision -> {
                    val remaining = loadVisionSessions().filter { it.id != sessionId }
                    saveVisionSessionsInternal(remaining)
                }
            }
            if (!saveOk) {
                return@withLock DeletionOutcome.FailedBeforeCommit("Failed to commit session removal from JSON store")
            }

            // 4. Physical deletion of owned attachments
            val filesDeleted = attachmentStore.deleteSessionAttachments(chatKind, sessionId)

            // 5. Check deletion results; remove journal entry only if files completely removed
            if (filesDeleted) {
                deletionJournal.removeEntry(entry.id)
                DeletionOutcome.Completed
            } else {
                DeletionOutcome.PendingCleanup
            }
        }
    }

    /**
     * Reconciles any interrupted or failed deletions from the journal.
     */
    suspend fun reconcileDeletionsAsync(): Boolean = withContext(ioDispatcher) {
        deletionJournal.reconcilePendingDeletions { path ->
            val f = File(path)
            if (f.exists()) {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } else {
                true
            }
        }
    }

    fun findChatUserRequest(
        sessionId: String,
        userMessageId: String,
        currentSessions: List<ChatSession>? = null
    ): PersistedChatUserRequest? {
        val sessionList = currentSessions ?: loadChatSessions()
        val session = sessionList.firstOrNull { it.id == sessionId } ?: return null
        val userMsg = session.messages.firstOrNull { it.isUser && it.id == userMessageId } ?: return null
        return PersistedChatUserRequest(
            sessionId = sessionId,
            userMessageId = userMsg.id,
            text = userMsg.text,
            documentPath = userMsg.documentPath,
            documentName = userMsg.documentName
        )
    }

    fun findVisionUserRequest(
        sessionId: String,
        userMessageId: String,
        currentSessions: List<VisionChatSession>? = null
    ): PersistedVisionUserRequest? {
        val sessionList = currentSessions ?: loadVisionSessions()
        val session = sessionList.firstOrNull { it.id == sessionId } ?: return null
        val userMsg = session.messages.firstOrNull { it.isUser && it.id == userMessageId } ?: return null
        return PersistedVisionUserRequest(
            sessionId = sessionId,
            userMessageId = userMsg.id,
            question = userMsg.text,
            imagePath = userMsg.imagePath ?: session.imagePath,
            audioPath = userMsg.audioPath ?: session.audioPath,
            audioName = userMsg.audioName ?: session.audioName,
            audioDurationMs = if (userMsg.audioDurationMs > 0) userMsg.audioDurationMs else session.audioDurationMs,
            documentName = userMsg.documentName ?: session.documentName,
            documentPath = userMsg.documentPath
        )
    }
}

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
import kotlinx.coroutines.flow.asSharedFlow

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val legacyCacheDir: (() -> File?)? = null
) {
    private val lock = ReentrantLock()
    // Old ViewModel snapshots must not erase chats added by a concurrent restore.
    private val restoredTextIds = mutableSetOf<String>()
    private val restoredVisionIds = mutableSetOf<String>()
    private val _externalChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val externalChanges = _externalChanges.asSharedFlow()

    suspend fun mergeRestoredSessions(text: List<ChatSession>, vision: List<VisionChatSession>, onCommitted: (ChatKind, Set<String>) -> Unit = { _, _ -> }) = withContext(ioDispatcher) {
        lock.withLock {
            val currentText = loadChatSessions()
            val currentVision = loadVisionSessions()
            check(isChatStoreHealthy() && isVisionStoreHealthy()) { "History requires recovery before restoring a backup" }
            val addedText = text.filter { incoming -> currentText.none { it.id == incoming.id } && !deletionJournal.isTombstoned(ChatKind.Text, incoming.id) }
            val addedVision = vision.filter { incoming -> currentVision.none { it.id == incoming.id } && !deletionJournal.isTombstoned(ChatKind.Vision, incoming.id) }
            check(saveChatSessionsInternal(currentText + addedText)) { "Could not restore text chats; existing data was preserved" }
            onCommitted(ChatKind.Text, addedText.map { it.id }.toSet())
            restoredTextIds.addAll(addedText.map { it.id })
            if (addedText.isNotEmpty()) _externalChanges.tryEmit(Unit)
            check(saveVisionSessionsInternal(currentVision + addedVision)) {
                "Text chats were restored, but vision history could not be saved. Retry this same backup to finish."
            }
            onCommitted(ChatKind.Vision, addedVision.map { it.id }.toSet())
            restoredVisionIds.addAll(addedVision.map { it.id })
            _externalChanges.tryEmit(Unit)
            addedText.size + addedVision.size
        }
    }
    val chatFile: File = rootDir.resolve("chat_sessions_v2.json")
    val visionFile: File = rootDir.resolve("vision_sessions_v2.json")

    val deletionJournal: ChatDeletionJournal = ChatDeletionJournal(rootDir)
    val attachmentStore: ChatAttachmentStore = ChatAttachmentStore(rootDir) { kind, sessionId ->
        check(deletionJournal.refreshForHistoryAccess()) {
            "Deletion journal requires recovery before attachments can be saved"
        }
        deletionJournal.isTombstoned(kind, sessionId)
    }

    private val chatAtomicFile = AtomicJsonFile<List<ChatSession>>(chatFile)
    private val visionAtomicFile = AtomicJsonFile<List<VisionChatSession>>(visionFile)

    @Volatile private var isChatUnsupportedVersion = false
    @Volatile private var isVisionUnsupportedVersion = false
    @Volatile private var isChatCorrupt = false
    @Volatile private var isVisionCorrupt = false

    fun isChatStoreHealthy(): Boolean = !isChatUnsupportedVersion && !isChatCorrupt && !deletionJournal.hasRecoveryError()
    fun isVisionStoreHealthy(): Boolean = !isVisionUnsupportedVersion && !isVisionCorrupt && !deletionJournal.hasRecoveryError()

    private fun deletionRecoveryError(): AtomicReadResult.Corrupt? =
        if (deletionJournal.refreshForHistoryAccess()) null else AtomicReadResult.Corrupt(
            file = rootDir.resolve("chat_deletion_journal.json"),
            cause = IllegalStateException("Deletion journal requires recovery before history can be accessed")
        )

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
                    ioDispatcher = ioDispatcher,
                    legacyCacheDir = { context.applicationContext.cacheDir }
                ).also { instance = it }
            }
        }
    }

    fun isTombstoned(chatKind: ChatKind, sessionId: String): Boolean {
        return deletionJournal.isTombstoned(chatKind, sessionId)
    }

    suspend fun loadChatSessionsAsync(): AtomicReadResult<List<ChatSession>> = withContext(ioDispatcher) {
        lock.withLock {
            deletionRecoveryError()?.let { return@withLock it }
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
                            isChatUnsupportedVersion = false
                            isChatCorrupt = false
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
        if (deletionRecoveryError() != null) return@withLock emptyList()
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
                        isChatUnsupportedVersion = false
                        isChatCorrupt = false
                        if (migrated.isNotEmpty()) {
                            val saved = saveChatSessionsInternal(migrated)
                            if (saved) {
                                legacyChatPrefs?.let { it.edit().remove("sessions_json").apply() }
                            }
                        }
                        migrated
                    }.onFailure { isChatCorrupt = true }.getOrDefault(emptyList())
                } else {
                    isChatUnsupportedVersion = false
                    isChatCorrupt = false
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
        if (deletionRecoveryError() != null) return false
        if (!isChatStoreHealthy()) return false
        var restored = emptyList<ChatSession>()
        when (val current = chatAtomicFile.read { SessionCodec.decodeChatSessions(it) }) {
            is AtomicReadResult.Corrupt -> { isChatCorrupt = true; return false }
            is AtomicReadResult.UnsupportedVersion -> { isChatUnsupportedVersion = true; return false }
            is AtomicReadResult.Success -> restored = current.data.filter { it.id in restoredTextIds && sessions.none { incoming -> incoming.id == it.id } }
            else -> Unit
        }
        val sanitized = (sessions + restored).filterNot { deletionJournal.isTombstoned(ChatKind.Text, it.id) }
        return chatAtomicFile.write(sanitized) { SessionCodec.encodeChatSessions(it) }.isSuccess
    }

    suspend fun loadVisionSessionsAsync(): AtomicReadResult<List<VisionChatSession>> = withContext(ioDispatcher) {
        lock.withLock {
            deletionRecoveryError()?.let { return@withLock it }
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
                            isVisionUnsupportedVersion = false
                            isVisionCorrupt = false
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
        if (deletionRecoveryError() != null) return@withLock emptyList()
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
                        isVisionUnsupportedVersion = false
                        isVisionCorrupt = false
                        if (migrated.isNotEmpty()) {
                            val saved = saveVisionSessionsInternal(migrated)
                            if (saved) {
                                legacyVisionPrefs?.let { it.edit().remove("sessions_json").apply() }
                            }
                        }
                        migrated
                    }.onFailure { isVisionCorrupt = true }.getOrDefault(emptyList())
                } else {
                    isVisionUnsupportedVersion = false
                    isVisionCorrupt = false
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
        if (deletionRecoveryError() != null) return false
        if (!isVisionStoreHealthy()) return false
        var restored = emptyList<VisionChatSession>()
        when (val current = visionAtomicFile.read { SessionCodec.decodeVisionSessions(it) }) {
            is AtomicReadResult.Corrupt -> { isVisionCorrupt = true; return false }
            is AtomicReadResult.UnsupportedVersion -> { isVisionUnsupportedVersion = true; return false }
            is AtomicReadResult.Success -> restored = current.data.filter { it.id in restoredVisionIds && sessions.none { incoming -> incoming.id == it.id } }
            else -> Unit
        }
        val sanitized = (sessions + restored).filterNot { deletionJournal.isTombstoned(ChatKind.Vision, it.id) }
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

            // Read before recording intent: an unreadable store is never an empty history.
            val textSessions = if (chatKind == ChatKind.Text) loadChatSessions() else null
            val visionSessions = if (chatKind == ChatKind.Vision) loadVisionSessions() else null
            if ((chatKind == ChatKind.Text && !isChatStoreHealthy()) ||
                (chatKind == ChatKind.Vision && !isVisionStoreHealthy())) {
                return@withLock DeletionOutcome.FailedBeforeCommit("History storage requires recovery before deletion")
            }

            val allText = textSessions ?: loadChatSessions()
            val allVision = visionSessions ?: loadVisionSessions()
            val otherReferences = buildSet {
                allText.filterNot { chatKind == ChatKind.Text && it.id == sessionId }.forEach { session ->
                    session.documentId?.let(::add)
                    session.messages.mapNotNullTo(this) { it.ragDocumentId }
                }
                allVision.filterNot { chatKind == ChatKind.Vision && it.id == sessionId }.forEach { session ->
                    session.documentId?.let(::add)
                    session.messages.mapNotNullTo(this) { it.ragDocumentId }
                }
            }
            val uploads = when (chatKind) {
                ChatKind.Text -> allText.firstOrNull { it.id == sessionId }?.messages.orEmpty()
                    .filter { it.isUser }.map { it.ragDocumentId to it.documentPath }
                ChatKind.Vision -> allVision.firstOrNull { it.id == sessionId }?.messages.orEmpty()
                    .filter { it.isUser }.map { it.ragDocumentId to it.documentPath }
            }
            val ownedDocumentIds = if (isChatStoreHealthy() && isVisionStoreHealthy()) uploads.mapNotNull { (id, path) ->
                id?.takeIf { it !in otherReferences && path != null && attachmentStore.isOwnedAttachment(File(path), chatKind, sessionId) }
            }.toSet() else emptySet()

            // 1. Gather owned directory path (entire directory is recorded to guard any concurrent writes)
            val sessionDir = attachmentStore.getSessionAttachmentsDir(chatKind, sessionId, create = false)
            val ownedPaths = buildList {
                add(sessionDir.absolutePath)
                if (chatKind == ChatKind.Vision) {
                    add(rootDir.resolve("vision_sessions/session_$sessionId.png").absolutePath)
                    legacyCacheDir?.invoke()?.let { add(it.resolve("vision_sessions/session_$sessionId.png").absolutePath) }
                }
            }

            // 2. Journal and tombstone
            val entry = DeletionJournalEntry(
                chatKind = chatKind,
                sessionId = sessionId,
                ownedResourcePaths = ownedPaths,
                ownedRagDocumentIds = ownedDocumentIds
            )
            val journalRecorded = deletionJournal.recordPendingDeletion(entry)
            if (!journalRecorded) {
                return@withLock DeletionOutcome.FailedBeforeCommit("Failed to record deletion journal entry")
            }

            // 3. Commit removal from authoritative JSON store
            val saveOk = when (chatKind) {
                ChatKind.Text -> {
                    val remaining = checkNotNull(textSessions).filter { it.id != sessionId }
                    saveChatSessionsInternal(remaining)
                }
                ChatKind.Vision -> {
                    val remaining = checkNotNull(visionSessions).filter { it.id != sessionId }
                    saveVisionSessionsInternal(remaining)
                }
            }
            if (!saveOk) {
                // The durable tombstone has committed deletion; keep the journal for retry.
                return@withLock DeletionOutcome.PendingCleanup
            }

            // 4. Physical deletion of owned attachments
            val attachmentsDeleted = attachmentStore.deleteSessionAttachments(chatKind, sessionId)
            val legacyDeleted = ownedPaths.drop(1).map { path ->
                val file = File(path)
                !file.exists() || file.delete()
            }.all { it }
            val filesDeleted = attachmentsDeleted && legacyDeleted

            // 5. Check deletion results; remove journal entry only if files completely removed
            if (filesDeleted) {
                if (deletionJournal.removeEntry(entry.id)) DeletionOutcome.Completed else DeletionOutcome.PendingCleanup
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

package com.shounak.localmeshai.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Shared production boundary between durable deletion and ViewModel presentation changes. */
internal class HistoryDeletionCoordinator(private val repository: ChatSessionRepository) {
    suspend fun deleteSessions(
        kind: ChatKind,
        sessionIds: List<String>,
        onCommitted: (sessionId: String, cleanupPending: Boolean) -> Unit,
        onFailure: (reason: String) -> Unit
    ) {
        for (sessionId in sessionIds.distinct()) {
            val result = withContext(NonCancellable) { repository.deleteSessionAsync(kind, sessionId) }
            when (result) {
                is DeletionOutcome.FailedBeforeCommit -> onFailure(result.reason)
                else -> onCommitted(sessionId, result is DeletionOutcome.PendingCleanup)
            }
        }
    }
}

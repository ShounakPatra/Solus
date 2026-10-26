package com.shounak.localmeshai.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reserved draft wrapper containing both referenced URIs and the underlying [LocalModelImporter.ImportResult].
 * Implements [Set<String>] by delegating to [uris] for complete backward-compatibility with tests.
 */
data class ReservedImportDraft(
    val uris: Set<String>,
    val result: LocalModelImporter.ImportResult? = null
) : Set<String> by uris

/**
 * Android-independent bookkeeping helper for phone-import permission ownership and draft lifecycle.
 *
 * Enforces:
 * - Session-level grant ownership across draft replacements and file reselection.
 * - Single mutex protecting every session operation.
 * - Suspendable grant acquisition and ownership bookkeeping.
 * - Non-cancellable recording and cancellation cleanup.
 * - Strict mutual exclusion: inspection rejected during registration, registration rejected during inspection.
 * - Safe deferred dismissal while registration is active until durable persistence finishes.
 * - Transfer of ownership to the registered model on success; clearing draft and releasing only
 *   unreferenced session-owned permissions on failure.
 * - Full cleanup on disposal/cancellation.
 * - Deferral of disposal cleanup if registration is still in progress until persistence finishes.
 * - Teardown gate stops accepting new operations once teardown begins.
 * - Staged drafts cleaned on Dispatchers.IO without destroying accepted registration artifacts.
 */
internal class PhoneImportSession(
    private val releasePermission: (String) -> Unit,
    private val isUriRegistered: (String) -> Boolean,
    val mutex: Mutex = Mutex()
) {
    private val sessionGrants = mutableSetOf<String>()
    private var currentDraftResult: LocalModelImporter.ImportResult? = null
    private var currentDraftUris = emptySet<String>()

    var isInspecting: Boolean = false
        private set

    var isRegistering: Boolean = false
        private set

    var isDisposed: Boolean = false
        private set

    var isTeardownStarted: Boolean = false
        private set

    var isDismissPending: Boolean = false
        private set

    suspend fun getSessionGrants(): Set<String> = mutex.withLock { sessionGrants.toSet() }
    suspend fun getCurrentDraftUris(): Set<String> = mutex.withLock { currentDraftUris.toSet() }

    /**
     * Flags teardown start under the mutex, preventing new operations.
     */
    suspend fun startTeardown() = mutex.withLock {
        isTeardownStarted = true
    }

    /**
     * Starts inspection if not already inspecting, registering, disposed, or tearing down.
     */
    suspend fun startInspection(): Boolean = mutex.withLock {
        if (isInspecting || isRegistering || isDisposed || isTeardownStarted) return false
        isInspecting = true
        return true
    }

    /**
     * Records a newly acquired permission grant immediately during inspection.
     */
    suspend fun recordGrant(uri: String) = mutex.withLock {
        if (!isDisposed && !isTeardownStarted) {
            sessionGrants.add(uri)
        } else {
            if (!isUriRegistered(uri)) {
                releasePermission(uri)
            }
        }
    }

    /**
     * Completes inspection and replaces the active draft with a new [ImportResult].
     * Cleans up superseded draft on Dispatchers.IO.
     */
    suspend fun finishInspection(draft: LocalModelImporter.ImportResult) {
        val uris = draft.bundle.allArtifacts.mapNotNull {
            (it.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString
        }.toSet()
        var oldDraftToClean: LocalModelImporter.ImportResult? = null
        mutex.withLock {
            isInspecting = false
            if (isDisposed || isTeardownStarted) {
                oldDraftToClean = draft
                cleanupGrantsInternal()
            } else {
                oldDraftToClean = if (currentDraftResult !== draft) currentDraftResult else null
                currentDraftResult = draft
                replaceGrantsInternal(uris)
                currentDraftUris = uris
            }
        }
        if (oldDraftToClean != null) {
            withContext(Dispatchers.IO) {
                oldDraftToClean.draftStagingDir?.deleteRecursively()
            }
        }
    }

    /**
     * Completes inspection and replaces the active draft with a new set of referenced URIs.
     */
    suspend fun finishInspection(newDraftUris: Set<String>) {
        var oldDraftToClean: LocalModelImporter.ImportResult? = null
        mutex.withLock {
            isInspecting = false
            if (isDisposed || isTeardownStarted) {
                oldDraftToClean = currentDraftResult
                currentDraftResult = null
                currentDraftUris = emptySet()
                cleanupGrantsInternal()
            } else {
                oldDraftToClean = currentDraftResult
                currentDraftResult = null
                replaceGrantsInternal(newDraftUris)
                currentDraftUris = newDraftUris
            }
        }
        if (oldDraftToClean != null) {
            withContext(Dispatchers.IO) {
                oldDraftToClean.draftStagingDir?.deleteRecursively()
            }
        }
    }

    /**
     * Direct draft replacement with [ImportResult].
     */
    suspend fun replaceDraft(draft: LocalModelImporter.ImportResult?) {
        val uris = draft?.bundle?.allArtifacts?.mapNotNull {
            (it.source as? com.shounak.localmeshai.models.ArtifactSource.PersistedUri)?.uriString
        }?.toSet() ?: emptySet()
        var oldDraftToClean: LocalModelImporter.ImportResult? = null
        mutex.withLock {
            if (isDisposed || isTeardownStarted) {
                oldDraftToClean = draft
            } else {
                oldDraftToClean = if (currentDraftResult !== draft) currentDraftResult else null
                currentDraftResult = draft
                replaceGrantsInternal(uris)
                currentDraftUris = uris
            }
        }
        if (oldDraftToClean != null) {
            withContext(Dispatchers.IO) {
                oldDraftToClean.draftStagingDir?.deleteRecursively()
            }
        }
    }

    /**
     * Direct draft replacement with raw URIs.
     */
    suspend fun replaceDraft(newDraftUris: Set<String>) {
        var oldDraftToClean: LocalModelImporter.ImportResult? = null
        mutex.withLock {
            if (isDisposed || isTeardownStarted) return@withLock
            oldDraftToClean = currentDraftResult
            currentDraftResult = null
            replaceGrantsInternal(newDraftUris)
            currentDraftUris = newDraftUris
        }
        if (oldDraftToClean != null) {
            withContext(Dispatchers.IO) {
                oldDraftToClean.draftStagingDir?.deleteRecursively()
            }
        }
    }

    private fun replaceGrantsInternal(newDraftUris: Set<String>) {
        val toRelease = sessionGrants.filter { uri ->
            uri !in newDraftUris && !isUriRegistered(uri)
        }
        for (uri in toRelease) {
            releasePermission(uri)
            sessionGrants.remove(uri)
        }
    }

    /**
     * Cleans up grants acquired during an inspection that was cancelled or failed.
     * Preserves grants referenced by the current active draft or registered models.
     */
    suspend fun cancelInspection() = mutex.withLock {
        isInspecting = false
        onInspectionCancelledOrFailedInternal()
    }

    suspend fun onInspectionCancelledOrFailed() = cancelInspection()

    private fun onInspectionCancelledOrFailedInternal() {
        val toRelease = sessionGrants.filter { uri ->
            uri !in currentDraftUris && !isUriRegistered(uri)
        }
        for (uri in toRelease) {
            releasePermission(uri)
            sessionGrants.remove(uri)
        }
    }

    /**
     * Begins registration. Returns true if registration can proceed.
     * Determines eligibility from the presence of a valid draft (URIs or app-managed draft),
     * independently of URI permission requirements.
     */
    suspend fun startRegistration(): Boolean = mutex.withLock {
        val hasDraft = currentDraftResult != null || currentDraftUris.isNotEmpty()
        if (isInspecting || isRegistering || isDisposed || isTeardownStarted || !hasDraft) return false
        isRegistering = true
        return true
    }

    /**
     * Reads and reserves the current draft under the mutex.
     * Rejects registration during inspection, during existing registration, or when disposed.
     * Determines eligibility from the presence of a valid draft (URIs or app-managed draft).
     */
    suspend fun reserveDraftForRegistration(): ReservedImportDraft? = mutex.withLock {
        val hasDraft = currentDraftResult != null || currentDraftUris.isNotEmpty()
        if (isInspecting || isDisposed || isTeardownStarted || !hasDraft) return null
        isRegistering = true
        val reserved = ReservedImportDraft(currentDraftUris.toSet(), currentDraftResult)
        currentDraftResult = null
        currentDraftUris = emptySet()
        return reserved
    }

    /**
     * Handles successful registration: transfers ownership to the registered model.
     * Retains accepted registration files through sheet dismissal.
     */
    suspend fun onRegistrationSuccess(bundleUris: Set<String>) {
        val needsDeferredCleanup = mutex.withLock {
            isRegistering = false
            sessionGrants.removeAll(bundleUris)
            currentDraftResult = null
            currentDraftUris = emptySet()
            val cleanup = isDismissPending || isDisposed || isTeardownStarted || sessionGrants.isNotEmpty()
            if (cleanup) {
                isDismissPending = false
            }
            cleanup
        }
        if (needsDeferredCleanup) {
            withContext(Dispatchers.IO) {
                mutex.withLock { cleanupGrantsInternal() }
            }
        }
    }

    /**
     * Handles registration failure: clears the invalid draft and releases unreferenced session grants.
     */
    suspend fun onRegistrationFailure() {
        var draftToClean: LocalModelImporter.ImportResult? = null
        mutex.withLock {
            isRegistering = false
            draftToClean = currentDraftResult
            currentDraftResult = null
            currentDraftUris = emptySet()
            cleanupGrantsInternal()
            isDismissPending = false
        }
        if (draftToClean != null) {
            withContext(Dispatchers.IO) {
                draftToClean.draftStagingDir?.deleteRecursively()
            }
        }
    }

    /**
     * Requests dismissal of the import UI.
     * If registration is active, defers cleanup until persistence finishes.
     * Returns true if dismissal was completed immediately.
     */
    suspend fun requestDismiss(): Boolean {
        var draftToClean: LocalModelImporter.ImportResult? = null
        val immediatelyDismissed = mutex.withLock {
            if (isRegistering) {
                isDismissPending = true
                return@withLock false
            }
            draftToClean = currentDraftResult
            currentDraftResult = null
            currentDraftUris = emptySet()
            cleanupGrantsInternal()
            return@withLock true
        }
        if (draftToClean != null) {
            withContext(Dispatchers.IO) {
                draftToClean.draftStagingDir?.deleteRecursively()
            }
        }
        return immediatelyDismissed
    }

    /**
     * Disposes the session, releasing all unreferenced session-owned grants.
     * Defers cleanup if registration is active until registration finishes.
     */
    suspend fun dispose() {
        var draftToClean: LocalModelImporter.ImportResult? = null
        mutex.withLock {
            isDisposed = true
            isTeardownStarted = true
            isInspecting = false
            if (isRegistering) {
                // Defer cleanup while registration remains active
                return
            }
            isDismissPending = false
            draftToClean = currentDraftResult
            currentDraftResult = null
            currentDraftUris = emptySet()
            cleanupGrantsInternal()
        }
        if (draftToClean != null) {
            withContext(Dispatchers.IO) {
                draftToClean.draftStagingDir?.deleteRecursively()
            }
        }
    }

    private fun cleanupGrantsInternal() {
        val toRelease = sessionGrants.filter { !isUriRegistered(it) }
        for (uri in toRelease) {
            releasePermission(uri)
            sessionGrants.remove(uri)
        }
    }

    private fun cleanupUnreferencedInternal() {
        cleanupGrantsInternal()
    }
}

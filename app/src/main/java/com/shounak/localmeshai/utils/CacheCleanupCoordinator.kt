package com.shounak.localmeshai.utils

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class CleanupReason {
    BackgroundClose,
    Reopen,
    ModelSwitch,
    ModelReload,
    ModelUnload,
    DeferredReaderCompletion,
    Explicit
}

data class CleanupResult(
    val reason: CleanupReason,
    val deletedFilesCount: Int,
    val deletedBytes: Long,
    val deferredResourcesCount: Int,
    val failuresCount: Int
)

class CacheCleanupCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "CacheCleanupCoordinator"
        @Volatile
        private var instance: CacheCleanupCoordinator? = null

        fun getInstance(context: Context): CacheCleanupCoordinator {
            return instance ?: synchronized(this) {
                instance ?: CacheCleanupCoordinator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val mutex = Mutex()
    private val requestChannel = Channel<CleanupReason>(Channel.CONFLATED)

    init {
        scope.launch {
            for (reason in requestChannel) {
                mutex.withLock {
                    runCleanupInternal(reason)
                }
            }
        }
    }

    fun attachProcessLifecycle() {
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to attach ProcessLifecycleOwner: ${e.message}")
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App returned to foreground / reopened: only trigger if startup hydration is complete
        val startupCoordinator = com.shounak.localmeshai.data.StartupCoordinator.getInstance(context)
        if (startupCoordinator.isReady) {
            requestCleanup(CleanupReason.Reopen)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background / closing
        requestCleanup(CleanupReason.BackgroundClose)
    }

    fun requestCleanup(reason: CleanupReason) {
        requestChannel.trySend(reason)
    }

    suspend fun executeCleanupNow(reason: CleanupReason): CleanupResult = mutex.withLock {
        runCleanupInternal(reason)
    }

    private fun runCleanupInternal(reason: CleanupReason): CleanupResult {
        Log.i(TAG, "Executing cache cleanup for reason: $reason")
        var deletedFiles = 0
        var deletedBytes = 0L
        var deferredCount = 0
        var failures = 0

        try {
            // 1. If runtime is busy or model is initializing, defer compiled kernel cleanup
            val isRuntimeBusy = ModelRuntimeCoordinator.isRuntimeBusy.value
            if (!isRuntimeBusy) {
                val compiledDeleted = if (reason == CleanupReason.Explicit) LiteRtRuntimeCache.clearInactive(context)
                    else LiteRtRuntimeCache.prune(context)
                deletedFiles += compiledDeleted
            } else {
                deferredCount++
            }

            // 2. Prune disposable app-managed cache files (camera, audio, documents, previews)
            // skipping anything protected by ActiveInputFiles or stored in ChatAttachmentStore
            val protectedPaths = mutableSetOf<String>()
            protectedPaths.addAll(AppCacheCleaner.savedAttachmentPaths(context))

            val internalCachePruned = AppCacheCleaner.pruneExpiredInputs(
                root = context.cacheDir,
                protectedPaths = protectedPaths
            )
            deletedFiles += internalCachePruned

            val externalCache = runCatching { context.externalCacheDir }.getOrNull()
            if (externalCache != null) {
                val externalCachePruned = AppCacheCleaner.pruneExpiredInputs(
                    root = externalCache,
                    protectedPaths = protectedPaths
                )
                deletedFiles += externalCachePruned
            }

            // 3. Reconcile any interrupted chat deletions
            val repo = com.shounak.localmeshai.data.ChatSessionRepository.getInstance(context)
            val reconcileOk = repo.deletionJournal.reconcilePendingDeletions { path ->
                val f = File(path)
                if (f.exists()) {
                    val size = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                    if (ok) {
                        deletedFiles++
                        deletedBytes += size
                    }
                    ok
                } else {
                    true
                }
            }
            if (!reconcileOk) failures++

        } catch (e: Throwable) {
            Log.e(TAG, "Error during cache cleanup execution", e)
            failures++
        }

        return CleanupResult(
            reason = reason,
            deletedFilesCount = deletedFiles,
            deletedBytes = deletedBytes,
            deferredResourcesCount = deferredCount,
            failuresCount = failures
        )
    }
}

package com.shounak.localmeshai.services

import android.content.Context
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.utils.DownloadProgress
import com.shounak.localmeshai.utils.DownloadRestorationPolicy
import com.shounak.localmeshai.utils.DownloadResult
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadRequest(
    val modelId: String,
    val name: String,
    val url: String,
    val fileName: String,
    val packageType: ModelPackage,
    val bearerToken: String? = null,
    val sha256: String? = null,
    val downloadSpec: com.shounak.localmeshai.models.ModelDownloadSpec? = null,
    val retainStagingOnCancel: Boolean = false
)

interface DownloadEventListener {
    fun onDownloadStarted(request: DownloadRequest, initialSnapshot: DownloadSnapshot) {}
    fun onDownloadProgress(request: DownloadRequest, progress: DownloadProgress, snapshot: DownloadSnapshot) {}
    fun onDownloadCompleted(request: DownloadRequest, result: DownloadResult, snapshot: DownloadSnapshot) {}
    fun onDownloadPaused(request: DownloadRequest, snapshot: DownloadSnapshot) {}
    fun onDownloadCancelled(request: DownloadRequest) {}
    fun onDownloadFailed(request: DownloadRequest, error: Throwable, snapshot: DownloadSnapshot) {}
    fun onAllDownloadsFinished() {}
}

/**
 * Engine-independent download controller managing serialization, attempt isolation,
 * cancellation, and state publication.
 */
class DownloadController(
    val downloader: ModelDownloader,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val networkChecker: (() -> Boolean)? = null,
    var eventListener: DownloadEventListener? = null,
    var inferenceShutdownAwaiter: (suspend (String) -> Boolean)? = null,
    private val recordCompletedDownload: (String) -> Unit = {}
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeAttempts = ConcurrentHashMap<String, Long>()
    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val attemptCounter = AtomicLong(0L)
    private val lastStartedAt = AtomicLong(0L)

    private val pendingOperationsCount = AtomicInteger(0)
    private val cancellingJobs: MutableSet<Job> = Collections.newSetFromMap(ConcurrentHashMap<Job, Boolean>())

    private fun getMutex(modelId: String): Mutex =
        mutexes.computeIfAbsent(modelId) { Mutex() }

    fun activeModelIds(): Set<String> = activeJobs.filterValues { it.isActive }.keys

    fun isDownloading(modelId: String): Boolean = activeJobs[modelId]?.isActive == true

    fun getRequest(modelId: String): DownloadRequest? = requests[modelId]

    fun hasActiveOrPendingOperations(): Boolean =
        pendingOperationsCount.get() > 0 ||
            activeJobs.any { !it.value.isCompleted } ||
            cancellingJobs.any { !it.isCompleted }

    fun start(request: DownloadRequest): Job {
        requests[request.modelId] = request
        val attemptId = attemptCounter.incrementAndGet()
        val startedAt = lastStartedAt.updateAndGet { maxOf(System.currentTimeMillis(), it + 1L) }
        pendingOperationsCount.incrementAndGet()

        var previousJob: Job? = null
        val lazyJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val prev = previousJob
                if (prev != null && !prev.isCompleted) {
                    runCatching { prev.join() }
                    cancellingJobs.remove(prev)
                }

                val canProceed = getMutex(request.modelId).withLock {
                    if (activeAttempts[request.modelId] != attemptId) return@withLock false
                    if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(request.modelId) ||
                        com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(request.modelId)) {
                        activeAttempts.remove(request.modelId, attemptId)
                        return@withLock false
                    }

                    val previousSnapshot = DownloadStateStore.get(request.modelId)
                    val partialBytes = maxOf(
                        previousSnapshot?.downloadedBytes ?: 0L,
                        downloader.getPartialDownloadBytes(request.modelId)
                    )
                    val knownTotalBytes = previousSnapshot?.totalBytes ?: -1L
                    val initialProgress = when {
                        knownTotalBytes > 0L && partialBytes > 0L -> (partialBytes.toFloat() / knownTotalBytes.toFloat()).coerceIn(0f, 1f)
                        previousSnapshot != null -> previousSnapshot.progress
                        else -> 0f
                    }

                    val isConnected = networkChecker?.invoke() ?: true
                    if (!isConnected) {
                        val failureSnapshot = DownloadSnapshot(
                            modelId = request.modelId,
                            status = DownloadRestorationPolicy.afterFailure(partialBytes),
                            progress = initialProgress,
                            downloadedBytes = partialBytes,
                            totalBytes = knownTotalBytes,
                            errorMessage = "No internet connection. Please connect to Wi-Fi or mobile data to download models."
                        )
                        DownloadStateStore.update(failureSnapshot)
                        eventListener?.onDownloadFailed(request, IOException(failureSnapshot.errorMessage), failureSnapshot)
                        activeAttempts.remove(request.modelId, attemptId)
                        return@withLock false
                    }

                    val startSnapshot = DownloadSnapshot(
                        modelId = request.modelId,
                        startedAt = startedAt,
                        status = ModelStatus.Downloading,
                        progress = initialProgress,
                        downloadedBytes = partialBytes,
                        totalBytes = knownTotalBytes
                    )
                    DownloadStateStore.update(startSnapshot)
                    eventListener?.onDownloadStarted(request, startSnapshot)
                    true
                }

                if (!canProceed) return@launch

                try {
                    val result = if (request.downloadSpec != null) {
                        downloader.downloadArtifactBundle(
                            spec = request.downloadSpec,
                            bearerToken = request.bearerToken
                        ) { progress ->
                            if (activeAttempts[request.modelId] != attemptId) return@downloadArtifactBundle
                            val snapshot = DownloadSnapshot(
                                modelId = request.modelId,
                                startedAt = startedAt,
                                status = ModelStatus.Downloading,
                                progress = progress.progress,
                                downloadedBytes = progress.downloadedBytes,
                                totalBytes = progress.totalBytes,
                                bytesPerSecond = progress.bytesPerSecond
                            )
                            DownloadStateStore.update(snapshot)
                            eventListener?.onDownloadProgress(request, progress, snapshot)
                        }
                    } else {
                        downloader.downloadModel(
                            url = request.url,
                            modelId = request.modelId,
                            fileName = request.fileName,
                            packageType = request.packageType,
                            bearerToken = request.bearerToken,
                            expectedSha256 = request.sha256
                        ) { progress ->
                            if (activeAttempts[request.modelId] != attemptId) return@downloadModel
                            val snapshot = DownloadSnapshot(
                                modelId = request.modelId,
                                startedAt = startedAt,
                                status = ModelStatus.Downloading,
                                progress = progress.progress,
                                downloadedBytes = progress.downloadedBytes,
                                totalBytes = progress.totalBytes,
                                bytesPerSecond = progress.bytesPerSecond
                            )
                            DownloadStateStore.update(snapshot)
                            eventListener?.onDownloadProgress(request, progress, snapshot)
                        }
                    }

                    getMutex(request.modelId).withLock {
                        if (activeAttempts[request.modelId] == attemptId) {
                            if (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(request.modelId) ||
                                com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(request.modelId)) {
                                activeAttempts.remove(request.modelId, attemptId)
                                return@withLock
                            }
                            val completeSnapshot = DownloadSnapshot(
                                modelId = request.modelId,
                                status = ModelStatus.Available,
                                progress = 1f,
                                downloadedBytes = result.bytesDownloaded,
                                localPath = result.localPath,
                                downloadedAt = System.currentTimeMillis()
                            )
                            publishCompleted(completeSnapshot)
                            eventListener?.onDownloadCompleted(request, result, completeSnapshot)
                        }
                    }
                } catch (e: CancellationException) {
                    getMutex(request.modelId).withLock {
                        if (activeAttempts[request.modelId] == attemptId) {
                            val snapshot = DownloadStateStore.get(request.modelId)
                            val publishedFile = downloader.artifactStorage.getPublishedArtifact(request.modelId)
                            val isAvailable = snapshot?.status == ModelStatus.Available || publishedFile != null
                            if (isAvailable) {
                                recoverPublishedDownload(request.modelId)
                                return@withLock
                            }
                            val pausedSnapshot = DownloadSnapshot(
                                modelId = request.modelId,
                                status = ModelStatus.Paused,
                                progress = snapshot?.progress ?: 0f,
                                downloadedBytes = snapshot?.downloadedBytes ?: 0L,
                                totalBytes = snapshot?.totalBytes ?: -1L
                            )
                            DownloadStateStore.update(pausedSnapshot)
                            eventListener?.onDownloadPaused(request, pausedSnapshot)
                        }
                    }
                } catch (e: Exception) {
                    getMutex(request.modelId).withLock {
                        if (activeAttempts[request.modelId] == attemptId) {
                            // Publishing is already committed: an ancillary failure (such as a
                            // notification callback) must not turn a usable model into Failed/Paused.
                            if (recoverPublishedDownload(request.modelId)) return@withLock
                            val previous = DownloadStateStore.get(request.modelId)
                            val currentPartial = maxOf(
                                previous?.downloadedBytes ?: 0L,
                                downloader.getPartialDownloadBytes(request.modelId)
                            )
                            val friendlyMessage = when {
                                (networkChecker?.invoke() == false) ||
                                    NetworkUtils.isOfflineException(e) ->
                                    "No internet connection. Please connect to Wi-Fi or mobile data to download models."
                                NetworkUtils.isNetworkException(e) ->
                                    "Network connection lost or timed out. Tap Resume to continue."
                                else -> e.message ?: "Download failed"
                            }
                            val failureSnapshot = DownloadSnapshot(
                                modelId = request.modelId,
                                status = DownloadRestorationPolicy.afterFailure(currentPartial),
                                progress = previous?.progress ?: 0f,
                                downloadedBytes = currentPartial,
                                totalBytes = previous?.totalBytes ?: -1L,
                                errorMessage = friendlyMessage
                            )
                            DownloadStateStore.update(failureSnapshot)
                            eventListener?.onDownloadFailed(request, e, failureSnapshot)
                        }
                    }
                }
            } finally {
                if (activeAttempts[request.modelId] == attemptId) {
                    activeAttempts.remove(request.modelId)
                    activeJobs.remove(request.modelId)
                }
                pendingOperationsCount.decrementAndGet()
                checkAllFinished()
            }
        }

        synchronized(this) {
            val prev = activeJobs[request.modelId]
            previousJob = prev
            if (prev != null && !prev.isCompleted) {
                cancellingJobs.add(prev)
                downloader.cancelTransport(request.modelId)
                prev.cancel()
            }
            activeJobs[request.modelId] = lazyJob
            activeAttempts[request.modelId] = attemptId
        }

        lazyJob.start()
        return lazyJob
    }

    suspend fun pause(modelId: String) {
        pendingOperationsCount.incrementAndGet()
        try {
            downloader.cancelTransport(modelId)
            val job = activeJobs.remove(modelId)
            activeAttempts.remove(modelId)
            if (job != null && !job.isCompleted) {
                cancellingJobs.add(job)
                job.cancel()
                runCatching { job.join() }
                cancellingJobs.remove(job)
            }
            getMutex(modelId).withLock {
                val request = requests[modelId]
                if (recoverPublishedDownload(modelId)) return@withLock
                val snapshot = DownloadStateStore.get(modelId)
                val partialBytes = maxOf(snapshot?.downloadedBytes ?: 0L, downloader.getPartialDownloadBytes(modelId))
                val pausedSnapshot = DownloadSnapshot(
                    modelId = modelId,
                    status = ModelStatus.Paused,
                    progress = snapshot?.progress ?: 0f,
                    downloadedBytes = partialBytes,
                    totalBytes = snapshot?.totalBytes ?: -1L,
                    errorMessage = "Paused — download kept"
                )
                DownloadStateStore.update(pausedSnapshot)
                if (request != null) {
                    eventListener?.onDownloadPaused(request, pausedSnapshot)
                }
            }
        } finally {
            pendingOperationsCount.decrementAndGet()
            checkAllFinished()
        }
    }

    fun resume(request: DownloadRequest): Job = start(request)

    private fun publishCompleted(snapshot: DownloadSnapshot) {
        val previous = DownloadStateStore.get(snapshot.modelId)
        DownloadStateStore.update(snapshot)
        if (previous?.status != ModelStatus.Available || previous.downloadedAt != snapshot.downloadedAt) {
            recordCompletedDownload(snapshot.modelId)
        }
    }

    /** A publish can finish just before Stop/Pause cancels the download coroutine. */
    private fun recoverPublishedDownload(modelId: String): Boolean {
        val previous = DownloadStateStore.get(modelId)
        val publishedFile = downloader.artifactStorage.getPublishedArtifact(modelId)
        if (previous?.status != ModelStatus.Available && publishedFile == null) return false
        val resolvedBytes = when {
            (previous?.downloadedBytes ?: 0L) > 0L -> previous?.downloadedBytes ?: 0L
            (previous?.totalBytes ?: 0L) > 0L -> previous?.totalBytes ?: 0L
            publishedFile != null && publishedFile.length() > 0L -> publishedFile.length()
            else -> 0L
        }
        publishCompleted(DownloadSnapshot(
            modelId = modelId,
            status = ModelStatus.Available,
            progress = 1f,
            downloadedBytes = resolvedBytes,
            totalBytes = previous?.totalBytes ?: -1L,
            localPath = previous?.localPath ?: publishedFile?.absolutePath,
            downloadedAt = previous?.downloadedAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
        ))
        return true
    }

    suspend fun cancelDownload(modelId: String, retainStaging: Boolean = false) {
        pendingOperationsCount.incrementAndGet()
        try {
            downloader.cancelTransport(modelId)
            val request = requests.remove(modelId)
            activeAttempts.remove(modelId)
            val job = activeJobs.remove(modelId)
            if (job != null && !job.isCompleted) {
                cancellingJobs.add(job)
                job.cancel()
                runCatching { job.join() }
                cancellingJobs.remove(job)
            }
            getMutex(modelId).withLock {
                val latestSnapshot = DownloadStateStore.get(modelId)
                val publishedFile = downloader.artifactStorage.getPublishedArtifact(modelId)
                val isAvailable = latestSnapshot?.status == ModelStatus.Available || publishedFile != null

                if (isAvailable) {
                    if (!retainStaging) {
                        if (request != null) {
                            downloader.deleteStagingDownload(request.modelId, request.fileName)
                        } else {
                            downloader.deleteStagingDownload(modelId)
                        }
                    }
                    recoverPublishedDownload(modelId)
                    return@withLock
                }

                if (retainStaging) {
                    val partialBytes = maxOf(
                        latestSnapshot?.downloadedBytes ?: 0L,
                        downloader.getPartialDownloadBytes(modelId)
                    )
                    val totalBytes = latestSnapshot?.totalBytes ?: -1L
                    val progress = if (totalBytes > 0L) (partialBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else (latestSnapshot?.progress ?: 0f)
                    val pausedSnapshot = DownloadSnapshot(
                        modelId = modelId,
                        status = ModelStatus.Paused,
                        progress = progress,
                        downloadedBytes = partialBytes,
                        totalBytes = totalBytes,
                        errorMessage = "Paused — download kept"
                    )
                    DownloadStateStore.update(pausedSnapshot)
                    if (request != null) {
                        eventListener?.onDownloadPaused(request, pausedSnapshot)
                    }
                } else {
                    // Cancellation deletes staging files ONLY — never deletes published models.
                    if (request != null) {
                        downloader.deleteStagingDownload(request.modelId, request.fileName)
                    } else {
                        downloader.deleteStagingDownload(modelId)
                    }
                    val resetSnapshot = DownloadSnapshot(
                        modelId = modelId,
                        status = ModelStatus.NotDownloaded,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = -1L
                    )
                    DownloadStateStore.update(resetSnapshot)
                    if (request != null) {
                        eventListener?.onDownloadCancelled(request)
                    }
                }
            }
        } finally {
            pendingOperationsCount.decrementAndGet()
            checkAllFinished()
        }
    }

    suspend fun cancel(modelId: String, retainStaging: Boolean = false) = cancelDownload(modelId, retainStaging)

    suspend fun removeModel(modelId: String) {
        pendingOperationsCount.incrementAndGet()
        try {
            val request = requests.remove(modelId)
            activeAttempts.remove(modelId)
            val job = activeJobs.remove(modelId)
            if (job != null && !job.isCompleted) {
                cancellingJobs.add(job)
                job.cancel()
                runCatching { job.join() }
                cancellingJobs.remove(job)
            }
            getMutex(modelId).withLock {
                // Delete staging files
                if (request != null) {
                    downloader.deleteStagingDownload(modelId, request.fileName)
                } else {
                    downloader.deleteStagingDownload(modelId)
                }

                // Await inference shutdown before deleting published files
                val shutdownOk = runCatching {
                    inferenceShutdownAwaiter?.let { awaiter ->
                        withTimeout(10_000L) {
                            awaiter.invoke(modelId)
                        }
                    } ?: true
                }.getOrDefault(false)

                if (!shutdownOk) {
                    throw IllegalStateException("Cannot remove model $modelId: inference shutdown stalled or failed")
                }

                // Delete published model
                val deleted = downloader.artifactStorage.deleteModel(modelId)
                if (!deleted) {
                    throw IllegalStateException("Cannot remove model $modelId: deletion refused because files remain leased")
                }

                val resetSnapshot = DownloadSnapshot(
                    modelId = modelId,
                    status = ModelStatus.NotDownloaded,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = -1L
                )
                DownloadStateStore.update(resetSnapshot)
                if (request != null) {
                    eventListener?.onDownloadCancelled(request)
                }
            }
        } finally {
            pendingOperationsCount.decrementAndGet()
            checkAllFinished()
        }
    }

    suspend fun remove(modelId: String) = removeModel(modelId)

    fun pauseAllRetainingStaging() {
        activeJobs.forEach { (_, job) ->
            if (!job.isCompleted) {
                cancellingJobs.add(job)
                job.cancel()
            }
        }
    }

    private fun checkAllFinished() {
        if (pendingOperationsCount.get() <= 0 &&
            activeJobs.none { !it.value.isCompleted } &&
            cancellingJobs.none { !it.isCompleted }
        ) {
            eventListener?.onAllDownloadsFinished()
        }
    }

    companion object {
        @Volatile
        private var instance: DownloadController? = null

        fun getInstance(context: Context): DownloadController =
            instance ?: synchronized(this) {
                instance ?: DownloadController(
                    downloader = ModelDownloader(context),
                    networkChecker = { NetworkUtils.isConnected(context) },
                    recordCompletedDownload = { modelId ->
                        com.shounak.localmeshai.utils.ModelUsageStore.get(context.applicationContext).recordUsed(modelId)
                    }
                ).also { instance = it }
            }

        fun setInstanceForTesting(controller: DownloadController?) {
            instance = controller
        }
    }
}

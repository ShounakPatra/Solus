package com.shounak.localmeshai.ai

import android.util.Log
import com.shounak.localmeshai.utils.AppTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Uniquely identifies a requested model configuration.
 */
data class ModelLoadIdentity(
    val modelPath: String,
    val fileLength: Long = 0L,
    val fileLastModified: Long = 0L,
    val runtimeKind: String = "",
    val backendPreference: String = "",
    val threads: Int = 4,
    val prefillThreads: Int = threads,
    val contextWindow: Int = 2048,
    val batchSize: Int = 256,
    val ubatchSize: Int = 128,
    val gpuLayers: Int = 99,
    val artifactGeneration: Long = 0L,
    val companionFilesSummary: String = ""
) {
    companion object {
        fun fromFile(
            file: File,
            runtimeKind: String,
            backendPreference: String,
            threads: Int = 4,
            prefillThreads: Int = threads,
            contextWindow: Int = 2048,
            batchSize: Int = 256,
            ubatchSize: Int = 128,
            gpuLayers: Int = 99,
            artifactGeneration: Long = 0L,
            companionFilesSummary: String = ""
        ): ModelLoadIdentity {
            val length = if (file.exists()) file.length() else 0L
            val lastMod = if (file.exists()) file.lastModified() else 0L
            return ModelLoadIdentity(
                modelPath = file.absolutePath,
                fileLength = length,
                fileLastModified = lastMod,
                runtimeKind = runtimeKind,
                backendPreference = backendPreference,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                batchSize = batchSize,
                ubatchSize = ubatchSize,
                gpuLayers = gpuLayers,
                artifactGeneration = artifactGeneration,
                companionFilesSummary = companionFilesSummary
            )
        }

        fun fromBundle(
            bundle: com.shounak.localmeshai.models.ModelBundle,
            runtimeKind: String,
            backendPreference: String,
            threads: Int = 4,
            prefillThreads: Int = threads,
            contextWindow: Int = 2048,
            batchSize: Int = 256,
            ubatchSize: Int = 128,
            gpuLayers: Int = 99
        ): ModelLoadIdentity {
            val primary = bundle.mainArtifacts.firstOrNull()
            val totalBytes = bundle.totalSizeBytes
            val projectorDesc = bundle.projectorArtifact?.displayName.orEmpty()
            val artifactSummary = buildString {
                append(bundle.mainArtifacts.size).append("_parts")
                if (bundle.projectorArtifact != null) {
                    append("_proj:").append(projectorDesc)
                }
                append("_artifacts:").append(bundle.allArtifacts.joinToString(",") { "${it.displayName}:${it.sizeBytes}" })
                append("_caps:text=${bundle.capabilities.supportsText},vis=${bundle.capabilities.supportsVision},aud=${bundle.capabilities.supportsAudio}")
            }
            return ModelLoadIdentity(
                modelPath = primary?.source?.location ?: bundle.id,
                fileLength = totalBytes,
                fileLastModified = primary?.lastModified ?: 0L,
                runtimeKind = runtimeKind,
                backendPreference = backendPreference,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                batchSize = batchSize,
                ubatchSize = ubatchSize,
                gpuLayers = gpuLayers,
                companionFilesSummary = artifactSummary
            )
        }
    }
}

/**
 * Controller managing model loading lifecycles, progress forwarding,
 * and cooperative cancellation watchdog.
 */
class ModelLoadController internal constructor(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    internal val nativeBridge: LlamaNativeBridge = DefaultLlamaNativeBridge
) {
    constructor() : this({ System.nanoTime() / 1_000_000L }, DefaultLlamaNativeBridge)
    companion object {
        private const val TAG = "ModelLoadController"
        const val PROGRESS_WARN_TIMEOUT_MS = 30_000L
        const val PROGRESS_CANCEL_TIMEOUT_MS = 120_000L
        const val MAX_INITIALIZATION_TIMEOUT_MS = 600_000L // 10 minutes
        const val SHUTDOWN_STALL_TIMEOUT_MS = 30_000L

        private fun safeLogI(msg: String) { runCatching { Log.i(TAG, msg) } }
        private fun safeLogW(msg: String) { runCatching { Log.w(TAG, msg) } }
        private fun safeLogE(msg: String, t: Throwable? = null) {
            runCatching { if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg) }
        }
    }

    private val mutex = Mutex()
    private val progressLock = Any()
    private val _loadState = MutableStateFlow<ModelLoadState>(ModelLoadState.Unloaded)
    val loadState: StateFlow<ModelLoadState> = _loadState.asStateFlow()

    @Volatile var currentIdentity: ModelLoadIdentity? = null
        private set
    @Volatile private var currentOperationId: String? = null
    @Volatile private var currentNativeControlId: Long = 0L
    @Volatile private var currentEpoch: Long = 0L
    private var inFlightDeferred: CompletableDeferred<ModelActualConfiguration>? = null
    private var inFlightJob: Job? = null
    private var activeCancellationAction: (() -> Unit)? = null
    private var isShutdownStalled: Boolean = false
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isInitializing: Boolean
        get() = _loadState.value is ModelLoadState.Loading

    val isReady: Boolean
        get() = _loadState.value is ModelLoadState.Ready

    val activeOperationId: String?
        get() = currentOperationId

    val currentConfig: ModelActualConfiguration?
        get() = (_loadState.value as? ModelLoadState.Ready)?.actualConfiguration

    private data class TupleShutdown(
        val opId: String,
        val job: Job?,
        val cancelAction: (() -> Unit)?,
        val nativeToken: Long,
        val myEpoch: Long
    )

    private data class TupleReset(
        val opId: String,
        val job: Job?,
        val cancelAction: (() -> Unit)?,
        val nativeToken: Long,
        val myEpoch: Long,
        val shouldLaunch: Boolean
    )

    /**
     * Starts loading a model or attaches to an identical in-flight load operation.
     */
    suspend fun loadOrAttach(
        identity: ModelLoadIdentity,
        scope: CoroutineScope,
        cancellationAction: (() -> Unit)? = null,
        loader: suspend (
            operationId: String,
            operationControlId: Long,
            updateProgress: (stage: ModelLoadStage, completedUnits: Long?, totalUnits: Long?) -> Unit
        ) -> ModelActualConfiguration
    ): ModelActualConfiguration {
        val (deferredToAwait, jobToStart) = mutex.withLock {
            if (isShutdownStalled) {
                throw IllegalStateException("Cannot start new model load: native runtime shutdown is stalled.")
            }

            // Case 1: Already ready with identical configuration
            val currentState = _loadState.value
            if (currentState is ModelLoadState.Ready && currentIdentity == identity) {
                val readyDef = CompletableDeferred<ModelActualConfiguration>()
                readyDef.complete(currentState.actualConfiguration)
                return@withLock (readyDef to null)
            }

            // Case 2: Identical in-flight loading operation exists -> capture deferred to await outside lock
            if (currentIdentity == identity && inFlightDeferred != null && inFlightJob?.isCompleted == false) {
                safeLogI("Reusing in-flight model initialization for: ${identity.modelPath}")
                return@withLock (inFlightDeferred!! to null)
            }

            // Case 3: Need to cancel previous operation if running
            val previousJob = inFlightJob
            val previousDeferred = inFlightDeferred
            val previousNativeToken = currentNativeControlId
            val previousCancelAction = activeCancellationAction

            if (previousJob != null && !previousJob.isCompleted) {
                safeLogI("Cancelling previous in-flight load to start new config")
                if (previousNativeToken != 0L) {
                    try { nativeBridge.cancelOperationControl(previousNativeToken) } catch (_: Throwable) {}
                }
                try { previousCancelAction?.invoke() } catch (_: Throwable) {}
                previousJob.cancel()
                previousDeferred?.completeExceptionally(CancellationException("Superseded by new load operation"))
            }

            val operationId = UUID.randomUUID().toString()
            val myEpoch = ++currentEpoch
            currentIdentity = identity
            currentOperationId = operationId

            val nativeToken = if (nativeBridge.isAvailable()) nativeBridge.createOperationControl() else 0L
            currentNativeControlId = nativeToken

            val opCancellationAction: () -> Unit = {
                if (nativeToken != 0L) {
                    try { nativeBridge.cancelOperationControl(nativeToken) } catch (_: Throwable) {}
                }
                try { cancellationAction?.invoke() } catch (_: Throwable) {}
            }
            activeCancellationAction = opCancellationAction

            val deferred = CompletableDeferred<ModelActualConfiguration>()
            inFlightDeferred = deferred

            val requestedAt = clock()
            val initialStage = ModelLoadStage.WaitingForRuntime
            _loadState.value = ModelLoadState.Loading(
                operationId = operationId,
                stage = initialStage,
                elapsedMs = 0L
            )

            // This ticker runs even while the loader waits for a previous native request.
            val elapsedJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(500L)
                    synchronized(progressLock) {
                        val state = _loadState.value
                        if (myEpoch == currentEpoch && currentOperationId == operationId && state is ModelLoadState.Loading) {
                            _loadState.value = state.copy(elapsedMs = (clock() - requestedAt).coerceAtLeast(0L))
                        }
                    }
                }
            }

            val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                // If there was a previous job, wait for it to cleanly complete cancellation
                if (previousJob != null && !previousJob.isCompleted) {
                    try {
                        previousJob.join()
                    } catch (_: Throwable) {}
                }

                // Verify this operation was not superseded before beginning execution
                val stillValid = mutex.withLock {
                    currentEpoch == myEpoch && currentOperationId == operationId && !isShutdownStalled
                }
                if (!stillValid) {
                    deferred.completeExceptionally(CancellationException("Superseded before starting"))
                    return@launch
                }

                val traceCookie = operationId.hashCode()
                AppTrace.beginAsyncSection("model_load", traceCookie)
                var currentTrackedStage: ModelLoadStage? = initialStage
                AppTrace.beginAsyncSection("stage_${initialStage.javaClass.simpleName}", traceCookie)

                val startTime = requestedAt
                var lastProgressTime = startTime
                var lastUiPublishTime = 0L
                var lastCompletedUnits: Long? = null
                var lastTotalUnits: Long? = null
                var currentStage: ModelLoadStage = initialStage
                var timedOut = false
                var timeoutReason: ModelLoadFailureReason? = null
                val tokenReleased = java.util.concurrent.atomic.AtomicBoolean(false)

                fun releaseNativeToken() {
                    if (nativeToken != 0L && tokenReleased.compareAndSet(false, true)) {
                        try {
                            nativeBridge.releaseOperationControl(nativeToken)
                        } catch (e: Throwable) {
                            safeLogW("Error releasing operation control $nativeToken: ${e.message}")
                        }
                    }
                }

                fun onProgressUpdate(stage: ModelLoadStage, completed: Long?, total: Long?, fromNative: Boolean = false) {
                    val now = clock()
                    val stageChanged: Boolean
                    val unitsChanged: Boolean
                    synchronized(progressLock) {
                        // Native load snapshots remain on their last phase during Kotlin warm-up.
                        if (fromNative && currentStage == ModelLoadStage.WarmingUp) return
                        stageChanged = (stage != currentStage)
                        unitsChanged = (completed != null && completed != lastCompletedUnits) || (total != null && total != lastTotalUnits)
                        if (stageChanged || unitsChanged) {
                            lastProgressTime = now
                            currentStage = stage
                            lastCompletedUnits = completed
                            lastTotalUnits = total
                        }
                    }

                    if (stageChanged) {
                        synchronized(progressLock) {
                            val prevStage = currentTrackedStage
                            if (prevStage != null) {
                                AppTrace.endAsyncSection("stage_${prevStage.javaClass.simpleName}", traceCookie)
                            }
                            currentTrackedStage = stage
                            AppTrace.beginAsyncSection("stage_${stage.javaClass.simpleName}", traceCookie)
                        }
                    }

                    synchronized(progressLock) {
                        if (myEpoch == currentEpoch && currentOperationId == operationId && !timedOut && _loadState.value !is ModelLoadState.Stopping && _loadState.value !is ModelLoadState.Failed) {
                            if (stageChanged || (now - lastUiPublishTime >= 100L)) {
                                lastUiPublishTime = now
                                _loadState.value = ModelLoadState.Loading(
                                    operationId = operationId,
                                    stage = stage,
                                    completedUnits = completed,
                                    totalUnits = total,
                                    elapsedMs = now - startTime,
                                    idleMs = now - lastProgressTime
                                )
                            }
                        }
                    }
                }

                // Native polling coroutine
                val pollingJob = launch {
                    while (isActive) {
                        delay(100L)
                        if (nativeToken != 0L) {
                            try {
                                val snapshotStr = nativeBridge.getOperationControlSnapshot(nativeToken)
                                if (!snapshotStr.isNullOrBlank()) {
                                    val obj = JSONObject(snapshotStr)
                                    val stageStr = obj.optString("stage")
                                    val comp = obj.optLong("completed_units")
                                    val tot = obj.optLong("total_units")
                                    if (stageStr.isNotBlank()) {
                                        val stage: ModelLoadStage? = when (stageStr) {
                                            "CpuLoading" -> ModelLoadStage.CpuLoading
                                            "GpuLoading" -> ModelLoadStage.GpuLoading
                                            "ContextPreparation" -> ModelLoadStage.ContextPreparation("GGUF")
                                            "CpuReference" -> ModelLoadStage.CpuReference(0, 0)
                                            "GpuProbe" -> ModelLoadStage.GpuProbe(0, 0)
                                            else -> null
                                        }
                                        if (stage != null) {
                                            onProgressUpdate(stage, if (tot > 0) comp else null, if (tot > 0) tot else null, fromNative = true)
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }

                // Watchdog coroutine
                val watchdogJob = launch {
                    while (isActive) {
                        delay(500L)
                        val now = clock()
                        val (elapsedSinceProgress, totalElapsed) = synchronized(progressLock) {
                            (now - lastProgressTime) to (now - startTime)
                        }

                        if (totalElapsed >= MAX_INITIALIZATION_TIMEOUT_MS) {
                            safeLogE("Initialization exceeded max timeout of 10 minutes (op: $operationId)")
                            timedOut = true
                            timeoutReason = ModelLoadFailureReason.TotalTimeout(totalElapsed)
                            mutex.withLock {
                                if (myEpoch == currentEpoch && currentOperationId == operationId) {
                                    _loadState.value = ModelLoadState.Stopping(operationId)
                                }
                            }
                            opCancellationAction()
                            deferred.completeExceptionally(
                                IllegalStateException(timeoutReason.toString())
                            )
                            break
                        }

                        if (elapsedSinceProgress >= PROGRESS_CANCEL_TIMEOUT_MS) {
                            safeLogE("Initialization timed out after 120s without progress (op: $operationId, stage: $currentStage)")
                            timedOut = true
                            timeoutReason = ModelLoadFailureReason.StalledInitialization(currentStage, elapsedSinceProgress)
                            mutex.withLock {
                                if (myEpoch == currentEpoch && currentOperationId == operationId) {
                                    _loadState.value = ModelLoadState.Stopping(operationId)
                                }
                            }
                            opCancellationAction()
                            deferred.completeExceptionally(
                                IllegalStateException(timeoutReason.toString())
                            )
                            break
                        }

                        if (elapsedSinceProgress >= PROGRESS_WARN_TIMEOUT_MS) {
                            synchronized(progressLock) {
                                if (myEpoch == currentEpoch && currentOperationId == operationId && _loadState.value is ModelLoadState.Loading) {
                                    _loadState.value = ModelLoadState.Loading(
                                        operationId = operationId,
                                        stage = currentStage,
                                        completedUnits = lastCompletedUnits,
                                        totalUnits = lastTotalUnits,
                                        elapsedMs = totalElapsed,
                                        idleMs = elapsedSinceProgress
                                    )
                                }
                            }
                        }
                    }
                }

                try {
                    val resultConfig = loader(operationId, nativeToken) { stage, comp, tot ->
                        onProgressUpdate(stage, comp, tot)
                    }
                    watchdogJob.cancel()
                    pollingJob.cancel()

                    releaseNativeToken()

                    mutex.withLock {
                        if (currentOperationId == operationId && currentEpoch == myEpoch) {
                            if (!timedOut && _loadState.value !is ModelLoadState.Stopping && _loadState.value !is ModelLoadState.Failed) {
                                _loadState.value = ModelLoadState.Ready(
                                    operationId = operationId,
                                    actualConfiguration = resultConfig
                                )
                                deferred.complete(resultConfig)
                            } else {
                                deferred.cancel()
                            }
                            inFlightJob = null
                            inFlightDeferred = null
                            activeCancellationAction = null
                            currentNativeControlId = 0L
                        } else {
                            deferred.completeExceptionally(CancellationException("Superseded before completion"))
                        }
                    }
                } catch (t: Throwable) {
                    watchdogJob.cancel()
                    pollingJob.cancel()
                    mutex.withLock {
                        if (myEpoch == currentEpoch && currentOperationId == operationId) {
                            val failureReason: ModelLoadFailureReason = when {
                                timeoutReason != null -> timeoutReason
                                t is CancellationException -> {
                                    val detail = t.message.orEmpty()
                                    when {
                                        detail.contains("user", ignoreCase = true) || detail.contains("stopped", ignoreCase = true) ->
                                            ModelLoadFailureReason.Cancellation("Model loading cancelled.")
                                        detail.contains("superseded", ignoreCase = true) ->
                                            ModelLoadFailureReason.Cancellation("Superseded by new load operation")
                                        else -> ModelLoadFailureReason.Cancellation(detail.ifBlank { "Operation cancelled" })
                                    }
                                }
                                t.message?.contains("CPU reference", ignoreCase = true) == true ->
                                    ModelLoadFailureReason.CpuReferenceFailure(t.message ?: "CPU reference probe failed")
                                t.message?.contains("GPU validation", ignoreCase = true) == true || t.message?.contains("GPU disagreement", ignoreCase = true) == true ->
                                    ModelLoadFailureReason.GpuDisagreement(t.message ?: "GPU numerical validation failed")
                                else ->
                                    ModelLoadFailureReason.InitializationFailure(t.message ?: "Failed to load model")
                            }
                            _loadState.value = ModelLoadState.Failed(operationId, failureReason)
                            inFlightJob = null
                            inFlightDeferred = null
                            activeCancellationAction = null
                            currentNativeControlId = 0L
                        }
                    }
                    deferred.completeExceptionally(t)
                } finally {
                    synchronized(progressLock) {
                        currentTrackedStage?.let { stage ->
                            AppTrace.endAsyncSection("stage_${stage.javaClass.simpleName}", traceCookie)
                            currentTrackedStage = null
                        }
                    }
                    AppTrace.endAsyncSection("model_load", traceCookie)
                    withContext(NonCancellable) {
                        releaseNativeToken()
                        mutex.withLock {
                            if (currentOperationId == operationId && currentEpoch == myEpoch) {
                                inFlightJob = null
                                inFlightDeferred = null
                                activeCancellationAction = null
                                currentNativeControlId = 0L
                            }
                        }
                    }
                }
            }
            job.invokeOnCompletion { elapsedJob.cancel() }
            inFlightJob = job
            (deferred to job)
        }

        jobToStart?.start()
        return deferredToAwait.await()
    }

    /**
     * Backward compatible overload for callers not taking operationControlId.
     */
    suspend fun loadOrAttach(
        identity: ModelLoadIdentity,
        scope: CoroutineScope,
        cancellationAction: (() -> Unit)? = null,
        loader: suspend (
            operationId: String,
            updateProgress: (stage: ModelLoadStage, completedUnits: Long?, totalUnits: Long?) -> Unit
        ) -> ModelActualConfiguration
    ): ModelActualConfiguration = loadOrAttach(identity, scope, cancellationAction) { opId, _, updateProgress ->
        loader(opId, updateProgress)
    }

    /**
     * Unifies shutdown across cancellation, timeout, replacement, and explicit unload.
     * Retains ownership until native work completes or 30s timeout expires.
     */
    suspend fun shutdown(
        reason: String = "Shutdown requested",
        targetOperationId: String? = null,
        unloadAction: (suspend () -> Unit)? = null
    ): ShutdownResult = withContext(Dispatchers.IO) {
        val (opId, job, cancelAction, nativeToken, myEpoch) = mutex.withLock {
            val op = currentOperationId ?: run {
                _loadState.value = ModelLoadState.Unloaded
                currentIdentity = null
                currentOperationId = null
                inFlightDeferred = null
                inFlightJob = null
                activeCancellationAction = null
                currentNativeControlId = 0L
                isShutdownStalled = false
                return@withContext ShutdownResult.Success
            }
            if (targetOperationId != null && targetOperationId != op) {
                safeLogI("Ignoring shutdown for mismatched operation: target=$targetOperationId, current=$op")
                return@withContext ShutdownResult.StaleNoOp
            }
            val epoch = ++currentEpoch
            _loadState.value = ModelLoadState.Stopping(op)
            inFlightDeferred?.completeExceptionally(CancellationException(reason))
            val j = inFlightJob
            val ca = activeCancellationAction
            val nt = currentNativeControlId
            currentNativeControlId = 0L
            TupleShutdown(op, j, ca, nt, epoch)
        }

        val traceCookie = opId.hashCode()
        AppTrace.beginAsyncSection("model_shutdown", traceCookie)
        try {
            if (nativeToken != 0L) nativeBridge.cancelOperationControl(nativeToken)
            cancelAction?.invoke()
        } catch (e: Throwable) {
            safeLogW("Error invoking cancellation callback: ${e.message}")
        }

        job?.cancel()

        var unloadThrew = false
        var unloadError: Throwable? = null
        val finishedInTime = withTimeoutOrNull(SHUTDOWN_STALL_TIMEOUT_MS) {
            try {
                job?.join()
                unloadAction?.invoke()
                true
            } catch (e: Throwable) {
                safeLogE("Shutdown failed for op $opId", e)
                unloadThrew = true
                unloadError = e
                false
            }
        } ?: false

        val result = when {
            unloadThrew -> ShutdownResult.Failed(unloadError ?: IllegalStateException("Shutdown failed for op $opId"))
            !finishedInTime || job?.isCompleted == false -> ShutdownResult.TimedOut
            else -> ShutdownResult.Success
        }

        try {
            mutex.withLock {
                if (currentEpoch == myEpoch) {
                    if (!result.isSuccess) {
                        safeLogW("Native shutdown stalled for operation $opId")
                        isShutdownStalled = true
                        _loadState.value = ModelLoadState.ShutdownStalled(opId)
                    } else {
                        isShutdownStalled = false
                        _loadState.value = ModelLoadState.Unloaded
                        currentIdentity = null
                        currentOperationId = null
                        inFlightDeferred = null
                        inFlightJob = null
                        activeCancellationAction = null
                        currentNativeControlId = 0L
                    }
                }
            }
        } finally {
            AppTrace.endAsyncSection("model_shutdown", traceCookie)
        }
        return@withContext result
    }

    /**
     * Requests cancellation of the currently active initialization operation.
     */
    suspend fun cancelActiveOperation(
        targetOperationId: String?,
        reason: String = "Cancelled by user"
    ) {
        val matches = mutex.withLock {
            targetOperationId == null || currentOperationId == targetOperationId
        }
        if (!matches) {
            safeLogI("Ignoring cancelActiveOperation for mismatched operation: target=$targetOperationId, current=$currentOperationId")
            return
        }
        shutdown(reason = reason, targetOperationId = targetOperationId)
    }

    /**
     * Resets state to Unloaded (used on idle release or explicit unload).
     * If an initialization job is running or an unload action is provided,
     * ensures completion before publishing Unloaded, or transitions to ShutdownStalled on failure/timeout.
     */
    fun resetToUnloaded(
        targetOperationId: String? = null,
        unloadAction: (suspend () -> Unit)? = null
    ): Job {
        val requestedOperation = targetOperationId ?: currentOperationId
        return controllerScope.launch {
        val (opId, job, cancelAction, nativeToken, myEpoch, shouldLaunch) = mutex.withLock {
            val op = currentOperationId ?: UUID.randomUUID().toString()
            if (requestedOperation != currentOperationId) {
                safeLogI("Ignoring resetToUnloaded for mismatched operation: target=$targetOperationId, current=$currentOperationId")
                return@launch
            }
            val epoch = ++currentEpoch
            val j = inFlightJob
            val ca = activeCancellationAction
            val nt = currentNativeControlId
            currentNativeControlId = 0L

            inFlightDeferred?.completeExceptionally(CancellationException("Reset to unloaded"))

            val hasActiveJob = j != null && !j.isCompleted
            val needsBackgroundWait = hasActiveJob || unloadAction != null

            if (!needsBackgroundWait) {
                isShutdownStalled = false
                _loadState.value = ModelLoadState.Unloaded
                currentIdentity = null
                currentOperationId = null
                inFlightDeferred = null
                inFlightJob = null
                activeCancellationAction = null
                currentNativeControlId = 0L
                TupleReset(op, j, ca, nt, epoch, false)
            } else {
                _loadState.value = ModelLoadState.Stopping(op)
                TupleReset(op, j, ca, nt, epoch, true)
            }
        }

        if (!shouldLaunch) {
            return@launch
        }

            val traceCookie = opId.hashCode()
            AppTrace.beginAsyncSection("model_reset_unload", traceCookie)
            try {
                if (nativeToken != 0L) nativeBridge.cancelOperationControl(nativeToken)
                cancelAction?.invoke()
            } catch (_: Throwable) {}

            job?.cancel()

            var unloadThrew = false
            val finished = withTimeoutOrNull(SHUTDOWN_STALL_TIMEOUT_MS) {
                try {
                    job?.join()
                    unloadAction?.invoke()
                    true
                } catch (t: Throwable) {
                    safeLogE("Unload failed during reset for op $opId", t)
                    unloadThrew = true
                    false
                }
            } ?: false

            try {
                mutex.withLock {
                    if (currentEpoch == myEpoch) {
                        if (!finished || unloadThrew || job?.isCompleted == false) {
                            safeLogW("Native shutdown stalled on reset for op $opId")
                            isShutdownStalled = true
                            _loadState.value = ModelLoadState.ShutdownStalled(opId)
                        } else {
                            isShutdownStalled = false
                            _loadState.value = ModelLoadState.Unloaded
                            currentIdentity = null
                            currentOperationId = null
                            inFlightDeferred = null
                            inFlightJob = null
                            activeCancellationAction = null
                            currentNativeControlId = 0L
                        }
                    }
                }
            } finally {
                AppTrace.endAsyncSection("model_reset_unload", traceCookie)
            }
        }
    }

    /**
     * Clears readiness (e.g. after CPU recovery failure or unexpected crash).
     */
    fun clearReadiness(errorReason: String? = null) {
        val opId = currentOperationId ?: UUID.randomUUID().toString()
        currentEpoch++
        currentIdentity = null
        if (errorReason != null) {
            _loadState.value = ModelLoadState.Failed(opId, errorReason)
        } else {
            _loadState.value = ModelLoadState.Unloaded
        }
    }
}

package com.shounak.localmeshai.ai

sealed interface ModelLoadStage {
    object WaitingForRuntime : ModelLoadStage {
        override fun toString(): String = "Waiting for runtime"
    }
    object VerifyingFile : ModelLoadStage {
        override fun toString(): String = "Verifying model file"
    }
    object General : ModelLoadStage {
        override fun toString(): String = "Initialising model"
    }
    object WarmingUp : ModelLoadStage {
        override fun toString(): String = "Preparing first response"
    }
    object CpuLoading : ModelLoadStage {
        override fun toString(): String = "Loading CPU model"
    }
    object GpuLoading : ModelLoadStage {
        override fun toString(): String = "Loading GPU model"
    }
    data class CpuReference(val promptIndex: Int = 0, val position: Int = 0) : ModelLoadStage {
        override fun toString(): String = "CPU reference probe (prompt ${promptIndex + 1}/3, pos $position/8)"
    }
    data class GpuProbe(val promptIndex: Int = 0, val position: Int = 0) : ModelLoadStage {
        override fun toString(): String = "GPU probe (prompt ${promptIndex + 1}/3, pos $position/8)"
    }
    data class ContextPreparation(val backend: String = "") : ModelLoadStage {
        override fun toString(): String = if (backend.isNotBlank()) "Preparing context on $backend" else "Preparing context"
    }
}

data class ModelActualConfiguration(
    val runtimeKind: String = "",
    val backend: String = "",
    val threads: Int = 4,
    val prefillThreads: Int = threads,
    val contextWindow: Int = 2048,
    val batchSize: Int = 256,
    val ubatchSize: Int = 128,
    val gpuLayers: Int = 0,
    val contextLength: Int = contextWindow,
    val actualComputeBackends: List<String> = emptyList(),
    val effectiveOffloadKqv: Boolean = false,
    val isCpuIsolated: Boolean = false,
    val gpuComputeBufferBytes: Long = 0L,
    val backendPolicyVersion: Int = 1
)

sealed interface ModelLoadFailureReason {
    data class Cancellation(val detail: String = "Operation cancelled") : ModelLoadFailureReason {
        override fun toString(): String = detail
    }
    data class StalledInitialization(val stage: ModelLoadStage, val elapsedMs: Long) : ModelLoadFailureReason {
        override fun toString(): String = "Model initialization timed out after ${elapsedMs / 1000}s without progress in stage: $stage"
    }
    data class TotalTimeout(val totalElapsedMs: Long) : ModelLoadFailureReason {
        override fun toString(): String = "Model initialization exceeded maximum limit (${totalElapsedMs / 1000}s)"
    }
    data class CpuReferenceFailure(val reason: String) : ModelLoadFailureReason {
        override fun toString(): String = "CPU reference probe failed: $reason"
    }
    data class GpuDisagreement(val reason: String) : ModelLoadFailureReason {
        override fun toString(): String = "GPU numerical validation failed: $reason"
    }
    data class InitializationFailure(val reason: String) : ModelLoadFailureReason {
        override fun toString(): String = reason
    }
    data class StalledShutdown(val elapsedMs: Long) : ModelLoadFailureReason {
        override fun toString(): String = "Native runtime shutdown stalled after ${elapsedMs / 1000}s"
    }
}

sealed class ModelLoadState {
    object Unloaded : ModelLoadState()

    data class Loading(
        val operationId: String,
        val stage: ModelLoadStage,
        val completedUnits: Long? = null,
        val totalUnits: Long? = null,
        val elapsedMs: Long = 0L,
        val idleMs: Long = 0L
    ) : ModelLoadState() {
        val progressFraction: Float?
            get() = if (completedUnits != null && totalUnits != null && totalUnits > 0L) {
                (completedUnits.toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f)
            } else null

        val isStalledWarning: Boolean
            get() = idleMs >= 30_000L
    }

    data class Ready(
        val operationId: String,
        val actualConfiguration: ModelActualConfiguration
    ) : ModelLoadState()

    data class Stopping(val operationId: String) : ModelLoadState()

    data class Failed(
        val operationId: String,
        val reason: ModelLoadFailureReason,
        val typedError: String = reason.toString()
    ) : ModelLoadState() {
        constructor(operationId: String, errorText: String) : this(
            operationId = operationId,
            reason = when {
                errorText.contains("cancel", ignoreCase = true) -> ModelLoadFailureReason.Cancellation(errorText)
                errorText.contains("timed out", ignoreCase = true) || errorText.contains("without progress", ignoreCase = true) ->
                    ModelLoadFailureReason.StalledInitialization(ModelLoadStage.General, 120_000L)
                errorText.contains("10-minute", ignoreCase = true) || errorText.contains("maximum", ignoreCase = true) ->
                    ModelLoadFailureReason.TotalTimeout(600_000L)
                errorText.contains("CPU reference", ignoreCase = true) -> ModelLoadFailureReason.CpuReferenceFailure(errorText)
                errorText.contains("GPU validation", ignoreCase = true) || errorText.contains("GPU disagreement", ignoreCase = true) ->
                    ModelLoadFailureReason.GpuDisagreement(errorText)
                errorText.contains("shutdown", ignoreCase = true) -> ModelLoadFailureReason.StalledShutdown(30_000L)
                else -> ModelLoadFailureReason.InitializationFailure(errorText)
            },
            typedError = errorText
        )
    }

    data class ShutdownStalled(val operationId: String) : ModelLoadState()
}

sealed class ShutdownResult {
    object Success : ShutdownResult()
    data class Failed(val error: Throwable) : ShutdownResult()
    object TimedOut : ShutdownResult()
    object StaleNoOp : ShutdownResult()

    val isSuccess: Boolean get() = this is Success
    val isStaleNoOp: Boolean get() = this is StaleNoOp
}

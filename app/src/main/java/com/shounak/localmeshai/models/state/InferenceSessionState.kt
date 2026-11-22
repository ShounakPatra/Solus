package com.shounak.localmeshai.models.state

import com.shounak.localmeshai.ai.ModelActualConfiguration
import com.shounak.localmeshai.ai.ModelLoadFailureReason
import com.shounak.localmeshai.ai.ModelLoadStage

/**
 * Unified, explicit state machine for the active LLM or Vision inference session.
 * Eliminates combinatorial hazards from disparate boolean flags:
 * isInitializing, isGenerating, isAnalyzing, isStopping, isModelReady, error.
 */
sealed interface InferenceSessionState {
    val isBusy: Boolean get() = this is Preparing || this is Generating || this is Stopping
    val canAcceptPrompt: Boolean get() = this is Ready
    val canRequestStop: Boolean get() = this is Generating || this is Preparing
    val statusKey: String

    /** No model is currently loaded in memory. */
    data class Idle(
        val reason: String = "No model loaded"
    ) : InferenceSessionState {
        override val statusKey: String = "idle"
    }

    /** Model runtime is initializing, allocating context/KV-cache, or warming up. */
    data class Preparing(
        val modelId: String = "",
        val stage: ModelLoadStage = ModelLoadStage.General,
        val progressFraction: Float? = null,
        val elapsedMs: Long = 0L
    ) : InferenceSessionState {
        override val statusKey: String = "preparing"
    }

    /** Model is hot in memory and ready for prompt submission. */
    data class Ready(
        val modelId: String = "",
        val backendDisplayName: String = "",
        val configuration: ModelActualConfiguration? = null
    ) : InferenceSessionState {
        override val statusKey: String = "ready"
    }

    /** Actively streaming inference tokens or executing multi-turn tools. */
    data class Generating(
        val modelId: String = "",
        val requestId: String = "",
        val tokensPerSecond: Float = 0f,
        val isThinking: Boolean = false,
        val activeToolName: String? = null
    ) : InferenceSessionState {
        override val statusKey: String = "generating"
    }

    /** Cancellation requested; native execution thread is draining safely. */
    data class Stopping(
        val modelId: String = "",
        val requestId: String = "",
        val targetState: String = "ready"
    ) : InferenceSessionState {
        override val statusKey: String = "stopping"
    }

    /** Model loading or inference generation failed with a typed reason. */
    data class Failed(
        val modelId: String? = null,
        val reason: ModelLoadFailureReason = ModelLoadFailureReason.InitializationFailure("Unknown error"),
        val canRetry: Boolean = true
    ) : InferenceSessionState {
        override val statusKey: String = "failed"
    }

    /** Machine-readable representation for autonomous agents and structured telemetry (AX). */
    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "isBusy" to isBusy,
        "canAcceptPrompt" to canAcceptPrompt,
        "canRequestStop" to canRequestStop,
        "details" to when (this) {
            is Idle -> mapOf("reason" to reason)
            is Preparing -> mapOf("modelId" to modelId, "stage" to stage.toString(), "progress" to progressFraction)
            is Ready -> mapOf("modelId" to modelId, "backend" to backendDisplayName)
            is Generating -> mapOf("modelId" to modelId, "requestId" to requestId, "tps" to tokensPerSecond, "isThinking" to isThinking, "tool" to activeToolName)
            is Stopping -> mapOf("modelId" to modelId, "requestId" to requestId, "targetState" to targetState)
            is Failed -> mapOf("modelId" to modelId, "reason" to reason.toString(), "canRetry" to canRetry)
        }
    )
}

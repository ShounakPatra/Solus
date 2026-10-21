package com.shounak.localmeshai.ai

/**
 * Authoritative terminal response model for model generation.
 */
sealed class FinalizedResponse {
    abstract val requestId: String
    abstract val attemptId: Int
    abstract val answerText: String
    abstract val reasoningText: String
    abstract val status: RequestTerminalStatus
    abstract val error: String?
    abstract val emptyReason: EmptyResponseReason?

    data class Completed(
        override val requestId: String,
        override val attemptId: Int,
        override val answerText: String,
        override val reasoningText: String = ""
    ) : FinalizedResponse() {
        override val status: RequestTerminalStatus get() = RequestTerminalStatus.Completed
        override val error: String? get() = null
        override val emptyReason: EmptyResponseReason? get() = null
    }

    data class Stopped(
        override val requestId: String,
        override val attemptId: Int,
        override val answerText: String,
        override val reasoningText: String = ""
    ) : FinalizedResponse() {
        override val status: RequestTerminalStatus get() = RequestTerminalStatus.Stopped
        override val error: String? get() = null
        override val emptyReason: EmptyResponseReason? get() = null
    }

    data class Failed(
        override val requestId: String,
        override val attemptId: Int,
        override val answerText: String,
        override val reasoningText: String = "",
        override val error: String,
        override val emptyReason: EmptyResponseReason? = null
    ) : FinalizedResponse() {
        override val status: RequestTerminalStatus get() = RequestTerminalStatus.Failed
    }

    fun toExecutionResult(metrics: InferenceMetrics? = null): InferenceExecutionResult {
        return InferenceExecutionResult(
            text = answerText,
            status = status,
            error = error,
            metrics = metrics,
            reasoningText = reasoningText.ifBlank { null },
            emptyReason = emptyReason,
            requestId = requestId,
            attemptId = attemptId
        )
    }
}

/**
 * Single authoritative finalizer returning typed terminal outcomes.
 * Guarantees that completed outputs are non-blank, partial text is preserved on error/stop,
 * and blank terminal events never overwrite previously accepted text.
 */
object ResponseFinalizer {
    const val FAILED_NO_ANSWER_MESSAGE = "The model finished without an answer. Retry this message."

    fun finalize(
        requestId: String,
        attemptId: Int = 1,
        rawAnswer: String,
        rawReasoning: String = "",
        isStopped: Boolean = false,
        sdkError: Throwable? = null,
        acceptedAnswerFallback: String = "",
        acceptedReasoningFallback: String = "",
        thinkingMode: Boolean = false
    ): FinalizedResponse {
        val trimmedRawAnswer = rawAnswer.trim()
        val trimmedFallback = acceptedAnswerFallback.trim()
        // Blank terminal event cannot overwrite an accepted answer from the same attempt
        val effectiveAnswer = if (trimmedRawAnswer.isNotEmpty()) trimmedRawAnswer else trimmedFallback
        val trimmedRawReasoning = rawReasoning.trim()
        val trimmedReasoningFallback = acceptedReasoningFallback.trim()
        val effectiveReasoning = if (trimmedRawReasoning.isNotEmpty()) trimmedRawReasoning else trimmedReasoningFallback

        if (isStopped) {
            return FinalizedResponse.Stopped(
                requestId = requestId,
                attemptId = attemptId,
                answerText = effectiveAnswer,
                reasoningText = effectiveReasoning
            )
        }

        if (sdkError != null) {
            val errorMsg = sdkError.localizedMessage ?: sdkError.message ?: "Inference failed"
            val reason = if (effectiveAnswer.isBlank()) {
                if (effectiveReasoning.isNotBlank()) EmptyResponseReason.ReasoningOnly else EmptyResponseReason.NoVisibleAnswer
            } else null
            return FinalizedResponse.Failed(
                requestId = requestId,
                attemptId = attemptId,
                answerText = effectiveAnswer,
                reasoningText = effectiveReasoning,
                error = errorMsg,
                emptyReason = reason
            )
        }

        if (effectiveAnswer.isNotBlank()) {
            return FinalizedResponse.Completed(
                requestId = requestId,
                attemptId = attemptId,
                answerText = effectiveAnswer,
                reasoningText = effectiveReasoning
            )
        }

        // Answer is blank!
        return if (effectiveReasoning.isNotBlank()) {
            FinalizedResponse.Failed(
                requestId = requestId,
                attemptId = attemptId,
                answerText = "",
                reasoningText = effectiveReasoning,
                error = FAILED_NO_ANSWER_MESSAGE,
                emptyReason = EmptyResponseReason.ReasoningOnly
            )
        } else {
            FinalizedResponse.Failed(
                requestId = requestId,
                attemptId = attemptId,
                answerText = "",
                reasoningText = "",
                error = FAILED_NO_ANSWER_MESSAGE,
                emptyReason = EmptyResponseReason.NoVisibleAnswer
            )
        }
    }
}

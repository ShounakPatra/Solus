package com.shounak.localmeshai.ai

import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ThinkingTextUtils

/**
 * Controller managing streaming responses, maintaining separate buffers for
 * reasoning (thinking) tokens and final answer tokens, supporting multiple
 * reasoning tag formats, unclosed block recovery, and unified response finalization.
 */
class InferenceResponseController(
    val thinkingMode: Boolean = true,
    private val rawUserText: String = "",
    val requestId: String = "",
    initialAttemptId: Int = 1
) {
    private val lock = Any()
    var currentAttemptId: Int = initialAttemptId
        private set

    private val rawBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()
    private val answerBuffer = StringBuilder()
    private var acceptedAnswer: String = ""
    private var acceptedReasoning: String = ""
    private var structuredThinkingActive: Boolean? = null

    private var isFinished: Boolean = false
    var isStructuredMode: Boolean = false

    val isThinkingActive: Boolean
        get() = synchronized(lock) {
            if (isFinished) false
            else structuredThinkingActive ?: (thinkingMode && ThinkingTextUtils.parse(ThinkingTextUtils.holdIncompleteTag(rawBuffer.toString()), allowActiveThinking = true).isThinkingActive)
        }

    val hasThinkingBlock: Boolean
        get() = synchronized(lock) {
            if (isFinished) thinkingBuffer.isNotEmpty()
            else if (structuredThinkingActive != null || isStructuredMode) (thinkingBuffer.isNotEmpty() || structuredThinkingActive == true)
            else (thinkingMode && ThinkingTextUtils.parse(ThinkingTextUtils.holdIncompleteTag(rawBuffer.toString()), allowActiveThinking = true).let {
                it.isThinkingActive || !it.thinkingText.isNullOrBlank()
            })
        }

    /**
     * Clears all attempt-local state and buffers when beginning a new attempt.
     */
    fun beginAttempt(newAttemptId: Int) {
        synchronized(lock) {
            currentAttemptId = newAttemptId
            isFinished = false
            isStructuredMode = false
            rawBuffer.clear()
            thinkingBuffer.clear()
            answerBuffer.clear()
            acceptedAnswer = ""
            acceptedReasoning = ""
            structuredThinkingActive = null
        }
    }

    /**
     * Ingests a new delta token or chunk from the model stream.
     * Rejects callbacks from obsolete requests or attempts, or after finalization.
     * Appends tokens under synchronization without reparsing on every token.
     */
    fun appendDelta(delta: String, reqId: String? = null, attemptId: Int? = null) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            if (isFinished) return
            if (reqId != null && requestId.isNotEmpty() && reqId != requestId) return
            if (attemptId != null && attemptId != currentAttemptId) return
            rawBuffer.append(delta)
        }
    }

    /**
     * Ingests pre-parsed answer and reasoning snapshots from native/pipeline stream.
     */
    fun appendStructured(
        answer: String,
        reasoning: String,
        isThinkingActive: Boolean,
        reqId: String? = null,
        attemptId: Int? = null
    ) {
        synchronized(lock) {
            if (isFinished) return
            if (reqId != null && requestId.isNotEmpty() && reqId != requestId) return
            if (attemptId != null && attemptId != currentAttemptId) return
            isStructuredMode = true
            structuredThinkingActive = if (thinkingMode) isThinkingActive else false
            if (reasoning.isNotEmpty()) {
                val cleanReasoning = if (thinkingMode) reasoning.trim() else ""
                thinkingBuffer.clear()
                thinkingBuffer.append(cleanReasoning)
                if (cleanReasoning.isNotBlank()) {
                    acceptedReasoning = cleanReasoning
                }
            }
            if (answer.isNotEmpty()) {
                val cleaned = ModelOutputSanitizer.cleanAssistantText(answer, rawUserText)
                answerBuffer.clear()
                answerBuffer.append(cleaned)
                if (cleaned.isNotBlank()) {
                    acceptedAnswer = cleaned
                }
            }
        }
    }

    fun snapshot(revision: Int = 0): StructuredResponseSnapshot = synchronized(lock) {
        if (isStructuredMode || structuredThinkingActive != null || (thinkingBuffer.isNotEmpty() && answerBuffer.isNotEmpty())) {
            val think = if (thinkingMode) thinkingBuffer.toString().trim() else ""
            val ans = answerBuffer.toString().trim()
            StructuredResponseSnapshot(
                requestId = requestId,
                attemptId = currentAttemptId,
                revision = revision,
                answerText = ans,
                reasoningText = think,
                isThinkingActive = if (isFinished || !thinkingMode) false else (structuredThinkingActive ?: false)
            )
        } else {
            val raw = rawBuffer.toString()
            val safeRaw = ThinkingTextUtils.holdIncompleteTag(raw)
            if (!thinkingMode) {
                val extracted = ThinkingTextUtils.extractFinalAnswerOnly(safeRaw)
                val cleaned = ModelOutputSanitizer.cleanAssistantText(extracted, rawUserText)
                StructuredResponseSnapshot(
                    requestId = requestId,
                    attemptId = currentAttemptId,
                    revision = revision,
                    answerText = ThinkingTextUtils.removeLooseThinkTags(cleaned),
                    reasoningText = "",
                    isThinkingActive = false
                )
            } else {
                val parsed = ThinkingTextUtils.parse(safeRaw, allowActiveThinking = true)
                val thinking = parsed.thinkingText.orEmpty().trim()
                val answer = ModelOutputSanitizer.cleanAssistantText(parsed.finalResponseText, rawUserText)
                StructuredResponseSnapshot(
                    requestId = requestId,
                    attemptId = currentAttemptId,
                    revision = revision,
                    answerText = answer,
                    reasoningText = thinking,
                    isThinkingActive = if (isFinished) false else parsed.isThinkingActive
                )
            }
        }
    }

    /**
     * Formats an immutable snapshot of raw accumulated text for live UI presentation.
     * When thinking mode is disabled, thinking blocks and tags are completely suppressed.
     */
    fun formatSnapshot(raw: String): String {
        if (!thinkingMode) {
            val safeSnapshot = ThinkingTextUtils.holdIncompleteTag(raw)
            val extracted = ThinkingTextUtils.extractFinalAnswerOnly(safeSnapshot)
            val cleaned = ModelOutputSanitizer.cleanAssistantText(extracted, rawUserText)
            return ThinkingTextUtils.removeLooseThinkTags(cleaned)
        }

        val safeRaw = ThinkingTextUtils.holdIncompleteTag(raw)
        val parsed = ThinkingTextUtils.parse(safeRaw, allowActiveThinking = true)
        val thinking = parsed.thinkingText.orEmpty()
        val answer = parsed.finalResponseText

        return when {
            parsed.isThinkingActive -> {
                if (answer.isNotBlank()) {
                    "${ModelOutputSanitizer.cleanAssistantText(answer, rawUserText)}\n\n<think>\n$thinking"
                } else {
                    "<think>\n$thinking"
                }
            }
            thinking.isNotBlank() -> {
                buildString {
                    append("<think>\n")
                    append(thinking)
                    append("\n</think>")
                    if (answer.isNotBlank()) {
                        append("\n\n")
                        append(ModelOutputSanitizer.cleanAssistantText(answer, rawUserText))
                    }
                }
            }
            else -> ModelOutputSanitizer.cleanAssistantText(answer, rawUserText)
        }
    }

    /**
     * Live formatting for current raw buffer or structured response.
     */
    fun currentFormattedText(): String = synchronized(lock) {
        if (isStructuredMode) {
            answerBuffer.toString().trim().ifBlank { acceptedAnswer.trim() }
        } else {
            val raw = rawBuffer.toString()
            formatSnapshot(raw)
        }
    }

    /**
     * Formats explicit finalized answer and reasoning for terminal publication.
     */
    fun formatFinal(answerText: String, reasoningText: String): String {
        if (isStructuredMode) {
            return answerText.trim()
        }
        if (!thinkingMode) {
            return ThinkingTextUtils.removeLooseThinkTags(answerText.trim())
        }
        val cleanReasoning = reasoningText.trim()
        val cleanAnswer = answerText.trim()
        return when {
            cleanReasoning.isNotBlank() && cleanAnswer.isNotBlank() ->
                "<think>\n$cleanReasoning\n</think>\n\n$cleanAnswer"
            cleanReasoning.isNotBlank() ->
                "<think>\n$cleanReasoning\n</think>"
            else -> cleanAnswer
        }
    }

    /**
     * Finalizes stream processing at EOF. If an opening reasoning tag was left unclosed,
     * the thinking block is safely closed without leaking its contents into the answer buffer.
     */
    fun finish(): InferenceResponseController {
        synchronized(lock) {
            isFinished = true
            structuredThinkingActive = false
            if (!isStructuredMode && rawBuffer.isNotEmpty()) {
                val raw = rawBuffer.toString()
                val parsed = ThinkingTextUtils.parse(raw, allowActiveThinking = false)
                val thinking = if (thinkingMode) parsed.thinkingText.orEmpty().trim() else ""
                val rawAnswer = if (!thinkingMode) {
                    ThinkingTextUtils.extractFinalAnswerOnly(raw)
                } else {
                    ThinkingTextUtils.finalResponseOrReasoning(raw, allowReasoningFallback = false)
                }
                val cleanAnswer = ModelOutputSanitizer.cleanAssistantText(rawAnswer, rawUserText)

                thinkingBuffer.clear()
                thinkingBuffer.append(thinking)
                if (thinking.isNotBlank()) {
                    acceptedReasoning = thinking
                }

                answerBuffer.clear()
                answerBuffer.append(cleanAnswer)
                if (cleanAnswer.isNotBlank()) {
                    acceptedAnswer = cleanAnswer
                }
            } else {
                if (thinkingBuffer.isNotEmpty() && thinkingBuffer.isNotBlank()) {
                    acceptedReasoning = thinkingBuffer.toString().trim()
                }
                if (answerBuffer.isNotEmpty() && answerBuffer.isNotBlank()) {
                    acceptedAnswer = answerBuffer.toString().trim()
                }
            }
        }
        return this
    }

    fun currentThinkingText(): String = synchronized(lock) {
        if (!thinkingMode) ""
        else if (isStructuredMode) thinkingBuffer.toString().trim().ifBlank { acceptedReasoning.trim() }
        else if (thinkingBuffer.isNotEmpty()) thinkingBuffer.toString().trim()
        else ThinkingTextUtils.parse(rawBuffer.toString(), allowActiveThinking = true).thinkingText.orEmpty().trim()
    }

    fun currentAnswerText(): String = synchronized(lock) {
        if (isStructuredMode) {
            answerBuffer.toString().trim().ifBlank { acceptedAnswer.trim() }
        } else if (answerBuffer.isNotEmpty()) {
            answerBuffer.toString().trim()
        } else {
            val raw = rawBuffer.toString()
            val clean = if (!thinkingMode) ThinkingTextUtils.extractFinalAnswerOnly(raw)
            else ThinkingTextUtils.finalResponseOrReasoning(raw, allowReasoningFallback = false)
            ModelOutputSanitizer.cleanAssistantText(clean, rawUserText)
        }
    }

    fun acceptedAnswerText(): String = synchronized(lock) {
        acceptedAnswer.ifBlank { answerBuffer.toString().trim() }
    }

    fun acceptedReasoningText(): String = synchronized(lock) {
        if (!thinkingMode) ""
        else acceptedReasoning.ifBlank { thinkingBuffer.toString().trim() }
    }

    fun getRawText(): String = synchronized(lock) { rawBuffer.toString() }

    /**
     * Extracts the final answer text outside thinking blocks.
     */
    fun getFinalAnswer(): String = synchronized(lock) {
        if (isStructuredMode) {
            answerBuffer.toString().trim().ifBlank { acceptedAnswer.trim() }
        } else if (answerBuffer.isNotEmpty()) {
            answerBuffer.toString().trim()
        } else {
            val raw = rawBuffer.toString()
            val clean = if (!thinkingMode) {
                ThinkingTextUtils.extractFinalAnswerOnly(raw)
            } else {
                ThinkingTextUtils.finalResponseOrReasoning(raw, allowReasoningFallback = false)
            }
            ModelOutputSanitizer.cleanAssistantText(clean, rawUserText)
        }
    }

    /**
     * Extracts internal reasoning text when thinking mode is enabled; returns empty when disabled.
     */
    fun getReasoningText(): String = synchronized(lock) {
        if (!thinkingMode) return ""
        if (isStructuredMode) {
            thinkingBuffer.toString().trim().ifBlank { acceptedReasoning.trim() }
        } else if (thinkingBuffer.isNotEmpty()) {
            thinkingBuffer.toString().trim()
        } else {
            val raw = rawBuffer.toString()
            ThinkingTextUtils.parse(raw, allowActiveThinking = false).thinkingText.orEmpty().trim()
        }
    }

    /**
     * Unifies final response construction via ResponseFinalizer.
     */
    fun finalizeResponse(
        requestId: String = this.requestId,
        attemptId: Int = this.currentAttemptId,
        isStopped: Boolean = false,
        sdkError: Throwable? = null,
        acceptedAnswerFallback: String = acceptedAnswerText(),
        acceptedReasoningFallback: String = acceptedReasoningText()
    ): FinalizedResponse {
        finish()
        val answer = getFinalAnswer()
        val reasoning = getReasoningText()

        return ResponseFinalizer.finalize(
            requestId = requestId,
            attemptId = attemptId,
            rawAnswer = answer,
            rawReasoning = reasoning,
            isStopped = isStopped,
            sdkError = sdkError,
            acceptedAnswerFallback = acceptedAnswerFallback,
            acceptedReasoningFallback = acceptedReasoningFallback,
            thinkingMode = thinkingMode
        )
    }
}

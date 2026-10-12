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

    private var isFinished: Boolean = false

    val isThinkingActive: Boolean
        get() = synchronized(lock) {
            if (isFinished) false
            else (thinkingMode && ThinkingTextUtils.parse(ThinkingTextUtils.holdIncompleteTag(rawBuffer.toString()), allowActiveThinking = true).isThinkingActive)
        }

    val hasThinkingBlock: Boolean
        get() = synchronized(lock) {
            if (isFinished) thinkingBuffer.isNotEmpty()
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
            rawBuffer.clear()
            thinkingBuffer.clear()
            answerBuffer.clear()
            acceptedAnswer = ""
        }
    }

    /**
     * Ingests a new delta token or chunk from the model stream.
     * Rejects callbacks from obsolete requests or attempts.
     * Appends tokens under synchronization without reparsing on every token.
     */
    fun appendDelta(delta: String, reqId: String? = null, attemptId: Int? = null) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            if (reqId != null && requestId.isNotEmpty() && reqId != requestId) return
            if (attemptId != null && attemptId != currentAttemptId) return
            rawBuffer.append(delta)
        }
    }

data class StructuredResponseSnapshot(
    val answer: String,
    val reasoning: String,
    val attempt: Int,
    val revision: Int
)

    fun snapshot(revision: Int = 0): StructuredResponseSnapshot = synchronized(lock) {
        val raw = rawBuffer.toString()
        val safeRaw = ThinkingTextUtils.holdIncompleteTag(raw)
        if (!thinkingMode) {
            val extracted = ThinkingTextUtils.extractFinalAnswerOnly(safeRaw)
            val cleaned = ModelOutputSanitizer.cleanAssistantText(extracted, rawUserText)
            StructuredResponseSnapshot(
                answer = ThinkingTextUtils.removeLooseThinkTags(cleaned),
                reasoning = "",
                attempt = currentAttemptId,
                revision = revision
            )
        } else {
            val parsed = ThinkingTextUtils.parse(safeRaw, allowActiveThinking = true)
            val thinking = parsed.thinkingText.orEmpty().trim()
            val answer = ModelOutputSanitizer.cleanAssistantText(parsed.finalResponseText, rawUserText)
            StructuredResponseSnapshot(
                answer = answer,
                reasoning = thinking,
                attempt = currentAttemptId,
                revision = revision
            )
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
     * Live formatting for current raw buffer.
     */
    fun currentFormattedText(): String {
        val raw = synchronized(lock) { rawBuffer.toString() }
        return formatSnapshot(raw)
    }

    /**
     * Formats explicit finalized answer and reasoning for terminal publication.
     */
    fun formatFinal(answerText: String, reasoningText: String): String {
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
        val raw = synchronized(lock) { rawBuffer.toString() }
        val parsed = ThinkingTextUtils.parse(raw, allowActiveThinking = false)
        val thinking = if (thinkingMode) parsed.thinkingText.orEmpty().trim() else ""
        val rawAnswer = if (!thinkingMode) {
            ThinkingTextUtils.extractFinalAnswerOnly(raw)
        } else {
            ThinkingTextUtils.finalResponseOrReasoning(raw, allowReasoningFallback = false)
        }
        val cleanAnswer = ModelOutputSanitizer.cleanAssistantText(rawAnswer, rawUserText)

        synchronized(lock) {
            isFinished = true
            thinkingBuffer.clear()
            thinkingBuffer.append(thinking)

            answerBuffer.clear()
            answerBuffer.append(cleanAnswer)
            if (cleanAnswer.isNotBlank()) {
                acceptedAnswer = cleanAnswer
            }
        }
        return this
    }

    fun currentThinkingText(): String = synchronized(lock) {
        if (thinkingBuffer.isNotEmpty()) thinkingBuffer.toString().trim()
        else {
            if (!thinkingMode) ""
            else ThinkingTextUtils.parse(rawBuffer.toString(), allowActiveThinking = true).thinkingText.orEmpty().trim()
        }
    }

    fun currentAnswerText(): String = synchronized(lock) {
        if (answerBuffer.isNotEmpty()) answerBuffer.toString().trim()
        else {
            val raw = rawBuffer.toString()
            val clean = if (!thinkingMode) ThinkingTextUtils.extractFinalAnswerOnly(raw)
            else ThinkingTextUtils.finalResponseOrReasoning(raw, allowReasoningFallback = false)
            ModelOutputSanitizer.cleanAssistantText(clean, rawUserText)
        }
    }

    fun acceptedAnswerText(): String = synchronized(lock) {
        acceptedAnswer.ifBlank { answerBuffer.toString().trim() }
    }

    fun getRawText(): String = synchronized(lock) { rawBuffer.toString() }

    /**
     * Extracts the final answer text outside thinking blocks.
     */
    fun getFinalAnswer(): String = synchronized(lock) {
        if (answerBuffer.isNotEmpty()) {
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
        if (thinkingBuffer.isNotEmpty()) {
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
        acceptedAnswerFallback: String = acceptedAnswerText()
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
            thinkingMode = thinkingMode
        )
    }
}

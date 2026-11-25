package com.shounak.localmeshai.ai

import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ThinkingTextUtils

/**
 * Controller managing streaming responses, maintaining separate buffers for
 * reasoning (thinking) tokens and final answer tokens, supporting multiple
 * reasoning tag formats, unclosed block recovery, and unified response finalization.
 */
class InferenceResponseController(
    @Suppress("unused")
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
            else structuredThinkingActive ?: ThinkingTextUtils.parse(ThinkingTextUtils.holdIncompleteTag(rawBuffer.toString()), allowActiveThinking = true).isThinkingActive
        }

    val hasThinkingBlock: Boolean
        get() = synchronized(lock) {
            if (isFinished) thinkingBuffer.isNotEmpty()
            else if (structuredThinkingActive != null || isStructuredMode) (thinkingBuffer.isNotEmpty() || structuredThinkingActive == true)
            else ThinkingTextUtils.parse(ThinkingTextUtils.holdIncompleteTag(rawBuffer.toString()), allowActiveThinking = true).let {
                it.isThinkingActive || !it.thinkingText.isNullOrBlank()
            }
        }

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

    fun appendDelta(delta: String, reqId: String? = null, attemptId: Int? = null) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            if (isFinished) return
            if (reqId != null && requestId.isNotEmpty() && reqId != requestId) return
            if (attemptId != null && attemptId != currentAttemptId) return
            rawBuffer.append(delta)
        }
    }

    fun appendStructured(
        answer: String,
        reasoning: String,
        isThinkingActive: Boolean,
        reqId: String? = null,
        attemptId: Int? = null,
        replaceSnapshot: Boolean = false
    ) {
        synchronized(lock) {
            if (isFinished) return
            if (reqId != null && requestId.isNotEmpty() && reqId != requestId) return
            if (attemptId != null && attemptId != currentAttemptId) return
            if (replaceSnapshot) {
                answerBuffer.clear()
                thinkingBuffer.clear()
                acceptedAnswer = ""
                acceptedReasoning = ""
            }
            isStructuredMode = true
            structuredThinkingActive = isThinkingActive
            if (reasoning.isNotEmpty()) {
                val cleanReasoning = reasoning.trim()
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
            val think = thinkingBuffer.toString().trim()
            val ans = answerBuffer.toString().trim()
            StructuredResponseSnapshot(
                requestId = requestId,
                attemptId = currentAttemptId,
                revision = revision,
                answerText = ans,
                reasoningText = think,
                isThinkingActive = if (isFinished) false else (structuredThinkingActive ?: false)
            )
        } else {
            val raw = rawBuffer.toString()
            val safeRaw = ThinkingTextUtils.holdIncompleteTag(raw)
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

    fun formatSnapshot(raw: String): String {
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

    fun currentFormattedText(): String = synchronized(lock) {
        if (isStructuredMode) {
            answerBuffer.toString().trim().ifBlank { acceptedAnswer.trim() }
        } else {
            formatSnapshot(rawBuffer.toString())
        }
    }

    fun formatFinal(answerText: String, reasoningText: String): String {
        if (isStructuredMode) {
            return answerText.trim()
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
     * Finalizes stream processing at EOF. When [recoverUnclosedOnCompletion] is true (normal
     * generation completion), any opened thought block that never emitted a closing tag and
     * produced no separate answer is promoted gracefully to the visible answer instead of
     * trapping the reply in an empty/failed bubble.
     */
    fun finish(recoverUnclosedOnCompletion: Boolean = true): InferenceResponseController {
        synchronized(lock) {
            isFinished = true
            structuredThinkingActive = false
            if (!isStructuredMode && rawBuffer.isNotEmpty()) {
                val raw = rawBuffer.toString()
                val parsed = ThinkingTextUtils.parse(
                    text = raw,
                    allowActiveThinking = false,
                    recoverUnclosedOnCompletion = recoverUnclosedOnCompletion
                )
                val thinking = parsed.thinkingText.orEmpty().trim()
                val rawAnswer = if (parsed.finalResponseText.isNotBlank()) {
                    parsed.finalResponseText
                } else {
                    ""
                }
                val cleanAnswer = ModelOutputSanitizer.cleanAssistantText(rawAnswer, rawUserText)

                thinkingBuffer.clear()
                thinkingBuffer.append(thinking)
                if (thinking.isNotBlank()) {
                    acceptedReasoning = thinking
                } else if (recoverUnclosedOnCompletion && cleanAnswer.isNotBlank()) {
                    acceptedReasoning = ""
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
        if (isStructuredMode || isFinished) thinkingBuffer.toString().trim().ifBlank { acceptedReasoning.trim() }
        else if (thinkingBuffer.isNotEmpty()) thinkingBuffer.toString().trim()
        else ThinkingTextUtils.parse(rawBuffer.toString(), allowActiveThinking = true).thinkingText.orEmpty().trim()
    }

    fun currentAnswerText(): String = synchronized(lock) {
        if (isStructuredMode || isFinished) {
            answerBuffer.toString().trim().ifBlank { acceptedAnswer.trim() }
        } else if (answerBuffer.isNotEmpty()) {
            answerBuffer.toString().trim()
        } else {
            val clean = ThinkingTextUtils.finalResponseOrReasoning(rawBuffer.toString(), allowReasoningFallback = false)
            ModelOutputSanitizer.cleanAssistantText(clean, rawUserText)
        }
    }

    fun acceptedAnswerText(): String = synchronized(lock) {
        acceptedAnswer.ifBlank { answerBuffer.toString().trim() }
    }

    fun acceptedReasoningText(): String = synchronized(lock) {
        acceptedReasoning.ifBlank { thinkingBuffer.toString().trim() }
    }

    fun getRawText(): String = synchronized(lock) { rawBuffer.toString() }

    fun getFinalAnswer(): String = synchronized(lock) {
        if (isStructuredMode || isFinished) {
            answerBuffer.toString().trim().ifBlank { acceptedAnswer.trim() }
        } else if (answerBuffer.isNotEmpty()) {
            answerBuffer.toString().trim()
        } else {
            val clean = ThinkingTextUtils.finalResponseOrReasoning(rawBuffer.toString(), allowReasoningFallback = false)
            ModelOutputSanitizer.cleanAssistantText(clean, rawUserText)
        }
    }

    fun getReasoningText(): String = synchronized(lock) {
        if (isStructuredMode || isFinished) {
            thinkingBuffer.toString().trim().ifBlank { acceptedReasoning.trim() }
        } else if (thinkingBuffer.isNotEmpty()) {
            thinkingBuffer.toString().trim()
        } else {
            ThinkingTextUtils.parse(rawBuffer.toString(), allowActiveThinking = false).thinkingText.orEmpty().trim()
        }
    }

    fun finalizeResponse(
        requestId: String = this.requestId,
        attemptId: Int = this.currentAttemptId,
        isStopped: Boolean = false,
        sdkError: Throwable? = null,
        acceptedAnswerFallback: String = acceptedAnswerText(),
        acceptedReasoningFallback: String = acceptedReasoningText()
    ): FinalizedResponse {
        val wasFinished = synchronized(lock) { isFinished }
        if (!wasFinished) {
            finish(recoverUnclosedOnCompletion = !isStopped && sdkError == null)
        }
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
            thinkingMode = true
        )
    }
}

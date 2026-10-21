package com.shounak.localmeshai.ui.models

import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shounak.localmeshai.utils.ModelAnswerFormatter
import com.shounak.localmeshai.utils.ModelAnswerSegment
import com.shounak.localmeshai.utils.ParsedThinkingContent
import com.shounak.localmeshai.utils.ThinkingTextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val STREAMING_PLACEHOLDERS = setOf(
    "Generating…", "Generating...", "…", "Reading input…", "Reading input...",
    "Loading model…", "Loading model...", "Preparing input…", "Preparing input...",
    "Validating backend…", "Validating backend...", "Processing prompt…", "Processing prompt..."
)

private fun isStreamingPlaceholder(text: String): Boolean = text.trim() in STREAMING_PLACEHOLDERS

@Immutable
data class MessagePresentation(
    val messageId: String = "",
    val requestId: String = "",
    val attemptId: Long = 0L,
    val revision: Int = 0,
    val parsedThinking: ParsedThinkingContent,
    val displayText: String,
    val parsedSegments: List<ModelAnswerSegment>,
    val hasRichBlock: Boolean,
    val showStreamingDots: Boolean = false,
    val answerText: String = displayText,
    val reasoningText: String? = parsedThinking.thinkingText
) {
    val parsedContent: ParsedThinkingContent get() = parsedThinking
    val segments: List<ModelAnswerSegment> get() = parsedSegments
    val showThinking: Boolean get() = !reasoningText.isNullOrBlank() || parsedThinking.isThinkingActive
}

@Immutable
data class MessagePresentationKey(
    val messageId: String = "",
    val requestId: String = "",
    val attemptId: Long = 0L,
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean,
    val revision: Int = 0,
    val reasoningText: String? = null,
    val isThinkingActive: Boolean = false,
    val responseFormatVersion: Int = 0
)

object MessagePresentationCache {
    private val completedCache = LruCache<MessagePresentationKey, MessagePresentation>(256)

    fun clear() {
        completedCache.evictAll()
    }

    fun getIfCached(key: MessagePresentationKey): MessagePresentation? = completedCache.get(key)

    /**
     * Fast initial synchronous presentation: NO regex parsing, NO text normalisation, NO markdown parsing.
     * Pure O(1) allocation so Compose main thread is never blocked.
     */
    fun getFastInitial(
        messageId: String = "",
        requestId: String = "",
        attemptId: Long = 0L,
        revision: Int = 0,
        text: String,
        isUser: Boolean,
        isStreaming: Boolean,
        reasoningText: String? = null,
        isThinkingActive: Boolean = false,
        responseFormatVersion: Int = 0
    ): MessagePresentation {
        val showDots = isStreaming && isStreamingPlaceholder(text)
        val cleanReasoning = reasoningText?.trim()?.takeIf { it.isNotBlank() }
        val thinkingContent = ParsedThinkingContent(
            thinkingText = cleanReasoning,
            finalResponseText = text,
            isThinkingActive = isStreaming && isThinkingActive
        )
        return MessagePresentation(
            messageId = messageId,
            requestId = requestId,
            attemptId = attemptId,
            revision = revision,
            parsedThinking = thinkingContent,
            displayText = text,
            parsedSegments = if (text.isBlank()) emptyList() else listOf(ModelAnswerSegment.Text(text)),
            hasRichBlock = false,
            showStreamingDots = showDots,
            answerText = text,
            reasoningText = cleanReasoning
        )
    }

    fun getFastInitial(
        messageId: String = "",
        revision: Int = 0,
        text: String,
        isUser: Boolean,
        isStreaming: Boolean,
        reasoningText: String? = null,
        isThinkingActive: Boolean = false
    ): MessagePresentation = getFastInitial(
        messageId = messageId,
        requestId = "",
        attemptId = 0L,
        revision = revision,
        text = text,
        isUser = isUser,
        isStreaming = isStreaming,
        reasoningText = reasoningText,
        isThinkingActive = isThinkingActive,
        responseFormatVersion = 0
    )

    fun getOrCompute(
        text: String,
        isUser: Boolean,
        isStreaming: Boolean
    ): MessagePresentation = getOrCompute(
        messageId = "",
        requestId = "",
        attemptId = 0L,
        text = text,
        isUser = isUser,
        isStreaming = isStreaming,
        revision = 0,
        reasoningText = null,
        isThinkingActive = false,
        responseFormatVersion = 0
    )

    fun getOrCompute(
        messageId: String,
        text: String,
        isUser: Boolean,
        isStreaming: Boolean,
        revision: Int = 0,
        reasoningText: String? = null,
        isThinkingActive: Boolean = false
    ): MessagePresentation = getOrCompute(
        messageId = messageId,
        requestId = "",
        attemptId = 0L,
        text = text,
        isUser = isUser,
        isStreaming = isStreaming,
        revision = revision,
        reasoningText = reasoningText,
        isThinkingActive = isThinkingActive,
        responseFormatVersion = 0
    )

    fun getOrCompute(
        messageId: String,
        requestId: String,
        attemptId: Long,
        text: String,
        isUser: Boolean,
        isStreaming: Boolean,
        revision: Int = 0,
        reasoningText: String? = null,
        isThinkingActive: Boolean = false,
        responseFormatVersion: Int = 0
    ): MessagePresentation {
        val cacheKey = MessagePresentationKey(
            messageId = messageId,
            requestId = requestId,
            attemptId = attemptId,
            text = text,
            isUser = isUser,
            isStreaming = isStreaming,
            revision = revision,
            reasoningText = reasoningText,
            isThinkingActive = isThinkingActive,
            responseFormatVersion = responseFormatVersion
        )
        if (!isStreaming) {
            completedCache.get(cacheKey)?.let { return it }
        }

        // During streaming, show plain answer and separate plain reasoning section.
        // Do not repeatedly parse Markdown, code blocks, or mathematics.
        if (isStreaming) {
            return getFastInitial(messageId, requestId, attemptId, revision, text, isUser, isStreaming, reasoningText, isThinkingActive, responseFormatVersion)
        }

        val cleanReasoning = reasoningText?.trim()?.takeIf { it.isNotBlank() }
        val effectiveThinking = if (responseFormatVersion >= 1) {
            val sanitizedText = if (!isUser && !cleanReasoning.isNullOrBlank()) {
                ThinkingTextUtils.stripDuplicateLeadingThinkingEnvelope(text, cleanReasoning)
            } else {
                text
            }
            ParsedThinkingContent(
                thinkingText = cleanReasoning,
                finalResponseText = sanitizedText,
                isThinkingActive = false
            )
        } else {
            val parsedThinking = ThinkingTextUtils.parse(text, allowActiveThinking = false)
            val parsedReasoning = parsedThinking.thinkingText?.trim()?.takeIf { it.isNotBlank() }
            if (parsedReasoning.isNullOrBlank() && !cleanReasoning.isNullOrBlank()) {
                parsedThinking.copy(thinkingText = cleanReasoning)
            } else {
                parsedThinking.copy(thinkingText = parsedReasoning)
            }
        }
        val rawDisplay = if (isUser) {
            effectiveThinking.finalResponseText
        } else {
            ModelAnswerFormatter.normalizeEscapedModelText(effectiveThinking.finalResponseText)
        }
        val segments = if (rawDisplay.isBlank()) {
            emptyList()
        } else {
            ModelAnswerFormatter.parseSafely(rawDisplay)
        }
        val hasRich = segments.any { it !is ModelAnswerSegment.Text }
        val presentation = MessagePresentation(
            messageId = messageId,
            requestId = requestId,
            attemptId = attemptId,
            revision = revision,
            parsedThinking = effectiveThinking,
            displayText = rawDisplay,
            parsedSegments = segments,
            hasRichBlock = hasRich,
            showStreamingDots = false,
            answerText = rawDisplay,
            reasoningText = effectiveThinking.thinkingText
        )
        completedCache.put(cacheKey, presentation)
        return presentation
    }
}

/**
 * Composable helper that resolves message presentation off the main Compose thread.
 * Streaming messages immediately return a lightweight plain presentation.
 * Terminal rich presentations are computed once on Dispatchers.Default and cached.
 */
@Composable
fun rememberMessagePresentation(
    text: String,
    isUser: Boolean,
    isStreaming: Boolean,
    messageId: String = "",
    requestId: String = "",
    attemptId: Long = 0L,
    revision: Int = 0,
    reasoningText: String? = null,
    isThinkingActive: Boolean = false,
    responseFormatVersion: Int = 0
): MessagePresentation {
    if (isStreaming) {
        return MessagePresentationCache.getFastInitial(
            messageId = messageId,
            requestId = requestId,
            attemptId = attemptId,
            revision = revision,
            text = text,
            isUser = isUser,
            isStreaming = true,
            reasoningText = reasoningText,
            isThinkingActive = isThinkingActive,
            responseFormatVersion = responseFormatVersion
        )
    }

    val cacheKey = MessagePresentationKey(
        messageId = messageId,
        requestId = requestId,
        attemptId = attemptId,
        text = text,
        isUser = isUser,
        isStreaming = false,
        revision = revision,
        reasoningText = reasoningText,
        isThinkingActive = false,
        responseFormatVersion = responseFormatVersion
    )
    MessagePresentationCache.getIfCached(cacheKey)?.let { return it }

    var lastValidPresentation by remember(messageId, attemptId) {
        mutableStateOf<MessagePresentation?>(null)
    }

    val initial = if (lastValidPresentation?.messageId == messageId && lastValidPresentation?.attemptId == attemptId) {
        lastValidPresentation!!
    } else {
        MessagePresentationCache.getFastInitial(
            messageId = messageId,
            requestId = requestId,
            attemptId = attemptId,
            revision = revision,
            text = text,
            isUser = isUser,
            isStreaming = false,
            reasoningText = reasoningText,
            responseFormatVersion = responseFormatVersion
        )
    }

    return produceState(
        initialValue = initial,
        messageId,
        requestId,
        attemptId,
        revision,
        text,
        reasoningText,
        responseFormatVersion
    ) {
        val presentation = withContext(Dispatchers.Default) {
            MessagePresentationCache.getOrCompute(
                messageId = messageId,
                requestId = requestId,
                attemptId = attemptId,
                text = text,
                isUser = isUser,
                isStreaming = false,
                revision = revision,
                reasoningText = reasoningText,
                responseFormatVersion = responseFormatVersion
            )
        }
        if (presentation.messageId == messageId &&
            presentation.requestId == requestId &&
            presentation.attemptId == attemptId &&
            presentation.revision == revision
        ) {
            value = presentation
            lastValidPresentation = presentation
        }
    }.value
}

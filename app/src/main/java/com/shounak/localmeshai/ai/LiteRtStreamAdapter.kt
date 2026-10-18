package com.shounak.localmeshai.ai

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.shounak.localmeshai.utils.ThinkingTextUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapter bridging LiteRT-LM's native [MessageCallback] API to structured response accumulation.
 *
 * Guarantees:
 * 1. Appends each [Content.Text] to the primary answer accumulator in order.
 * 2. Accumulates named channels (e.g. thought/reasoning) separately from the primary answer.
 * 3. Never treats an empty message or channel-only message as a reset.
 * 4. Discards late events arriving after completion, cancellation, or attempt superseding.
 * 5. Yields structured snapshots on each new text chunk.
 */
class LiteRtStreamAdapter(
    val requestId: String,
    val attemptId: Int = 1,
    private val thinkingMode: Boolean = false,
    private val onFirstToken: (() -> Unit)? = null,
    private val onDelta: ((String) -> Unit)? = null,
    private val onSnapshot: ((StructuredResponseSnapshot) -> Unit)? = null
) : MessageCallback {

    companion object {
        private const val TAG = "LiteRtStreamAdapter"
        private val REASONING_CHANNEL_KEYS = setOf("thought", "reasoning", "thinking")
    }

    private val lock = Any()
    private val answerBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val namedChannels = mutableMapOf<String, StringBuilder>()

    private val revisionCounter = AtomicInteger(0)
    private val callbackCounter = AtomicInteger(0)
    val callbackCount: Int get() = callbackCounter.get()
    val tokenCount: Int get() = callbackCounter.get()
    private val isCompleted = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val completionDeferred = CompletableDeferred<StructuredResponseSnapshot>()
    private val firstTokenReported = AtomicBoolean(false)

    @Volatile private var isThinkingOpen = false
    @Volatile private var hasEmittedThinkingStart = false
    @Volatile private var hasEmittedThinkingEnd = false

    var terminalError: Throwable? = null
        private set

    override fun onMessage(message: Message) {
        if (isCompleted.get() || isCancelled.get()) {
            return
        }

        var newAnswerDelta = ""
        var newReasoningDelta = ""
        var snapshot: StructuredResponseSnapshot? = null

        synchronized(lock) {
            if (isCompleted.get() || isCancelled.get()) return

            // 1. Process regular contents
            val contentsList = message.contents.contents.orEmpty()
            for (content in contentsList) {
                if (content is Content.Text) {
                    val text = content.text
                    if (text.isNotEmpty()) {
                        answerBuffer.append(text)
                        newAnswerDelta += text
                    }
                }
            }

            // 2. Process named channels (e.g. thought/reasoning)
            val channelsMap = message.channels.orEmpty()
            for ((key, value) in channelsMap) {
                if (value.isNotEmpty()) {
                    val buf = namedChannels.getOrPut(key) { StringBuilder() }
                    buf.append(value)
                    if (REASONING_CHANNEL_KEYS.contains(key.lowercase())) {
                        reasoningBuffer.append(value)
                        newReasoningDelta += value
                    }
                }
            }

            // Update thinking phase state BEFORE constructing snapshot and message presentation
            if (thinkingMode) {
                if (newReasoningDelta.isNotEmpty()) {
                    isThinkingOpen = true
                }
                if (newAnswerDelta.isNotEmpty()) {
                    isThinkingOpen = false
                }
            }

            // If message was completely empty or channel-only without text,
            // that is a valid non-answer event — NEVER a reset.
            val currentRev = revisionCounter.incrementAndGet()
            if (onSnapshot != null) {
                val channelSnapshots = namedChannels.mapValues { it.value.toString() }
                snapshot = StructuredResponseSnapshot(
                    requestId = requestId,
                    attemptId = attemptId,
                    revision = currentRev,
                    answerText = answerBuffer.toString(),
                    reasoningText = reasoningBuffer.toString(),
                    isThinkingActive = isThinkingOpen && !isCompleted.get(),
                    channels = channelSnapshots
                )
            }
        }

        // Notify first token immediately when any token arrives
        if (newAnswerDelta.isNotEmpty() || newReasoningDelta.isNotEmpty()) {
            callbackCounter.incrementAndGet()
            if (firstTokenReported.compareAndSet(false, true)) {
                onFirstToken?.invoke()
            }
        }

        // Handle streaming deltas
        if (thinkingMode) {
            if (newReasoningDelta.isNotEmpty()) {
                if (!hasEmittedThinkingStart) {
                    hasEmittedThinkingStart = true
                    onDelta?.invoke("<think>\n")
                }
                onDelta?.invoke(newReasoningDelta)
            }
            if (newAnswerDelta.isNotEmpty()) {
                if (hasEmittedThinkingStart && !hasEmittedThinkingEnd) {
                    hasEmittedThinkingEnd = true
                    onDelta?.invoke("\n</think>\n\n")
                }
                onDelta?.invoke(newAnswerDelta)
            }
        } else {
            if (newAnswerDelta.isNotEmpty()) {
                onDelta?.invoke(newAnswerDelta)
            }
        }

        snapshot?.let { onSnapshot?.invoke(it) }
    }

    override fun onDone() {
        if (!isCompleted.compareAndSet(false, true)) {
            return
        }
        synchronized(lock) {
            isThinkingOpen = false
        }
        if (thinkingMode && hasEmittedThinkingStart && !hasEmittedThinkingEnd) {
            hasEmittedThinkingEnd = true
            onDelta?.invoke("\n</think>")
        }
        val finalSnapshot = currentSnapshot()
        completionDeferred.complete(finalSnapshot)
    }

    override fun onError(throwable: Throwable) {
        synchronized(lock) {
            terminalError = throwable
        }
        if (isCompleted.compareAndSet(false, true)) {
            val finalSnapshot = currentSnapshot()
            completionDeferred.completeExceptionally(throwable)
        }
    }

    fun cancel(conversation: Conversation? = null) {
        if (!isCancelled.compareAndSet(false, true)) {
            return
        }
        runCatching { conversation?.cancelProcess() }
        synchronized(lock) {
            isThinkingOpen = false
        }
        if (thinkingMode && hasEmittedThinkingStart && !hasEmittedThinkingEnd) {
            hasEmittedThinkingEnd = true
            onDelta?.invoke("\n</think>\n\n")
        }
        if (isCompleted.compareAndSet(false, true)) {
            completionDeferred.completeExceptionally(CancellationException("LiteRT inference cancelled"))
        }
    }

    fun currentSnapshot(): StructuredResponseSnapshot = synchronized(lock) {
        StructuredResponseSnapshot(
            requestId = requestId,
            attemptId = attemptId,
            revision = revisionCounter.get(),
            answerText = answerBuffer.toString(),
            reasoningText = reasoningBuffer.toString(),
            isThinkingActive = isThinkingOpen && !isCompleted.get(),
            channels = namedChannels.mapValues { it.value.toString() }
        )
    }

    suspend fun awaitDone(): StructuredResponseSnapshot {
        return completionDeferred.await()
    }
}

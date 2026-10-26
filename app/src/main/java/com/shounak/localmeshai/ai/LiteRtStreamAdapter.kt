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
    private val onSnapshot: ((StructuredResponseSnapshot) -> Unit)? = null,
    reasoningChannelKeys: Set<String> = emptySet(),
    private val startsInReasoning: Boolean = false
) : MessageCallback {

    companion object {
        private const val TAG = "LiteRtStreamAdapter"
        private val REASONING_CHANNEL_KEYS = setOf("thought", "reasoning", "thinking", "analysis")
        private val LEADING_REASONING_TAG = Regex("^\\s*(?:<\\s*(?:think|thought|reasoning)\\s*>|\\[\\s*(?:think|thought|reasoning)\\s*\\])", RegexOption.IGNORE_CASE)
    }

    private val reasoningKeys = REASONING_CHANNEL_KEYS + reasoningChannelKeys.map { it.lowercase() }
    private val lock = Any()
    private val answerBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val namedChannels = mutableMapOf<String, StringBuilder>()
    /** Channel keys seen this request, logged once each for on-device confirmation. */
    private val seenChannelKeys = linkedSetOf<String>()

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

    override fun onMessage(message: Message): Unit = synchronized(lock) {
        if (isCompleted.get() || isCancelled.get()) {
            return
        }

        var newAnswerDelta = ""
        var newReasoningDelta = ""
        var snapshot: StructuredResponseSnapshot? = null

        synchronized(lock) {
            if (isCompleted.get() || isCancelled.get()) return

            val previous = snapshotLocked()

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
                if (seenChannelKeys.add(key)) {
                    val isReasoning = reasoningKeys.contains(key.lowercase())
                    Log.d(TAG, "req=$requestId attempt=$attemptId channel key=\"$key\" reasoning=$isReasoning " +
                        "thinkingMode=$thinkingMode chars=${value.length}")
                    if (isReasoning && !thinkingMode) {
                        Log.w(TAG, "req=$requestId reasoning channel \"$key\" received while thinkingMode=false; it will not be shown")
                    }
                }
                if (value.isNotEmpty()) {
                    val buf = namedChannels.getOrPut(key) { StringBuilder() }
                    buf.append(value)
                    if (reasoningKeys.contains(key.lowercase())) {
                        reasoningBuffer.append(value)
                        newReasoningDelta += value
                    }
                }
            }

            val hasContent = newAnswerDelta.isNotEmpty() || newReasoningDelta.isNotEmpty()
            if (newReasoningDelta.isNotEmpty()) isThinkingOpen = true
            if (newAnswerDelta.isNotEmpty() && parseRawLocked().finalResponseText.isNotEmpty()) isThinkingOpen = false
            revisionCounter.incrementAndGet()
            val normalized = snapshotLocked()
            newAnswerDelta = if (normalized.answerText.startsWith(previous.answerText)) normalized.answerText.substring(previous.answerText.length) else ""
            newReasoningDelta = if (normalized.reasoningText.startsWith(previous.reasoningText)) normalized.reasoningText.substring(previous.reasoningText.length) else ""
            if (hasContent) {
                callbackCounter.incrementAndGet()
                if (firstTokenReported.compareAndSet(false, true)) onFirstToken?.invoke()
            }
            if (onSnapshot != null) snapshot = normalized

        }

        publishDeltas(newAnswerDelta, newReasoningDelta)
        snapshot?.let { onSnapshot?.invoke(it) }
    }

    private fun publishDeltas(newAnswerDelta: String, newReasoningDelta: String) {
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
    }

    override fun onDone() {
        terminate()
    }

    override fun onError(throwable: Throwable) {
        terminate(throwable)
    }

    fun cancel(conversation: Conversation? = null) {
        synchronized(lock) {
            if (isCompleted.get() || !isCancelled.compareAndSet(false, true)) return
        }
        // Native cancellation can synchronously call back. Reject those callbacks without
        // holding the lock, and cancel native work before waking the awaiting owner.
        runCatching { conversation?.cancelProcess() }
        terminate(CancellationException("LiteRT inference cancelled"), cancelled = true)
    }

    private fun terminate(error: Throwable? = null, cancelled: Boolean = false): Boolean = synchronized(lock) {
        if (isCompleted.get() || (isCancelled.get() && !cancelled)) return false
        val previous = snapshotLocked()
        isCancelled.set(cancelled)
        isCompleted.set(true)
        if (!cancelled) terminalError = error
        isThinkingOpen = false
        revisionCounter.incrementAndGet()
        val finalSnapshot = snapshotLocked()
        Log.d(TAG, "req=$requestId attempt=$attemptId end=${if (cancelled) "cancelled" else if (error != null) "error" else "done"} " +
            "callbacks=${callbackCounter.get()} channelKeys=$seenChannelKeys thinkingMode=$thinkingMode " +
            "answerChars=${finalSnapshot.answerText.length} reasoningChars=${finalSnapshot.reasoningText.length}")
        try {
            publishDeltas(
                if (finalSnapshot.answerText.startsWith(previous.answerText)) finalSnapshot.answerText.substring(previous.answerText.length) else "",
                if (finalSnapshot.reasoningText.startsWith(previous.reasoningText)) finalSnapshot.reasoningText.substring(previous.reasoningText.length) else ""
            )
            if (thinkingMode && hasEmittedThinkingStart && !hasEmittedThinkingEnd) {
                hasEmittedThinkingEnd = true
                onDelta?.invoke("\n</think>")
            }
            onSnapshot?.invoke(finalSnapshot)
        } finally {
            if (error == null) completionDeferred.complete(finalSnapshot)
            else completionDeferred.completeExceptionally(error)
        }
        true
    }

    private fun parseRawLocked(): com.shounak.localmeshai.utils.ParsedThinkingContent {
        val raw = answerBuffer.toString()
        val text = if (isCompleted.get()) raw else ThinkingTextUtils.holdIncompleteTag(raw)
        // A legacy template may already supply the opening tag. Native channels take
        // precedence, and an explicitly generated opening tag must not add a second depth.
        val prefill = startsInReasoning && reasoningBuffer.isEmpty() && !LEADING_REASONING_TAG.containsMatchIn(text)
        return ThinkingTextUtils.parse(if (prefill) "<think>$text" else text, allowActiveThinking = true)
    }

    private fun snapshotLocked(): StructuredResponseSnapshot {
        val parsed = parseRawLocked()
        // Native reasoning channels take precedence over a duplicated tagged envelope.
        val reasoning = reasoningBuffer.toString().ifEmpty { parsed.thinkingText.orEmpty() }
        return StructuredResponseSnapshot(
            requestId = requestId,
            attemptId = attemptId,
            revision = revisionCounter.get(),
            answerText = parsed.finalResponseText,
            reasoningText = reasoning,
            isThinkingActive = thinkingMode && (parsed.isThinkingActive || isThinkingOpen) && !isCompleted.get() && !isCancelled.get(),
            channels = namedChannels.mapValues { it.value.toString() }
        )
    }

    fun currentSnapshot(): StructuredResponseSnapshot = synchronized(lock) { snapshotLocked() }

    suspend fun awaitDone(): StructuredResponseSnapshot {
        return completionDeferred.await()
    }
}

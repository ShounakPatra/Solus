package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ai.StructuredResponseSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SDK callbacks append deltas; only the background publisher copies and formats snapshots. */
internal class StreamingResponsePublisher(
    scope: CoroutineScope,
    private val transform: (String) -> String,
    private val publish: (String) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val intervalNanos: Long = 50_000_000L,
    private val finalTransform: (String) -> String = transform,
    private val snapshotProvider: (() -> StructuredResponseSnapshot)? = null,
    private val publishSnapshot: ((StructuredResponseSnapshot) -> Unit)? = null
) {
    private val lock = Any()
    private val buffer = StringBuilder()
    private val notifications = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastPublished: String? = null
    @Volatile private var lastPublishedKey: String? = null
    private var version = 0
    private var finished = false
    private var lastAt: Long? = null
    private var currentAttemptId: Int = 1

    val currentAttempt: Int
        get() = synchronized(lock) { currentAttemptId }

    private val job = scope.launch(Dispatchers.Default) {
        for (ignored in notifications) {
            val remaining = synchronized(lock) {
                if (lastPublished.isNullOrBlank()) {
                    0L
                } else {
                    lastAt?.let { intervalNanos - (clock() - it) } ?: 0L
                }
            }
            if (remaining > 0L) delay((remaining + 999_999L) / 1_000_000L)

            val (snapVersion, snapAttempt, snapText) = synchronized(lock) {
                Triple(version, currentAttemptId, buffer.toString())
            }
            val formatted = transform(snapText)
            val currentSnapshot = snapshotProvider?.invoke()
            val hasContent = formatted.isNotBlank() || (currentSnapshot != null && (currentSnapshot.answerText.isNotBlank() || currentSnapshot.reasoningText.isNotBlank() || currentSnapshot.isThinkingActive))
            var shouldPublish = false
            var isFirst = false
            synchronized(lock) {
                if (snapVersion == version && snapAttempt == currentAttemptId && !finished) {
                    val key = if (currentSnapshot != null) {
                        "${currentSnapshot.answerText}|${currentSnapshot.reasoningText}|${currentSnapshot.isThinkingActive}|$formatted"
                    } else {
                        formatted
                    }
                    if (hasContent && key != lastPublishedKey) {
                        isFirst = lastPublished.isNullOrBlank()
                        lastPublishedKey = key
                        lastPublished = formatted
                        lastAt = clock()
                        shouldPublish = true
                    }
                }
            }
            if (shouldPublish) {
                if (isFirst) {
                    AppTrace.beginSection("first_visible_publication")
                }
                try {
                    if (formatted.isNotBlank()) publish(formatted)
                    if (publishSnapshot != null && currentSnapshot != null) {
                        publishSnapshot(currentSnapshot)
                    }
                } finally {
                    if (isFirst) AppTrace.endSection()
                }
            }
        }
    }

    /**
     * Appends a non-empty chunk to the in-flight accumulator.
     * Empty chunks are strictly ignored and never reset the accumulator.
     * Repeated identical chunks (e.g. "a", "a", "abc") are preserved exactly ("aaabc").
     */
    fun appendDelta(delta: String, reqId: String? = null, attemptId: Int? = null) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            if (finished) return
            if (attemptId != null && attemptId != currentAttemptId) return
            buffer.append(delta)
        }
        notifications.trySend(Unit)
    }

    /**
     * Explicit operation to start a new attempt (e.g. GGUF CPU recovery fallback).
     * Clears the accumulator only for a new higher attempt ID.
     */
    fun beginAttempt(newAttemptId: Int) {
        var shouldPublishReset = false
        var resetSnapshot: StructuredResponseSnapshot? = null
        synchronized(lock) {
            if (finished) return
            if (newAttemptId <= currentAttemptId && buffer.isNotEmpty()) return
            currentAttemptId = newAttemptId
            buffer.clear()
            version++
            lastAt = null
            lastPublished = ""
            lastPublishedKey = ""
            shouldPublishReset = true
            resetSnapshot = snapshotProvider?.invoke() ?: StructuredResponseSnapshot(
                requestId = "",
                attemptId = newAttemptId,
                revision = version,
                answerText = "",
                reasoningText = "",
                isThinkingActive = false
            )
        }
        if (shouldPublishReset) {
            publish("")
            resetSnapshot?.let { publishSnapshot?.invoke(it) }
        }
        notifications.trySend(Unit)
    }

    /** Legacy reset, advances attempt counter. */
    fun reset() {
        beginAttempt(currentAttemptId + 1)
    }

    fun rawText(): String = synchronized(lock) { buffer.toString() }

    fun currentSnapshot(reasoning: String = ""): StreamingSnapshot = synchronized(lock) {
        StreamingSnapshot(
            answer = lastPublished.orEmpty(),
            reasoning = reasoning,
            attempt = currentAttemptId,
            revision = version
        )
    }

data class StreamingSnapshot(
    val answer: String,
    val reasoning: String = "",
    val attempt: Int,
    val revision: Int
)

    /**
     * Finalizes generation once and returns the formatted response.
     * Guarantees that a blank terminal event cannot overwrite previously accepted text from this attempt.
     */
    suspend fun finish(finalText: String? = null): String = withContext(NonCancellable + Dispatchers.Default) {
        val alreadyFinished: Boolean
        val currentRaw: String
        val lastPub: String
        synchronized(lock) {
            alreadyFinished = finished
            finished = true
            currentRaw = buffer.toString()
            lastPub = lastPublished.orEmpty()
        }
        if (alreadyFinished) {
            return@withContext lastPub
        }
        notifications.close()
        job.cancel()
        job.join()

        val textToFormat = when {
            !finalText.isNullOrBlank() -> finalText
            currentRaw.isNotBlank() -> currentRaw
            else -> lastPub
        }
        val formatted = finalTransform(textToFormat)
        var shouldPublish = false
        synchronized(lock) {
            if (formatted != lastPublished) {
                lastPublished = formatted
                shouldPublish = true
            }
        }
        if (shouldPublish) {
            publish(formatted)
        }
        snapshotProvider?.invoke()?.let { publishSnapshot?.invoke(it) }
        formatted
    }

    /**
     * Finalizes generation and explicitly publishes the authoritative formatted outcome.
     * Guarantees final publication uses the finalized answer/reasoning explicitly.
     */
    suspend fun finishExplicit(finalFormattedText: String): String = withContext(NonCancellable + Dispatchers.Default) {
        val alreadyFinished: Boolean
        val lastPub: String
        synchronized(lock) {
            alreadyFinished = finished
            finished = true
            lastPub = lastPublished.orEmpty()
        }
        if (alreadyFinished) {
            return@withContext lastPub
        }
        notifications.close()
        job.cancel()
        job.join()

        var shouldPublish = false
        synchronized(lock) {
            if (finalFormattedText != lastPublished) {
                lastPublished = finalFormattedText
                shouldPublish = true
            }
        }
        if (shouldPublish) {
            publish(finalFormattedText)
        }
        snapshotProvider?.invoke()?.let { publishSnapshot?.invoke(it) }
        finalFormattedText
    }

    fun notifyStructuredUpdate() {
        synchronized(lock) {
            if (finished) return
            version++
        }
        notifications.trySend(Unit)
    }
}

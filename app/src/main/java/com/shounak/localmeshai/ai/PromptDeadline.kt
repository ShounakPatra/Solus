package com.shounak.localmeshai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal class PromptTimeoutException : IllegalStateException(
    "The model stopped making progress while reading the input. " +
        "Retry with a shorter message or a new chat. If it keeps happening, reload the model."
)

/** Stops stalled prefill without releasing a runtime that may still be executing native code. */
internal class PromptDeadline(private val timeoutMillis: Long = 120_000L) {
    // 0: prompt/tool work, 1: visible generation, 2: timed out, 3: completed.
    private val state = AtomicInteger(0)
    private val progressLock = Any()
    private val lastProgressNanos = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

    fun onProgress(progress: RequestProgress) = synchronized(progressLock) {
        if (progress == RequestProgress.Generating) state.compareAndSet(0, 1)
        else if (state.get() < 2) {
            lastProgressNanos.set(System.nanoTime())
            state.compareAndSet(1, 0)
        }
    }

    suspend fun <T> run(cancelNative: () -> Unit, onTimeout: () -> Unit = {}, block: suspend () -> T): T = coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            while (state.get() < 2) {
                // Visible streaming has no prefill deadline, but a later tool round can re-arm it.
                if (state.get() == 1) {
                    delay(timeoutMillis.coerceAtMost(1_000L).coerceAtLeast(1L))
                    continue
                }
                val remaining = timeoutMillis - (System.nanoTime() - lastProgressNanos.get()) / 1_000_000L
                if (remaining > 0) delay(remaining)
                else if (synchronized(progressLock) {
                    (System.nanoTime() - lastProgressNanos.get()) / 1_000_000L >= timeoutMillis && state.compareAndSet(0, 2)
                }) {
                    onTimeout()
                    cancelNative()
                }
            }
        }
        try {
            val result = block()
            synchronized(progressLock) { if (state.get() != 2) state.set(3) }
            if (state.get() == 2) throw PromptTimeoutException()
            result
        } catch (error: Exception) {
            if (state.get() == 2) throw PromptTimeoutException().also { it.initCause(error) }
            throw error
        } finally {
            synchronized(progressLock) { if (state.get() != 2) state.set(3) }
            watchdog.cancel()
        }
    }

    companion object {
        /** CPU prefill cost grows with input; keep short stalled turns bounded too. */
        fun forPrompt(promptChars: Int) = PromptDeadline(budgetMillis(promptChars))
        internal fun budgetMillis(promptChars: Int): Long =
            (120_000L + promptChars.coerceAtLeast(0).toLong() * 65L).coerceAtMost(1_800_000L)
    }
}

package com.shounak.localmeshai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal class PromptTimeoutException : IllegalStateException(
    "The model took too long to read the input (2 minutes without a token). " +
        "Retry with a shorter message or a new chat. If it keeps happening, reload the model."
)

/** Stops stalled prefill without releasing a runtime that may still be executing native code. */
internal class PromptDeadline(private val timeoutMillis: Long = 120_000L) {
    // Exactly one of first token, completion, or timeout wins.
    private val state = AtomicInteger(0)

    fun onProgress(progress: RequestProgress) {
        if (progress == RequestProgress.Generating) state.compareAndSet(0, 1)
    }

    suspend fun <T> run(cancelNative: () -> Unit, onTimeout: () -> Unit = {}, block: suspend () -> T): T = coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            delay(timeoutMillis)
            if (state.compareAndSet(0, 2)) {
                onTimeout()
                cancelNative()
            }
        }
        try {
            val result = block()
            state.compareAndSet(0, 1)
            if (state.get() == 2) throw PromptTimeoutException()
            result
        } catch (error: Exception) {
            if (state.get() == 2) throw PromptTimeoutException().also { it.initCause(error) }
            throw error
        } finally {
            state.compareAndSet(0, 1)
            watchdog.cancel()
        }
    }
}

package com.shounak.localmeshai.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** One worker, with duplicate requests sharing a result and user selections moving to the front. */
class ModelValidationQueue<K, V>(scope: CoroutineScope) {
    private class Request<V>(val result: CompletableDeferred<V>, val block: suspend () -> V)
    private val lock = Any()
    private val pending = LinkedHashMap<K, Request<V>>()
    private val active = mutableMapOf<K, Request<V>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            try {
                for (signal in wake) {
                    while (true) {
                        val request = synchronized(lock) {
                            pending.entries.firstOrNull()?.let { (key, value) ->
                                pending.remove(key)
                                key to value
                            }
                        } ?: break
                        try {
                            request.second.result.complete(request.second.block())
                        } catch (cancelled: CancellationException) {
                            request.second.result.cancel(cancelled)
                            throw cancelled
                        } catch (error: Throwable) {
                            request.second.result.completeExceptionally(error)
                        } finally {
                            synchronized(lock) { active.remove(request.first) }
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    active.values.forEach { it.result.cancel() }
                    active.clear()
                    pending.clear()
                    wake.close()
                }
            }
        }
    }

    fun submit(key: K, prioritize: Boolean = false, block: suspend () -> V): Deferred<V> = synchronized(lock) {
        val request = active.getOrPut(key) { Request(CompletableDeferred(), block).also { pending[key] = it } }
        if (prioritize && pending.containsKey(key)) {
            pending.remove(key)
            val remaining = pending.toMap()
            pending.clear()
            pending[key] = request
            pending.putAll(remaining)
        }
        wake.trySend(Unit)
        request.result
    }
}

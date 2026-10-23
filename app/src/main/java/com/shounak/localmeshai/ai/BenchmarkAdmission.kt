package com.shounak.localmeshai.ai

import com.shounak.localmeshai.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal object BenchmarkAdmission {
    private class Admitted : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Admitted> }
    private val gate = Mutex()
    suspend fun <T> run(modelId: String, block: suspend () -> T): T {
        if (currentCoroutineContext()[Admitted] != null) return block()
        return gate.withLock {
            val job = currentCoroutineContext()[Job]
            val token = ModelRuntimeCoordinator.register(ModelRuntimeOwner.Benchmark,
                stableModelIdProvider = { modelId }, modelPathProvider = { modelId }, requestStop = { job?.cancel() }, release = { ShutdownResult.Success })
            try {
                ModelRuntimeCoordinator.requestActiveStop()
                ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Benchmark) {
                    check(!ModelRemovalCoordinator.isReserved(modelId)) { "Model is being removed" }
                    withContext(Admitted()) { block() }
                }
            } finally { ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Benchmark, token) }
        }
    }
}

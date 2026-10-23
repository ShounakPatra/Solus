package com.shounak.localmeshai.ai

import com.shounak.localmeshai.utils.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkAdmissionTest {
    @Test fun allNestedRunnersShareSingleAdmissionAndReleaseChat() = runBlocking {
        var released=0
        ModelRuntimeCoordinator.register(ModelRuntimeOwner.Chat, requestStop={}, release={ released++; ShutdownResult.Success })
        ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Chat)
        try {
            BenchmarkAdmission.run("model") {
                assertEquals(1,released)
                assertTrue(ModelRuntimeCoordinator.isRuntimeBusy.value)
                BenchmarkAdmission.run("model") { assertEquals("model",ModelRuntimeCoordinator.getActiveStableModelId(ModelRuntimeOwner.Benchmark)) }
            }
            assertFalse(ModelRuntimeCoordinator.isRuntimeBusy.value)
            assertNull(ModelRuntimeCoordinator.getActiveStableModelId(ModelRuntimeOwner.Benchmark))
        } finally { ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Chat); ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Benchmark) }
    }
    @Test fun cancellationReleasesGate() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val task=launch { BenchmarkAdmission.run("first") { entered.complete(Unit); awaitCancellation() } }
        entered.await(); task.cancelAndJoin()
        BenchmarkAdmission.run("second") { assertEquals("second",ModelRuntimeCoordinator.getActiveStableModelId(ModelRuntimeOwner.Benchmark)) }
        assertFalse(ModelRuntimeCoordinator.isRuntimeBusy.value)
    }
}

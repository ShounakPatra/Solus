package com.shounak.localmeshai.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModelValidationQueueTest {
    @Test fun slowValidationSharesResultsAndPrioritizesAnExplicitSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = ModelValidationQueue<String, String>(scope)
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val first = queue.submit("first") { order += "first"; release.await(); "first" }
            val second = queue.submit("second") { order += "second"; "second" }
            val selected = queue.submit("selected") { order += "selected"; "selected" }
            val duplicate = queue.submit("selected", prioritize = true) { error("Must share the pending inspection") }
            assertSame(selected, duplicate)
            assertFalse(second.isCompleted)
            assertFalse(selected.isCompleted)
            release.complete(Unit)
            withTimeout(2000) { first.await(); selected.await(); second.await() }
            assertEquals(listOf("first", "selected", "second"), order)
        } finally { scope.cancel() }
    }

    @Test fun failureDoesNotPreventOtherModelsBeingChecked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = ModelValidationQueue<String, String>(scope)
            val failed = queue.submit("broken") { error("Missing projector") }
            val valid = queue.submit("valid") { "ready" }
            assertTrue(runCatching { failed.await() }.isFailure)
            assertEquals("ready", withTimeout(2000) { valid.await() })
        } finally { scope.cancel() }
    }
}

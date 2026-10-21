package com.shounak.localmeshai.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ModelLoadControllerTest {

    @Test fun elapsedTimeAdvancesWhileWaitingWithoutProgressAndStopsAtReady() = runBlocking {
        val time = java.util.concurrent.atomic.AtomicLong(100L)
        val controller = ModelLoadController(clock = { time.get() })
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val loading = async(Dispatchers.IO) {
            controller.loadOrAttach(ModelLoadIdentity("waiting"), this) { _, _ ->
                entered.complete(Unit)
                finish.await()
                ModelActualConfiguration(runtimeKind = "LiteRT", backend = "CPU", threads = 1)
            }
        }
        try {
            entered.await()
            time.set(2_600L)
            val state = kotlinx.coroutines.withTimeout(3_000L) {
                controller.loadState.first { it is ModelLoadState.Loading && it.elapsedMs >= 2_500L }
            } as ModelLoadState.Loading
            assertEquals(ModelLoadStage.WaitingForRuntime, state.stage)
            assertFalse(state.isStalledWarning)
            finish.complete(Unit)
            loading.await()
            time.set(60_000L)
            delay(600L)
            assertTrue(controller.loadState.value is ModelLoadState.Ready)
        } finally { finish.complete(Unit); loading.cancel() }
    }

    @Test fun elapsedTimeAloneDoesNotMeanProgressHasStalled() {
        assertFalse(ModelLoadState.Loading("load", ModelLoadStage.CpuLoading, elapsedMs = 60_000L).isStalledWarning)
        assertTrue(ModelLoadState.Loading("load", ModelLoadStage.WaitingForRuntime,
            elapsedMs = 60_000L, idleMs = 30_000L).isStalledWarning)
    }

    @Test
    fun testInitialStateIsUnloaded() {
        val controller = ModelLoadController()
        assertEquals(ModelLoadState.Unloaded, controller.loadState.value)
        assertFalse(controller.isReady)
        assertFalse(controller.isInitializing)
    }

    @Test
    fun testSuccessfulLoadTransitionsToReady() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/test-model.gguf",
            runtimeKind = "Gguf",
            backendPreference = "gpu"
        )

        val config = ModelActualConfiguration(
            runtimeKind = "Gguf",
            backend = "Vulkan",
            threads = 4,
            contextWindow = 2048,
            batchSize = 256,
            ubatchSize = 128,
            gpuLayers = 99
        )

        val result = controller.loadOrAttach(
            identity = identity,
            scope = this
        ) { _, updateProgress ->
            updateProgress(ModelLoadStage.CpuReference(), 10, 100)
            updateProgress(ModelLoadStage.ContextPreparation(), 100, 100)
            config
        }

        assertEquals(config, result)
        assertTrue(controller.isReady)
        assertFalse(controller.isInitializing)
        val state = controller.loadState.value
        assertTrue(state is ModelLoadState.Ready)
        assertEquals(config, (state as ModelLoadState.Ready).actualConfiguration)
    }

    @Test
    fun testIdenticalLoadReusesInFlightOperation() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/shared-model.gguf",
            runtimeKind = "Gguf",
            backendPreference = "gpu"
        )

        val config = ModelActualConfiguration(
            runtimeKind = "Gguf",
            backend = "Vulkan",
            threads = 4,
            contextWindow = 2048,
            batchSize = 256,
            ubatchSize = 128,
            gpuLayers = 99
        )

        val loadInvocationCount = AtomicInteger(0)
        val loaderHold = CompletableDeferred<Unit>()

        val firstDeferred = async {
            controller.loadOrAttach(identity, this) { _, _ ->
                loadInvocationCount.incrementAndGet()
                loaderHold.await()
                config
            }
        }

        // Wait until controller is loading
        while (!controller.isInitializing) {
            delay(10)
        }

        // Attach second request with identical identity
        val secondDeferred = async {
            controller.loadOrAttach(identity, this) { _, _ ->
                loadInvocationCount.incrementAndGet()
                config
            }
        }

        loaderHold.complete(Unit)

        val firstResult = firstDeferred.await()
        val secondResult = secondDeferred.await()

        assertEquals(1, loadInvocationCount.get())
        assertEquals(config, firstResult)
        assertEquals(config, secondResult)
    }

    @Test
    fun testCancelActiveOperationTransitionsToUnloaded() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/cancellable-model.gguf",
            runtimeKind = "Gguf"
        )

        val cancelActionInvoked = AtomicBoolean(false)
        val loadStarted = CompletableDeferred<Unit>()

        val loadJob = launch {
            try {
                controller.loadOrAttach(
                    identity = identity,
                    scope = this,
                    cancellationAction = { cancelActionInvoked.set(true) }
                ) { _, _ ->
                    loadStarted.complete(Unit)
                    delay(5000)
                    ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
                }
            } catch (_: Exception) {
            }
        }

        loadStarted.await()
        val opId = controller.activeOperationId
        controller.cancelActiveOperation(targetOperationId = opId, reason = "User cancelled")
        loadJob.join()

        assertTrue(cancelActionInvoked.get())
        assertEquals(ModelLoadState.Unloaded, controller.loadState.value)
        assertFalse(controller.isReady)
    }

    @Test
    fun testResetToUnloadedClearsState() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/test-model.gguf",
            runtimeKind = "Gguf",
            backendPreference = "cpu"
        )
        controller.loadOrAttach(identity, this) { _, _ ->
            ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
        }
        assertTrue(controller.isReady)

        controller.resetToUnloaded()
        assertEquals(ModelLoadState.Unloaded, controller.loadState.value)
        assertFalse(controller.isReady)
    }

    @Test
    fun testClearReadinessSetsFailedOnError() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/test-model.gguf",
            runtimeKind = "Gguf",
            backendPreference = "cpu"
        )
        controller.loadOrAttach(identity, this) { _, _ ->
            ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
        }
        assertTrue(controller.isReady)

        controller.clearReadiness("CPU recovery failed")
        val state = controller.loadState.value
        assertTrue(state is ModelLoadState.Failed)
        assertEquals("CPU recovery failed", (state as ModelLoadState.Failed).typedError)
        assertFalse(controller.isReady)
    }

    @Test
    fun testSupersededLoadCancelsPreviousAndDoesNotOverwriteState() = runBlocking {
        val controller = ModelLoadController()
        val identity1 = ModelLoadIdentity(
            modelPath = "/path/to/model-1.gguf",
            runtimeKind = "Gguf",
            backendPreference = "cpu"
        )
        val identity2 = ModelLoadIdentity(
            modelPath = "/path/to/model-2.gguf",
            runtimeKind = "Gguf",
            backendPreference = "gpu"
        )
        val config2 = ModelActualConfiguration(
            runtimeKind = "Gguf",
            backend = "Vulkan",
            threads = 4,
            contextWindow = 2048,
            batchSize = 256,
            ubatchSize = 128,
            gpuLayers = 99
        )

        val load1Started = CompletableDeferred<Unit>()
        val load1Cancelled = AtomicBoolean(false)

        val firstDeferred = async {
            try {
                controller.loadOrAttach(
                    identity = identity1,
                    scope = this,
                    cancellationAction = { load1Cancelled.set(true) }
                ) { _, _ ->
                    load1Started.complete(Unit)
                    delay(10_000) // Simulate long load
                    ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
                }
                fail("First load should have been cancelled")
                error("Unreachable")
            } catch (e: Exception) {
                // Expected cancellation
                e
            }
        }

        load1Started.await()

        // Immediately start second load with different model identity
        val secondDeferred = async {
            controller.loadOrAttach(
                identity = identity2,
                scope = this
            ) { _, _ ->
                config2
            }
        }

        val firstException = firstDeferred.await()
        assertTrue("First load must throw CancellationException or IllegalStateException",
            firstException is kotlinx.coroutines.CancellationException || firstException is IllegalStateException)
        assertTrue("Cancellation action should be invoked", load1Cancelled.get())

        val secondResult = secondDeferred.await()
        assertEquals(config2, secondResult)
        assertTrue("Controller should be ready", controller.isReady)

        val finalState = controller.loadState.value
        assertTrue("Final state must be Ready", finalState is ModelLoadState.Ready)
        assertEquals(config2, (finalState as ModelLoadState.Ready).actualConfiguration)
    }

    @Test
    fun testResetToUnloadedTimesOutOrThrowsTransitionsToShutdownStalled() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/test-model.gguf",
            runtimeKind = "Gguf"
        )
        controller.loadOrAttach(identity, this) { _, _ ->
            ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
        }
        assertTrue(controller.isReady)

        // Reset with failing native unload action
        val resetJob = controller.resetToUnloaded(unloadAction = {
            throw RuntimeException("Simulated native unload crash")
        })
        resetJob.join()

        val state = controller.loadState.value
        assertTrue("State should be ShutdownStalled on unload failure", state is ModelLoadState.ShutdownStalled)

        // Subsequent load attempts must be rejected while shutdown is stalled
        try {
            controller.loadOrAttach(identity, this) { _, _ ->
                ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
            }
            fail("Load should fail when shutdown is stalled")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("stalled") == true)
        }
    }

    @Test
    fun testResetToUnloadedDuringActiveLoadCompletesSafely() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/cancellable-model.gguf",
            runtimeKind = "Gguf"
        )

        val loadStarted = CompletableDeferred<Unit>()
        val cancelInvoked = AtomicBoolean(false)

        val loadJob = launch {
            try {
                controller.loadOrAttach(
                    identity = identity,
                    scope = this,
                    cancellationAction = { cancelInvoked.set(true) }
                ) { _, _ ->
                    loadStarted.complete(Unit)
                    delay(10_000)
                    ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
                }
            } catch (_: Exception) {}
        }

        loadStarted.await()
        val resetJob = controller.resetToUnloaded()
        resetJob.join()
        loadJob.join()

        assertTrue("Cancellation action should be invoked", cancelInvoked.get())
        assertEquals(ModelLoadState.Unloaded, controller.loadState.value)
        assertFalse(controller.isReady)
    }

    @Test
    fun testModelLoadEmitsAsyncTraceEvents() = runBlocking {
        val events = mutableListOf<String>()
        val traceListener = object : com.shounak.localmeshai.utils.AppTrace.TraceListener {
            override fun onBeginAsyncSection(name: String, cookie: Int) {
                events.add("begin:$name")
            }
            override fun onEndAsyncSection(name: String, cookie: Int) {
                events.add("end:$name")
            }
        }
        com.shounak.localmeshai.utils.AppTrace.listener = traceListener
        try {
            val controller = ModelLoadController()
            val identity = ModelLoadIdentity(
                modelPath = "/path/to/traced-model.gguf",
                runtimeKind = "Gguf"
            )
            val config = ModelActualConfiguration(
                runtimeKind = "Gguf",
                backend = "CPU",
                threads = 4,
                contextWindow = 2048,
                batchSize = 256,
                ubatchSize = 128,
                gpuLayers = 0
            )

            controller.loadOrAttach(identity, this) { _, updateProgress ->
                updateProgress(ModelLoadStage.CpuLoading, 50, 100)
                config
            }

            var attempts = 0
            while (!events.contains("end:model_load") && attempts++ < 50) {
                delay(10)
            }

            assertTrue("Should start model_load trace", events.contains("begin:model_load"))
            assertTrue("Should start stage_WaitingForRuntime trace", events.contains("begin:stage_WaitingForRuntime"))
            assertTrue("Should transition to stage_CpuLoading trace", events.contains("begin:stage_CpuLoading"))
            assertTrue("Should end model_load trace", events.contains("end:model_load"))
        } finally {
            com.shounak.localmeshai.utils.AppTrace.listener = null
        }
    }

    @Test
    fun testShutdownReturnsSuccessWhenIdle() = runBlocking {
        val controller = ModelLoadController()
        val result = controller.shutdown()
        assertEquals(ShutdownResult.Success, result)
        assertEquals(ModelLoadState.Unloaded, controller.loadState.value)
    }

    @Test
    fun testShutdownReturnsFailedWhenUnloadThrows() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/test-model.gguf",
            runtimeKind = "Gguf"
        )
        controller.loadOrAttach(identity, this) { _, _ ->
            ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
        }
        val result = controller.shutdown(unloadAction = {
            throw RuntimeException("Unload exploded")
        })
        assertTrue("Shutdown must return Failed", result is ShutdownResult.Failed)
        assertTrue("Controller state must be ShutdownStalled", controller.loadState.value is ModelLoadState.ShutdownStalled)
    }

    @Test
    fun testScopedCancellationIgnoresMismatchedOperationId() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/scoped-test.gguf",
            runtimeKind = "Gguf"
        )
        val hold = CompletableDeferred<Unit>()
        val config = ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)

        val loadJob = async {
            controller.loadOrAttach(identity, this) { _, _ ->
                hold.await()
                config
            }
        }

        while (!controller.isInitializing) {
            delay(10)
        }

        // Cancel with wrong operation ID -> must be ignored
        controller.cancelActiveOperation(targetOperationId = "non-matching-op-id", reason = "Stale stop")
        assertTrue("Controller should still be initializing", controller.isInitializing)

        // Now release hold and verify it succeeds
        hold.complete(Unit)
        val result = loadJob.await()
        assertEquals(config, result)
        assertTrue(controller.isReady)
    }

    @Test
    fun testScopedShutdownReturnsStaleNoOpForMismatchedTarget(): Unit = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/path/to/scoped-shutdown.gguf",
            runtimeKind = "Gguf"
        )
        val hold = CompletableDeferred<Unit>()
        val loadJob = async {
            controller.loadOrAttach(identity, this) { _, _ ->
                hold.await()
                ModelActualConfiguration("Gguf", "CPU", 4, 1024, 128, 64, 0)
            }
        }

        while (!controller.isInitializing) {
            delay(10)
        }

        val result = controller.shutdown(targetOperationId = "wrong-op-id")
        assertEquals(ShutdownResult.StaleNoOp, result)

        hold.complete(Unit)
        loadJob.await()
        Unit
    }
}


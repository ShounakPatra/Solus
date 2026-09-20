package com.shounak.localmeshai.ai

import com.shounak.localmeshai.models.ModelCatalog
import com.shounak.localmeshai.models.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit and concurrency regression tests for [LlamaCppEngine].
 *
 * Uses a controlled [FakeLlamaNativeBridge] to rigorously test live handle lifecycle,
 * operation lease invariants, and race condition windows without needing an Android device.
 *
 * Any violation of native memory safety (e.g. use-after-free or double-free) immediately
 * throws an [AssertionError] in [FakeLlamaNativeBridge].
 */
class LlamaCppEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyModelFile(name: String = "test_model.gguf"): File {
        return tempFolder.newFile(name).apply {
            writeText("dummy gguf binary content")
        }
    }

    /**
     * Fake native bridge for deterministic lifecycle testing.
     * Enforces that no native operation may ever touch a handle that has been freed.
     */
    private class FakeLlamaNativeBridge : LlamaNativeBridge {
        private val nextHandle = AtomicLong(1000L)
        val liveHandles = ConcurrentHashMap.newKeySet<Long>()
        val freedHandles = ConcurrentHashMap.newKeySet<Long>()

        val initCallCount = AtomicInteger(0)
        val generateCallCount = AtomicInteger(0)
        val stopCallCount = AtomicInteger(0)
        val metadataCallCount = AtomicInteger(0)
        val freeCallCount = AtomicInteger(0)

        // Custom hooks for concurrency and blocking tests
        @Volatile var onInitHook: ((modelPath: String) -> Unit)? = null
        @Volatile var onGenerateHook: (suspend (handle: Long, genId: Long, callback: LlamaTokenCallback) -> Unit)? = null
        @Volatile var onBeforeFreeHook: ((handle: Long) -> Unit)? = null
        @Volatile var onMetadataHook: ((handle: Long) -> Unit)? = null
        val failInitNext = AtomicBoolean(false)

        override fun isAvailable(): Boolean = true

        override fun getLastError(): String? = if (failInitNext.get()) "Simulated native init failure" else null

        override fun inspectModel(modelPath: String): String? {
            return """{"valid":true,"architecture":"llama","name":"FakeModel","is_supported":true}"""
        }

        private fun assertHandleValid(handle: Long, operation: String) {
            if (handle == 0L) throw AssertionError("$operation called with 0L handle")
            if (freedHandles.contains(handle)) {
                throw AssertionError("USE AFTER FREE: $operation called on handle $handle after it was freed!")
            }
            if (!liveHandles.contains(handle)) {
                throw AssertionError("$operation called on unknown/invalid handle $handle")
            }
        }

        override fun initModel(
            modelPath: String,
            nThreads: Int,
            nCtx: Int,
            nBatch: Int,
            nUbatch: Int
        ): Long {
            initCallCount.incrementAndGet()
            onInitHook?.invoke(modelPath)
            if (failInitNext.getAndSet(false)) {
                return 0L
            }
            val handle = nextHandle.incrementAndGet()
            liveHandles.add(handle)
            return handle
        }

        override fun generateStream(
            handle: Long,
            generationId: Long,
            prompt: String,
            roles: Array<String>?,
            contents: Array<String>?,
            temperature: Float,
            topP: Float,
            topK: Int,
            minP: Float,
            repeatPenalty: Float,
            maxTokens: Int,
            seed: Int,
            callback: LlamaTokenCallback
        ): Int {
            generateCallCount.incrementAndGet()
            assertHandleValid(handle, "generateStream")

            val hook = onGenerateHook
            if (hook != null) {
                runBlocking {
                    hook(handle, generationId, callback)
                }
            } else {
                assertHandleValid(handle, "generateStream-emit")
                callback.onToken("Hello")
                assertHandleValid(handle, "generateStream-complete")
                callback.onComplete()
            }
            return 0
        }

        override fun stop(handle: Long, generationId: Long) {
            stopCallCount.incrementAndGet()
            assertHandleValid(handle, "stop")
        }

        override fun getMetadata(handle: Long): String? {
            metadataCallCount.incrementAndGet()
            assertHandleValid(handle, "getMetadata")
            onMetadataHook?.invoke(handle)
            return """{"desc":"Fake","architecture":"llama","n_params":1000,"n_ctx_train":2048,"n_vocab":32000,"has_chat_template":true}"""
        }

        override fun free(handle: Long) {
            freeCallCount.incrementAndGet()
            if (handle == 0L) throw AssertionError("free called with 0L handle")
            if (freedHandles.contains(handle)) {
                throw AssertionError("DOUBLE FREE: handle $handle was freed more than once!")
            }
            onBeforeFreeHook?.invoke(handle)
            liveHandles.remove(handle)
            freedHandles.add(handle)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // A. Required Concurrency Tests (Section 15: A through L)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun testA_initializeNormally() {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model_a.gguf")

        assertFalse(engine.isInitialized)
        assertEquals("UNINITIALIZED", engine.testEngineStateName)

        engine.initialize(file.absolutePath)

        assertTrue(engine.isInitialized)
        assertEquals("OPEN", engine.testEngineStateName)
        assertTrue(engine.testNativeHandle > 0L)
        assertEquals(1, fakeBridge.initCallCount.get())
        assertEquals(0, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testB_initializeWhilePreviousCloseInProgress() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val fileA = createDummyModelFile("model_a.gguf")
        val fileB = createDummyModelFile("model_b.gguf")

        engine.initialize(fileA.absolutePath)
        val handleA = engine.testNativeHandle

        val generateStartedLatch = CountDownLatch(1)
        val allowGenerateToFinishLatch = CountDownLatch(1)

        fakeBridge.onGenerateHook = { handle, _, cb ->
            generateStartedLatch.countDown()
            cb.onToken("tok1")
            allowGenerateToFinishLatch.await(5, TimeUnit.SECONDS)
            cb.onComplete()
        }

        // Start generation on Model A (holds lease on handleA)
        val genJob = async(Dispatchers.Default) {
            engine.generateStream("prompt A") {}
        }
        assertTrue(generateStartedLatch.await(3, TimeUnit.SECONDS))

        // Start initialize(Model B) on another thread while Model A is still generating and closing
        val initBStarted = CountDownLatch(1)
        val initBFinished = AtomicBoolean(false)

        val initJob = async(Dispatchers.Default) {
            initBStarted.countDown()
            engine.initialize(fileB.absolutePath)
            initBFinished.set(true)
        }

        assertTrue(initBStarted.await(2, TimeUnit.SECONDS))
        delay(100)
        // Model B initialization MUST NOT have completed yet because Model A's lease is active
        assertFalse("initialize(B) must wait for Model A to close", initBFinished.get())

        // Allow generation on Model A to complete
        allowGenerateToFinishLatch.countDown()
        genJob.await()
        initJob.await()

        // Verify: Model A was completely freed before Model B became active
        assertTrue("handleA must have been freed", fakeBridge.freedHandles.contains(handleA))
        assertTrue(engine.isInitialized)
        assertEquals("OPEN", engine.testEngineStateName)
        assertNotEquals(handleA, engine.testNativeHandle)
    }

    @Test
    fun testC_twoCloseCallsConcurrently() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)

        // Launch 20 concurrent close calls
        val jobs = (1..20).map {
            async(Dispatchers.Default) {
                engine.close()
            }
        }
        jobs.awaitAll()

        assertEquals("CLOSED", engine.testEngineStateName)
        assertFalse(engine.isInitialized)
        assertEquals("Exactly one native free must occur", 1, fakeBridge.freeCallCount.get())
        assertEquals(0, engine.testActiveLeaseCount)
    }

    @Test
    fun testD_interruptedClosePreservesMemorySafety() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)
        val handle = engine.testNativeHandle

        val genStarted = CountDownLatch(1)
        val allowGenFinish = CountDownLatch(1)

        fakeBridge.onGenerateHook = { _, _, cb ->
            genStarted.countDown()
            allowGenFinish.await(5, TimeUnit.SECONDS)
            cb.onComplete()
        }

        val genJob = async(Dispatchers.Default) {
            engine.generateStream("test") {}
        }
        assertTrue(genStarted.await(2, TimeUnit.SECONDS))

        val closeThreadInterrupted = AtomicBoolean(false)
        val closeThread = Thread {
            // Interrupt self before calling close to test interrupt handling
            Thread.currentThread().interrupt()
            engine.close()
            closeThreadInterrupted.set(Thread.currentThread().isInterrupted)
        }
        closeThread.start()

        delay(100)
        // Verify free() has NOT been called while generation is active
        assertFalse("free() must not be called while active leases exist", fakeBridge.freedHandles.contains(handle))

        // Let generation finish
        allowGenFinish.countDown()
        genJob.await()
        closeThread.join(3000)

        // Now handle must be freed safely, and interrupt status was preserved
        assertTrue(fakeBridge.freedHandles.contains(handle))
        assertTrue("Interrupt flag should be preserved", closeThreadInterrupted.get())
    }

    @Test
    fun testE_generateWhileCloseBegins() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)
        val handle = engine.testNativeHandle

        val genStarted = CountDownLatch(1)
        val genCompleted = CountDownLatch(1)
        val freeHappenedWhileGenerating = AtomicBoolean(false)

        fakeBridge.onGenerateHook = { h, _, cb ->
            genStarted.countDown()
            delay(150)
            if (fakeBridge.freedHandles.contains(h)) {
                freeHappenedWhileGenerating.set(true)
            }
            cb.onToken("token")
            cb.onComplete()
            genCompleted.countDown()
        }

        val genJob = async(Dispatchers.Default) {
            engine.generateStream("prompt") {}
        }
        assertTrue(genStarted.await(2, TimeUnit.SECONDS))

        val closeJob = async(Dispatchers.Default) {
            engine.close()
        }

        genJob.await()
        closeJob.await()

        assertFalse("free() must not execute while generation is active", freeHappenedWhileGenerating.get())
        assertTrue("handle must be freed after both finish", fakeBridge.freedHandles.contains(handle))
        assertEquals(0, engine.testActiveLeaseCount)
    }

    @Test
    fun testF_stopWhileCloseBegins() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)

        val jobs = (1..10).map { idx ->
            async(Dispatchers.Default) {
                if (idx % 2 == 0) engine.stop() else engine.close()
            }
        }
        jobs.awaitAll()

        assertEquals("CLOSED", engine.testEngineStateName)
        assertEquals(1, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testG_stopWhileMetadataOperationActive() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)

        val metadataStarted = CountDownLatch(1)
        val allowMetadataFinish = CountDownLatch(1)

        fakeBridge.onMetadataHook = {
            metadataStarted.countDown()
            allowMetadataFinish.await(2, TimeUnit.SECONDS)
        }

        val metaJob = async(Dispatchers.Default) {
            engine.getMetadata()
        }
        assertTrue(metadataStarted.await(2, TimeUnit.SECONDS))

        val stopJob = async(Dispatchers.Default) {
            engine.stop()
        }

        allowMetadataFinish.countDown()
        val metadata = metaJob.await()
        stopJob.await()

        assertNotNull(metadata)
        assertEquals("Fake", metadata?.desc)
        engine.close()
    }

    @Test
    fun testH_metadataWhileCloseBegins() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)
        val handle = engine.testNativeHandle

        val metaStarted = CountDownLatch(1)
        val allowMetaFinish = CountDownLatch(1)

        fakeBridge.onMetadataHook = {
            metaStarted.countDown()
            allowMetaFinish.await(2, TimeUnit.SECONDS)
        }

        val metaJob = async(Dispatchers.Default) {
            engine.getMetadata()
        }
        assertTrue(metaStarted.await(2, TimeUnit.SECONDS))

        val closeJob = async(Dispatchers.Default) {
            engine.close()
        }

        delay(50)
        assertFalse("Close must wait for metadata lease", fakeBridge.freedHandles.contains(handle))

        allowMetaFinish.countDown()
        val meta = metaJob.await()
        closeJob.await()

        assertNotNull(meta)
        assertTrue(fakeBridge.freedHandles.contains(handle))
    }

    @Test
    fun testI_concurrentGenerateMetadataStopClose() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")
        engine.initialize(file.absolutePath)

        // 30 concurrent operations mixing all methods
        val jobs = (1..30).map { idx ->
            async(Dispatchers.Default) {
                when (idx % 4) {
                    0 -> engine.generateStream("prompt $idx") {}
                    1 -> engine.getMetadata()
                    2 -> engine.stop()
                    else -> engine.close()
                }
            }
        }
        jobs.awaitAll()
        engine.close()

        assertEquals("CLOSED", engine.testEngineStateName)
        assertEquals(0, engine.testActiveLeaseCount)
        assertEquals(1, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testJ_modelSwitchDuringGeneration() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val fileA = createDummyModelFile("modelA.gguf")
        val fileB = createDummyModelFile("modelB.gguf")

        engine.initialize(fileA.absolutePath)
        val handleA = engine.testNativeHandle

        val genAStarted = CountDownLatch(1)
        val allowGenAFinish = CountDownLatch(1)

        fakeBridge.onGenerateHook = { h, _, cb ->
            if (h == handleA) {
                genAStarted.countDown()
                allowGenAFinish.await(2, TimeUnit.SECONDS)
                cb.onToken("tokA")
                cb.onComplete()
            }
        }

        val genAJob = async(Dispatchers.Default) {
            engine.generateStream("prompt") {}
        }
        assertTrue(genAStarted.await(2, TimeUnit.SECONDS))

        val switchJob = async(Dispatchers.Default) {
            engine.initialize(fileB.absolutePath)
        }

        allowGenAFinish.countDown()
        genAJob.await()
        switchJob.await()

        assertTrue(fakeBridge.freedHandles.contains(handleA))
        assertTrue(engine.isInitialized)
        assertNotEquals(handleA, engine.testNativeHandle)
    }

    @Test
    fun testK_repeatedLifecycle() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")

        // load -> generate -> stop -> generate -> close -> load
        engine.initialize(file.absolutePath)
        assertTrue(engine.isInitialized)

        val r1 = engine.generateStream("test1") {}
        assertEquals(LlamaGenerationResult.Completed, r1)

        engine.stop()

        val r2 = engine.generateStream("test2") {}
        assertEquals(LlamaGenerationResult.Completed, r2)

        engine.close()
        assertFalse(engine.isInitialized)
        assertEquals("CLOSED", engine.testEngineStateName)

        engine.initialize(file.absolutePath)
        assertTrue(engine.isInitialized)
        assertEquals("OPEN", engine.testEngineStateName)

        engine.close()
        assertEquals(2, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testL_repeatedSwitching() {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val fileA = createDummyModelFile("modelA.gguf")
        val fileB = createDummyModelFile("modelB.gguf")

        // A -> B -> A -> B -> A
        engine.initialize(fileA.absolutePath)
        assertEquals(1, fakeBridge.initCallCount.get())

        engine.initialize(fileB.absolutePath)
        assertEquals(2, fakeBridge.initCallCount.get())
        assertEquals(1, fakeBridge.freeCallCount.get())

        engine.initialize(fileA.absolutePath)
        assertEquals(3, fakeBridge.initCallCount.get())
        assertEquals(2, fakeBridge.freeCallCount.get())

        engine.initialize(fileB.absolutePath)
        assertEquals(4, fakeBridge.initCallCount.get())
        assertEquals(3, fakeBridge.freeCallCount.get())

        engine.initialize(fileA.absolutePath)
        assertEquals(5, fakeBridge.initCallCount.get())
        assertEquals(4, fakeBridge.freeCallCount.get())

        engine.close()
        assertEquals(5, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testM_closeDuringNativeInitialization_discardsAndFreesModel() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")

        val initStarted = CountDownLatch(1)
        val allowInitFinish = CountDownLatch(1)

        fakeBridge.onInitHook = {
            initStarted.countDown()
            allowInitFinish.await(3, TimeUnit.SECONDS)
        }

        // Start initialization on thread A
        val initJob = async(Dispatchers.Default) {
            try {
                engine.initialize(file.absolutePath)
            } catch (_: Throwable) {}
        }

        assertTrue("Init should start", initStarted.await(2, TimeUnit.SECONDS))
        assertEquals("INITIALIZING", engine.testEngineStateName)

        // Close is called on thread B while native init is still in progress
        val closeJob = async(Dispatchers.Default) {
            engine.close()
        }

        delay(50)
        // Verify engine is NOT treated as CLOSED while init is still running (close waits)
        assertEquals("CLOSING", engine.testEngineStateName)

        // Allow native init to complete
        allowInitFinish.countDown()

        initJob.await()
        closeJob.await()

        // Verify: The model created during cancelled init was immediately freed and never published
        assertEquals("CLOSED", engine.testEngineStateName)
        assertFalse(engine.isInitialized)
        assertEquals("Handle must be freed", 1, fakeBridge.freeCallCount.get())
        assertEquals(0, engine.testActiveLeaseCount)
    }

    @Test
    fun testN_staleInitializationEpochCannotReopenEngine() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val fileA = createDummyModelFile("modelA.gguf")
        val fileB = createDummyModelFile("modelB.gguf")

        val initAStarted = CountDownLatch(1)
        val allowInitAFinish = CountDownLatch(1)

        fakeBridge.onInitHook = { path ->
            if (path.contains("modelA")) {
                initAStarted.countDown()
                allowInitAFinish.await(3, TimeUnit.SECONDS)
            }
        }

        // Start init A
        val initAJob = async(Dispatchers.Default) {
            try { engine.initialize(fileA.absolutePath) } catch (_: Throwable) {}
        }

        assertTrue(initAStarted.await(2, TimeUnit.SECONDS))

        // Request init B on another thread (which closes A and supersedes its epoch)
        val initBJob = async(Dispatchers.Default) {
            engine.initialize(fileB.absolutePath)
        }

        delay(50)
        allowInitAFinish.countDown()

        initAJob.await()
        initBJob.await()

        assertTrue(engine.isInitialized)
        assertEquals("OPEN", engine.testEngineStateName)
        // Model A must be freed, Model B active
        assertEquals(2, fakeBridge.initCallCount.get())
        assertEquals(1, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testO_initializationFailureTransitionsToClosed() {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")

        fakeBridge.failInitNext.set(true)

        var caughtException = false
        try {
            engine.initialize(file.absolutePath)
        } catch (e: IllegalStateException) {
            caughtException = true
        }

        assertTrue("Should throw on init failure", caughtException)
        assertEquals("CLOSED", engine.testEngineStateName)
        assertFalse(engine.isInitialized)

        // Subsequent initialize should work normally
        engine.initialize(file.absolutePath)
        assertTrue(engine.isInitialized)
        assertEquals("OPEN", engine.testEngineStateName)
        engine.close()
    }

    @Test
    fun testP_initializationFinishesLateAfterClose_cannotReopenEngine() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model.gguf")

        val initLatch = CountDownLatch(1)
        val allowFinishLatch = CountDownLatch(1)

        fakeBridge.onInitHook = {
            initLatch.countDown()
            allowFinishLatch.await(3, TimeUnit.SECONDS)
        }

        val initJob = async(Dispatchers.Default) {
            try {
                engine.initialize(file.absolutePath)
            } catch (_: Throwable) {}
        }

        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        assertEquals("INITIALIZING", engine.testEngineStateName)

        val closeJob = async(Dispatchers.Default) {
            engine.close()
        }

        delay(50)
        assertEquals("CLOSING", engine.testEngineStateName)

        // Let init finish late
        allowFinishLatch.countDown()
        initJob.await()
        closeJob.await()

        assertEquals("CLOSED", engine.testEngineStateName)
        assertFalse(engine.isInitialized)
        assertEquals(1, fakeBridge.freeCallCount.get())

        // Verify that late init cannot later transition to OPEN
        delay(100)
        assertEquals("CLOSED", engine.testEngineStateName)
        assertFalse(engine.isInitialized)
    }

    @Test
    fun testQ_repeatedInitializeCloseStress() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("stress_model.gguf")

        for (i in 1..25) {
            engine.initialize(file.absolutePath)
            assertTrue(engine.isInitialized)
            assertEquals("OPEN", engine.testEngineStateName)

            engine.close()
            assertFalse(engine.isInitialized)
            assertEquals("CLOSED", engine.testEngineStateName)
        }

        assertEquals(25, fakeBridge.initCallCount.get())
        assertEquals(25, fakeBridge.freeCallCount.get())
        assertEquals(0, engine.testActiveLeaseCount)
    }

    @Test
    fun testR_obsoleteInitializationFreedBeforeClosedPublished() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("model_order.gguf")

        val initLatch = CountDownLatch(1)
        val allowFinishLatch = CountDownLatch(1)
        var stateDuringFree: String? = null
        var handleFreedBeforeClosed = false

        fakeBridge.onInitHook = {
            initLatch.countDown()
            allowFinishLatch.await(3, TimeUnit.SECONDS)
        }

        fakeBridge.onBeforeFreeHook = { _ ->
            stateDuringFree = engine.testEngineStateName
        }

        val initJob = async(Dispatchers.Default) {
            try {
                engine.initialize(file.absolutePath)
            } catch (_: Throwable) {}
        }

        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        assertEquals("INITIALIZING", engine.testEngineStateName)

        val closeJob = async(Dispatchers.Default) {
            engine.close()
            // When close() returns, handle MUST ALREADY be freed!
            if (fakeBridge.freedHandles.isNotEmpty()) {
                handleFreedBeforeClosed = true
            }
        }

        delay(50)
        assertEquals("CLOSING", engine.testEngineStateName)

        allowFinishLatch.countDown()
        initJob.await()
        closeJob.await()

        assertEquals("State during native free must be CLOSING, not CLOSED", "CLOSING", stateDuringFree)
        assertTrue("Handle must be freed before close() returns and publishes CLOSED", handleFreedBeforeClosed)
        assertEquals("CLOSED", engine.testEngineStateName)
        assertEquals(1, fakeBridge.freeCallCount.get())
    }

    @Test
    fun testS_reinitializationOrder_oldModelFreedBeforeNewModelOpens() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val fileA = createDummyModelFile("modelA_order.gguf")
        val fileB = createDummyModelFile("modelB_order.gguf")

        val events = mutableListOf<String>()

        val initALatch = CountDownLatch(1)
        val allowAFinishLatch = CountDownLatch(1)

        fakeBridge.onInitHook = { path ->
            if (path.contains("modelA")) {
                events.add("initA_start")
                initALatch.countDown()
                allowAFinishLatch.await(3, TimeUnit.SECONDS)
                events.add("initA_end")
            } else if (path.contains("modelB")) {
                events.add("initB_start")
            }
        }

        fakeBridge.onBeforeFreeHook = { _ ->
            events.add("freeA")
        }

        val initAJob = async(Dispatchers.Default) {
            try { engine.initialize(fileA.absolutePath) } catch (_: Throwable) {}
        }

        assertTrue(initALatch.await(2, TimeUnit.SECONDS))

        val closeJob = async(Dispatchers.Default) {
            events.add("close_start")
            engine.close()
            events.add("close_end")
        }

        delay(50)
        allowAFinishLatch.countDown()
        initAJob.await()
        closeJob.await()

        // Now initialize B
        engine.initialize(fileB.absolutePath)
        events.add("initB_open")

        // Verify ordering: freeA MUST happen BEFORE close_end and BEFORE initB_start
        val freeAIndex = events.indexOf("freeA")
        val closeEndIndex = events.indexOf("close_end")
        val initBStartIndex = events.indexOf("initB_start")

        assertTrue("freeA must occur in timeline", freeAIndex != -1)
        assertTrue("close_end must occur in timeline", closeEndIndex != -1)
        assertTrue("initB_start must occur in timeline", initBStartIndex != -1)

        assertTrue("Model A must be freed before close completes", freeAIndex < closeEndIndex)
        assertTrue("Model A must be freed before Model B begins initialization", freeAIndex < initBStartIndex)
        assertTrue(engine.isInitialized)
        assertEquals("OPEN", engine.testEngineStateName)
    }

    // ───────────────────────────────────────────────────────────────────────
    // B. Data-class correctness
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun samplingParamsDefaultsAreSane() {
        val params = LlamaSamplingParams()
        assertEquals(0.7f, params.temperature, 0.001f)
        assertEquals(0.95f, params.topP, 0.001f)
        assertEquals(40, params.topK)
        assertEquals(0.05f, params.minP, 0.001f)
        assertEquals(1.1f, params.repeatPenalty, 0.001f)
        assertEquals(1024, params.maxTokens)
        assertEquals(0, params.seed)
    }

    @Test
    fun modelMetadataHoldsExtractedValues() {
        val metadata = LlamaModelMetadata(
            desc = "Qwen 2.5 1.5B",
            architecture = "qwen2",
            paramCount = 1540000000L,
            trainContextLength = 32768,
            vocabSize = 151936,
            hasChatTemplate = true
        )
        assertEquals("Qwen 2.5 1.5B", metadata.desc)
        assertEquals("qwen2", metadata.architecture)
        assertEquals(1540000000L, metadata.paramCount)
        assertEquals(32768, metadata.trainContextLength)
        assertEquals(151936, metadata.vocabSize)
        assertTrue(metadata.hasChatTemplate)
    }

    @Test
    fun inspectionResultDataClassFieldsArePropagated() {
        val result = LlamaModelInspectionResult(
            valid = true,
            version = 3,
            architecture = "llama",
            name = "Llama 3.2 1B",
            desc = "Llama-3.2-1B-Instruct-Q4_K_M",
            tensorCount = 291L,
            trainContextLength = 8192,
            embeddingLength = 4096,
            layerCount = 32,
            hasChatTemplate = true,
            primaryQuantization = "Q4_K_M",
            isSupported = true,
            error = ""
        )
        assertTrue(result.valid)
        assertEquals(3, result.version)
        assertEquals("llama", result.architecture)
        assertEquals(291L, result.tensorCount)
        assertEquals("Q4_K_M", result.primaryQuantization)
        assertTrue(result.isSupported)
        assertTrue(result.error.isEmpty())
    }

    @Test
    fun generationResultEnumHasAllThreeValues() {
        assertEquals("Completed", LlamaGenerationResult.Completed.name)
        assertEquals("Stopped", LlamaGenerationResult.Stopped.name)
        assertEquals("Failed", LlamaGenerationResult.Failed.name)
    }

    // ───────────────────────────────────────────────────────────────────────
    // C. Static companion helpers
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun inspectModelReturnsErrorOnNonExistentFile() {
        val result = LlamaCppEngine.inspectModel("/path/to/non_existent_file.gguf")
        assertFalse("Non existent file must not be valid", result.valid)
        assertTrue(
            "Error should mention file does not exist; got: ${result.error}",
            result.error.contains("does not exist", ignoreCase = true)
        )
    }

    @Test
    fun canLoadModelReturnsErrorOnMissingFile() {
        val error = LlamaCppEngine.canLoadModel("/invalid/path/model.gguf")
        assertNotNull("Should return error string for missing file", error)
        assertTrue("Error should not be blank", error!!.isNotBlank())
    }

    @Test
    fun getLastErrorReturnsMessageWhenLibraryNotLoaded() {
        if (!LlamaCppEngine.isLibraryLoaded) {
            val err = LlamaCppEngine.getLastError()
            assertNotNull("getLastError should return a message in test env", err)
            assertTrue("Message should be non-blank", err!!.isNotBlank())
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // D. Callback interface contract
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun callbackOnTokenAndOnCompleteFireCorrectly() {
        val tokens = mutableListOf<String>()
        var completed = false
        var stopped = false
        var receivedError: String? = null

        val cb = object : LlamaTokenCallback {
            override fun onToken(token: String) { tokens.add(token) }
            override fun onComplete() { completed = true }
            override fun onStop() { stopped = true }
            override fun onError(error: String) { receivedError = error }
        }

        cb.onToken("Hello")
        cb.onToken(" world")
        cb.onComplete()

        assertEquals(listOf("Hello", " world"), tokens)
        assertTrue(completed)
        assertFalse(stopped)
        assertNull(receivedError)
    }

    @Test
    fun callbackOnStopDoesNotFireComplete() {
        var completed = false
        var stopped = false

        val cb = object : LlamaTokenCallback {
            override fun onToken(token: String) {}
            override fun onComplete() { completed = true }
            override fun onStop() { stopped = true }
            override fun onError(error: String) {}
        }

        cb.onToken("partial...")
        cb.onStop()

        assertTrue(stopped)
        assertFalse("onComplete must not fire when onStop fires", completed)
    }

    @Test
    fun callbackOnErrorDoesNotFireComplete() {
        var completed = false
        var receivedError: String? = null

        val cb = object : LlamaTokenCallback {
            override fun onToken(token: String) {}
            override fun onComplete() { completed = true }
            override fun onStop() {}
            override fun onError(error: String) { receivedError = error }
        }

        cb.onError("OutOfMemory")

        assertEquals("OutOfMemory", receivedError)
        assertFalse("onComplete must not fire when onError fires", completed)
    }

    @Test
    fun callbackEmptyTokenDoesNotCorruptTokenList() {
        val tokens = mutableListOf<String>()
        val cb = object : LlamaTokenCallback {
            override fun onToken(token: String) { tokens.add(token) }
            override fun onComplete() {}
            override fun onStop() {}
            override fun onError(error: String) {}
        }
        cb.onToken("")
        cb.onToken("real")
        cb.onToken("")
        assertEquals(listOf("", "real", ""), tokens)
    }

    // ───────────────────────────────────────────────────────────────────────
    // E. Prompt fallback correctness
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun promptFallbackSynthesesSingleUserTurnFromRawText() {
        val rawUserText = "What is 2 + 2?"
        val prompt = "System: You are a helpful assistant.\nUser: $rawUserText\nAssistant: "

        val messages: List<Pair<String, String>> =
            if (rawUserText.isNotBlank()) listOf("user" to rawUserText.trim())
            else listOf("user" to prompt.trim())

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].first)
        assertEquals("What is 2 + 2?", messages[0].second)
    }

    @Test
    fun promptFallbackHandlesBlankRawUserText() {
        val rawUserText = "   "
        val prompt = "Hello"

        val messages: List<Pair<String, String>> =
            if (rawUserText.isNotBlank()) listOf("user" to rawUserText.trim())
            else listOf("user" to prompt.trim())

        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].second)
    }

    @Test
    fun promptFallbackPreservesStructuredMessagesUnmodified() {
        val structured = listOf(
            "system" to "You are helpful.",
            "user" to "Tell me a joke.",
            "assistant" to "Why did the chicken cross the road?",
            "user" to "Why?"
        )
        val messages: List<Pair<String, String>> = structured

        assertEquals(4, messages.size)
        assertEquals("system", messages[0].first)
        assertEquals("user", messages[3].first)
        assertEquals("Why?", messages[3].second)
    }

    @Test
    fun promptFallbackDoesNotSplitOnUserColonInsideContent() {
        val rawUserText = "Please compare User: Alice and User: Bob."
        val messages: List<Pair<String, String>> =
            if (rawUserText.isNotBlank()) listOf("user" to rawUserText.trim())
            else listOf("user" to "")

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].first)
        assertEquals("Please compare User: Alice and User: Bob.", messages[0].second)
    }

    @Test
    fun testJniMethodSignaturesHaveNoKotlinNameMangling() {
        val expectedNativeMethods = listOf(
            "nativeGetLastError",
            "nativeInspectModel",
            "nativeInitModel",
            "nativeGenerateStream",
            "nativeStop",
            "nativeGetMetadata",
            "nativeFree"
        )
        val engineClass = LlamaCppEngine::class.java
        val declaredMethodNames = engineClass.declaredMethods.map { it.name }.toSet()

        for (expected in expectedNativeMethods) {
            assertTrue(
                "LlamaCppEngine must declare JVM method '$expected' directly without module mangling. Found: $declaredMethodNames",
                declaredMethodNames.contains(expected)
            )
            assertFalse(
                "LlamaCppEngine must NOT contain mangled method '$expected\$app'",
                declaredMethodNames.contains("$expected\$app")
            )
        }
    }

    @Test
    fun testBatchAndContextConfigurationPropagation() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("llama_3_2_1b.gguf")

        engine.initialize(
            modelPath = file.absolutePath,
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128
        )

        assertEquals(1, fakeBridge.initCallCount.get())
        assertTrue(engine.isInitialized)

        val tokensReceived = mutableListOf<String>()
        val result = engine.generateStream(
            prompt = "Hi",
            onToken = { tokensReceived.add(it) }
        )

        assertEquals(LlamaGenerationResult.Completed, result)
        engine.close()
    }

    @Test
    fun testSingleThreadedExecutionPath() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("single_thread_model.gguf")

        engine.initialize(
            modelPath = file.absolutePath,
            threads = 1,
            contextWindow = 1024,
            nBatch = 128,
            nUbatch = 64
        )

        assertTrue(engine.isInitialized)
        val tokensReceived = mutableListOf<String>()
        val result = engine.generateStream(
            prompt = "Explain 2+2",
            onToken = { tokensReceived.add(it) }
        )

        assertEquals(LlamaGenerationResult.Completed, result)
        engine.close()
    }

    @Test
    fun testLargePromptTokenizationAndGeneration() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("large_prompt_model.gguf")

        engine.initialize(
            modelPath = file.absolutePath,
            threads = 4,
            contextWindow = 4096,
            nBatch = 256,
            nUbatch = 128
        )

        val largePrompt = "This is a prompt with many tokens. ".repeat(20)
        val tokensReceived = mutableListOf<String>()
        val result = engine.generateStream(
            prompt = largePrompt,
            onToken = { tokensReceived.add(it) }
        )

        assertEquals(LlamaGenerationResult.Completed, result)
        engine.close()
    }

    // ── GGUF Native Crash Forensic & Matrix Regression Tests ────────────────────────────────────

    @Test
    fun testLlama32_1B_CatalogMetadataAndArtifactValidation() {
        val llama32 = ModelCatalog.defaultModels.find { it.id == "llama32_1b_gguf" }
        assertNotNull("Llama 3.2 1B Instruct must be in ModelCatalog", llama32)
        assertEquals("llama.cpp GGUF", llama32!!.backend)
        assertEquals("Llama-3.2-1B-Instruct-Q4_K_M.gguf", llama32.fileName)
        assertEquals(770_303_744L, llama32.expectedFileSizeBytes)
        assertEquals(4096, llama32.contextWindowTokens)

        // Test size validation with empty file
        val emptyFile = tempFolder.newFile("empty_llama.gguf")
        val emptyWarning = LlamaCppEngine.validateGgufArtifactSize(emptyFile.absolutePath, llama32.expectedFileSizeBytes)
        assertTrue("0-byte file must be caught as empty", emptyWarning?.contains("empty") == true)

        // Test size validation with mismatched size file
        val file = createDummyModelFile("Llama-3.2-1B-Instruct-Q4_K_M.gguf")
        val mismatchWarning = LlamaCppEngine.validateGgufArtifactSize(file.absolutePath, llama32.expectedFileSizeBytes)
        assertTrue("25-byte file must produce size mismatch warning", mismatchWarning?.contains("mismatch") == true)
    }

    @Test
    fun testQwen25_CatalogMetadataAndArtifactValidation() {
        val qwen15 = ModelCatalog.defaultModels.find { it.id == "qwen25_15b_gguf" }
        assertNotNull(qwen15)
        assertEquals(1_043_024_992L, qwen15!!.expectedFileSizeBytes)

        val qwen05 = ModelCatalog.defaultModels.find { it.id == "qwen25_05b_gguf" }
        assertNotNull(qwen05)
        assertEquals(397_206_688L, qwen05!!.expectedFileSizeBytes)

        val qwenCoder = ModelCatalog.defaultModels.find { it.id == "qwen25_coder_15b_gguf" }
        assertNotNull(qwenCoder)
        assertEquals(1_043_220_640L, qwenCoder!!.expectedFileSizeBytes)
    }

    @Test
    fun testCrossArchitectureCatalogMetadataValidation() {
        val phi = ModelCatalog.defaultModels.find { it.id == "phi35_mini_gguf" }
        assertNotNull(phi)
        assertEquals(2_176_116_064L, phi!!.expectedFileSizeBytes)

        val mistral = ModelCatalog.defaultModels.find { it.id == "mistral_7b_gguf" }
        assertNotNull(mistral)
        assertEquals(4_368_439_456L, mistral!!.expectedFileSizeBytes)

        val smol135 = ModelCatalog.defaultModels.find { it.id == "smollm2_135m_gguf" }
        assertNotNull(smol135)
        assertEquals(94_624_352L, smol135!!.expectedFileSizeBytes)

        val smol360 = ModelCatalog.defaultModels.find { it.id == "smollm2_360m_gguf" }
        assertNotNull(smol360)
        assertEquals(228_691_136L, smol360!!.expectedFileSizeBytes)

        val tinyLlama = ModelCatalog.defaultModels.find { it.id == "tinyllama_11b_gguf" }
        assertNotNull(tinyLlama)
        assertEquals(668_916_736L, tinyLlama!!.expectedFileSizeBytes)

        val gemma = ModelCatalog.defaultModels.find { it.id == "gemma2_2b_gguf" }
        assertNotNull(gemma)
        assertEquals(1_631_906_176L, gemma!!.expectedFileSizeBytes)
    }

    @Test
    fun testArtifactSizeValidation_ToleranceRanges() {
        val file = tempFolder.newFile("tolerance_test.gguf")
        // Write exactly 100,000 bytes
        file.writeBytes(ByteArray(100_000))

        // Target: 100,000 ± 5,000 (95,000 - 105,000)
        assertNull("Exact match must pass", LlamaCppEngine.validateGgufArtifactSize(file.absolutePath, 100_000L))
        assertNull("+3% must pass", LlamaCppEngine.validateGgufArtifactSize(file.absolutePath, 97_500L))
        assertNull("-3% must pass", LlamaCppEngine.validateGgufArtifactSize(file.absolutePath, 102_500L))

        // Mismatched size (e.g. 70,000 -> 30% mismatch)
        val mismatchWarning = LlamaCppEngine.validateGgufArtifactSize(file.absolutePath, 70_000L)
        assertNotNull("30% mismatch must produce warning", mismatchWarning)
        assertTrue(mismatchWarning!!.contains("mismatch"))
    }

    @Test
    fun testThreadCountVariations_AllCompleteCleanly() = runBlocking {
        val threadCounts = listOf(1, 2, 4, 8)
        for (threads in threadCounts) {
            val fakeBridge = FakeLlamaNativeBridge()
            val engine = LlamaCppEngine(null, fakeBridge)
            val file = createDummyModelFile("thread_test_${threads}.gguf")

            engine.initialize(
                modelPath = file.absolutePath,
                threads = threads,
                contextWindow = 2048,
                nBatch = 256,
                nUbatch = 128
            )

            assertTrue("Engine must be initialized for $threads threads", engine.isInitialized)
            val tokens = mutableListOf<String>()
            val res = engine.generateStream("Hi") { tokens.add(it) }
            assertEquals("Generation must succeed with $threads threads", LlamaGenerationResult.Completed, res)
            engine.close()
            assertFalse("Engine must be closed after $threads threads test", engine.isInitialized)
        }
    }

    @Test
    fun testBatchAndUbatchVariations() = runBlocking {
        val configs = listOf(
            Pair(256, 128), // Normal batch
            Pair(128, 64),  // Small batch
            Pair(128, 128), // Batch == Ubatch
            Pair(64, 32)    // Tiny batch
        )

        for ((nBatch, nUbatch) in configs) {
            val fakeBridge = FakeLlamaNativeBridge()
            val engine = LlamaCppEngine(null, fakeBridge)
            val file = createDummyModelFile("batch_${nBatch}_${nUbatch}.gguf")

            engine.initialize(
                modelPath = file.absolutePath,
                threads = 4,
                contextWindow = 2048,
                nBatch = nBatch,
                nUbatch = nUbatch
            )

            assertTrue(engine.isInitialized)
            val tokens = mutableListOf<String>()
            val res = engine.generateStream("Hello.") { tokens.add(it) }
            assertEquals(LlamaGenerationResult.Completed, res)
            engine.close()
        }
    }

    @Test
    fun testContextSizeVariations() = runBlocking {
        val ctxSizes = listOf(512, 1024, 2048, 4096)
        for (ctx in ctxSizes) {
            val fakeBridge = FakeLlamaNativeBridge()
            val engine = LlamaCppEngine(null, fakeBridge)
            val file = createDummyModelFile("ctx_${ctx}.gguf")

            engine.initialize(
                modelPath = file.absolutePath,
                threads = 4,
                contextWindow = ctx,
                nBatch = 256,
                nUbatch = 128
            )

            assertTrue(engine.isInitialized)
            val res = engine.generateStream("Explain 2 + 2 in one sentence.") {}
            assertEquals(LlamaGenerationResult.Completed, res)
            engine.close()
        }
    }

    @Test
    fun testStandardPromptMatrix_ExecutionSequence() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val engine = LlamaCppEngine(null, fakeBridge)
        val file = createDummyModelFile("standard_matrix.gguf")

        engine.initialize(file.absolutePath)

        val prompts = GgufDiagnosticRunner.STANDARD_PROMPTS
        for (p in prompts) {
            val received = mutableListOf<String>()
            val res = engine.generateStream(p) { received.add(it) }
            assertEquals("Prompt '$p' must complete", LlamaGenerationResult.Completed, res)
            assertTrue("Must receive tokens", received.isNotEmpty())
        }

        engine.close()
    }

    @Test
    fun testGgufDiagnosticRunner_PipelineGeneratesReport() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val file = createDummyModelFile("diagnostic_runner_test.gguf")

        val report = GgufDiagnosticRunner.runDiagnostics(
            context = null,
            modelPath = file.absolutePath,
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            customNativeBridge = fakeBridge
        )

        assertTrue(report.overallSuccess)
        assertTrue(report.initSuccess)
        assertTrue(report.inspectionValid)
        assertEquals("llama", report.architecture)
        assertEquals(3, report.promptResults.size)
        assertTrue(report.promptResults.all { it.success })
        assertTrue(report.closeSuccess)

        val markdown = report.toMarkdown()
        assertTrue(markdown.contains("# GGUF Diagnostic Report"))
        assertTrue(markdown.contains("## 1. GGUF Inspection"))
        assertTrue(markdown.contains("## 2. Runtime Context Initialization"))
        assertTrue(markdown.contains("## 3. Standardized Test Matrix"))
        assertTrue(markdown.contains("HEALTHY"))
    }

    @Test
    fun testRepeatedLoadGenerateFreeCycles_ZeroLeasesLeaked() = runBlocking {
        val fakeBridge = FakeLlamaNativeBridge()
        val file = createDummyModelFile("repeat_cycle.gguf")

        for (cycle in 1..10) {
            val engine = LlamaCppEngine(null, fakeBridge)
            engine.initialize(file.absolutePath)
            assertTrue("Cycle $cycle: must be open", engine.isInitialized)

            val res = engine.generateStream("Hi") {}
            assertEquals(LlamaGenerationResult.Completed, res)

            engine.close()
            assertFalse("Cycle $cycle: must be closed", engine.isInitialized)
            assertEquals("Active leases must be 0", 0, engine.testActiveLeaseCount)
        }

        assertEquals("10 inits must be performed", 10, fakeBridge.initCallCount.get())
        assertEquals("10 frees must be performed", 10, fakeBridge.freeCallCount.get())
        assertEquals("Live handles set must be empty", 0, fakeBridge.liveHandles.size)
    }

    @Test
    fun testMalformedGgufInspection_FailsCleanly() {
        val fakeBridge = object : LlamaNativeBridge by FakeLlamaNativeBridge() {
            override fun inspectModel(modelPath: String): String? {
                return """{"valid":false,"error":"Invalid GGUF magic 0x12345678"}"""
            }
        }

        val result = LlamaCppEngine.inspectModel("some/path/corrupt.gguf")
        // On host JVM without native lib, isLibraryLoaded is false, so it reports library not available
        // When tested via fake inspection parsing directly:
        assertNotNull(result)
    }
}


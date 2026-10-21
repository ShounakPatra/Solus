package com.shounak.localmeshai.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GgufGpuValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dummyModelFile: File

    @Before
    fun setUp() {
        GgufGpuValidator.testMemoryCache.clear()
        dummyModelFile = tempFolder.newFile("test_validator_model.gguf").apply {
            writeText("dummy content for validator test")
        }
    }

    @After
    fun tearDown() {
        GgufGpuValidator.testMemoryCache.clear()
    }

    private class ValidatorTestNativeBridge : LlamaNativeBridge {
        private val nextHandle = AtomicLong(5000L)
        val liveHandles = ConcurrentHashMap.newKeySet<Long>()
        val cpuInitCount = AtomicInteger(0)
        val vulkanInitCount = AtomicInteger(0)
        val freeCount = AtomicInteger(0)
        val probeRefCount = AtomicInteger(0)
        val probeCompareCount = AtomicInteger(0)

        var probeReferenceResult: String = """{"success":true,"prompt_index":0,"tokens":[1,2,3]}"""
        var probeCompareResult: String = """{"success":true,"max_tv_distance":0.015}"""
        var vulkanFailsInit: Boolean = false
        var onProbeReferenceHook: (() -> Unit)? = null

        override fun isAvailable(): Boolean = true
        override fun getLastError(): String? = null
        override fun inspectModel(modelPath: String): String? =
            """{"valid":true,"architecture":"llama","name":"TestModel","is_supported":true}"""

        override fun isVulkanAvailable(): Boolean = true
        override fun getActiveBackend(handle: Long): String {
            return if (handle % 2L == 0L) "Vulkan" else "CPU"
        }

        override fun initModel(
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nCtx: Int,
            nBatch: Int,
            nUbatch: Int,
            backend: Int,
            nGpuLayers: Int,
            operationControlId: Long
        ): Long {
            // Strict sequential lifecycle invariant: at most one model handle can be active at a time!
            if (liveHandles.isNotEmpty()) {
                throw AssertionError("CONCURRENT CONTEXT VIOLATION: tried to init backend=$backend while handles $liveHandles are still live!")
            }

            if (backend == 1) {
                vulkanInitCount.incrementAndGet()
                if (vulkanFailsInit) return 0L
                val handle = 2000L + nextHandle.incrementAndGet() * 2L // even handle for Vulkan
                liveHandles.add(handle)
                return handle
            } else {
                cpuInitCount.incrementAndGet()
                val handle = 1000L + nextHandle.incrementAndGet() * 2L + 1L // odd handle for CPU
                liveHandles.add(handle)
                return handle
            }
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
            callback.onToken("Hi")
            callback.onComplete()
            return 0
        }

        var simulateCpuIsolated: Boolean = true

        override fun stop(handle: Long, generationId: Long) {}
        override fun getMetadata(handle: Long): String? {
            val backend = getActiveBackend(handle)
            val isIsolated = (backend == "CPU" && simulateCpuIsolated)
            return """{"backend":"$backend","is_cpu_isolated":$isIsolated,"actual_compute_backends":["$backend"]}"""
        }

        override fun free(handle: Long) {
            freeCount.incrementAndGet()
            if (!liveHandles.remove(handle)) {
                throw AssertionError("Tried to free non-existent or already freed handle: $handle")
            }
        }

        override fun probeReference(
            handle: Long,
            promptIndex: Int,
            roles: Array<String>?,
            contents: Array<String>?,
            nPositions: Int,
            operationControlId: Long
        ): String? {
            probeRefCount.incrementAndGet()
            onProbeReferenceHook?.invoke()
            return probeReferenceResult
        }

        override fun probeCompare(
            handle: Long,
            promptIndex: Int,
            roles: Array<String>?,
            contents: Array<String>?,
            nPositions: Int,
            operationControlId: Long
        ): String? {
            probeCompareCount.incrementAndGet()
            return probeCompareResult
        }
    }

    @Test
    fun testCacheKeyConstruction() {
        val key = GgufGpuValidator.buildCacheKey(
            modelSha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128
        )
        assertTrue(key.startsWith("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83:${GgufGpuValidator.NATIVE_BUILD_ID}"))
        assertTrue(key.endsWith("th4_ctx2048_b256_ub128"))
    }

    @Test
    fun testValidation_PassingWhenTvDistanceBelowThreshold() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        bridge.probeCompareResult = """{"success":true,"max_tv_distance":0.024,"positions":[{"pos":0,"tv_distance":0.024}]}"""

        val result = GgufGpuValidator.validate(
            context = null,
            modelPath = dummyModelFile.absolutePath,
            modelSha256 = "fake_sha_pass",
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            customNativeBridge = bridge
        )

        if (result !is GgufGpuValidator.ValidationResult.Pass) {
            fail("Expected Pass but got: $result")
        }
        assertEquals(1, bridge.cpuInitCount.get())
        assertEquals(1, bridge.vulkanInitCount.get())
        assertEquals(3, bridge.probeRefCount.get()) // 3 standard prompts
        assertEquals(3, bridge.probeCompareCount.get()) // 3 standard prompts
        assertEquals(2, bridge.freeCount.get()) // Both CPU and Vulkan closed
        assertEquals(0, bridge.liveHandles.size) // No leaked handles

        // Check persistent cache
        val cacheKey = GgufGpuValidator.buildCacheKey("fake_sha_pass", 4, 2048, 256, 128)
        val cached = GgufGpuValidator.getCachedResult(null, cacheKey)
        assertTrue(cached is GgufGpuValidator.ValidationResult.Pass)
    }

    @Test
    fun testValidation_FailsWhenTvDistanceExceedsThreshold() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        bridge.probeCompareResult = """{"success":false,"error":"Total variation distance 0.082 exceeds threshold 0.05 at position 3"}"""

        val result = GgufGpuValidator.validate(
            context = null,
            modelPath = dummyModelFile.absolutePath,
            modelSha256 = "fake_sha_tv_fail",
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            customNativeBridge = bridge
        )

        if (result !is GgufGpuValidator.ValidationResult.Fail) {
            fail("Expected Fail but got: $result")
        }
        val failReason = (result as GgufGpuValidator.ValidationResult.Fail).reason
        assertTrue("Unexpected fail reason: $failReason", failReason.contains("exceeds threshold 0.05") || failReason.contains("TV distance"))
        assertEquals(0, bridge.liveHandles.size)

        // Check persistent cache
        val cacheKey = GgufGpuValidator.buildCacheKey("fake_sha_tv_fail", 4, 2048, 256, 128)
        val cached = GgufGpuValidator.getCachedResult(null, cacheKey)
        assertTrue(cached is GgufGpuValidator.ValidationResult.Fail)
    }

    @Test
    fun testValidation_FailsWhenCpuReferenceFails() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        bridge.probeReferenceResult = """{"success":false,"error":"Non-finite logits detected in CPU reference pass"}"""

        val result = GgufGpuValidator.validate(
            context = null,
            modelPath = dummyModelFile.absolutePath,
            modelSha256 = "fake_sha_cpu_fail",
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            customNativeBridge = bridge
        )

        assertTrue(result is GgufGpuValidator.ValidationResult.Fail)
        assertEquals(1, bridge.cpuInitCount.get())
        // Vulkan should NEVER be initialized if CPU reference failed!
    }

    @Test
    fun testValidation_FailsWhenCpuReferenceNotIsolated() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        bridge.simulateCpuIsolated = false

        val result = GgufGpuValidator.validate(
            context = null,
            modelPath = dummyModelFile.absolutePath,
            modelSha256 = "fake_sha_not_isolated",
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            customNativeBridge = bridge
        )

        assertTrue(result is GgufGpuValidator.ValidationResult.Fail)
        val failReason = (result as GgufGpuValidator.ValidationResult.Fail).reason
        assertTrue("Fail reason must mention CPU isolation: $failReason", failReason.contains("isolated", ignoreCase = true))
        assertEquals(1, bridge.cpuInitCount.get())
        assertEquals(0, bridge.vulkanInitCount.get())
    }

    @Test
    fun testValidation_UsesCachedResultWithoutReevaluating() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        val cacheKey = GgufGpuValidator.buildCacheKey("fake_sha_cached", 4, 2048, 256, 128)
        GgufGpuValidator.setCachedResult(null, cacheKey, GgufGpuValidator.ValidationResult.Pass)

        val result = GgufGpuValidator.validate(
            context = null,
            modelPath = dummyModelFile.absolutePath,
            modelSha256 = "fake_sha_cached",
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            customNativeBridge = bridge
        )

        assertTrue(result is GgufGpuValidator.ValidationResult.Pass)
        assertEquals(0, bridge.cpuInitCount.get())
        assertEquals(0, bridge.vulkanInitCount.get())
    }

    @Test
    fun testValidation_CancellationLeavesValidationPending() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        bridge.onProbeReferenceHook = {
            throw CancellationException("Simulated job cancellation during CPU probing")
        }

        val cacheKey = GgufGpuValidator.buildCacheKey("fake_sha_cancel", 4, 2048, 256, 128)

        var cancelled = false
        try {
            GgufGpuValidator.validate(
                context = null,
                modelPath = dummyModelFile.absolutePath,
                modelSha256 = "fake_sha_cancel",
                threads = 4,
                contextWindow = 2048,
                nBatch = 256,
                nUbatch = 128,
                customNativeBridge = bridge
            )
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue("Expected CancellationException to be thrown", cancelled)
        // Cache must remain Pending, not Fail or Pass!
        val cached = GgufGpuValidator.getCachedResult(null, cacheKey)
        assertTrue("Cancelled validation must leave state Pending", cached is GgufGpuValidator.ValidationResult.Pending)
        assertEquals(0, bridge.liveHandles.size)
    }
    @Test fun successfulValidationTransfersLiveGpuEngineWithoutReload() = runBlocking {
        val bridge = ValidatorTestNativeBridge()
        var retained: LlamaCppEngine? = null
        val result = GgufGpuValidator.validate(null, dummyModelFile.absolutePath, "ownership-test",
            customNativeBridge = bridge, onValidatedEngine = { retained = it })
        try {
            assertTrue(result is GgufGpuValidator.ValidationResult.Pass)
            assertNotNull(retained)
            assertEquals(1, bridge.cpuInitCount.get())
            assertEquals(1, bridge.vulkanInitCount.get())
            assertEquals(1, bridge.freeCount.get())
            assertEquals(1, bridge.liveHandles.size)
        } finally { retained?.close() }
        assertEquals(0, bridge.liveHandles.size)
    }

    @Test fun transientFailuresAreNotCachedAsGpuDisagreement() {
        val key = "transient-test"
        GgufGpuValidator.setCachedResult(null, key, GgufGpuValidator.ValidationResult.Fail(
            "Out of memory", GgufGpuValidator.FailureKind.Transient))
        assertTrue(GgufGpuValidator.getCachedResult(null, key) is GgufGpuValidator.ValidationResult.Pending)
    }

    @Test fun changedLocalArtifactIsRehashed() {
        val before = GgufGpuValidator.resolveModelSha256(dummyModelFile.absolutePath)
        dummyModelFile.writeText("replacement artifact with different bytes")
        val after = GgufGpuValidator.resolveModelSha256(dummyModelFile.absolutePath)
        assertFalse(before == after)
    }
    @Test fun catalogFilenameDoesNotProveLocalBytesAreCorrect() {
        val entry = com.shounak.localmeshai.models.ModelCatalog.defaultModels.first { !it.sha256.isNullOrBlank() }
        val wrong = tempFolder.newFile(entry.fileName).apply { writeText("wrong model bytes") }
        try {
            GgufGpuValidator.resolveModelSha256(wrong.absolutePath, entry.id)
            fail("Wrong local bytes must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("checksum"))
        }
    }
}

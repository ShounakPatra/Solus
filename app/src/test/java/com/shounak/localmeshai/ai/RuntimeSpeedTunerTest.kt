package com.shounak.localmeshai.ai

import com.shounak.localmeshai.utils.InferenceBackend
import org.junit.Assert.*
import org.junit.Test

class RuntimeSpeedTunerTest {
    @Test fun tuningUsesTheLoadersContextAndKeepsThreadSelectionsPerModel() {
        assertEquals(8192, RuntimeSpeedTuner.liteRtContextTokens(null, 32768))
        assertEquals(2048, RuntimeSpeedTuner.liteRtContextTokens(2048, 32768))
        assertEquals(4096, RuntimeSpeedTuner.liteRtContextTokens(null, null))
        assertNotEquals(RuntimeExecutionPolicy.threadPreferenceKey("a"), RuntimeExecutionPolicy.threadPreferenceKey("b"))
    }
    @Test fun unsupportedTuningDoesNotStopOrEvictTheActiveChatRuntime() = kotlinx.coroutines.runBlocking {
        val stops = java.util.concurrent.atomic.AtomicInteger()
        val releases = java.util.concurrent.atomic.AtomicInteger()
        val owner = com.shounak.localmeshai.utils.ModelRuntimeOwner.Chat
        val coordinator = com.shounak.localmeshai.utils.ModelRuntimeCoordinator
        val token = coordinator.register(owner, requestStop = { stops.incrementAndGet() }, release = { releases.incrementAndGet(); ShutdownResult.Success })
        try {
            coordinator.withRuntime(owner) { }
            val model = com.shounak.localmeshai.models.ModelInfo("onnx", "ONNX", "1 GB",
                com.shounak.localmeshai.models.ModelStatus.Available, com.shounak.localmeshai.models.ModelType.Text,
                "model.onnx", description = "", backend = "ONNX", deviceTarget = "Test", localPath = "missing.onnx")
            try {
                RuntimeSpeedTuner.run(android.content.ContextWrapper(null), model) { }
                fail("Unsupported tuning was admitted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, stops.get())
            assertEquals(0, releases.get())
            assertTrue(coordinator.isActive(owner))
        } finally { coordinator.unregister(owner, token) }
    }
    @Test fun selectsMeasuredMedianRatherThanTheLargestThreadCountOrOneLuckySample() {
        assertEquals(4, RuntimeSpeedTuner.fastest(mapOf(2 to listOf(5.0, 150.0, 160.0),
            4 to listOf(80.0, 82.0, 85.0), 6 to listOf(100.0, 105.0, 110.0))))
        assertEquals(2, RuntimeSpeedTuner.fastest(mapOf(2 to listOf(100.0), 6 to listOf(Double.NaN))))
        assertNull(RuntimeSpeedTuner.fastest(mapOf(2 to listOf(0.0), 4 to emptyList())))
    }
    @Test fun autoGpuNeedsBothFasterTimingAndMatchingNonemptyProbeOutputs() {
        val cpu = listOf("Hello", "4", "Blue")
        assertTrue(RuntimeSpeedTuner.qualifiesGpu(100.0, 80.0, cpu, cpu))
        assertFalse(RuntimeSpeedTuner.qualifiesGpu(100.0, 95.0, cpu, cpu))
        assertFalse(RuntimeSpeedTuner.qualifiesGpu(100.0, 80.0, cpu, listOf("Hello", "5", "Blue")))
        assertFalse(RuntimeSpeedTuner.qualifiesGpu(100.0, 80.0, emptyList(), emptyList()))
        assertFalse(RuntimeSpeedTuner.qualifiesGpu(100.0, Double.NaN, cpu, cpu))
    }
    @Test fun gpuFailureVetoesEveryReceiptForTheFileRegardlessOfContextOrThreads() {
        val folder = java.nio.file.Files.createTempDirectory("speed-veto").toFile()
        try {
            val model = java.io.File(folder, "m.litertlm").apply { writeText("model") }
            val prefs = com.shounak.localmeshai.features.FeatureTestPreferences()
            val context = object : android.content.ContextWrapper(null) {
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = prefs
                override fun getApplicationContext(): android.content.Context = this
                override fun getSystemService(name: String): Any? = null
            }
            val threads = RuntimeExecutionPolicy.resolve(context, "m").cpuThreads
            // Tuned GPU receipts at both the requested and a fallback context, and for other thread counts.
            for (tokens in listOf(2048, 4096)) for (t in setOf(threads, 2, 4, 6)) {
                prefs.edit().putString("litert_${RuntimeSpeedTuner.key("m", model, tokens, t)}", "GPU").commit()
            }
            assertEquals(InferenceBackend.LITERT_GPU, RuntimeSpeedTuner.liteRtBackend(context, "m", model.path, 4096))
            RuntimeSpeedTuner.recordGpuFailure(context, "m", model.path)
            assertEquals(InferenceBackend.LITERT_CPU, RuntimeSpeedTuner.liteRtBackend(context, "m", model.path, 4096))
            assertEquals(InferenceBackend.LITERT_CPU, RuntimeSpeedTuner.liteRtBackend(context, "m", model.path, 2048))
        } finally { folder.deleteRecursively() }
    }
    @Test fun backendSelectionNeedsAGpuReceiptAndNoVetoOrProbeCrash() {
        assertEquals(InferenceBackend.LITERT_GPU, RuntimeSpeedTuner.selectLiteRtBackend("GPU", gpuVetoed = false, gpuProbeBlocked = false))
        assertEquals(InferenceBackend.LITERT_CPU, RuntimeSpeedTuner.selectLiteRtBackend("GPU", gpuVetoed = true, gpuProbeBlocked = false))
        assertEquals(InferenceBackend.LITERT_CPU, RuntimeSpeedTuner.selectLiteRtBackend("GPU", gpuVetoed = false, gpuProbeBlocked = true))
        assertEquals(InferenceBackend.LITERT_CPU, RuntimeSpeedTuner.selectLiteRtBackend("CPU", gpuVetoed = false, gpuProbeBlocked = false))
        assertEquals(InferenceBackend.LITERT_CPU, RuntimeSpeedTuner.selectLiteRtBackend(null, gpuVetoed = false, gpuProbeBlocked = false))
    }
}

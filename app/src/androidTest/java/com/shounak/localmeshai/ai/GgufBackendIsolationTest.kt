package com.shounak.localmeshai.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GgufBackendIsolationTest {

    companion object {
        const val DEFAULT_FIXTURE_FILENAME = "smollm2-360m-instruct-q4_k_m.gguf"
        const val DEFAULT_FIXTURE_SHA256 = "c04439c27702fa1fbddca2ecbb30c6aebdafaadfd07ecaa26db8aa7899c323fc"
    }

    @Test
    fun testNativeLibraryLoadedAndCpuIsolationInvariant() {
        assertTrue(
            "Native libsolus_llama.so must be loaded in the Android test runtime",
            LlamaCppEngine.isLibraryLoaded
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelsDir = File(filesDir, "models")
        val testModel = File(modelsDir, DEFAULT_FIXTURE_FILENAME).takeIf { it.exists() && it.length() > 1024 }
            ?: modelsDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf") && it.length() > 1024 }

        assumeTrue(
            "GGUF model fixture not available in $modelsDir. Skipping optional on-device model load test.",
            testModel != null && testModel.exists()
        )
        val validModel = testModel!!

        val engine = LlamaCppEngine(context = context)
        try {
            engine.initialize(
                modelPath = validModel.absolutePath,
                threads = 4,
                prefillThreads = 4,
                contextWindow = 512,
                nBatch = 128,
                nUbatch = 64,
                backend = LlamaBackend.CPU,
                nGpuLayers = 0
            )

            val meta = engine.getMetadata()
            assertNotNull("Model metadata must be populated", meta)

            // Verify strict CPU isolation invariant
            assertTrue("isCpuIsolated must be true for CPU backend", meta?.isCpuIsolated == true)
            assertEquals("gpuComputeBufferBytes must be 0 for isolated CPU", 0L, meta?.gpuComputeBufferBytes ?: -1L)
            assertFalse("effectiveOffloadKqv must be false for isolated CPU", meta?.effectiveOffloadKqv == true)

            val backends: List<String> = meta?.actualComputeBackends ?: emptyList()
            for (backendName in backends) {
                val lower = backendName.lowercase()
                assertFalse(
                    "CPU execution context must not contain Vulkan compute backend (found: $backendName)",
                    lower.contains("vulkan") || lower.contains("gpu")
                )
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun testBackendPolicyAutoDefaultsToCpuWithoutReceipts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dummySha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val decision = GgufBackendPolicy.selectBackend(
            context = context,
            modelSha256 = dummySha,
            requestedBackend = null
        )

        assertEquals("Auto policy must choose CPU when unverified", LlamaBackend.CPU, decision.selectedBackend)
        assertTrue("Decision must be marked as Auto", decision.isAuto)
    }
}

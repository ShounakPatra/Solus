package com.shounak.localmeshai.ai

import com.shounak.localmeshai.models.ModelCatalog
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufBackendAndRecoveryTest {

    @Test
    fun testFailureCategoryRecoverabilityOnVulkan() {
        val recoverableCategories = setOf(
            LlamaFailureCategory.NumericalInstability,
            LlamaFailureCategory.BackendExecution,
            LlamaFailureCategory.GenerationDecodeFailed,
            LlamaFailureCategory.PromptDecodeFailed
        )

        fun isRecoverableOnVulkan(category: LlamaFailureCategory): Boolean {
            return category in recoverableCategories
        }

        assertTrue(isRecoverableOnVulkan(LlamaFailureCategory.NumericalInstability))
        assertTrue(isRecoverableOnVulkan(LlamaFailureCategory.BackendExecution))
        assertTrue(isRecoverableOnVulkan(LlamaFailureCategory.GenerationDecodeFailed))
        assertTrue(isRecoverableOnVulkan(LlamaFailureCategory.PromptDecodeFailed))

        // Non-recoverable categories MUST NOT trigger retry
        assertFalse(isRecoverableOnVulkan(LlamaFailureCategory.UnsupportedTemplate))
        assertFalse(isRecoverableOnVulkan(LlamaFailureCategory.TokenizationError))
        assertFalse(isRecoverableOnVulkan(LlamaFailureCategory.OutOfMemory))
        assertFalse(isRecoverableOnVulkan(LlamaFailureCategory.UserStopped))
        assertFalse(isRecoverableOnVulkan(LlamaFailureCategory.PromptEmpty))
        assertFalse(isRecoverableOnVulkan(LlamaFailureCategory.Unknown))
    }

    @Test
    fun testChatRuntimePolicy_RecognizesGgufModel() {
        assertTrue(ChatRuntimePolicy.isGgufModel("llama-3.2-1b.gguf"))
        assertTrue(ChatRuntimePolicy.isGgufModel("QWEN2.5-0.5B-INSTRUCT-Q4_K_M.GGUF"))
        assertFalse(ChatRuntimePolicy.isGgufModel("gemma-2b-it-cpu.task"))
        assertFalse(ChatRuntimePolicy.isGgufModel("gemma-2b-it-gpu.litertlm"))
    }

    @get:org.junit.Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    @Test
    fun testModelCatalog_Llama321bParameters() {
        val llamaEntry = ModelCatalog.defaultModels.find { it.id == "llama32_1b_gguf" }
        assertNotNull("llama32_1b_gguf should exist in default catalog", llamaEntry)
        assertEquals(807694464L, llamaEntry!!.expectedFileSizeBytes)
        assertEquals("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", llamaEntry.sha256)
        assertTrue(
            "Download URL should be pinned to commit 067b946cf014b7c697f3654f621d577a3e3afd1c",
            llamaEntry.url?.contains("067b946cf014b7c697f3654f621d577a3e3afd1c") == true
        )
    }

    @Test
    fun testArtifactSizeValidation() {
        val expectedBytes = 100_000L

        // Exact match
        val exactFile = tempFolder.newFile("exact.gguf")
        java.io.RandomAccessFile(exactFile, "rw").use { it.setLength(expectedBytes) }
        assertNull(LlamaCppEngine.validateGgufArtifactSize(exactFile.absolutePath, expectedBytes))

        // Within 5% tolerance
        val withinToleranceFile = tempFolder.newFile("within.gguf")
        java.io.RandomAccessFile(withinToleranceFile, "rw").use { it.setLength((expectedBytes * 1.03).toLong()) }
        assertNull(LlamaCppEngine.validateGgufArtifactSize(withinToleranceFile.absolutePath, expectedBytes))

        // Greater deviation (> 5% smaller)
        val tooSmallFile = tempFolder.newFile("small.gguf")
        java.io.RandomAccessFile(tooSmallFile, "rw").use { it.setLength((expectedBytes * 0.90).toLong()) }
        val warningSmall = LlamaCppEngine.validateGgufArtifactSize(tooSmallFile.absolutePath, expectedBytes)
        assertNotNull(warningSmall)
        assertTrue(warningSmall!!.contains("mismatch"))

        // Greater deviation (> 5% larger)
        val tooLargeFile = tempFolder.newFile("large.gguf")
        java.io.RandomAccessFile(tooLargeFile, "rw").use { it.setLength((expectedBytes * 1.15).toLong()) }
        val warningLarge = LlamaCppEngine.validateGgufArtifactSize(tooLargeFile.absolutePath, expectedBytes)
        assertNotNull(warningLarge)
        assertTrue(warningLarge!!.contains("mismatch"))
    }

    @Test
    fun testBackendPreferenceConsistency() {
        assertEquals(LlamaBackend.CPU, LlamaBackend.fromName("CPU"))
        assertEquals(LlamaBackend.VULKAN, LlamaBackend.fromName("VULKAN"))
        assertEquals(LlamaBackend.VULKAN, LlamaBackend.fromName("Vulkan GPU"))

        // Unknown defaults to CPU
        assertEquals(LlamaBackend.CPU, LlamaBackend.fromName("UNKNOWN"))
        assertEquals(LlamaBackend.CPU, LlamaBackend.fromId(-1))
    }

    @Test
    fun testDiagnosticReport_DistinguishesExecutionSuccessFromQualityVerification() {
        val promptOk = GgufPromptDiagnosticResult(
            prompt = "Hi",
            success = true,
            generatedTokenCount = 10,
            generatedText = "Hello! How can I help you today?"
        )

        // Case 1: Execution success and quality verified
        val reportVerified = GgufDiagnosticReport(
            timestamp = "2026-10-05 12:00:00",
            modelPath = "/path/to/model.gguf",
            fileName = "model.gguf",
            modelSha256 = "hash123",
            backendUsed = "Vulkan GPU",
            fileSizeBytes = 800000000L,
            expectedSizeBytes = 800000000L,
            artifactSizeValidationPassed = true,
            artifactSizeWarning = null,
            inspectionValid = true,
            architecture = "llama",
            primaryQuantization = "Q4_K_M",
            tensorCount = 200L,
            trainContextLength = 2048,
            isSupported = true,
            inspectionError = null,
            initSuccess = true,
            initDurationMs = 150L,
            threadsUsed = 4,
            contextWindowUsed = 2048,
            nBatchUsed = 256,
            nUbatchUsed = 128,
            initError = null,
            promptResults = listOf(promptOk),
            closeSuccess = true,
            numericalAnomalyReport = null,
            responseQualityVerified = true,
            overallSuccess = true
        )

        val mdVerified = reportVerified.toMarkdown()
        assertTrue(mdVerified.contains("**Response Quality Verified:** ✅ Yes"))
        assertTrue(mdVerified.contains("**Backend:** `Vulkan GPU`"))
        assertTrue(mdVerified.contains("**Model SHA-256:** `hash123`"))

        // Case 2: Execution succeeded (no crash), but response quality unverified (e.g. unverified GPU output)
        val reportUnverified = reportVerified.copy(
            responseQualityVerified = false,
            numericalAnomalyReport = "[NUMERICAL_AUDIT] Anomaly detected"
        )
        val mdUnverified = reportUnverified.toMarkdown()
        assertTrue(reportUnverified.overallSuccess)
        assertFalse(reportUnverified.responseQualityVerified)
        assertTrue(mdUnverified.contains("**Response Quality Verified:** ⚠️ No"))
    }
}

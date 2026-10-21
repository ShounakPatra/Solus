package com.shounak.localmeshai

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.memory.MemoryCategory
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.PersistentMemoryStore
import com.shounak.localmeshai.models.ModelCatalog
import com.shounak.localmeshai.utils.DocumentTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ReleaseOptimizationSmokeTest {

    private val targetContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun testNativeLoadingAndVulkanDiscovery() {
        // Verify native bridge loading on Android device
        assertTrue(
            "Native libsolus_llama.so must be loaded in the Android test environment",
            LlamaCppEngine.isLibraryLoaded
        )

        // Verify Vulkan discovery executes without linkage or reflection errors
        val isVulkanAvail = LlamaCppEngine.isVulkanAvailable()
        val vulkanInfo = LlamaCppEngine.getVulkanDeviceInfo()
        if (isVulkanAvail) {
            assertNotNull("VulkanDeviceInfo should not be null when Vulkan is available", vulkanInfo)
            assertTrue("Device count should be > 0", (vulkanInfo?.deviceCount ?: 0) > 0)
        }
    }

    @Test
    fun testPdfExtractionOrdinaryText() {
        val file = copyAssetToTestFile("pdf/ordinary_text.pdf")
        val extracted = DocumentTextExtractor.extract(
            context = targetContext,
            uri = Uri.fromFile(file),
            fileName = file.name,
            mimeType = "application/pdf"
        )
        assertTrue(
            "Extracted text should contain the known expected sentence. Got: $extracted",
            extracted.contains("The quick brown fox jumps over the lazy dog", ignoreCase = true)
        )
    }

    @Test
    fun testPdfExtractionMultilingual() {
        val file = copyAssetToTestFile("pdf/multilingual_embedded.pdf")
        val extracted = DocumentTextExtractor.extract(
            context = targetContext,
            uri = Uri.fromFile(file),
            fileName = file.name,
            mimeType = "application/pdf"
        )
        assertTrue(
            "Extracted text should contain multilingual greetings. Got: $extracted",
            extracted.contains("Hello world", ignoreCase = true) &&
                extracted.contains("Hola mundo", ignoreCase = true)
        )
    }

    @Test
    fun testPdfExtractionEncryptedEmptyPassword() {
        val file = copyAssetToTestFile("pdf/encrypted_empty_password.pdf")
        val extracted = DocumentTextExtractor.extract(
            context = targetContext,
            uri = Uri.fromFile(file),
            fileName = file.name,
            mimeType = "application/pdf"
        )
        assertTrue(
            "Extracted text should read encrypted PDF with empty password. Got: $extracted",
            extracted.contains("This document is encrypted with an empty user password", ignoreCase = true)
        )
    }

    @Test
    fun testPdfExtractionPasswordProtected() {
        val file = copyAssetToTestFile("pdf/password_protected.pdf")
        val extracted = DocumentTextExtractor.extract(
            context = targetContext,
            uri = Uri.fromFile(file),
            fileName = file.name,
            mimeType = "application/pdf"
        )
        assertTrue(
            "Password-protected PDF should surface a readable error or failure message. Got: $extracted",
            extracted.contains("Could not extract PDF text", ignoreCase = true) ||
                extracted.contains("password", ignoreCase = true) ||
                extracted.contains("encrypted", ignoreCase = true)
        )
    }

    @Test
    fun testPdfExtractionImageOnly() {
        val file = copyAssetToTestFile("pdf/image_only.pdf")
        val extracted = DocumentTextExtractor.extract(
            context = targetContext,
            uri = Uri.fromFile(file),
            fileName = file.name,
            mimeType = "application/pdf"
        )
        assertTrue(
            "Image-only PDF should return readable fallback message. Got: $extracted",
            extracted.contains("No readable text could be extracted", ignoreCase = true) ||
                extracted.contains("OCR is required", ignoreCase = true)
        )
    }

    @Test
    fun testPdfExtractionMalformed() {
        val file = copyAssetToTestFile("pdf/malformed.pdf")
        val extracted = DocumentTextExtractor.extract(
            context = targetContext,
            uri = Uri.fromFile(file),
            fileName = file.name,
            mimeType = "application/pdf"
        )
        assertTrue(
            "Malformed PDF should return error message. Got: $extracted",
            extracted.contains("Could not extract PDF text", ignoreCase = true)
        )
    }

    @Test
    fun testPersistenceRoundTrip() {
        val testStorageDir = File(targetContext.cacheDir, "test_persistence_${System.currentTimeMillis()}")
        testStorageDir.mkdirs()

        try {
            val store1 = PersistentMemoryStore(
                storageDir = testStorageDir,
                storageFileName = "test_memory.json"
            )

            val entry = MemoryEntry(
                id = "smoke_test_entry_1",
                content = "User prefers concise markdown explanations",
                category = MemoryCategory.PREFERENCE,
                isEnabled = true
            )

            assertTrue("Adding memory entry should succeed", store1.add(entry))
            store1.persist()

            // Verify persistence round trip by reading back with an independent store instance
            val store2 = PersistentMemoryStore(
                storageDir = testStorageDir,
                storageFileName = "test_memory.json"
            )

            assertEquals("Store should contain 1 persisted entry", 1, store2.count())
            val loaded = store2.getById("smoke_test_entry_1")
            assertNotNull("Loaded entry must not be null", loaded)
            assertEquals("User prefers concise markdown explanations", loaded?.content)
            assertEquals(MemoryCategory.PREFERENCE, loaded?.category)
            assertTrue(loaded?.isEnabled == true)
        } finally {
            testStorageDir.deleteRecursively()
        }
    }

    @Test
    fun testModelCatalogIntegrity() {
        val models = ModelCatalog.defaultModels
        assertFalse("Model catalog should not be empty", models.isEmpty())
        assertTrue("All catalog models should have non-empty IDs", models.all { it.id.isNotBlank() })
        assertTrue("All catalog models should have non-empty fileNames", models.all { it.fileName.isNotBlank() })
    }

    private fun copyAssetToTestFile(assetPath: String): File {
        val outFile = File(targetContext.cacheDir, assetPath.substringAfterLast('/'))
        // Try testContext first (assets bundled with instrumentation APK)
        val stream = runCatching { testContext.assets.open(assetPath) }
            .getOrElse { targetContext.assets.open(assetPath) }
        stream.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}

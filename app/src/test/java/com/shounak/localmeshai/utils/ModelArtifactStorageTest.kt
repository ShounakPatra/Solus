package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelArtifactStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageRoot: File
    private lateinit var storage: ModelArtifactStorage

    @Before
    fun setup() {
        storageRoot = tempFolder.newFolder("models_root")
        storage = ModelArtifactStorage(storageRoot)
    }

    @Test
    fun twoUrlsSharingFilename_haveIsolatedStorage() {
        val modelId1 = "custom_text_model1"
        val modelId2 = "custom_text_model2"
        val sharedFileName = "model.gguf"

        val staging1 = storage.getStagingFile(modelId1, sharedFileName)
        val staging2 = storage.getStagingFile(modelId2, sharedFileName)

        assertNotEquals(staging1.absolutePath, staging2.absolutePath)

        staging1.writeText("Content for model 1")
        staging2.writeText("Content for model 2")

        val published1 = storage.publishArtifact(modelId1, staging1, sharedFileName, "https://url1/model.gguf")
        val published2 = storage.publishArtifact(modelId2, staging2, sharedFileName, "https://url2/model.gguf")

        assertTrue(published1.exists())
        assertTrue(published2.exists())
        assertEquals("Content for model 1", published1.readText())
        assertEquals("Content for model 2", published2.readText())

        // Independent deletion
        storage.deleteModel(modelId1)
        assertFalse(published1.exists())
        assertNull(storage.getPublishedArtifact(modelId1))

        assertTrue(published2.exists())
        assertEquals("Content for model 2", storage.getPublishedArtifact(modelId2)?.readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodedTraversal_isRejected() {
        ModelArtifactStorage.sanitizeFileName("..%2f..%2fetc%2fpasswd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawPathTraversal_isRejected() {
        ModelArtifactStorage.sanitizeFileName("../model.gguf")
    }

    @Test(expected = IllegalArgumentException::class)
    fun controlCharactersInFilename_areRejected() {
        ModelArtifactStorage.sanitizeFileName("model\u0000.gguf")
    }

    @Test
    fun inferFileName_decodesOnceSafely() {
        val safe = ModelArtifactStorage.inferFileName("https://huggingface.co/repo/resolve/main/my%20model.gguf")
        assertEquals("my model.gguf", safe)
    }

    @Test
    fun manifestContainsSizeAndChecksum() {
        val modelId = "model_checksum_test"
        val fileName = "test.bin"
        val staging = storage.getStagingFile(modelId, fileName)
        staging.writeText("Checksum test payload")

        val sha256 = ModelArtifactStorage.calculateSha256(staging)

        storage.publishArtifact(
            modelId = modelId,
            stagedSource = staging,
            relativeTargetName = fileName,
            sourceIdentity = "https://example.com/test.bin",
            expectedSha256 = sha256
        )

        val manifest = storage.getManifest(modelId)
        assertNotNull(manifest)
        assertEquals(modelId, manifest?.modelId)
        assertTrue(manifest?.relativePath?.endsWith(fileName) == true)
        assertEquals(sha256, manifest?.checksumSha256)
        assertEquals("Checksum test payload".toByteArray().size.toLong(), manifest?.sizeBytes)
    }

    @Test
    fun checksumMismatch_abortsPublicationAndDeletesStaging() {
        val modelId = "model_checksum_fail"
        val fileName = "corrupt.bin"
        val staging = storage.getStagingFile(modelId, fileName)
        staging.writeText("Corrupted content")

        try {
            storage.publishArtifact(
                modelId = modelId,
                stagedSource = staging,
                relativeTargetName = fileName,
                sourceIdentity = "https://example.com/corrupt.bin",
                expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
            )
            fail("Expected checksum mismatch exception")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("checksum", ignoreCase = true) == true)
        }

        assertNull(storage.getPublishedArtifact(modelId))
        assertFalse(staging.exists())
    }

    @Test
    fun emptyDirectoryReturnsNullArtifact() {
        val modelDir = File(storageRoot, "empty_model_dir")
        modelDir.mkdirs()
        assertNull(storage.getPublishedArtifact("empty_model_dir"))
    }

    @Test
    fun stagingOnlyDirectoryReturnsNullArtifact() {
        val modelId = "staging_only_model"
        val stagingFile = storage.getStagingFile(modelId, "model.gguf")
        stagingFile.writeText("halfway downloaded bytes")
        assertNull(storage.getPublishedArtifact(modelId))
    }

    @Test
    fun zipWithArchiveShaPublishesWithoutHashingDirectoryAndResolvesRunnable() {
        val modelId = "zip_model_test"
        val stagingDir = storage.getStagingDir(modelId).resolve("extracted_bundle")
        stagingDir.mkdirs()
        val taskFile = File(stagingDir, "model.task")
        taskFile.writeBytes(ByteArray(2048) { 1 })

        val published = storage.publishArtifact(
            modelId = modelId,
            stagedSource = stagingDir,
            relativeTargetName = "extracted_bundle",
            sourceIdentity = "https://example.com/bundle.zip",
            expectedSha256 = null,
            archiveSha256 = "archive_sha_12345",
            isTextModel = true
        )

        assertTrue(published.exists())
        assertEquals("model.task", published.name)

        val manifest = storage.getManifest(modelId)
        assertNotNull(manifest)
        assertEquals("archive_sha_12345", manifest?.archiveChecksumSha256)
        assertNull(manifest?.checksumSha256)
        assertEquals("ZipDirectory", manifest?.packageType)

        val retrieved = storage.getPublishedArtifact(modelId)
        assertNotNull(retrieved)
        assertEquals("model.task", retrieved?.name)
    }

    @Test
    fun unsupportedZipBundle_rejectedWithClearError() {
        val modelId = "unsupported_zip"
        val stagingDir = storage.getStagingDir(modelId).resolve("unsupported_bundle")
        stagingDir.mkdirs()
        File(stagingDir, "readme.txt").writeText("Not a model")

        try {
            storage.publishArtifact(
                modelId = modelId,
                stagedSource = stagingDir,
                relativeTargetName = "bundle",
                sourceIdentity = "https://example.com/bundle.zip",
                isTextModel = true
            )
            fail("Expected exception for unsupported model bundle")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("supported runnable model", ignoreCase = true) == true)
        }
        assertNull(storage.getPublishedArtifact(modelId))
    }

    @Test
    fun ambiguousZipBundle_rejectedWithClearError() {
        val modelId = "ambiguous_zip"
        val stagingDir = storage.getStagingDir(modelId).resolve("ambiguous_bundle")
        stagingDir.mkdirs()
        File(stagingDir, "model1.gguf").writeBytes(ByteArray(2048))
        File(stagingDir, "model2.gguf").writeBytes(ByteArray(2048))

        try {
            storage.publishArtifact(
                modelId = modelId,
                stagedSource = stagingDir,
                relativeTargetName = "bundle",
                sourceIdentity = "https://example.com/bundle.zip",
                isTextModel = true
            )
            fail("Expected exception for ambiguous model bundle")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("ambiguous", ignoreCase = true) == true)
        }
        assertNull(storage.getPublishedArtifact(modelId))
    }

    @Test
    fun cancelledPublicationPreservesPreviousGeneration() {
        val modelId = "cancellation_preservation"
        val staging1 = storage.getStagingFile(modelId, "v1.bin")
        staging1.writeBytes(ByteArray(2048) { 1 })
        val pub1 = storage.publishArtifact(modelId, staging1, "v1.bin", "https://example.com/v1")
        assertEquals(pub1.absolutePath, storage.getPublishedArtifact(modelId)?.absolutePath)

        // Attempt v2 but cancel
        val staging2 = storage.getStagingFile(modelId, "v2.bin")
        staging2.writeBytes(ByteArray(2048) { 2 })
        try {
            storage.publishArtifact(
                modelId = modelId,
                stagedSource = staging2,
                relativeTargetName = "v2.bin",
                sourceIdentity = "https://example.com/v2",
                isCancelled = { true }
            )
            fail("Expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Expected
        }

        // v1 remains published and intact!
        val currentPub = storage.getPublishedArtifact(modelId)
        assertNotNull(currentPub)
        assertEquals(pub1.absolutePath, currentPub?.absolutePath)
    }

    @Test
    fun invalidManifestPathReturnsNull() {
        val modelId = "invalid_manifest_model"
        val file = storage.getManifestFile(modelId)
        file.parentFile?.mkdirs()
        file.writeText("""
            {
                "modelId": "$modelId",
                "relativePath": "../../../etc/passwd",
                "sourceIdentity": "https://example.com",
                "sizeBytes": 12345
            }
        """.trimIndent())

        assertNull(storage.getPublishedArtifact(modelId))
    }
}

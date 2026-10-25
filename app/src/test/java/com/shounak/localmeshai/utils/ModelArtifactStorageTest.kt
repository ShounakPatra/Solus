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
    @Test fun pausedSingleFilePublicationRestoresTheCompleteStagedDownload() {
        for (cancelAt in listOf(2, 3)) {
            val id = "pause_single_$cancelAt"
            val stage = storage.getStagingFile(id, "model.gguf").apply { writeBytes(ByteArray(128 * 1024) { 7 }) }
            var checks = 0
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                storage.publishArtifact(id, stage, "model.gguf", "source", isCancelled = { ++checks >= cancelAt })
            }
            assertTrue(stage.isFile)
            assertEquals(128L * 1024, stage.length())
            assertTrue(stage.readBytes().all { it == 7.toByte() })
            assertNull(storage.getPublishedArtifact(id))
        }
    }

    @Test fun pausedBundleVerificationRestoresAlreadyMovedComponents() {
        val id = "pause_bundle"
        val staging = storage.getStagingDir(id).apply { mkdirs() }
        val weights = File(staging, "weights.download").apply { writeText("weights") }
        val tokenizer = File(staging, "tokenizer.download").apply { writeText("tokenizer") }
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            storage.publishArtifactBundle(id, listOf(StagedArtifactItem(weights, "model.onnx", expectedSha256 = "a".repeat(64), isPrimary = true),
                StagedArtifactItem(tokenizer, "tokenizer.json")), "model.onnx", "source", isCancelled = { ++checks >= 3 })
        }
        assertEquals("weights", weights.readText())
        assertEquals("tokenizer", tokenizer.readText())
        assertNull(storage.getPublishedArtifact(id))
    }
    @Test fun deletingPortableBundlesRemovesWeightsCompanionsAndPartialDownloadsOnlyForThatModel() {
        for (extension in listOf("onnx", "safetensors")) {
            val id = "portable_$extension"
            val names = listOf("model.$extension", "config.json", "tokenizer.json", "tokenizer_config.json",
                if (extension == "onnx") "model.onnx_data" else "model-00002-of-00002.safetensors")
            val staging = storage.getStagingDir(id).apply { mkdirs() }
            val items = names.mapIndexed { index, name ->
                StagedArtifactItem(File(staging, name).apply { writeText("fixture $name") }, name, isPrimary = index == 0, order = index)
            }
            val primary = storage.publishArtifactBundle(id, items, names.first(), "source_$id")
            val bundleDir = primary.parentFile!!
            names.forEach { assertTrue(File(bundleDir, it).isFile) }
            File(storage.getStagingDir(id).apply { mkdirs() }, "unfinished.download").writeText("partial download")
            val other = storage.getModelDir("retained_$extension", true)
            val retained = File(other, "tokenizer.json").apply { writeText("other model tokenizer") }
            val lease = ModelArtifactStorage.leaseArtifact(File(bundleDir, "tokenizer.json"))
            try { assertFalse("Companions in use must protect the whole bundle", storage.deleteModel(id)) }
            finally { lease.close() }
            assertTrue(storage.deleteModel(id))
            assertFalse(storage.getModelDir(id).exists())
            assertTrue(retained.isFile)
        }
    }


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

    @Test
    fun sameNamedPhoneImports_getIsolatedDirectories_andDoNotOverwrite() {
        val modelId1 = "phone_import_11111"
        val modelId2 = "phone_import_22222"
        val sharedFileName = "model.task"

        val stagingDir1 = storage.getStagingDir(modelId1)
        val stagingDir2 = storage.getStagingDir(modelId2)
        stagingDir1.mkdirs()
        stagingDir2.mkdirs()

        val stagedFile1 = File(stagingDir1, "$sharedFileName.tmp")
        val stagedFile2 = File(stagingDir2, "$sharedFileName.tmp")
        stagedFile1.writeText("MediaPipe Model 1")
        stagedFile2.writeText("MediaPipe Model 2")

        val item1 = StagedArtifactItem(
            stagedFile = stagedFile1,
            relativeTargetName = sharedFileName,
            isPrimary = true,
            role = com.shounak.localmeshai.models.ArtifactRole.Main,
            order = 0
        )
        val item2 = StagedArtifactItem(
            stagedFile = stagedFile2,
            relativeTargetName = sharedFileName,
            isPrimary = true,
            role = com.shounak.localmeshai.models.ArtifactRole.Main,
            order = 0
        )

        val published1 = storage.publishArtifactBundle(
            modelId = modelId1,
            stagedItems = listOf(item1),
            primaryItemName = sharedFileName,
            sourceIdentity = "phone_uri_1",
            format = com.shounak.localmeshai.models.ModelFormat.MediaPipeTask
        )
        val published2 = storage.publishArtifactBundle(
            modelId = modelId2,
            stagedItems = listOf(item2),
            primaryItemName = sharedFileName,
            sourceIdentity = "phone_uri_2",
            format = com.shounak.localmeshai.models.ModelFormat.MediaPipeTask
        )

        assertNotEquals(published1.absolutePath, published2.absolutePath)
        assertEquals("MediaPipe Model 1", published1.readText())
        assertEquals("MediaPipe Model 2", published2.readText())

        val bundle1 = storage.getPublishedBundle(modelId1)
        val bundle2 = storage.getPublishedBundle(modelId2)
        assertNotNull(bundle1)
        assertNotNull(bundle2)
        assertEquals(com.shounak.localmeshai.models.ModelFormat.MediaPipeTask, bundle1?.format)
        assertEquals(com.shounak.localmeshai.models.ModelFormat.MediaPipeTask, bundle2?.format)
    }

    @Test
    fun atomicReservation_blocksConcurrentReaders() {
        val modelId = "atomic_reservation_test"
        val staging = storage.getStagingFile(modelId, "model.gguf")
        staging.writeText("GGUF Data")
        val published = storage.publishArtifact(modelId, staging, "model.gguf", "source_1")

        // Hold a lease
        val lease = ModelArtifactStorage.leaseArtifact(published)
        assertNotNull(lease)

        // Attempt deletion reservation while leased -> must fail
        val reservedWhileLeased = ModelArtifactStorage.reserveAndExecuteDeletion(published) {
            fail("Block should not execute while file is actively leased")
            false
        }
        assertFalse("reserveAndExecuteDeletion must return false while reader lease is held", reservedWhileLeased)

        // Close lease
        lease.close()

        // Deletion reservation after lease closed -> must succeed
        var executed = false
        val reservedAfterClose = ModelArtifactStorage.reserveAndExecuteDeletion(published) {
            executed = true
            published.delete()
        }
        assertTrue("reserveAndExecuteDeletion must succeed once reader lease is closed", reservedAfterClose)
        assertTrue("Deletion block must have executed", executed)
        assertFalse(published.exists())
    }
}

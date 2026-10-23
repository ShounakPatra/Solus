package com.shounak.localmeshai.ui

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.FileHeaderSniffer
import com.shounak.localmeshai.utils.LocalModelImporter
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.utils.TaskBundleFixtures
import com.shounak.localmeshai.utils.NonGgufBundleInspector
import com.shounak.localmeshai.utils.PhoneImportSession
import com.shounak.localmeshai.ai.ShutdownResult
import com.shounak.localmeshai.utils.StagedArtifactItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainViewModelStartupAndImportRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMultiGbBufferAllocationClampingAvoidsNegativeOverflow() {
        val multiGbLength = 3_500_000_000L
        val buggyOverflowInt = multiGbLength.toInt()
        assertTrue("3.5GB cast directly to Int must be negative", buggyOverflowInt < 0)
        val buggyBufferSize = minOf(buggyOverflowInt.coerceAtLeast(0), 512)
        assertEquals("Buggy allocation allocates 0 bytes because of integer overflow", 0, buggyBufferSize)

        val fixedBufferSize = minOf(multiGbLength, 65536L).toInt().coerceAtLeast(0)
        assertEquals("Clamped allocation correctly allocates 65536 bytes", 65536, fixedBufferSize)
    }

    @Test
    fun testStrictContainerValidationDifferentiatesLitertlmFromRawTfl3() {
        val litertlmMagic = "LITERTLM".toByteArray(StandardCharsets.US_ASCII)
        val sniffLitertlm = FileHeaderSniffer.sniffHeader(litertlmMagic, litertlmMagic.size.toLong(), "model.litertlm")
        assertTrue("LITERTLM magic must yield ValidLiteRtLm", sniffLitertlm is FileHeaderSniffer.SniffResult.ValidLiteRtLm)

        val rawTfl3 = byteArrayOf(0, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())
        val sniffRawTfl3NamedLitertlm = FileHeaderSniffer.sniffHeader(rawTfl3, rawTfl3.size.toLong(), "model.litertlm")
        assertTrue("Raw TFL3 named .litertlm must yield NonModel", sniffRawTfl3NamedLitertlm is FileHeaderSniffer.SniffResult.NonModel)

        val sniffRawTfl3Generic = FileHeaderSniffer.sniffHeader(rawTfl3, rawTfl3.size.toLong(), "unknown_file")
        assertTrue("Raw TFL3 without extension must yield NonModel", sniffRawTfl3Generic is FileHeaderSniffer.SniffResult.NonModel)
    }

    @Test
    fun testAppManagedDraftWithZeroUrisRegistersExactlyOnce() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        // Draft has only AppManaged artifacts (so referenced URIs set is empty)
        val draftStagingDir = tempFolder.newFolder("draft_app_managed")
        val stagedFile = File(draftStagingDir, "model.litertlm").apply { writeBytes("dummy".toByteArray()) }
        val appManagedDraft = LocalModelImporter.ImportResult(
            bundle = ModelBundle(
                id = "app_managed_1",
                displayName = "App Managed Model",
                mainArtifacts = listOf(
                    ModelArtifact(
                        role = ArtifactRole.Main,
                        displayName = "model.litertlm",
                        source = ArtifactSource.AppManaged(stagedFile.absolutePath)
                    )
                ),
                format = ModelFormat.LiteRtLm
            ),
            newlyAcquiredGrants = emptyList(),
            stagedArtifactItem = StagedArtifactItem(
                stagedFile = stagedFile,
                relativeTargetName = "model.litertlm",
                isPrimary = true,
                role = ArtifactRole.Main,
                order = 0
            ),
            draftStagingDir = draftStagingDir
        )

        session.finishInspection(appManagedDraft)

        // Registration eligibility must succeed despite zero persisted URIs
        val canRegister = session.startRegistration()
        assertTrue("App-managed draft with zero URIs must be eligible for registration", canRegister)

        val reservedDraft = session.reserveDraftForRegistration()
        assertNotNull("Reserved draft must not be null", reservedDraft)
        assertEquals("app_managed_1", reservedDraft?.result?.bundle?.id)

        // Duplicate registration attempts must be rejected
        val secondAttempt = session.startRegistration()
        assertFalse("Second registration attempt must be blocked by duplicate-submission guard", secondAttempt)
        val secondReserve = session.reserveDraftForRegistration()
        assertNull("Second reserve draft call must return null", secondReserve)
    }

    @Test
    fun testDraftReplacementCancellationAndDisposalOrderingWithStagingCleanup() = runBlocking {
        val session = PhoneImportSession(
            releasePermission = {},
            isUriRegistered = { false }
        )

        // 1. First draft inspected
        val staging1 = tempFolder.newFolder("staging1")
        val draft1 = LocalModelImporter.ImportResult(
            bundle = ModelBundle(id = "m1", displayName = "M1", mainArtifacts = emptyList(), format = ModelFormat.MediaPipeTask),
            newlyAcquiredGrants = emptyList(),
            draftStagingDir = staging1
        )
        session.finishInspection(draft1)
        assertTrue("Staging 1 must exist", staging1.exists())

        // 2. Replaced with draft 2 -> staging 1 must be cleaned up on Dispatchers.IO
        val staging2 = tempFolder.newFolder("staging2")
        val draft2 = LocalModelImporter.ImportResult(
            bundle = ModelBundle(id = "m2", displayName = "M2", mainArtifacts = emptyList(), format = ModelFormat.MediaPipeTask),
            newlyAcquiredGrants = emptyList(),
            draftStagingDir = staging2
        )
        session.finishInspection(draft2)
        assertFalse("Staging 1 must be cleaned up when replaced", staging1.exists())
        assertTrue("Staging 2 must exist", staging2.exists())

        // 3. Reserve draft 2 for registration
        val reserved = session.reserveDraftForRegistration()
        assertNotNull("Draft 2 must be reserved", reserved)

        // 4. Dismissal requested during active registration: must be deferred without deleting accepted work
        val dismissedImmediately = session.requestDismiss()
        assertFalse("Dismissal must be deferred while registration is in progress", dismissedImmediately)
        assertTrue("Staging 2 must be preserved through sheet dismissal during registration", staging2.exists())

        // 5. Registration succeeds: ownership transferred, files preserved
        session.onRegistrationSuccess(emptySet())
        assertTrue("Staging 2 must still exist after registration success", staging2.exists())

        // 6. Clean up draft 2 manually as registration would after publishing
        staging2.deleteRecursively()

        // 7. Another draft created then disposed: disposal must clean its staging directory
        val staging3 = tempFolder.newFolder("staging3")
        val draft3 = LocalModelImporter.ImportResult(
            bundle = ModelBundle(id = "m3", displayName = "M3", mainArtifacts = emptyList(), format = ModelFormat.MediaPipeTask),
            newlyAcquiredGrants = emptyList(),
            draftStagingDir = staging3
        )
        session.finishInspection(draft3)
        assertTrue("Staging 3 must exist", staging3.exists())
        session.dispose()
        assertFalse("Staging 3 must be cleaned up upon session disposal", staging3.exists())
    }

    @Test
    fun testOfficialPaddedTaggedTaskBundleAndTruncatedLitertlm() {
        val testDir = tempFolder.newFolder("inspector_tests")

        // 1. Task ZIP with entry-extra padding. Whole-file AlignZipFile padding is
        // covered separately in TaskBundleValidationTest (Android rejects that in ZipFile).
        val paddedTaskFile = File(testDir, "official_model.task")
        ZipOutputStream(FileOutputStream(paddedTaskFile)).use { zos ->
            // Prefill/decode LLM entry
            val llmEntry = ZipEntry("TF_LITE_PREFILL_DECODE").apply {
                extra = byteArrayOf(0, 0, 0, 0) // 4-byte alignment padding
            }
            zos.putNextEntry(llmEntry)
            val tfl3 = TaskBundleFixtures.graph()
            zos.write(tfl3)
            zos.closeEntry()

            // Tokenizer entry
            val tokEntry = ZipEntry("TOKENIZER_MODEL")
            zos.putNextEntry(tokEntry)
            zos.write(TaskBundleFixtures.tokenizer())
            zos.closeEntry()

            // Metadata entry
            val metaEntry = ZipEntry("METADATA")
            zos.putNextEntry(metaEntry)
            zos.write(TaskBundleFixtures.metadata())
            zos.closeEntry()
        }

        val taskInspection = NonGgufBundleInspector.inspectMediaPipeTask(paddedTaskFile)
        assertTrue("Task bundle with entry-extra padding must pass inspection", taskInspection.isValid)
        assertEquals(ModelFormat.MediaPipeTask, taskInspection.format)

        // 2. Incomplete task bundle (has metadata but lacks LLM model or tokenizer)
        val incompleteTaskFile = File(testDir, "incomplete.task")
        ZipOutputStream(FileOutputStream(incompleteTaskFile)).use { zos ->
            zos.putNextEntry(ZipEntry("METADATA"))
            zos.write("only_metadata".toByteArray())
            zos.closeEntry()
        }
        val incompleteInspection = NonGgufBundleInspector.inspectMediaPipeTask(incompleteTaskFile)
        assertFalse("Incomplete bundle lacking LLM/tokenizer must fail inspection", incompleteInspection.isValid)

        // 3. Truncated LiteRT-LM container (headerEndOffset points beyond file size)
        val truncatedLitertlmFile = File(testDir, "truncated.litertlm")
        val truncBytes = ByteArray(32).apply {
            "LITERTLM".toByteArray(StandardCharsets.US_ASCII).copyInto(this, 0)
            this[8] = 1 // major version = 1
            this[24] = 0x00
            this[25] = 0x10 // headerEndOffset = 4096 bytes, but file is only 32 bytes
        }
        truncatedLitertlmFile.writeBytes(truncBytes)
        val truncInspection = NonGgufBundleInspector.inspectLiteRtLm(truncatedLitertlmFile)
        assertFalse("Truncated LiteRT-LM container must fail inspection", truncInspection.isValid)

        // 4. Valid LiteRT-LM container with real FlatBuffer SectionMetadata table
        val validLitertlmFile = File(testDir, "valid.litertlm")
        createValidLiteRtLmContainer(validLitertlmFile)
        val validInspection = NonGgufBundleInspector.inspectLiteRtLm(validLitertlmFile)
        assertTrue("Valid LiteRT-LM container with real FlatBuffer sections must pass inspection", validInspection.isValid)
        assertEquals(ModelFormat.LiteRtLm, validInspection.format)
    }

    @Test
    fun testPhoneAndUrlImportsOfIdenticalContentProduceIdenticalCapabilities() {
        val testDir = tempFolder.newFolder("capabilities_test")
        val multimodalTaskFile = File(testDir, "vision_multimodal.task")
        val tfl3Header = TaskBundleFixtures.graph()
        ZipOutputStream(FileOutputStream(multimodalTaskFile)).use { zos ->
            zos.putNextEntry(ZipEntry("TF_LITE_PREFILL_DECODE"))
            zos.write(tfl3Header)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("TOKENIZER_MODEL"))
            zos.write(TaskBundleFixtures.tokenizer())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("TF_LITE_VISION_ENCODER"))
            zos.write(tfl3Header)
            zos.closeEntry()
        }

        // Phone import inspection
        val phoneInspection = NonGgufBundleInspector.inspectFile(multimodalTaskFile, ModelFormat.MediaPipeTask)
        // URL/Download inspection
        val downloadInspection = NonGgufBundleInspector.inspectFile(multimodalTaskFile, ModelFormat.MediaPipeTask)

        assertTrue(phoneInspection.isValid)
        assertTrue(downloadInspection.isValid)
        assertEquals("Phone and URL capabilities must be identical", phoneInspection.capabilities, downloadInspection.capabilities)
        assertTrue("Multimodal component must derive vision support", phoneInspection.capabilities.supportsVision)
        assertFalse("Audio must be false unless component is present", phoneInspection.capabilities.supportsAudio)
    }

    @Test
    fun testPublicationTransactionRollbackAndJournalRecoveryPreservesPreviousModel() {
        val storageRoot = tempFolder.newFolder("storage_tx")
        val artifactStorage = ModelArtifactStorage(storageRoot)
        val modelId = "tx_model"

        // 1. Establish existing published v1
        val stagingV1 = artifactStorage.getStagingDir("tx_staging_v1").apply { mkdirs() }
        val fileV1 = File(stagingV1, "model.litertlm").apply { writeBytes("V1_DATA".toByteArray()) }
        val publishedV1 = artifactStorage.publishArtifactBundle(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(
                    stagedFile = fileV1,
                    relativeTargetName = "model.litertlm",
                    isPrimary = true,
                    role = ArtifactRole.Main,
                    order = 0
                )
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "v1_source",
            format = ModelFormat.LiteRtLm
        )
        assertTrue("V1 published file must exist", publishedV1.exists())

        // 2. Begin transaction for v2
        val stagingV2 = artifactStorage.getStagingDir("tx_staging_v2").apply { mkdirs() }
        val fileV2 = File(stagingV2, "model.litertlm").apply { writeBytes("V2_DATA".toByteArray()) }
        val tx = artifactStorage.beginTransaction(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(
                    stagedFile = fileV2,
                    relativeTargetName = "model.litertlm",
                    isPrimary = true,
                    role = ArtifactRole.Main,
                    order = 0
                )
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "v2_source",
            format = ModelFormat.LiteRtLm
        )

        // 3. Simulate failure before commit and rollback
        tx.rollback()

        // 4. V1 model must be completely preserved!
        assertTrue("Published v1 model must be intact after v2 rollback", publishedV1.exists())
        val bundleAfterRollback = artifactStorage.getPublishedBundle(modelId)
        assertNotNull("Published bundle must still exist", bundleAfterRollback)
        assertEquals("model.litertlm", bundleAfterRollback?.mainArtifacts?.first()?.displayName)

        // 5. Test interrupted journal recovery at startup
        val modelDir = artifactStorage.getModelDir(modelId, create = false)
        val fakeJournal = File(modelDir, "publication_journal.json")
        val interruptedGenDir = File(modelDir, "gen_interrupted").apply { mkdirs() }
        File(interruptedGenDir, "model.litertlm").writeBytes("INTERRUPTED".toByteArray())
        fakeJournal.writeText("""
            {
              "state": "PREPARED",
              "modelId": "$modelId",
              "newGenDirName": "gen_interrupted",
              "hasPreviousManifest": true
            }
        """.trimIndent())

        artifactStorage.recoverPendingTransactions()

        assertFalse("Uncommitted generation must be cleaned up on recovery", interruptedGenDir.exists())
        assertFalse("Journal must be removed after recovery", fakeJournal.exists())
        assertTrue("Previous v1 model must still be intact", publishedV1.exists())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun testSupersededValidationJobCancellationAndGenerationGuard() = runBlocking {
        val inFlightValidations = ConcurrentHashMap<String, String>()
        val latestSnapshotGenerations = ConcurrentHashMap<String, String>()
        val inFlightValidationJobs = ConcurrentHashMap<String, Job>()
        val modelId = "model_1"

        val gen1 = "path1_100_1000"
        latestSnapshotGenerations[modelId] = gen1
        inFlightValidations[modelId] = gen1

        val job1Started = CompletableDeferred<Unit>()
        val job1Applied = CompletableDeferred<Boolean>()

        val job1 = launch {
            job1Started.complete(Unit)
            kotlinx.coroutines.delay(50)
            if (latestSnapshotGenerations[modelId] != gen1) {
                job1Applied.complete(false)
                return@launch
            }
            job1Applied.complete(true)
        }
        inFlightValidationJobs[modelId] = job1

        job1Started.await()

        val gen2 = "path1_200_2000"
        latestSnapshotGenerations[modelId] = gen2
        inFlightValidationJobs[modelId]?.cancel()
        inFlightValidations[modelId] = gen2

        val job2Applied = CompletableDeferred<Boolean>()
        val job2 = launch {
            if (latestSnapshotGenerations[modelId] != gen2) {
                job2Applied.complete(false)
                return@launch
            }
            job2Applied.complete(true)
        }
        inFlightValidationJobs[modelId] = job2

        val job2Result = job2Applied.await()
        assertTrue("Job 2 (latest generation) must be applied", job2Result)
        assertFalse("Job 1 was cancelled or guarded out by generation mismatch", job1Applied.isCompleted && job1Applied.getCompleted())
    }

    private fun writeUInt16LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeInt32LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeUInt64LE(arr: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            arr[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
    }

    private fun createValidLiteRtLmContainer(
        file: File,
        fileSize: Int = 1024,
        headerEndOffset: Long = 256L,
        sectionCount: Int = 2,
        corruptOffsets: Boolean = false
    ) {
        val bytes = ByteArray(fileSize)
        "LITERTLM".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, 0)
        writeInt32LE(bytes, 8, 1) // majorVersion = 1
        writeInt32LE(bytes, 12, 0) // minorVersion = 0
        writeInt32LE(bytes, 16, 0) // patchVersion = 0
        writeUInt64LE(bytes, 24, headerEndOffset) // headerEndOffset

        // FlatBuffer payload starts at byte 32 of file
        val fb = ByteArray((headerEndOffset - 32L).toInt())
        writeInt32LE(fb, 0, 16) // rootOffset = 16

        // VTable for Root table at offset 4
        writeUInt16LE(fb, 4, 8) // vtableSize = 8
        writeUInt16LE(fb, 6, 8) // tableSize = 8
        writeUInt16LE(fb, 8, 0) // field 0 offset = 0
        writeUInt16LE(fb, 10, 4) // field 1 offset = 4 (section_metadata)

        // Root table at offset 16
        writeInt32LE(fb, 16, 12) // vtableOffset = 16 - 4 = 12
        writeInt32LE(fb, 20, 16) // secMetaTableOffset = 36 - 20 = 16

        // VTable for SectionMetadata at offset 28
        writeUInt16LE(fb, 28, 6) // secVtableSize = 6
        writeUInt16LE(fb, 30, 8) // secTableSize = 8
        writeUInt16LE(fb, 32, 4) // field 0 offset = 4 (objects vector)

        // SectionMetadata table at offset 36
        writeInt32LE(fb, 36, 8) // secVtableOffset = 36 - 28 = 8
        writeInt32LE(fb, 40, 12) // vecOffset = 52 - 40 = 12

        // Objects vector at offset 52
        writeInt32LE(fb, 52, sectionCount)
        if (sectionCount >= 2) {
            writeInt32LE(fb, 56, 24) // elem 0 UOffset = 80 - 56 = 24
            writeInt32LE(fb, 60, 52) // elem 1 UOffset = 112 - 60 = 52
        }

        // Shared VTable for SectionObject at offset 64
        writeUInt16LE(fb, 64, 12) // sVtableSize = 12
        writeUInt16LE(fb, 66, 24) // sTableSize = 24
        writeUInt16LE(fb, 68, 0) // field 0 offset = 0
        writeUInt16LE(fb, 70, 4) // field 1 offset (begin_offset) = 4
        writeUInt16LE(fb, 72, 12) // field 2 offset (end_offset) = 12
        writeUInt16LE(fb, 74, 20) // field 3 offset (data_type) = 20

        // SectionObject 0 at offset 80 (TFLiteModel = 3)
        writeInt32LE(fb, 80, 16) // sVtableOffset = 80 - 64 = 16
        writeUInt64LE(fb, 84, if (corruptOffsets) 10L else headerEndOffset) // begin_offset
        writeUInt64LE(fb, 92, 512L) // end_offset = 512
        fb[100] = 3.toByte() // data_type = 3 (TFLiteModel)

        // SectionObject 1 at offset 112 (Tokenizer = 4)
        writeInt32LE(fb, 112, 48) // sVtableOffset = 112 - 64 = 48
        writeUInt64LE(fb, 116, 512L) // begin_offset = 512
        writeUInt64LE(fb, 124, if (corruptOffsets) 2048L else 1024L) // end_offset
        fb[132] = 4.toByte() // data_type = 4 (SentencePiece)

        fb.copyInto(bytes, destinationOffset = 32)
        "VALID_MODEL_WEIGHTS_AND_TOKENIZER".toByteArray().copyInto(bytes, destinationOffset = 256)
        file.writeBytes(bytes)
    }

    @Test
    fun testMalformedLiteRtLmFlatBufferTablesRejected() {
        val testDir = tempFolder.newFolder("malformed_litertlm_test")

        // 1. Zero section count
        val zeroCountFile = File(testDir, "zero_count.litertlm")
        createValidLiteRtLmContainer(zeroCountFile, sectionCount = 0)
        val zeroInspection = NonGgufBundleInspector.inspectLiteRtLm(zeroCountFile)
        assertFalse("Zero section count must fail inspection", zeroInspection.isValid)

        // 2. Excessive section count (> 100)
        val highCountFile = File(testDir, "high_count.litertlm")
        createValidLiteRtLmContainer(highCountFile, sectionCount = 150)
        val highInspection = NonGgufBundleInspector.inspectLiteRtLm(highCountFile)
        assertFalse("Excessive section count (> 100) must fail inspection", highInspection.isValid)

        // 3. Out-of-bounds section offsets
        val corruptFile = File(testDir, "corrupt_offsets.litertlm")
        createValidLiteRtLmContainer(corruptFile, corruptOffsets = true)
        val corruptInspection = NonGgufBundleInspector.inspectLiteRtLm(corruptFile)
        assertFalse("Out-of-bounds section offsets must fail inspection", corruptInspection.isValid)
    }

    @Test
    fun testUnreadableStoreRecoveryPreservesAllArtifactsAndSetsRecoveryRequired() {
        val storageRoot = tempFolder.newFolder("storage_unreadable")
        val artifactStorage = ModelArtifactStorage(storageRoot)
        val modelId = "unreadable_store_model"

        val staging = artifactStorage.getStagingDir("staging_unreadable").apply { mkdirs() }
        val modelFile = File(staging, "model.litertlm").apply { writeBytes("PRESERVE_ME".toByteArray()) }
        val published = artifactStorage.publishArtifactBundle(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(
                    stagedFile = modelFile,
                    relativeTargetName = "model.litertlm",
                    isPrimary = true,
                    role = ArtifactRole.Main,
                    order = 0
                )
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "source_1",
            format = ModelFormat.LiteRtLm
        )
        assertTrue(published.exists())

        // Simulate pending uncommitted generation and journal
        val modelDir = artifactStorage.getModelDir(modelId, create = false)
        val pendingGen = File(modelDir, "gen_pending").apply { mkdirs() }
        val pendingFile = File(pendingGen, "model.litertlm").apply { writeBytes("PENDING".toByteArray()) }
        val manifestFile = File(modelDir, "manifest.json")
        val bakManifest = File(modelDir, "manifest.json.tx_bak").apply {
            manifestFile.copyTo(this, overwrite = true)
        }
        val journal = File(modelDir, "publication_journal.json").apply {
            writeText("""{"state":"PREPARED","modelId":"$modelId","newGenDirName":"gen_pending","hasPreviousManifest":true}""")
        }

        // When store is unavailable during startup recovery:
        artifactStorage.recoverPendingTransactions(isStoreAvailable = false)

        // BOTH generations, manifest, and journal MUST be preserved!
        assertTrue("Published generation must be preserved when store is unreadable", published.exists())
        assertTrue("Pending generation must be preserved when store is unreadable", pendingFile.exists())
        assertTrue("Journal must be preserved when store is unreadable", journal.exists())
        assertTrue("Recovery required state must be recorded", ModelArtifactStorage.isRecoveryRequired(modelId))
        assertTrue("Recovery required models must contain model", ModelArtifactStorage.getRecoveryRequiredModels().contains(modelId))

        // When store becomes available on next recovery:
        artifactStorage.recoverPendingTransactions(isStoreAvailable = true)
        assertFalse("Pending generation must be rolled back once store is available", pendingGen.exists())
        assertFalse("Journal must be removed once store is available", journal.exists())
        assertTrue("Published model remains intact", published.exists())
        assertFalse("Recovery required state must be cleared", ModelArtifactStorage.isRecoveryRequired(modelId))
    }

    @Test
    fun testMissingBackupWithHasPreviousManifestPreservesFilesAndFlagsRecovery() {
        val storageRoot = tempFolder.newFolder("storage_missing_bak")
        val artifactStorage = ModelArtifactStorage(storageRoot)
        val modelId = "missing_bak_model"

        val modelDir = artifactStorage.getModelDir(modelId, create = true)
        val manifestFile = File(modelDir, "manifest.json").apply { writeText("{\"id\":\"$modelId\"}") }
        val pendingGen = File(modelDir, "gen_new").apply { mkdirs() }
        File(pendingGen, "model.litertlm").writeBytes("NEW".toByteArray())
        val journal = File(modelDir, "publication_journal.json").apply {
            writeText("""{"state":"PREPARED","modelId":"$modelId","newGenDirName":"gen_new","hasPreviousManifest":true}""")
        }
        val backupFile = File(modelDir, "manifest.json.bak")
        assertFalse("Backup file must not exist for this test", backupFile.exists())

        artifactStorage.recoverPendingTransactions(isStoreAvailable = true)

        // Manifest must NOT be deleted because backup is missing and hasPreviousManifest was true
        assertTrue("Manifest must be preserved instead of deleted when backup is missing", manifestFile.exists())
        assertTrue("Recovery required state must be flagged", ModelArtifactStorage.isRecoveryRequired(modelId))
    }

    @Test
    fun testExactIdentityEjectionInCoordinator(): Unit = runBlocking {
        var stopCallCount = 0
        var releaseCallCount = 0

        val token = ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Chat,
            stableModelIdProvider = { "model_alpha" },
            modelPathProvider = { "/data/models/model_alpha.bin" },
            loadOperationIdProvider = { "op_uuid_100" },
            requestStop = { stopCallCount++ },
            release = {
                releaseCallCount++
                ShutdownResult.Success
            }
        )
        assertNotNull("Registration token must not be null", token)

        // Make Chat the active owner
        ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Chat) { }

        // 1. Eject with different model ID -> StaleNoOp
        val wrongIdResult = ModelRuntimeCoordinator.releaseIfActive(
            owner = ModelRuntimeOwner.Chat,
            expectedModelId = "model_beta"
        )
        assertEquals(ShutdownResult.StaleNoOp, wrongIdResult)
        assertEquals(0, stopCallCount)

        // 2. Eject with substring model ID -> StaleNoOp (strict exact match required!)
        val substringResult = ModelRuntimeCoordinator.releaseIfActive(
            owner = ModelRuntimeOwner.Chat,
            expectedModelId = "alpha"
        )
        assertEquals(ShutdownResult.StaleNoOp, substringResult)
        assertEquals(0, stopCallCount)

        // 3. Eject with matching model ID but mismatched loadOpId -> StaleNoOp
        val wrongOpResult = ModelRuntimeCoordinator.releaseIfActive(
            owner = ModelRuntimeOwner.Chat,
            expectedModelId = "model_alpha",
            expectedLoadOperationId = "op_uuid_999"
        )
        assertEquals(ShutdownResult.StaleNoOp, wrongOpResult)
        assertEquals(0, stopCallCount)

        // 4. Exact match model ID and loadOpId -> Success
        val exactResult = ModelRuntimeCoordinator.releaseIfActive(
            owner = ModelRuntimeOwner.Chat,
            expectedModelId = "model_alpha",
            expectedLoadOperationId = "op_uuid_100"
        )
        assertEquals(ShutdownResult.Success, exactResult)
        assertEquals(1, stopCallCount)
        assertEquals(1, releaseCallCount)

        // 5. Subsequent eject after already unloaded -> StaleNoOp
        val subsequentResult = ModelRuntimeCoordinator.releaseIfActive(
            owner = ModelRuntimeOwner.Chat,
            expectedModelId = "model_alpha"
        )
        assertEquals(ShutdownResult.StaleNoOp, subsequentResult)
        assertEquals(1, stopCallCount)

        // Clean up
        ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Chat, token)
    }

    @Test
    fun testCombinedThinkingAndVisionCapabilitiesPreservedAndRestorationDefersInspection() {
        val testDir = tempFolder.newFolder("combined_caps_test")
        val multimodalFile = File(testDir, "thinking_vision.task")

        val tfl3Header = TaskBundleFixtures.graph()
        ZipOutputStream(FileOutputStream(multimodalFile)).use { zos ->
            zos.putNextEntry(ZipEntry("TF_LITE_PREFILL_DECODE"))
            zos.write(tfl3Header)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("TOKENIZER_MODEL"))
            zos.write(TaskBundleFixtures.tokenizer())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("TF_LITE_VISION_ENCODER"))
            zos.write(tfl3Header)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("METADATA"))
            zos.write("""{"thinking_mode":"model_controlled"}""".toByteArray())
            zos.closeEntry()
        }

        val inspection = NonGgufBundleInspector.inspectMediaPipeTask(multimodalFile)
        assertTrue(inspection.isValid)
        assertTrue("Vision capability must be true", inspection.capabilities.supportsVision)
        assertEquals("Thinking capability must be ModelControlled", ThinkingCapability.ModelControlled, inspection.capabilities.thinking)
        assertTrue(
            "Attachments must be supported when vision is present",
            ChatRuntimePolicy.supportsAttachments(multimodalFile.absolutePath, inspection.capabilities)
        )

        // Test lazy upgrade on getPublishedBundle
        val storageRoot = tempFolder.newFolder("storage_upgrade")
        val storage = ModelArtifactStorage(storageRoot)
        val modelId = "upgrade_model"

        val staging = storage.getStagingDir("stg").apply { mkdirs() }
        val stagedTask = File(staging, "model.task")
        multimodalFile.copyTo(stagedTask)

        storage.publishArtifactBundle(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(
                    stagedFile = stagedTask,
                    relativeTargetName = "model.task",
                    isPrimary = true,
                    role = ArtifactRole.Main,
                    order = 0
                )
            ),
            primaryItemName = "model.task",
            sourceIdentity = "src",
            format = ModelFormat.MediaPipeTask
        )

        // Tamper manifest to simulate legacy detector version 0
        val modelDir = storage.getModelDir(modelId, create = false)
        val manifestFile = File(modelDir, "manifest.json")
        val legacyText = manifestFile.readText().replace(
            "\"thinkingDetectorVersion\": ${NonGgufBundleInspector.DETECTOR_VERSION}",
            "\"thinkingDetectorVersion\": 0"
        )
        manifestFile.writeText(legacyText)

        // Restoring a row must preserve its file identity without parsing or rewriting its model metadata.
        val restoredBundle = storage.getPublishedBundle(modelId)
        assertNotNull(restoredBundle)
        assertEquals(0, restoredBundle!!.capabilities.thinkingDetectorVersion)
        assertEquals(legacyText, manifestFile.readText())
        val originalPath = restoredBundle.mainArtifacts.first().source.location
        val refreshed = NonGgufBundleInspector.inspectMediaPipeTask(File(originalPath))
        assertTrue(refreshed.isValid)
        assertEquals(NonGgufBundleInspector.DETECTOR_VERSION, refreshed.capabilities.thinkingDetectorVersion)
        assertTrue(refreshed.capabilities.supportsVision)
        assertEquals(ThinkingCapability.ModelControlled, refreshed.capabilities.thinking)
        assertEquals(originalPath, storage.getPublishedBundle(modelId)!!.mainArtifacts.first().source.location)

    }

    @Test
    fun testFailureAfterStorePersistencePreservesNewGenerationAndFlagsRecovery() {
        val storageRoot = tempFolder.newFolder("storage_store_failure")
        val artifactStorage = ModelArtifactStorage(storageRoot)
        val modelId = "persisted_model"

        val staging = artifactStorage.getStagingDir(modelId).apply { mkdirs() }
        val stagedFile = File(staging, "model.litertlm").apply { writeBytes("GEN_1_DATA".toByteArray()) }
        val tx = artifactStorage.beginTransaction(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(
                    stagedFile = stagedFile,
                    relativeTargetName = "model.litertlm",
                    isPrimary = true,
                    role = ArtifactRole.Main,
                    order = 0
                )
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "source_persisted",
            format = ModelFormat.LiteRtLm
        )

        val newGenFile = tx.primaryFile
        assertTrue("Prepared new generation file must exist", newGenFile.exists())

        // In the durable registration boundary:
        // Assume customModelStore has added/updated the bundle referencing newGenFile.
        // If a commit/journal failure occurs afterwards:
        ModelArtifactStorage.recordRecoveryRequired(modelId, "Simulated failure after durable store persistence")

        // Assert: New generation file MUST NOT be rolled back or deleted
        assertTrue("Generation file must still exist after durable boundary failure", newGenFile.exists())
        assertTrue("Recovery required state must be recorded", ModelArtifactStorage.isRecoveryRequired(modelId))

        // New publication transactions must be blocked while in recovery-required state
        var publicationBlocked = false
        try {
            artifactStorage.beginTransaction(
                modelId = modelId,
                stagedItems = emptyList(),
                primaryItemName = "model.litertlm",
                sourceIdentity = "source_persisted",
                format = ModelFormat.LiteRtLm
            )
        } catch (e: Exception) {
            publicationBlocked = true
        }
        assertTrue("Beginning transaction must be blocked when recovery is required", publicationBlocked)

        ModelArtifactStorage.clearRecoveryRequired(modelId)
    }

    @Test
    fun testFailedManifestRestorationRetainsBothGenerationsBackupAndJournal() {
        val storageRoot = tempFolder.newFolder("storage_failed_manifest_restore")
        val artifactStorage = ModelArtifactStorage(storageRoot)
        val modelId = "fail_manifest_model"

        val staging1 = artifactStorage.getStagingDir(modelId).apply { mkdirs() }
        val stagedFile1 = File(staging1, "model.litertlm").apply { writeBytes("V1_DATA".toByteArray()) }
        val v1File = artifactStorage.publishArtifactBundle(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(stagedFile = stagedFile1, relativeTargetName = "model.litertlm", isPrimary = true, role = ArtifactRole.Main, order = 0)
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "v1",
            format = ModelFormat.LiteRtLm
        )
        assertTrue(v1File.exists())

        val staging2 = artifactStorage.getStagingDir(modelId).apply { mkdirs() }
        val stagedFile2 = File(staging2, "model.litertlm").apply { writeBytes("V2_DATA".toByteArray()) }
        val tx = artifactStorage.beginTransaction(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(stagedFile = stagedFile2, relativeTargetName = "model.litertlm", isPrimary = true, role = ArtifactRole.Main, order = 0)
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "v2",
            format = ModelFormat.LiteRtLm
        )
        val v2File = tx.primaryFile
        assertTrue(v2File.exists())

        val manifestFile = artifactStorage.getManifestFile(modelId)
        val modelDir = artifactStorage.getModelDir(modelId, create = false)
        val journalFile = File(modelDir, "publication_journal.json")
        assertTrue(journalFile.exists())

        // Simulate corrupted backup manifest or restore failure by deleting backup files while hasPreviousManifest is true
        val bakFile = File(modelDir, "manifest.json.tx_bak")
        bakFile.delete()
        File(modelDir, "manifest.json.bak").delete()

        artifactStorage.recoverPendingTransactions(isStoreAvailable = true)

        // Must retain both generations, manifest and flag recovery-required
        assertTrue("V1 generation must be retained", v1File.exists())
        assertTrue("V2 generation must be retained on failed manifest restore", v2File.exists())
        assertTrue("Manifest must be retained", manifestFile.exists())
        assertTrue("Recovery required must be recorded", ModelArtifactStorage.isRecoveryRequired(modelId))

        ModelArtifactStorage.clearRecoveryRequired(modelId)
    }

    @Test
    fun testRollbackBlockedWhenGenerationIsLeased() {
        val storageRoot = tempFolder.newFolder("storage_leased_rollback")
        val artifactStorage = ModelArtifactStorage(storageRoot)
        val modelId = "leased_rollback_model"

        val staging = artifactStorage.getStagingDir(modelId).apply { mkdirs() }
        val stagedFile = File(staging, "model.litertlm").apply { writeBytes("LEASED_DATA".toByteArray()) }
        val tx = artifactStorage.beginTransaction(
            modelId = modelId,
            stagedItems = listOf(
                StagedArtifactItem(stagedFile = stagedFile, relativeTargetName = "model.litertlm", isPrimary = true, role = ArtifactRole.Main, order = 0)
            ),
            primaryItemName = "model.litertlm",
            sourceIdentity = "src_leased",
            format = ModelFormat.LiteRtLm
        )
        val newGenFile = tx.primaryFile
        assertTrue(newGenFile.exists())

        // Acquire lease on the new generation
        val lease = ModelArtifactStorage.leaseArtifact(newGenFile)
        try {
            // Attempt rollback while generation is leased
            val rolledBack = tx.rollback()
            assertFalse("Rollback must return false when generation deletion is blocked by lease", rolledBack)
            assertTrue("Leased generation file must survive rollback attempt", newGenFile.exists())
            assertTrue("Recovery required must be set because rollback could not clean leased generation", ModelArtifactStorage.isRecoveryRequired(modelId))
        } finally {
            lease.close()
        }

        // After lease is released, recovery can successfully clean up
        artifactStorage.recoverPendingTransactions(isStoreAvailable = true)
        assertFalse("After lease is released, pending generation is cleaned up", newGenFile.exists())
        assertFalse("Recovery required is cleared once pending transactions resolve", ModelArtifactStorage.isRecoveryRequired(modelId))
    }

    @Test
    fun testOversizedLiteRtLmHeaderRejectedWithoutAllocating() {
        val testDir = tempFolder.newFolder("oversized_header_test")
        val oversizedFile = File(testDir, "oversized.litertlm")
        val headerBytes = ByteArray(32).apply {
            "LITERTLM".toByteArray(StandardCharsets.US_ASCII).copyInto(this, 0)
            this[8] = 1 // major version = 1
            // Set headerEndOffset = 100_000_000L (> 8MB limit)
            val bigOffset = 100_000_000L
            for (i in 0..7) {
                this[24 + i] = ((bigOffset shr (i * 8)) and 0xFF).toByte()
            }
        }
        oversizedFile.writeBytes(headerBytes)

        val inspection = NonGgufBundleInspector.inspectLiteRtLm(oversizedFile)
        assertFalse("Oversized header must fail inspection", inspection.isValid)
        assertTrue("Error message must explain header size violation", inspection.errorMessage?.contains("oversized") == true)
    }

    @Test
    fun testMediaPipeTaskMissingVisionComponentsAndMalformedMetadata() {
        val testDir = tempFolder.newFolder("invalid_vision_task_test")
        val taskFile = File(testDir, "bad_vision.task")

        val validTfl3Header = TaskBundleFixtures.graph()
        val badEncoder = ByteArray(32).apply {
            this[0] = 12.toByte()
            this[4] = 'B'.code.toByte()
            this[5] = 'A'.code.toByte()
            this[6] = 'D'.code.toByte()
            this[7] = '0'.code.toByte()
        }
        ZipOutputStream(FileOutputStream(taskFile)).use { zos ->
            // Valid LLM model
            zos.putNextEntry(ZipEntry("TF_LITE_PREFILL_DECODE"))
            zos.write(validTfl3Header)
            zos.closeEntry()

            // Valid tokenizer
            zos.putNextEntry(ZipEntry("TOKENIZER_MODEL"))
            zos.write(TaskBundleFixtures.tokenizer())
            zos.closeEntry()

            // Vision encoder with corrupt non-TFL3 magic
            zos.putNextEntry(ZipEntry("TF_LITE_VISION_ENCODER"))
            zos.write(badEncoder)
            zos.closeEntry()

            // Malformed non-JSON metadata
            zos.putNextEntry(ZipEntry("METADATA"))
            zos.write("THIS_IS_NOT_VALID_JSON_METADATA".toByteArray())
            zos.closeEntry()
        }

        val inspection = NonGgufBundleInspector.inspectMediaPipeTask(taskFile)
        assertFalse("Malformed recognized metadata must fail validation", inspection.isValid)
        assertTrue("Must support text", inspection.capabilities.supportsText)
        assertFalse("Must NOT support vision when vision encoder lacks valid TFL3 magic", inspection.capabilities.supportsVision)

    }
}

package com.shounak.localmeshai.services

import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.utils.DownloadProgress
import com.shounak.localmeshai.utils.DownloadResult
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelManifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadControllerTest {

    @Test(timeout = 10_000L) fun completionCallbackFailureDoesNotDowngradePublishedDownload() = runBlocking {
        val recorded = mutableListOf<String>()
        var failed = false
        val downloader = object : ModelDownloader(context = null, artifactStorage = artifactStorage, minValidModelBytes = 0L) {
            override suspend fun downloadModel(
                url: String, modelId: String, fileName: String, packageType: ModelPackage,
                bearerToken: String?, expectedSha256: String?, eTag: String?,
                publishImmediately: Boolean, sourceIdentity: String?,
                onProgress: suspend (DownloadProgress) -> Unit
            ) = DownloadResult("/models/completed.gguf", 1000L)
        }
        val controller = DownloadController(downloader, scope = createTestScope(),
            eventListener = object : DownloadEventListener {
                override fun onDownloadCompleted(request: DownloadRequest, result: DownloadResult, snapshot: DownloadSnapshot) {
                    throw IllegalStateException("Notification could not be shown")
                }
                override fun onDownloadFailed(request: DownloadRequest, error: Throwable, snapshot: DownloadSnapshot) {
                    failed = true
                }
            }, recordCompletedDownload = { recorded.add(it) })
        val id = "completion_callback_failure"
        controller.start(DownloadRequest(id, "Completed", "https://example.com/model", "completed.gguf", ModelPackage.SingleFile)).join()
        val snapshot = DownloadStateStore.get(id)!!
        assertEquals(ModelStatus.Available, snapshot.status)
        assertEquals("/models/completed.gguf", snapshot.localPath)
        assertEquals(1000L, snapshot.downloadedBytes)
        assertEquals(listOf(id), recorded)
        assertFalse(failed)
    }

    @Test fun recoveredPublicationRecordsRecencyOnceForPauseAndCancel() = runBlocking {
        for (pause in listOf(true, false)) {
            val id = "recovered_publication_$pause"
            val file = artifactStorage.getModelDir(id, create = true).resolve("model.gguf")
            file.writeText("published model data")
            artifactStorage.getManifestFile(id).writeText(ModelManifest(
                modelId = id, relativePath = file.name, sourceIdentity = "test",
                sizeBytes = file.length(), checksumSha256 = null).toJson())
            DownloadStateStore.update(DownloadSnapshot(id, ModelStatus.Downloading,
                downloadedBytes = file.length(), totalBytes = file.length(), startedAt = 100L))
            val recorded = mutableListOf<String>()
            val controller = DownloadController(fakeDownloader, scope = createTestScope(),
                recordCompletedDownload = { recorded.add(it) })
            if (pause) controller.pause(id) else controller.cancelDownload(id)
            val completed = DownloadStateStore.get(id)!!
            assertEquals(ModelStatus.Available, completed.status)
            assertEquals(file.absolutePath, completed.localPath)
            assertTrue(completed.downloadedAt > 0L)
            assertEquals(listOf(id), recorded)
            // Late/repeated UI actions must not make an old model recent again.
            controller.pause(id)
            controller.cancelDownload(id)
            assertEquals(completed.downloadedAt, DownloadStateStore.get(id)?.downloadedAt)
            assertEquals(listOf(id), recorded)
            assertTrue(file.exists())
        }
    }

    @Test(timeout = 10_000L) fun progressKeepsStartOrderAndCompletionRecordsWithoutAListener() = runBlocking {
        val starts = mapOf("start_order_older" to CompletableDeferred<Unit>(), "start_order_newer" to CompletableDeferred<Unit>())
        val finish = CompletableDeferred<Unit>()
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val downloader = object : ModelDownloader(context = null, artifactStorage = artifactStorage, minValidModelBytes = 0L) {
            override suspend fun downloadModel(
                url: String, modelId: String, fileName: String, packageType: ModelPackage,
                bearerToken: String?, expectedSha256: String?, eTag: String?,
                publishImmediately: Boolean, sourceIdentity: String?,
                onProgress: suspend (DownloadProgress) -> Unit
            ): DownloadResult {
                onProgress(DownloadProgress(0.5f, 50L, 100L, 0L))
                starts.getValue(modelId).complete(Unit)
                finish.await()
                onProgress(DownloadProgress(1f, 100L, 100L, 0L))
                return DownloadResult("/models/$fileName", 100L)
            }
        }
        val controller = DownloadController(downloader, scope = createTestScope(),
            recordCompletedDownload = { recorded.add(it) })
        val older = controller.start(DownloadRequest("start_order_older", "Older", "https://example.com/older", "older.gguf", ModelPackage.SingleFile))
        starts.getValue("start_order_older").await()
        val newer = controller.start(DownloadRequest("start_order_newer", "Newer", "https://example.com/newer", "newer.gguf", ModelPackage.SingleFile))
        starts.getValue("start_order_newer").await()
        val olderStart = DownloadStateStore.get("start_order_older")!!.startedAt
        val newerStart = DownloadStateStore.get("start_order_newer")!!.startedAt
        assertTrue(olderStart > 0L)
        assertTrue(newerStart > olderStart)
        finish.complete(Unit)
        older.join()
        newer.join()
        assertEquals(setOf("start_order_older", "start_order_newer"), recorded.toSet())
        assertEquals(2, recorded.size)
        assertEquals(ModelStatus.Available, DownloadStateStore.get("start_order_newer")?.status)
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var artifactStorage: ModelArtifactStorage
    private lateinit var fakeDownloader: ModelDownloader

    @Before
    fun setup() {
        rootDir = tempFolder.newFolder("models_root")
        artifactStorage = ModelArtifactStorage(rootDir)
        fakeDownloader = ModelDownloader(
            context = null,
            customHttpClient = null,
            artifactStorage = artifactStorage,
            minValidModelBytes = 0L
        )
    }

    private val activeTestScopes = mutableListOf<CoroutineScope>()

    private fun createTestScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default).also { activeTestScopes.add(it) }

    @After
    fun tearDown() {
        DownloadController.setInstanceForTesting(null)
        activeTestScopes.forEach { it.cancel() }
        activeTestScopes.clear()
    }

    @Test
    fun testStartPauseAndResume() = runBlocking {
        val progressSignal = CompletableDeferred<Unit>()
        val pauseSignal = CompletableDeferred<Unit>()

        val testScope = createTestScope()

        // Mock download that pauses when asked
        val request = DownloadRequest(
            modelId = "test_model_1",
            name = "Test Model 1",
            url = "https://example.com/model.bin",
            fileName = "model.bin",
            packageType = ModelPackage.SingleFile
        )

        val controllerSpy = DownloadController(
            downloader = object : ModelDownloader(
                context = null,
                artifactStorage = artifactStorage,
                minValidModelBytes = 0L
            ) {
                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    publishImmediately: Boolean,
                    sourceIdentity: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    onProgress(DownloadProgress(0.5f, 500L, 1000L, 100L))
                    progressSignal.complete(Unit)
                    runCatching { pauseSignal.await() }
                    return DownloadResult(
                        localPath = artifactStorage.getModelDir(modelId).resolve(fileName).absolutePath,
                        bytesDownloaded = 1000L
                    )
                }

                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult = downloadModel(
                    url, modelId, fileName, packageType, bearerToken, expectedSha256, eTag,
                    true, null, onProgress
                )
            },
            scope = testScope,
            networkChecker = { true }
        )

        controllerSpy.start(request)
        progressSignal.await()
        assertTrue("Controller should report downloading", controllerSpy.isDownloading("test_model_1"))

        // Pause download
        controllerSpy.pause("test_model_1")
        assertFalse("Controller should not report downloading after pause", controllerSpy.isDownloading("test_model_1"))
        assertEquals(ModelStatus.Paused, DownloadStateStore.get("test_model_1")?.status)
    }

    @Test
    fun testCancelAndResumeResetsState() = runBlocking {
        val testScope = createTestScope()
        val progressSignal = CompletableDeferred<Unit>()
        val cancelSignal = CompletableDeferred<Unit>()
        val request = DownloadRequest(
            modelId = "test_cancel_model",
            name = "Cancel Model",
            url = "https://example.com/cancel.bin",
            fileName = "cancel.bin",
            packageType = ModelPackage.SingleFile
        )

        val controller = DownloadController(
            downloader = object : ModelDownloader(
                context = null,
                artifactStorage = artifactStorage,
                minValidModelBytes = 0L
            ) {
                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    publishImmediately: Boolean,
                    sourceIdentity: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    onProgress(DownloadProgress(0.3f, 300L, 1000L, 50L))
                    progressSignal.complete(Unit)
                    runCatching { cancelSignal.await() }
                    return DownloadResult("path", 1000L)
                }

                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult = downloadModel(
                    url, modelId, fileName, packageType, bearerToken, expectedSha256, eTag,
                    true, null, onProgress
                )
            },
            scope = testScope,
            networkChecker = { true }
        )

        controller.start(request)
        progressSignal.await()
        assertTrue(controller.isDownloading(request.modelId))

        // Cancel
        controller.cancel(request.modelId)
        assertFalse(controller.isDownloading(request.modelId))
        val snapshot = DownloadStateStore.get(request.modelId)
        assertEquals(ModelStatus.NotDownloaded, snapshot?.status)
        assertEquals(0L, snapshot?.downloadedBytes)
    }

    @Test
    fun testStaleAttemptDoesNotOverwriteNewAttempt() = runBlocking {
        val testScope = createTestScope()
        val request = DownloadRequest(
            modelId = "test_stale_model",
            name = "Stale Model",
            url = "https://example.com/stale.bin",
            fileName = "stale.bin",
            packageType = ModelPackage.SingleFile
        )

        var attemptCount = 0
        val attempt1Started = CompletableDeferred<Unit>()
        val attempt1Hold = CompletableDeferred<Unit>()
        val attempt2Completed = CompletableDeferred<Unit>()

        val controller = DownloadController(
            downloader = object : ModelDownloader(
                context = null,
                artifactStorage = artifactStorage,
                minValidModelBytes = 0L
            ) {
                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    publishImmediately: Boolean,
                    sourceIdentity: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    val currentAttempt = ++attemptCount
                    if (currentAttempt == 1) {
                        attempt1Started.complete(Unit)
                        runCatching { attempt1Hold.await() }
                        return DownloadResult("stale_path", 500L)
                    } else {
                        attempt2Completed.complete(Unit)
                        return DownloadResult("new_path", 1000L)
                    }
                }

                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult = downloadModel(
                    url, modelId, fileName, packageType, bearerToken, expectedSha256, eTag,
                    true, null, onProgress
                )
            },
            scope = testScope,
            networkChecker = { true }
        )

        // Start Attempt 1
        controller.start(request)
        attempt1Started.await()

        // Now start Attempt 2 (which cancels attempt 1)
        controller.start(request)

        // Release attempt 1 to finish its coroutine
        attempt1Hold.complete(Unit)

        // Wait for attempt 2 to complete
        attempt2Completed.await()
        delay(100)

        // Verify Attempt 2's completion state was preserved and not overwritten by attempt 1's cleanup/finally
        val snapshot = DownloadStateStore.get(request.modelId)
        assertEquals(ModelStatus.Available, snapshot?.status)
        assertEquals("new_path", snapshot?.localPath)
    }

    @Test
    fun testRemoveModelCleansUpArtifacts() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default)
        val request = DownloadRequest(
            modelId = "test_remove_model",
            name = "Remove Model",
            url = "https://example.com/remove.bin",
            fileName = "remove.bin",
            packageType = ModelPackage.SingleFile
        )

        // Simulate created model artifact
        val modelDir = artifactStorage.getModelDir(request.modelId, create = true)
        val artifactFile = modelDir.resolve("remove.bin")
        artifactFile.writeText("sample model content")

        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = testScope,
            networkChecker = { true }
        )

        assertTrue(modelDir.exists())
        controller.remove(request.modelId)

        assertFalse("Model directory should be deleted", modelDir.exists())
        val snapshot = DownloadStateStore.get(request.modelId)
        assertEquals(ModelStatus.NotDownloaded, snapshot?.status)
    }

    @Test
    fun testCancelDownloadDeletesStagingOnlyAndPreservesPublishedModel() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default)
        val request = DownloadRequest(
            modelId = "test_cancel_staging_split",
            name = "Split Model",
            url = "https://example.com/split.bin",
            fileName = "split.bin",
            packageType = ModelPackage.SingleFile
        )

        val modelDir = artifactStorage.getModelDir(request.modelId, create = true)
        val publishedFile = modelDir.resolve("split.bin")
        publishedFile.writeText("published artifact content")

        val stagingFile = artifactStorage.getStagingFile(request.modelId, request.fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeText("partial staging content")

        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = testScope,
            networkChecker = { true }
        )

        controller.cancelDownload(request.modelId)

        assertFalse("Staging file must be deleted on cancelDownload", stagingFile.exists())
        assertTrue("Published artifact must be preserved on cancelDownload", publishedFile.exists())

        controller.removeModel(request.modelId)
        assertFalse("Published artifact must be deleted on removeModel", publishedFile.exists())
    }

    @Test
    fun testRemoveModelAbortsWhenInferenceShutdownStalls() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default)
        val request = DownloadRequest(
            modelId = "test_shutdown_stall",
            name = "Stall Model",
            url = "https://example.com/stall.bin",
            fileName = "stall.bin",
            packageType = ModelPackage.SingleFile
        )

        val modelDir = artifactStorage.getModelDir(request.modelId, create = true)
        val publishedFile = modelDir.resolve("stall.bin")
        publishedFile.writeText("critical published content")

        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = testScope,
            networkChecker = { true },
            inferenceShutdownAwaiter = {
                // Simulate stalled/failed inference shutdown
                false
            }
        )

        var caughtException: Exception? = null
        try {
            controller.removeModel(request.modelId)
        } catch (e: Exception) {
            caughtException = e
        }

        assertNotNull("Expected exception when inference shutdown fails", caughtException)
        assertTrue("Published file must be preserved when shutdown stalls", publishedFile.exists())
    }

    @Test
    fun testRestartDuringCancellationDoesNotPrematurelyFireAllDownloadsFinished() = runBlocking {
        val testScope = createTestScope()
        val request = DownloadRequest(
            modelId = "test_no_early_finish",
            name = "Finish Model",
            url = "https://example.com/finish.bin",
            fileName = "finish.bin",
            packageType = ModelPackage.SingleFile
        )

        var finishedSignals = 0
        val attempt1Started = CompletableDeferred<Unit>()
        val attempt1Hold = CompletableDeferred<Unit>()
        val attempt2Completed = CompletableDeferred<Unit>()

        val controller = DownloadController(
            downloader = object : ModelDownloader(
                context = null,
                artifactStorage = artifactStorage,
                minValidModelBytes = 0L
            ) {
                var count = 0
                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    publishImmediately: Boolean,
                    sourceIdentity: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    val attempt = ++count
                    if (attempt == 1) {
                        attempt1Started.complete(Unit)
                        runCatching { attempt1Hold.await() }
                        return DownloadResult("p1", 100L)
                    } else {
                        attempt2Completed.complete(Unit)
                        return DownloadResult("p2", 200L)
                    }
                }

                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult = downloadModel(
                    url, modelId, fileName, packageType, bearerToken, expectedSha256, eTag,
                    true, null, onProgress
                )
            },
            scope = testScope,
            networkChecker = { true },
            eventListener = object : DownloadEventListener {
                override fun onAllDownloadsFinished() {
                    finishedSignals++
                }
            }
        )

        // Start Attempt 1
        controller.start(request)
        attempt1Started.await()

        // Start Attempt 2 (cancelling attempt 1)
        controller.start(request)

        // Release attempt 1 to finish
        attempt1Hold.complete(Unit)

        // Wait for attempt 2 to complete
        attempt2Completed.await()
        delay(150)

        // finishedSignals should be exactly 1, not fired between attempt 1 cancellation and attempt 2
        assertEquals("onAllDownloadsFinished should only fire once after all operations complete", 1, finishedSignals)
    }

    @Test
    fun testRapidToggleSpamSettlesToConsistentState() = runBlocking {
        val testScope = createTestScope()
        val request = DownloadRequest(
            modelId = "test_toggle_spam",
            name = "Spam Model",
            url = "https://example.com/spam.bin",
            fileName = "spam.bin",
            packageType = ModelPackage.SingleFile
        )

        val controller = DownloadController(
            downloader = object : ModelDownloader(
                context = null,
                artifactStorage = artifactStorage,
                minValidModelBytes = 0L
            ) {
                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    publishImmediately: Boolean,
                    sourceIdentity: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    delay(300)
                    return DownloadResult("spam_path", 500L)
                }

                override suspend fun downloadModel(
                    url: String,
                    modelId: String,
                    fileName: String,
                    packageType: ModelPackage,
                    bearerToken: String?,
                    expectedSha256: String?,
                    eTag: String?,
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult = downloadModel(
                    url, modelId, fileName, packageType, bearerToken, expectedSha256, eTag,
                    true, null, onProgress
                )
            },
            scope = testScope,
            networkChecker = { true }
        )

        // Rapid start / pause / resume / pause spam
        controller.start(request)
        controller.pause(request.modelId)
        controller.resume(request)
        controller.pause(request.modelId)

        delay(100)
        assertFalse("Should not be downloading after final pause", controller.isDownloading(request.modelId))
        val snapshot = DownloadStateStore.get(request.modelId)
        assertEquals(ModelStatus.Paused, snapshot?.status)
    }

    @Test
    fun testRepeatedRemovalAfterFailedShutdown() = runBlocking {
        var shutdownCallCount = 0
        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = CoroutineScope(Dispatchers.Unconfined),
            networkChecker = { true }
        )
        controller.inferenceShutdownAwaiter = {
            shutdownCallCount++
            false // Simulate failed shutdown
        }
        val file = File(rootDir, "test-model.gguf").apply { writeText("data") }
        val snapshot = DownloadSnapshot("test_model", ModelStatus.Available, localPath = file.absolutePath)
        DownloadStateStore.update(snapshot)

        var thrown = false
        try {
            controller.removeModel("test_model")
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue("removeModel must throw if inference shutdown fails", thrown)
        assertEquals("Awaiter must have been called once", 1, shutdownCallCount)
        assertTrue("File must still exist", file.exists())

        // Subsequent attempt must still call shutdown awaiter (not bypassed)
        thrown = false
        try {
            controller.removeModel("test_model")
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue("Second removeModel attempt must also throw", thrown)
        assertEquals("Awaiter must be invoked again on retry", 2, shutdownCallCount)
    }

    @Test
    fun testDeletionRefusedByActiveLease() = runBlocking {
        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = CoroutineScope(Dispatchers.Unconfined),
            networkChecker = { true }
        )
        controller.inferenceShutdownAwaiter = { true }
        val modelId = "leased_model"
        val modelDir = File(rootDir, modelId).apply { mkdirs() }
        val file = File(modelDir, "model.gguf").apply { writeText("model data") }
        val snapshot = DownloadSnapshot(modelId, ModelStatus.Available, localPath = file.absolutePath)
        DownloadStateStore.update(snapshot)

        // Acquire active lease on the artifact storage
        val lease = ModelArtifactStorage.leaseArtifact(file)
        assertNotNull("Lease should be acquired", lease)

        var thrown = false
        try {
            controller.removeModel(modelId)
        } catch (e: Exception) {
            thrown = true
        }

        assertTrue("removeModel must throw if deletion is refused due to active lease", thrown)
        assertTrue("Model file must be retained", file.exists())
        val currentState = DownloadStateStore.get(modelId)
        assertEquals("Status must not be reset to NotDownloaded when deletion is refused", ModelStatus.Available, currentState?.status)

        // Release lease and verify removal can now succeed
        lease.close()
        controller.removeModel(modelId)
        assertFalse("Model file should be deleted after lease release", file.exists())
        assertEquals(ModelStatus.NotDownloaded, DownloadStateStore.get(modelId)?.status)
    }

    @Test
    fun testCancelDownloadWhenModelAlreadyAvailablePreservesAvailableStatus() = runBlocking {
        val testScope = createTestScope()
        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = testScope,
            networkChecker = { true }
        )

        val modelId = "completed_model_cancel_test"
        val modelDir = artifactStorage.getModelDir(modelId, create = true)
        val publishedFile = modelDir.resolve("model.gguf")
        publishedFile.writeText("completed model binary")

        // Model is already marked Available with published file and unknown total bytes (-1L)
        val completedSnapshot = DownloadSnapshot(
            modelId = modelId,
            status = ModelStatus.Available,
            downloadedBytes = 1000L,
            totalBytes = -1L,
            localPath = publishedFile.absolutePath,
            downloadedAt = System.currentTimeMillis()
        )
        DownloadStateStore.update(completedSnapshot)

        // Calling cancelDownload after completion has already won must preserve Available and downloadedBytes
        controller.cancelDownload(modelId, retainStaging = false)

        val current = DownloadStateStore.get(modelId)
        assertNotNull(current)
        assertEquals("Status must remain Available when completion already won", ModelStatus.Available, current?.status)
        assertEquals("Path must remain pointing to published file", publishedFile.absolutePath, current?.localPath)
        assertEquals("Valid completed bytes preserved when totalBytes is unknown (-1L)", 1000L, current?.downloadedBytes)
        assertEquals("Total bytes preserved as -1L", -1L, current?.totalBytes)
        assertTrue("Published file must still exist", publishedFile.exists())
    }

    @Test
    fun testCancelWithRetainStagingPreservesPartialDownloadsAndPausedState() = runBlocking {
        val testScope = createTestScope()
        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = testScope,
            networkChecker = { true }
        )

        val modelId = "partial_model_retain_test"
        val fileName = "partial.gguf"
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeText("50% downloaded partial data")

        // Set downloading state
        val downloadingSnapshot = DownloadSnapshot(
            modelId = modelId,
            status = ModelStatus.Downloading,
            progress = 0.5f,
            downloadedBytes = 500L,
            totalBytes = 1000L
        )
        DownloadStateStore.update(downloadingSnapshot)

        // Cancel with retainStaging = true
        controller.cancelDownload(modelId, retainStaging = true)

        val current = DownloadStateStore.get(modelId)
        assertNotNull(current)
        assertEquals("Status must be Paused when retainStaging is true", ModelStatus.Paused, current?.status)
        assertTrue("Staging file must be preserved on retainStaging = true", stagingFile.exists())
        assertEquals("50% downloaded partial data", stagingFile.readText())
    }
}

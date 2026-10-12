package com.shounak.localmeshai.services

import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.utils.DownloadProgress
import com.shounak.localmeshai.utils.DownloadResult
import com.shounak.localmeshai.utils.DownloadSnapshot
import com.shounak.localmeshai.utils.DownloadStateStore
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @After
    fun tearDown() {
        DownloadController.setInstanceForTesting(null)
    }

    @Test
    fun testStartPauseAndResume() = runBlocking {
        var progressEmitted = false
        var completedEmitted = false

        val testScope = CoroutineScope(Dispatchers.Default)
        val controller = DownloadController(
            downloader = fakeDownloader,
            scope = testScope,
            networkChecker = { true }
        )

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
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    onProgress(DownloadProgress(0.5f, 500L, 1000L, 100L))
                    progressEmitted = true
                    delay(500)
                    completedEmitted = true
                    return DownloadResult(
                        localPath = artifactStorage.getModelDir(modelId).resolve(fileName).absolutePath,
                        bytesDownloaded = 1000L
                    )
                }
            },
            scope = testScope,
            networkChecker = { true }
        )

        controllerSpy.start(request)
        // Wait briefly for download to start and progress to emit
        var attempts = 0
        while (!progressEmitted && attempts++ < 20) {
            delay(50)
        }
        assertTrue("Progress should have been emitted", progressEmitted)
        assertTrue("Controller should report downloading", controllerSpy.isDownloading("test_model_1"))

        // Pause download
        controllerSpy.pause("test_model_1")
        assertFalse("Controller should not report downloading after pause", controllerSpy.isDownloading("test_model_1"))
        assertEquals(ModelStatus.Paused, DownloadStateStore.get("test_model_1")?.status)
    }

    @Test
    fun testCancelAndResumeResetsState() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default)
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
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    onProgress(DownloadProgress(0.3f, 300L, 1000L, 50L))
                    delay(1000)
                    return DownloadResult("path", 1000L)
                }
            },
            scope = testScope,
            networkChecker = { true }
        )

        controller.start(request)
        delay(100)
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
        val testScope = CoroutineScope(Dispatchers.Default)
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
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    val currentAttempt = ++attemptCount
                    if (currentAttempt == 1) {
                        attempt1Started.complete(Unit)
                        attempt1Hold.await() // block until test releases
                        return DownloadResult("stale_path", 500L)
                    } else {
                        attempt2Completed.complete(Unit)
                        return DownloadResult("new_path", 1000L)
                    }
                }
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
        val testScope = CoroutineScope(Dispatchers.Default)
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
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    val attempt = ++count
                    if (attempt == 1) {
                        attempt1Started.complete(Unit)
                        attempt1Hold.await()
                        return DownloadResult("p1", 100L)
                    } else {
                        attempt2Completed.complete(Unit)
                        return DownloadResult("p2", 200L)
                    }
                }
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
        val testScope = CoroutineScope(Dispatchers.Default)
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
                    onProgress: suspend (DownloadProgress) -> Unit
                ): DownloadResult {
                    delay(300)
                    return DownloadResult("spam_path", 500L)
                }
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
}

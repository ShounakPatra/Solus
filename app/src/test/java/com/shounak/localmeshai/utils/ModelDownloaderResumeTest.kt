package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelPackage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderResumeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var rootDir: File
    private lateinit var artifactStorage: ModelArtifactStorage
    private lateinit var downloader: ModelDownloader
    private val client = OkHttpClient.Builder().build()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        rootDir = tempFolder.newFolder("models_storage")
        artifactStorage = ModelArtifactStorage(rootDir)
        downloader = ModelDownloader(
            context = null,
            customHttpClient = client,
            artifactStorage = artifactStorage,
            minValidModelBytes = 0L
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testResumedDownloadWithValid206RangeAppendsCorrectly() = runBlocking {
        val modelId = "resume_test_model"
        val fileName = "weights.bin"
        val fullData = ByteArray(1000) { (it % 256).toByte() }

        // Setup 500 partial bytes in staging file
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(fullData.copyOfRange(0, 500))
        assertEquals(500L, stagingFile.length())

        val secondHalf = fullData.copyOfRange(500, 1000)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 500-999/1000")
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(secondHalf))
        )

        val url = server.url("/models/weights.bin").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null
        ) {}

        val request = server.takeRequest()
        assertEquals("bytes=500-", request.getHeader("Range"))

        val publishedFile = File(result.localPath)
        assertTrue(publishedFile.exists())
        assertEquals(1000L, publishedFile.length())
        assertArrayEquals(fullData, publishedFile.readBytes())
    }

    @Test
    fun testWrongRange206RestartsFromZero() = runBlocking {
        val modelId = "wrong_range_model"
        val fileName = "model.bin"
        val fullData = ByteArray(1000) { (it % 256).toByte() }

        // Staging file has 500 bytes
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(fullData.copyOfRange(0, 500))

        // First response returns 206 with mismatched start (e.g. bytes 0-999/1000 instead of 500)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999/1000")
                .setBody(Buffer().write(fullData))
        )
        // Second response for the retry from 0
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(fullData))
        )

        val url = server.url("/models/model.bin").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null
        ) {}

        val publishedFile = File(result.localPath)
        assertTrue(publishedFile.exists())
        assertEquals(1000L, publishedFile.length())
        assertArrayEquals(fullData, publishedFile.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun testChangedValidatorIfRangeReturns200RestartsFromZero() = runBlocking {
        val modelId = "if_range_model"
        val fileName = "model.bin"
        val newData = ByteArray(1000) { 0x42.toByte() }

        // Staging file has stale partial data
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(ByteArray(500) { 0x11.toByte() })

        // Server returns 200 OK because If-Range validator did not match
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"new-etag\"")
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(newData))
        )

        val url = server.url("/models/model.bin").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null,
            eTag = "\"old-etag\""
        ) {}

        val request = server.takeRequest()
        assertEquals("\"old-etag\"", request.getHeader("If-Range"))
        assertEquals("bytes=500-", request.getHeader("Range"))

        val publishedFile = File(result.localPath)
        assertTrue(publishedFile.exists())
        assertEquals(1000L, publishedFile.length())
        assertArrayEquals(newData, publishedFile.readBytes())
    }

    @Test
    fun testResponse416WhenFileAlreadyCompletePublishesSuccessfully() = runBlocking {
        val modelId = "already_complete_model"
        val fileName = "complete.bin"
        val fullData = ByteArray(1000) { (it % 256).toByte() }

        // Staging file already has all 1000 bytes
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(fullData)

        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */1000")
        )

        val url = server.url("/models/complete.bin").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null
        ) {}

        val publishedFile = File(result.localPath)
        assertTrue(publishedFile.exists())
        assertEquals(1000L, publishedFile.length())
        assertArrayEquals(fullData, publishedFile.readBytes())
    }

    @Test
    fun testResponse416WhenFileCorruptRestartsFromZero() = runBlocking {
        val modelId = "corrupt_416_model"
        val fileName = "model.bin"
        val fullData = ByteArray(1000) { (it % 256).toByte() }

        // Staging file has 1200 bytes (corrupt/oversized)
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(ByteArray(1200))

        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */1000")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(fullData))
        )

        val url = server.url("/models/model.bin").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null
        ) {}

        val publishedFile = File(result.localPath)
        assertTrue(publishedFile.exists())
        assertEquals(1000L, publishedFile.length())
        assertArrayEquals(fullData, publishedFile.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun testStagedZipExtractionAndPublication() = runBlocking {
        val modelId = "zip_test_model"
        val fileName = "bundle.zip"

        // Build a valid ZIP in memory
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("config.json"))
            zos.write("{\"model\": \"test\"}".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("model.task"))
            zos.write(ByteArray(500) { 0x33.toByte() })
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/zip")
                .setBody(Buffer().write(zipBytes))
        )

        val url = server.url("/models/bundle.zip").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.ZipDirectory,
            bearerToken = null
        ) {}

        val publishedRunnable = File(result.localPath)
        assertTrue(publishedRunnable.exists())
        assertEquals("model.task", publishedRunnable.name)
        val bundleDir = publishedRunnable.parentFile
        assertNotNull(bundleDir)
        assertTrue(bundleDir!!.resolve("config.json").exists())
        assertTrue(bundleDir.resolve("model.task").exists())

        // Ensure staging was cleaned up
        val stagingDir = artifactStorage.getStagingDir(modelId)
        assertFalse("Staging directory should be cleaned up after publication", stagingDir.exists())

        // Manifest must be present
        val manifest = artifactStorage.getManifest(modelId)
        assertNotNull(manifest)
        assertEquals(modelId, manifest?.modelId)
    }

    @Test
    fun testResumeMetadataCapturedAndPersistedAcrossDownloaderRecreation(): Unit = runBlocking {
        val modelId = "meta_recreation_model"
        val fileName = "model.bin"
        val fullData = ByteArray(1000) { (it % 256).toByte() }
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)

        val url = server.url("/models/model.bin").toString()
        val normalizedUrl = ModelDownloadUrlPolicy.normalizeUrl(url)

        // Save metadata simulating an interrupted download
        DownloadResumeMetadata.saveFor(
            stagingFile,
            DownloadResumeMetadata(
                normalizedUrl = normalizedUrl,
                packageType = ModelPackage.SingleFile,
                validator = "\"etag-stable-123\"",
                expectedTotalBytes = 1000L,
                lastDownloadedBytes = 400L
            )
        )
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(fullData.copyOfRange(0, 400))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"etag-stable-123\"")
                .setHeader("Content-Range", "bytes 400-999/1000")
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(fullData.copyOfRange(400, 1000)))
        )

        val newDownloader = ModelDownloader(
            context = null,
            customHttpClient = client,
            artifactStorage = artifactStorage,
            minValidModelBytes = 0L
        )

        val result = newDownloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null
        ) {}

        val request = server.takeRequest()
        assertEquals("bytes=400-", request.getHeader("Range"))
        assertEquals("\"etag-stable-123\"", request.getHeader("If-Range"))

        val published = File(result.localPath)
        assertTrue(published.exists())
        assertEquals(1000L, published.length())
        assertArrayEquals(fullData, published.readBytes())
        assertNull(DownloadResumeMetadata.loadFor(stagingFile))
    }

    @Test
    fun testChangedUrlDiscardsStalePartialFileAndMetadata(): Unit = runBlocking {
        val modelId = "changed_url_model"
        val fileName = "model.bin"
        val fullData = ByteArray(1000) { 0x55.toByte() }
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(ByteArray(400) { 0x11.toByte() })

        DownloadResumeMetadata.saveFor(
            stagingFile,
            DownloadResumeMetadata(
                normalizedUrl = "https://example.com/other-model.bin",
                packageType = ModelPackage.SingleFile,
                validator = "\"etag-old\"",
                expectedTotalBytes = 1000L,
                lastDownloadedBytes = 400L
            )
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(fullData))
        )

        val url = server.url("/models/model.bin").toString()
        val result = downloader.downloadModel(
            url = url,
            modelId = modelId,
            fileName = fileName,
            packageType = ModelPackage.SingleFile,
            bearerToken = null
        ) {}

        val request = server.takeRequest()
        assertNull(request.getHeader("Range"))

        val published = File(result.localPath)
        assertTrue(published.exists())
        assertEquals(1000L, published.length())
        assertArrayEquals(fullData, published.readBytes())
    }

    @Test
    fun testRepeatedMalformed206FailsCleanlyWithoutInfiniteRecursion(): Unit = runBlocking {
        val modelId = "repeated_malformed_model"
        val fileName = "model.bin"
        val stagingFile = artifactStorage.getStagingFile(modelId, fileName)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes(ByteArray(500) { 0x11.toByte() })

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999/1000")
                .setBody(Buffer().write(ByteArray(1000)))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 999-500/1000")
                .setBody(Buffer().write(ByteArray(100)))
        )

        val url = server.url("/models/model.bin").toString()
        try {
            downloader.downloadModel(
                url = url,
                modelId = modelId,
                fileName = fileName,
                packageType = ModelPackage.SingleFile,
                bearerToken = null
            ) {}
            org.junit.Assert.fail("Expected failure on repeated malformed responses")
        } catch (e: Exception) {
            assertTrue(e is java.io.IOException)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun testShortResponseThrowsIOExceptionWithoutPublishing(): Unit = runBlocking {
        val modelId = "short_resp_model"
        val fileName = "model.bin"

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999/1000")
                .setBody(Buffer().write(ByteArray(400)))
        )

        val url = server.url("/models/model.bin").toString()
        try {
            downloader.downloadModel(
                url = url,
                modelId = modelId,
                fileName = fileName,
                packageType = ModelPackage.SingleFile,
                bearerToken = null
            ) {}
            org.junit.Assert.fail("Expected IOException for short response")
        } catch (e: Exception) {
            assertTrue(e is java.io.IOException)
        }

        assertNull(artifactStorage.getPublishedArtifact(modelId))
    }

    @Test
    fun testCancellationPromptlyInterruptsDownload(): Unit = runBlocking {
        val modelId = "cancellation_test_model"
        val fileName = "model.bin"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "1000000")
                .throttleBody(1024, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody(Buffer().write(ByteArray(1000000)))
        )

        val url = server.url("/models/model.bin").toString()
        val job = launch(Dispatchers.IO) {
            downloader.downloadModel(
                url = url,
                modelId = modelId,
                fileName = fileName,
                packageType = ModelPackage.SingleFile,
                bearerToken = null
            ) {}
        }

        delay(200)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(artifactStorage.getPublishedArtifact(modelId))
    }
}

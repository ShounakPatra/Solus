package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.ui.viewmodels.ModelInputClassification
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class PortableCompanionRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun weights(): ByteArray {
        val header = """{"weight":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array() + header + ByteArray(4)
    }

    @Test fun oldWeightOnlySpecFetchesConfigurationAndBpeAlternativesAndReusesWeightsOnRetry() = runBlocking {
        val server = MockWebServer()
        val weightRequests = AtomicInteger()
        var tokenizerAvailable = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/model.safetensors" -> {
                    weightRequests.incrementAndGet()
                    MockResponse().setBody(Buffer().write(weights()))
                }
                "/config.json" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"model_type":"gpt2","eos_token_id":2}""")
                "/vocab.json" -> if (tokenizerAvailable) MockResponse().setBody("""{"a":0}""") else MockResponse().setResponseCode(404)
                "/merges.txt" -> if (tokenizerAvailable) MockResponse().setBody("#version: 0.2\na b") else MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val storage = ModelArtifactStorage(temporary.newFolder())
            val downloader = ModelDownloader(customHttpClient = OkHttpClient(), artifactStorage = storage)
            val spec = ModelDownloadSpec("repair", artifacts = listOf(DownloadArtifactSpec(server.url("/model.safetensors").toString(), "model.safetensors")), format = ModelFormat.Safetensors)
            try {
                downloader.downloadArtifactBundle(spec, null) {}
                fail("Missing tokenizer must not be published as runnable")
            } catch (e: MissingModelFilesException) {
                assertTrue(PortableCompanionFiles.isMissing(e.message))
                assertEquals("Missing files", ErrorClassifier.classify(e.message)!!.headline)
            }
            assertNull(storage.getPublishedBundle("repair"))
            assertTrue(storage.getStagingFile("repair", "model.safetensors").isFile)
            tokenizerAvailable = true
            val result = downloader.downloadArtifactBundle(spec, null) {}
            assertEquals(1, weightRequests.get())
            assertTrue(File(File(result.localPath).parentFile, "config.json").isFile)
            assertTrue(File(File(result.localPath).parentFile, "vocab.json").isFile)
            assertNull(storage.getPublishedBundle("repair")!!.loadRestriction)
        } finally { server.shutdown() }
    }

    @Test fun optionalCompanionsDoNotHideAuthenticationErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(weights())))
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val downloader = ModelDownloader(customHttpClient = OkHttpClient(), artifactStorage = ModelArtifactStorage(temporary.newFolder()))
            val spec = HuggingFaceModelResolver.createDirectPortableSpec(server.url("/model.safetensors").toString(), "auth")
            try {
                downloader.downloadArtifactBundle(spec, null) {}
                fail("403 must remain an authentication failure")
            } catch (e: ModelDownloadHttpException) { assertEquals(403, e.statusCode) }
        } finally { server.shutdown() }
    }

    @Test fun irrelevantProcessorFailureDoesNotPreventPublishingTextModel() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/model.safetensors" -> MockResponse().setBody(Buffer().write(weights()))
                "/config.json" -> MockResponse().setBody("""{"model_type":"gpt2","eos_token_id":2}""")
                "/tokenizer.json" -> MockResponse().setBody("""{"model":{"vocab":{"a":0}}}""")
                "/processor_config.json", "/preprocessor_config.json" -> MockResponse().setResponseCode(500)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val storage = ModelArtifactStorage(temporary.newFolder())
            val downloader = ModelDownloader(customHttpClient = OkHttpClient(), artifactStorage = storage)
            downloader.downloadArtifactBundle(HuggingFaceModelResolver.createDirectPortableSpec(server.url("/model.safetensors").toString(), "extras"), null) {}
            assertNotNull(storage.getPublishedBundle("extras"))
        } finally { server.shutdown() }
    }

    @Test fun absentConfigurationIsAMissingFileAndKeepsTheCompletedTransfer() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/model.safetensors")
                MockResponse().setBody(Buffer().write(weights())) else MockResponse().setResponseCode(404)
        }
        server.start()
        try {
            val storage = ModelArtifactStorage(temporary.newFolder())
            val downloader = ModelDownloader(customHttpClient = OkHttpClient(), artifactStorage = storage)
            val spec = HuggingFaceModelResolver.createDirectPortableSpec(server.url("/model.safetensors").toString(), "missing-config")
            try {
                downloader.downloadArtifactBundle(spec, null) {}
                fail("Absent config must not masquerade as a failed weight transfer")
            } catch (e: MissingModelFilesException) { assertTrue(e.message!!.contains("config.json")) }
            assertArrayEquals(weights(), storage.getStagingFile("missing-config", "model.safetensors").readBytes())
            assertNull(storage.getPublishedBundle("missing-config"))
        } finally { server.shutdown() }
    }

    @Test fun optionalFlagSurvivesSavedSpecsAndLegacySpecsRemainRequired() {
        val artifact = DownloadArtifactSpec("https://example.com/config.json", "config.json", optional = true)
        assertTrue(DownloadArtifactSpec.fromJson(artifact.toJson()).optional)
        assertFalse(DownloadArtifactSpec.fromJson(artifact.toJson().apply { remove("optional") }).optional)
        val old = ModelDownloadSpec("legacy", format = ModelFormat.Safetensors, artifacts = listOf(
            DownloadArtifactSpec("https://example.com/model.safetensors", "model.safetensors"),
            DownloadArtifactSpec("https://example.com/tokenizer.json", "tokenizer.json")))
        val repaired = PortableCompanionFiles.complete(old)
        assertTrue(repaired.single { it.fileName == "tokenizer.json" }.optional)
        assertFalse(repaired.first().optional)
        assertEquals(repaired.size, repaired.distinctBy { it.fileName }.size)
    }

    @Test fun completeTransfersAndMissingCompanionsHaveAccurateLabels() {
        val model = ModelInfo("test", "Test", "1 GB", ModelStatus.Failed, ModelType.Text, "model.safetensors",
            description = "", backend = "Candle CPU", deviceTarget = "CPU", downloadedBytes = 123, progress = 1f)
        assertEquals("Model validation failed", model.failureLabel)
        assertEquals("Missing files", model.copy(errorMessage = "Missing tokenizer.json or BPE vocab.json and merges.txt").failureLabel)
        assertEquals("Download Failed", model.copy(progress = .5f).failureLabel)
        assertEquals("Missing files", model.copy(errorMessage = MissingModelFilesException("config.json").message).failureLabel)
    }

    @Test fun unsupportedExtensionsAreRejectedBeforeVariantSelection() {
        for (name in listOf("pytorch_model.bin", "model.PTH", "model.ckpt")) {
            val input = HuggingFaceModelResolver.parseInput("owner/repo/$name")
            assertEquals(HfInputKind.FILE, input.kind)
            try {
                HuggingFaceModelResolver.selectVariants(input, emptyList())
                fail("Unsupported file should be rejected")
            } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains(".safetensors version")) }
        }
        for (extension in listOf("onnx", "safetensors")) {
            assertEquals(ModelInputClassification.DirectDownload, MainViewModel.classifyModelInput("https://example.com/model.$extension"))
        }
    }
}

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

class PortableLinkImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = """{"model_type":"qwen2","max_position_embeddings":1024,"eos_token_id":2}"""
    private val tokenizer = """{"model":{"vocab":{"a":0}}}"""
    private fun repo(files: List<RepoFile>) = ResolvedRepoInfo("owner/repo", "main", "immutable-sha", files)
    private fun weights(): ByteArray {
        val header = """{"weight":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array() + header + ByteArray(4)
    }

    @Test fun discoversAllPortableFormatsAndKeepsTheirCompanions() {
        val info = repo(listOf("config.json", "tokenizer.json", "model.onnx", "model.onnx_data", "model.tflite", "model.safetensors").map { RepoFile(it, 10) })
        val variants = HuggingFaceModelResolver.groupVariants(info)
        assertEquals(setOf(ModelFormat.Onnx, ModelFormat.TensorFlowLite, ModelFormat.Safetensors), variants.map { it.format }.toSet())
        variants.forEach { variant ->
            val spec = HuggingFaceModelResolver.createDownloadSpec(info, variant, "test")
            assertEquals(variant.format, spec.effectiveFormat)
            assertEquals(variant.mainFiles.first().fileName, spec.artifacts.first().fileName)
            assertTrue(spec.artifacts.any { it.fileName == "config.json" })
            assertTrue(spec.artifacts.any { it.fileName == "tokenizer.json" })
            assertTrue(spec.artifacts.all { it.downloadUrl.contains("/immutable-sha/") })
        }
        assertTrue(variants.single { it.format == ModelFormat.Onnx }.companionFiles.any { it.fileName == "model.onnx_data" })
    }

    @Test fun shardsStayTogetherAndOnnxSubfoldersUseNearestMetadata() {
        val files = listOf("config.json", "tokenizer.json", "onnx/model.onnx", "onnx/config.json",
            "model-00001-of-00002.safetensors", "model-00002-of-00002.safetensors", "model.safetensors.index.json").map { RepoFile(it) }
        val variants = HuggingFaceModelResolver.groupVariants(repo(files))
        val safetensors = variants.single { it.format == ModelFormat.Safetensors }
        assertEquals(2, safetensors.mainFiles.size)
        assertTrue(safetensors.companionFiles.any { it.fileName == "model.safetensors.index.json" })
        val onnx = variants.single { it.format == ModelFormat.Onnx }
        assertEquals("onnx/config.json", onnx.companionFiles.single { it.fileName == "config.json" }.path)
        assertEquals("tokenizer.json", onnx.companionFiles.single { it.fileName == "tokenizer.json" }.path)
        val parsed = HuggingFaceModelResolver.parseInput("owner/repo/model-00002-of-00002.safetensors")
        assertEquals(HfInputKind.FILE, parsed.kind)
        assertEquals(safetensors, HuggingFaceModelResolver.selectVariants(parsed, variants).preselectedVariant)
    }

    @Test fun huggingFacePortableFileLinksUseRepositoryResolution() {
        for (extension in listOf("onnx", "tflite", "safetensors")) {
            val url = "https://huggingface.co/owner/repo/resolve/main/model.$extension?download=true"
            assertEquals(ModelInputClassification.HuggingFace, MainViewModel.classifyModelInput(url))
            assertEquals(HfInputKind.FILE, HuggingFaceModelResolver.parseInput("owner/repo/model.$extension").kind)
        }
    }

    @Test fun backendLabelsRepairOldGgufEntriesWithoutOverwritingCpuPreference() {
        for (extension in listOf("task", "litertlm", "onnx", "tflite", "safetensors")) {
            val model = ModelInfo("custom_test", "model", "Custom", ModelStatus.Downloading, ModelType.Text,
                "model.$extension", description = "", backend = "llama.cpp GGUF", deviceTarget = "Test")
            assertEquals(ModelFormat.fromFileName(model.fileName)!!.backendLabel, model.displayBackend)
        }
        val cpu = ModelInfo("cpu", "cpu", "1 GB", ModelStatus.Available, ModelType.Text,
            "model.litertlm", description = "", backend = "LiteRT-LM CPU", deviceTarget = "Test")
        assertEquals("LiteRT-LM CPU", cpu.displayBackend)
    }

    @Test fun downloadsJsonCompanionsAndPublishesEachPortableFormat() = runBlocking {
        for ((index, format) in listOf(ModelFormat.Safetensors, ModelFormat.TensorFlowLite, ModelFormat.Safetensors).withIndex()) {
            val extension = if (format == ModelFormat.Safetensors) "safetensors" else "tflite"
            val bytes = if (format == ModelFormat.Safetensors) weights() else byteArrayOf(16,0,0,0) + "TFL3".toByteArray() + ByteArray(32)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                    "/model.$extension" -> MockResponse().setBody(Buffer().write(bytes))
                    "/config.json" -> MockResponse().setHeader("Content-Type", "application/json").setBody(if (index == 2) config.replace("qwen2", "gpt2") else config)
                    "/tokenizer.json" -> MockResponse().setHeader("Content-Type", "application/json").setBody(tokenizer)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                val storage = ModelArtifactStorage(temporary.newFolder())
                val downloader = ModelDownloader(customHttpClient = OkHttpClient(), artifactStorage = storage)
                val spec = HuggingFaceModelResolver.createDirectPortableSpec(server.url("/model.$extension").toString(), "portable")
                val result = downloader.downloadArtifactBundle(spec, bearerToken = null) {}
                val published = requireNotNull(storage.getPublishedBundle("portable"))
                assertEquals(format, published.format)
                assertEquals("model.$extension", File(result.localPath).name)
                assertEquals(3, published.mainArtifacts.size)
                assertTrue(File(File(result.localPath).parentFile, "config.json").isFile)
                assertTrue(PortableModelBundle.inspect(File(result.localPath), format).supportsText)
                assertNull(published.loadRestriction)
            } finally { server.shutdown() }
        }
    }

    @Test fun onnxExternalDataIsDiscoveredFromTheDownloadedGraph(): Unit = runBlocking {
        fun field(number: Int, value: ByteArray) = byteArrayOf((number * 8 + 2).toByte(), value.size.toByte()) + value
        fun entry(key: String, value: String) = field(13, field(1, key.toByteArray()) + field(2, value.toByteArray()))
        val tensor = entry("location", "external.weights") + entry("length", "4") + byteArrayOf(112, 1)
        val graph = byteArrayOf(8, 10) + field(7, field(5, tensor))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/model.onnx" -> MockResponse().setBody(Buffer().write(graph))
                "/config.json" -> MockResponse().setHeader("Content-Type", "application/json").setBody(config)
                "/tokenizer.json" -> MockResponse().setHeader("Content-Type", "application/json").setBody(tokenizer)
                "/external.weights" -> MockResponse().setBody(Buffer().write(ByteArray(4)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val storage = ModelArtifactStorage(temporary.newFolder())
            val downloader = ModelDownloader(customHttpClient = OkHttpClient(), artifactStorage = storage)
            val spec = HuggingFaceModelResolver.createDirectPortableSpec(server.url("/model.onnx").toString(), "onnx")
            val result = downloader.downloadArtifactBundle(spec, bearerToken = null) {}
            assertEquals(4, requireNotNull(storage.getPublishedBundle("onnx")).mainArtifacts.size)
            OnnxBundleValidation.validate(File(result.localPath))
        } finally { server.shutdown() }
    }
}

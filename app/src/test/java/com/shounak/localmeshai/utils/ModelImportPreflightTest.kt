package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelImportPreflightTest {
    private fun weights(): ByteArray {
        val header = """{"w":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array() + header + ByteArray(4)
    }
    private fun check(format: ModelFormat, model: ByteArray, files: Map<String, String> = emptyMap(),
        expectedError: String? = null, optionalCompanions: Boolean = false, denyHead: Boolean = false) = runBlocking {
        val server = MockWebServer()
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests.add(request) }
                val path = request.requestUrl!!.encodedPath.removePrefix("/")
                if (path == "model.${when(format) { ModelFormat.Safetensors -> "safetensors"; ModelFormat.TensorFlowLite -> "tflite"; ModelFormat.Onnx -> "onnx"; ModelFormat.GGUF -> "gguf"; ModelFormat.LiteRtLm -> "litertlm"; else -> "task" }}") {
                    assertNotNull("Weights must only be probed using Range", request.getHeader("Range"))
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-${model.size-1}/${model.size}")
                        .setBody(Buffer().write(model))
                }
                if (denyHead && request.method == "HEAD") return MockResponse().setResponseCode(405)
                return files[path]?.let { MockResponse().setBody(if (request.method == "HEAD") "" else it) }
                    ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val extension = when(format) { ModelFormat.Safetensors -> "safetensors"; ModelFormat.TensorFlowLite -> "tflite"; ModelFormat.Onnx -> "onnx"; ModelFormat.GGUF -> "gguf"; ModelFormat.LiteRtLm -> "litertlm"; else -> "task" }
            val name = "model.$extension"
            val spec = ModelDownloadSpec("test", artifacts = listOf(DownloadArtifactSpec(server.url("/$name").toString(), name)) +
                files.keys.map { DownloadArtifactSpec(server.url("/$it").toString(), it, optional = optionalCompanions) }, format = format)
            try {
                ModelImportPreflight.check(spec, null)
                if (expectedError != null) fail("Expected $expectedError")
            } catch (failure: IllegalArgumentException) {
                if (expectedError == null) throw failure
                assertTrue(failure.message, failure.message.orEmpty().contains(expectedError))
            }
            assertTrue(requests.any { it.getHeader("Range") != null })
            assertTrue(requests.filter { it.requestUrl!!.encodedPath == "/$name" }.all { it.getHeader("Range") != null })
        } finally { server.shutdown() }
    }

    @Test fun embeddedClassifierNeedsNoConfigOrTokenizer() = check(ModelFormat.TensorFlowLite, TfliteFixture.classifier())
    @Test fun tfliteCustomKernelReportsItsRealRequirement() = check(ModelFormat.TensorFlowLite,
        TfliteFixture.text(custom = true), expectedError = "custom operators")
    @Test fun rawGenAiCacheExportReportsAdapterBeforeLookingForConfig() = check(ModelFormat.TensorFlowLite,
        TfliteFixture.cachedText(), expectedError = "external KV-cache inputs")
    @Test fun unlabelledTextTfliteExplainsPackagingInsteadOfMandatoryConfig() = check(ModelFormat.TensorFlowLite,
        TfliteFixture.text(), expectedError = "packaged .task/.litertlm")
    @Test fun supportedSafetensorsNeedsItsTokenizerBeforeWeightsDownload() = check(ModelFormat.Safetensors, weights(),
        mapOf("config.json" to """{"model_type":"llama","eos_token_id":2}"""), "tokenizer.json")
    @Test fun unsupportedArchitectureIsDetectedBeforeFetchingWeights() = check(ModelFormat.Safetensors, weights(),
        mapOf("config.json" to """{"model_type":"openelm","architectures":["OpenELMForCausalLM"]}"""), "needs its own inference adapter")
    @Test fun supportedTextBundlePasses() = check(ModelFormat.Safetensors, weights(),
        mapOf("config.json" to """{"model_type":"llama","eos_token_id":2}""", "tokenizer.json" to "{}"))
    @Test fun directServerWithoutHeadUsesBoundedGetForRequiredCompanions() = check(ModelFormat.Safetensors, weights(),
        mapOf("config.json" to """{"model_type":"llama","eos_token_id":2}""", "tokenizer.json" to "{}"),
        optionalCompanions = true, denyHead = true)
    @Test fun packagedFormatsNeedNoHuggingFaceConfig() {
        check(ModelFormat.GGUF, "GGUFtest".toByteArray())
        check(ModelFormat.LiteRtLm, "LITERTLM".toByteArray())
        check(ModelFormat.MediaPipeTask, byteArrayOf(0,0,0,0,80,75,3,4))
    }
    @Test fun renamedWebPagesAreRejected() = check(ModelFormat.LiteRtLm, "<html>not a model".toByteArray(), expectedError = "not a LiteRtLm")
}

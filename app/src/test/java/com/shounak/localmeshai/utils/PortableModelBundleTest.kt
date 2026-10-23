package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PortableModelBundleTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun companions(dir: File, type: String = "qwen2") {
        File(dir, "config.json").writeText("""{"model_type":"$type","max_position_embeddings":4096,"eos_token_id":[2,3]}""")
        File(dir, "tokenizer.json").writeText("""{"model":{"vocab":{"a":0}}}""")
    }
    private fun weights(file: File): File = file.apply {
        val header = """{"weight":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".toByteArray()
        writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array() + header + ByteArray(4))
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected an invalid bundle to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun qwenSafetensorsHasOnlyVerifiedTextCapabilities() {
        val dir = temporary.newFolder(); companions(dir)
        val file = weights(File(dir, "model.safetensors"))
        val caps = PortableModelBundle.inspect(file, ModelFormat.Safetensors)
        assertTrue(caps.supportsText); assertFalse(caps.supportsVision); assertFalse(caps.supportsAudio)
        assertEquals(4096, caps.contextLength)
        assertEquals(ModelFormat.Safetensors, NonGgufBundleInspector.detectFormat(file))
    }

    @Test fun missingTokenizerDoesNotBecomeRunnableFromExtension() {
        val dir = temporary.newFolder(); companions(dir)
        File(dir, "tokenizer.json").delete()
        rejected { PortableModelBundle.inspect(weights(File(dir, "model.safetensors")), ModelFormat.Safetensors) }
    }

    @Test fun missingShardAndTraversalAreRejected() {
        val dir = temporary.newFolder(); companions(dir)
        val primary = weights(File(dir, "model-00001-of-00002.safetensors"))
        val index = File(dir, "model.safetensors.index.json")
        index.writeText("""{"weight_map":{"a":"${primary.name}","b":"missing.safetensors"}}""")
        rejected { PortableModelBundle.inspect(primary, ModelFormat.Safetensors) }
        index.writeText("""{"weight_map":{"a":"../outside.safetensors"}}""")
        rejected { PortableModelBundle.inspect(primary, ModelFormat.Safetensors) }
    }

    @Test fun unsupportedArchitectureAndQuantizationAreImportedWithRuntimeRestriction() {
        val dir = temporary.newFolder(); companions(dir, "bert")
        val primary = weights(File(dir, "model.safetensors"))
        assertTrue(PortableModelBundle.inspect(primary, ModelFormat.Safetensors).supportsText)
        assertEquals(LoadRestrictionCategory.RuntimeIncompatibility, PortableModelBundle.runtimeRestriction(primary, ModelFormat.Safetensors)?.category)
        companions(dir)
        val config = File(dir, "config.json")
        config.writeText(JSONObject(config.readText()).put("quantization_config", JSONObject()).toString())
        assertTrue(PortableModelBundle.inspect(primary, ModelFormat.Safetensors).supportsText)
        assertTrue(PortableModelBundle.runtimeRestriction(primary, ModelFormat.Safetensors)!!.reason.contains("prequantized"))
    }

    @Test fun truncatedAndOversizedSafetensorsHeadersAreRejected() {
        val file = weights(temporary.newFile("model.safetensors"))
        file.writeBytes(file.readBytes().dropLast(1).toByteArray())
        rejected { PortableModelBundle.validateSafetensors(file) }
        file.writeBytes(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MAX_VALUE).array())
        rejected { PortableModelBundle.validateSafetensors(file) }
    }

    @Test fun rawTfliteIsDistinctFromLiteRtLmAndMustHaveCompanions() {
        val dir = temporary.newFolder(); companions(dir)
        val file = File(dir, "model.tflite").apply { writeBytes(TfliteFixture.text()) }
        assertEquals(ModelFormat.TensorFlowLite, NonGgufBundleInspector.detectFormat(file))
        assertTrue(PortableModelBundle.inspect(file, ModelFormat.TensorFlowLite).supportsText)
        val renamed = File(dir, "raw.litertlm").apply { writeBytes(file.readBytes()) }
        assertNull(NonGgufBundleInspector.detectFormat(renamed))
    }

    @Test fun allFormatsAndCompanionsSurvivePublicationAndRestore() {
        for (format in listOf(ModelFormat.Onnx, ModelFormat.TensorFlowLite, ModelFormat.Safetensors)) {
            val root = temporary.newFolder()
            val storage = ModelArtifactStorage(root)
            val staging = storage.getStagingDir("portable").apply { mkdirs() }
            val names = listOf("model." + when (format) { ModelFormat.Onnx -> "onnx"; ModelFormat.TensorFlowLite -> "tflite"; else -> "safetensors" }, "config.json", "tokenizer.json")
            val items = names.mapIndexed { index, name ->
                StagedArtifactItem(File(staging, name).apply { writeText("test content") }, name, isPrimary = index == 0, order = index)
            }
            val transaction = storage.beginTransaction("portable", items, names.first(), "test", format = format)
            transaction.commit()
            val restored = requireNotNull(storage.getPublishedBundle("portable"))
            assertEquals(format, restored.format)
            assertEquals(names, restored.mainArtifacts.map { it.displayName })
            assertFalse(restored.isSplit)
            assertEquals(format.backendLabel, restored.backend)
            assertEquals(format, ModelBundle.fromJson(restored.toJson()).format)
        }
    }
}

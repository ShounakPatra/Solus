package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OnnxBundleValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun integer(value: Int): ByteArray {
        var remaining = value
        val result = mutableListOf<Byte>()
        do { result.add(((remaining and 127) or if (remaining > 127) 128 else 0).toByte()); remaining = remaining ushr 7 } while (remaining > 0)
        return result.toByteArray()
    }
    private fun field(number: Int, payload: ByteArray) = integer(number * 8 + 2) + integer(payload.size) + payload
    private fun entry(key: String, value: String) = field(13, field(1, key.toByteArray()) + field(2, value.toByteArray()))
    private fun model(location: String, length: String = "4"): File {
        val tensor = entry("location", location) + entry("offset", "0") + entry("length", length) + byteArrayOf(112, 1)
        return File(folder.root, "model.onnx").apply { writeBytes(byteArrayOf(8, 10) + field(7, field(5, tensor))) }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid external weights to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun completeExternalWeightsAreAccepted() {
        File(folder.root, "weights.data").writeBytes(ByteArray(4))
        OnnxBundleValidation.validate(model("weights.data"))
    }
    @Test fun nestedExternalWeightsResolveRelativeToEachLogicalGraph() {
        val first = folder.newFile("staged-unet.data").apply { writeBytes(ByteArray(4)) }
        val second = folder.newFile("staged-vae.data").apply { writeBytes(ByteArray(2)) }
        val graph = model("weights.data")
        val files = mapOf("unet/weights.data" to first, "vae_decoder/weights.data" to second)
        assertEquals(setOf("weights.data"), OnnxBundleValidation.validate(graph, files, logicalPath="unet/model.onnx"))
        rejected { OnnxBundleValidation.validate(graph, files, logicalPath="vae_decoder/model.onnx") }
        val nested = folder.newFile("staged-nested.data").apply { writeBytes(ByteArray(4)) }
        OnnxBundleValidation.validate(model("data/weights.data"), mapOf("unet/data/weights.data" to nested), logicalPath="unet/model.onnx")
    }
    @Test fun missingOrTruncatedExternalWeightsAreRejected() {
        rejected { OnnxBundleValidation.validate(model("weights.data")) }
        File(folder.root, "weights.data").writeBytes(ByteArray(3))
        rejected { OnnxBundleValidation.validate(model("weights.data")) }
    }
    @Test fun externalWeightsCannotEscapeTheGeneration() {
        for (name in listOf("../secret", "C:\\secret", "/secret", "sub/weights", "..")) {
            rejected { OnnxBundleValidation.validate(model(name)) }
        }
    }
    @Test fun protobufFieldOrderDoesNotDefineTheFormat() {
        val file = folder.newFile("ordered.onnx").apply { writeBytes(field(7, byteArrayOf()) + field(2, "producer".toByteArray()) + byteArrayOf(8, 10)) }
        OnnxBundleValidation.validate(file)
        assertEquals(com.shounak.localmeshai.models.ModelFormat.Onnx, NonGgufBundleInspector.detectFormat(file))
    }
    @Test fun malformedLengthDoesNotReadBeyondTheModel() {
        val file = folder.newFile("truncated.onnx").apply { writeBytes(byteArrayOf(8, 10, 58, 127, 1)) }
        rejected { OnnxBundleValidation.validate(file) }
    }
}

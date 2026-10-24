package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelTask
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class OnnxExportInspectorTest {
    private fun number(value: Int): ByteArray {
        var remaining = value
        return ByteArrayOutputStream().apply {
            do { val next = remaining and 127; remaining = remaining ushr 7; write(next or if (remaining > 0) 128 else 0) } while (remaining > 0)
        }.toByteArray()
    }
    private fun field(id: Int, bytes: ByteArray) = number(id*8+2) + number(bytes.size) + bytes
    private fun scalar(id: Int, value: Int) = number(id*8) + number(value)
    private fun tensor(name: String, fixed: Int = -1) = field(1, name.toByteArray()) + field(2,
        field(1, scalar(1, 7) + field(2, field(1, scalar(1,1)) + field(1,
            if (fixed < 0) field(2,"sequence".toByteArray()) else scalar(1,fixed)))))
    private fun model(fixed: Int = -1, weight: ByteArray = byteArrayOf()) = scalar(1,8) + field(7,
        field(11,tensor("input_ids",fixed)) + field(12,tensor("logits")) + weight)
    private fun inspect(bytes: ByteArray) = OnnxExportInspector.inspect(bytes.size.toLong()) { at, count ->
        bytes.copyOfRange(at.toInt(),at.toInt()+count)
    }
    @Test fun dynamicDecoderSignaturePassesAndFixedSequenceRequiresAdapter() {
        inspect(model()).check(ModelTask.Chat)
        try { inspect(model(128)).check(ModelTask.Chat); fail() }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("padding/cache")) }
    }
    @Test fun weightPayloadsAreSkippedAndExternalReferencesAreReported() {
        val entry = field(1,"location".toByteArray()) + field(2,"weights.bin".toByteArray())
        val bytes = model(weight = field(5, field(9,ByteArray(262144)) + field(13,entry)))
        var readBytes = 0
        val export = OnnxExportInspector.inspect(bytes.size.toLong()) { at, count ->
            readBytes += count; bytes.copyOfRange(at.toInt(),at.toInt()+count)
        }
        assertEquals(setOf("weights.bin"),export.external)
        assertTrue("Weight payload was read", readBytes < 1024)
    }
    @Test fun traversalInExternalDataAndTruncationAreRejected() {
        val entry = field(1,"location".toByteArray()) + field(2,"../weights.bin".toByteArray())
        listOf(model(weight = field(5,field(13,entry))), model().dropLast(1).toByteArray()).forEach { bytes ->
            try { inspect(bytes); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}

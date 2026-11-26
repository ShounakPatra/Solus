package com.shounak.localmeshai.ai.image

import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelTask
import com.shounak.localmeshai.models.state.T2iRamGate
import com.shounak.localmeshai.models.state.T2iRamPolicy
import com.shounak.localmeshai.utils.OnnxBundleValidation
import com.shounak.localmeshai.utils.PortableModelBundle
import org.junit.Assert.assertArrayEquals
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
import kotlin.math.sqrt

class OnnxImageGeneratorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `seeded Gaussian noise is deterministic finite and matches scale`() {
        val n1 = OnnxImageGenerator.generateGaussianNoise(count = 16_384, seed = 42L, scale = 2.0f)
        val n2 = OnnxImageGenerator.generateGaussianNoise(count = 16_384, seed = 42L, scale = 2.0f)
        val n3 = OnnxImageGenerator.generateGaussianNoise(count = 16_384, seed = 99L, scale = 2.0f)

        assertArrayEquals(n1, n2, 0f)
        assertFalse(n1.contentEquals(n3))
        assertTrue(n1.all { it.isFinite() })

        val mean = n1.average()
        val variance = n1.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)
        assertEquals(0.0, mean, 0.1)
        assertEquals(2.0, std, 0.12)
    }

    @Test
    fun `nchw float tensor converts normalized values and clamps out-of-range floats cleanly`() {
        // 2x2 image in NCHW: R=[ -1f, 0f, 1f, -5f ], G=[ 0f, 1f, -1f, 5f ], B=[ 1f, -1f, 0f, Float.NaN ]
        val nchw = floatArrayOf(
            -1f, 0f, 1f, -5f,
            0f, 1f, -1f, 5f,
            1f, -1f, 0f, Float.NaN
        )
        val pixels = OnnxImageGenerator.nchwFloatToArgbPixels(nchw, width = 2, height = 2)
        assertEquals(4, pixels.size)
        // (-1f->0, 0f->128, 1f->255)
        assertEquals(0xFF0080FF.toInt(), pixels[0])
        // (0f->128, 1f->255, -1f->0)
        assertEquals(0xFF80FF00.toInt(), pixels[1])
        // (1f->255, -1f->0, 0f->128)
        assertEquals(0xFFFF0080.toInt(), pixels[2])
        // (-5f->0, 5f->255, NaN->0)
        assertEquals(0xFF00FF00.toInt(), pixels[3])
    }

    @Test
    fun `RAM gate enforces 7_2 GiB threshold and 10 GiB chat auto-unload policy`() {
        val low6 = T2iRamGate.evaluate(totalRamGiB = 5.8, availableRamMb = 2500L)
        assertEquals(T2iRamPolicy.UnsupportedLowRam, low6.policy)
        assertFalse(low6.isSupported)
        assertNotNull(low6.warningMessage)

        val borderlineLow = T2iRamGate.evaluate(totalRamGiB = 7.19, availableRamMb = 3500L)
        assertFalse(borderlineLow.isSupported)

        val phone8Gb = T2iRamGate.evaluate(totalRamGiB = 7.45, availableRamMb = 3200L)
        assertEquals(T2iRamPolicy.UnloadChatBeforeT2i, phone8Gb.policy)
        assertTrue(phone8Gb.isSupported)
        assertTrue(phone8Gb.requiresChatUnload)
        assertNull(phone8Gb.warningMessage)

        val phone10Gb = T2iRamGate.evaluate(totalRamGiB = 10.0, availableRamMb = 5000L)
        assertEquals(T2iRamPolicy.UnloadChatBeforeT2i, phone10Gb.policy)
        assertTrue(phone10Gb.requiresChatUnload)

        val phone12Gb = T2iRamGate.evaluate(totalRamGiB = 11.6, availableRamMb = 6400L)
        assertEquals(T2iRamPolicy.KeepChatLoaded, phone12Gb.policy)
        assertTrue(phone12Gb.isSupported)
        assertFalse(phone12Gb.requiresChatUnload)
    }

    @Test
    fun `ONNX protobuf graph inspector accepts single-graph T2I bundle without config_json`() {
        val dir = tempFolder.newFolder("minit2i")
        val onnxFile = File(dir, "minit2i.onnx").apply {
            writeBytes(buildMinimalSingleGraphT2iOnnx())
        }
        val vocabFile = File(dir, "vocab.json").apply {
            writeText("""[["<pad>",0.0],["</s>",0.0],["<unk>",0.0],["\u2581hello",-1.0]]""")
        }

        val spec = OnnxBundleValidation.inspectSingleGraphT2i(onnxFile)
        assertNotNull(spec)
        assertEquals("input_ids", spec!!.inputIdsName)
        assertEquals("attention_mask", spec.attentionMaskName)
        assertEquals("noise", spec.noiseInputName)
        assertEquals("image", spec.outputName)
        assertEquals(256, spec.sequenceLength)
        assertEquals(512, spec.imageWidth)
        assertEquals(512, spec.imageHeight)

        val companions = mapOf("vocab.json" to vocabFile)
        val caps = PortableModelBundle.inspect(onnxFile, ModelFormat.Onnx, companions, "minit2i.onnx")
        assertEquals(ModelTask.ImageGeneration, caps.task)
        assertEquals(256, caps.contextLength)
        assertNull(PortableModelBundle.runtimeRestriction(onnxFile, ModelFormat.Onnx, companions))
    }

    private fun buildMinimalSingleGraphT2iOnnx(): ByteArray {
        val inputIds = valueInfo("input_ids", elemType = 7, dims = listOf(1L, 256L))
        val attentionMask = valueInfo("attention_mask", elemType = 7, dims = listOf(1L, 256L))
        val noise = valueInfo("noise", elemType = 1, dims = listOf(1L, 3L, 512L, 512L))
        val imageOut = valueInfo("image", elemType = 1, dims = listOf(1L, 3L, 512L, 512L))

        val graph = ByteArrayOutputStream().apply {
            writeLengthField(11, inputIds)
            writeLengthField(11, attentionMask)
            writeLengthField(11, noise)
            writeLengthField(12, imageOut)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeVarintField(1, 9L) // ir_version = 9
            writeLengthField(7, graph) // graph
        }.toByteArray()
    }

    private fun valueInfo(name: String, elemType: Int, dims: List<Long>): ByteArray {
        val shape = ByteArrayOutputStream().apply {
            dims.forEach { d ->
                val dim = ByteArrayOutputStream().apply { writeVarintField(1, d) }.toByteArray()
                writeLengthField(1, dim)
            }
        }.toByteArray()
        val tensorType = ByteArrayOutputStream().apply {
            writeVarintField(1, elemType.toLong())
            writeLengthField(2, shape)
        }.toByteArray()
        val typeProto = ByteArrayOutputStream().apply {
            writeLengthField(1, tensorType)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeLengthField(1, name.toByteArray(Charsets.UTF_8))
            writeLengthField(2, typeProto)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeVarintField(fieldNum: Int, value: Long) {
        writeVarintRaw(((fieldNum shl 3) or 0).toLong())
        writeVarintRaw(value)
    }

    private fun ByteArrayOutputStream.writeLengthField(fieldNum: Int, payload: ByteArray) {
        writeVarintRaw(((fieldNum shl 3) or 2).toLong())
        writeVarintRaw(payload.size.toLong())
        write(payload)
    }

    private fun ByteArrayOutputStream.writeVarintRaw(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                write(v.toInt())
                return
            } else {
                write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }
    }
}

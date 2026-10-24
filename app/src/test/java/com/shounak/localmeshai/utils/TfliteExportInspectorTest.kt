package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TfliteExportInspectorTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun rawGenAiCacheSignatureIsIdentifiedWithoutConfig() {
        val file = temp.newFile("decode.tflite").apply { writeBytes(TfliteFixture.cachedText()) }
        try { TfliteExportInspector.file(file).check(JSONObject()); fail("Cache-aware export accepted") }
        catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("external KV-cache"))
            assertTrue(expected.message!!.contains("packaged .task or .litertlm"))
        }
    }
    @Test fun standaloneClassifierUsesEmbeddedTaskAndNormalizationWithoutConfig() {
        val file = temp.newFile("classifier.tflite").apply { writeBytes(TfliteFixture.classifier()) }
        val export = TfliteExportInspector.file(file)
        assertEquals("image_classification", export.config!!.getString("solus_task"))
        assertFalse(export.processor!!.getBoolean("do_rescale"))
        assertEquals(127.5, export.processor.getJSONArray("image_mean").getDouble(0), 0.0)
        val capabilities = PortableModelBundle.inspect(file, ModelFormat.TensorFlowLite)
        assertTrue(capabilities.supportsVision)
        assertFalse(capabilities.supportsText)
        assertNull(PortableModelBundle.runtimeRestriction(file, ModelFormat.TensorFlowLite))
    }
    @Test fun textGraphStillNeedsTokenizerAndGenerationMetadata() {
        val file = temp.newFile("text.tflite").apply { writeBytes(TfliteFixture.text()) }
        val export = TfliteExportInspector.file(file)
        assertNull(export.config)
        export.check(JSONObject("""{"model_type":"gpt2","vocab_size":32}"""))
        assertNotNull(PortableModelBundle.runtimeRestriction(file, ModelFormat.TensorFlowLite))
    }
    @Test fun customKernelsFailWithPackagingGuidanceBeforeNativeLoad() {
        val file = temp.newFile("text.tflite").apply { writeBytes(TfliteFixture.text(custom = true)) }
        try { TfliteExportInspector.file(file).check(JSONObject()); fail("Custom kernels accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains(".task or .litertlm")) }
    }
    @Test fun unsupportedIoTypesExplainTheirRuntimeRequirements() {
        val file = temp.newFile("resource.tflite").apply { writeBytes(TfliteFixture.text(inputType = 13)) }
        try { TfliteExportInspector.file(file); fail("Resource input accepted") }
        catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("I/O type 13"))
            assertTrue(expected.message!!.contains("packaged .task/.litertlm"))
        }
    }
    @Test fun malformedOffsetsAndOversizedVectorsAreRejected() {
        listOf(ByteArray(32), byteArrayOf(-1,-1,-1,127) + "TFL3".toByteArray()).forEach { raw ->
            try { TfliteExportInspector.inspect(raw.size.toLong()) { at, count -> raw.copyOfRange(at.toInt(), at.toInt()+count) }; fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
}

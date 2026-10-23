package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelMemoryAssessmentTest {
    @Test fun typedRuntimeRestrictionsAreNotMislabelledAsMemoryWarnings() {
        val message = "This runtime requires at least a different export, regardless of available RAM in GB"
        val classified = ErrorClassifier.classify(message, LoadRestrictionCategory.RuntimeIncompatibility)
        assertEquals(ErrorCategory.RuntimeUnsupported, classified!!.category)
        assertFalse(classified.isRetryAction)
    }
    @get:Rule val temporary = TemporaryFolder()
    private val gib = 1073741824L
    private fun artifact(name: String, size: Long) = ModelArtifact(ArtifactRole.Main, name, ArtifactSource.PersistedUri("content://test/$name"), size)
    private fun model(name: String = "model", artifacts: List<ModelArtifact> = emptyList(), format: ModelFormat = ModelFormat.GGUF): ModelInfo {
        val bundle = if (artifacts.isEmpty()) null else ModelBundle("test", name, artifacts, format = format)
        return ModelInfo("test", name, "Unknown", ModelStatus.Available, ModelType.Text,
            "model.${if (format == ModelFormat.Safetensors) "safetensors" else "gguf"}", description = "", backend = "", deviceTarget = "", bundle = bundle)
    }

    @Test fun actualSmallArtifactsOverrideMisleadingNamesAndUnknownMetadataDoesNotBlock() {
        val small = model("70B-64-8bits", listOf(artifact("small.gguf", 122_000_000)))
        assertTrue(ModelMemoryAssessment.check(3.8, small).first)
        assertNull(ModelMemoryAssessment.estimate(model("70B")))
        assertTrue(ModelMemoryAssessment.check(3.8, model("70B")).first)
        assertTrue(ModelMemoryAssessment.check(Double.NaN, small).first)
        assertTrue(ModelMemoryAssessment.check(0.0, small).first)
    }

    @Test fun largeGenericNamesAndAllShardsAndProjectorsAreIncluded() {
        val large = model(artifacts = listOf(artifact("model.gguf", 6 * gib)))
        assertFalse(ModelMemoryAssessment.check(7.3, large).first)
        val single = model(artifacts = listOf(artifact("part-1.gguf", gib)))
        assertTrue(ModelMemoryAssessment.check(4.0, single).first)
        val combined = single.copy(bundle = single.bundle!!.copy(
            mainArtifacts = single.bundle.mainArtifacts + artifact("part-2.gguf", gib),
            projectorArtifact = artifact("mmproj.gguf", gib).copy(role = ArtifactRole.Projector)))
        assertFalse(ModelMemoryAssessment.check(4.0, combined).first)
        assertTrue(ModelMemoryAssessment.check(12.0, combined).first)
        assertEquals(ErrorCategory.MemoryEstimate, ErrorClassifier.classify(ModelMemoryAssessment.check(4.0, combined).second)?.category)
    }

    @Test fun metadataFilesAndStaleFileSizesDoNotInflateTheEstimate() {
        val file = temporary.newFile("model.gguf").apply { writeBytes(ByteArray(64)) }
        val small = model(artifacts = listOf(artifact("model.gguf", 10 * gib).copy(source = ArtifactSource.AppManaged(file.absolutePath)),
            artifact("tokenizer.json", 10 * gib)))
        assertTrue(ModelMemoryAssessment.check(3.8, small).first)
    }

    private fun safetensors(name: String, dtype: String, bytesPerElement: Int): File {
        val file = File(temporary.newFolder(), name)
        val header = """{"weight":{"dtype":"$dtype","shape":[1024,1024],"data_offsets":[0,${1024 * 1024 * bytesPerElement}]}}""".toByteArray()
        RandomAccessFile(file, "rw").use {
            it.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array())
            it.write(header); it.setLength(8L + header.size + 1024 * 1024 * bytesPerElement)
        }
        return file
    }

    @Test fun candleEstimateUsesFloat32TensorShapesInsteadOfCompressedCheckpointSize() {
        fun estimate(file: File) = ModelMemoryAssessment.estimate(model(artifacts = listOf(
            artifact(file.name, file.length()).copy(source = ArtifactSource.AppManaged(file.absolutePath))), format = ModelFormat.Safetensors))!!
        val half = estimate(safetensors("half.safetensors", "F16", 2))
        val full = estimate(safetensors("full.safetensors", "F32", 4))
        assertEquals(full.requiredGiB, half.requiredGiB, .00001)
        assertTrue(half.source.contains("FP32"))
    }

    @Test fun malformedHeadersAndDeclaredDownloadSizesHaveBoundedFallbacks() {
        val file = temporary.newFile("model.safetensors").apply { writeBytes(ByteArray(64)) }
        val imported = model(artifacts = listOf(artifact(file.name, 64).copy(source = ArtifactSource.AppManaged(file.absolutePath))), format = ModelFormat.Safetensors)
        assertNotNull(ModelMemoryAssessment.estimate(imported))
        val declared = model().copy(size = "6 GiB")
        assertFalse(ModelMemoryAssessment.check(7.3, declared).first)
        assertTrue(ModelMemoryAssessment.estimate(declared)!!.source.contains("declared"))
        assertNull(ModelMemoryAssessment.estimate(model().copy(size = "Unknown (70B)")))
    }
}

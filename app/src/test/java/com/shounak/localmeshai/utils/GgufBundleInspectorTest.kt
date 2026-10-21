package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ThinkingCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufBundleInspectorTest {

    @Test
    fun isVisionArchitecture_detectsKnownVisionArchitectures() {
        assertTrue(GgufBundleInspector.isVisionArchitecture("mllama"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("minicpmv"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("minicpm-v"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("qwen2vl"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("qwen2_vl"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("llava"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("llava-hf"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("moondream"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("granite-vision"))
        assertTrue(GgufBundleInspector.isVisionArchitecture("paligemma"))

        assertFalse(GgufBundleInspector.isVisionArchitecture("llama"))
        assertFalse(GgufBundleInspector.isVisionArchitecture("gemma"))
        assertFalse(GgufBundleInspector.isVisionArchitecture("qwen2"))
        assertFalse(GgufBundleInspector.isVisionArchitecture(""))
    }

    @Test
    fun validateBundle_recognizesUnifiedVisionArchitectures() {
        val artifact = GgufArtifactInspection(
            location = "/path/to/mllama-model.gguf",
            displayName = "mllama-model.gguf",
            sizeBytes = 1_000_000_000L,
            valid = true,
            isSupported = true,
            architecture = "mllama",
            name = "Llama 3.2 Vision",
            isProjector = false,
            hasVision = false, // When raw native flag is false and projector is absent, vision is false and projector is missing
            hasAudio = false
        )

        val result = GgufBundleInspector.validateBundle(listOf(artifact))
        assertTrue(result.isValid)
        assertFalse("Without projector or embedded encoder, supportsVision must be false", result.capabilities.supportsVision)
        assertTrue("Vision projector must be flagged as missing", result.capabilities.visionProjectorMissing)
        assertFalse(result.capabilities.supportsAudio)

        // With embedded vision encoder:
        val embeddedArtifact = artifact.copy(hasVision = true)
        val embeddedResult = GgufBundleInspector.validateBundle(listOf(embeddedArtifact))
        assertTrue(embeddedResult.isValid)
        assertTrue("With embedded vision encoder, supportsVision must be true", embeddedResult.capabilities.supportsVision)
        assertFalse("Vision projector should not be flagged missing when encoder is embedded", embeddedResult.capabilities.visionProjectorMissing)
    }

    @Test
    fun validateBundle_requiresConfirmedVisionEncoderOnProjector() {
        val main = GgufArtifactInspection(
            location = "/path/to/model.gguf",
            displayName = "model.gguf",
            sizeBytes = 2_000_000_000L,
            valid = true,
            isSupported = true,
            architecture = "llama",
            name = "Base Model",
            isProjector = false,
            hasVision = false,
            hasAudio = false
        )

        val unconfirmedProjector = GgufArtifactInspection(
            location = "/path/to/mmproj-f16.gguf",
            displayName = "mmproj-f16.gguf",
            sizeBytes = 300_000_000L,
            valid = true,
            isSupported = true,
            architecture = "clip",
            name = "CLIP Projector",
            isProjector = true,
            hasVision = false,
            hasAudio = false
        )

        val resultUnconfirmed = GgufBundleInspector.validateBundle(listOf(main, unconfirmedProjector))
        assertTrue(resultUnconfirmed.isValid)
        assertFalse("Projector without confirmed vision encoder must not set supportsVision = true", resultUnconfirmed.capabilities.supportsVision)

        val confirmedProjector = unconfirmedProjector.copy(hasVision = true)
        val resultConfirmed = GgufBundleInspector.validateBundle(listOf(main, confirmedProjector))
        assertTrue(resultConfirmed.isValid)
        assertTrue("Projector with confirmed vision encoder must set supportsVision = true", resultConfirmed.capabilities.supportsVision)
        assertFalse(resultConfirmed.capabilities.supportsAudio)
    }

    @Test
    fun validateBundle_preservesThinkingCapabilityWithoutNameFallback() {
        val main = GgufArtifactInspection(
            location = "/path/to/DeepSeek-R1-Distill-Q4_K_M.gguf",
            displayName = "DeepSeek-R1-Distill-Q4_K_M.gguf",
            sizeBytes = 4_000_000_000L,
            valid = true,
            isSupported = true,
            architecture = "qwen2",
            name = "DeepSeek R1 Distill Qwen",
            thinkingCapability = "Unknown"
        )

        val result = GgufBundleInspector.validateBundle(listOf(main))
        assertTrue(result.isValid)
        assertEquals(ThinkingCapability.Unknown, result.capabilities.thinking)

        val explicitMain = main.copy(thinkingCapability = "Toggleable")
        val explicitResult = GgufBundleInspector.validateBundle(listOf(explicitMain))
        assertTrue(explicitResult.isValid)
        assertEquals(ThinkingCapability.Toggleable, explicitResult.capabilities.thinking)
    }
}

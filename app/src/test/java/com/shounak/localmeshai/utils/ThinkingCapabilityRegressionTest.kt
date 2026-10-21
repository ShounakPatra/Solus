package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ThinkingCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCapabilityRegressionTest {

    @Test
    fun allFourCapabilityStates_onOffRequestBehaviorForToggleableModels() {
        // Toggleable: user preference is honored directly
        assertTrue(
            "Toggleable model with user preference ON must enable thinking",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.Toggleable, userPreference = true)
        )
        assertFalse(
            "Toggleable model with user preference OFF must disable thinking",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.Toggleable, userPreference = false)
        )

        // ModelControlled: always enabled regardless of user preference
        assertTrue(
            "ModelControlled must enable thinking when user preference is ON",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.ModelControlled, userPreference = true)
        )
        assertTrue(
            "ModelControlled must enable thinking even when user preference is OFF (model controlled)",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.ModelControlled, userPreference = false)
        )

        // Unsupported: always disabled
        assertFalse(
            "Unsupported model must never enable thinking even if user preference is ON",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.Unsupported, userPreference = true)
        )
        assertFalse(
            "Unsupported model must keep thinking disabled when user preference is OFF",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.Unsupported, userPreference = false)
        )

        // Unknown: unconfirmed, thinking must remain disabled
        assertFalse(
            "Unknown model must keep thinking disabled when user preference is ON",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.Unknown, userPreference = true)
        )
        assertFalse(
            "Unknown model must keep thinking disabled when user preference is OFF",
            ThinkingModeConfig.resolveRequestThinking(ThinkingCapability.Unknown, userPreference = false)
        )
    }

    @Test
    fun bundleAuthoritativeCapabilities_neverPromotedByFilename() {
        val artifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/501"),
            sizeBytes = 1_500_000_000L
        )

        // Case A: Bundle explicitly reports Unsupported (e.g. chat template lacks thinking tag)
        val unsupportedBundle = ModelBundle(
            id = "deepseek_r1_unsupported",
            displayName = "DeepSeek R1 Distill",
            mainArtifacts = listOf(artifact),
            capabilities = ModelCapabilities(
                supportsText = true,
                thinking = ThinkingCapability.Unsupported,
                thinkingDetectorVersion = ThinkingModeConfig.DETECTOR_VERSION,
                thinkingArtifactIdentity = "content://media/external/file/501"
            )
        )

        val resolvedA = ThinkingModeConfig.resolveThinkingCapability(
            bundle = unsupportedBundle,
            localPath = null,
            id = unsupportedBundle.id,
            name = unsupportedBundle.displayName,
            fileName = artifact.displayName
        )
        assertEquals(
            "Bundle with Unsupported thinking capability must NOT be promoted to thinking because of filename",
            ThinkingCapability.Unsupported,
            resolvedA
        )

        // Case B: Bundle has Unknown thinking capability
        val unknownBundle = ModelBundle(
            id = "qwen3_unknown",
            displayName = "Qwen 3 7B",
            mainArtifacts = listOf(
                artifact.copy(
                    displayName = "qwen-3-7b-instruct.gguf",
                    source = ArtifactSource.PersistedUri("content://media/external/file/502")
                )
            ),
            capabilities = ModelCapabilities(
                supportsText = true,
                thinking = ThinkingCapability.Unknown,
                thinkingDetectorVersion = ThinkingModeConfig.DETECTOR_VERSION,
                thinkingArtifactIdentity = "content://media/external/file/502"
            )
        )

        val resolvedB = ThinkingModeConfig.resolveThinkingCapability(
            bundle = unknownBundle,
            localPath = null,
            id = unknownBundle.id,
            name = unknownBundle.displayName,
            fileName = "qwen-3-7b-instruct.gguf"
        )
        assertEquals(
            "Bundle with Unknown thinking capability must NOT be promoted to thinking by name 'qwen3'",
            ThinkingCapability.Unknown,
            resolvedB
        )
    }

    @Test
    fun staleDetectorVersionOrRevisionMismatch_requiresReinspectionWithoutDropping() {
        val artifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "custom_model.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/601"),
            sizeBytes = 2_000_000_000L
        )

        // Stale detector version (< DETECTOR_VERSION)
        val staleVersionBundle = ModelBundle(
            id = "stale_version_model",
            displayName = "Stale Version Model",
            mainArtifacts = listOf(artifact),
            capabilities = ModelCapabilities(
                supportsText = true,
                thinking = ThinkingCapability.Toggleable,
                thinkingDetectorVersion = 0, // Stale version
                thinkingArtifactIdentity = "content://media/external/file/601"
            )
        )

        val resolvedStale = ThinkingModeConfig.resolveThinkingCapability(
            bundle = staleVersionBundle,
            localPath = null,
            id = staleVersionBundle.id,
            fileName = artifact.displayName
        )
        // Since detector version is stale and no context to inspect header, it does not blindly trust stale cached verdict
        assertEquals(
            "Stale detector version must not be trusted without fresh inspection",
            ThinkingCapability.Unknown,
            resolvedStale
        )

        // Mismatched artifact identity
        val mismatchedIdentityBundle = ModelBundle(
            id = "mismatched_id_model",
            displayName = "Mismatched ID Model",
            mainArtifacts = listOf(artifact),
            capabilities = ModelCapabilities(
                supportsText = true,
                thinking = ThinkingCapability.Toggleable,
                thinkingDetectorVersion = ThinkingModeConfig.DETECTOR_VERSION,
                thinkingArtifactIdentity = "content://media/external/file/OLD_URI" // Outdated URI
            )
        )

        val resolvedMismatched = ThinkingModeConfig.resolveThinkingCapability(
            bundle = mismatchedIdentityBundle,
            localPath = null,
            id = mismatchedIdentityBundle.id,
            fileName = artifact.displayName
        )
        assertEquals(
            "Mismatched artifact identity must not be trusted",
            ThinkingCapability.Unknown,
            resolvedMismatched
        )
    }
}

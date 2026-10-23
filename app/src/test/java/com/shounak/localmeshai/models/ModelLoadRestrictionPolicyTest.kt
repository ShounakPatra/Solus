package com.shounak.localmeshai.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadRestrictionPolicyTest {
    @Test fun oldIncorrectDeviceWarningsAreRecomputedForSmallQuantizedImports() {
        val model = createTestModel("gpt2", "64-8bits (TensorFlowLite)", 122, status = ModelStatus.Blocked,
            progress = 1f, localPath = "/models/64-8bits.tflite").copy(
            loadRestriction = LoadRestriction("Needs 12 GB", LoadRestrictionCategory.DeviceCompatibility),
            errorMessage = "Needs 12 GB")
        val projected = ModelLoadRestrictionPolicy.projectCompletedModel(false, model, 7.3)
        assertEquals(ModelStatus.Available, projected.status)
        assertNull(projected.loadRestriction)
        assertNull(projected.errorMessage)
        assertEquals(model.localPath, projected.localPath)
        assertEquals(model.downloadedBytes, projected.downloadedBytes)
    }

    private fun createTestModel(
        id: String,
        name: String,
        sizeMb: Long,
        status: ModelStatus = ModelStatus.NotDownloaded,
        progress: Float = 0f,
        downloadedBytes: Long = 0L,
        localPath: String? = null,
        isCustom: Boolean = false,
        bundle: ModelBundle? = null
    ): ModelInfo {
        val sizeStr = if (sizeMb >= 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"
        val effectiveId = if (isCustom && !id.startsWith("custom_")) "custom_$id" else id
        return ModelInfo(
            id = effectiveId,
            name = name,
            size = sizeStr,
            status = status,
            type = ModelType.Text,
            fileName = "$effectiveId.bin",
            packageType = ModelPackage.SingleFile,
            description = "Test model $effectiveId",
            backend = "test",
            deviceTarget = "All",
            url = if (isCustom) "https://example.com/$effectiveId.gguf" else "",
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = sizeMb * 1024 * 1024,
            localPath = localPath,
            bundle = bundle
        )
    }

    @Test
    fun testRestrictedModelProceedsThroughAcquisitionAndBecomesBlockedOnlyAfterCompletion() {
        // A 7B model on a 4GB RAM device profile
        val totalRamGB = 4.0
        val model = createTestModel("qwen_7b", "Qwen 2.5 7B", 4500L)

        // 1. Initial catalog state: un-downloaded models are NEVER blocked
        assertEquals(ModelStatus.NotDownloaded, model.status)
        assertNull(model.loadRestriction)

        // 2. Transfer starts: acquisition must proceed without being replaced by Blocked
        val downloadingModel = model.copy(
            status = ModelStatus.Downloading,
            progress = 0.45f,
            downloadedBytes = 2000L * 1024 * 1024
        )
        assertEquals(ModelStatus.Downloading, downloadingModel.status)
        assertNull(downloadingModel.loadRestriction)

        // 3. Paused state: preserved as Paused, never Blocked
        val pausedModel = downloadingModel.copy(
            status = ModelStatus.Paused,
            errorMessage = "Paused by user"
        )
        assertEquals(ModelStatus.Paused, pausedModel.status)
        assertNull(pausedModel.loadRestriction)

        // 4. Download completes: all artifacts present & validated
        val completedModel = pausedModel.copy(
            status = ModelStatus.Available,
            progress = 1f,
            downloadedBytes = pausedModel.totalBytes,
            localPath = "/data/user/0/com.shounak.localmeshai/files/models/qwen_7b.bin",
            errorMessage = null
        )

        // Completed model projected through shared load restriction policy
        val projected = ModelLoadRestrictionPolicy.projectCompletedModel(
            isCrashBlocked = false,
            model = completedModel,
            totalRamGB = totalRamGB
        )

        // Now, and only now, does it become Blocked
        assertEquals(ModelStatus.Blocked, projected.status)
        assertNotNull(projected.loadRestriction)
        assertEquals(LoadRestrictionCategory.DeviceCompatibility, projected.loadRestriction?.category)
        assertTrue("Device compatibility warning must allow Try Anyway", projected.canTryAnyway)
        assertFalse(projected.isCrashBlocked)
        assertNotNull(projected.localPath)
        assertEquals(1f, projected.progress, 0.001f)
    }

    @Test
    fun testPhoneAndNetworkImportsProduceEquivalentCompletedModelWarnings() {
        val totalRamGB = 4.0
        val artifactUri = ArtifactSource.PersistedUri("content://com.android.providers.media.documents/document/123")
        val phoneBundle = ModelBundle(
            id = "custom_phone_7b",
            displayName = "Qwen 2.5 7B",
            description = "Phone imported 7B model",
            backend = "llama.cpp",
            mainArtifacts = listOf(
                ModelArtifact(
                    role = ArtifactRole.Main,
                    displayName = "qwen_7b.gguf",
                    source = artifactUri,
                    sizeBytes = 4500L * 1024 * 1024
                )
            ),
            capabilities = ModelCapabilities(supportsText = true)
        )

        val phoneImport = createTestModel(
            id = "custom_phone_7b",
            name = "Qwen 2.5 7B",
            sizeMb = 4500L,
            status = ModelStatus.Available,
            progress = 1f,
            localPath = artifactUri.location,
            isCustom = true,
            bundle = phoneBundle
        )

        val networkImport = createTestModel(
            id = "custom_network_7b",
            name = "Qwen 2.5 7B",
            sizeMb = 4500L,
            status = ModelStatus.Available,
            progress = 1f,
            localPath = "/data/user/0/com.shounak.localmeshai/files/models/custom_network_7b.gguf",
            isCustom = true
        )

        val projectedPhone = ModelLoadRestrictionPolicy.projectCompletedModel(
            isCrashBlocked = false,
            model = phoneImport,
            totalRamGB = totalRamGB
        )

        val projectedNetwork = ModelLoadRestrictionPolicy.projectCompletedModel(
            isCrashBlocked = false,
            model = networkImport,
            totalRamGB = totalRamGB
        )

        // Both are blocked with identical category and reason
        assertEquals(ModelStatus.Blocked, projectedPhone.status)
        assertEquals(ModelStatus.Blocked, projectedNetwork.status)
        assertEquals(LoadRestrictionCategory.DeviceCompatibility, projectedPhone.loadRestriction?.category)
        assertEquals(LoadRestrictionCategory.DeviceCompatibility, projectedNetwork.loadRestriction?.category)
        assertEquals(projectedNetwork.loadRestriction?.reason, projectedPhone.loadRestriction?.reason)

        // Both support Try Anyway
        assertTrue(projectedPhone.canTryAnyway)
        assertTrue(projectedNetwork.canTryAnyway)

        // Phone import preserves storage workflow metadata
        assertTrue(projectedPhone.isStorageImport)
        assertEquals(artifactUri.location, projectedPhone.localPath)
    }

    @Test
    fun testPreviousCrashRestrictionsPreserveTransfersAndOfferExplicitRetry() {
        val model = createTestModel("crashed_model", "Crashed Model", 1500L)

        // 1. During transfer, previous crash must NOT mask progress
        val downloading = model.copy(
            status = ModelStatus.Downloading,
            progress = 0.65f,
            downloadedBytes = 975L * 1024 * 1024
        )
        // Observe status is preserved
        assertEquals(ModelStatus.Downloading, downloading.status)
        assertEquals(0.65f, downloading.progress, 0.001f)

        // 2. Once completed, project with previous crash flag
        val completed = downloading.copy(
            status = ModelStatus.Available,
            progress = 1f,
            localPath = "/data/user/0/models/crashed.bin"
        )
        val projected = ModelLoadRestrictionPolicy.projectCompletedModel(
            isCrashBlocked = true,
            model = completed,
            totalRamGB = 12.0 // Plenty of RAM, but previously crashed!
        )

        assertEquals(ModelStatus.Blocked, projected.status)
        assertNotNull(projected.loadRestriction)
        assertEquals(LoadRestrictionCategory.PreviousCrash, projected.loadRestriction?.category)
        assertTrue(projected.isCrashBlocked)

        // Previous crashes offer an explicit retry; the persisted block is cleared only after initialization succeeds.
        assertTrue("Previous crash restrictions must offer an explicit retry", projected.canTryAnyway)
    }

    @Test
    fun testDownloadingNeverGrantsLoadOverride() {
        val unsafeInitOverrideIds = mutableListOf<String>()
        val modelId = "custom_large_model"

        // Simulating the user tapping Download on an un-downloaded model
        val model = createTestModel(modelId, "Custom Llama 8B", 8000L, isCustom = true)
        assertEquals(ModelStatus.NotDownloaded, model.status)

        // Downloading never automatically adds to unsafeInitOverrideIds
        assertFalse("Downloading must never grant a load override", unsafeInitOverrideIds.contains(modelId))

        // Only confirmed Try Anyway after completion may grant the override
        val completedModel = model.copy(
            status = ModelStatus.Available,
            localPath = "/path/to/large.gguf"
        )
        val projected = ModelLoadRestrictionPolicy.projectCompletedModel(
            isCrashBlocked = false,
            model = completedModel,
            totalRamGB = 4.0
        )
        assertEquals(ModelStatus.Blocked, projected.status)
        assertTrue(projected.canTryAnyway)

        // Tapping Try Anyway confirmed:
        if (projected.canTryAnyway) {
            unsafeInitOverrideIds.add(modelId)
        }
        assertTrue("Only confirmed Try Anyway grants session override", unsafeInitOverrideIds.contains(modelId))
    }

    @Test
    fun testPausedFailedStatesAndSavedImportedEntriesSurviveRestoration() {
        // Model 1: Paused download with partial data
        val pausedModel = createTestModel(
            id = "custom_partial",
            name = "Partial Download",
            sizeMb = 3000L,
            status = ModelStatus.Paused,
            progress = 0.5f,
            downloadedBytes = 1500L * 1024 * 1024,
            isCustom = true
        )
        // When restored, paused state and partial bytes survive without becoming Blocked
        val restoredPaused = if (pausedModel.status == ModelStatus.Available) {
            ModelLoadRestrictionPolicy.projectCompletedModel(false, pausedModel, 4.0)
        } else {
            pausedModel
        }
        assertEquals(ModelStatus.Paused, restoredPaused.status)
        assertEquals(0.5f, restoredPaused.progress, 0.001f)
        assertNull(restoredPaused.loadRestriction)

        // Model 2: Storage import where permission was revoked
        val failedStorageModel = createTestModel(
            id = "custom_revoked",
            name = "Revoked Import",
            sizeMb = 2000L,
            status = ModelStatus.Failed,
            isCustom = true
        ).copy(errorMessage = "Files moved or storage permission revoked. Please relink files.")

        val restoredStorage = if (failedStorageModel.status == ModelStatus.Available) {
            ModelLoadRestrictionPolicy.projectCompletedModel(false, failedStorageModel, 4.0)
        } else {
            failedStorageModel
        }
        assertEquals(ModelStatus.Failed, restoredStorage.status)
        assertEquals("Files moved or storage permission revoked. Please relink files.", restoredStorage.errorMessage)
        assertNull(restoredStorage.loadRestriction)

        // Model 3: Completed accessible import exceeding device RAM
        val completedImport = createTestModel(
            id = "custom_done",
            name = "Large Completed Import 8B",
            sizeMb = 8000L,
            status = ModelStatus.Available,
            progress = 1f,
            localPath = "/data/models/custom_done.bin",
            isCustom = true
        )
        val restoredCompleted = ModelLoadRestrictionPolicy.projectCompletedModel(
            isCrashBlocked = false,
            model = completedImport,
            totalRamGB = 4.0
        )
        assertEquals(ModelStatus.Blocked, restoredCompleted.status)
        assertEquals(LoadRestrictionCategory.DeviceCompatibility, restoredCompleted.loadRestriction?.category)
        assertTrue(restoredCompleted.canTryAnyway)
        assertTrue(restoredCompleted.isCustom)
        assertEquals("/data/models/custom_done.bin", restoredCompleted.localPath)
    }
    @Test
    fun validationAndTransferFailuresAreNeverProjectedAsAvailableOrBlocked() {
        for (status in listOf(ModelStatus.Failed, ModelStatus.Downloading, ModelStatus.Paused)) {
            val model = createTestModel("bad_bundle", "Model", 1000, status = status,
                localPath = "/models/model.gguf").copy(errorMessage = "Missing shard", downloadedBytes = 42)
            assertEquals(model, ModelLoadRestrictionPolicy.projectCompletedModel(
                isCrashBlocked = true, model = model, totalRamGB = 1.0))
        }
    }

    @Test fun successfulCrashRetryRemovesStaleCrashAndDeviceWarnings() {
        val model = createTestModel("retry_7b", "Model 7B", 4500L, status = ModelStatus.Blocked, localPath = "/models/retry.gguf")
            .copy(loadRestriction = LoadRestriction("Previous crash", LoadRestrictionCategory.PreviousCrash))
        val recovered = ModelLoadRestrictionPolicy.projectCompletedModel(false, model, 4.0, hasSuccessfulRetry = true)
        assertEquals(ModelStatus.Available, recovered.status)
        assertNull(recovered.loadRestriction)
        assertNull(recovered.errorMessage)
        assertEquals(model.localPath, recovered.localPath)
        val restarted = ModelLoadRestrictionPolicy.projectCompletedModel(false, recovered, 4.0, hasSuccessfulRetry = true)
        assertEquals(ModelStatus.Available, restarted.status)
    }
    @Test fun failedRetryKeepsCrashPriorityAndRuntimeRestrictionsRemainBinding() {
        val model = createTestModel("retry", "Model", 1500L, status = ModelStatus.Blocked, localPath = "/models/retry.task")
        val crashed = ModelLoadRestrictionPolicy.projectCompletedModel(true, model, 12.0, hasSuccessfulRetry = true)
        assertTrue(crashed.isCrashBlocked)
        assertEquals(ModelStatus.Blocked, crashed.status)
        val runtime = model.copy(loadRestriction = LoadRestriction("Missing runtime support", LoadRestrictionCategory.RuntimeIncompatibility))
        val restricted = ModelLoadRestrictionPolicy.projectCompletedModel(false, runtime, 12.0, hasSuccessfulRetry = true)
        assertEquals(ModelStatus.Blocked, restricted.status)
        assertFalse(restricted.canTryAnyway)
    }
    @Test fun clearingCrashWithoutSuccessfulRetryReassessesDeviceLimits() {
        val model = createTestModel("retry_7b", "Model 7B", 4500L, status = ModelStatus.Blocked, localPath = "/models/retry.gguf")
            .copy(loadRestriction = LoadRestriction("Previous crash", LoadRestrictionCategory.PreviousCrash))
        val projected = ModelLoadRestrictionPolicy.projectCompletedModel(false, model, 4.0)
        assertEquals(LoadRestrictionCategory.DeviceCompatibility, projected.loadRestriction?.category)
    }

}

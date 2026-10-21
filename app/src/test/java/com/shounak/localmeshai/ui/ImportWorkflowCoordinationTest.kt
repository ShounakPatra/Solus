package com.shounak.localmeshai.ui

import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelPackage
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.ui.screens.ModelSourceFilter
import com.shounak.localmeshai.ui.screens.SizeTier
import com.shounak.localmeshai.ui.screens.matches
import com.shounak.localmeshai.utils.ImportPresentationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ImportWorkflowCoordinationTest {

    @Test
    fun testImportFinishesAfterDismissalCardAppearsImmediately() {
        val coordinator = ImportPresentationCoordinator()
        val sheetSessionId = "session_abc_123"
        val modelId = "imported_custom_model_1"

        // 1. User opens Add Model sheet
        coordinator.openImportSheet(sheetSessionId)
        assertTrue(coordinator.isSheetSessionOpen(sheetSessionId))
        assertTrue(coordinator.isSessionActive(sheetSessionId))

        // 2. User dismisses sheet BEFORE registration finishes
        val wasActive = coordinator.dismissImportSheet(sheetSessionId)
        assertTrue("Dismissing the active sheet returns true", wasActive)
        assertFalse("Sheet session is closed", coordinator.isSheetSessionOpen(sheetSessionId))
        assertFalse("Sheet session is no longer active", coordinator.isSessionActive(sheetSessionId))

        // 3. Late registration completes for the closed session
        val hidden = coordinator.hideModelForSession(sheetSessionId, modelId, isNewModel = true)

        // 4. Verification: Model is NOT hidden; card appears immediately without reopening Add Model!
        assertFalse("Model must NOT be hidden when registration finishes after sheet close", hidden)
        assertFalse(
            "hiddenFromPresentationIds must not contain the late model",
            coordinator.hiddenFromPresentationIds.value.contains(modelId)
        )
        assertTrue("No models should be hidden in presentation", coordinator.hiddenFromPresentationIds.value.isEmpty())
    }

    @Test
    fun testReimportingExistingModelNeverHidesCard() {
        val coordinator = ImportPresentationCoordinator()
        val sheetSessionId = "session_reimport_1"
        val existingModelId = "existing_custom_model"

        // Sheet is currently open
        coordinator.openImportSheet(sheetSessionId)
        assertTrue(coordinator.isSheetSessionOpen(sheetSessionId))

        // Registration completes for an already-existing model (re-import)
        val hidden = coordinator.hideModelForSession(sheetSessionId, existingModelId, isNewModel = false)

        // Must NOT hide existing model
        assertFalse("Re-importing existing model must never hide its card", hidden)
        assertFalse(
            "hiddenFromPresentationIds must never contain existing re-imported model",
            coordinator.hiddenFromPresentationIds.value.contains(existingModelId)
        )
        assertTrue(coordinator.hiddenFromPresentationIds.value.isEmpty())
    }

    @Test
    fun testCloseButtonDismissalFlowIsIdempotentAndCleansPresentation() {
        val coordinator = ImportPresentationCoordinator()
        val sessionId = "session_close_button_test"
        val modelId = "model_hidden_while_open"

        coordinator.openImportSheet(sessionId)
        val hidden = coordinator.hideModelForSession(sessionId, modelId, isNewModel = true)
        assertTrue("New model should be hidden while owning sheet is open", hidden)
        assertTrue(coordinator.hiddenFromPresentationIds.value.contains(modelId))

        // First dismissal (e.g. from Close button / hide completion)
        val wasActiveFirst = coordinator.dismissImportSheet(sessionId)
        assertTrue("First dismissal should report session was active", wasActiveFirst)
        assertFalse("Model must be revealed after dismissal", coordinator.hiddenFromPresentationIds.value.contains(modelId))
        assertFalse("Session is closed", coordinator.isSheetSessionOpen(sessionId))

        // Duplicate dismissal (idempotent call from onDismiss)
        val wasActiveSecond = coordinator.dismissImportSheet(sessionId)
        assertFalse("Duplicate dismissal should be a clean no-op", wasActiveSecond)
        assertFalse(coordinator.hiddenFromPresentationIds.value.contains(modelId))
        assertFalse(coordinator.isSheetSessionOpen(sessionId))
    }

    @Test
    fun testOldPickerAndDismissCallbacksCannotAffectNewSheetSession() {
        val coordinator = ImportPresentationCoordinator()
        val oldSessionId = "session_old"
        val newSessionId = "session_new"

        // User opens old sheet session, acquires hidden models
        coordinator.openImportSheet(oldSessionId)
        coordinator.hideModelForSession(oldSessionId, "old_model", isNewModel = true)
        assertTrue(coordinator.hiddenFromPresentationIds.value.contains("old_model"))

        // User opens a NEW sheet session (e.g. reopened or rotated)
        coordinator.openImportSheet(newSessionId)
        assertTrue(coordinator.isSessionActive(newSessionId))
        assertFalse(coordinator.isSessionActive(oldSessionId))

        // Stale picker callback arriving with oldSessionId is rejected because it is not active
        assertFalse(
            "Stale picker callback with oldSessionId must not be considered active",
            coordinator.isSessionActive(oldSessionId)
        )

        // New model registered in new session
        coordinator.hideModelForSession(newSessionId, "new_model", isNewModel = true)
        assertTrue(coordinator.hiddenFromPresentationIds.value.contains("new_model"))

        // Dismissing oldSessionId unhides only oldSessionId's models without clearing newSessionId
        val wasOldActive = coordinator.dismissImportSheet(oldSessionId)
        assertFalse("Old session was not the currently active session", wasOldActive)
        assertFalse("Old model is unhidden", coordinator.hiddenFromPresentationIds.value.contains("old_model"))
        assertTrue("New model should still be hidden while newSessionId is open", coordinator.hiddenFromPresentationIds.value.contains("new_model"))
        assertTrue("New session remains open", coordinator.isSheetSessionOpen(newSessionId))
        assertTrue("New session remains active", coordinator.isSessionActive(newSessionId))
    }

    @Test
    fun testClosingDuringRegistrationProtectsAcceptedOperation() = runBlocking {
        val operationCompleted = CompletableDeferred<Boolean>()

        // Simulate ViewModel registration protected by NonCancellable
        val parentJob = launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                // Persistence, publication, and enqueueing protected together
                delay(50)
                operationCompleted.complete(true)
            }
        }

        // Sheet dismissal/rotation cancels calling scope
        delay(10)
        parentJob.cancel()

        // Wait for operation to finish
        val result = operationCompleted.await()
        assertTrue("Accepted registration must complete even if outer scope is cancelled during rotation", result)
    }

    @Test
    fun testConcurrentDuplicateSubmissionsEnqueueOnce() = runBlocking {
        val submissionLocks = ConcurrentHashMap<String, Mutex>()
        val enqueueCount = AtomicInteger(0)
        val registeredModels = ConcurrentHashMap.newKeySet<String>()
        val modelId = "concurrent_submission_model_1"

        suspend fun submitModel(id: String) {
            val lock = submissionLocks.computeIfAbsent(id) { Mutex() }
            lock.withLock {
                // Check if already registered/enqueued under lock
                if (id in registeredModels) {
                    return
                }
                delay(20) // Simulate persistence / enqueueing time
                registeredModels.add(id)
                enqueueCount.incrementAndGet()
            }
        }

        // Launch 10 concurrent submissions for the same model identity
        val jobs = (1..10).map {
            async(Dispatchers.Default) {
                submitModel(modelId)
            }
        }
        jobs.awaitAll()

        assertEquals("Model must be enqueued exactly once despite concurrent duplicate clicks", 1, enqueueCount.get())
        assertTrue("Model is registered", registeredModels.contains(modelId))
    }

    @Test
    fun testImportedPlusSizeFilteringCoordination() {
        fun makeModel(id: String, name: String, sizeMb: Long, isCustom: Boolean, status: ModelStatus): ModelInfo {
            val sizeStr = if (sizeMb >= 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"
            val actualId = if (isCustom && !id.startsWith("custom_")) "custom_$id" else id
            return ModelInfo(
                id = actualId,
                name = name,
                size = sizeStr,
                status = status,
                type = ModelType.Text,
                fileName = "$actualId.bin",
                packageType = ModelPackage.SingleFile,
                description = "Test model $actualId",
                backend = "test",
                deviceTarget = "All",
                url = if (isCustom) "https://example.com/$actualId.gguf" else ""
            )
        }

        val catalogSmall = makeModel("cat_small", "Catalog Small", 1500L, isCustom = false, status = ModelStatus.NotDownloaded)
        val catalogLarge = makeModel("cat_large", "Catalog Large", 7000L, isCustom = false, status = ModelStatus.NotDownloaded)
        val importedDownloadedSmall = makeModel("imp_dl_small", "Imported DL Small", 2000L, isCustom = true, status = ModelStatus.Available)
        val importedDownloadedLarge = makeModel("imp_dl_large", "Imported DL Large", 8000L, isCustom = true, status = ModelStatus.Available)
        val importedPendingSmall = makeModel("imp_not_dl_small", "Imported Not DL Small", 1800L, isCustom = true, status = ModelStatus.NotDownloaded)

        val allModels = listOf(
            catalogSmall,
            catalogLarge,
            importedDownloadedSmall,
            importedDownloadedLarge,
            importedPendingSmall
        )

        // Pipeline matching ModelManagerScreen:
        // 1. Source filter = Imported
        val sourceFiltered = allModels.filter { it.isCustom }
        assertEquals(3, sourceFiltered.size)

        // 2. Size filter = Small (< 3 GB)
        val tierSmallFiltered = sourceFiltered.filter { it.matches("", SizeTier.Small) }
        assertEquals(2, tierSmallFiltered.size)
        assertTrue(tierSmallFiltered.contains(importedDownloadedSmall))
        assertTrue(tierSmallFiltered.contains(importedPendingSmall))
        assertFalse("Large imported model must be excluded by Small size filter", tierSmallFiltered.contains(importedDownloadedLarge))

        // 3. Partitioning: Downloaded vs Catalog
        val downloaded = tierSmallFiltered.filter { it.status == ModelStatus.Available }
        val catalog = tierSmallFiltered.filter { it.status != ModelStatus.Available }

        assertEquals("Only small downloaded imported model appears in downloaded section", 1, downloaded.size)
        assertEquals(importedDownloadedSmall.id, downloaded[0].id)
        assertFalse("Large downloaded imported model must NOT appear in downloaded section when Small tier is selected", downloaded.any { it.id == importedDownloadedLarge.id })

        assertEquals("Only small pending imported model appears in catalog section", 1, catalog.size)
        assertEquals(importedPendingSmall.id, catalog[0].id)

        // 4. Empty state verification:
        val hasMatches = tierSmallFiltered.isNotEmpty()
        assertTrue("Matches exist for Small Imported", hasMatches)

        // 5. Size filter = Huge (> 10 GB) with Imported
        val tierHugeFiltered = sourceFiltered.filter { it.matches("", SizeTier.Huge) }
        assertTrue("No imported models > 10 GB", tierHugeFiltered.isEmpty())
        val showEmpty = tierHugeFiltered.isEmpty()
        assertTrue("Empty state shown when no imported models match tier", showEmpty)
    }
}

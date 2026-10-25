package com.shounak.localmeshai.utils

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ModelRedownloadTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun explicitDownloadClearsOnlyCompletedTombstones() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = temp.root
        }
        val id = UUID.randomUUID().toString()
        assertTrue(ModelRemovalCoordinator.recordPendingDeletion(context, DeletionRecord(id, isPending = true)))
        assertFalse(ModelRemovalCoordinator.prepareExplicitDownload(context, id))
        assertTrue(ModelRemovalCoordinator.markDeletionComplete(context, id))
        assertTrue(ModelRemovalCoordinator.reserve(id))
        try { assertFalse(ModelRemovalCoordinator.prepareExplicitDownload(context, id)) }
        finally { ModelRemovalCoordinator.unreserve(id) }
        assertTrue(ModelRemovalCoordinator.isModelDeleted(id))
        assertTrue(ModelRemovalCoordinator.prepareExplicitDownload(context, id))
        assertFalse(ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(id))
    }
}

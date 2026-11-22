package com.shounak.localmeshai.models.state

import java.io.File

/**
 * State model for workspace SQLite database export, import, and auto-backup synchronization.
 * Replaces disjoint @Volatile booleans in ChatBackupManager.
 */
sealed interface ChatBackupState {
    val isBusy: Boolean get() = this is BackingUp || this is Restoring
    val statusKey: String

    object Idle : ChatBackupState {
        override val statusKey: String = "idle"
    }

    data class BackingUp(val targetFile: File) : ChatBackupState {
        override val statusKey: String = "backing_up"
    }

    data class Restoring(val sourceFile: File) : ChatBackupState {
        override val statusKey: String = "restoring"
    }

    data class BackupSuccess(val backupFile: File, val timestampMs: Long) : ChatBackupState {
        override val statusKey: String = "backup_success"
    }

    data class RestoreSuccess(val timestampMs: Long) : ChatBackupState {
        override val statusKey: String = "restore_success"
    }

    data class Failed(val operation: String, val error: String) : ChatBackupState {
        override val statusKey: String = "failed"
    }

    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "isBusy" to isBusy
    )
}

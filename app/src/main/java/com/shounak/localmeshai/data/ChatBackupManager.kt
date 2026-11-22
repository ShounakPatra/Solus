package com.shounak.localmeshai.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.shounak.localmeshai.features.WorkspaceBackup
import com.shounak.localmeshai.utils.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ChatBackupManager {
    private const val TAG = "ChatBackupManager"
    private const val BACKUP_FILE_NAME = "solus_auto_backup.zip"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _backupState = kotlinx.coroutines.flow.MutableStateFlow<com.shounak.localmeshai.models.state.ChatBackupState>(
        com.shounak.localmeshai.models.state.ChatBackupState.Idle
    )
    val backupState: kotlinx.coroutines.flow.StateFlow<com.shounak.localmeshai.models.state.ChatBackupState> = _backupState.asStateFlow()

    @Volatile
    private var isBackingUp = false

    @Volatile
    private var isRestoring = false

    fun getBackupFile(context: Context): File {
        return try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(docs, "Solus")
            if (!dir.exists()) dir.mkdirs()
            File(dir, BACKUP_FILE_NAME)
        } catch (_: Exception) {
            File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_FILE_NAME)
        }
    }

    private fun getFallbackBackupFile(context: Context): File {
        return File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_FILE_NAME)
    }

    fun triggerAutoBackup(context: Context) {
        val appSettings = AppSettings.getInstance(context).settings.value
        if (!appSettings.enableAutoChatBackup) return
        if (isBackingUp) return

        scope.launch {
            try {
                isBackingUp = true
                performAutoBackup(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Auto-backup failed: ${e.message}")
            } finally {
                isBackingUp = false
            }
        }
    }

    suspend fun performAutoBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appSettings = AppSettings.getInstance(context).settings.value
        if (!appSettings.enableAutoChatBackup) return@withContext false

        try {
            val primaryFile = getBackupFile(context)
            _backupState.value = com.shounak.localmeshai.models.state.ChatBackupState.BackingUp(primaryFile)
            primaryFile.parentFile?.mkdirs()
            val tempFile = File(primaryFile.parentFile ?: context.cacheDir, "$BACKUP_FILE_NAME.tmp")
            
            FileOutputStream(tempFile).use { output ->
                WorkspaceBackup(context).exportTo(output, includeAttachments = false)
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (primaryFile.exists()) primaryFile.delete()
                tempFile.renameTo(primaryFile)
                _backupState.value = com.shounak.localmeshai.models.state.ChatBackupState.BackupSuccess(primaryFile, System.currentTimeMillis())
                Log.i(TAG, "Auto-backup created at ${primaryFile.absolutePath} (${primaryFile.length()} bytes)")
            }

            // Also keep secondary copy in external files dir
            val fallback = getFallbackBackupFile(context)
            if (primaryFile.exists() && primaryFile != fallback) {
                try {
                    primaryFile.copyTo(fallback, overwrite = true)
                } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "performAutoBackup error", e)
            false
        }
    }

    suspend fun checkAndPerformAutoRestore(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appSettings = AppSettings.getInstance(context).settings.value
        if (!appSettings.enableAutoChatBackup) return@withContext false
        if (isRestoring) return@withContext false

        isRestoring = true
        try {
            val repository = ChatSessionRepository.getInstance(context)
            val existing = repository.loadChatSessions()
            if (existing.isNotEmpty()) {
                // User already has local sessions; no restore needed
                return@withContext false
            }

            // Candidate backup files to check
            val candidates = listOf(getBackupFile(context), getFallbackBackupFile(context))
            val targetFile = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return@withContext false

            Log.i(TAG, "Found reinstall auto-backup at ${targetFile.absolutePath}, restoring...")
            FileInputStream(targetFile).use { input ->
                WorkspaceBackup(context).restoreFrom(input)
            }
            Log.i(TAG, "Reinstall auto-backup restored successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "checkAndPerformAutoRestore error", e)
            false
        } finally {
            isRestoring = false
        }
    }
}

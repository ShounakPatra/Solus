package com.shounak.localmeshai.features

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

data class StorageSnapshot(val models: Long, val attachments: Long, val knowledge: Long, val cache: Long, val available: Long)

object StorageDashboard {
    fun inspect(context: Context): StorageSnapshot {
        val modelRoot = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir.resolve("downloads")).resolve("models")
        val knowledge = context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("rag_vector_store") }.sumOf { size(it) } +
            size(context.filesDir.resolve("embeddings"))
        return StorageSnapshot(size(modelRoot), size(context.filesDir.resolve("chat_attachments")) + size(context.filesDir.resolve("vision_sessions")),
            knowledge, size(context.cacheDir) + (context.externalCacheDir?.let(::size) ?: 0L), StatFs(context.filesDir.path).availableBytes)
    }
    internal fun size(root: File): Long {
        if (!root.exists()) return 0
        val canonical = root.canonicalFile
        return root.walkTopDown().onEnter { it.canonicalFile == canonical || WorkspaceBackup.inside(it, canonical) }
            .filter { it.isFile && (it.canonicalFile == canonical || WorkspaceBackup.inside(it, canonical)) }.sumOf { it.length() }
    }
    fun format(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(java.util.Locale.US, bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(java.util.Locale.US, bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }
}

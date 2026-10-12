package com.shounak.localmeshai.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class ChatAttachmentStore(
    val filesDir: File
) {
    val attachmentsRoot: File by lazy {
        filesDir.resolve("chat_attachments").apply { mkdirs() }
    }

    fun getSessionAttachmentsDir(chatKind: ChatKind, sessionId: String, create: Boolean = false): File {
        val safeSessionId = sanitizeSegment(sessionId)
        val dir = attachmentsRoot.resolve(chatKind.name.lowercase()).resolve(safeSessionId)
        verifyContainment(attachmentsRoot, dir)
        if (create) dir.mkdirs()
        return dir
    }

    fun getAttachmentDir(chatKind: ChatKind, sessionId: String, attachmentId: String, create: Boolean = false): File {
        val sessionDir = getSessionAttachmentsDir(chatKind, sessionId, create = create)
        val safeAttachmentId = sanitizeSegment(attachmentId)
        val dir = sessionDir.resolve(safeAttachmentId)
        verifyContainment(sessionDir, dir)
        if (create) dir.mkdirs()
        return dir
    }

    /**
     * Checks if a given file is a valid owned attachment contained strictly inside this store.
     */
    fun isOwnedAttachment(file: File, chatKind: ChatKind? = null, sessionId: String? = null): Boolean {
        if (!file.exists()) return false
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val canonicalRoot = runCatching { attachmentsRoot.canonicalFile }.getOrNull() ?: return false
        if (!canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) return false

        if (chatKind != null && sessionId != null) {
            val sessionDir = getSessionAttachmentsDir(chatKind, sessionId, create = false)
            val canonicalSession = runCatching { sessionDir.canonicalFile }.getOrNull() ?: return false
            if (!canonicalFile.path.startsWith(canonicalSession.path + File.separator)) return false
        }
        return true
    }

    /**
     * Copies and commits a source stream or URI to an owned durable attachment location.
     * Throws IOException if input stream cannot be opened.
     */
    fun storeAttachmentFromStream(
        context: Context,
        chatKind: ChatKind,
        sessionId: String,
        attachmentId: String = UUID.randomUUID().toString(),
        sourceUri: Uri?,
        fallbackStream: InputStream? = null,
        targetFileName: String
    ): File {
        val stream: InputStream = when {
            sourceUri != null -> {
                context.contentResolver.openInputStream(sourceUri)
                    ?: fallbackStream
                    ?: throw IOException("Cannot open input stream for URI: $sourceUri")
            }
            fallbackStream != null -> fallbackStream
            else -> throw IOException("No source stream or URI provided for attachment")
        }

        val targetDir = getAttachmentDir(chatKind, sessionId, attachmentId, create = true)
        val safeName = sanitizeFileName(targetFileName)
        val targetFile = targetDir.resolve(safeName)
        verifyContainment(targetDir, targetFile)

        val tempFile = targetDir.resolve(".temp_${UUID.randomUUID()}")
        try {
            stream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IOException("Stored attachment is empty: $targetFileName")
            }
            publishFileAtomically(tempFile, targetFile)
            return targetFile
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Copies an existing file into durable attachment storage for a specific session.
     */
    fun storeAttachmentFromFile(
        chatKind: ChatKind,
        sessionId: String,
        attachmentId: String = UUID.randomUUID().toString(),
        sourceFile: File,
        targetFileName: String = sourceFile.name
    ): File {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            throw IOException("Source file is missing or empty: ${sourceFile.absolutePath}")
        }
        val targetDir = getAttachmentDir(chatKind, sessionId, attachmentId, create = true)
        val safeName = sanitizeFileName(targetFileName)
        val targetFile = targetDir.resolve(safeName)
        verifyContainment(targetDir, targetFile)

        val tempFile = targetDir.resolve(".temp_${UUID.randomUUID()}")
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IOException("Stored attachment is empty: $targetFileName")
            }
            publishFileAtomically(tempFile, targetFile)
            return targetFile
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun publishFileAtomically(tempFile: File, targetFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /**
     * Returns all physical files owned by a session.
     */
    fun getOwnedFilesForSession(chatKind: ChatKind, sessionId: String): List<File> {
        val dir = getSessionAttachmentsDir(chatKind, sessionId, create = false)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".temp_") }.toList()
    }

    /**
     * Deletes all attachments owned by a session and returns true if completely removed.
     */
    fun deleteSessionAttachments(chatKind: ChatKind, sessionId: String): Boolean {
        val dir = getSessionAttachmentsDir(chatKind, sessionId, create = false)
        if (!dir.exists()) return true
        val success = dir.deleteRecursively()
        return success && !dir.exists()
    }

    private fun sanitizeSegment(id: String): String {
        val clean = id.trim()
        if (clean.isBlank() || clean == "." || clean == ".." || clean.contains("..") || !SEGMENT_REGEX.matches(clean)) {
            throw IllegalArgumentException("Invalid segment: $id")
        }
        return clean
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().replace('\\', '/').substringAfterLast('/')
        val clean = trimmed.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return clean.ifBlank { "attachment_${System.currentTimeMillis()}" }
    }

    private fun verifyContainment(parent: File, child: File) {
        val canonicalParent = parent.canonicalPath
        val canonicalChild = child.canonicalPath
        if (!canonicalChild.startsWith(canonicalParent + File.separator) && canonicalChild != canonicalParent) {
            throw SecurityException("Path traversal violation: $canonicalChild is not inside $canonicalParent")
        }
    }

    companion object {
        private val SEGMENT_REGEX = Regex("^[a-zA-Z0-9._-]+$")
    }
}

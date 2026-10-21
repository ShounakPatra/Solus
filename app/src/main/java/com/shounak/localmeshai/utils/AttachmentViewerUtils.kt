package com.shounak.localmeshai.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.json.JSONArray

object AttachmentViewerUtils {
    private const val TAG = "AttachmentViewerUtils"
    private const val FILE_PROVIDER_AUTHORITY = "com.shounak.localmeshai.fileprovider"

    /**
     * Resolves the MIME type for a given file name or extension.
     */
    fun resolveMimeType(fileNameOrExt: String): String {
        val ext = fileNameOrExt.substringAfterLast('.', fileNameOrExt).lowercase(Locale.ROOT).trim()
        val mimeFromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!mimeFromMap.isNullOrBlank()) {
            return mimeFromMap
        }
        return when (ext) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "csv" -> "text/csv"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "log", "ini", "conf" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz", "gzip" -> "application/gzip"
            "class" -> "application/java-vm"
            "jar" -> "application/java-archive"
            "apk" -> "application/vnd.android.package-archive"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "py" -> "text/x-python"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "c" -> "text/x-c"
            "cpp", "cc", "cxx" -> "text/x-c++src"
            "js" -> "text/javascript"
            "ts" -> "text/typescript"
            else -> "*/*"
        }
    }

    fun findAttachmentFile(context: Context, documentName: String, explicitPath: String? = null): File? {
        if (!explicitPath.isNullOrBlank()) {
            val f = File(explicitPath)
            if (f.exists() && f.isFile) return f
        }

        val cacheInputs = context.cacheDir.resolve("multimodal_inputs").resolve(documentName)
        if (cacheInputs.exists() && cacheInputs.isFile) return cacheInputs

        return null
    }

    /**
     * Cleans up orphaned attachment directories in chat_attachments that do not belong
     * to any existing text session or vision session according to the authoritative repository.
     */
    fun cleanupOrphanAttachments(context: Context, extraKeepSessionIds: Set<String> = emptySet()) {
        val attachmentsDir = context.filesDir.resolve("chat_attachments")
        if (!attachmentsDir.exists() || !attachmentsDir.isDirectory) return

        val repository = com.shounak.localmeshai.data.ChatSessionRepository.getInstance(context)
        // If either store is unreadable, corrupt, or unsupported, defer destructive orphan cleanup
        if (!repository.isChatStoreHealthy() || !repository.isVisionStoreHealthy()) return

        val textSessions = runCatching { repository.loadChatSessions() }.getOrNull()
        val visionSessions = runCatching { repository.loadVisionSessions() }.getOrNull()

        // If either store read failed, defer destructive orphan cleanup
        if (textSessions == null || visionSessions == null || !repository.isChatStoreHealthy() || !repository.isVisionStoreHealthy()) return

        val validIds = mutableSetOf<String>()
        validIds.addAll(extraKeepSessionIds.filter { it.isNotBlank() })
        textSessions.forEach { if (it.id.isNotBlank()) validIds.add(it.id) }
        visionSessions.forEach { if (it.id.isNotBlank()) validIds.add(it.id) }

        // Clean text/vision kind subdirectories as well as legacy root-level session subdirectories
        val topChildren = attachmentsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (child in topChildren) {
            when (child.name.lowercase()) {
                "text", "vision" -> {
                    child.listFiles()?.filter { it.isDirectory }?.forEach { sessionDir ->
                        if (!validIds.contains(sessionDir.name)) {
                            runCatching { sessionDir.deleteRecursively() }
                        }
                    }
                }
                else -> {
                    // Legacy root-level session directory
                    if (!validIds.contains(child.name)) {
                        runCatching { child.deleteRecursively() }
                    }
                }
            }
        }
    }

    /**
     * Opens a physical file in the device's default or chosen external viewer.
     */
    fun openFileInExternalViewer(context: Context, file: File, explicitMimeType: String? = null) {
        if (!file.exists()) {
            Toast.makeText(context, "Attachment file is no longer available on device.", Toast.LENGTH_SHORT).show()
            return
        }

        val previewLease = ActiveInputFiles.protect(file)
        try {
            // External viewers may open the URI after this app enters the background.
            // Renew the cache retention window before handing the file to another app.
            if (file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)) {
                file.setLastModified(System.currentTimeMillis())
            }
            val uri: Uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val mimeType = explicitMimeType ?: resolveMimeType(file.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening attachment in external app", e)
            Toast.makeText(context, "Could not open file: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
        } finally {
            previewLease.close()
        }
    }

    /**
     * Opens a document by name/path in the device's default viewer.
     */
    fun openDocumentInExternalViewer(context: Context, documentName: String, explicitPath: String? = null) {
        val file = findAttachmentFile(context, documentName, explicitPath)
        if (file != null && file.exists()) {
            openFileInExternalViewer(context, file)
        } else {
            Toast.makeText(context, "File \"$documentName\" not found on device.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens an image in the device's default image viewer (e.g. Gallery, Photos).
     */
    fun openImageInExternalViewer(context: Context, bitmap: Bitmap? = null, imagePath: String? = null) {
        if (!imagePath.isNullOrBlank()) {
            val file = File(imagePath)
            if (file.exists() && file.isFile) {
                openFileInExternalViewer(context, file, "image/*")
                return
            }
        }

        if (bitmap != null && !bitmap.isRecycled) {
            try {
                val previewDir = context.cacheDir.resolve("image_previews").apply { mkdirs() }
                val tempFile = File(previewDir, "image_preview_${System.currentTimeMillis()}.png")
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                openFileInExternalViewer(context, tempFile, "image/png")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache bitmap for external viewer", e)
            }
        }

        Toast.makeText(context, "Image file is not available.", Toast.LENGTH_SHORT).show()
    }
}

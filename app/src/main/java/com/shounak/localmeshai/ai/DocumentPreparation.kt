package com.shounak.localmeshai.ai

import android.content.Context
import android.net.Uri
import com.shounak.localmeshai.utils.ActiveInputFiles
import com.shounak.localmeshai.utils.DocumentTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

/** A draft owns its preparation until Send takes it; replacing the draft cancels only that draft. */
internal class DocumentPreparation(private val context: Context, private val scope: CoroutineScope) {
    private data class Slot(val uri: Uri, val name: String, val result: Deferred<PreparedAttachment>,
                            @Volatile var attachment: PreparedAttachment? = null)
    private var staged: Slot? = null
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Deferred<PreparedAttachment>, Slot>()
    private val knownDocuments = object : LinkedHashMap<String, String>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3
    }

    private fun identity(file: File) = "${file.canonicalPath}|${file.length()}|${file.lastModified()}"

    fun remember(attachment: PreparedAttachment) {
        if (attachment.extractionError != null) return
        val file = attachment.durableFile ?: return
        val text = attachment.extractedText ?: return
        synchronized(knownDocuments) { knownDocuments[identity(file)] = text }
    }

    fun stage(uri: Uri, name: String) {
        if (staged?.uri == uri && staged?.name == name) return
        cancel()
        var slot: Slot? = null
        val result = scope.async(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val attachment = prepare(uri, name)
            slot?.attachment = attachment
            try {
                ensureActive()
                attachment
            } catch (t: Throwable) {
                attachment.close()
                throw t
            }
        }
        slot = Slot(uri, name, result)
        staged = slot
        result.start()
    }

    fun take(uri: Uri, name: String): Deferred<PreparedAttachment> {
        if (staged?.uri != uri || staged?.name != name) stage(uri, name)
        val slot = checkNotNull(staged)
        inFlight[slot.result] = slot
        staged = null
        return slot.result
    }

    fun release(result: Deferred<PreparedAttachment>?) {
        if (result == null) return
        val slot = inFlight.remove(result) ?: return
        result.cancel()
        slot.attachment?.close()
    }

    fun cancel() {
        val previous = staged ?: return
        staged = null
        previous.result.cancel()
        previous.attachment?.close()
    }

    private suspend fun prepare(uri: Uri, name: String): PreparedAttachment {
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        var extractionError: String? = null
        if (uri.scheme == "file") {
            val file = File(checkNotNull(uri.path))
            require(file.isFile) { "Saved attachment is no longer available" }
            val text = synchronized(knownDocuments) { knownDocuments[identity(file)] }
                ?: DocumentTextExtractor.extract(
                    context = context,
                    uri = uri,
                    fileName = name,
                    budget = com.shounak.localmeshai.utils.DocumentExtractionBudget.ATTACHED_DOCUMENT,
                    isCancelled = { currentJob?.isActive == false },
                    onFailure = { extractionError = it }
                )
            return PreparedAttachment(name = name, fileUri = uri, durableFile = file, extractedText = text, extractionError = extractionError)
                .also(::remember)
        }
        val directory = context.cacheDir.resolve("multimodal_inputs").apply { mkdirs() }
        val extension = name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12)
        val file = File(directory, "document_${UUID.randomUUID()}.$extension")
        val lease = ActiveInputFiles.protect(file)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Cannot read the attached document")
            input.use { source ->
                file.outputStream().use { output ->
                    val bytes = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = source.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= 20L * 1024L * 1024L) { "Attached document exceeds 20 MB" }
                        output.write(bytes, 0, count)
                    }
                }
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            val text = DocumentTextExtractor.extract(
                context = context,
                uri = Uri.fromFile(file),
                fileName = name,
                mimeType = mime,
                budget = com.shounak.localmeshai.utils.DocumentExtractionBudget.ATTACHED_DOCUMENT,
                isCancelled = { currentJob?.isActive == false },
                onFailure = { extractionError = it }
            )
            return PreparedAttachment(name = name, mimeType = mime, fileUri = Uri.fromFile(file),
                extractedText = text, extractionError = extractionError, ownsResources = true, temporaryFiles = listOf(file), resourceLease = lease)
        } catch (t: Throwable) {
            lease.close()
            ActiveInputFiles.deleteIfUnreferenced(file)
            throw t
        }
    }
}

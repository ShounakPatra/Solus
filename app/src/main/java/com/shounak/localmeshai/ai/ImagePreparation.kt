package com.shounak.localmeshai.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.shounak.localmeshai.utils.ActiveInputFiles
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Encode selected images once; ownership moves from the draft to its accepted request. */
internal class ImagePreparation(private val context: Context, private val scope: CoroutineScope) {
    private data class Slot(val bitmap: Bitmap, val result: Deferred<PreparedAttachment>,
                            @Volatile var attachment: PreparedAttachment? = null)
    private var staged: Slot? = null
    private val requests = ConcurrentHashMap<Deferred<PreparedAttachment>, Slot>()

    fun stage(bitmap: Bitmap?) {
        if (staged?.bitmap === bitmap) return
        cancel()
        if (bitmap == null) return
        var slot: Slot? = null
        val result = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val directory = context.cacheDir.resolve("multimodal_inputs").apply { mkdirs() }
            val file = directory.resolve("image_${UUID.randomUUID()}.jpg")
            val lease = ActiveInputFiles.protect(file)
            try {
                val encoded = withContext(Dispatchers.Default) {
                    val maxDimension = 1024
                    val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                        val destW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                        val destH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(bitmap, destW, destH, true)
                    } else {
                        null
                    }
                    val target = scaledBitmap ?: bitmap
                    try {
                        ByteArrayOutputStream().use {
                            check(!target.isRecycled && target.compress(Bitmap.CompressFormat.JPEG, 90, it)) {
                                "Could not prepare the attached image"
                            }
                            it.toByteArray()
                        }
                    } finally {
                        if (scaledBitmap != null && scaledBitmap != bitmap) {
                            runCatching { scaledBitmap.recycle() }
                        }
                    }
                }
                ensureActive()
                file.writeBytes(encoded)
                val attachment = PreparedAttachment(name = file.name, fileUri = Uri.fromFile(file),
                    ownsResources = true, temporaryFiles = listOf(file), resourceLease = lease)
                slot?.attachment = attachment
                ensureActive()
                attachment
            } catch (t: Throwable) {
                lease.close()
                ActiveInputFiles.deleteIfUnreferenced(file)
                throw t
            }
        }
        slot = Slot(bitmap, result)
        staged = slot
        result.start()
    }

    fun take(bitmap: Bitmap): Deferred<PreparedAttachment> {
        if (staged?.bitmap !== bitmap) stage(bitmap)
        val slot = checkNotNull(staged)
        staged = null
        requests[slot.result] = slot
        return slot.result
    }

    fun release(result: Deferred<PreparedAttachment>?) {
        val slot = result?.let(requests::remove) ?: return
        slot.result.cancel()
        slot.attachment?.close()
    }

    fun cancel() {
        val previous = staged ?: return
        staged = null
        previous.result.cancel()
        previous.attachment?.close()
    }
}

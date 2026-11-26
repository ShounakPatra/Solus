package com.shounak.localmeshai.ai.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Exports locally generated PNG images to the Android MediaStore gallery (API 30+ Scoped Storage).
 */
object ImageGallerySaver {
    fun saveToGallery(context: Context, imagePath: String, titlePrefix: String = "Solus"): Result<Uri> {
        return runCatching {
            val source = File(imagePath)
            require(source.isFile && source.length() > 0L) { "Generated image file not found" }
            val displayName = "${titlePrefix}_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Solus")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create MediaStore entry")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IOException("Could not open MediaStore output stream")

                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publishValues, null, null)
                uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }
    }

    fun decodeSampledBitmap(imagePath: String): Bitmap? {
        val file = File(imagePath)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }
}

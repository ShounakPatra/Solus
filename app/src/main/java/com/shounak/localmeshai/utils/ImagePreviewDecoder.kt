package com.shounak.localmeshai.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImagePreviewDecoder {
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int = 1024): Int {
        require(maxDimension > 0)
        var sample = 1
        while (maxOf(width, height).toLong() > maxDimension.toLong() * sample && sample <= Int.MAX_VALUE / 2) sample *= 2
        return sample
    }

    /** Call from a worker thread. The full-resolution original remains on disk. */
    fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        })
    }.getOrNull()
}

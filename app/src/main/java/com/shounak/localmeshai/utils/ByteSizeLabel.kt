package com.shounak.localmeshai.utils

/** Size labels that match the 1024-based KiB/MiB/GiB units used across the app. */
object ByteSizeLabel {
    /** A download size in whole MiB, rounded to the nearest MiB. */
    fun wholeMib(bytes: Long): String = "${(bytes.coerceAtLeast(0L) + 524_288L) / 1_048_576L} MiB"
}

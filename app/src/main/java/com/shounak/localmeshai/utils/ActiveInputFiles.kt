package com.shounak.localmeshai.utils

import java.io.File

/** Cleanup and native readers coordinate through leases; durable attachments are never owned here. */
object ActiveInputFiles {
    private val references = mutableMapOf<String, Int>()

    @Synchronized
    fun protect(file: File): AutoCloseable {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        references[path] = (references[path] ?: 0) + 1
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val remaining = (references[path] ?: 1) - 1
                    if (remaining <= 0) references.remove(path) else references[path] = remaining
                }
            }
        }
    }

    @Synchronized
    fun release(file: File) {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        val remaining = (references[path] ?: 1) - 1
        if (remaining <= 0) references.remove(path) else references[path] = remaining
    }

    @Synchronized
    fun isProtected(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        return references.containsKey(path)
    }

    @Synchronized
    fun deleteIfUnreferenced(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        if (references.containsKey(path)) return false
        return file.delete()
    }
}

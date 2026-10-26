package com.shounak.localmeshai.utils

import java.io.File
import java.util.UUID

/** Only explicitly selected files enter the provider's export root. Call on an IO thread. */
internal object SharedFileExports {
    fun prune(cacheRoot: File, now: Long): Int {
        val root = cacheRoot.resolve("shared_exports").canonicalFile
        var removed = 0
        for (directory in root.listFiles().orEmpty()) {
            if (!directory.isDirectory || directory.canonicalFile.parentFile != root) continue
            val files = directory.listFiles().orEmpty()
            if (files.any { !it.isFile || it.canonicalFile.parentFile != directory.canonicalFile ||
                    ActiveInputFiles.isProtected(it) || it.lastModified() <= 0 || now - it.lastModified() < AppCacheCleaner.INPUT_RETENTION_MS }) continue
            files.forEach { if (ActiveInputFiles.deleteIfUnreferenced(it)) removed++ }
            if (directory.listFiles()?.isEmpty() == true && directory.delete()) removed++
        }
        return removed
    }
    fun stage(cacheRoot: File, source: File): File {
        require(source.isFile) { "The selected file is unavailable" }
        val directory = cacheRoot.resolve("shared_exports/${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Could not create export folder" }
        val target = directory.resolve(source.name)
        try {
            source.copyTo(target)
            return target
        } catch (failure: Throwable) {
            target.delete()
            directory.delete()
            throw failure
        }
    }
}

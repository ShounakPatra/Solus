package com.shounak.localmeshai.utils

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Copy first; callers commit saved references only after this returns a durable file. */
internal object LegacyAttachmentMigration {
    fun copyReferenced(source: File, cacheRoot: File, destination: File): File? = runCatching {
        val sourcePath = source.canonicalPath
        if (!sourcePath.startsWith(cacheRoot.canonicalPath + File.separator)) return null
        if (!source.isFile || source.length() == 0L) return null
        check(destination.mkdirs() || destination.isDirectory)
        val identity = "$sourcePath|${source.length()}|${source.lastModified()}"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val target = destination.resolve("legacy_${key}.${source.extension.ifBlank { "bin" }}")
        if (target.isFile && target.length() == source.length()) return target
        val temporary = destination.resolve("migration_${UUID.randomUUID()}.tmp")
        try {
            source.copyTo(temporary, overwrite = false)
            check(temporary.length() == source.length())
            if (!temporary.renameTo(target)) {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            target
        } finally { temporary.delete() }
    }.getOrNull()
}

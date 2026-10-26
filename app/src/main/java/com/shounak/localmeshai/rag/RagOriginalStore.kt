package com.shounak.localmeshai.rag

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Original uploads live independently of chat sessions and embedding model migrations. */
internal class RagOriginalStore(private val root: File) {
    private fun directory(id: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, key)
    }

    @Synchronized fun find(id: String): File? = directory(id).listFiles()
        ?.firstOrNull { it.isFile && !it.name.startsWith(".pending_") }

    private fun safeName(name: String): String = name.replace('\\', '/').substringAfterLast('/').take(180)
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: "document.bin"

    /**
     * The retained original of [id] when it already holds exactly the bytes of [source] under
     * [name], so the caller can skip a redundant copy. Reads only; an atomic [save] running
     * concurrently replaces the file by rename, so a comparison never sees a partial file.
     */
    fun findIdentical(id: String, name: String, source: File, checkActive: () -> Unit = {}): File? {
        val existing = find(id) ?: return null
        if (existing.name != safeName(name) || !source.isFile || existing.length() != source.length()) return null
        return existing.takeIf { sameBytes(it, source, checkActive) }
    }

    private fun sameBytes(first: File, second: File, checkActive: () -> Unit): Boolean {
        first.inputStream().use { left ->
            second.inputStream().use { right ->
                val a = ByteArray(8192)
                val b = ByteArray(8192)
                var count: Int
                do {
                    checkActive()
                    count = left.readBlock(a)
                    if (right.readBlock(b) != count) return false
                    for (i in 0 until count) if (a[i] != b[i]) return false
                } while (count > 0)
            }
        }
        return true
    }

    private fun InputStream.readBlock(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    @Synchronized fun save(id: String, name: String, input: InputStream, checkActive: () -> Unit = {}): File {
        val dir = directory(id).apply { check(mkdirs() || isDirectory) { "Could not retain uploaded file" } }
        val target = File(dir, safeName(name))
        val pending = File(dir, ".pending_${UUID.randomUUID()}")
        try {
            pending.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20L * 1024 * 1024) { "Document exceeds 20 MiB" }
                    output.write(buffer, 0, count)
                }
                require(total > 0) { "Uploaded file is empty" }
            }
            checkActive()
            try { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            dir.listFiles()?.filter { it != target && it.isFile && !it.name.startsWith(".pending_") }?.forEach { it.delete() }
            return target
        } finally { pending.delete() }
    }

    @Synchronized fun remove(id: String) {
        val dir = directory(id)
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        dir.delete()
    }
}

package com.shounak.localmeshai.utils

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pluggable atomic file transaction interface.
 * Abstracts Android's AtomicFile for production and test injectability.
 */
interface AtomicFileTransaction {
    val file: File
    val recoveryBackupFile: File get() = File(file.parentFile, "${file.name}.bak")
    fun exists(): Boolean
    fun openRead(): InputStream
    fun startWrite(): OutputStream
    fun finishWrite(stream: OutputStream)
    fun failWrite(stream: OutputStream)
    fun delete(): Boolean
}

/**
 * Android framework implementation of [AtomicFileTransaction] delegating to [android.util.AtomicFile].
 */
class AndroidAtomicFileTransaction(override val file: File) : AtomicFileTransaction {
    // AtomicFile owns .bak; application recovery must never write to that path.
    override val recoveryBackupFile: File get() = File(file.parentFile, "${file.name}.lastgood")
    private val delegate = android.util.AtomicFile(file)
    private var activeStream: OutputStream? = null

    override fun exists(): Boolean = file.exists() || File(file.parentFile, "${file.name}.bak").exists()

    override fun openRead(): InputStream = if (file.exists()) file.inputStream() else delegate.openRead()

    override fun startWrite(): OutputStream {
        val stream = delegate.startWrite()
        activeStream = stream
        return stream
    }

    override fun finishWrite(stream: OutputStream) {
        val target = (stream as? java.io.FileOutputStream) ?: (activeStream as? java.io.FileOutputStream)
        if (target != null) {
            delegate.finishWrite(target)
        } else {
            stream.flush()
            stream.close()
        }
        activeStream = null
    }

    override fun failWrite(stream: OutputStream) {
        val target = (stream as? java.io.FileOutputStream) ?: (activeStream as? java.io.FileOutputStream)
        if (target != null) {
            delegate.failWrite(target)
        } else {
            try { stream.close() } catch (_: Throwable) {}
        }
        activeStream = null
    }

    override fun delete(): Boolean {
        delegate.delete()
        return !file.exists()
    }
}

/**
 * Standard JVM implementation of [AtomicFileTransaction] using temp files and atomic replace.
 * Works reliably cross-platform without Android framework dependencies in desktop unit tests.
 */
open class StandardAtomicFileTransaction(override val file: File) : AtomicFileTransaction {
    private val tempFile = File(file.parentFile ?: File("."), "${file.name}.tmp")
    private val backupFile = File(file.parentFile ?: File("."), "${file.name}.bak")
    private var activeStream: OutputStream? = null

    override fun exists(): Boolean = file.exists() || backupFile.exists()

    override fun openRead(): InputStream {
        if (!file.exists() && backupFile.exists()) {
            backupFile.renameTo(file)
        }
        return java.io.FileInputStream(file)
    }

    override fun startWrite(): OutputStream {
        file.parentFile?.mkdirs()
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val stream = java.io.FileOutputStream(tempFile)
        activeStream = stream
        return stream
    }

    override fun finishWrite(stream: OutputStream) {
        stream.flush()
        if (stream is java.io.FileOutputStream) {
            try { stream.fd.sync() } catch (_: Throwable) {}
        }
        stream.close()
        activeStream = null

        if (file.exists()) {
            try {
                Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Throwable) {}
        }

        val source = tempFile.toPath()
        val target = file.toPath()
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    override fun failWrite(stream: OutputStream) {
        try { stream.close() } catch (_: Throwable) {}
        activeStream = null
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    override fun delete(): Boolean {
        if (tempFile.exists()) tempFile.delete()
        if (backupFile.exists()) backupFile.delete()
        if (file.exists()) file.delete()
        return !file.exists()
    }
}

/**
 * Checks if running on an Android runtime (ART/Dalvik) or standard desktop JVM.
 */
fun isAndroidRuntime(): Boolean {
    val vmName = System.getProperty("java.vm.name") ?: ""
    return vmName.contains("Dalvik", ignoreCase = true) || vmName.contains("Art", ignoreCase = true)
}

/**
 * Creates the appropriate [AtomicFileTransaction] for the current environment.
 */
fun createAtomicFileTransaction(file: File): AtomicFileTransaction {
    return if (isAndroidRuntime()) {
        try {
            AndroidAtomicFileTransaction(file)
        } catch (_: Throwable) {
            StandardAtomicFileTransaction(file)
        }
    } else {
        StandardAtomicFileTransaction(file)
    }
}

/**
 * Result of reading from an [AtomicJsonFile].
 */
/**
 * Thrown when both primary storage and its backup fail or contain corrupt data.
 */
class CompositeCorruptException(
    val primaryCause: Throwable,
    val backupCause: Throwable
) : java.io.IOException(
    "Primary storage failed: ${primaryCause.message}; backup recovery failed: ${backupCause.message}",
    primaryCause
) {
    init {
        addSuppressed(backupCause)
    }
}

class UnsupportedVersionException(
    val foundVersion: Int,
    val maxSupportedVersion: Int
) : java.io.IOException("File version $foundVersion exceeds maximum supported version $maxSupportedVersion")

/**
 * Result of reading from an [AtomicJsonFile].
 */
sealed class AtomicReadResult<out T> {
    data object Missing : AtomicReadResult<Nothing>()
    data class Success<T>(val data: T) : AtomicReadResult<T>()
    data class UnsupportedVersion(
        val file: File,
        val foundVersion: Int,
        val maxSupportedVersion: Int
    ) : AtomicReadResult<Nothing>()
    data class Corrupt(
        val file: File,
        val cause: Throwable,
        val backupFile: File? = null
    ) : AtomicReadResult<Nothing>()
}

/**
 * Manages atomic JSON file persistence with corrupt data preservation and transactional writes.
 */
class AtomicJsonFile<T>(
    val file: File,
    val transaction: AtomicFileTransaction = createAtomicFileTransaction(file)
) {
    fun read(parser: (String) -> T): AtomicReadResult<T> {
        val backupFile = transaction.recoveryBackupFile.takeIf { it.exists() }
            ?: File(file.parentFile ?: File("."), "${file.name}.bak")
        if (!transaction.exists() && !backupFile.exists()) {
            return AtomicReadResult.Missing
        }

        var primaryContent: String? = null
        var primaryCause: Throwable? = null

        if (transaction.exists()) {
            try {
                primaryContent = transaction.openRead().use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (e: Throwable) {
                primaryCause = e
            }
        } else {
            primaryCause = java.io.FileNotFoundException("Primary file does not exist")
        }

        if (primaryContent != null) {
            if (primaryContent.isBlank()) {
                // An existing empty file may be truncated storage, not a fresh install.
                primaryCause = IllegalStateException("Primary file is blank")
            } else {
                try {
                    val parsed = parser(primaryContent)
                    return AtomicReadResult.Success(parsed)
                } catch (e: UnsupportedVersionException) {
                    return AtomicReadResult.UnsupportedVersion(file, e.foundVersion, e.maxSupportedVersion)
                } catch (e: Throwable) {
                    primaryCause = e
                }
            }
        }

        // Primary read failed or was corrupt. Fall back to backup if present.
        if (backupFile.exists()) {
            val backupContent = try {
                backupFile.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Throwable) {
                null
            }

            if (backupContent != null && backupContent.isNotBlank()) {
                try {
                    val recovered = parser(backupContent)
                    // Heal primary storage transactionally
                    try {
                        val out = transaction.startWrite()
                        try {
                            out.write(backupContent.toByteArray(Charsets.UTF_8))
                            out.flush()
                            transaction.finishWrite(out)
                        } catch (writeErr: Throwable) {
                            transaction.failWrite(out)
                        }
                    } catch (_: Throwable) {}
                    return AtomicReadResult.Success(recovered)
                } catch (e: UnsupportedVersionException) {
                    return AtomicReadResult.UnsupportedVersion(backupFile, e.foundVersion, e.maxSupportedVersion)
                } catch (backupParseError: Throwable) {
                    val composite = CompositeCorruptException(
                        primaryCause ?: IllegalStateException("Primary read failed"),
                        backupParseError
                    )
                    return preserveCorruptFile(composite, backupFile)
                }
            } else {
                val backupCause = IllegalStateException("Backup file is blank or unreadable")
                val composite = CompositeCorruptException(
                    primaryCause ?: IllegalStateException("Primary read failed"),
                    backupCause
                )
                return preserveCorruptFile(composite, backupFile)
            }
        }

        // Missing backup is normal
        val cause = primaryCause ?: IllegalStateException("Primary file missing or corrupt")
        return preserveCorruptFile(cause, null)
    }

    private fun preserveCorruptFile(cause: Throwable, backupFile: File? = null): AtomicReadResult.Corrupt {
        val parent = file.parentFile ?: File(".")
        var corruptCopy: File? = null
        try {
            val source = file.takeIf { it.exists() } ?: backupFile?.takeIf { it.exists() }
            if (source != null) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                corruptCopy = File(parent, "${file.name}.corrupt_$hash")
                if (!corruptCopy.exists()) source.copyTo(corruptCopy)
            }
        } catch (_: Throwable) {
            // Ignore backup copy failure, return corrupt status
        }
        return AtomicReadResult.Corrupt(file = file, cause = cause, backupFile = corruptCopy)
    }

    fun write(data: T, serializer: (T) -> String): Result<Unit> {
        val serialized = try {
            serializer(data)
        } catch (e: Throwable) {
            return Result.failure(e)
        }

        val backup = transaction.recoveryBackupFile
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() > 0) {
            try {
                file.copyTo(backup, overwrite = true)
            } catch (_: Throwable) {}
        }

        val stream = try {
            transaction.startWrite()
        } catch (e: Throwable) {
            return Result.failure(e)
        }

        try {
            val bytes = serialized.toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.flush()
            transaction.finishWrite(stream)
            return Result.success(Unit)
        } catch (e: Throwable) {
            try {
                transaction.failWrite(stream)
            } catch (_: Throwable) {}
            return Result.failure(e)
        }
    }

    fun delete(): Boolean {
        val backup = File(file.parentFile ?: File("."), "${file.name}.bak")
        if (backup.exists()) backup.delete()
        transaction.recoveryBackupFile.takeIf { it.exists() }?.delete()
        return transaction.delete()
    }
}

package com.shounak.localmeshai.utils

import android.content.Context
import android.os.Build
import com.shounak.localmeshai.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object LiteRtRuntimeCache {
    const val ROOT_DIR_NAME = "litert_runtime"
    private val leaseCounts = mutableMapOf<String, Int>()
    private val deletingPaths = mutableSetOf<String>()
    private val lock = Any()

    fun lease(cacheDir: File): AutoCloseable {
        val path = cacheDir.canonicalPathOrNull() ?: cacheDir.absolutePath
        synchronized(lock) {
            if (deletingPaths.contains(path)) {
                throw IllegalStateException("Cache directory $path is currently being deleted")
            }
            leaseCounts[path] = (leaseCounts[path] ?: 0) + 1
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    val rem = (leaseCounts[path] ?: 1) - 1
                    if (rem <= 0) leaseCounts.remove(path) else leaseCounts[path] = rem
                }
            }
        }
    }

    fun prepare(context: Context, modelId: String, backend: InferenceBackend, modelPath: String): File {
        val root = runtimeRoot(context).apply { mkdirs() }
        val modelFile = File(modelPath)
        val target = root.resolve(cacheKey(modelId, backend, modelFile, BuildConfig.VERSION_CODE,
            "${BuildConfig.LITERT_RUNTIME_VERSION}|${BuildConfig.BUILD_TYPE}|${BuildConfig.NATIVE_BUILD_ID}|${Build.FINGERPRINT}|${Build.HARDWARE}"))
        val path = target.canonicalPathOrNull() ?: target.absolutePath
        synchronized(lock) {
            if (deletingPaths.contains(path)) {
                throw IllegalStateException("Cache directory $path is currently being deleted")
            }
            target.mkdirs()
            leaseCounts[path] = (leaseCounts[path] ?: 0) + 1
        }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    internal fun cacheKey(modelId: String, backend: InferenceBackend, model: File, versionCode: Int,
                          runtimeIdentity: String = "cache-v2"): String {
        val identity = "cache-v2|${model.absolutePath}|${model.length()}|${model.lastModified()}|$versionCode|$runtimeIdentity"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return "${safeName(modelId)}-${backend.name.lowercase(Locale.US)}-$fingerprint"
    }

    fun release(context: Context, cacheDir: File?) {
        val path = cacheDir?.canonicalPathOrNull() ?: return
        var droppedToZero = false
        synchronized(lock) {
            val rem = (leaseCounts[path] ?: 1) - 1
            if (rem <= 0) {
                leaseCounts.remove(path)
                droppedToZero = true
            } else {
                leaseCounts[path] = rem
            }
        }
        if (cacheDir.isSafeChildOf(runtimeRoot(context))) {
            cacheDir.setLastModified(System.currentTimeMillis())
        }
        if (droppedToZero) {
            runCatching {
                CacheCleanupCoordinator.getInstance(context).requestCleanup(CleanupReason.DeferredReaderCompletion)
            }
        }
    }

    fun clear(context: Context, cacheDir: File?) {
        cacheDir ?: return
        val path = cacheDir.canonicalPathOrNull() ?: cacheDir.absolutePath
        val canDelete = synchronized(lock) {
            val count = leaseCounts[path] ?: 0
            if (count <= 0 && !deletingPaths.contains(path)) {
                deletingPaths.add(path)
                true
            } else {
                false
            }
        }
        if (canDelete) {
            try {
                val root = runtimeRoot(context)
                if (cacheDir.isSafeChildOf(root)) {
                    runCatching { cacheDir.deleteRecursively() }
                }
            } finally {
                synchronized(lock) {
                    deletingPaths.remove(path)
                }
            }
        }
    }

    fun activePaths(): Set<String> = synchronized(lock) { leaseCounts.keys.toSet() }

    fun isLeased(file: File): Boolean {
        val path = file.canonicalPathOrNull() ?: file.absolutePath
        return synchronized(lock) { (leaseCounts[path] ?: 0) > 0 }
    }

    fun prune(context: Context, now: Long = System.currentTimeMillis()): Int {
        return pruneRoot(runtimeRoot(context), now)
    }

    /** Keep recent warm starts bounded by age, count and disk usage. Active engines are exempt. */
    internal fun pruneRoot(root: File, now: Long, maxEntries: Int = 3,
                           maxBytes: Long = 1024L * 1024 * 1024): Int {
        if (!root.isDirectory) return 0
        val toDelete = mutableListOf<File>()
        synchronized(lock) {
            var retained = 0
            var retainedBytes = 0L
            root.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEach { child ->
                val path = child.canonicalPathOrNull() ?: return@forEach
                if (!child.isSafeChildOf(root) || (leaseCounts[path] ?: 0) > 0 || path in deletingPaths) return@forEach
                val bytes = child.walkTopDown().onEnter { it.isSafeChildOf(root) }
                    .filter { it.isFile && it.isSafeChildOf(root) }.sumOf { it.length() }
                val fresh = child.lastModified() > 0 && now - child.lastModified() < 7L * 24 * 60 * 60 * 1000
                if (fresh && retained < maxEntries && bytes <= maxBytes - retainedBytes) {
                    retained++
                    retainedBytes += bytes
                } else {
                    deletingPaths.add(path)
                    toDelete.add(child)
                }
            }
        }
        return deleteReserved(root, toDelete)
    }

    /**
     * Removes all inactive compiled model caches regardless of age or count.
     * Protects active caches and synchronizes deletion atomically.
     */
    fun clearInactive(context: Context): Int {
        return clearInactiveRoot(runtimeRoot(context))
    }

    fun clearInactiveRoot(root: File): Int {
        if (!root.exists() || !root.isDirectory) return 0
        val toDelete = mutableListOf<File>()
        synchronized(lock) {
            val children = root.listFiles().orEmpty()
            for (child in children) {
                val path = child.canonicalPathOrNull() ?: child.absolutePath
                if ((leaseCounts[path] ?: 0) == 0 && !deletingPaths.contains(path)) {
                    deletingPaths.add(path)
                    toDelete.add(child)
                }
            }
        }

        return deleteReserved(root, toDelete)
    }

    private fun deleteReserved(root: File, toDelete: List<File>): Int {
        var deletedCount = 0
        try {
            for (dir in toDelete) {
                if (dir.isSafeChildOf(root)) {
                    val success = runCatching {
                        if (dir.isDirectory) dir.deleteRecursively() else dir.delete()
                    }.getOrDefault(false)
                    if (success) deletedCount++
                }
            }
        } finally {
            synchronized(lock) {
                toDelete.forEach { dir ->
                    val path = dir.canonicalPathOrNull() ?: dir.absolutePath
                    deletingPaths.remove(path)
                }
            }
        }
        return deletedCount
    }

    fun pruneOnStartup(context: Context) {
        prune(context)
        AppCacheCleaner.clearAppCache(context)
    }

    internal fun runtimeRoot(context: Context): File = context.cacheDir.resolve(ROOT_DIR_NAME)

    private fun safeName(raw: String): String {
        return raw.ifBlank { "model" }
            .lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9._-]+"""), "_")
            .take(96)
            .ifBlank { "model" }
    }

    private fun File.isSafeChildOf(parent: File): Boolean {
        val rootPath = parent.canonicalPathOrNull() ?: return false
        val childPath = canonicalPathOrNull() ?: return false
        return childPath.startsWith(rootPath + File.separator)
    }

    private fun File.canonicalPathOrNull(): String? {
        return runCatching { canonicalFile.absolutePath }.getOrNull()
    }
}

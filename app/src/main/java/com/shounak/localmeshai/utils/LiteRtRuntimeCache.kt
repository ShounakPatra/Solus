package com.shounak.localmeshai.utils

import android.content.Context
import java.io.File
import java.util.Locale

object LiteRtRuntimeCache {
    private const val ROOT_DIR_NAME = "litert_runtime"
    private const val LARGE_CACHE_CHILD_BYTES = 64L * 1024L * 1024L
    private val transientCacheDirs = setOf(
        ROOT_DIR_NAME,
        "vision_questions",
        "multimodal_inputs",
        "camera_inputs"
    )

    fun prepare(context: Context, modelId: String, backend: InferenceBackend): File {
        val root = runtimeRoot(context).apply { mkdirs() }
        val target = root.resolve("${safeName(modelId)}-${backend.name.lowercase(Locale.US)}")
        pruneRuntimeRoot(root, keep = target)
        target.mkdirs()
        return target
    }

    fun clear(context: Context, cacheDir: File?) {
        cacheDir ?: return
        val root = runtimeRoot(context)
        if (cacheDir.isSafeChildOf(root)) {
            runCatching { cacheDir.deleteRecursively() }
        }
    }

    fun pruneOnStartup(context: Context) {
        val cacheRoot = context.cacheDir
        transientCacheDirs.forEach { name ->
            runCatching { cacheRoot.resolve(name).deleteRecursively() }
        }
        cacheRoot.listFiles()
            ?.filter { it.name != "vision_sessions" }
            ?.filter { child ->
                child.name.contains("litert", ignoreCase = true) ||
                    child.name.contains("llm", ignoreCase = true) ||
                    child.safeSizeBytes(limit = LARGE_CACHE_CHILD_BYTES) >= LARGE_CACHE_CHILD_BYTES
            }
            ?.forEach { child ->
                runCatching {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
    }

    private fun runtimeRoot(context: Context): File = context.cacheDir.resolve(ROOT_DIR_NAME)

    private fun pruneRuntimeRoot(root: File, keep: File) {
        root.listFiles()
            ?.filterNot { it.canonicalPathOrNull() == keep.canonicalPathOrNull() }
            ?.forEach { child ->
                runCatching {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
    }

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
        return childPath == rootPath || childPath.startsWith(rootPath + File.separator)
    }

    private fun File.canonicalPathOrNull(): String? {
        return runCatching { canonicalFile.absolutePath }.getOrNull()
    }

    private fun File.safeSizeBytes(limit: Long): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(this)
        while (stack.isNotEmpty() && total < limit) {
            val current = stack.removeLast()
            current.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) {
                    stack.add(child)
                } else {
                    total += child.length()
                    if (total >= limit) return total
                }
            }
        }
        return total
    }
}

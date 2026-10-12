package com.shounak.localmeshai.utils

import android.content.Context
import android.util.Log
import java.io.File

object AppCacheCleaner {
    private const val TAG = "AppCacheCleaner"

    private val cleanLock = Any()

    /**
     * Cleans up expired temporary files and directories from internal and external cache directories,
     * as well as pruning LiteRT runtime compiled kernels according to its retention policy,
     * while preserving active leases ([ActiveInputFiles]), active runtime kernels ([LiteRtRuntimeCache.activePaths]),
     * fresh unowned inputs (< 24 hours), and durable chat attachments ([savedAttachmentPaths]).
     *
     * Returns the total count of deleted cache files and directories.
     */
    fun clearAppCache(context: Context, now: Long = System.currentTimeMillis()): Int {
        return synchronized(cleanLock) {
            runCatching {
                val protectedPaths = mutableSetOf<String>()
                protectedPaths.addAll(savedAttachmentPaths(context))

                var deletedCount = 0
                // Prune LiteRT runtime compiled kernels (clearing all inactive, unleased kernels)
                deletedCount += LiteRtRuntimeCache.clearInactive(context)

                // Prune unleased temporary inputs
                deletedCount += pruneExpiredInputs(context.cacheDir, now, protectedPaths)

                // Prune external cache directory if available
                val externalCache = runCatching { context.externalCacheDir }.getOrNull()
                if (externalCache != null) {
                    deletedCount += pruneExpiredInputs(externalCache, now, protectedPaths)
                }

                Log.d(TAG, "Cleared $deletedCount app storage cache items")
                deletedCount
            }.onFailure { e ->
                Log.e(TAG, "Error while clearing app storage cache", e)
            }.getOrDefault(0)
        }
    }

    /**
     * Recursively clears files and subdirectories in [cacheRoot], skipping any file or directory
     * whose canonical path is in [protectedPaths], is protected by [ActiveInputFiles],
     * or belongs to the LiteRT runtime cache root.
     *
     * Empty subdirectories are cleaned up once their contents are removed.
     * Returns the number of files and directories deleted.
     */
    fun clearStorageCacheDir(cacheRoot: File?, protectedPaths: Set<String> = emptySet()): Int {
        if (cacheRoot == null || !cacheRoot.exists() || !cacheRoot.isDirectory) return 0
        return deleteUnprotectedContents(cacheRoot, cacheRoot, protectedPaths)
    }

    private fun deleteUnprotectedContents(root: File, current: File, protectedPaths: Set<String>): Int {
        var count = 0
        val entries = current.listFiles() ?: return 0
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
        for (entry in entries) {
            val canonical = runCatching { entry.canonicalPath }.getOrNull() ?: entry.absolutePath
            if (!canonical.startsWith(rootCanonical + File.separator)) {
                continue
            }
            // Exclude the entire LiteRT cache root from generic cleanup
            if (entry.name == LiteRtRuntimeCache.ROOT_DIR_NAME) {
                continue
            }
            if (canonical in protectedPaths || ActiveInputFiles.isProtected(entry)) {
                continue
            }
            if (entry.isDirectory) {
                count += deleteUnprotectedContents(root, entry, protectedPaths)
                val remaining = entry.listFiles()
                if (remaining != null && remaining.isEmpty()) {
                    if (entry.delete()) count++
                }
            } else if (entry.isFile) {
                if (ActiveInputFiles.deleteIfUnreferenced(entry)) {
                    count++
                }
            }
        }
        return count
    }

    internal fun pruneExpiredInputs(root: File, now: Long = System.currentTimeMillis(),
                                    protectedPaths: Set<String> = emptySet()): Int {
        return listOf("camera_inputs", "multimodal_inputs", "vision_questions", "image_previews")
            .sumOf { name ->
                val directory = root.resolve(name)
                if (!directory.canonicalPath.startsWith(root.canonicalPath + File.separator)) return@sumOf 0
                directory.listFiles().orEmpty().count { file ->
                    file.isFile && file.canonicalPath !in protectedPaths && file.canonicalFile.parentFile == directory.canonicalFile &&
                        ActiveInputFiles.deleteIfUnreferenced(file)
                }
            }
    }

    internal fun savedAttachmentPaths(context: Context): Set<String> {
        val paths = mutableSetOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is org.json.JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is org.json.JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key in setOf("imagePath", "audioPath", "documentPath") && child is String && child.isNotBlank()) {
                            runCatching {
                                val f = File(child)
                                paths.add(f.canonicalPath)
                                paths.add(f.absolutePath)
                            }
                        } else {
                            visit(child)
                        }
                    }
                }
            }
        }

        fun parseAndVisit(raw: String?) {
            if (raw.isNullOrBlank()) return
            val trimmed = raw.trim()
            runCatching {
                if (trimmed.startsWith("{")) {
                    visit(org.json.JSONObject(trimmed))
                } else if (trimmed.startsWith("[")) {
                    visit(org.json.JSONArray(trimmed))
                }
            }
        }

        // 1. Files in filesDir (V2 chat and vision sessions, including backups)
        val filesDir = runCatching { context.filesDir }.getOrNull()
        if (filesDir != null) {
            val sessionFiles = listOf(
                "chat_sessions_v2.json",
                "vision_sessions_v2.json",
                "chat_sessions_v2.json.bak",
                "vision_sessions_v2.json.bak"
            )
            for (fileName in sessionFiles) {
                val f = filesDir.resolve(fileName)
                if (f.exists() && f.isFile) {
                    runCatching { parseAndVisit(f.readText()) }
                }
            }
        }

        // 2. Legacy SharedPreferences
        for (name in listOf("chat_history", "vision_chat_history", "chat_prefs", "vision_prefs")) {
            val raw = runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("sessions_json", null)
            }.getOrNull()
            parseAndVisit(raw)
        }
        return paths
    }

    /**
     * Recursively deletes all files and subdirectories inside [dir],
     * preserving the directory [dir] itself and any top-level children matching [excludedNames].
     */
    fun clearDirectoryContents(dir: File?, excludedNames: Set<String> = emptySet()): Int {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (excludedNames.contains(file.name)) return@forEach
            runCatching {
                val success = if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                if (success) count++
            }
        }
        return count
    }
}

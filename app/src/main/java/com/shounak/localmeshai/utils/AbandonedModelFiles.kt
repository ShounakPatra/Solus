package com.shounak.localmeshai.utils

import java.io.File

/** Only disposable, unpublished files with no resume receipt are eligible. */
internal object AbandonedModelFiles {
    private val processStartedAt = System.currentTimeMillis()
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private val draftName = Regex("draft_(?:[0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
    private val partName = Regex("[0-9a-f]{64}\\.part")

    // Pin this before import UI is available; a long-lived draft in this process stays protected.
    fun initialize() = Unit

    fun prune(modelRoot: File, repositoryRoot: File, now: Long = System.currentTimeMillis(),
        startedAt: Long = processStartedAt): Int {
        val cutoff = minOf(now, startedAt) - RETENTION_MS
        var deleted = 0
        for (draft in modelRoot.listFiles().orEmpty()) {
            if (!draftName.matches(draft.name) || !draft.isDirectory || draft.lastModified() >= cutoff) continue
            // Published generations, manifests and transaction recovery files are never drafts.
            if (draft.listFiles()?.all { it.name == ".staging" } != true) continue
            if (!containedTree(modelRoot, draft, cutoff)) continue
            if (ModelArtifactStorage.reserveAndExecuteDeletion(draft) {
                    containedTree(modelRoot, it, cutoff) && it.deleteRecursively()
                }) deleted++
        }
        val staging = File(repositoryRoot, "staging")
        if (!inside(repositoryRoot, staging)) return deleted
        for (part in staging.listFiles().orEmpty()) {
            if (!partName.matches(part.name) || !part.isFile || part.lastModified() >= cutoff) continue
            // Even an old paused transfer can still be resumed when its metadata exists.
            if (File(part.path + ".json").exists() || !inside(staging, part) ||
                java.nio.file.Files.isSymbolicLink(part.toPath())) continue
            if (ModelArtifactStorage.reserveAndExecuteDeletion(part) {
                    !File(it.path + ".json").exists() && it.lastModified() < cutoff &&
                        inside(staging, it) && ActiveInputFiles.deleteIfUnreferenced(it)
                }) deleted++
        }
        return deleted
    }

    private fun inside(root: File, file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun containedTree(root: File, draft: File, cutoff: Long): Boolean {
        if (!inside(root, draft)) return false
        var safe = true
        val unchanged = draft.walkTopDown().onEnter {
            val allowed = inside(root, it) && !java.nio.file.Files.isSymbolicLink(it.toPath())
            if (!allowed) safe = false
            allowed
        }.all {
            inside(root, it) && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                it.lastModified() < cutoff && !ActiveInputFiles.isProtected(it)
        }
        return safe && unchanged
    }
}

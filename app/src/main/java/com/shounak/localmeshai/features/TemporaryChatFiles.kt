package com.shounak.localmeshai.features

import com.shounak.localmeshai.utils.ActiveInputFiles
import java.io.File

/** Active conversations survive cache cleanup; abandoned files expire after a day. */
object TemporaryChatFiles {
    private val active = mutableSetOf<String>()
    @Synchronized fun retain(directory: File) { active.add(directory.canonicalPath) }
    @Synchronized fun discard(directory: File) {
        active.remove(directory.canonicalPath)
        // Native input readers hold file leases independently from conversation ownership.
        directory.walkBottomUp().forEach { file ->
            if (file.canonicalFile == directory.canonicalFile || WorkspaceBackup.inside(file, directory)) {
                if (file.isFile) ActiveInputFiles.deleteIfUnreferenced(file)
                else if (file.isDirectory && file.listFiles()?.isEmpty() == true) file.delete()
            }
        }
    }
    @Synchronized fun prune(root: File, now: Long): Int {
        if (!root.isDirectory) return 0
        var count = 0
        root.walkBottomUp().forEach { file ->
            val path = file.canonicalPath
            if (WorkspaceBackup.inside(file, root) && active.none { path == it || path.startsWith(it + File.separator) }) {
                if (file.isFile && file.lastModified() > 0 && now - file.lastModified() >= 86_400_000L && ActiveInputFiles.deleteIfUnreferenced(file)) count++
                else if (file.isDirectory && file.listFiles()?.isEmpty() == true && file.delete()) count++
            }
        }
        return count
    }
}

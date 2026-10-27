package com.shounak.localmeshai.ui.models

import java.io.File

/** Which stored image an assistant reply in vision history may show. */
internal object VisionHistoryImages {
    /**
     * An assistant reply shows an image only when it is a file this chat owns (where generated images are stored)
     * and it is not one of the chat's query photos, such as the session bitmap that v6.0.0 copied onto replies.
     */
    fun assistantImagePath(path: String?, userImagePaths: Collection<String>, isOwned: (File) -> Boolean): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val canonical = canonical(file) ?: return null
        if (userImagePaths.any { it.isNotBlank() && canonical(File(it)) == canonical }) return null
        return path.takeIf { isOwned(file) }
    }

    private fun canonical(file: File): String? = runCatching { file.canonicalPath }.getOrNull()
}

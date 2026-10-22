package com.shounak.localmeshai.utils

/** Preserve component directories while refusing traversal, URI decoding tricks and Windows aliases. */
internal object PortableBundlePath {
    fun normalize(raw: String): String {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.contains('\\') && !raw.contains(':')) { "Unsafe model bundle path" }
        val parts = raw.split('/')
        require(parts.all { part -> part.isNotBlank() && part != "." && part != ".." &&
            ModelArtifactStorage.sanitizeFileName(part) == part }) { "Unsafe model bundle component" }
        return parts.joinToString("/")
    }
}

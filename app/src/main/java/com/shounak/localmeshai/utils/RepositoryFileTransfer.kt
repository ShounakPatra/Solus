package com.shounak.localmeshai.utils

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Files-only downloads are kept outside runnable model storage and published only after verification. */
internal class RepositoryFileTransfer(private val root: File, private val client: OkHttpClient) {
    companion object {
        fun key(repo: String, revision: String, path: String) = digest("$repo@$revision/$path".toByteArray())
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun url(repo: String, revision: String, path: String): String = HttpUrl.Builder()
            .scheme("https").host("huggingface.co")
            .apply { repo.split('/').forEach { addPathSegment(it) } }
            .addPathSegment("resolve").addPathSegment(revision)
            .apply { safePath(path).split('/').forEach { addPathSegment(it) } }
            .addQueryParameter("download", "true").build().toString()
        private fun safePath(path: String): String {
            require(path.isNotBlank() && !path.contains('\\') && !path.contains(':')) { "Unsafe repository path" }
            require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." &&
                it == it.trim() && !it.endsWith('.') && it.none(Char::isISOControl) }) { "Unsafe repository path" }
            return path
        }
    }

    fun target(repo: String, revision: String, file: RepoFile): File {
        val base = root.canonicalFile
        val directory = File(base, key(repo, revision, "") + "/files").canonicalFile
        require(directory.path.startsWith(base.path + File.separator)) { "Unsafe repository directory" }
        val path = safePath(file.path)
        // The digest keeps distinct repository paths distinct even on case-insensitive storage.
        val target = File(directory, key(repo, revision, path) + "/" + path.substringAfterLast('/')).canonicalFile
        require(target.path.startsWith(directory.path + File.separator)) { "Unsafe repository path" }
        return target
    }

    /** Keep pre-upgrade downloads discoverable, but never alias a case-only sibling to them. */
    fun existingTarget(repo: String, revision: String, file: RepoFile): File {
        val modern = target(repo, revision, file)
        if (modern.isFile) return modern
        val directory = File(root.canonicalFile, key(repo, revision, "") + "/files").canonicalFile
        var legacy = directory
        for (segment in safePath(file.path).split('/')) {
            legacy = legacy.listFiles()?.singleOrNull { it.name == segment } ?: return modern
        }
        val resolved = legacy.canonicalFile
        require(resolved.path.startsWith(directory.path + File.separator)) { "Unsafe legacy repository path" }
        return if (resolved.isFile) resolved else modern
    }

    fun download(repo: String, revision: String, file: RepoFile,
        downloadUrl: String = url(repo, revision, file.path),
        checkActive: () -> Unit = {}, onProgress: (Long, Long) -> Unit = { _, _ -> },
        onCall: (okhttp3.Call?) -> Unit = {}): File =
        ModelArtifactStorage.leaseArtifact(File(root, "staging/" + key(repo, revision, file.path) + ".part")).use {
            downloadLeased(repo, revision, file, downloadUrl, checkActive, onProgress, onCall)
        }

    private fun downloadLeased(repo: String, revision: String, file: RepoFile, downloadUrl: String,
        checkActive: () -> Unit, onProgress: (Long, Long) -> Unit,
        onCall: (okhttp3.Call?) -> Unit = {}): File {
        val target = target(repo, revision, file)
        fun verify(candidate: File): Boolean {
            if (!candidate.isFile || (file.sizeBytes > 0 && candidate.length() != file.sizeBytes)) return false
            val expected = file.sha256 ?: return true
            val digest = MessageDigest.getInstance("SHA-256")
            candidate.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
        }
        val existing = existingTarget(repo, revision, file)
        if (verify(existing)) { onProgress(existing.length(), existing.length()); return existing }
        val part = File(root, "staging/" + key(repo, revision, file.path) + ".part")
        val metadata = File(part.path + ".json")
        part.parentFile?.mkdirs()
        val identity = org.json.JSONObject().put("url", downloadUrl).put("size", file.sizeBytes).put("sha256", file.sha256.orEmpty())
        val saved = runCatching { org.json.JSONObject(metadata.readText()) }.getOrNull()
        if (saved == null || saved.optString("url") != downloadUrl || saved.optLong("size") != file.sizeBytes ||
            saved.optString("sha256") != file.sha256.orEmpty() ||
            (file.sizeBytes > 0 && part.length() > file.sizeBytes)) {
            part.delete(); metadata.delete()
        }
        val completeSize = file.sizeBytes.takeIf { it > 0 } ?: saved?.optLong("total", -1L) ?: -1L
        if (completeSize > 0 && part.isFile && part.length() == completeSize) {
            if (verify(part)) {
                target.parentFile?.mkdirs()
                if (target.exists() && !target.delete()) throw IOException("Could not replace ${file.path}")
                if (!part.renameTo(target)) throw IOException("Could not publish ${file.path}")
                metadata.delete()
                onProgress(completeSize, completeSize)
                return target
            }
            part.delete(); metadata.delete()
        }
        val offset = part.takeIf { it.isFile }?.length() ?: 0L
        val builder = Request.Builder().url(downloadUrl).header("Accept-Encoding", "identity")
        if (offset > 0) {
            builder.header("Range", "bytes=$offset-")
            saved?.optString("validator")?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }
        }
        val call = client.newCall(builder.build())
        onCall(call)
        var discardPartial = false
        try {
            checkActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading ${file.path}")
                val append = response.code == 206
                val range = if (append) Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                    .matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("Invalid download range") else null
                if (append && (offset <= 0 || range!!.groupValues[1].toLong() != offset ||
                        range.groupValues[2].toLong() < offset || range.groupValues[2].toLong() >= range.groupValues[3].toLong()))
                    throw IOException("Incorrect download range")
                val total = range?.groupValues?.get(3)?.toLong() ?: response.body.contentLength()
                if (file.sizeBytes > 0 && total >= 0 && total != file.sizeBytes) throw IOException("Unexpected file size")
                val expected = file.sizeBytes.takeIf { it > 0 } ?: total
                val digest = file.sha256?.let { MessageDigest.getInstance("SHA-256") }
                var bytes = if (append) offset else 0L
                if (append && digest != null) part.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified").orEmpty()
                val oldValidator = saved?.optString("validator").orEmpty()
                if (append && oldValidator.isNotBlank() && validator.isNotBlank() && oldValidator != validator) {
                    discardPartial = true
                    throw IOException("Download source changed; select again to restart")
                }
                if (!append) part.delete()
                identity.put("total", expected)
                identity.put("validator", response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: response.header("Last-Modified").orEmpty())
                metadata.writeText(identity.toString())
                response.body.byteStream().use { input -> java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n
                        if (expected >= 0 && bytes > expected) { discardPartial = true; throw IOException("Unexpected size for ${file.path}") }
                        output.write(buffer, 0, n)
                        digest?.update(buffer, 0, n)
                        onProgress(bytes, expected)
                    }
                } }
                checkActive()
                if (expected >= 0 && bytes != expected) throw IOException("Incomplete download for ${file.path}; select again to resume")
                if (digest != null && !digest.digest().joinToString("") { "%02x".format(it) }.equals(file.sha256, true)) {
                    part.delete(); metadata.delete()
                    throw IOException("Checksum verification failed for ${file.path}")
                }
            }
            target.parentFile?.mkdirs()
            if (target.exists() && !target.delete()) throw IOException("Could not replace ${file.path}")
            if (!part.renameTo(target)) throw IOException("Could not publish ${file.path}")
            metadata.delete()
            return target
        } finally { onCall(null); if (discardPartial) { part.delete(); metadata.delete() } }
    }
}

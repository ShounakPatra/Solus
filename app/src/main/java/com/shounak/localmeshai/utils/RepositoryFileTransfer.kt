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
        val target = File(directory, safePath(file.path)).canonicalFile
        require(target.path.startsWith(directory.path + File.separator)) { "Unsafe repository path" }
        return target
    }

    fun download(repo: String, revision: String, file: RepoFile,
        downloadUrl: String = url(repo, revision, file.path),
        checkActive: () -> Unit = {}, onProgress: (Long, Long) -> Unit = { _, _ -> },
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
        if (verify(target)) { onProgress(target.length(), target.length()); return target }
        val part = File(root, "staging/" + key(repo, revision, file.path) + ".part")
        part.parentFile?.mkdirs()
        val call = client.newCall(Request.Builder().url(downloadUrl).build())
        onCall(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading ${file.path}")
                val length = response.body.contentLength()
                val expected = file.sizeBytes.takeIf { it > 0 } ?: length
                var bytes = 0L
                response.body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n
                        if (expected >= 0 && bytes > expected) throw IOException("Unexpected size for ${file.path}")
                        output.write(buffer, 0, n)
                        onProgress(bytes, expected)
                    }
                } }
                checkActive()
                if ((expected >= 0 && bytes != expected) || !verify(part)) throw IOException("Size or checksum verification failed for ${file.path}")
            }
            target.parentFile?.mkdirs()
            if (target.exists() && !target.delete()) throw IOException("Could not replace ${file.path}")
            if (!part.renameTo(target)) throw IOException("Could not publish ${file.path}")
            return target
        } finally { onCall(null); if (part.exists()) part.delete() }
    }
}

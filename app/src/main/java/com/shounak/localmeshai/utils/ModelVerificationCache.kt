package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ArtifactRole
import org.json.JSONObject
import java.io.File

/** Optional cache records. Missing, old, or unreadable records always cause fresh verification. */
class ModelVerificationCache(private val directory: File) {
    companion object {
        fun fileIdentity(file: File, role: ArtifactRole = ArtifactRole.Main): String =
            "$role:${file.canonicalPath}:${file.length()}:${file.lastModified()}"
    }
    private fun read(name: String): JSONObject? = when (val result =
        AtomicJsonFile<JSONObject>(File(directory, name)).read { JSONObject(it) }) {
        is AtomicReadResult.Success -> result.data
        else -> null
    }

    private fun write(name: String, json: JSONObject) {
        directory.mkdirs()
        AtomicJsonFile<JSONObject>(File(directory, name)).write(json) { it.toString() }
    }

    fun checksumMatches(file: File, expected: String, source: String): Boolean {
        val record = read("verified-checksum.json") ?: return false
        return file.isFile && file.lastModified() > 0L &&
            record.optString("path") == file.canonicalPath &&
            record.optLong("size", -1L) == file.length() &&
            record.optLong("modified", -1L) == file.lastModified() &&
            record.optString("source") == source &&
            record.optString("sha256").equals(expected.trim(), ignoreCase = true)
    }

    fun recordChecksum(file: File, verifiedSha256: String, source: String) = write(
        "verified-checksum.json", JSONObject().apply {
            put("path", file.canonicalPath)
            put("size", file.length())
            put("modified", file.lastModified())
            put("source", source)
            put("sha256", verifiedSha256.trim())
        }
    )

    fun readBundle(revision: String): ModelBundle? = runCatching {
        val record = read("verified-bundle.json") ?: return null
        if (record.optString("revision") != revision) return null
        ModelBundle.fromJson(record.getJSONObject("bundle"))
    }.getOrNull()

    fun recordBundle(revision: String, bundle: ModelBundle) = write(
        "verified-bundle.json", JSONObject().apply {
            put("revision", revision)
            put("bundle", bundle.toJson())
        }
    )
}

package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelPackage
import org.json.JSONObject
import java.io.File

/**
 * Persisted metadata for resuming partial downloads.
 * Never stores bearer tokens or sensitive credentials.
 */
data class DownloadResumeMetadata(
    val normalizedUrl: String,
    val packageType: ModelPackage,
    val expectedSha256: String? = null,
    val validator: String? = null, // Strong ETag or Last-Modified
    val expectedTotalBytes: Long = -1L,
    val lastDownloadedBytes: Long = 0L,
    val isCompleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceIdentity: String? = null,
    /** Failed attempts to publish this completed file; Resume downloads again once it is exhausted. */
    val publishFailures: Int = 0
) {
    fun toJson(): String = JSONObject().apply {
        put("normalizedUrl", normalizedUrl)
        put("packageType", packageType.name)
        put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
        put("validator", validator ?: JSONObject.NULL)
        put("expectedTotalBytes", expectedTotalBytes)
        put("lastDownloadedBytes", lastDownloadedBytes)
        put("isCompleted", isCompleted)
        put("updatedAt", updatedAt)
        put("sourceIdentity", sourceIdentity ?: JSONObject.NULL)
        put("publishFailures", publishFailures)
    }.toString(2)

    companion object {
        fun fromJson(jsonStr: String): DownloadResumeMetadata {
            val json = JSONObject(jsonStr)
            return DownloadResumeMetadata(
                normalizedUrl = json.getString("normalizedUrl"),
                packageType = runCatching {
                    ModelPackage.valueOf(json.optString("packageType", ModelPackage.SingleFile.name))
                }.getOrDefault(ModelPackage.SingleFile),
                expectedSha256 = if (json.isNull("expectedSha256") || !json.has("expectedSha256")) null else json.getString("expectedSha256"),
                validator = if (json.isNull("validator") || !json.has("validator")) null else json.getString("validator"),
                expectedTotalBytes = json.optLong("expectedTotalBytes", -1L),
                lastDownloadedBytes = json.optLong("lastDownloadedBytes", 0L),
                isCompleted = json.optBoolean("isCompleted", false),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                sourceIdentity = if (json.isNull("sourceIdentity") || !json.has("sourceIdentity")) null else json.getString("sourceIdentity"),
                publishFailures = json.optInt("publishFailures", 0).coerceAtLeast(0)
            )
        }

        fun metadataFileFor(stagingFile: File): File {
            return File(stagingFile.parentFile, "${stagingFile.name}.resume.json")
        }

        fun loadFor(stagingFile: File): DownloadResumeMetadata? {
            val metaFile = metadataFileFor(stagingFile)
            if (!metaFile.exists() || !metaFile.isFile) return null
            val atomic = AtomicJsonFile<DownloadResumeMetadata>(metaFile)
            return when (val res = atomic.read { fromJson(it) }) {
                is AtomicReadResult.Success -> res.data
                else -> null
            }
        }

        fun saveFor(stagingFile: File, metadata: DownloadResumeMetadata) {
            val metaFile = metadataFileFor(stagingFile)
            metaFile.parentFile?.mkdirs()
            val atomic = AtomicJsonFile<DownloadResumeMetadata>(metaFile)
            atomic.write(metadata) { it.toJson() }
        }

        fun deleteFor(stagingFile: File) {
            val metaFile = metadataFileFor(stagingFile)
            AtomicJsonFile<DownloadResumeMetadata>(metaFile).delete()
        }
    }
}

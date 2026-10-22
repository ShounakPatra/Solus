package com.shounak.localmeshai.models

import org.json.JSONArray
import org.json.JSONObject

data class DownloadArtifactSpec(
    val downloadUrl: String,
    val fileName: String,
    val role: ArtifactRole = ArtifactRole.Main,
    val shardIndex: Int = -1,
    val totalShards: Int = -1,
    val expectedSizeBytes: Long = 0L,
    val expectedSha256: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("downloadUrl", downloadUrl)
        put("fileName", fileName)
        put("role", role.name)
        put("shardIndex", shardIndex)
        put("totalShards", totalShards)
        put("expectedSizeBytes", expectedSizeBytes)
        if (expectedSha256 != null) put("expectedSha256", expectedSha256)
    }

    companion object {
        fun fromJson(json: JSONObject): DownloadArtifactSpec {
            return DownloadArtifactSpec(
                downloadUrl = json.getString("downloadUrl"),
                fileName = json.getString("fileName"),
                role = ArtifactRole.valueOf(json.optString("role", ArtifactRole.Main.name)),
                shardIndex = json.optInt("shardIndex", -1),
                totalShards = json.optInt("totalShards", -1),
                expectedSizeBytes = json.optLong("expectedSizeBytes", 0L),
                expectedSha256 = if (json.has("expectedSha256") && !json.isNull("expectedSha256")) json.getString("expectedSha256") else null
            )
        }
    }
}

data class ModelDownloadSpec(
    val modelId: String,
    val repository: String = "",
    val revision: String = "",
    val artifacts: List<DownloadArtifactSpec>,
    val packageType: ModelPackage = ModelPackage.SingleFile,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val format: ModelFormat = ModelFormat.GGUF
) {
    val totalExpectedBytes: Long
        get() = artifacts.sumOf { it.expectedSizeBytes }

    val effectiveFormat: ModelFormat
        get() {
            if (format != ModelFormat.GGUF) return format
            val firstFileName = artifacts.firstOrNull()?.fileName?.lowercase(java.util.Locale.ROOT).orEmpty()
            return when {
                firstFileName.endsWith(".task") -> ModelFormat.MediaPipeTask
                firstFileName.endsWith(".litertlm") -> ModelFormat.LiteRtLm
                else -> ModelFormat.fromFileName(firstFileName) ?: ModelFormat.GGUF
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("modelId", modelId)
        put("repository", repository)
        put("revision", revision)
        put("packageType", packageType.name)
        put("capabilities", capabilities.toJson())
        put("format", format.name)
        val arr = JSONArray()
        artifacts.forEach { arr.put(it.toJson()) }
        put("artifacts", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelDownloadSpec {
            val arr = json.getJSONArray("artifacts")
            val artifactsList = mutableListOf<DownloadArtifactSpec>()
            for (i in 0 until arr.length()) {
                artifactsList.add(DownloadArtifactSpec.fromJson(arr.getJSONObject(i)))
            }
            val formatStr = json.optString("format", "GGUF")
            val fmt = ModelFormat.entries.firstOrNull { it.name.equals(formatStr, ignoreCase = true) } ?: ModelFormat.GGUF
            return ModelDownloadSpec(
                modelId = json.getString("modelId"),
                repository = json.optString("repository", ""),
                revision = json.optString("revision", ""),
                artifacts = artifactsList,
                packageType = if (json.has("packageType")) ModelPackage.valueOf(json.getString("packageType")) else ModelPackage.SingleFile,
                capabilities = if (json.has("capabilities")) ModelCapabilities.fromJson(json.getJSONObject("capabilities")) else ModelCapabilities(),
                format = fmt
            )
        }
    }
}

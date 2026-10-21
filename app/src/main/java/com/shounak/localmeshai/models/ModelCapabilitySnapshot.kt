package com.shounak.localmeshai.models

/** The capability contract for one initialized artifact revision, shared by requests and UI. */
data class ModelCapabilitySnapshot(
    val modelId: String,
    val artifactRevision: String,
    val capabilities: ModelCapabilities,
    val initializedContextTokens: Int? = null
) {
    fun contextTokensFor(modelId: String, artifactRevision: String, ready: Boolean): Int? =
        initializedContextTokens?.takeIf {
            ready && it > 0 && this.modelId == modelId && this.artifactRevision == artifactRevision
        }

    companion object {
        fun revision(bundle: ModelBundle?, path: String): String = bundle?.allArtifacts?.joinToString(";") {
            "${it.role}:${it.source.location}:${it.sizeBytes}:${it.lastModified}"
        } ?: path
    }
}

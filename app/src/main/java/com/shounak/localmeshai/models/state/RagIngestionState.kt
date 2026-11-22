package com.shounak.localmeshai.models.state

/**
 * Granular state model for RAG document indexing and embedding generation.
 * Replaces coarse isIngesting boolean flag.
 */
sealed interface RagIngestionState {
    val isBusy: Boolean get() = this is Reading || this is Chunking || this is GeneratingEmbeddings || this is Storing
    val statusKey: String

    object Idle : RagIngestionState {
        override val statusKey: String = "idle"
    }

    data class Reading(val fileName: String) : RagIngestionState {
        override val statusKey: String = "reading"
    }

    data class Chunking(val fileName: String, val rawLength: Int) : RagIngestionState {
        override val statusKey: String = "chunking"
    }

    data class GeneratingEmbeddings(
        val fileName: String,
        val completedChunks: Int,
        val totalChunks: Int
    ) : RagIngestionState {
        override val statusKey: String = "generating_embeddings"
        val progressFraction: Float get() = if (totalChunks > 0) completedChunks.toFloat() / totalChunks.toFloat() else 0f
    }

    data class Storing(val fileName: String, val chunkCount: Int) : RagIngestionState {
        override val statusKey: String = "storing"
    }

    data class Success(val fileName: String, val chunkCount: Int) : RagIngestionState {
        override val statusKey: String = "success"
    }

    data class Failed(val fileName: String, val error: String) : RagIngestionState {
        override val statusKey: String = "failed"
    }

    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "isBusy" to isBusy
    )
}

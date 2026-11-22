package com.shounak.localmeshai.models.state

import android.graphics.Bitmap
import android.net.Uri

/**
 * Single source of truth for the Chat composer input state.
 * Prevents impossible combinations such as audio recording during document ingestion
 * or active voice transcription colliding with image selection.
 */
sealed interface ComposerInputState {
    val canType: Boolean get() = this !is AudioRecording && this !is AudioDecoding && this !is DocumentIngesting
    val canSend: Boolean
    val statusKey: String

    data class Text(
        val draft: String = ""
    ) : ComposerInputState {
        override val canSend: Boolean get() = draft.isNotBlank()
        override val statusKey: String = "text"
    }

    data class AudioRecording(
        val elapsedMs: Long = 0L,
        val isPaused: Boolean = false
    ) : ComposerInputState {
        override val canSend: Boolean get() = false
        override val statusKey: String = "audio_recording"
    }

    data class AudioDecoding(
        val elapsedMs: Long = 0L
    ) : ComposerInputState {
        override val canSend: Boolean get() = false
        override val statusKey: String = "audio_decoding"
    }

    data class MediaAttached(
        val draft: String = "",
        val imageBitmap: Bitmap? = null,
        val fileUri: Uri? = null,
        val audioBytes: ByteArray? = null
    ) : ComposerInputState {
        override val canSend: Boolean get() = draft.isNotBlank() || imageBitmap != null || fileUri != null || (audioBytes != null && audioBytes.isNotEmpty())
        override val statusKey: String = "media_attached"
    }

    data class DocumentIngesting(
        val fileName: String = "",
        val progressFraction: Float? = null
    ) : ComposerInputState {
        override val canSend: Boolean get() = false
        override val statusKey: String = "document_ingesting"
    }

    fun toAgentDescriptor(): Map<String, Any?> = mapOf(
        "status" to statusKey,
        "canType" to canType,
        "canSend" to canSend
    )
}

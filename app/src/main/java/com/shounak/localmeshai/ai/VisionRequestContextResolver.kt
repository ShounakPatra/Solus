package com.shounak.localmeshai.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import java.io.File

/**
 * Result of resolving vision media for a request.
 */
sealed class ResolvedVisionMedia {
    data class NewImage(
        val bitmap: Bitmap?,
        val durableFile: File? = null
    ) : ResolvedVisionMedia()

    data class ReusedImage(
        val imageFile: File
    ) : ResolvedVisionMedia()

    object DocumentOrAudioOnly : ResolvedVisionMedia()

    object None : ResolvedVisionMedia()

    data class AttachmentError(
        val message: String
    ) : ResolvedVisionMedia()
}

/**
 * Resolved context for a vision request.
 */
data class ResolvedVisionRequestContext(
    val effectivePrompt: String,
    val isImageOnlyPrompt: Boolean,
    val media: ResolvedVisionMedia,
    val imageFileToUse: File? = null,
    val bitmapToUse: Bitmap? = null
)

object VisionRequestContextResolver {
    const val DEFAULT_IMAGE_PROMPT = "Describe this image."

    /**
     * Resolves the effective question and media from the main-thread snapshot of the current session.
     */
    fun resolve(
        rawQuestion: String,
        newBitmap: Bitmap?,
        newFileUri: Uri?,
        newAudioUri: Uri?,
        newAudioBytes: ByteArray?,
        currentSessionMessages: List<VisionChatMessage>,
        currentSessionId: String
    ): ResolvedVisionRequestContext {
        val trimmedQuestion = rawQuestion.trim()

        // 1. Resolve media according to rules
        val media: ResolvedVisionMedia = when {
            // Rule 1: A newly attached image takes precedence.
            newBitmap != null && !newBitmap.isRecycled -> {
                ResolvedVisionMedia.NewImage(newBitmap)
            }

            // Rule 3: A new explicit document/audio request takes precedence over implicit image reuse.
            newFileUri != null || newAudioUri != null || (newAudioBytes != null && newAudioBytes.isNotEmpty()) -> {
                ResolvedVisionMedia.DocumentOrAudioOnly
            }

            // Rule 2: For a text-only follow-up, retain the most recent image in the same session as its active image context.
            else -> {
                findMostRecentSessionImage(currentSessionMessages)
            }
        }

        val hasImage = media is ResolvedVisionMedia.NewImage || media is ResolvedVisionMedia.ReusedImage
        val isImageOnlyPrompt = trimmedQuestion.isBlank() && hasImage

        // Rule for image-only sends:
        // Use "Describe this image." as the effective inference question.
        val effectivePrompt = if (isImageOnlyPrompt) {
            DEFAULT_IMAGE_PROMPT
        } else {
            trimmedQuestion
        }

        val imageFileToUse = when (media) {
            is ResolvedVisionMedia.ReusedImage -> media.imageFile
            else -> null
        }

        val bitmapToUse = when (media) {
            is ResolvedVisionMedia.NewImage -> media.bitmap
            else -> null
        }

        return ResolvedVisionRequestContext(
            effectivePrompt = effectivePrompt,
            isImageOnlyPrompt = isImageOnlyPrompt,
            media = media,
            imageFileToUse = imageFileToUse,
            bitmapToUse = bitmapToUse
        )
    }

    /**
     * Finds the most recent valid image in the current session messages.
     * Enforces:
     * - Rule 4: Never search another session.
     * - Rule 5: Never use an assistant thumbnail as the source image.
     * - Rule 6: A missing referenced file produces AttachmentUnavailable.
     */
    fun findMostRecentSessionImage(
        sessionMessages: List<VisionChatMessage>
    ): ResolvedVisionMedia {
        // Search backwards for the last user message containing an image
        val candidate = sessionMessages.findLast { message ->
            message.isUser && (!message.imagePath.isNullOrBlank() || (message.bitmap != null && !message.bitmap.isRecycled))
        } ?: return ResolvedVisionMedia.None

        val path = candidate.imagePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            return if (file.exists() && file.isFile) {
                ResolvedVisionMedia.ReusedImage(file)
            } else {
                ResolvedVisionMedia.AttachmentError("Referenced image file is unavailable at $path")
            }
        }

        val bitmap = candidate.bitmap
        return if (bitmap != null && !bitmap.isRecycled) {
            ResolvedVisionMedia.NewImage(bitmap)
        } else {
            ResolvedVisionMedia.AttachmentError("Referenced image is no longer available in memory")
        }
    }

    /**
     * Validates that an explicitly provided image file exists and is readable.
     * Throws an explicit [IllegalArgumentException] instead of silently dropping it.
     */
    fun validatePreparedImageFile(file: File?) {
        if (file == null) return
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("Attached image file does not exist: ${file.absolutePath}")
        }
    }

    /**
     * Decodes an image file off Main thread for stateless MediaPipe follow-ups.
     */
    fun decodeImageFile(file: File): Bitmap? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    /**
     * Seeds bounded history through [ConversationConfig.initialMessages] for LiteRT conversation restoration.
     * Excludes placeholders, failed-answer strings, and in-flight/current messages.
     */
    fun buildRestorationHistory(
        sessionMessages: List<VisionChatMessage>,
        currentRequestId: String = "",
        maxTurns: Int = 10,
        placeholderTexts: Set<String> = emptySet(),
        failedAnswerTexts: Set<String> = emptySet()
    ): List<Message> {
        val filtered = sessionMessages
            .filter { msg ->
                val text = msg.text.trim()
                val hasUserMedia = msg.isUser && (!msg.imagePath.isNullOrBlank() || (msg.bitmap != null && !msg.bitmap.isRecycled))
                val isValidText = text.isNotBlank() && text !in placeholderTexts && text !in failedAnswerTexts
                (isValidText || hasUserMedia) &&
                    msg.id != currentRequestId &&
                    msg.terminalStatus != RequestTerminalStatus.Failed
            }
            .takeLast(maxTurns * 2)

        val initialMessages = mutableListOf<Message>()
        for (msg in filtered) {
            if (msg.isUser) {
                val contents = mutableListOf<Content>()
                val imgPath = msg.imagePath
                if (!imgPath.isNullOrBlank()) {
                    val f = File(imgPath)
                    if (f.exists() && f.isFile) {
                        contents.add(Content.ImageFile(f.absolutePath))
                    }
                }
                if (msg.text.isNotBlank()) {
                    contents.add(Content.Text(msg.text))
                } else if (contents.isNotEmpty()) {
                    contents.add(Content.Text(DEFAULT_IMAGE_PROMPT))
                }
                if (contents.isNotEmpty()) {
                    initialMessages.add(Message.of(contents))
                }
            } else {
                if (msg.text.isNotBlank()) {
                    initialMessages.add(Message.model(msg.text))
                }
            }
        }
        return initialMessages
    }
}

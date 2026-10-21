package com.shounak.localmeshai.features

import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage

/** Prepare revisions without touching live state; callers validate model and attachment availability first. */
internal object ConversationEdits {
    fun text(messages: List<ChatMessage>, userId: String, replacement: String?): List<ChatMessage> {
        val index = messages.indexOfFirst { it.id == userId && it.isUser }
        require(index >= 0) { "Prompt no longer exists" }
        val text = replacement?.trim() ?: messages[index].text
        require(text.isNotBlank() || replacement == null && !messages[index].documentPath.isNullOrBlank()) { "Prompt cannot be empty" }
        return messages.take(index) + messages[index].copy(text = text)
    }
    fun vision(messages: List<VisionChatMessage>, userId: String, replacement: String?): List<VisionChatMessage> {
        val index = messages.indexOfFirst { it.id == userId && it.isUser }
        require(index >= 0) { "Prompt no longer exists" }
        val text = replacement?.trim() ?: messages[index].text
        // Attachment-only prompts can be regenerated without introducing text.
        require(text.isNotBlank() || replacement == null && messages[index].let {
            it.bitmap != null || !it.imagePath.isNullOrBlank() || !it.audioPath.isNullOrBlank() || !it.documentPath.isNullOrBlank()
        }) { "Prompt cannot be empty" }
        return messages.take(index) + messages[index].copy(text = text)
    }
}

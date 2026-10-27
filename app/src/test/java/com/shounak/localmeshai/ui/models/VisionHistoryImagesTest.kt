package com.shounak.localmeshai.ui.models

import com.shounak.localmeshai.data.ChatAttachmentStore
import com.shounak.localmeshai.data.ChatKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisionHistoryImagesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun assistantRepliesShowOnlyOwnedGeneratedImagesAndNeverTheQueryPhoto() {
        val store = ChatAttachmentStore(folder.newFolder("attachments"))
        val generated = store.getAttachmentDir(ChatKind.Vision, "chat", "assistant", true).resolve("generated.png").apply { writeBytes(byteArrayOf(1)) }
        val photo = store.getAttachmentDir(ChatKind.Vision, "chat", "user", true).resolve("photo.png").apply { writeBytes(byteArrayOf(2)) }
        val sessionBitmap = folder.newFolder("vision_sessions").resolve("session_chat.png").apply { writeBytes(byteArrayOf(3)) }
        val otherChat = store.getAttachmentDir(ChatKind.Vision, "other", "assistant", true).resolve("generated.png").apply { writeBytes(byteArrayOf(4)) }
        val owned = { file: java.io.File -> store.isOwnedAttachment(file, ChatKind.Vision, "chat") }
        val userImages = listOf(sessionBitmap.path, photo.path)

        assertEquals(generated.path, VisionHistoryImages.assistantImagePath(generated.path, userImages, owned))
        // v6.0.0 stored the session bitmap on assistant replies.
        assertNull(VisionHistoryImages.assistantImagePath(sessionBitmap.path, userImages, owned))
        assertNull(VisionHistoryImages.assistantImagePath(photo.path, userImages, owned))
        assertNull(VisionHistoryImages.assistantImagePath(otherChat.path, userImages, owned))
        assertNull(VisionHistoryImages.assistantImagePath(generated.path + ".missing", userImages, owned))
        assertNull(VisionHistoryImages.assistantImagePath(null, userImages, owned))
    }
}

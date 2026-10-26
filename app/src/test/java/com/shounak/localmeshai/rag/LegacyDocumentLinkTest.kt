package com.shounak.localmeshai.rag

import com.shounak.localmeshai.data.ChatAttachmentStore
import com.shounak.localmeshai.data.ChatKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyDocumentLinkTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun repairUsesOwnedPathsAndNeverFilenameMatchesAcrossChats() {
        val attachments = ChatAttachmentStore(files.newFolder())
        val owned = attachments.getAttachmentDir(ChatKind.Text, "current", "upload", true).resolve("summary.txt").apply { writeText("Owned text") }
        val id = LegacyDocumentLink.repairId(owned.path, null, attachments, ChatKind.Text, "current")
        assertNotNull(id)
        assertEquals(id, LegacyDocumentLink.repairId(owned.path, id, attachments, ChatKind.Text, "current"))
        assertNull(LegacyDocumentLink.repairId(owned.path, null, attachments, ChatKind.Text, "other-chat"))
        assertNull(LegacyDocumentLink.repairId(owned.path, null, attachments, ChatKind.Vision, "current"))
        assertNull(LegacyDocumentLink.repairId(files.newFile("summary.txt").path, null, attachments, ChatKind.Text, "current"))
    }
}

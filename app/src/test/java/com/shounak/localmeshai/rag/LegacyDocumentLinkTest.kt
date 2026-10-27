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
        assertNull(LegacyDocumentLink.repairId(owned.path, null, attachments, ChatKind.Text, "other-chat"))
        assertNull(LegacyDocumentLink.repairId(owned.path, null, attachments, ChatKind.Vision, "current"))
        assertNull(LegacyDocumentLink.repairId(files.newFile("summary.txt").path, null, attachments, ChatKind.Text, "current"))
    }

    @Test fun alreadyLinkedUploadsAreNotRepairedAgain() {
        val attachments = ChatAttachmentStore(files.newFolder())
        val owned = attachments.getAttachmentDir(ChatKind.Text, "current", "upload", true).resolve("notes.txt").apply { writeText("Owned text") }
        // A linked, indexed upload is left alone, so follow-up questions never re-extract or re-stamp it.
        assertNull(LegacyDocumentLink.repairId(owned.path, "linked", attachments, ChatKind.Text, "current"))
        assertNull(LegacyDocumentLink.repairId(owned.path, "linked", attachments, ChatKind.Text, "current", isIndexed = { true }))
        assertNull(LegacyDocumentLink.repairId(owned.path, " ", attachments, ChatKind.Text, "current", isIndexed = { false }))
        // A linked upload whose index entry is missing (a restored backup) is re-linked under its own id only.
        assertEquals("linked", LegacyDocumentLink.repairId(owned.path, "linked", attachments, ChatKind.Text, "current", isIndexed = { false }))
        assertNull(LegacyDocumentLink.repairId(owned.path, "linked", attachments, ChatKind.Text, "other-chat", isIndexed = { false }))
    }
}

package com.shounak.localmeshai.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSnapshotMergeTest {
    private data class Session(val id: String, val updated: Long, val text: String)

    @Test fun staleDiskReadPreservesUnsavedChatsAndNewerReplies() {
        val disk = listOf(Session("a", 1, "old"), Session("external", 3, "imported"))
        val visible = listOf(Session("a", 2, "reply"), Session("unsaved", 4, "new chat"))
        val merged = mergeSessionSnapshots(disk, visible, { it.id }, { it.updated }, { false })
        assertEquals(listOf("unsaved", "external", "a"), merged.map { it.id })
        assertEquals("reply", merged.last().text)
    }

    @Test fun tombstonesPreventEitherSnapshotFromResurrectingDeletedChats() {
        val merged = mergeSessionSnapshots(
            listOf(Session("deleted", 5, "disk")), listOf(Session("deleted", 6, "memory")),
            { it.id }, { it.updated }, { it == "deleted" }
        )
        assertEquals(emptyList<Session>(), merged)
    }
}

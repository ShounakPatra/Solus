package com.shounak.localmeshai.ui

import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistorySortingTest {

    @Test
    fun sessionsSortedByUpdatedAtDescending_putsMostRecentlyChattedAtTop() {
        val s1 = ChatSession(id = "1", title = "Chat 1", messages = emptyList(), updatedAt = 1000L)
        val s2 = ChatSession(id = "2", title = "Chat 2", messages = emptyList(), updatedAt = 5000L)
        val s3 = ChatSession(id = "3", title = "Chat 3", messages = emptyList(), updatedAt = 3000L)

        val sorted = listOf(s1, s2, s3).sortedWith(
            compareByDescending<ChatSession> { it.updatedAt }.thenBy { it.title }
        )

        assertEquals("Chat 2", sorted[0].title)
        assertEquals("Chat 3", sorted[1].title)
        assertEquals("Chat 1", sorted[2].title)
    }

    @Test
    fun chattingInExistingSession_movesItToTopWithLatestTimestamp() {
        val initialSessions = mutableListOf(
            ChatSession(id = "1", title = "Chat 1", messages = listOf(ChatMessage("hi", true)), updatedAt = 5000L),
            ChatSession(id = "2", title = "Chat 2", messages = listOf(ChatMessage("hello", true)), updatedAt = 10000L)
        )

        // Currently Chat 2 is at top
        val sortedBefore = initialSessions.sortedByDescending { it.updatedAt }
        assertEquals("2", sortedBefore[0].id)

        // User chats in Chat 1 at timestamp 15000L
        val chat1Index = initialSessions.indexOfFirst { it.id == "1" }
        val updatedChat1 = initialSessions[chat1Index].copy(
            updatedAt = 15000L,
            messages = initialSessions[chat1Index].messages + ChatMessage("new message", true)
        )
        initialSessions.removeAt(chat1Index)
        initialSessions.add(0, updatedChat1)

        assertEquals("1", initialSessions[0].id)
        assertEquals(15000L, initialSessions[0].updatedAt)

        val sortedAfter = initialSessions.sortedByDescending { it.updatedAt }
        assertEquals("Chat 1 must move to top of priority order", "1", sortedAfter[0].id)
        assertEquals("2", sortedAfter[1].id)
    }

    @Test
    fun mergedTextAndVisionSessions_followStrictPriorityOrderByLastChatted() {
        data class MergedItem(val id: String, val isVision: Boolean, val updatedAt: Long)

        val textSessions = listOf(
            ChatSession(id = "t1", title = "Text 1", messages = emptyList(), updatedAt = 1000L),
            ChatSession(id = "t2", title = "Text 2", messages = emptyList(), updatedAt = 9000L)
        )
        val visionSessions = listOf(
            VisionChatSession(id = "v1", title = "Vision 1", question = "q", answer = "a", updatedAt = 5000L),
            VisionChatSession(id = "v2", title = "Vision 2", question = "q", answer = "a", updatedAt = 12000L)
        )

        val merged = (textSessions.map { MergedItem(it.id, false, it.updatedAt) } +
            visionSessions.map { MergedItem(it.id, true, it.updatedAt) })
            .sortedWith(compareByDescending<MergedItem> { it.updatedAt }.thenBy { it.id })

        assertEquals("v2 (Vision 2, 12000L) should be at top", "v2", merged[0].id)
        assertEquals("t2 (Text 2, 9000L) should be 2nd", "t2", merged[1].id)
        assertEquals("v1 (Vision 1, 5000L) should be 3rd", "v1", merged[2].id)
        assertEquals("t1 (Text 1, 1000L) should be 4th", "t1", merged[3].id)
    }
}

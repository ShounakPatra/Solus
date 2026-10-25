package com.shounak.localmeshai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shounak.localmeshai.ui.screens.ChatHistoryDialog
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryDeletionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmedTextAndVisionDeletionRefreshesTheOpenDialog() {
        val text = mutableStateListOf(ChatSession(java.util.UUID.randomUUID().toString(), "Text deletion fixture", emptyList(), 1L))
        val vision = mutableStateListOf(VisionChatSession(id = java.util.UUID.randomUUID().toString(),
            title = "Vision deletion fixture", question = "question", answer = "answer", updatedAt = 2L))
        compose.setContent {
            MaterialTheme {
                ChatHistoryDialog(textSessions = text, visionSessions = vision,
                    currentTextSessionId = null, currentVisionSessionId = null, isVisionModeActive = false,
                    onSelectTextSession = {}, onSelectVisionSession = {}, onNewChat = {},
                    onDeleteTextSession = { id -> text.removeAll { it.id == id } },
                    onDeleteVisionSession = { id -> vision.removeAll { it.id == id } },
                    onClearAll = {}, onDismiss = {}, hazeState = remember { HazeState() })
            }
        }
        for (title in listOf("Text deletion fixture", "Vision deletion fixture")) {
            compose.onNodeWithContentDescription("Conversation actions for $title").performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
        }
        compose.onNodeWithText("No messages yet.").assertIsDisplayed()
    }
}

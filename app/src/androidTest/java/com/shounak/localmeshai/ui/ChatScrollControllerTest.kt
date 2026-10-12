package com.shounak.localmeshai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shounak.localmeshai.ui.models.ChatScrollController
import com.shounak.localmeshai.ui.models.rememberChatScrollController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScrollControllerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun userDragCancelsAutoFollowAndScrollToBottomRestoresIt() {
        lateinit var controller: ChatScrollController

        composeTestRule.setContent {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            controller = rememberChatScrollController(listState, scope)

            androidx.compose.runtime.LaunchedEffect(listState) {
                controller.observeUserInteractions()
            }
            androidx.compose.runtime.LaunchedEffect(listState) {
                controller.observeScrollPosition()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chat_list")
                ) {
                    items(50) { index ->
                        Text(
                            text = "Message turn $index with detailed response content",
                            modifier = Modifier.fillMaxWidth().height(60.dp)
                        )
                    }
                    item(key = "bottom_anchor") {
                        Spacer(modifier = Modifier.height(1.dp).testTag("bottom_anchor"))
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        assertTrue("Initially auto-follow is active", controller.followLatest)

        // User drags list up (scrolls down)
        composeTestRule.onNodeWithTag("chat_list").performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()

        // User drag cancels auto follow
        assertFalse("User gesture must disable auto-follow", controller.followLatest)

        // Programmatic scroll to bottom restores auto-follow
        composeTestRule.runOnUiThread {
            controller.scrollToBottom(anchorIndex = 50)
        }
        composeTestRule.waitForIdle()

        assertTrue("Scrolling to bottom restores auto-follow", controller.followLatest)
    }

    @Test
    fun conversationBindingResetsStateCleanly() {
        lateinit var controller: ChatScrollController

        composeTestRule.setContent {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            controller = rememberChatScrollController(listState, scope)
        }

        composeTestRule.waitForIdle()

        // Disabling follow
        composeTestRule.runOnUiThread {
            controller.scrollToTop()
        }
        composeTestRule.waitForIdle()
        assertFalse("Scroll to top disables auto-follow", controller.followLatest)

        // Switch conversation / mode
        composeTestRule.runOnUiThread {
            controller.bindConversation("session_2", "text")
        }
        composeTestRule.waitForIdle()

        assertTrue("Binding new conversation restores followLatest", controller.followLatest)
    }
}

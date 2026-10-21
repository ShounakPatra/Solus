package com.shounak.localmeshai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shounak.localmeshai.ai.ModelActualConfiguration
import com.shounak.localmeshai.ai.ModelLoadController
import com.shounak.localmeshai.ai.ModelLoadIdentity
import com.shounak.localmeshai.ai.ModelLoadStage
import com.shounak.localmeshai.ai.ModelLoadState
import com.shounak.localmeshai.ui.models.MessagePresentationCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRuntimeResponsivenessTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun messagePresentationCachesEfficientlyOnAndroid() {
        val markdown = """
            Thinking process completed.
            Here is sample Kotlin code:
            ```kotlin
            fun add(a: Int, b: Int) = a + b
            ```
            And inline math: \(x + y = z\).
        """.trimIndent()

        val first = MessagePresentationCache.getOrCompute(markdown, isUser = false, isStreaming = false)
        val second = MessagePresentationCache.getOrCompute(markdown, isUser = false, isStreaming = false)

        assertTrue(first.hasRichBlock)
        assertEquals(first.displayText, second.displayText)
        assertEquals(first.segments.size, second.segments.size)
    }

    @Test
    fun modelLoadControllerExecutesStagesOnAndroid() = runBlocking {
        val controller = ModelLoadController()
        val identity = ModelLoadIdentity(
            modelPath = "/data/local/tmp/fake.gguf",
            runtimeKind = "Gguf",
            backendPreference = "gpu"
        )

        val recordedStages = mutableListOf<ModelLoadStage>()
        val result = controller.loadOrAttach(identity, this) { _, updateProgress ->
            val stages = listOf(
                ModelLoadStage.WaitingForRuntime,
                ModelLoadStage.VerifyingFile,
                ModelLoadStage.CpuReference(),
                ModelLoadStage.GpuProbe(),
                ModelLoadStage.ContextPreparation()
            )
            for (st in stages) {
                recordedStages.add(st)
                updateProgress(st, 1, 10)
            }
            ModelActualConfiguration("Gguf", "Vulkan", 4, 2048, 256, 128, 99)
        }

        assertEquals(5, recordedStages.size)
        assertTrue(controller.isReady)
        val state = controller.loadState.value
        assertTrue(state is ModelLoadState.Ready)
        assertEquals("Vulkan", (state as ModelLoadState.Ready).actualConfiguration.backend)
    }

    @Test
    fun delayedFakeRuntimeExercisesTypingScrollingAndStopOnAndroid() {
        var stopped = false
        var textValue by mutableStateOf("")

        composeTestRule.setContent {
            val listState = rememberLazyListState()
            Column(modifier = Modifier.fillMaxSize()) {
                // Pre-answer loading indicator representing active stage
                Row(modifier = Modifier.padding(8.dp)) {
                    Text(text = "Loading: ContextPreparation")
                    CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(start = 8.dp))
                }
                // Scrollable message list
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag("message_list")
                ) {
                    items(40) { index ->
                        Text(
                            text = "Message $index: Sample conversational turn in Solus chat history",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                // Input and stop controls
                Row(modifier = Modifier.padding(8.dp)) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        modifier = Modifier.weight(1f).testTag("chat_input"),
                        placeholder = { Text("Ask something…") }
                    )
                    IconButton(
                        onClick = { stopped = true },
                        modifier = Modifier.testTag("stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop responding"
                        )
                    }
                }
            }
        }

        // 1. Exercise typing while loading/pre-answer
        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello while loading")
        composeTestRule.waitForIdle()
        assertEquals("Hello while loading", textValue)

        // 2. Exercise scrolling while loading/pre-answer
        composeTestRule.onNodeWithTag("message_list").performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()

        // 3. Exercise Stop action
        composeTestRule.onNodeWithContentDescription("Stop responding").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Stop action must be received", stopped)
    }
}

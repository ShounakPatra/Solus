package com.shounak.localmeshai.ai.tools

import com.shounak.localmeshai.ai.InferenceResponseController
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GgufToolTurnStreamTest {
    @Test fun finalRoundStreamsBeforeGenerationCompletesAndDoesNotDuplicate() = runBlocking {
        val controller = InferenceResponseController(true, "calculate", "request", 1)
        val visible = mutableListOf<String>()
        val chunks = mutableListOf<String>()
        val stream = GgufToolTurnStream({ answer, reasoning, thinking ->
            controller.appendStructured(answer, reasoning, thinking, "request", 1, replaceSnapshot = true)
            visible += controller.snapshot().answerText
        }, chunks::add)
        var rounds = 0
        val result = ToolCallingLoop.run(JSONArray(), listOf(CalculatorTool()), {}, {}) {
            if (rounds++ == 0) {
                stream.update("Let me calculate.", "", false)
                JSONObject("""{"content":"Let me calculate.","tool_calls":[{"id":"a","function":{"name":"calculator","arguments":{"expression":"17.5*23"}}}]}""")
                    .also(stream::complete)
            } else {
                assertEquals("", controller.snapshot().answerText)
                stream.update("402", "", false)
                assertEquals("402", controller.snapshot().answerText)
                assertEquals("402", visible.last()) // Before the final generation returns.
                stream.update("402.5", "", false)
                JSONObject().put("content", "402.5").also(stream::complete)
            }
        }
        assertEquals("402.5", result.getString("content"))
        assertEquals(listOf("Let me calculate.", "402", ".5"), chunks)
        assertEquals(listOf("Let me calculate.", "", "402", "402.5"), visible)
    }

    @Test fun reasoningAndFallbackResetAreAuthoritativeSnapshots() {
        val values = mutableListOf<Triple<String, String, Boolean>>()
        val stream = GgufToolTurnStream({ a, r, t -> values += Triple(a, r, t) }, {})
        stream.update("", "Thinking", true)
        stream.update("Yes", "Thinking", false)
        stream.complete(JSONObject().put("content", "Yes").put("reasoning_content", "Thinking"))
        assertEquals(2, values.size)
        stream.clear()
        assertEquals(Triple("", "", false), values.last())
    }
}

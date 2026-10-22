package com.shounak.localmeshai.ai.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolCallingLoopTest {
    private fun call(name: String = "calculator") = JSONObject("""{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"$name","arguments":"{\"expression\":\"17.5*23\"}"}}]}""")

    @Test fun executesAndFeedsResultBeforeFinalAnswer() = runBlocking<Unit> {
        val uses = mutableListOf<ToolUse>()
        var generations = 0
        val answer = ToolCallingLoop.run(JSONArray(), listOf(CalculatorTool()), uses::add, {}) { request ->
            val json = JSONObject(request)
            if (generations++ == 0) call() else {
                val messages = json.getJSONArray("messages")
                assertEquals("assistant", messages.getJSONObject(0).getString("role"))
                assertEquals("call_1", messages.getJSONObject(1).getString("tool_call_id"))
                assertEquals("402.5", messages.getJSONObject(1).getString("content"))
                JSONObject().put("content", "402.5")
            }
        }
        assertEquals("402.5", answer.getString("content"))
        assertEquals(1, uses.size)
    }
    @Test fun capsExecutionAndDisablesCallsOnLastGeneration() = runBlocking<Unit> {
        var executions = 0
        var generations = 0
        ToolCallingLoop.run(JSONArray(), listOf(CalculatorTool()), { executions++ }, {}) { request ->
            generations++
            if (JSONObject(request).getBoolean("allow_calls")) call() else JSONObject().put("content", "done")
        }
        assertEquals(3, executions)
        assertEquals(4, generations)
    }
    @Test fun unknownToolIsReturnedAsError() = runBlocking<Unit> {
        var rounds = 0
        ToolCallingLoop.run(JSONArray(), emptyList(), {}, {}) { request ->
            if (rounds++ == 0) call("unknown") else {
                assertTrue(JSONObject(request).getJSONArray("messages").getJSONObject(1)
                    .getString("content").startsWith("Error: Unknown tool"))
                JSONObject().put("content", "done")
            }
        }
    }
    @Test fun cancelledRequestDoesNotExecuteTools() = runBlocking<Unit> {
        var checks = 0
        var executions = 0
        try {
            ToolCallingLoop.run(JSONArray(), listOf(CalculatorTool()), { executions++ }, {
                if (++checks == 2) throw kotlinx.coroutines.CancellationException("cancelled")
            }) { call() }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, executions)
    }
}

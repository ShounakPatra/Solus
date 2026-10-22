package com.shounak.localmeshai.ai.tools

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ChatToolsTest {
    @Test fun precedenceAndAssociativity() {
        mapOf("17.5 × 23" to "402.5", "2+3*4" to "14", "(2+3)*4" to "20",
            "2^3^2" to "512", "-2^2" to "-4", "(-2)^2" to "4", "2^-2" to "0.25",
            "8/4/2" to "1", "9-3-2" to "4", "10%4*3" to "6", "2**3" to "8",
            "−6÷2" to "-3", ".5 + 1." to "1.5", "1e3+2E-2" to "1000.02",
            "--2" to "2", "1+-2" to "-1", " 2 + 3 " to "5")
            .forEach { (expression, result) -> assertEquals(expression, result, CalculatorEvaluator.evaluate(expression)) }
    }
    @Test fun rejectsBadInputAndNonFiniteResults() {
        listOf("", " ", ".", "1e", "1e+", "1..2", "2 3", "(1", "1)", "sin(2)",
            "1/0", "1%0", "1e999", "(-1)^.5", "10^999", "2+", "2//3", "NaN")
            .forEach { assertTrue(it, CalculatorEvaluator.evaluate(it).startsWith("Error: ")) }
    }
    @Test fun limitsBoundAllRecursivePaths() {
        assertEquals("1", CalculatorEvaluator.evaluate("(".repeat(64) + "1" + ")".repeat(64)))
        listOf("(".repeat(65) + "1" + ")".repeat(65), "-".repeat(65) + "1",
            "1^".repeat(65) + "1", "1".repeat(513)).forEach {
            assertTrue(CalculatorEvaluator.evaluate(it).startsWith("Error: "))
        }
        assertEquals("1", CalculatorEvaluator.evaluate(" ".repeat(511) + "1"))
    }
    @Test fun schemasAndCalculatorArguments() {
        listOf(CalculatorTool(), CurrentDateTimeTool()).forEach {
            val description = JSONObject(it.getToolDescriptionJsonString())
            assertEquals(it.name, description.getString("name"))
            assertEquals("object", description.getJSONObject("parameters").getString("type"))
        }
        val tool = CalculatorTool()
        assertEquals("402.5", tool.execute("""{"expression":"17.5*23"}"""))
        listOf("{}", "{", """{"expression":2}""").forEach { assertTrue(tool.execute(it).startsWith("Error: ")) }
    }
    @Test fun datetimeUsesOneDeviceClockSnapshot() {
        val clock = Clock.fixed(Instant.parse("2026-10-22T19:00:00Z"), ZoneId.of("Asia/Kolkata"))
        val result = JSONObject(CurrentDateTimeTool(clock).execute("{}"))
        assertEquals("2026-10-23", result.getString("date"))
        assertEquals("FRIDAY", result.getString("weekday"))
        assertEquals("+05:30", result.getString("utc_offset"))
        assertEquals("Asia/Kolkata", result.getString("timezone"))
        assertEquals("2026-10-23T00:30:00+05:30", result.getString("datetime"))
    }
    @Test fun recorderCopiesAndClears() {
        val recorder = ToolUseRecorder()
        recorder.recording(CalculatorTool()).execute("""{"expression":"2+2"}""")
        val copy = recorder.snapshot()
        recorder.clear()
        assertEquals("4", copy.single().result)
        assertTrue(recorder.snapshot().isEmpty())
    }
}

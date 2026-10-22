package com.shounak.localmeshai.ai.tools

import com.google.ai.edge.litertlm.ToolManager
import com.google.gson.JsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolusLiteRtToolsTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-10-22T15:21:05Z"), ZoneId.of("Asia/Kolkata"))
    private val tools = SolusLiteRtTools(clock = { fixedClock })

    @Test fun calculatorReturnsResultJson() {
        val json = JSONObject(tools.calculate("17.5*23"))
        assertEquals("17.5*23", json.getString("expression"))
        assertEquals("402.5", json.getString("result"))
        assertFalse(json.has("error"))
    }

    @Test fun calculatorReturnsErrorJson() {
        val json = JSONObject(tools.calculate("1/0"))
        assertEquals("Error: division by zero", json.getString("error"))
        assertFalse(json.has("result"))
    }

    @Test fun dateTimeUsesDeviceClockAndZone() {
        val json = JSONObject(tools.currentDateTime())
        assertEquals("2026-10-22T20:51:05+05:30", json.getString("iso_datetime"))
        assertEquals("2026-10-22", json.getString("date"))
        assertEquals("20:51:05", json.getString("time"))
        assertEquals("Thursday", json.getString("weekday"))
        assertEquals("Asia/Kolkata", json.getString("timezone"))
        assertEquals("+05:30", json.getString("utc_offset"))
    }

    @Test fun displayNamesAreHumanReadable() {
        assertEquals("calculator", SolusLiteRtTools.displayName(SolusLiteRtTools.CALCULATOR_NAME))
        assertEquals("date & time", SolusLiteRtTools.displayName(SolusLiteRtTools.DATETIME_NAME))
        assertTrue(SolusLiteRtTools.displayName("other") == "other")
    }

    @Test fun liteRtToolManagerSeesAndExecutesBothTools() {
        val used = mutableListOf<String>()
        val manager = ToolManager(SolusLiteRtTools(clock = { fixedClock }, onToolUsed = { used += it }).providers())

        val names = manager.getToolsDescription().map {
            val entry = it.asJsonObject
            assertEquals("function", entry.get("type").asString)
            entry.getAsJsonObject("function").get("name").asString
        }.toSet()
        assertEquals(setOf(SolusLiteRtTools.CALCULATOR_NAME, SolusLiteRtTools.DATETIME_NAME), names)

        val calc = manager.execute(SolusLiteRtTools.CALCULATOR_NAME, JsonObject().apply { addProperty("expression", "17.5*23") })
        assertEquals("402.5", JSONObject(calc.asString).getString("result"))

        val now = manager.execute(SolusLiteRtTools.DATETIME_NAME, JsonObject())
        assertEquals("Thursday", JSONObject(now.asString).getString("weekday"))

        assertEquals(listOf(SolusLiteRtTools.CALCULATOR_NAME, SolusLiteRtTools.DATETIME_NAME), used)
    }
}

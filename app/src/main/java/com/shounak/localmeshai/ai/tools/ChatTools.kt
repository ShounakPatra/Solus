package com.shounak.localmeshai.ai.tools

import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONObject
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Backend-independent schema and execution contract; no annotation/reflection discovery. */
interface ChatTool : OpenApiTool {
    val name: String
}

data class ToolUse(val name: String, val arguments: String, val result: String)

class CalculatorTool : ChatTool {
    override val name = "calculator"
    override fun getToolDescriptionJsonString(): String = """{"name":"calculator","description":"Evaluate an arithmetic expression accurately.","parameters":{"type":"object","properties":{"expression":{"type":"string","description":"Arithmetic expression to evaluate"}},"required":["expression"],"additionalProperties":false}}"""
    override fun execute(paramsJsonString: String): String = try {
        val params = JSONObject(paramsJsonString)
        val expression = params.opt("expression")
        if (expression !is String) "Error: expression must be a string"
        else CalculatorEvaluator.evaluate(expression)
    } catch (_: org.json.JSONException) { "Error: Invalid JSON arguments" }
}

class CurrentDateTimeTool(private val clock: Clock = Clock.systemDefaultZone()) : ChatTool {
    override val name = "get_current_datetime"
    override fun getToolDescriptionJsonString(): String = """{"name":"get_current_datetime","description":"Get the current date and time in the device's local timezone.","parameters":{"type":"object","properties":{},"additionalProperties":false}}"""
    override fun execute(paramsJsonString: String): String {
        try { JSONObject(paramsJsonString) } catch (_: org.json.JSONException) { return "Error: Invalid JSON arguments" }
        val now = ZonedDateTime.now(clock)
        return JSONObject().put("datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("date", now.toLocalDate().toString()).put("time", now.toLocalTime().toString())
            .put("weekday", now.dayOfWeek.toString()).put("timezone", now.zone.id)
            .put("utc_offset", now.offset.id).toString()
    }
}

/** SDK callbacks can execute on worker threads. Snapshot copies never expose mutable state. */
class ToolUseRecorder {
    private val uses = mutableListOf<ToolUse>()
    @Synchronized fun clear() { uses.clear() }
    @Synchronized fun snapshot(): List<ToolUse> = uses.toList()
    fun recording(delegate: ChatTool): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString() = delegate.getToolDescriptionJsonString()
        override fun execute(paramsJsonString: String): String {
            val result = delegate.execute(paramsJsonString)
            synchronized(this@ToolUseRecorder) { uses.add(ToolUse(delegate.name, paramsJsonString, result)) }
            return result
        }
    }
}

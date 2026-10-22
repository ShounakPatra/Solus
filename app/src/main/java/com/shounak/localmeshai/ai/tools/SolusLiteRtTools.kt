package com.shounak.localmeshai.ai.tools

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONObject

/**
 * Experimental built-in tools exposed to LiteRT-LM (.litertlm) conversations when the
 * "Tool calling (experimental)" setting is enabled.
 *
 * Tools are declared through LiteRT-LM's [OpenApiTool] interface rather than the
 * reflection-based `ToolSet` + `@Tool` annotations. Both are public API in
 * litertlm-android 0.18.0; the OpenAPI variant avoids kotlin-reflect walking our classes at
 * runtime, which keeps the tools stable under R8 in release builds.
 *
 * LiteRT-LM executes these automatically (ConversationConfig.automaticToolCalling defaults to
 * true) and feeds the JSON result back to the model before it writes its final answer.
 */
class SolusLiteRtTools(
    /** Read on every call so a device timezone change is picked up without recreating tools. */
    private val clock: () -> Clock = { Clock.systemDefaultZone() },
    private val onToolUsed: (toolName: String) -> Unit = {}
) {

    fun providers(): List<ToolProvider> = listOf(tool(CalculatorTool()), tool(DateTimeTool()))

    /** Evaluates [expression] and returns the JSON payload sent back to the model. */
    fun calculate(expression: String): String {
        return when (val result = CalculatorEvaluator.evaluate(expression)) {
            is CalculatorEvaluator.Result.Success -> JSONObject()
                .put("expression", expression)
                .put("result", result.formatted)
                .toString()
            is CalculatorEvaluator.Result.Error -> JSONObject()
                .put("expression", expression)
                .put("error", result.message)
                .toString()
        }
    }

    /** Returns the device's current local date/time as the JSON payload sent back to the model. */
    fun currentDateTime(): String {
        val now = ZonedDateTime.now(clock())
        return JSONObject()
            .put("iso_datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("date", now.toLocalDate().toString())
            .put("time", now.toLocalTime().withNano(0).toString())
            .put("weekday", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            .put("timezone", now.zone.id)
            .put("utc_offset", now.offset.id)
            .toString()
    }

    private inner class CalculatorTool : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = CALCULATOR_SPEC

        override fun execute(paramsJsonString: String): String {
            onToolUsed(CALCULATOR_NAME)
            val expression = runCatching { JSONObject(paramsJsonString).optString("expression", "") }
                .getOrDefault("")
            return calculate(expression)
        }
    }

    private inner class DateTimeTool : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = DATETIME_SPEC

        override fun execute(paramsJsonString: String): String {
            onToolUsed(DATETIME_NAME)
            return currentDateTime()
        }
    }

    companion object {
        const val CALCULATOR_NAME = "calculator"
        const val DATETIME_NAME = "get_current_datetime"

        private val CALCULATOR_SPEC = """
            {"name":"$CALCULATOR_NAME","description":"Evaluate an arithmetic expression exactly. Supports + - * / % ^, parentheses, decimals and unary minus. Use this for any arithmetic instead of computing it yourself.","parameters":{"type":"object","properties":{"expression":{"type":"string","description":"The arithmetic expression, e.g. (17.5*23)-4^2"}},"required":["expression"]}}
        """.trimIndent()

        private val DATETIME_SPEC = """
            {"name":"$DATETIME_NAME","description":"Get the current local date, time, weekday and timezone from the device clock. Use this whenever the user asks about today's date, the day of the week, or the current time.","parameters":{"type":"object","properties":{}}}
        """.trimIndent()

        /** Display label for a tool name, e.g. for a future "Used calculator" chip. */
        fun displayName(toolName: String): String = when (toolName) {
            CALCULATOR_NAME -> "calculator"
            DATETIME_NAME -> "date & time"
            else -> toolName
        }
    }
}

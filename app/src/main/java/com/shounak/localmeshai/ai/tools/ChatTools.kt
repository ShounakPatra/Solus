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
    override val name: String = "calculator"
    override fun getToolDescriptionJsonString(): String = """{"name":"calculator","description":"Evaluate an arithmetic expression accurately.","parameters":{"type":"object","properties":{"expression":{"type":"string","description":"Arithmetic expression to evaluate"}},"required":["expression"],"additionalProperties":false}}"""
    override fun execute(paramsJsonString: String): String = try {
        val params = JSONObject(paramsJsonString)
        val expression = params.opt("expression")
        if (expression !is String) "Error: expression must be a string"
        else CalculatorEvaluator.evaluate(expression)
    } catch (_: org.json.JSONException) { "Error: Invalid JSON arguments" }
}

class CurrentDateTimeTool(private val clock: Clock? = null) : ChatTool {
    override val name: String = "get_current_datetime"
    override fun getToolDescriptionJsonString(): String = """{"name":"get_current_datetime","description":"Get the current date and time in the device's local timezone.","parameters":{"type":"object","properties":{},"additionalProperties":false}}"""
    override fun execute(paramsJsonString: String): String {
        try { JSONObject(paramsJsonString) } catch (_: org.json.JSONException) { return "Error: Invalid JSON arguments" }
        val now = ZonedDateTime.now(clock ?: Clock.systemDefaultZone())
        return JSONObject().put("datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("date", now.toLocalDate().toString()).put("time", now.toLocalTime().toString())
            .put("weekday", now.dayOfWeek.toString()).put("timezone", now.zone.id)
            .put("utc_offset", now.offset.id).toString()
    }
}

/**
 * SDK callbacks can execute on worker threads. Snapshot copies never expose mutable state.
 *
 * With automatic tool calling the SDK chains calls on its own, so tool output could keep driving
 * further calls. [clear] starts a request; after [maxCallsPerRequest] executions in it every
 * further call is refused without running, and the model is told to answer instead.
 */
class ToolUseRecorder(private val maxCallsPerRequest: Int = MAX_CALLS_PER_REQUEST) {
    @Volatile var onProgress: (() -> Unit)? = null
    private val uses = mutableListOf<ToolUse>()
    private var attempts = 0
    @Synchronized fun add(use: ToolUse) { if (uses.size < 128) uses.add(use.copy(arguments = use.arguments.take(32768), result = use.result.take(32768))) }
    @Synchronized fun clear() { uses.clear(); attempts = 0 }
    @Synchronized fun snapshot(): List<ToolUse> = uses.toList()
    @Synchronized private fun admit(): Boolean = ++attempts <= maxCallsPerRequest
    fun recording(delegate: ChatTool): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = delegate.getToolDescriptionJsonString()
        override fun execute(paramsJsonString: String): String {
            if (!admit()) {
                add(ToolUse(delegate.name, paramsJsonString, CALL_LIMIT_RESULT))
                return CALL_LIMIT_RESULT
            }
            onProgress?.invoke()
            try {
                val result = delegate.execute(paramsJsonString)
                add(ToolUse(delegate.name, paramsJsonString, result))
                return result
            } finally { onProgress?.invoke() }
        }
    }

    companion object {
        /** Matches [ToolCallingLoop]'s bound in spirit: a few rounds, never an unbounded chain. */
        const val MAX_CALLS_PER_REQUEST = 12
        const val CALL_LIMIT_RESULT = "Error: Tool call limit reached for this answer. Do not call more tools; answer with the results you already have."
    }
}

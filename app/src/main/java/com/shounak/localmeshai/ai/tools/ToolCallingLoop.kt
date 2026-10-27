package com.shounak.localmeshai.ai.tools

import org.json.JSONArray
import org.json.JSONObject

/** Three execution rounds, followed by one final answer with tool choice disabled. */
object ToolCallingLoop {
    suspend fun run(
        messages: JSONArray,
        tools: List<ChatTool>,
        record: (ToolUse) -> Unit,
        checkActive: () -> Unit,
        onToolStart: () -> Unit = {},
        generate: suspend (request: String) -> JSONObject
    ): JSONObject {
        val descriptions = JSONArray().apply {
            tools.forEach { put(JSONObject().put("type", "function")
                .put("function", JSONObject(it.getToolDescriptionJsonString()))) }
        }
        val registry = tools.associateBy { it.name }
        for (round in 0..3) {
            checkActive()
            val response = generate(JSONObject().put("messages", messages).put("tools", descriptions)
                .put("allow_calls", round < 3).toString())
            checkActive()
            val calls = response.optJSONArray("tool_calls") ?: JSONArray()
            if (calls.length() == 0) return response
            check(round < 3) { "Tool calling exceeded three rounds" }
            check(calls.length() <= 8) { "Too many tool calls in one round" }
            messages.put(response.put("role", "assistant"))
            for (index in 0 until calls.length()) {
                checkActive()
                val call = calls.getJSONObject(index)
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                val arguments = function.get("arguments").let { if (it is String) it else it.toString() }
                onToolStart()
                val result = registry[name]?.execute(arguments) ?: "Error: Unknown tool $name"
                record(ToolUse(name, arguments, result))
                messages.put(JSONObject().put("role", "tool").put("name", name)
                    .put("tool_call_id", call.optString("id", "")).put("content", result))
            }
        }
        error("Tool loop did not finish")
    }
}

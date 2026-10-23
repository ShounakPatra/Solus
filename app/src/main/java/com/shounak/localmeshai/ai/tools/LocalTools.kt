package com.shounak.localmeshai.ai.tools

import android.content.Context
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.utils.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot tool availability at conversation creation, shared by text and vision. */
internal object LocalTools {
    fun conversationConfig(base: com.google.ai.edge.litertlm.ConversationConfig?,
        tools: List<com.google.ai.edge.litertlm.ToolProvider>) = com.google.ai.edge.litertlm.ConversationConfig(
        initialMessages = base?.initialMessages.orEmpty(), extraContext = base?.extraContext.orEmpty(),
        tools = tools, automaticToolCalling = true
    )

    fun forConversation(context: Context, isCancelled: () -> Boolean): List<ChatTool> = buildList {
        add(CalculatorTool())
        add(CurrentDateTimeTool())
        if (AppSettings.getInstance(context).settings.value.enableRag) {
            add(DocumentSearchTool(isCancelled = isCancelled) { query ->
                runBlocking(Dispatchers.IO) {
                    val settings = AppSettings.getInstance(context).settings.value
                    val matches = RagManager.getInstance(context).retrieve(query,
                        topK = settings.ragTopK.coerceIn(1, 5), minScore = settings.ragMinSimilarity).matches
                    JSONObject().put("matches", JSONArray().apply {
                        matches.forEach { match -> put(JSONObject()
                            .put("document", match.chunk.documentName).put("chunk", match.chunk.chunkIndex)
                            .put("text", match.chunk.text.take(2000))) }
                    }).toString()
                }
            })
        }
    }
}

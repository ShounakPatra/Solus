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

    fun forConversation(context: Context, settings: ConversationToolSettings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value), isCancelled: () -> Boolean): List<ChatTool> = buildList {
        if ("calculator" in settings.skills) add(CalculatorTool())
        if ("get_current_datetime" in settings.skills) add(CurrentDateTimeTool())
        if (settings.documentSearch && "search_documents" in settings.skills) {
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
        val services = AndroidSkillServices(context, isCancelled)
        SkillCatalog.builtins.filter { it.id in settings.skills }.forEach {
            add(BoundedSkillTool(it.tool(services), isCancelled))
        }
        CustomSkillCodec.decode(settings.customSkills).filter { it.id in settings.skills }.forEach {
            add(BoundedSkillTool(it.definition().tool(services), isCancelled))
        }
    }
}

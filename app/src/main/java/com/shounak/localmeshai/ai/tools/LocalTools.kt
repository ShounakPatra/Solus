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

    fun forConversation(context: Context, settings: ConversationToolSettings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value),
        documentIds: () -> Set<String> = { emptySet() }, isCancelled: () -> Boolean): List<ChatTool> = buildList {
        if ("calculator" in settings.skills) add(CalculatorTool())
        if ("get_current_datetime" in settings.skills) add(CurrentDateTimeTool())
        if (settings.documentSearch && "search_documents" in settings.skills) {
            add(DocumentSearchTool(isCancelled = isCancelled) search@ { query ->
                val scope = documentIds().toSet()
                if (scope.isEmpty()) return@search JSONObject().put("status", "no_context_for_question")
                    .put("note", "No document was matched to this question, so nothing was searched. This does not establish whether this chat or the document library contains documents; do not claim that none exist. If the user means a document, ask which one, by name or by referring to it (for example \"in the contract\"), or to attach it if it is not in this chat.")
                    .put("matches", JSONArray()).toString()
                runBlocking(Dispatchers.IO) {
                    val settings = AppSettings.getInstance(context).settings.value
                    val retrieval = RagManager.getInstance(context).retrieveForChat(query,
                        topK = settings.ragTopK.coerceIn(1, 5), minScore = settings.ragMinSimilarity, timeoutMillis = 4_000L,
                        allowedDocumentIds = scope, allowGlobalSearch = false)
                    JSONObject().put("status", if (retrieval.unavailableReason == null) "ready" else "temporarily_unavailable")
                        .put("note", retrieval.unavailableReason ?: if (scope.isEmpty()) "No document search context is available for this question in the current chat. Do not use documents from other chats." else "Search completed within this chat's uploads")
                        .put("matches", JSONArray().apply {
                        retrieval.matches.forEach { match -> put(JSONObject()
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

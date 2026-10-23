package com.shounak.localmeshai.ai.tools

import com.shounak.localmeshai.data.SessionCodec
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.features.PortableSettings
import com.shounak.localmeshai.utils.AppSettingsData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolHistoryTest {
    @Test fun visionToolsSurviveSavedHistoryAndOldBackupsDefaultToEmpty() {
        val use = ToolUse("calculator", """{"expression":"17.5*23"}""", "402.5")
        val session = com.shounak.localmeshai.ui.viewmodels.VisionChatSession("vision", "Tools", "calculate", "402.5", 1,
            messages = listOf(com.shounak.localmeshai.ui.viewmodels.VisionChatMessage("calculate", true),
                com.shounak.localmeshai.ui.viewmodels.VisionChatMessage("402.5", false, toolUses = listOf(use))))
        val encoded = SessionCodec.encodeVisionSessions(listOf(session))
        assertEquals(listOf(use), SessionCodec.decodeVisionSessions(encoded).single().messages.last().toolUses)
        val old = JSONObject(encoded)
        old.getJSONArray("sessions").getJSONObject(0).getJSONArray("messages").getJSONObject(1).remove("toolUses")
        assertTrue(SessionCodec.decodeVisionSessions(old.toString()).single().messages.last().toolUses.isEmpty())
    }
    @Test fun recordedToolsSurviveHistoryAndBackupCodec() {
        val use = ToolUse("calculator", """{"expression":"17.5*23"}""", "402.5")
        val session = ChatSession(id = "chat", title = "Tools", updatedAt = 1,
            messages = listOf(ChatMessage("Calculate", true, id = "user"),
                ChatMessage("402.5", false, id = "answer", toolUses = listOf(use))))
        val encoded = SessionCodec.encodeChatSessions(listOf(session))
        val restored = SessionCodec.decodeChatSessions(encoded).single()
        assertEquals(listOf(use), restored.messages.last().toolUses)
        // Version 2 saves and backups written before tools need no destructive rewrite.
        val old = JSONObject(encoded)
        old.getJSONArray("sessions").getJSONObject(0).getJSONArray("messages")
            .getJSONObject(1).remove("toolUses")
        assertTrue(SessionCodec.decodeChatSessions(old.toString()).single().messages.last().toolUses.isEmpty())
    }
    @Test fun malformedHistoryIsRejected() {
        try {
            ToolUseCodec.decode(JSONArray().put(JSONObject().put("name", "calculator").put("arguments", 7).put("result", "7")))
            fail("Malformed tool history was accepted")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun toolSettingIsOptInAndPersistsUnderItsStableKey() {
        val prefs = com.shounak.localmeshai.features.FeatureTestPreferences()
        val tokenStore = com.shounak.localmeshai.utils.HuggingFaceTokenStore(
            prefs, com.shounak.localmeshai.utils.TestTokenCipher())
        val settings = com.shounak.localmeshai.utils.AppSettings(prefs, tokenStore)
        assertFalse(settings.settings.value.enableToolCalling)
        settings.updateSettings { it.copy(enableToolCalling = true) }
        assertTrue(prefs.getBoolean("enable_tool_calling", false))
        assertTrue(com.shounak.localmeshai.utils.AppSettings(prefs, tokenStore).settings.value.enableToolCalling)
    }
    @Test fun settingsExportAndOldBackupCompatibility() {
        val enabled = AppSettingsData(enableToolCalling = true)
        assertEquals(enabled, PortableSettings.decode(PortableSettings.encode(enabled), AppSettingsData()))
        assertTrue(PortableSettings.decode(JSONObject(), enabled).enableToolCalling)
        assertFalse(PortableSettings.decode(JSONObject(), AppSettingsData()).enableToolCalling)
    }
    @Test fun documentToolValidatesAndReturnsIndexResults() {
        var calls = 0
        val tool = DocumentSearchTool { query ->
            calls++
            JSONObject().put("matches", JSONArray().put(JSONObject().put("document", "notes.txt").put("text", query))).toString()
        }
        assertEquals("search_documents", JSONObject(tool.getToolDescriptionJsonString()).getString("name"))
        val result = JSONObject(tool.execute("""{"query":"release date"}"""))
        assertEquals("notes.txt", result.getJSONArray("matches").getJSONObject(0).getString("document"))
        listOf("{}", "{", """{"query":4}""", """{"query":""}""").forEach {
            assertTrue(tool.execute(it).startsWith("Error: "))
        }
        assertEquals(1, calls)
    }
}

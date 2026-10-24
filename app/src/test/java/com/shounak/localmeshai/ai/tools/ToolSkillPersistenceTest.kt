package com.shounak.localmeshai.ai.tools

import android.content.Context
import android.content.ContextWrapper
import com.shounak.localmeshai.features.FeatureTestPreferences
import com.shounak.localmeshai.features.PortableSettings
import com.shounak.localmeshai.utils.AppSettingsData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ToolSkillPersistenceTest {
    @Test fun moodLogsUpdateOneDayAndCanBeReadDeletedAndMergedIdempotently() {
        val store = ToolSkillStore(FeatureTestPreferences())
        val day = LocalDate.of(2026, 10, 24)
        fun log(mood: String) = store.mood(JSONObject().put("operation", "log").put("mood", mood).put("comment", "Local comment"), day)
        log("Happy"); log("Calm")
        val history = JSONObject(store.mood(JSONObject().put("operation", "history"), day)).getJSONArray("moods")
        assertEquals(1, history.length()); assertEquals("Calm", history.getJSONObject(0).getString("mood"))
        val target = ToolSkillStore(FeatureTestPreferences())
        target.merge(store.export()); target.merge(store.export())
        assertEquals(1, target.export().getJSONObject("moods").length())
        val draft = JSONObject(target.mood(JSONObject().put("operation", "delete"), day))
        assertEquals(1, target.export().getJSONObject("moods").length())
        target.confirmMoodDeletion(draft)
        assertEquals(0, target.export().getJSONObject("moods").length())
    }
    @Test fun moodHistoryIsBoundedAndFutureDatesAreRejected() {
        val store = ToolSkillStore(FeatureTestPreferences())
        val today = LocalDate.of(2026, 10, 24)
        repeat(40) { day -> store.mood(JSONObject().put("operation", "log").put("date", today.minusDays(day.toLong()).toString()).put("mood", "Calm").put("comment", "x".repeat(1000)), today) }
        val result = store.mood(JSONObject().put("operation", "history").put("days", 90), today)
        assertTrue(result.length < 24000)
        assertEquals(30, JSONObject(result).getJSONArray("moods").length())
        assertEquals(40, JSONObject(result).getInt("total_matching"))
        assertThrows(Exception::class.java) { store.mood(JSONObject().put("operation", "log").put("date", today.plusDays(1).toString()).put("mood", "Happy"), today) }
    }
    @Test fun customSkillsAndEnabledSelectionsRoundTripWithoutChangingOldDefaults() {
        val skill = CustomSkill("custom_lesson", "Tutor", "When asked to teach", "Use one example and ask a question.")
        val current = AppSettingsData(enabledToolSkills = setOf("calculator", skill.id), customToolSkills = CustomSkillCodec.encode(listOf(skill)))
        assertEquals(current, PortableSettings.decode(PortableSettings.encode(current), AppSettingsData()))
        assertEquals(current, PortableSettings.decode(JSONObject(), current))
        assertFalse(AppSettingsData().enabledToolSkills.contains("query_wikipedia"))
        assertEquals(skill, CustomSkillCodec.decode(current.customToolSkills).single())
        assertThrows(Exception::class.java) { CustomSkillCodec.encode(listOf(skill, skill)) }
        assertThrows(Exception::class.java) { CustomSkillCodec.encode(listOf(skill.copy(id = "calculator"))) }
        assertThrows(Exception::class.java) { CustomSkillCodec.encode(listOf(skill.copy(instructions = ""))) }
        assertThrows(Exception::class.java) { PortableSettings.decode(JSONObject().put("enabledSkills", JSONArray().put("bad skill")), current) }
    }
    @Test fun skillSelectionsAndCustomInstructionsInvalidateTheNextConversationSnapshot() {
        val settings = AppSettingsData(enableToolCalling = true)
        val applied = ConversationToolSettings.from(settings)
        assertTrue(applied.requiresRefresh(ConversationToolSettings.from(settings.copy(enabledToolSkills = settings.enabledToolSkills + "qr_code"))))
        val custom = CustomSkillCodec.encode(listOf(CustomSkill("custom_tutor", "Tutor", "Teach a topic", "Use examples.")))
        assertFalse(applied.requiresRefresh(ConversationToolSettings.from(settings.copy(customToolSkills = custom))))
        assertTrue(applied.requiresRefresh(ConversationToolSettings.from(settings.copy(customToolSkills = custom,
            enabledToolSkills = settings.enabledToolSkills + "custom_tutor"))))
    }
    @Test fun restoredRemindersArePausedAndDoNotInvokeAndroidAlarms() {
        val prefs = FeatureTestPreferences()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val record = JSONObject().put("id", "2b4cc85e-6581-44db-afcb-00ec8fe0a7ad").put("title", "Learn")
            .put("message", "Ask for a lesson").put("datetime", "2027-01-01T09:00:00+05:30").put("repeat", "daily").put("active", true)
        ToolReminders.importPaused(context, JSONArray().put(record))
        ToolReminders.importPaused(context, JSONArray().put(record))
        val restored = ToolReminders.records(context)
        assertEquals(1, restored.length()); assertFalse(restored.getJSONObject(0).getBoolean("active"))
    }
    @Test fun staleDeletionDraftCannotDeleteANewerMoodLog() {
        val store = ToolSkillStore(FeatureTestPreferences())
        val day = LocalDate.of(2026, 10, 24)
        store.mood(JSONObject().put("operation", "log").put("mood", "Calm"), day)
        val draft = JSONObject(store.mood(JSONObject().put("operation", "delete"), day))
        store.mood(JSONObject().put("operation", "log").put("mood", "Happy"), day)
        assertThrows(IllegalArgumentException::class.java) { store.confirmMoodDeletion(draft) }
        assertEquals("Happy", store.export().getJSONObject("moods").getJSONObject(day.toString()).getString("mood"))
    }

}

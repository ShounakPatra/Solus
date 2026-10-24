package com.shounak.localmeshai.ai.tools

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Private skill data. Backup includes mood logs, but never re-arms notifications automatically. */
// Explicit commit results are checked before reporting a skill operation successful.
@Suppress("UseKtx")
internal class ToolSkillStore(private val prefs: SharedPreferences) {
    @Synchronized fun mood(args: JSONObject, today: LocalDate = LocalDate.now()): String {
        val date = if (args.has("date")) LocalDate.parse(SkillCatalog.text(args, "date", 10)) else today
        val logs = JSONObject(prefs.getString("moods", "{}") ?: "{}")
        when (args.getString("operation")) {
            "log" -> {
                require(date <= today) { "Mood date cannot be in the future" }
                val entry = JSONObject().put("date", date.toString()).put("mood", SkillCatalog.text(args, "mood", 40))
                    .put("comment", if (args.has("comment")) SkillCatalog.text(args, "comment", 1000, true) else "")
                logs.put(date.toString(), entry)
                val keys = logs.keys().asSequence().sortedDescending().toList()
                keys.drop(366).forEach(logs::remove)
                check(prefs.edit().putString("moods", logs.toString()).commit()) { "Could not save mood log" }
                return JSONObject().put("saved", entry).toString()
            }
            "delete" -> {
                val entry = logs.optJSONObject(date.toString()) ?: return JSONObject().put("message", "No mood log for $date").toString()
                return JSONObject().put("solus_tool_action", "delete_mood").put("date", date.toString())
                    .put("expected", entry).put("message", "Awaiting user confirmation; nothing has been deleted").toString()
            }
            "history" -> {
                val days = args.optInt("days", 7); require(days in 1..90) { "History must be 1 to 90 days" }
                val result = logs.keys().asSequence().sortedDescending().filter {
                    val value = LocalDate.parse(it); value <= date && value > date.minusDays(days.toLong())
                }.map { logs.getJSONObject(it) }.toList()
                val bounded = result.take(30).map { JSONObject(it.toString()).put("comment", it.getString("comment").take(200)) }
                return JSONObject().put("moods", JSONArray(bounded)).put("total_matching", result.size).put("limit", 30).toString()
            }
            else -> error("Unsupported mood operation")
        }
    }
    @Synchronized fun confirmMoodDeletion(draft: JSONObject): String {
        require(draft.getString("solus_tool_action") == "delete_mood")
        val date = LocalDate.parse(draft.getString("date")).toString()
        val logs = JSONObject(prefs.getString("moods", "{}") ?: "{}")
        val current = logs.optJSONObject(date) ?: return "This mood log was already deleted."
        val expected = draft.getJSONObject("expected")
        require(listOf("date", "mood", "comment").all { current.opt(it) == expected.opt(it) }) {
            "This log changed after the request. Ask for a new deletion request."
        }
        logs.remove(date)
        check(prefs.edit().putString("moods", logs.toString()).commit()) { "Could not delete mood log" }
        return "Mood log for $date deleted."
    }
    @Synchronized fun clearMoods() { check(prefs.edit().remove("moods").commit()) }
    @Synchronized fun export(): JSONObject = JSONObject().put("moods", JSONObject(prefs.getString("moods", "{}") ?: "{}"))
    @Synchronized fun merge(data: JSONObject) {
        validate(data)
        val moods = JSONObject(prefs.getString("moods", "{}") ?: "{}")
        val imported = data.getJSONObject("moods")
        imported.keys().forEach { if (!moods.has(it)) moods.put(it, imported.getJSONObject(it)) }
        moods.keys().asSequence().sortedDescending().toList().drop(366).forEach(moods::remove)
        check(prefs.edit().putString("moods", moods.toString()).commit()) { "Could not restore mood logs" }
    }
    companion object {
        private var instance: ToolSkillStore? = null
        @Synchronized fun get(context: Context): ToolSkillStore {
            val preferences = context.applicationContext.getSharedPreferences("tool_skill_data", Context.MODE_PRIVATE)
            if (instance?.prefs !== preferences) instance = ToolSkillStore(preferences)
            return requireNotNull(instance)
        }
        fun validate(data: JSONObject) {
            val moods = data.getJSONObject("moods")
            require(moods.length() <= 366) { "Too many mood logs" }
            moods.keys().forEach { key ->
                LocalDate.parse(key)
                val entry = moods.getJSONObject(key)
                require(entry.getString("date") == key)
                SkillCatalog.text(entry, "mood", 40); SkillCatalog.text(entry, "comment", 1000, true)
            }
        }
    }
}

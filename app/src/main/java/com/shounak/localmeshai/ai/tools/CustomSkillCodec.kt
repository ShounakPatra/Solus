package com.shounak.localmeshai.ai.tools

import org.json.JSONArray
import org.json.JSONObject

internal data class CustomSkill(val id: String, val title: String, val description: String, val instructions: String) {
    fun json() = JSONObject().put("id", id).put("title", title).put("description", description).put("instructions", instructions)
    fun definition() = SkillDefinition(id, title, description, access = "Custom instructions; no script execution") { _, _ ->
        JSONObject().put("skill", title).put("instructions", instructions).toString()
    }
}
internal object CustomSkillCodec {
    fun decode(text: String): List<CustomSkill> {
        require(text.length <= 80_000) { "Custom skills are too large" }
        val array = JSONArray(text)
        require(array.length() <= 16) { "At most 16 custom skills are supported" }
        val skills = (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val id = SkillCatalog.text(json, "id", 48)
            require(id.matches(Regex("custom_[a-z0-9_]{1,40}"))) { "Invalid custom skill ID" }
            CustomSkill(id, SkillCatalog.text(json, "title", 80), SkillCatalog.text(json, "description", 240), SkillCatalog.text(json, "instructions", 4000))
        }
        require(skills.map { it.id }.distinct().size == skills.size) { "Duplicate custom skill IDs" }
        return skills
    }
    fun encode(skills: List<CustomSkill>): String = JSONArray().apply { skills.forEach { put(it.json()) } }.toString().also { decode(it) }
}

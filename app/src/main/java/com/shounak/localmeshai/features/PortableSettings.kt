package com.shounak.localmeshai.features

import com.shounak.localmeshai.utils.AppSettingsData
import org.json.JSONObject
import org.json.JSONArray

/** Deliberate schema: credentials and storage permissions are never portable. */
internal object PortableSettings {
    fun encode(value: AppSettingsData) = JSONObject().apply {
        put("dark", value.enableDarkMode); put("dynamic", value.enableDynamicThemes)
        put("telemetry", value.enableTelemetryBar); put("thermalGuard", value.showThermalGuard)
        put("ramGuard", value.showRamGuard); put("suggestions", value.enableSuggestionPills)
        put("personas", value.enablePersonaPresets); put("bench", value.enableSolusBench)
        put("hideBottomBar", value.enableAutoHideBottomBar); put("autoUnloadMinutes", value.autoUnloadMinutes)
        put("autoUpdates", value.autoCheckUpdates); put("backend", value.llamaBackendPreference)
        put("memory", value.enablePersistentMemory); put("autoMemories", value.autoExtractMemories)
        put("toolCalling", value.enableToolCalling)
        put("enabledSkills", JSONArray(value.enabledToolSkills.sorted()))
        put("customSkills", JSONArray(value.customToolSkills))
        put("rag", value.enableRag); put("ragTopK", value.ragTopK); put("ragMinSimilarity", value.ragMinSimilarity)
    }
    fun decode(input: JSONObject, current: AppSettingsData): AppSettingsData {
        val supported = encode(AppSettingsData()).keys().asSequence().toSet()
        val present = input.keys().asSequence().toSet().intersect(supported)
        val numeric = setOf("autoUnloadMinutes", "ragTopK", "ragMinSimilarity")
        (present - numeric - setOf("backend", "enabledSkills", "customSkills")).forEach { require(input.get(it) is Boolean) { "Invalid setting: $it" } }
        (present intersect numeric).forEach { require(input.get(it) is Number) { "Invalid setting: $it" } }
        if ("backend" in present) require(input.get("backend") is String) { "Invalid setting: backend" }
        if ("customSkills" in present) com.shounak.localmeshai.ai.tools.CustomSkillCodec.decode(input.getJSONArray("customSkills").toString())
        if ("enabledSkills" in present) {
            val skills = input.getJSONArray("enabledSkills")
            require(skills.length() <= 64)
            for (i in 0 until skills.length()) require(skills.get(i) is String && skills.getString(i).matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Invalid skill ID" }
        }
        // New settings retain current values; unknown keys are not imported (including secrets).
        val json = encode(current).apply { present.forEach { put(it, input.get(it)) } }
        val backend = json.getString("backend")
        if ("backend" in present) require(backend in setOf("AUTO", "CPU", "VULKAN"))
        val unload = json.getInt("autoUnloadMinutes")
        val topK = json.getInt("ragTopK")
        val similarity = json.getDouble("ragMinSimilarity").toFloat()
        if ("autoUnloadMinutes" in present) require(unload in 0..1440 && json.getDouble("autoUnloadMinutes") == unload.toDouble())
        if ("ragTopK" in present) require(topK in 1..20 && json.getDouble("ragTopK") == topK.toDouble())
        if ("ragMinSimilarity" in present) require(similarity.isFinite() && similarity in 0.25f..1f)
        return current.copy(
            enableDarkMode = json.getBoolean("dark"), enableDynamicThemes = json.getBoolean("dynamic"),
            enableTelemetryBar = json.getBoolean("telemetry"), showThermalGuard = json.getBoolean("thermalGuard"),
            showRamGuard = json.getBoolean("ramGuard"), enableSuggestionPills = json.getBoolean("suggestions"),
            enablePersonaPresets = json.getBoolean("personas"), enableSolusBench = json.getBoolean("bench"),
            enableAutoHideBottomBar = json.getBoolean("hideBottomBar"), autoUnloadMinutes = unload,
            autoCheckUpdates = json.getBoolean("autoUpdates"), llamaBackendPreference = backend,
            enablePersistentMemory = json.getBoolean("memory"), autoExtractMemories = json.getBoolean("autoMemories"),
            enableToolCalling = json.getBoolean("toolCalling"),
            enabledToolSkills = json.getJSONArray("enabledSkills").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() },
            customToolSkills = json.getJSONArray("customSkills").toString(),
            enableRag = json.getBoolean("rag"), ragTopK = topK, ragMinSimilarity = similarity
        )
    }
}

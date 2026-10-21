package com.shounak.localmeshai.features

import com.shounak.localmeshai.utils.AppSettingsData
import org.json.JSONObject

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
        put("rag", value.enableRag); put("ragTopK", value.ragTopK); put("ragMinSimilarity", value.ragMinSimilarity)
    }
    fun decode(input: JSONObject, current: AppSettingsData): AppSettingsData {
        val supported = encode(AppSettingsData()).keys().asSequence().toSet()
        val present = input.keys().asSequence().toSet().intersect(supported)
        val numeric = setOf("autoUnloadMinutes", "ragTopK", "ragMinSimilarity")
        (present - numeric - "backend").forEach { require(input.get(it) is Boolean) { "Invalid setting: $it" } }
        (present intersect numeric).forEach { require(input.get(it) is Number) { "Invalid setting: $it" } }
        if ("backend" in present) require(input.get("backend") is String) { "Invalid setting: backend" }
        // New settings retain current values; unknown keys are not imported (including secrets).
        val json = encode(current).apply { present.forEach { put(it, input.get(it)) } }
        val backend = json.getString("backend")
        if ("backend" in present) require(backend in setOf("AUTO", "CPU", "VULKAN"))
        val unload = json.getInt("autoUnloadMinutes")
        val topK = json.getInt("ragTopK")
        val similarity = json.getDouble("ragMinSimilarity").toFloat()
        if ("autoUnloadMinutes" in present) require(unload in 0..1440 && json.getDouble("autoUnloadMinutes") == unload.toDouble())
        if ("ragTopK" in present) require(topK in 1..20 && json.getDouble("ragTopK") == topK.toDouble())
        if ("ragMinSimilarity" in present) require(similarity.isFinite() && similarity in 0.3f..1f)
        return current.copy(
            enableDarkMode = json.getBoolean("dark"), enableDynamicThemes = json.getBoolean("dynamic"),
            enableTelemetryBar = json.getBoolean("telemetry"), showThermalGuard = json.getBoolean("thermalGuard"),
            showRamGuard = json.getBoolean("ramGuard"), enableSuggestionPills = json.getBoolean("suggestions"),
            enablePersonaPresets = json.getBoolean("personas"), enableSolusBench = json.getBoolean("bench"),
            enableAutoHideBottomBar = json.getBoolean("hideBottomBar"), autoUnloadMinutes = unload,
            autoCheckUpdates = json.getBoolean("autoUpdates"), llamaBackendPreference = backend,
            enablePersistentMemory = json.getBoolean("memory"), autoExtractMemories = json.getBoolean("autoMemories"),
            enableRag = json.getBoolean("rag"), ragTopK = topK, ragMinSimilarity = similarity
        )
    }
}

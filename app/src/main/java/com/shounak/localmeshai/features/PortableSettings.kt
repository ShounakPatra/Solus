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
    fun decode(json: JSONObject, current: AppSettingsData): AppSettingsData {
        val supported = encode(AppSettingsData()).keys().asSequence().toSet()
        require(json.keys().asSequence().toSet() == supported) { "Unsupported or incomplete backup settings" }
        val numeric = setOf("autoUnloadMinutes", "ragTopK", "ragMinSimilarity")
        (supported - numeric - "backend").forEach { require(json.get(it) is Boolean) { "Invalid setting: $it" } }
        numeric.forEach { require(json.get(it) is Number) }
        val backend = json.getString("backend")
        require(backend in setOf("AUTO", "CPU", "VULKAN"))
        val unload = json.getInt("autoUnloadMinutes")
        val topK = json.getInt("ragTopK")
        val similarity = json.getDouble("ragMinSimilarity").toFloat()
        require(unload in 0..1440 && json.getDouble("autoUnloadMinutes") == unload.toDouble())
        require(topK in 1..20 && json.getDouble("ragTopK") == topK.toDouble())
        require(similarity.isFinite() && similarity in 0.3f..1f)
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

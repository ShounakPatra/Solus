package com.shounak.localmeshai.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettingsData(
    val enableDarkMode: Boolean = true,
    val enableDynamicThemes: Boolean = true,
    val enableTelemetryBar: Boolean = true,
    val showThermalGuard: Boolean = true,
    val showRamGuard: Boolean = true,
    val enableSuggestionPills: Boolean = false,
    val enablePersonaPresets: Boolean = true,
    val enableSolusBench: Boolean = true,
    val enableAutoHideBottomBar: Boolean = false,
    val autoUnloadMinutes: Int = 5,
    val huggingFaceToken: String = "",
    val autoCheckUpdates: Boolean = true,
    val llamaBackendPreference: String = "AUTO",
    val enableToolCalling: Boolean = false,
    val enabledToolSkills: Set<String> = setOf("calculator", "get_current_datetime", "search_documents"),
    val customToolSkills: String = "[]",
    val enableRag: Boolean = false,
    val ragTopK: Int = 3,
    val ragMinSimilarity: Float = 0.35f,
    val enablePersistentMemory: Boolean = true,
    val autoExtractMemories: Boolean = true
)

class AppSettings internal constructor(
    private val prefs: SharedPreferences,
    private val tokenStore: HuggingFaceTokenStore = HuggingFaceTokenStore(prefs)
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE))

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettingsData> = _settings.asStateFlow()

    private fun loadSettings(): AppSettingsData {
        return AppSettingsData(
            enableDarkMode = prefs.getBoolean(KEY_DARK_MODE, true),
            enableDynamicThemes = prefs.getBoolean(KEY_DYNAMIC_THEMES, true),
            enableTelemetryBar = prefs.getBoolean(KEY_TELEMETRY_BAR, true),
            showThermalGuard = prefs.getBoolean(KEY_THERMAL_GUARD, true),
            showRamGuard = prefs.getBoolean(KEY_RAM_GUARD, true),
            enableSuggestionPills = prefs.getBoolean(KEY_SUGGESTION_PILLS, false),
            enablePersonaPresets = prefs.getBoolean(KEY_PERSONA_PRESETS, true),
            enableSolusBench = prefs.getBoolean(KEY_SOLUS_BENCH, true),
            enableAutoHideBottomBar = prefs.getBoolean(KEY_AUTO_HIDE_BOTTOM_BAR, false),
            autoUnloadMinutes = prefs.getInt(KEY_AUTO_UNLOAD_MINS, 5),
            huggingFaceToken = tokenStore.read(),
            autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
            llamaBackendPreference = prefs.getString(KEY_LLAMA_BACKEND_PREF, "AUTO")?.let {
                if (it.uppercase() in listOf("AUTO", "CPU", "VULKAN")) it.uppercase() else "AUTO"
            } ?: "AUTO",
            enableToolCalling = prefs.getBoolean(KEY_ENABLE_TOOL_CALLING, false),
            enabledToolSkills = prefs.getStringSet(KEY_TOOL_SKILLS, setOf("calculator", "get_current_datetime", "search_documents"))?.toSet().orEmpty(),
            customToolSkills = prefs.getString(KEY_CUSTOM_SKILLS, "[]")?.takeIf {
                runCatching { com.shounak.localmeshai.ai.tools.CustomSkillCodec.decode(it) }.isSuccess
            } ?: "[]",
            enableRag = prefs.getBoolean(KEY_ENABLE_RAG, false),
            ragTopK = prefs.getInt(KEY_RAG_TOP_K, 3),
            ragMinSimilarity = prefs.getFloat(KEY_RAG_MIN_SIMILARITY, 0.35f).let {
                if (it < 0.30f) 0.35f else it
            },
            enablePersistentMemory = prefs.getBoolean(KEY_ENABLE_PERSISTENT_MEMORY, true),
            autoExtractMemories = prefs.getBoolean(KEY_AUTO_EXTRACT_MEMORIES, true)
        )
    }

    @Synchronized fun updateSettings(transform: (AppSettingsData) -> AppSettingsData) {
        val updated = transform(_settings.value).let { it.copy(huggingFaceToken = it.huggingFaceToken.trim(), enabledToolSkills = it.enabledToolSkills.toSet()) }
        require(updated.enabledToolSkills.size <= 64 && updated.enabledToolSkills.all { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) }) { "Invalid skill IDs" }
        com.shounak.localmeshai.ai.tools.CustomSkillCodec.decode(updated.customToolSkills)
        if (updated.huggingFaceToken != _settings.value.huggingFaceToken) tokenStore.write(updated.huggingFaceToken)
        prefs.edit()
            .putBoolean(KEY_DARK_MODE, updated.enableDarkMode)
            .putBoolean(KEY_DYNAMIC_THEMES, updated.enableDynamicThemes)
            .putBoolean(KEY_TELEMETRY_BAR, updated.enableTelemetryBar)
            .putBoolean(KEY_THERMAL_GUARD, updated.showThermalGuard)
            .putBoolean(KEY_RAM_GUARD, updated.showRamGuard)
            .putBoolean(KEY_SUGGESTION_PILLS, updated.enableSuggestionPills)
            .putBoolean(KEY_PERSONA_PRESETS, updated.enablePersonaPresets)
            .putBoolean(KEY_SOLUS_BENCH, updated.enableSolusBench)
            .putBoolean(KEY_AUTO_HIDE_BOTTOM_BAR, updated.enableAutoHideBottomBar)
            .putInt(KEY_AUTO_UNLOAD_MINS, updated.autoUnloadMinutes)
            .putBoolean(KEY_AUTO_CHECK_UPDATES, updated.autoCheckUpdates)
            .putString(KEY_LLAMA_BACKEND_PREF, updated.llamaBackendPreference)
            .putBoolean(KEY_ENABLE_TOOL_CALLING, updated.enableToolCalling)
            .putStringSet(KEY_TOOL_SKILLS, updated.enabledToolSkills.toSet())
            .putString(KEY_CUSTOM_SKILLS, updated.customToolSkills)
            .putBoolean(KEY_ENABLE_RAG, updated.enableRag)
            .putInt(KEY_RAG_TOP_K, updated.ragTopK)
            .putFloat(KEY_RAG_MIN_SIMILARITY, updated.ragMinSimilarity)
            .putBoolean(KEY_ENABLE_PERSISTENT_MEMORY, updated.enablePersistentMemory)
            .putBoolean(KEY_AUTO_EXTRACT_MEMORIES, updated.autoExtractMemories)
            .apply()
        _settings.value = updated
    }

    companion object {
        private const val KEY_DARK_MODE = "enable_dark_mode"
        private const val KEY_DYNAMIC_THEMES = "enable_dynamic_themes"
        private const val KEY_TELEMETRY_BAR = "enable_telemetry_bar"
        private const val KEY_THERMAL_GUARD = "show_thermal_guard"
        private const val KEY_RAM_GUARD = "show_ram_guard"
        private const val KEY_SUGGESTION_PILLS = "enable_suggestion_pills"
        private const val KEY_PERSONA_PRESETS = "enable_persona_presets"
        private const val KEY_SOLUS_BENCH = "enable_solus_bench"
        private const val KEY_AUTO_HIDE_BOTTOM_BAR = "auto_hide_bottom_bar"
        internal const val KEY_AUTO_UNLOAD_MINS = "auto_unload_minutes"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LLAMA_BACKEND_PREF = "llama_backend_pref"
        private const val KEY_ENABLE_TOOL_CALLING = "enable_tool_calling"
        private const val KEY_TOOL_SKILLS = "enabled_tool_skills"
        private const val KEY_CUSTOM_SKILLS = "custom_tool_skills"
        private const val KEY_ENABLE_RAG = "enable_rag"
        private const val KEY_RAG_TOP_K = "rag_top_k"
        private const val KEY_RAG_MIN_SIMILARITY = "rag_min_similarity"
        private const val KEY_ENABLE_PERSISTENT_MEMORY = "enable_persistent_memory"
        private const val KEY_AUTO_EXTRACT_MEMORIES = "auto_extract_memories"

        @Volatile
        private var INSTANCE: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(context).also { INSTANCE = it }
            }
        }
    }
}

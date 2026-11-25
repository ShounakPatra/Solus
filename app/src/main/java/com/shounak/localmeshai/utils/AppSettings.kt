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
    val warmupAfterLoading: Boolean = true,
    val enabledToolSkills: Set<String> = setOf("calculator", "get_current_datetime", "search_documents"),
    val customToolSkills: String = "[]",
    val enableRag: Boolean = false,
    val ragTopK: Int = 3,
    val ragMinSimilarity: Float = 0.35f,
    /** RAG embedder: "universal-fnv1a-384" (built-in lexical) or an EmbeddingModels id. */
    val ragEmbeddingModel: String = "universal-fnv1a-384",
    /** User chose "Later" on the Model Manager's document-search model card. */
    val embeddingModelPromptSkipped: Boolean = false,
    val enablePersistentMemory: Boolean = true,
    val autoExtractMemories: Boolean = true,
    val selectedPersonaId: String = "default",
    // Web Search
    val enableWebSearch: Boolean = false,
    val webSearchProvider: String = "DuckDuckGo",
    val braveApiKey: String = "",
    val tavilyApiKey: String = "",
    // Chat Defaults
    val defaultTemperature: Float = 0.7f,
    val defaultTopP: Float = 0.90f,
    val defaultTopK: Int = 40,
    val defaultMaxLength: Int = 0, // 0 = Auto
    val compactAutomaticallyAt: Int = 0, // 0 = Off
    val readEveryAnswerAloud: Boolean = false,
    val ttsVoice: String = "F2", // "F2" (Female) or "M2" (Male)
    val ttsSpeed: Float = 1.0f, // 1.0x, 1.25x, 1.5x
    val enableVoiceLiveHighlight: Boolean = false,
    // Automatic Chat Backup
    val enableAutoChatBackup: Boolean = true
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
            warmupAfterLoading = prefs.getBoolean("warmup_after_loading", true),
            enabledToolSkills = prefs.getStringSet(KEY_TOOL_SKILLS, setOf("calculator", "get_current_datetime", "search_documents"))?.toSet().orEmpty(),
            customToolSkills = prefs.getString(KEY_CUSTOM_SKILLS, "[]")?.takeIf {
                runCatching { com.shounak.localmeshai.ai.tools.CustomSkillCodec.decode(it) }.isSuccess
            } ?: "[]",
            enableRag = prefs.getBoolean(KEY_ENABLE_RAG, false),
            ragTopK = prefs.getInt(KEY_RAG_TOP_K, 3),
            ragMinSimilarity = prefs.getFloat(KEY_RAG_MIN_SIMILARITY, 0.35f).let {
                if (it.isFinite() && it in 0.25f..1f) it else 0.35f
            },
            ragEmbeddingModel = prefs.getString(KEY_RAG_EMBEDDING_MODEL, null) ?: "universal-fnv1a-384",
            embeddingModelPromptSkipped = prefs.getBoolean(KEY_EMBEDDING_PROMPT_SKIPPED, false),
            enablePersistentMemory = prefs.getBoolean(KEY_ENABLE_PERSISTENT_MEMORY, true),
            autoExtractMemories = prefs.getBoolean(KEY_AUTO_EXTRACT_MEMORIES, true),
            selectedPersonaId = prefs.getString(KEY_SELECTED_PERSONA, "default") ?: "default",
            enableWebSearch = prefs.getBoolean(KEY_ENABLE_WEB_SEARCH, false),
            webSearchProvider = prefs.getString(KEY_WEB_SEARCH_PROVIDER, "DuckDuckGo") ?: "DuckDuckGo",
            braveApiKey = prefs.getString(KEY_BRAVE_API_KEY, "") ?: "",
            tavilyApiKey = prefs.getString(KEY_TAVILY_API_KEY, "") ?: "",
            defaultTemperature = prefs.getFloat(KEY_DEFAULT_TEMPERATURE, 0.7f),
            defaultTopP = prefs.getFloat(KEY_DEFAULT_TOP_P, 0.90f),
            defaultTopK = prefs.getInt(KEY_DEFAULT_TOP_K, 40),
            defaultMaxLength = prefs.getInt(KEY_DEFAULT_MAX_LENGTH, 0),
            compactAutomaticallyAt = prefs.getInt(KEY_COMPACT_AUTOMATICALLY_AT, 0),
            readEveryAnswerAloud = prefs.getBoolean(KEY_READ_EVERY_ANSWER_ALOUD, false),
            ttsVoice = prefs.getString(KEY_TTS_VOICE, "F2") ?: "F2",
            ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f),
            enableVoiceLiveHighlight = prefs.getBoolean(KEY_ENABLE_VOICE_LIVE_HIGHLIGHT, false),
            enableAutoChatBackup = prefs.getBoolean(KEY_ENABLE_AUTO_CHAT_BACKUP, true)
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
            .putBoolean("warmup_after_loading", updated.warmupAfterLoading)
            .putStringSet(KEY_TOOL_SKILLS, updated.enabledToolSkills.toSet())
            .putString(KEY_CUSTOM_SKILLS, updated.customToolSkills)
            .putBoolean(KEY_ENABLE_RAG, updated.enableRag)
            .putInt(KEY_RAG_TOP_K, updated.ragTopK)
            .putFloat(KEY_RAG_MIN_SIMILARITY, updated.ragMinSimilarity)
            .putString(KEY_RAG_EMBEDDING_MODEL, updated.ragEmbeddingModel)
            .putBoolean(KEY_EMBEDDING_PROMPT_SKIPPED, updated.embeddingModelPromptSkipped)
            .putBoolean(KEY_ENABLE_PERSISTENT_MEMORY, updated.enablePersistentMemory)
            .putBoolean(KEY_AUTO_EXTRACT_MEMORIES, updated.autoExtractMemories)
            .putString(KEY_SELECTED_PERSONA, updated.selectedPersonaId)
            .putBoolean(KEY_ENABLE_WEB_SEARCH, updated.enableWebSearch)
            .putString(KEY_WEB_SEARCH_PROVIDER, updated.webSearchProvider)
            .putString(KEY_BRAVE_API_KEY, updated.braveApiKey)
            .putString(KEY_TAVILY_API_KEY, updated.tavilyApiKey)
            .putFloat(KEY_DEFAULT_TEMPERATURE, updated.defaultTemperature)
            .putFloat(KEY_DEFAULT_TOP_P, updated.defaultTopP)
            .putInt(KEY_DEFAULT_TOP_K, updated.defaultTopK)
            .putInt(KEY_DEFAULT_MAX_LENGTH, updated.defaultMaxLength)
            .putInt(KEY_COMPACT_AUTOMATICALLY_AT, updated.compactAutomaticallyAt)
            .putBoolean(KEY_READ_EVERY_ANSWER_ALOUD, updated.readEveryAnswerAloud)
            .putString(KEY_TTS_VOICE, updated.ttsVoice)
            .putFloat(KEY_TTS_SPEED, updated.ttsSpeed)
            .putBoolean(KEY_ENABLE_VOICE_LIVE_HIGHLIGHT, updated.enableVoiceLiveHighlight)
            .putBoolean(KEY_ENABLE_AUTO_CHAT_BACKUP, updated.enableAutoChatBackup)
            .apply()
        _settings.value = updated
    }

    fun setEnableVoiceLiveHighlight(enabled: Boolean) {
        updateSettings { it.copy(enableVoiceLiveHighlight = enabled) }
    }

    /** Called off the UI thread before retiring the previous persisted RAG index. */
    @Suppress("UseKtx") // A checked, synchronous commit is required at this migration boundary.
    @Synchronized internal fun commitEmbeddingModel(modelId: String) {
        check(prefs.edit().putString(KEY_RAG_EMBEDDING_MODEL, modelId).commit()) {
            "Could not save document search model selection"
        }
        _settings.value = _settings.value.copy(ragEmbeddingModel = modelId)
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
        private const val KEY_RAG_EMBEDDING_MODEL = "rag_embedding_model"
        private const val KEY_EMBEDDING_PROMPT_SKIPPED = "embedding_model_prompt_skipped"
        private const val KEY_ENABLE_PERSISTENT_MEMORY = "enable_persistent_memory"
        private const val KEY_AUTO_EXTRACT_MEMORIES = "auto_extract_memories"
        private const val KEY_SELECTED_PERSONA = "selected_persona"
        private const val KEY_ENABLE_WEB_SEARCH = "enable_web_search"
        private const val KEY_WEB_SEARCH_PROVIDER = "web_search_provider"
        private const val KEY_BRAVE_API_KEY = "brave_api_key"
        private const val KEY_TAVILY_API_KEY = "tavily_api_key"
        private const val KEY_DEFAULT_TEMPERATURE = "default_temperature"
        private const val KEY_DEFAULT_TOP_P = "default_top_p"
        private const val KEY_DEFAULT_TOP_K = "default_top_k"
        private const val KEY_DEFAULT_MAX_LENGTH = "default_max_length"
        private const val KEY_COMPACT_AUTOMATICALLY_AT = "compact_automatically_at"
        private const val KEY_READ_EVERY_ANSWER_ALOUD = "read_every_answer_aloud"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_ENABLE_VOICE_LIVE_HIGHLIGHT = "enable_voice_live_highlight"
        private const val KEY_ENABLE_AUTO_CHAT_BACKUP = "enable_auto_chat_backup"

        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.90f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_MAX_LENGTH = 0
        const val DEFAULT_AUTO_UNLOAD_MINUTES = 5

        @Volatile
        private var INSTANCE: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(context).also { INSTANCE = it }
            }
        }
    }
}

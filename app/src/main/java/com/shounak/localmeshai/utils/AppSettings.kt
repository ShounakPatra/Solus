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
    val autoUnloadMinutes: Int = 15,
    val huggingFaceToken: String = ""
)

class AppSettings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

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
            autoUnloadMinutes = prefs.getInt(KEY_AUTO_UNLOAD_MINS, 15),
            huggingFaceToken = prefs.getString(KEY_HF_TOKEN, "").orEmpty()
        )
    }

    fun updateSettings(transform: (AppSettingsData) -> AppSettingsData) {
        val updated = transform(_settings.value)
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
            .putString(KEY_HF_TOKEN, updated.huggingFaceToken)
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
        private const val KEY_AUTO_UNLOAD_MINS = "auto_unload_minutes"
        private const val KEY_HF_TOKEN = "hf_read_token"

        @Volatile
        private var INSTANCE: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(context).also { INSTANCE = it }
            }
        }
    }
}

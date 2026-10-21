package com.shounak.localmeshai.features

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

data class ModelProfile(
    val systemPrompt: String = "",
    val contextTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null
) {
    fun validate() {
        require(systemPrompt.length <= 16_000) { "System prompt is too long" }
        require(contextTokens == null || contextTokens in 512..262_144) { "Context must be 512–262144 tokens" }
        require(temperature == null || temperature.isFinite() && temperature in 0f..2f) { "Temperature must be 0–2" }
        require(topP == null || topP.isFinite() && topP in 0.01f..1f) { "Top P must be 0.01–1" }
        require(topK == null || topK in 1..200) { "Top K must be 1–200" }
    }
    fun toJson() = JSONObject().apply {
        put("systemPrompt", systemPrompt)
        contextTokens?.let { put("contextTokens", it) }
        temperature?.let { put("temperature", it) }
        topP?.let { put("topP", it) }
        topK?.let { put("topK", it) }
    }
    companion object {
        fun fromJson(json: JSONObject): ModelProfile {
            val supported = setOf("systemPrompt", "contextTokens", "temperature", "topP", "topK")
            require(json.keys().asSequence().all { it in supported }) { "Unsupported model profile field" }
            require(!json.has("systemPrompt") || json.get("systemPrompt") is String)
            fun integer(key: String): Int? {
                if (!json.has(key)) return null
                val value = json.get(key)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "Invalid $key" }
                return value.toInt()
            }
            fun decimal(key: String): Float? {
                if (!json.has(key)) return null
                require(json.get(key) is Number) { "Invalid $key" }
                return json.getDouble(key).toFloat()
            }
            return ModelProfile(json.optString("systemPrompt", ""), integer("contextTokens"), decimal("temperature"), decimal("topP"), integer("topK"))
                .also { it.validate() }
        }
    }
}

/** Nonsensitive presentation preferences; model files and credentials never belong here. */
class WorkspacePreferences internal constructor(private val prefs: SharedPreferences) {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _profileChanges = kotlinx.coroutines.flow.MutableSharedFlow<Set<String>>(extraBufferCapacity = 1)
    val profileChanges = _profileChanges.asSharedFlow()
    val reduceMotion get() = prefs.getBoolean("reduce_motion", false)
    val largeControls get() = prefs.getBoolean("large_controls", false)
    fun title(kind: String, id: String, fallback: String): String = prefs.getString("title:$kind:$id", null) ?: fallback
    fun pinned(kind: String, id: String) = prefs.getBoolean("pin:$kind:$id", false)
    fun rename(kind: String, id: String, title: String) {
        require(title.trim().isNotEmpty() && title.length <= 120) { "Use a title of 1–120 characters" }
        save(prefs.edit().putString("title:$kind:$id", title.trim()))
    }
    fun togglePin(kind: String, id: String) = save(prefs.edit().putBoolean("pin:$kind:$id", !pinned(kind, id)))
    fun setReduceMotion(value: Boolean) = save(prefs.edit().putBoolean("reduce_motion", value))
    fun setLargeControls(value: Boolean) = save(prefs.edit().putBoolean("large_controls", value))
    fun profile(modelId: String): ModelProfile = runCatching {
        ModelProfile.fromJson(JSONObject(prefs.getString("profile:$modelId", "{}")!!))
    }.getOrDefault(ModelProfile())
    fun setProfile(modelId: String, profile: ModelProfile) {
        require(modelId.isNotBlank())
        profile.validate()
        save(prefs.edit().putString("profile:$modelId", profile.toJson().toString()))
        _profileChanges.tryEmit(setOf(modelId))
    }
    /** Explicit allowlist: never add access tokens or model URIs to portable preferences. */
    fun export(): JSONObject = JSONObject().apply {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("profile:") || key.startsWith("title:") || key.startsWith("pin:") ||
                key in setOf("reduce_motion", "large_controls")) put(key, value)
        }
    }
    fun import(json: JSONObject, remapSession: (String, String) -> String) {
        WorkspaceBackup.validatePreferences(json)
        val editor = prefs.edit()
        val changedProfiles = mutableSetOf<String>()
        json.keys().forEach { key ->
            when {
                key.startsWith("profile:") -> {
                    val profile = ModelProfile.fromJson(JSONObject(json.getString(key)))
                    if (!prefs.contains(key)) {
                        editor.putString(key, profile.toJson().toString())
                        changedProfiles.add(key.removePrefix("profile:"))
                    }
                }
                key.startsWith("title:") || key.startsWith("pin:") -> {
                    val parts = key.split(':', limit = 3)
                    require(parts.size == 3 && parts[1] in setOf("text", "vision"))
                    val mapped = "${parts[0]}:${parts[1]}:${remapSession(parts[1], parts[2])}"
                    if (prefs.contains(mapped)) return@forEach
                    if (parts[0] == "title") {
                        val value = json.getString(key)
                        require(value.isNotBlank() && value.length <= 120)
                        editor.putString(mapped, value)
                    } else editor.putBoolean(mapped, json.getBoolean(key))
                }
                key in setOf("reduce_motion", "large_controls") -> editor.putBoolean(key, json.getBoolean(key))
                else -> error("Unsupported preference in backup")
            }
        }
        save(editor)
        if (changedProfiles.isNotEmpty()) _profileChanges.tryEmit(changedProfiles)
    }
    private fun save(editor: SharedPreferences.Editor) {
        editor.apply()
        _revision.value++
    }
    companion object {
        @Volatile private var instance: WorkspacePreferences? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: WorkspacePreferences(context.applicationContext.getSharedPreferences("workspace_features", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}

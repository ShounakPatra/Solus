package com.shounak.localmeshai.ai.tools

import com.shounak.localmeshai.utils.AppSettingsData

/** The settings used to construct a conversation, compared only at request boundaries. */
internal data class ConversationToolSettings(val enabled: Boolean, val documentSearch: Boolean,
    val skills: Set<String>, val customSkills: String) {
    fun requiresRefresh(current: ConversationToolSettings): Boolean = this != current

    companion object {
        fun from(settings: AppSettingsData): ConversationToolSettings {
            if (!settings.enableToolCalling) return ConversationToolSettings(false, false, emptySet(), "[]")
            val custom = CustomSkillCodec.decode(settings.customToolSkills).filter { it.id in settings.enabledToolSkills }.sortedBy { it.id }
            val available = SkillCatalog.defaults + SkillCatalog.builtins.map { it.id } + custom.map { it.id }
            val effective = settings.enabledToolSkills.intersect(available).let {
                if (settings.enableRag) it else it - "search_documents"
            }
            return ConversationToolSettings(effective.isNotEmpty(), "search_documents" in effective, effective,
                if (custom.isEmpty()) "[]" else CustomSkillCodec.encode(custom))
        }
    }
}

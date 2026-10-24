package com.shounak.localmeshai.ai.tools

import com.shounak.localmeshai.utils.AppSettingsData

/** The settings used to construct a conversation, compared only at request boundaries. */
internal data class ConversationToolSettings(val enabled: Boolean, val documentSearch: Boolean,
    val skills: Set<String>, val customSkills: String) {
    fun requiresRefresh(current: ConversationToolSettings): Boolean = this != current

    companion object {
        fun from(settings: AppSettingsData) = ConversationToolSettings(
            enabled = settings.enableToolCalling,
            documentSearch = settings.enableRag,
            skills = settings.enabledToolSkills.toSet(), customSkills = settings.customToolSkills
        )
    }
}

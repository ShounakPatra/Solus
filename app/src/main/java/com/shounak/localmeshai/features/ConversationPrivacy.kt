package com.shounak.localmeshai.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Privacy belongs to a conversation; switching runtimes must not change an empty draft's mode. */
class ConversationPrivacy {
    private val mutableTemporary = MutableStateFlow(false)
    val temporary = mutableTemporary.asStateFlow()

    fun setForNewChat(enabled: Boolean, isNewChat: Boolean, busy: Boolean): Boolean {
        if (!isNewChat || busy) return false
        mutableTemporary.value = enabled
        return true
    }

    fun openSavedChat(busy: Boolean, discardTemporary: () -> Unit): Boolean {
        if (temporary.value && busy) return false
        if (temporary.value) discardTemporary()
        mutableTemporary.value = false
        return true
    }

    companion object {
        /** Each destination enforces its own new-chat guard, preserving any inactive saved chat. */
        fun setNewDraftMode(enabled: Boolean, active: (Boolean) -> Boolean, other: (Boolean) -> Boolean): Boolean {
            if (!active(enabled)) return false
            other(enabled)
            return true
        }
    }
}

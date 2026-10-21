package com.shounak.localmeshai.features

import org.junit.Assert.*
import org.junit.Test

class ConversationPrivacyTest {
    @Test fun openingSavedHistoryDiscardsTemporaryBeforeLeavingTemporaryMode() {
        val state = ConversationPrivacy()
        state.setForNewChat(true, isNewChat = true, busy = false)
        var discarded = false
        assertTrue(state.openSavedChat(busy = false) {
            assertTrue(state.temporary.value)
            discarded = true
        })
        assertTrue(discarded)
        assertFalse(state.temporary.value)
    }

    @Test fun runningTemporaryRequestCannotSwitchToSavedHistory() {
        val state = ConversationPrivacy()
        state.setForNewChat(true, isNewChat = true, busy = false)
        assertFalse(state.openSavedChat(busy = true) { fail("Must not discard an active request") })
        assertTrue(state.temporary.value)
    }

    @Test fun ordinarySavedHistoryDoesNotDiscardTemporaryFiles() {
        val state = ConversationPrivacy()
        assertTrue(state.openSavedChat(busy = false) { fail("No temporary conversation exists") })
        assertFalse(state.temporary.value)
    }

    @Test fun enablingBeforeModelSelectionProtectsTextAndVisionDrafts() {
        val text = ConversationPrivacy()
        val vision = ConversationPrivacy()
        assertTrue(ConversationPrivacy.setNewDraftMode(true,
            active = { text.setForNewChat(it, true, false) },
            other = { vision.setForNewChat(it, true, false) }))
        assertTrue(text.temporary.value)
        assertTrue(vision.temporary.value)
        // Selecting a vision runtime now observes the same mode, including switching back.
        assertTrue(ConversationPrivacy.setNewDraftMode(false,
            active = { vision.setForNewChat(it, true, false) },
            other = { text.setForNewChat(it, true, false) }))
        assertFalse(text.temporary.value)
        assertFalse(vision.temporary.value)
    }

    @Test fun togglingNewDraftPreservesInactiveExistingConversation() {
        val text = ConversationPrivacy()
        val vision = ConversationPrivacy()
        assertTrue(ConversationPrivacy.setNewDraftMode(true,
            active = { text.setForNewChat(it, true, false) },
            other = { vision.setForNewChat(it, false, false) }))
        assertTrue(text.temporary.value)
        assertFalse(vision.temporary.value)
    }

    @Test fun rejectedActiveToggleDoesNotChangeOtherDraft() {
        val text = ConversationPrivacy()
        val vision = ConversationPrivacy()
        assertFalse(ConversationPrivacy.setNewDraftMode(true,
            active = { text.setForNewChat(it, true, true) },
            other = { vision.setForNewChat(it, true, false) }))
        assertFalse(text.temporary.value)
        assertFalse(vision.temporary.value)
        assertFalse(text.setForNewChat(true, isNewChat = false, busy = false))
    }
}

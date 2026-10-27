package com.shounak.localmeshai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtConversationReuseTest {
    private fun skip(
        alive: Boolean = true,
        used: Boolean = false,
        historyEmpty: Boolean = true,
        thinkingMatches: Boolean = true,
        toolsCurrent: Boolean = true
    ) = LiteRtConversationReuse.canSkipReset(alive, used, historyEmpty, thinkingMatches, toolsCurrent)

    @Test
    fun freshMatchingConversationWithNoHistoryIsReused() {
        // First turn of a new chat right after load: the conversation built at load already is the one a
        // reset would build, so it is kept (with any preface prefilled during load).
        assertTrue(skip())
    }

    @Test
    fun anyDifferenceFromWhatAResetWouldBuildForcesTheReset() {
        assertFalse("no live conversation", skip(alive = false))
        assertFalse("conversation already holds a turn", skip(used = true))
        assertFalse("history must be restored", skip(historyEmpty = false))
        assertFalse("Think toggled since load", skip(thinkingMatches = false))
        assertFalse("tool settings changed or a tool retry is pending", skip(toolsCurrent = false))
    }
}

package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChatDateFormatterTest {

    @Test
    fun formatChatDate_zeroOrNegative_returnsEarlier() {
        assertEquals("Earlier", ChatDateFormatter.formatChatDate(0L))
        assertEquals("Earlier", ChatDateFormatter.formatChatDate(-100L))
    }

    @Test
    fun formatChatDate_today_returnsTodayWithDateAndTime() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 14, 30, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Chat happened 2 hours earlier today
        val chatCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 12, 15, 0)
        }
        val result = ChatDateFormatter.formatChatDate(chatCal.timeInMillis, nowMillis = nowMillis)

        assertTrue("Expected to start with 'Today, Sep 26 • ' but was '$result'", result.startsWith("Today, Sep 26 • "))
        assertTrue("Expected to end with time '12:15 PM' but was '$result'", result.endsWith("12:15 PM"))
    }

    @Test
    fun formatChatDate_yesterday_returnsYesterdayWithDateAndTime() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 10, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Chat happened yesterday evening
        val chatCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 25, 20, 45, 0)
        }
        val result = ChatDateFormatter.formatChatDate(chatCal.timeInMillis, nowMillis = nowMillis)

        assertTrue("Expected to start with 'Yesterday, Sep 25 • ' but was '$result'", result.startsWith("Yesterday, Sep 25 • "))
        assertTrue("Expected to end with time '8:45 PM' but was '$result'", result.endsWith("8:45 PM"))
    }

    @Test
    fun formatChatDate_earlierThisYear_returnsFullDateAndTime() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 10, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Chat happened 6 days ago
        val chatCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 20, 11, 30, 0)
        }
        val result = ChatDateFormatter.formatChatDate(chatCal.timeInMillis, nowMillis = nowMillis)

        assertTrue("Expected to start with 'Sep 20, 2026 • ' but was '$result'", result.startsWith("Sep 20, 2026 • "))
        assertTrue("Expected to end with '11:30 AM' but was '$result'", result.endsWith("11:30 AM"))
    }

    @Test
    fun formatChatDate_priorYear_returnsFullDateWithYear() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 10, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val chatCal = Calendar.getInstance().apply {
            set(2025, Calendar.DECEMBER, 15, 9, 15, 0)
        }
        val result = ChatDateFormatter.formatChatDate(chatCal.timeInMillis, nowMillis = nowMillis)

        assertTrue("Expected to start with 'Dec 15, 2025 • ' but was '$result'", result.startsWith("Dec 15, 2025 • "))
        assertTrue("Expected to end with '9:15 AM' but was '$result'", result.endsWith("9:15 AM"))
    }

    @Test
    fun formatChatDate_futureSkew_treatedAsToday() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 10, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Chat timestamp is 5 minutes in future due to clock skew
        val chatCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 26, 10, 5, 0)
        }
        val result = ChatDateFormatter.formatChatDate(chatCal.timeInMillis, nowMillis = nowMillis)

        assertTrue("Expected to start with 'Today, Sep 26 • ' but was '$result'", result.startsWith("Today, Sep 26 • "))
    }
}

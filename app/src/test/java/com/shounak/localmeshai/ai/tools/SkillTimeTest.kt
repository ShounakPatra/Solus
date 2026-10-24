package com.shounak.localmeshai.ai.tools

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SkillTimeTest {
    @Test fun localAndOffsetTimesRespectTheirSpecifiedZones() {
        assertEquals(ZoneId.of("Asia/Kolkata"), SkillTime.parse("2027-01-01T09:00:00", ZoneId.of("Asia/Kolkata")).zone)
        assertEquals(Instant.parse("2027-01-01T03:30:00Z"), SkillTime.parse("2027-01-01T09:00:00+05:30").toInstant())
    }
    @Test fun dailyRemindersKeepLocalTimeAcrossDaylightSaving() {
        val start = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ZoneId.of("America/New_York"))
        val next = SkillTime.next(start, true, Instant.parse("2026-03-08T12:59:00Z"))
        assertEquals(9, next.hour)
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), next.toInstant())
        assertEquals(Instant.parse("2026-03-09T13:00:00Z"), SkillTime.next(start, true, next.toInstant()).toInstant())
    }
    @Test fun anchorRecoversOriginalLocalTimeAfterTheSpringGap() {
        val anchor = ZonedDateTime.of(2026, 3, 7, 2, 30, 0, 0, ZoneId.of("America/New_York"))
        assertEquals(3, SkillTime.next(anchor, true, Instant.parse("2026-03-08T06:00:00Z")).hour)
        assertEquals(2, SkillTime.next(anchor, true, Instant.parse("2026-03-09T05:00:00Z")).hour)
    }
    @Test fun onceRemindersAreNotSilentlyMovedIntoTheFuture() {
        val past = ZonedDateTime.parse("2026-01-01T00:00:00Z")
        assertEquals(past, SkillTime.next(past, false, Instant.parse("2026-02-01T00:00:00Z")))
    }
}

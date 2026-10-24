package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AudioDecodeLoopTest {
    @Test fun drainsDelayedSamplesAfterInputEosWithoutFeedingAgain() {
        var feeds = 0
        var drains = 0
        val samples = mutableListOf<String>()
        AudioDecodeLoop.run({}, feedInput = { feeds++; true }, drainOutput = {
            drains++
            if (drains == 2) samples.add("last samples")
            drains == 3
        })
        assertEquals(listOf("last samples"), samples)
        assertEquals(1, feeds)
    }

    @Test fun cancellationStopsWaitingForDecoderOutput() {
        var cancelled = false
        var drains = 0
        assertThrows(CancellationException::class.java) {
            AudioDecodeLoop.run({ if (cancelled) throw CancellationException() },
                feedInput = { true }, drainOutput = { drains++; cancelled = true; false })
        }
        assertEquals(1, drains)
    }
}

package com.shounak.localmeshai.ai.tools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DocumentSearchToolTest {
    private val arguments = """{"query":"hello"}"""

    @Test fun searchRunsOffCallbackThreadAndExceptionsDoNotEscape() {
        val callbackThread = Thread.currentThread()
        val tool = DocumentSearchTool { assertNotSame(callbackThread, Thread.currentThread()); "result" }
        assertEquals("result", tool.execute(arguments))
        assertEquals("Error: cancelled", DocumentSearchTool { throw CancellationException() }.execute(arguments))
        assertEquals("Error: Document search unavailable", DocumentSearchTool { error("index unavailable") }.execute(arguments))
    }

    @Test fun stopDuringSearchReturnsSafelyEvenWhenSearchDoesNotCooperate() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val callback = Executors.newSingleThreadExecutor()
        try {
            val tool = DocumentSearchTool(isCancelled = stopped::get) {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Deliberately uncooperative. */ }
                }
                "late result"
            }
            val result = callback.submit<String> { tool.execute(arguments) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stopped.set(true)
            assertEquals("Error: cancelled", result.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            callback.shutdownNow()
        }
    }

    @Test fun timeoutAndAlreadyStoppedCallsAreSafe() {
        assertEquals("Error: cancelled", DocumentSearchTool(isCancelled = { true }) {
            fail("Cancelled request must not search"); ""
        }.execute(arguments))
        val tool = DocumentSearchTool(timeoutMillis = 30) { Thread.sleep(5_000); "late" }
        assertEquals("Error: Document search timed out", tool.execute(arguments))
    }

    @Test fun interruptedSdkCallbackReturnsCancellationAndPreservesInterrupt() {
        val release = CountDownLatch(1)
        try {
            Thread.currentThread().interrupt()
            val result = DocumentSearchTool { release.await(); "late" }.execute(arguments)
            assertEquals("Error: cancelled", result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.countDown()
        }
    }
}

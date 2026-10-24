package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeWarmupDeadlineTest {
    @Test fun closingWaitsForAnAlreadyRunningNativeCancellationBeforeConversationTeardown() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val deadline = RuntimeWarmup.cancellationDeadline(1) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.submit { closing.countDown(); deadline.close(); closed.countDown() }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertFalse("Teardown must wait until native cancellation returns", closed.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); deadline.close(); worker.shutdownNow() }
    }

    @Test fun normalCompletionDisarmsLateCancellation() {
        val fired = CountDownLatch(1)
        RuntimeWarmup.cancellationDeadline(50) { fired.countDown() }.close()
        assertFalse(fired.await(100, TimeUnit.MILLISECONDS))
    }
}

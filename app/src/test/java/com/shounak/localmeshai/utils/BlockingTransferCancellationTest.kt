package com.shounak.localmeshai.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BlockingTransferCancellationTest {
    @Test fun cancellationUnblocksAnInFlightReadBeforeTheJobCompletes() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Cancellation failed to unblock input" }
                return -1
            }
        }
        val transfer = launch(Dispatchers.IO) {
            CancellableHttpTransfer.transfer(input, ByteArrayOutputStream(), coroutineContext,
                onAbort = { aborted.set(true); release.countDown() })
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            transfer.cancel()
            withTimeout(2_000) { transfer.join() }
            assertTrue(aborted.get())
            assertTrue(transfer.isCancelled)
        } finally {
            release.countDown()
            transfer.cancelAndJoin()
        }
    }

    @Test fun successfulTransferDoesNotAbortOrLeaveAChildJobBehind() = runBlocking {
        val aborts = AtomicBoolean(false)
        val output = ByteArrayOutputStream()
        CancellableHttpTransfer.transfer(ByteArrayInputStream("complete".toByteArray()), output,
            coroutineContext, onAbort = { aborts.set(true) })
        assertEquals("complete", output.toString())
        assertFalse(aborts.get())
        assertFalse(coroutineContext[Job]!!.children.any())
    }

    @Test fun transportHookFiresWhileItsOwningCoroutineIsBlocked() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val worker = launch(Dispatchers.IO) {
            val hook = coroutineContext[Job]!!.abortTransportOnCancellation {
                cancelled.set(true)
                release.countDown()
            }
            try {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } finally { hook.dispose() }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.cancel()
            withTimeout(2_000) { worker.join() }
            assertTrue(cancelled.get())
        } finally {
            release.countDown()
            worker.cancelAndJoin()
        }
    }
}

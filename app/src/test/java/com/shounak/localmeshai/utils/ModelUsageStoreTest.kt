package com.shounak.localmeshai.utils

import com.shounak.localmeshai.features.FeatureTestPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ModelUsageStoreTest {
    @Test fun startupLoadsOffCallerThreadAndPreservesQueuedLoadsAndDeletion() = runBlocking {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = FeatureTestPreferences(mapOf("deleted" to 10L, "old" to 20L))
        try {
            val store = ModelUsageStore({
                assertNotSame(caller, Thread.currentThread())
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                prefs
            }, scope)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(store.usedAt.value.isEmpty())
            store.remove("deleted")
            store.recordUsed("new")
            store.recordUsed("newer")
            release.countDown()
            val loaded = withTimeout(2_000) { store.usedAt.first { "newer" in it } }
            assertFalse("deleted" in loaded)
            assertEquals(20L, loaded["old"])
            assertTrue(loaded.getValue("newer") > loaded.getValue("new"))
            assertFalse(prefs.contains("deleted"))
            assertEquals(loaded.getValue("new"), prefs.getLong("new", 0L))
            store.remove("new")
            withTimeout(2_000) { store.usedAt.first { "new" !in it } }
            assertFalse(prefs.contains("new"))
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}

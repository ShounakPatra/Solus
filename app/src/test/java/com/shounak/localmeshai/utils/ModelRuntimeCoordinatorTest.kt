package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ai.ShutdownResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ModelRuntimeCoordinatorTest {

    private fun doCleanup() = runBlocking {
        ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Chat)
        ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Vision)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Chat, null)
        ModelRuntimeCoordinator.setReleasedCallback(ModelRuntimeOwner.Vision, null)
        ModelRuntimeCoordinator.releaseAll()
        Unit
    }

    @Before
    fun setup() {
        doCleanup()
    }

    @After
    fun tearDown() {
        doCleanup()
    }

    @Test
    fun modelSwitchWaitsForNativeReleaseInBothDirections() = runBlocking {
        for ((previous, next) in listOf(ModelRuntimeOwner.Chat to ModelRuntimeOwner.Vision,
                ModelRuntimeOwner.Vision to ModelRuntimeOwner.Chat)) {
            doCleanup()
            val closing = CompletableDeferred<Unit>()
            val mayClose = CompletableDeferred<Unit>()
            val previousAlive = AtomicBoolean(true)
            val nextStarted = AtomicBoolean(false)
            ModelRuntimeCoordinator.register(previous, release = {
                closing.complete(Unit)
                mayClose.await()
                previousAlive.set(false)
                ShutdownResult.Success
            })
            ModelRuntimeCoordinator.register(next, release = { ShutdownResult.Success })
            ModelRuntimeCoordinator.activate(previous)
            val switch = launch(Dispatchers.IO) {
                ModelRuntimeCoordinator.withRuntime(next) {
                    assertFalse(previousAlive.get())
                    nextStarted.set(true)
                }
            }
            try {
                kotlinx.coroutines.withTimeout(3_000L) { closing.await() }
                assertFalse(nextStarted.get())
                assertTrue(ModelRuntimeCoordinator.isActive(previous))
                mayClose.complete(Unit)
                switch.join()
                assertTrue(nextStarted.get())
                assertTrue(ModelRuntimeCoordinator.isActive(next))
            } finally { mayClose.complete(Unit); switch.cancel() }
        }
    }

    @Test
    fun testMutuallyExclusiveOwnershipBetweenOwners() = runBlocking {
        val chatReleased = AtomicBoolean(false)
        val chatActive = AtomicBoolean(false)
        val visionActive = AtomicBoolean(false)

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Chat,
            release = {
                chatActive.set(false)
                chatReleased.set(true)
                ShutdownResult.Success
            }
        )

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Vision,
            release = {
                visionActive.set(false)
                ShutdownResult.Success
            }
        )

        // Activate Chat
        ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Chat) {
            chatActive.set(true)
            assertTrue(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat))
            assertFalse(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision))
        }

        // Now activate Vision: this MUST release Chat before running Vision block
        ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Vision) {
            visionActive.set(true)
            assertTrue("Chat must be released before Vision starts", chatReleased.get())
            assertFalse("Chat must not be active", chatActive.get())
            assertTrue(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision))
        }

        assertTrue(visionActive.get())
    }

    @Test
    fun testTransitionFailsWhenPreviousOwnerShutdownFails() = runBlocking {
        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Chat,
            release = {
                throw RuntimeException("Chat failed to release native memory")
            }
        )

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Vision,
            release = { ShutdownResult.Success }
        )

        // Activate Chat
        ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Chat)
        assertTrue(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat))

        // Attempt to activate Vision: must fail because Chat release failed
        try {
            ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Vision) {
                fail("Vision should not run when previous release failed")
            }
            fail("Expected IllegalStateException on transition failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Chat") == true)
        }

        // Vision should not have become active owner
        assertFalse(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision))
    }

    @Test
    fun testReleaseCallbacksInvokedOutsideOperationsLock() = runBlocking {
        val callbackInvoked = AtomicBoolean(false)
        val lockCanBeAcquiredInCallback = AtomicBoolean(false)

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Chat,
            onReleased = {
                callbackInvoked.set(true)
            },
            release = { ShutdownResult.Success }
        )

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Vision,
            release = { ShutdownResult.Success }
        )

        ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Chat)

        // Switch to Vision, which triggers Chat's release and onReleased callback
        ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Vision)

        assertTrue("onReleased callback must have been invoked", callbackInvoked.get())
    }

    @Test
    fun testReleaseAllStopsAllRegisteredRuntimes() = runBlocking {
        val chatReleased = AtomicBoolean(false)
        val visionReleased = AtomicBoolean(false)

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Chat,
            release = {
                chatReleased.set(true)
                ShutdownResult.Success
            }
        )

        ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Vision,
            release = {
                visionReleased.set(true)
                ShutdownResult.Success
            }
        )

        ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Chat)
        assertTrue(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat))

        val result = ModelRuntimeCoordinator.releaseAll()
        assertEquals(ShutdownResult.Success, result)
        assertTrue(chatReleased.get())
        assertFalse(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat))
        assertFalse(ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision))
    }
}

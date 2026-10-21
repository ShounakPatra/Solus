package com.shounak.localmeshai.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shounak.localmeshai.data.ChatSessionRepository
import com.shounak.localmeshai.data.StartupCoordinator
import com.shounak.localmeshai.data.StoreLoadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupResponsivenessTest {

    @Test
    fun startupCoordinatorProvidesSingletonAndTracksStoreStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator1 = StartupCoordinator.getInstance(context)
        val coordinator2 = StartupCoordinator.getInstance(context)

        assertSame("StartupCoordinator must be application singleton", coordinator1, coordinator2)

        coordinator1.updateChatStoreState(StoreLoadState.Ready)
        assertEquals(StoreLoadState.Ready, coordinator1.chatStoreState.value)

        coordinator1.updateVisionStoreState(StoreLoadState.Ready)
        assertEquals(StoreLoadState.Ready, coordinator1.visionStoreState.value)
    }

    @Test
    fun chatSessionRepositoryIsApplicationSingleton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo1 = ChatSessionRepository.getInstance(context)
        val repo2 = ChatSessionRepository.getInstance(context)

        assertSame("ChatSessionRepository must be application singleton", repo1, repo2)
        assertNotNull(repo1.chatFile)
        assertNotNull(repo1.visionFile)
    }

    @Test
    fun deferredStartupWorkCompletesWithoutExceptions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = StartupCoordinator.getInstance(context)
        // Calling deferred startup tasks safely
        coordinator.runDeferredStartupWork()
        assertTrue(true)
    }
}

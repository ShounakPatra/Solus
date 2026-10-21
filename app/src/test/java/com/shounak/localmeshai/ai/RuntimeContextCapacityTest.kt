package com.shounak.localmeshai.ai

import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelCapabilitySnapshot
import org.junit.Assert.*
import org.junit.Test

class RuntimeContextCapacityTest {
    @Test fun failedPrimaryAndSuccessfulFallbackPublishOnlyAllocatedCapacity() {
        for (fallback in listOf(2048, 1024)) {
            val capacity = RuntimeContextCapacity()
            assertThrows(IllegalStateException::class.java) {
                capacity.initialize(8192) { error("native initialization failed") }
            }
            assertNull(capacity.tokens)
            val runtime = capacity.initialize(fallback) { "runtime" }
            assertEquals("runtime", runtime)
            assertEquals(fallback, capacity.tokens)
        }
    }
    @Test fun reloadFailureAndUnloadClearPreviousCapacity() {
        val capacity = RuntimeContextCapacity()
        capacity.initialize(8192) { Unit }
        assertThrows(IllegalStateException::class.java) { capacity.initialize(2048) { error("failed") } }
        assertNull(capacity.tokens)
        capacity.initialize(4096) { Unit }
        capacity.clear()
        assertNull(capacity.tokens)
    }
    @Test fun matchingReadySnapshotUsesAllocatedCapacityInsteadOfAdvertisedLimit() {
        val snapshot = ModelCapabilitySnapshot("model", "revision", ModelCapabilities(), initializedContextTokens = 2048)
        assertEquals(2048, snapshot.contextTokensFor("model", "revision", true))
        assertNull(snapshot.contextTokensFor("model", "revision", false))
        assertNull(snapshot.contextTokensFor("other-model", "revision", true))
        assertNull(snapshot.contextTokensFor("model", "other-revision", true))
        assertNull(snapshot.copy(initializedContextTokens = null).contextTokensFor("model", "revision", true))
        assertNull(snapshot.copy(initializedContextTokens = 0).contextTokensFor("model", "revision", true))
    }
}

package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiteRtRuntimeCacheTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun cacheIdentityChangesWithModelBackendOrAppBuild() {
        val model = files.newFile("model.litertlm").apply { writeText("model-v1") }
        val key = LiteRtRuntimeCache.cacheKey("test", InferenceBackend.LITERT_GPU, model, 7)
        assertEquals(key, LiteRtRuntimeCache.cacheKey("test", InferenceBackend.LITERT_GPU, model, 7))
        assertNotEquals(key, LiteRtRuntimeCache.cacheKey("test", InferenceBackend.LITERT_CPU, model, 7))
        assertNotEquals(key, LiteRtRuntimeCache.cacheKey("test", InferenceBackend.LITERT_GPU, model, 8))
        model.appendText("new weights")
        assertNotEquals(key, LiteRtRuntimeCache.cacheKey("test", InferenceBackend.LITERT_GPU, model, 7))
    }

    @Test fun clearInactiveRoot_protectsLeasedCachesAndDeletesInactive() {
        val root = files.newFolder("runtime")
        val active = root.resolve("active_cache").apply { mkdir() }
        val inactive1 = root.resolve("inactive_1").apply { mkdir() }
        val inactive2 = root.resolve("inactive_2").apply { mkdir() }

        // Lease the active cache
        val lease = LiteRtRuntimeCache.lease(active)
        assertTrue(LiteRtRuntimeCache.isLeased(active))
        assertFalse(LiteRtRuntimeCache.isLeased(inactive1))

        // Clear inactive caches
        val deletedCount = LiteRtRuntimeCache.clearInactiveRoot(root)
        assertEquals(2, deletedCount)

        assertTrue("Active leased cache must still exist", active.exists())
        assertFalse("Inactive cache 1 must be deleted", inactive1.exists())
        assertFalse("Inactive cache 2 must be deleted", inactive2.exists())

        // Now release the lease and clear again
        lease.close()
        assertFalse(LiteRtRuntimeCache.isLeased(active))

        val deletedAfterRelease = LiteRtRuntimeCache.clearInactiveRoot(root)
        assertEquals(1, deletedAfterRelease)
        assertFalse("Previously active cache is now inactive and deleted", active.exists())
    }

    @Test fun lease_referenceCountingAndRelease() {
        val root = files.newFolder("runtime_refcount")
        val target = root.resolve("target_cache").apply { mkdir() }

        assertFalse(LiteRtRuntimeCache.isLeased(target))

        val lease1 = LiteRtRuntimeCache.lease(target)
        assertTrue(LiteRtRuntimeCache.isLeased(target))

        val lease2 = LiteRtRuntimeCache.lease(target)
        assertTrue(LiteRtRuntimeCache.isLeased(target))

        lease1.close()
        assertTrue("Still leased by lease2", LiteRtRuntimeCache.isLeased(target))

        lease2.close()
        assertFalse("No remaining leases", LiteRtRuntimeCache.isLeased(target))
    }

    @Test fun concurrentPruningPreservesLeasedDirectories() {
        val root = files.newFolder("runtime_concurrent")
        val dirs = (1..5).map { index ->
            root.resolve("cache-$index").apply { mkdir() }
        }

        // Lease the first two directories
        val lease1 = LiteRtRuntimeCache.lease(dirs[0])
        val lease2 = LiteRtRuntimeCache.lease(dirs[1])

        val threads = (1..4).map {
            Thread {
                LiteRtRuntimeCache.clearInactiveRoot(root)
            }.apply { start() }
        }
        threads.forEach { it.join() }

        assertTrue("dirs[0] must be preserved", dirs[0].exists())
        assertTrue("dirs[1] must be preserved", dirs[1].exists())
        assertFalse("dirs[2] must be deleted", dirs[2].exists())
        assertFalse("dirs[3] must be deleted", dirs[3].exists())
        assertFalse("dirs[4] must be deleted", dirs[4].exists())

        lease1.close()
        lease2.close()
    }
}

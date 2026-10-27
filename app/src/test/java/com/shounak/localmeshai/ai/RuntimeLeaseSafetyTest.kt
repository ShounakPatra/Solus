package com.shounak.localmeshai.ai

import android.content.Context
import android.content.ContextWrapper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelArtifactStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class RuntimeLeaseSafetyTest {
    @Test fun firstThinkOnTurnRebuildsTheInitiallyDisabledLiteRtConversation() {
        val manager = ChatInferenceManager(context())
        val runtimeType = manager.javaClass.getDeclaredField("runtime").type
        set(manager, "runtime", checkNotNull(runtimeType.enumConstants).first { it.toString() == "LiteRtLm" })
        set(manager, "isDefaultThinkingModel", true)
        val modeType = manager.javaClass.getDeclaredField("liteRtConversationMode").type
        set(manager, "liteRtConversationMode", checkNotNull(modeType.enumConstants).first { it.toString() == "NoThinking" })
        assertTrue(manager.needsResetForThinkingMode(true))
        assertFalse(manager.needsResetForThinkingMode(false))
        set(manager, "liteRtConversationMode", checkNotNull(modeType.enumConstants).first { it.toString() == "Default" })
        assertFalse(manager.needsResetForThinkingMode(true))
        assertTrue(manager.needsResetForThinkingMode(false))
    }

    @Test fun visionThinkingToggleRebuildsTheLiteRtConversationCreatedWithADifferentValue() {
        val manager = VisionInferenceManager(context())
        val runtimeType = manager.javaClass.getDeclaredField("runtime").type
        set(manager, "runtime", checkNotNull(runtimeType.enumConstants).first { it.toString() == "LiteRtLm" })
        set(manager, "isDefaultThinkingModel", true)
        // The load creates the first conversation with enable_thinking=false.
        set(manager, "liteRtConversationThinking", false)
        assertTrue(manager.needsResetForThinkingMode(true))
        assertFalse(manager.needsResetForThinkingMode(false))
        set(manager, "liteRtConversationThinking", true)
        assertFalse(manager.needsResetForThinkingMode(true))
        assertTrue(manager.needsResetForThinkingMode(false))
        // Models without a thinking switch never reset for the toggle.
        set(manager, "isDefaultThinkingModel", false)
        set(manager, "liteRtConversationThinking", null)
        assertFalse(manager.needsResetForThinkingMode(true))
        assertFalse(manager.needsResetForThinkingMode(false))
        // Non-LiteRT runtimes carry thinking per request.
        set(manager, "isDefaultThinkingModel", true)
        set(manager, "runtime", checkNotNull(runtimeType.enumConstants).first { it.toString() == "LlamaCpp" })
        assertFalse(manager.needsResetForThinkingMode(true))
    }

    @get:Rule val files = TemporaryFolder()

    private fun context() = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = files.root.resolve("cache").apply { mkdirs() }
        override fun getFilesDir(): File = files.root.resolve("files").apply { mkdirs() }
    }

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun uninitializedLiteRtEngine() = Engine(
        EngineConfig(modelPath = files.root.resolve("failed-init.litertlm").absolutePath, backend = Backend.CPU())
    )

    @Test fun liteRtCleanupSkipsEngineWithoutNativeHandle() {
        val engine = uninitializedLiteRtEngine()
        assertFalse(engine.isInitialized())
        // Reproduce the SDK error without invoking JNI: close is not idempotent.
        val error = assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals("Engine is not initialized.", error.message)
        engine.closeIfInitialized()
        engine.closeIfInitialized()
    }

    private fun checkUninitializedLiteRtShutdown(manager: Any, close: () -> ShutdownResult) {
        val model = files.newFile()
        val lease = ModelArtifactStorage.leaseArtifact(model)
        val cache = files.newFolder()
        val cacheLease = LiteRtRuntimeCache.lease(cache)
        set(manager, "liteRtEngine", uninitializedLiteRtEngine())
        set(manager, "activeModelLease", lease)
        set(manager, "liteRtCacheDir", cache)
        try {
            assertEquals(ShutdownResult.Success, close())
            assertNull(get(manager, "liteRtEngine"))
            assertNull(get(manager, "activeModelLease"))
            assertNull(get(manager, "liteRtCacheDir"))
            assertFalse(ModelArtifactStorage.isArtifactLeased(model))
            assertFalse(LiteRtRuntimeCache.isLeased(cache))
            assertEquals(ShutdownResult.Success, close())
        } finally {
            cacheLease.close()
            lease.close()
        }
    }

    @Test fun uninitializedLiteRtChatEngineDoesNotBlockNextLoad() {
        val manager = ChatInferenceManager(context())
        checkUninitializedLiteRtShutdown(manager, manager::close)
    }

    @Test fun uninitializedLiteRtVisionEngineDoesNotBlockNextLoad() {
        val manager = VisionInferenceManager(context())
        checkUninitializedLiteRtShutdown(manager, manager::close)
    }

    @Test fun bothManagersPublishAllocatedContextOnlyForReadyRuntimeAndClearAfterClose() {
        val bridge = Proxy.newProxyInstance(LlamaNativeBridge::class.java.classLoader,
            arrayOf(LlamaNativeBridge::class.java)) { _, method, _ ->
            when (method.name) {
                "isAvailable" -> true
                "stop", "free" -> null
                "getMetadata" -> "{}"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as LlamaNativeBridge
        val chat = ChatInferenceManager(context())
        val vision = VisionInferenceManager(context())
        for (manager in listOf(chat, vision)) {
            val engine = LlamaCppEngine(nativeBridge = bridge).also {
                set(it, "engineState", LlamaCppEngine.EngineState.OPEN)
                set(it, "nativeHandle", 1L)
            }
            set(manager, "llamaCppEngine", engine)
            set(manager, "configuredCapabilities", com.shounak.localmeshai.models.ModelCapabilitySnapshot(
                "model", "revision", com.shounak.localmeshai.models.ModelCapabilities()))
            val capacity = get(manager, "runtimeContextCapacity") as RuntimeContextCapacity
            capacity.initialize(2048) { Unit }
            fun snapshot() = if (manager === chat) chat.runtimeCapabilities else vision.runtimeCapabilities
            assertNull(snapshot()) // An allocated but not ready engine is not a UI contract.
            val runtimeField = manager.javaClass.getDeclaredField("runtime").apply { isAccessible = true }
            runtimeField.set(manager, requireNotNull(runtimeField.type.enumConstants).first { it.toString() == "LlamaCpp" })
            assertEquals(2048, snapshot()?.initializedContextTokens)
            assertEquals(ShutdownResult.Success, if (manager === chat) chat.close() else vision.close())
            assertNull(capacity.tokens)
            assertNull(snapshot())
        }
    }

    private fun failingEngine(): LlamaCppEngine {
        val bridge = Proxy.newProxyInstance(LlamaNativeBridge::class.java.classLoader,
            arrayOf(LlamaNativeBridge::class.java)) { _, method, _ ->
            when (method.name) {
                "isAvailable" -> true
                "stop" -> null
                "free" -> throw IllegalStateException("native close failure")
                else -> throw UnsupportedOperationException(method.name)
            }
        } as LlamaNativeBridge
        return LlamaCppEngine(nativeBridge = bridge).also {
            set(it, "engineState", LlamaCppEngine.EngineState.OPEN)
            set(it, "nativeHandle", 1L)
        }
    }

    private fun initializationFailureEngine(failCleanup: Boolean): LlamaCppEngine {
        val bridge = Proxy.newProxyInstance(LlamaNativeBridge::class.java.classLoader,
            arrayOf(LlamaNativeBridge::class.java)) { _, method, _ ->
            when (method.name) {
                "isAvailable" -> true
                "initModelBundle" -> 41L
                "getMetadata" -> throw IllegalStateException("metadata read failed after native allocation")
                "stop" -> null
                "free" -> if (failCleanup) throw IllegalStateException("native initialization cleanup failed") else null
                else -> throw UnsupportedOperationException(method.name)
            }
        } as LlamaNativeBridge
        return LlamaCppEngine(nativeBridge = bridge)
    }

    private fun checkInitializationCleanup(
        manager: Any,
        initializeOwned: (LlamaCppEngine, () -> Unit) -> Unit,
        close: () -> ShutdownResult,
        failCleanup: Boolean,
        replacingClosedEngine: Boolean = false
    ) {
        val model = files.newFile().apply { writeText("model protected while native handle exists") }
        val lease = ModelArtifactStorage.leaseArtifact(model)
        set(manager, "activeModelLease", lease)
        if (replacingClosedEngine) {
            val previous = LlamaCppEngine()
            previous.close()
            set(manager, "llamaCppEngine", previous)
        }
        val engine = initializationFailureEngine(failCleanup)
        try {
            assertThrows(IllegalStateException::class.java) {
                initializeOwned(engine) {
                    assertSame(engine, get(manager, "llamaCppEngine"))
                    engine.initialize(modelPath = model.absolutePath)
                }
            }
            if (failCleanup) {
                assertEquals("CLOSE_FAILED", engine.testEngineStateName)
                assertEquals(41L, engine.testFailedHandle)
                assertTrue(close() is ShutdownResult.Failed)
                assertTrue(close() is ShutdownResult.Failed)
                assertSame(engine, get(manager, "llamaCppEngine"))
                assertSame(lease, get(manager, "activeModelLease"))
                assertTrue(ModelArtifactStorage.isArtifactLeased(model))
                assertFalse(ModelArtifactStorage.reserveAndExecuteDeletion(model) { it.delete() })
                assertTrue(model.exists())
            } else {
                assertEquals(ShutdownResult.Success, close())
                assertNull(get(manager, "llamaCppEngine"))
                assertNull(get(manager, "activeModelLease"))
                assertFalse(ModelArtifactStorage.isArtifactLeased(model))
            }
        } finally {
            // The fake has no native allocation; release only the test's global file lease.
            lease.close()
        }
    }

    @Test fun failedChatInitialAndFallbackInitializationRetainOwnership() {
        for (replacing in listOf(false, true)) {
            val manager = ChatInferenceManager(context())
            checkInitializationCleanup(manager, manager::initializeGgufEngine, manager::close,
                failCleanup = true, replacingClosedEngine = replacing)
        }
    }

    @Test fun failedVisionInitialAndFallbackInitializationRetainOwnership() {
        for (replacing in listOf(false, true)) {
            val manager = VisionInferenceManager(context())
            checkInitializationCleanup(manager, manager::initializeGgufEngine, manager::close,
                failCleanup = true, replacingClosedEngine = replacing)
        }
    }

    @Test fun safelyCleanedChatInitializationFailureReleasesOwnership() {
        val manager = ChatInferenceManager(context())
        checkInitializationCleanup(manager, manager::initializeGgufEngine, manager::close, failCleanup = false)
    }

    @Test fun safelyCleanedVisionInitializationFailureReleasesOwnership() {
        val manager = VisionInferenceManager(context())
        checkInitializationCleanup(manager, manager::initializeGgufEngine, manager::close, failCleanup = false)
    }

    @Test fun failedChatShutdownRetainsModelAndCacheLeases() {
        val manager = ChatInferenceManager(context())
        val closes = AtomicInteger()
        val modelLease = AutoCloseable { closes.incrementAndGet() }
        val cacheRoot = files.newFolder("chat-runtime")
        val cache = cacheRoot.resolve("active").apply { mkdirs() }
        val cacheLease = LiteRtRuntimeCache.lease(cache)
        set(manager, "activeModelLease", modelLease)
        set(manager, "liteRtCacheDir", cache)
        set(manager, "llamaCppEngine", failingEngine())
        try {
            assertTrue(manager.close() is ShutdownResult.Failed)
            assertTrue(manager.close() is ShutdownResult.Failed)
            assertEquals(0, closes.get())
            assertSame(modelLease, get(manager, "activeModelLease"))
            assertEquals(cache, get(manager, "liteRtCacheDir"))
            assertTrue(LiteRtRuntimeCache.isLeased(cache))
            assertEquals(0, LiteRtRuntimeCache.clearInactiveRoot(cacheRoot))
            assertTrue(cache.exists())
        } finally { cacheLease.close() }
    }

    @Test fun failedVisionShutdownRetainsModelAndCacheLeases() {
        val manager = VisionInferenceManager(context())
        val closes = AtomicInteger()
        val lease = AutoCloseable { closes.incrementAndGet() }
        val cache = files.newFolder("vision-cache")
        val cacheLease = LiteRtRuntimeCache.lease(cache)
        set(manager, "activeModelLease", lease)
        set(manager, "liteRtCacheDir", cache)
        set(manager, "llamaCppEngine", failingEngine())
        try {
            assertTrue(manager.close() is ShutdownResult.Failed)
            assertTrue(manager.close() is ShutdownResult.Failed)
            assertEquals(0, closes.get())
            assertSame(lease, get(manager, "activeModelLease"))
            assertTrue(LiteRtRuntimeCache.isLeased(cache))
        } finally { cacheLease.close() }
    }

    @Test fun successfulShutdownClosesDocumentDescriptorLeaseExactlyOnce() {
        val manager = ChatInferenceManager(context())
        val closes = AtomicInteger()
        set(manager, "activeModelLease", AutoCloseable { closes.incrementAndGet() })
        assertEquals(ShutdownResult.Success, manager.close())
        assertEquals(ShutdownResult.Success, manager.close())
        assertEquals(1, closes.get())
        assertNull(get(manager, "activeModelLease"))
    }

    @Test fun failingDescriptorCloseRetainsOwnershipForRetry() {
        val manager = ChatInferenceManager(context())
        val attempts = AtomicInteger()
        val lease = AutoCloseable {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("descriptor close failure")
        }
        set(manager, "activeModelLease", lease)
        assertTrue(manager.close() is ShutdownResult.Failed)
        assertSame(lease, get(manager, "activeModelLease"))
        assertEquals(ShutdownResult.Success, manager.close())
        assertNull(get(manager, "activeModelLease"))
        assertEquals(2, attempts.get())
    }
}

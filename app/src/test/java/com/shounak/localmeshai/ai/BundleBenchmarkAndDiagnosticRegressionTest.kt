package com.shounak.localmeshai.ai

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BundleBenchmarkAndDiagnosticRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private inner class FakeTestContext(private val prefs: android.content.SharedPreferences) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = prefs
        override fun getApplicationContext(): android.content.Context = this
        override fun getPackageName(): String = "com.shounak.localmeshai"
        override fun getFilesDir(): File = tempFolder.newFolder("fake_files_${System.nanoTime()}")
    }

    private fun createFakeContext(): android.content.Context {
        val memoryPrefs = mutableMapOf<String, String>()
        val editorHandler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    memoryPrefs[args!![0] as String] = args[1] as String
                    proxy
                }
                "apply", "commit" -> true
                else -> proxy
            }
        }
        val editor = Proxy.newProxyInstance(
            android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java),
            editorHandler
        ) as android.content.SharedPreferences.Editor

        val prefsHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getString" -> {
                    val key = args!![0] as String
                    val def = if (args.size > 1) args[1] as? String else null
                    memoryPrefs[key] ?: def
                }
                "edit" -> editor
                else -> null
            }
        }
        val sharedPrefs = Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
            prefsHandler
        ) as android.content.SharedPreferences

        return FakeTestContext(sharedPrefs)
    }

    private class TestNativeBridge : LlamaNativeBridge {
        val initializedPaths = mutableListOf<String>()
        var initializedProjector: String? = null
        val initBundleCallCount = AtomicInteger(0)
        val freedHandles = mutableListOf<Long>()
        val generateCount = AtomicInteger(0)

        override fun isAvailable(): Boolean = true
        override fun getLastError(): String? = null

        override fun inspectModel(modelPath: String): String? {
            return """{"valid":true,"architecture":"llama","name":"TestModel","is_supported":true}"""
        }

        override fun isVulkanAvailable(): Boolean = true
        override fun getActiveBackend(handle: Long): String = "CPU"

        override fun initModel(
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nCtx: Int,
            nBatch: Int,
            nUbatch: Int,
            backend: Int,
            nGpuLayers: Int,
            operationControlId: Long
        ): Long {
            initializedPaths.add(modelPath)
            return 2001L
        }

        override fun initModelBundle(
            modelPaths: Array<String>,
            projectorPath: String?,
            nThreads: Int,
            nThreadsBatch: Int,
            nCtx: Int,
            nBatch: Int,
            nUbatch: Int,
            backend: Int,
            nGpuLayers: Int,
            operationControlId: Long
        ): Long {
            initBundleCallCount.incrementAndGet()
            initializedPaths.addAll(modelPaths)
            initializedProjector = projectorPath
            return 2002L
        }

        override fun generateStream(
            handle: Long,
            generationId: Long,
            prompt: String,
            roles: Array<String>?,
            contents: Array<String>?,
            temperature: Float,
            topP: Float,
            topK: Int,
            minP: Float,
            repeatPenalty: Float,
            maxTokens: Int,
            seed: Int,
            callback: LlamaTokenCallback
        ): Int {
            generateCount.incrementAndGet()
            callback.onFirstToken()
            callback.onToken("Diagnostic token output.")
            callback.onMetrics(50L, 100L, 4)
            callback.onComplete()
            return 0
        }

        override fun stop(handle: Long, generationId: Long) {}
        override fun getMetadata(handle: Long): String? = null
        override fun free(handle: Long) {
            freedHandles.add(handle)
        }
    }

    @Test
    fun formatDetermination_isAuthoritativeAcrossBundlesAndSingleFiles() {
        // Single URI GGUF bundle
        val singleGgufBundle = ModelBundle(
            id = "single_gguf",
            displayName = "Single GGUF",
            mainArtifacts = listOf(
                ModelArtifact(
                    role = ArtifactRole.Main,
                    displayName = "model.gguf",
                    source = ArtifactSource.PersistedUri("content://uri/1")
                )
            )
        )
        val singleModel = ModelInfo(
            id = "single_gguf",
            name = "Single GGUF",
            size = "4.0 GB",
            status = ModelStatus.Available,
            type = ModelType.Text,
            fileName = "model.gguf",
            description = "",
            backend = "llama.cpp GGUF",
            deviceTarget = "All devices",
            bundle = singleGgufBundle
        )
        assertTrue(
            "Single GGUF bundle must be identified as GGUF",
            singleModel.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(singleModel.fileName)
        )

        // Split URI GGUF bundle
        val splitGgufBundle = ModelBundle(
            id = "split_gguf",
            displayName = "Split GGUF",
            mainArtifacts = listOf(
                ModelArtifact(
                    role = ArtifactRole.Main,
                    displayName = "model-00001-of-00002.gguf",
                    source = ArtifactSource.PersistedUri("content://uri/1")
                ),
                ModelArtifact(
                    role = ArtifactRole.Shard,
                    displayName = "model-00002-of-00002.gguf",
                    source = ArtifactSource.PersistedUri("content://uri/2")
                )
            )
        )
        val splitModel = ModelInfo(
            id = "split_gguf",
            name = "Split GGUF",
            size = "4.0 GB",
            status = ModelStatus.Available,
            type = ModelType.Text,
            fileName = "model-00001-of-00002.gguf",
            description = "",
            backend = "llama.cpp GGUF",
            deviceTarget = "All devices",
            bundle = splitGgufBundle
        )
        assertTrue(
            "Split GGUF bundle must be identified as GGUF",
            splitModel.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(splitModel.fileName)
        )

        // Multimodal projector bundle
        val visionBundle = ModelBundle(
            id = "vision_bundle",
            displayName = "Vision Bundle",
            mainArtifacts = listOf(
                ModelArtifact(
                    role = ArtifactRole.Main,
                    displayName = "model.gguf",
                    source = ArtifactSource.PersistedUri("content://uri/1")
                )
            ),
            projectorArtifact = ModelArtifact(
                role = ArtifactRole.Projector,
                displayName = "mmproj.gguf",
                source = ArtifactSource.PersistedUri("content://uri/proj")
            ),
            capabilities = ModelCapabilities(supportsVision = true)
        )
        val visionModel = ModelInfo(
            id = "vision_bundle",
            name = "Vision Bundle",
            size = "4.0 GB",
            status = ModelStatus.Available,
            type = ModelType.Vision,
            fileName = "model.gguf",
            description = "",
            backend = "llama.cpp GGUF",
            deviceTarget = "All devices",
            bundle = visionBundle
        )
        assertTrue(
            "Vision GGUF bundle must be identified as GGUF",
            visionModel.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(visionModel.fileName)
        )

        // MediaPipe .task non-GGUF
        val taskModel = ModelInfo(
            id = "mediapipe_model",
            name = "MediaPipe Model",
            size = "1.0 GB",
            status = ModelStatus.Available,
            type = ModelType.Text,
            fileName = "classifier.task",
            description = "",
            backend = "MediaPipe",
            deviceTarget = "All devices",
            bundle = null
        )
        assertFalse(
            "Non-GGUF .task file must not be identified as GGUF",
            taskModel.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(taskModel.fileName)
        )

        // LiteRT .litertlm non-GGUF
        val litertModel = ModelInfo(
            id = "litert_model",
            name = "LiteRT Model",
            size = "2.0 GB",
            status = ModelStatus.Available,
            type = ModelType.Text,
            fileName = "gemma.litertlm",
            description = "",
            backend = "LiteRT",
            deviceTarget = "All devices",
            bundle = null
        )
        assertFalse(
            "Non-GGUF .litertlm file must not be identified as GGUF",
            litertModel.bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(litertModel.fileName)
        )
    }

    @Test
    fun diagnosticRunnerBundleOverload_passesAllOrderedShardsAndProjector() = runBlocking {
        val context = createFakeContext()
        val bridge = TestNativeBridge()

        val shard1File = tempFolder.newFile("model-00001-of-00002.gguf").apply { writeBytes(ByteArray(1024) { 0x11 }) }
        val shard2File = tempFolder.newFile("model-00002-of-00002.gguf").apply { writeBytes(ByteArray(1024) { 0x22 }) }
        val projFile = tempFolder.newFile("mmproj-test.gguf").apply { writeBytes(ByteArray(512) { 0x33 }) }

        val shard1 = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = shard1File.name,
            source = ArtifactSource.AppManaged(shard1File.absolutePath),
            sizeBytes = shard1File.length(),
            lastModified = shard1File.lastModified()
        )
        val shard2 = ModelArtifact(
            role = ArtifactRole.Shard,
            displayName = shard2File.name,
            source = ArtifactSource.AppManaged(shard2File.absolutePath),
            sizeBytes = shard2File.length(),
            lastModified = shard2File.lastModified()
        )
        val proj = ModelArtifact(
            role = ArtifactRole.Projector,
            displayName = projFile.name,
            source = ArtifactSource.AppManaged(projFile.absolutePath),
            sizeBytes = projFile.length(),
            lastModified = projFile.lastModified()
        )

        val bundle = ModelBundle(
            id = "split_multimodal_bundle",
            displayName = "Split Multimodal Model",
            mainArtifacts = listOf(shard1, shard2),
            projectorArtifact = proj,
            capabilities = ModelCapabilities(supportsVision = true)
        )

        val report = GgufDiagnosticRunner.runDiagnostics(
            context = context,
            bundle = bundle,
            threads = 4,
            contextWindow = 2048,
            prompts = listOf("Hello test"),
            customNativeBridge = bridge
        )

        assertNotNull("Diagnostic report should be generated", report)
        assertTrue("Diagnostics should succeed with fake bridge", report.overallSuccess)
        assertEquals(1, bridge.initBundleCallCount.get())
        assertEquals("Both shards must be initialized", 2, bridge.initializedPaths.size)
        assertEquals(shard1File.absolutePath, bridge.initializedPaths[0])
        assertEquals(shard2File.absolutePath, bridge.initializedPaths[1])
        assertEquals(projFile.absolutePath, bridge.initializedProjector)
        assertTrue("Model handle must be freed after diagnostics run", bridge.freedHandles.contains(2002L))
    }

    @Test
    fun diagnosticRunner_handlesMissingFileGracefullyWithoutLeaking() = runBlocking {
        val context = createFakeContext()
        val bridge = TestNativeBridge()

        val nonExistentShard = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "missing.gguf",
            source = ArtifactSource.AppManaged("/path/does/not/exist/missing.gguf"),
            sizeBytes = 1000L
        )
        val brokenBundle = ModelBundle(
            id = "broken_bundle",
            displayName = "Broken Model",
            mainArtifacts = listOf(nonExistentShard)
        )

        val report = GgufDiagnosticRunner.runDiagnostics(
            context = context,
            bundle = brokenBundle,
            customNativeBridge = bridge
        )

        assertNotNull(report)
        assertFalse("Diagnostics must report failure on missing file", report.overallSuccess)
        assertTrue(
            "Report must contain actionable init or inspection error",
            report.initSuccess == false || report.inspectionValid == false
        )
        assertEquals("Bridge init should not have succeeded", 0, bridge.initBundleCallCount.get())
    }
}

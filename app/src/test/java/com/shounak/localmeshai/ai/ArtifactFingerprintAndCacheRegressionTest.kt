package com.shounak.localmeshai.ai

import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class ArtifactFingerprintAndCacheRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeTestContext(private val prefs: android.content.SharedPreferences) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = prefs
        override fun getApplicationContext(): android.content.Context = this
        override fun getPackageName(): String = "com.shounak.localmeshai"
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

    @Test
    fun oldUriOnlyReceiptsAreInvalidatedInValidationReceipt() {
        // Receipt with old local_gguf_ prefix
        val oldLocalJson = JSONObject().apply {
            put("protocol", GgufGpuValidator.PROTOCOL_VERSION)
            put("model_sha", "local_gguf_7f8a9b")
            put("req_threads", 4)
            put("act_threads", 4)
            put("req_ctx", 2048)
            put("req_batch", 256)
            put("req_ubatch", 128)
            put("act_ctx", 2048)
            put("act_batch", 256)
            put("act_ubatch", 128)
            put("status", "PASS")
        }.toString()
        assertNull("local_gguf_ receipts must be invalidated", GgufGpuValidator.ValidationReceipt.fromJson(oldLocalJson))

        // Receipt with content: URI
        val contentUriJson = JSONObject(oldLocalJson).apply {
            put("model_sha", "content://media/external/file/12345")
        }.toString()
        assertNull("content: URI receipts must be invalidated", GgufGpuValidator.ValidationReceipt.fromJson(contentUriJson))

        // Receipt with file: URI
        val fileUriJson = JSONObject(oldLocalJson).apply {
            put("model_sha", "file:///data/local/tmp/model.gguf")
        }.toString()
        assertNull("file: URI receipts must be invalidated", GgufGpuValidator.ValidationReceipt.fromJson(fileUriJson))

        // Legitimate standard sha256 receipt
        val legitimateSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val validJson = JSONObject(oldLocalJson).apply {
            put("model_sha", legitimateSha)
        }.toString()
        val restored = GgufGpuValidator.ValidationReceipt.fromJson(validJson)
        assertNotNull("Valid SHA receipt must be restored", restored)
        assertEquals(legitimateSha, restored?.modelSha256)
    }

    @Test
    fun oldUriOnlyReceiptsAreInvalidatedInGgufPerformanceReceipt() {
        val baseObj = JSONObject().apply {
            put("native_build_id", com.shounak.localmeshai.BuildConfig.NATIVE_BUILD_ID)
            put("build_type", com.shounak.localmeshai.BuildConfig.BUILD_TYPE)
            put("backend_policy_version", 2)
            put("config_key", "sample_key")
        }

        // Receipt with local_gguf_ prefix
        val localGgufObj = JSONObject(baseObj.toString()).apply {
            put("model_sha", "local_gguf_123456")
        }
        assertNull(GgufPerformanceReceipt.fromJson(localGgufObj.toString()))

        // Receipt with content: URI
        val contentUriObj = JSONObject(baseObj.toString()).apply {
            put("model_sha", "content://media/external/file/99")
        }
        assertNull(GgufPerformanceReceipt.fromJson(contentUriObj.toString()))

        // Receipt with valid sha256
        val validSha = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"
        val validObj = JSONObject(baseObj.toString()).apply {
            put("model_sha", validSha)
        }
        val validReceipt = GgufPerformanceReceipt.fromJson(validObj.toString())
        assertNotNull(validReceipt)
        assertEquals(validSha, validReceipt?.modelSha256)
        // The old timing policy used token gaps as display-frame measurements.
        assertNull(GgufPerformanceReceipt.fromJson(JSONObject(validObj.toString()).put("backend_policy_version", 1).toString()))
    }

    @Test
    fun companionSummaryIsPreservedInCacheKeys() {
        val modelSha = "abc123def456"
        val keyNoProjector = GgufPerformanceStore.buildCacheKey(
            modelSha256 = modelSha,
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            companionSummary = "1_parts_none"
        )
        val keyWithProjector = GgufPerformanceStore.buildCacheKey(
            modelSha256 = modelSha,
            threads = 4,
            contextWindow = 2048,
            nBatch = 256,
            nUbatch = 128,
            companionSummary = "1_parts_mmproj-f16.gguf"
        )

        assertNotEquals(
            "Cache key must differentiate when companion / projector changes",
            keyNoProjector,
            keyWithProjector
        )
        assertTrue(keyWithProjector.contains("mmproj-f16.gguf"))
    }

    @Test
    fun projectorOnlyReplacementChangesCompositeFingerprint() {
        val context = createFakeContext()
        val mainFile = tempFolder.newFile("main_model.gguf").apply { writeBytes(ByteArray(1024) { 0x11 }) }
        val projFile1 = tempFolder.newFile("mmproj_v1.gguf").apply { writeBytes(ByteArray(512) { 0x22 }) }
        val projFile2 = tempFolder.newFile("mmproj_v2.gguf").apply { writeBytes(ByteArray(512) { 0x33 }) }

        val mainArtifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "main_model.gguf",
            source = ArtifactSource.AppManaged(mainFile.absolutePath),
            sizeBytes = mainFile.length(),
            lastModified = mainFile.lastModified()
        )
        val projArtifact1 = ModelArtifact(
            role = ArtifactRole.Projector,
            displayName = "mmproj_v1.gguf",
            source = ArtifactSource.AppManaged(projFile1.absolutePath),
            sizeBytes = projFile1.length(),
            lastModified = projFile1.lastModified()
        )
        val projArtifact2 = ModelArtifact(
            role = ArtifactRole.Projector,
            displayName = "mmproj_v2.gguf",
            source = ArtifactSource.AppManaged(projFile2.absolutePath),
            sizeBytes = projFile2.length(),
            lastModified = projFile2.lastModified()
        )

        val bundle1 = ModelBundle(
            id = "vision_bundle_stable_id",
            displayName = "Vision Model",
            mainArtifacts = listOf(mainArtifact),
            projectorArtifact = projArtifact1,
            capabilities = ModelCapabilities(supportsVision = true)
        )
        val bundle2 = ModelBundle(
            id = "vision_bundle_stable_id", // Stable model identity preserved!
            displayName = "Vision Model",
            mainArtifacts = listOf(mainArtifact),
            projectorArtifact = projArtifact2, // Projector replaced
            capabilities = ModelCapabilities(supportsVision = true)
        )

        assertEquals("Model identity must remain stable", bundle1.id, bundle2.id)

        val fp1 = GgufGpuValidator.resolveArtifactFingerprint(context, bundle1)
        val fp2 = GgufGpuValidator.resolveArtifactFingerprint(context, bundle2)

        assertNotEquals(
            "Replacing projector must change the composite artifact fingerprint even though main file is identical",
            fp1,
            fp2
        )
    }

    @Test
    fun replacementAtSamePathChangesFingerprint() {
        val context = createFakeContext()
        val file = tempFolder.newFile("replaceable.gguf").apply { writeBytes(ByteArray(1024) { 0x44 }) }
        val artifact1 = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "replaceable.gguf",
            source = ArtifactSource.AppManaged(file.absolutePath),
            sizeBytes = file.length(),
            lastModified = file.lastModified()
        )
        val bundle1 = ModelBundle(
            id = "replaceable_model",
            displayName = "Replaceable Model",
            mainArtifacts = listOf(artifact1)
        )

        val fp1 = GgufGpuValidator.resolveArtifactFingerprint(context, bundle1)

        // Modify file content and size at the same path
        file.writeBytes(ByteArray(2048) { 0x55 })
        val artifact2 = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "replaceable.gguf",
            source = ArtifactSource.AppManaged(file.absolutePath),
            sizeBytes = file.length(),
            lastModified = file.lastModified()
        )
        val bundle2 = ModelBundle(
            id = "replaceable_model",
            displayName = "Replaceable Model",
            mainArtifacts = listOf(artifact2)
        )

        val fp2 = GgufGpuValidator.resolveArtifactFingerprint(context, bundle2)

        assertNotEquals(
            "Changing file content at same path must result in new fingerprint",
            fp1,
            fp2
        )
    }
}

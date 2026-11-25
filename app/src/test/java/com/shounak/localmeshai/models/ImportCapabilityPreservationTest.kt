package com.shounak.localmeshai.models

import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.StagedArtifactItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportCapabilityPreservationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeDir: File
    private lateinit var storageRoot: File
    private lateinit var artifactStorage: ModelArtifactStorage

    @Before
    fun setup() {
        storeDir = tempFolder.newFolder("store_dir")
        storageRoot = tempFolder.newFolder("models_root")
        artifactStorage = ModelArtifactStorage(storageRoot)
    }

    @Test
    fun phoneImport_toggleableThinkingTextModel_preservesCapabilitiesAcrossReopen() {
        val store1 = CustomModelStore(storeDir)
        val bundleId = "phone_import_toggleable_text"
        val artifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "qwen-2.5-7b-instruct.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/101"),
            sizeBytes = 4_000_000_000L,
            lastModified = 1700000000000L
        )
        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = false,
            supportsAudio = false,
            chatTemplate = "{% if enable_thinking %}<think>{% endif %}",
            contextLength = 32768
        )
        val bundle = ModelBundle(
            id = bundleId,
            displayName = "Qwen 2.5 7B Instruct",
            mainArtifacts = listOf(artifact),
            capabilities = capabilities,
            contextLength = 32768
        )
        val definition = CustomModelDefinition(
            id = bundleId,
            name = "Qwen 2.5 7B Instruct",
            type = ModelType.Text,
            fileName = "qwen-2.5-7b-instruct.gguf",
            bundle = bundle
        )

        assertTrue("Store add must succeed", store1.add(definition))

        // Simulate app restart / reopening store
        val store2 = CustomModelStore(storeDir)
        val restored = store2.getById(bundleId)
        assertNotNull("Definition must be restored", restored)
        val restoredBundle = restored?.bundle
        assertNotNull("Bundle must be restored", restoredBundle)

        assertEquals("{% if enable_thinking %}<think>{% endif %}", restoredBundle?.capabilities?.chatTemplate)
        assertEquals(32768, restoredBundle?.capabilities?.contextLength)
        assertEquals(32768, restoredBundle?.contextLength)
        assertTrue(restoredBundle?.capabilities?.supportsText == true)
        assertFalse(restoredBundle?.capabilities?.supportsVision == true)

        // Check ModelInfo effective context window tokens
        val modelInfo = ModelInfo(
            id = bundleId,
            name = restored?.name.orEmpty(),
            size = "4.0 GB",
            status = ModelStatus.Available,
            type = ModelType.Text,
            fileName = restored?.fileName.orEmpty(),
            description = "",
            backend = "llama.cpp GGUF",
            deviceTarget = "All devices",
            bundle = restoredBundle
        )
        assertEquals(32768, modelInfo.effectiveContextWindowTokens)
    }

    @Test
    fun phoneImport_modelControlledThinkingTextModel_preservesCapabilitiesAcrossReopen() {
        val store1 = CustomModelStore(storeDir)
        val bundleId = "phone_import_model_controlled"
        val artifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "deepseek-r1-distill.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/102"),
            sizeBytes = 5_000_000_000L
        )
        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = false,
            chatTemplate = "<think>",
            contextLength = 16384
        )
        val bundle = ModelBundle(
            id = bundleId,
            displayName = "DeepSeek R1 Distill",
            mainArtifacts = listOf(artifact),
            capabilities = capabilities,
            contextLength = 16384
        )
        val definition = CustomModelDefinition(
            id = bundleId,
            name = "DeepSeek R1 Distill",
            type = ModelType.Text,
            fileName = "deepseek-r1-distill.gguf",
            bundle = bundle
        )

        assertTrue(store1.add(definition))

        val store2 = CustomModelStore(storeDir)
        val restored = store2.getById(bundleId)?.bundle
        assertNotNull(restored)
        assertEquals("<think>", restored?.capabilities?.chatTemplate)
        assertEquals(16384, restored?.contextLength)
        assertEquals(16384, restored?.capabilities?.contextLength)
    }

    @Test
    fun phoneImport_visionBundleWithProjector_preservesVisionAndUnsupportedThinking() {
        val store1 = CustomModelStore(storeDir)
        val bundleId = "phone_import_vision_bundle"
        val mainArtifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "llava-v1.6-vicuna-7b.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/201")
        )
        val projArtifact = ModelArtifact(
            role = ArtifactRole.Projector,
            displayName = "mmproj-model-f16.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/202")
        )
        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = true,
            supportsAudio = false,
            chatTemplate = "plain chat",
            contextLength = 4096
        )
        val bundle = ModelBundle(
            id = bundleId,
            displayName = "LLaVA v1.6 7B",
            mainArtifacts = listOf(mainArtifact),
            projectorArtifact = projArtifact,
            capabilities = capabilities,
            contextLength = 4096
        )
        val definition = CustomModelDefinition(
            id = bundleId,
            name = "LLaVA v1.6 7B",
            type = ModelType.Vision,
            fileName = "llava-v1.6-vicuna-7b.gguf",
            bundle = bundle
        )

        assertTrue(store1.add(definition))

        val store2 = CustomModelStore(storeDir)
        val restored = store2.getById(bundleId)?.bundle
        assertNotNull(restored)
        assertTrue("Restored bundle must support vision", restored?.capabilities?.supportsVision == true)
        assertTrue("Restored bundle isMultimodal must be true", restored?.isMultimodal == true)
        assertNotNull("Projector artifact must be restored", restored?.projectorArtifact)
        assertEquals("mmproj-model-f16.gguf", restored?.projectorArtifact?.displayName)
        assertEquals("plain chat", restored?.capabilities?.chatTemplate)
        assertEquals(4096, restored?.contextLength)
    }

    @Test
    fun phoneImport_visionBundleWithToggleableThinking_preservesBothCapabilities() {
        val store1 = CustomModelStore(storeDir)
        val bundleId = "phone_import_vision_thinking"
        val mainArtifact = ModelArtifact(
            role = ArtifactRole.Main,
            displayName = "qwen2-vl-7b-instruct.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/301")
        )
        val projArtifact = ModelArtifact(
            role = ArtifactRole.Projector,
            displayName = "mmproj-qwen2-vl-7b.gguf",
            source = ArtifactSource.PersistedUri("content://media/external/file/302")
        )
        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = true,
            chatTemplate = "<|thought|>",
            contextLength = 8192
        )
        val bundle = ModelBundle(
            id = bundleId,
            displayName = "Qwen2-VL 7B",
            mainArtifacts = listOf(mainArtifact),
            projectorArtifact = projArtifact,
            capabilities = capabilities,
            contextLength = 8192
        )
        val definition = CustomModelDefinition(
            id = bundleId,
            name = "Qwen2-VL 7B",
            type = ModelType.Vision,
            fileName = "qwen2-vl-7b-instruct.gguf",
            bundle = bundle
        )

        assertTrue(store1.add(definition))

        val store2 = CustomModelStore(storeDir)
        val restored = store2.getById(bundleId)?.bundle
        assertNotNull(restored)
        assertTrue(restored?.capabilities?.supportsVision == true)
        assertEquals("<|thought|>", restored?.capabilities?.chatTemplate)
        assertEquals(8192, restored?.contextLength)
    }

    @Test
    fun huggingFacePublicationProvenance_pointsToPublishedMainArtifactNotStaging() {
        val modelId = "hf_published_model"
        val stagingDir = artifactStorage.getStagingDir(modelId)
        stagingDir.mkdirs()

        val stagedFile = File(stagingDir, "model.gguf")
        stagedFile.writeBytes(ByteArray(4096) { 0x47 }) // dummy bytes

        val initialStagingIdentity = stagedFile.absolutePath

        val stagedItem = StagedArtifactItem(
            stagedFile = stagedFile,
            relativeTargetName = "model.gguf",
            role = ArtifactRole.Main,
            order = 0,
            isPrimary = true
        )

        val stagingCapabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = false,
            chatTemplate = "{% if enable_thinking %}<think>{% endif %}",
            reasoningChannels = setOf("thought"),
            contextLength = 4096
        )

        val publishedFile = artifactStorage.publishArtifactBundle(
            modelId = modelId,
            stagedItems = listOf(stagedItem),
            primaryItemName = "model.gguf",
            sourceIdentity = "https://huggingface.co/org/repo/resolve/main/model.gguf",
            capabilities = stagingCapabilities
        )

        assertTrue("Published file must exist", publishedFile.exists())
        assertFalse("Staging file must no longer exist after publication", stagedFile.exists())

        // Reopen published bundle from storage
        val publishedBundle = artifactStorage.getPublishedBundle(modelId)
        assertNotNull("Published bundle must be loadable", publishedBundle)
        assertEquals(publishedFile.absolutePath, publishedBundle!!.mainArtifacts.first().source.location)
        assertEquals("{% if enable_thinking %}<think>{% endif %}", publishedBundle.capabilities.chatTemplate)
        assertEquals(setOf("thought"), publishedBundle.capabilities.reasoningChannels)
        assertEquals(4096, publishedBundle.capabilities.contextLength)
    }
}

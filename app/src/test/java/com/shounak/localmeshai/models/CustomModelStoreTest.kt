package com.shounak.localmeshai.models

import com.shounak.localmeshai.utils.ModelArtifactStorage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomModelStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeDir: File
    private lateinit var modelsDir: File

    @Before
    fun setup() {
        storeDir = tempFolder.newFolder("store_dir")
        modelsDir = tempFolder.newFolder("models_dir")
    }

    @Test
    fun generateCustomModelId_isDeterministicAndUniquePerType() {
        val url = "https://huggingface.co/org/repo/resolve/main/model.gguf?download=true"
        val id1 = CustomModelStore.generateCustomModelId(url, ModelType.Text)
        val id2 = CustomModelStore.generateCustomModelId(url, ModelType.Text)
        val idVision = CustomModelStore.generateCustomModelId(url, ModelType.Vision)

        assertEquals(id1, id2)
        assertTrue(id1.startsWith("custom_text_"))
        assertTrue(idVision.startsWith("custom_vision_"))
        assertNotEquals(id1, idVision)
    }

    @Test
    fun customModelRestart_restoresDefinitions() {
        val store1 = CustomModelStore(storeDir)
        val url = "https://huggingface.co/org/repo/resolve/main/qwen.gguf?download=true"
        val id = CustomModelStore.generateCustomModelId(url, ModelType.Text)

        val definition = CustomModelDefinition(
            id = id,
            name = "Qwen Custom",
            url = url,
            type = ModelType.Text,
            fileName = "qwen.gguf",
            packageType = ModelPackage.SingleFile,
            sha256 = "abc123sha",
            description = "Custom Qwen model"
        )

        assertTrue(store1.add(definition))
        assertEquals(1, store1.getAll().size)

        // Simulate app restart with a fresh store instance
        val store2 = CustomModelStore(storeDir)
        assertEquals(1, store2.getAll().size)

        val restored = store2.getById(id)
        assertNotNull(restored)
        assertEquals("Qwen Custom", restored?.name)
        assertEquals(url, restored?.url)
        assertEquals(ModelType.Text, restored?.type)
        assertEquals("qwen.gguf", restored?.fileName)
        assertEquals("abc123sha", restored?.sha256)
    }

    @Test
    fun customPartialDownloadRestart_preservesDefinitionAndPartialBytes() {
        val store = CustomModelStore(storeDir)
        val artifactStorage = ModelArtifactStorage(modelsDir)

        val url = "https://huggingface.co/org/repo/resolve/main/custom.gguf?download=true"
        val id = CustomModelStore.generateCustomModelId(url, ModelType.Text)

        val definition = CustomModelDefinition(
            id = id,
            name = "Partial Test",
            url = url,
            type = ModelType.Text,
            fileName = "custom.gguf"
        )
        store.add(definition)

        // Simulate a partial download of 5 MB in staging
        val staging = artifactStorage.getStagingFile(id, "custom.gguf")
        staging.writeBytes(ByteArray(5 * 1024 * 1024))
        assertEquals(5L * 1024 * 1024, artifactStorage.getPartialDownloadBytes(id, "custom.gguf"))

        // Reopen both stores (restart)
        val reopenedStore = CustomModelStore(storeDir)
        val reopenedStorage = ModelArtifactStorage(modelsDir)

        assertNotNull(reopenedStore.getById(id))
        assertEquals(5L * 1024 * 1024, reopenedStorage.getPartialDownloadBytes(id, "custom.gguf"))
    }
}

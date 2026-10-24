package com.shounak.localmeshai.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableModelArchiveTest {
    @Test fun rootLevelBundlesUseArchiveDisplayNameInsteadOfDraftDirectory() {
        val staging = temporary.newFolder("draft_1234")
        assertEquals("Qwen-chat", PortableModelArchive.displayName(staging, staging, "Qwen-chat.zip"))
        assertEquals("Vision Model", PortableModelArchive.displayName(staging, staging, "Vision Model.ZIP"))
        assertEquals("Imported model", PortableModelArchive.displayName(staging, staging, ""))
        val modelFolder = java.io.File(staging, "whisper-tiny").apply { mkdirs() }
        assertEquals("whisper-tiny", PortableModelArchive.displayName(modelFolder, staging, "archive.zip"))
    }

    @get:Rule val temporary = TemporaryFolder()
    private fun archive(vararg names: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> names.forEachIndexed { index, name ->
            zip.putNextEntry(ZipEntry(name)); zip.write("component $index".toByteArray()); zip.closeEntry()
        } }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    @Test fun componentNamesAndDirectoriesSurviveExtraction() = runBlocking {
        val files = PortableModelArchive.extract(archive("model_index.json", "unet/model.onnx", "vae_decoder/model.onnx"), temporary.newFolder())
        assertEquals(3, files.size)
        assertEquals("component 1", files.getValue("unet/model.onnx").readText())
        assertEquals("component 2", files.getValue("vae_decoder/model.onnx").readText())
    }
    @Test fun metadataRootDoesNotDependOnZipEntryOrder() = runBlocking {
        val staging = temporary.newFolder()
        val files = PortableModelArchive.extract(archive("vision/config.json", "config.json", "model.safetensors"), staging)
        assertEquals(staging.canonicalFile, PortableModelArchive.metadataRoot(files)?.canonicalFile)
    }
    @Test fun separateModelRootsAreRejectedInsteadOfSelectingArbitraryWeights(): Unit = runBlocking {
        val files = PortableModelArchive.extract(archive("first/config.json", "first/model.onnx", "second/config.json", "second/model.onnx"), temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { PortableModelArchive.metadataRoot(files) }
    }
    @Test fun traversalAndCaseCollisionsAreRejected(): Unit = runBlocking {
        for (names in listOf(arrayOf("../outside"), arrayOf("unet/model.onnx", "UNET/model.onnx"), arrayOf("unet/%2e%2e/outside"))) {
            try { PortableModelArchive.extract(archive(*names), temporary.newFolder()); fail("Unsafe archive accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}

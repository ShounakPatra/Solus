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
    @Test fun traversalAndCaseCollisionsAreRejected(): Unit = runBlocking {
        for (names in listOf(arrayOf("../outside"), arrayOf("unet/model.onnx", "UNET/model.onnx"), arrayOf("unet/%2e%2e/outside"))) {
            try { PortableModelArchive.extract(archive(*names), temporary.newFolder()); fail("Unsafe archive accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}

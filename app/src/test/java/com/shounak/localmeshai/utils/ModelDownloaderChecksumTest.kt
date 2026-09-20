package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class ModelDownloaderChecksumTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun computeSha256CalculatesAccurateHash() {
        val testFile = tempFolder.newFile("test_sample.bin")
        FileOutputStream(testFile).use { fos ->
            fos.write("Hello Solus Local AI Checksum Verification".toByteArray(Charsets.UTF_8))
        }

        val actualSha = ModelDownloader.computeSha256(testFile)
        assertTrue(actualSha.isNotBlank())
        assertEquals(64, actualSha.length)

        assertTrue(ModelDownloader.verifyFileSha256(testFile, actualSha))
        assertTrue(ModelDownloader.verifyFileSha256(testFile, actualSha.uppercase()))
        assertFalse(ModelDownloader.verifyFileSha256(testFile, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
    }
}

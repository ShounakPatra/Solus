package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class FailableAtomicFileTransaction(
    override val file: File,
    private val delegate: AtomicFileTransaction = StandardAtomicFileTransaction(file)
) : AtomicFileTransaction {
    var failStartWrite: Boolean = false
    var failFinishWrite: Boolean = false
    var failOpenRead: Boolean = false

    override fun exists(): Boolean = delegate.exists()

    override fun openRead(): InputStream {
        if (failOpenRead) throw IOException("Simulated read failure")
        return delegate.openRead()
    }

    override fun startWrite(): OutputStream {
        if (failStartWrite) throw IOException("Simulated startWrite failure")
        return delegate.startWrite()
    }

    override fun finishWrite(stream: OutputStream) {
        if (failFinishWrite) {
            delegate.failWrite(stream)
            throw IOException("Simulated finishWrite failure")
        }
        delegate.finishWrite(stream)
    }

    override fun failWrite(stream: OutputStream) = delegate.failWrite(stream)

    override fun delete(): Boolean = delegate.delete()
}

class AtomicJsonFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File

    @Before
    fun setup() {
        testFile = File(tempFolder.root, "test_data.json")
    }

    @Test
    fun testRead_MissingFile_ReturnsMissing() {
        val atomicFile = AtomicJsonFile<List<String>>(testFile)
        val result = atomicFile.read { text -> listOf(text) }
        assertTrue(result is AtomicReadResult.Missing)
    }

    @Test
    fun testRead_BlankFileWithoutBackup_ReportsCorruptionAndPreservesFile() {
        for (content in listOf("", " \r\n\t")) {
            val blankFile = tempFolder.newFile().apply { writeText(content) }
            val result = AtomicJsonFile<String>(blankFile).read { error("Blank content must not be parsed") }
            assertTrue(result is AtomicReadResult.Corrupt)
            val corrupt = result as AtomicReadResult.Corrupt
            assertTrue(blankFile.exists())
            assertEquals(content, blankFile.readText())
            assertTrue(corrupt.backupFile?.exists() == true)
            assertEquals(content, corrupt.backupFile?.readText())
        }
    }

    @Test
    fun testRead_BlankPrimaryWithValidBackup_RecoversAndHealsPrimary() {
        testFile.writeText("")
        File(testFile.parentFile, "${testFile.name}.bak").writeText("recovered data")
        val result = AtomicJsonFile<String>(testFile).read { it }
        assertTrue(result is AtomicReadResult.Success)
        assertEquals("recovered data", (result as AtomicReadResult.Success).data)
        assertEquals("recovered data", testFile.readText())
    }

    @Test
    fun testRead_BlankPrimaryAndBackup_ReportCompositeCorruption() {
        testFile.writeText("")
        File(testFile.parentFile, "${testFile.name}.bak").writeText(" \n")
        val result = AtomicJsonFile<String>(testFile).read { error("Blank content must not be parsed") }
        assertTrue(result is AtomicReadResult.Corrupt)
        assertTrue((result as AtomicReadResult.Corrupt).cause is CompositeCorruptException)
        assertEquals("", testFile.readText())
    }

    @Test
    fun testWriteAndRead_Success() {
        val atomicFile = AtomicJsonFile<List<String>>(testFile)
        val data = listOf("item1", "item2")
        val writeResult = atomicFile.write(data) { list -> list.joinToString(",") }
        assertTrue(writeResult.isSuccess)

        val readResult = atomicFile.read { text -> text.split(",") }
        assertTrue(readResult is AtomicReadResult.Success)
        assertEquals(data, (readResult as AtomicReadResult.Success).data)
    }

    @Test
    fun testRead_CorruptData_PreservesCorruptFile() {
        testFile.writeText("{ corrupted not-valid json !!!")
        val atomicFile = AtomicJsonFile<List<String>>(testFile)

        val readResult = atomicFile.read { _ ->
            throw IllegalArgumentException("Malformed JSON")
        }

        assertTrue(readResult is AtomicReadResult.Corrupt)
        val corrupt = readResult as AtomicReadResult.Corrupt
        assertEquals(testFile, corrupt.file)
        assertTrue(testFile.exists())
        assertEquals("{ corrupted not-valid json !!!", testFile.readText())
        assertNotNull(corrupt.backupFile)
        assertTrue(corrupt.backupFile?.exists() == true)
        assertEquals("{ corrupted not-valid json !!!", corrupt.backupFile?.readText())
    }

    @Test
    fun testWriteFailure_PreservesPreviousFile() {
        val failableTx = FailableAtomicFileTransaction(testFile)
        val atomicFile = AtomicJsonFile<String>(testFile, failableTx)

        // Initial successful write
        val initialResult = atomicFile.write("original valid content") { it }
        assertTrue(initialResult.isSuccess)
        assertEquals("original valid content", testFile.readText())

        // Subsequent write fails at startWrite
        failableTx.failStartWrite = true
        val failedResult1 = atomicFile.write("new content that should fail") { it }
        assertTrue(failedResult1.isFailure)
        assertEquals("original valid content", testFile.readText())

        // Subsequent write fails at finishWrite (interrupted commit)
        failableTx.failStartWrite = false
        failableTx.failFinishWrite = true
        val failedResult2 = atomicFile.write("another content that should fail") { it }
        assertTrue(failedResult2.isFailure)
        assertEquals("original valid content", testFile.readText())

        // Re-read succeeds with original content
        val readResult = atomicFile.read { it }
        assertTrue(readResult is AtomicReadResult.Success)
        assertEquals("original valid content", (readResult as AtomicReadResult.Success).data)
    }

    @Test
    fun testRead_CorruptPrimaryWithGoodBackup_RecoversAndHealsPrimary() {
        val backupFile = File(testFile.parentFile ?: File("."), "${testFile.name}.bak")
        testFile.writeText("{ corrupted primary json [[[")
        backupFile.writeText("recovered,backup,data")

        val atomicFile = AtomicJsonFile<List<String>>(testFile)
        val readResult = atomicFile.read { text ->
            if (text.startsWith("{")) throw IllegalArgumentException("Bad JSON")
            text.split(",")
        }

        assertTrue("Should recover from backup", readResult is AtomicReadResult.Success)
        val data = (readResult as AtomicReadResult.Success).data
        assertEquals(listOf("recovered", "backup", "data"), data)

        // Verifies primary file was healed
        assertEquals("recovered,backup,data", testFile.readText())
    }

    @Test
    fun testRead_CorruptPrimaryAndCorruptBackup_ReportsCompositeFailure() {
        val backupFile = File(testFile.parentFile ?: File("."), "${testFile.name}.bak")
        testFile.writeText("{ corrupted primary json [[[")
        backupFile.writeText("{ corrupted backup json }}}")

        val atomicFile = AtomicJsonFile<List<String>>(testFile)
        val readResult = atomicFile.read { _ ->
            throw IllegalArgumentException("Malformed JSON")
        }

        assertTrue(readResult is AtomicReadResult.Corrupt)
        val corrupt = readResult as AtomicReadResult.Corrupt
        assertTrue(
            "Cause must be CompositeCorruptException",
            corrupt.cause is CompositeCorruptException
        )
        val composite = corrupt.cause as CompositeCorruptException
        assertTrue(composite.primaryCause is IllegalArgumentException)
        assertTrue(composite.backupCause is IllegalArgumentException)
        assertEquals(1, composite.suppressed.size)
    }

    @Test
    fun testRead_CorruptPrimaryWithMissingBackup_NormalCorruption() {
        val backupFile = File(testFile.parentFile ?: File("."), "${testFile.name}.bak")
        if (backupFile.exists()) backupFile.delete()
        testFile.writeText("{ corrupted primary json [[[")

        val atomicFile = AtomicJsonFile<List<String>>(testFile)
        val readResult = atomicFile.read { _ ->
            throw IllegalArgumentException("Malformed JSON")
        }

        assertTrue(readResult is AtomicReadResult.Corrupt)
        val corrupt = readResult as AtomicReadResult.Corrupt
        assertFalse(
            "Single corrupt without backup should not be CompositeCorruptException",
            corrupt.cause is CompositeCorruptException
        )
    }
}

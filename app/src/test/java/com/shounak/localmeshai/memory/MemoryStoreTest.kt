package com.shounak.localmeshai.memory

import com.shounak.localmeshai.utils.FailableAtomicFileTransaction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAddAndRetrieve_Success() {
        val store = PersistentMemoryStore(null)
        val entry = MemoryEntry(
            content = "User loves Kotlin coroutines",
            category = MemoryCategory.PREFERENCE,
            isEnabled = true
        )

        val added = store.add(entry)
        assertTrue(added)
        assertEquals(1, store.count())

        val retrieved = store.getById(entry.id)
        assertNotNull(retrieved)
        assertEquals("User loves Kotlin coroutines", retrieved?.content)
        assertEquals(MemoryCategory.PREFERENCE, retrieved?.category)
        assertTrue(retrieved?.isEnabled == true)
    }

    @Test
    fun testAddDuplicateContent_RejectsDuplicate() {
        val store = PersistentMemoryStore(null)
        val entry1 = MemoryEntry(content = "Same fact", category = MemoryCategory.FACT)
        val entry2 = MemoryEntry(content = "  same fact  ", category = MemoryCategory.GENERAL)

        assertTrue(store.add(entry1))
        assertFalse(store.add(entry2))
        assertEquals(1, store.count())
    }

    @Test
    fun testActiveFiltering_ReturnsOnlyEnabledEntries() {
        val store = PersistentMemoryStore(null)
        val e1 = MemoryEntry(content = "Active 1", isEnabled = true)
        val e2 = MemoryEntry(content = "Active 2", isEnabled = true)
        val e3 = MemoryEntry(content = "Disabled 1", isEnabled = false)

        store.add(e1)
        store.add(e2)
        store.add(e3)

        assertEquals(3, store.getAll().size)
        assertEquals(2, store.getActive().size)

        // Disable e1
        store.setEnabled(e1.id, false)
        assertEquals(1, store.getActive().size)

        // Re-enable e3
        store.setEnabled(e3.id, true)
        assertEquals(2, store.getActive().size)
    }

    @Test
    fun testUpdateEntry_ModifiesContent() {
        val store = PersistentMemoryStore(null)
        val entry = MemoryEntry(content = "Original text", category = MemoryCategory.FACT)
        store.add(entry)

        val updated = entry.copy(content = "Updated text", category = MemoryCategory.PREFERENCE)
        assertTrue(store.update(updated))

        val retrieved = store.getById(entry.id)
        assertEquals("Updated text", retrieved?.content)
        assertEquals(MemoryCategory.PREFERENCE, retrieved?.category)
    }

    @Test
    fun testDeleteEntry_RemovesFromStore() {
        val store = PersistentMemoryStore(null)
        val entry = MemoryEntry(content = "To be deleted")
        store.add(entry)
        assertEquals(1, store.count())

        assertTrue(store.delete(entry.id))
        assertEquals(0, store.count())
        assertNull(store.getById(entry.id))
    }

    @Test
    fun testClearAll_RemovesEverything() {
        val store = PersistentMemoryStore(null)
        store.add(MemoryEntry(content = "Fact 1"))
        store.add(MemoryEntry(content = "Fact 2"))
        store.add(MemoryEntry(content = "Fact 3"))
        assertEquals(3, store.count())

        store.clearAll()
        assertEquals(0, store.count())
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun testFilePersistence_SavesAndReloadsAcrossInstances() {
        val tempDir = tempFolder.newFolder("solus_mem_test")
        val store1 = PersistentMemoryStore(storageDir = tempDir, storageFileName = "test_memory.json")
        store1.add(MemoryEntry(content = "Persistent fact 1", category = MemoryCategory.FACT))
        store1.add(MemoryEntry(content = "Persistent rule 2", category = MemoryCategory.INSTRUCTION))

        // Create a second store pointing to the same file
        val store2 = PersistentMemoryStore(storageDir = tempDir, storageFileName = "test_memory.json")
        assertEquals(2, store2.count())

        val loaded = store2.getAll()
        assertTrue(loaded.any { it.content == "Persistent fact 1" && it.category == MemoryCategory.FACT })
        assertTrue(loaded.any { it.content == "Persistent rule 2" && it.category == MemoryCategory.INSTRUCTION })
    }

    @Test
    fun testFailedWrite_PreservesPreviousStateAndFile() {
        val testFile = File(tempFolder.root, "memory_failed_write.json")
        val failableTx = FailableAtomicFileTransaction(testFile)
        val store = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = failableTx
        )

        val entry1 = MemoryEntry(content = "Initial state", category = MemoryCategory.FACT)
        assertTrue(store.add(entry1))
        assertEquals(1, store.count())

        // Invalidate startWrite to simulate write failure
        failableTx.failStartWrite = true
        val entry2 = MemoryEntry(content = "Should fail", category = MemoryCategory.FACT)
        assertFalse(store.add(entry2))

        // In-memory state remains 1
        assertEquals(1, store.count())
        assertNotNull(store.getById(entry1.id))
        assertNull(store.getById(entry2.id))
        assertNotNull(store.lastError)

        // Reopening store reloads only entry1
        val reopened = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = FailableAtomicFileTransaction(testFile)
        )
        assertEquals(1, reopened.count())
        assertEquals("Initial state", reopened.getAll().first().content)
    }

    @Test
    fun testInterruptedCommit_PreservesPreviousStateAndFile() {
        val testFile = File(tempFolder.root, "memory_interrupted_commit.json")
        val failableTx = FailableAtomicFileTransaction(testFile)
        val store = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = failableTx
        )

        assertTrue(store.add(MemoryEntry(content = "Valid commit", category = MemoryCategory.FACT)))
        assertEquals(1, store.count())

        // Fail finishWrite to simulate interrupted commit
        failableTx.failFinishWrite = true
        assertFalse(store.add(MemoryEntry(content = "Interrupted commit", category = MemoryCategory.FACT)))
        assertEquals(1, store.count())

        // Disk content intact
        val reopened = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = FailableAtomicFileTransaction(testFile)
        )
        assertEquals(1, reopened.count())
        assertEquals("Valid commit", reopened.getAll().first().content)
    }

    @Test
    fun testCorruptRead_PreservesCorruptFile() {
        val testFile = File(tempFolder.root, "memory_corrupt.json")
        testFile.writeText("{ not-valid-json: [corrupt data")

        val store = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name
        )

        assertEquals(0, store.count())
        assertNotNull(store.lastError)
        // Corrupt file was preserved
        assertTrue(testFile.exists())
        assertEquals("{ not-valid-json: [corrupt data", testFile.readText())
    }

    @Test
    fun testConcurrentMutations_SerializedAndConsistent() {
        val testFile = File(tempFolder.root, "memory_concurrent.json")
        val store = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name
        )

        val threadCount = 10
        val itemsPerThread = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        store.add(MemoryEntry(content = "Thread $t item $i", category = MemoryCategory.FACT))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threadCount * itemsPerThread, store.count())

        // Reopen store to verify all mutations were reliably written to disk
        val reopened = PersistentMemoryStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name
        )
        assertEquals(threadCount * itemsPerThread, reopened.count())
    }
}

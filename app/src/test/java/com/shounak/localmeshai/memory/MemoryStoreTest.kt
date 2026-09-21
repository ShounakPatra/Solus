package com.shounak.localmeshai.memory

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MemoryStoreTest {

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
        val tempDir = File(System.getProperty("java.io.tmpdir"), "solus_mem_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val store1 = PersistentMemoryStore(storageDir = tempDir, storageFileName = "test_memory.json")
            store1.add(MemoryEntry(content = "Persistent fact 1", category = MemoryCategory.FACT))
            store1.add(MemoryEntry(content = "Persistent rule 2", category = MemoryCategory.INSTRUCTION))

            // Create a second store pointing to the same file
            val store2 = PersistentMemoryStore(storageDir = tempDir, storageFileName = "test_memory.json")
            assertEquals(2, store2.count())

            val loaded = store2.getAll()
            assertTrue(loaded.any { it.content == "Persistent fact 1" && it.category == MemoryCategory.FACT })
            assertTrue(loaded.any { it.content == "Persistent rule 2" && it.category == MemoryCategory.INSTRUCTION })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

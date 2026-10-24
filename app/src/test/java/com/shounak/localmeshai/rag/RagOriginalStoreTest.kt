package com.shounak.localmeshai.rag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RagOriginalStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainedOriginalSurvivesReopeningAndDuplicateNamesStayDistinct() {
        val root = temporary.newFolder()
        val store = RagOriginalStore(root)
        val first = store.save("first", "cover_page.docx", byteArrayOf(0x50, 0x4b, 1, 2).inputStream())
        val second = store.save("second", "cover_page.docx", byteArrayOf(3, 4, 5).inputStream())
        assertNotEquals(first, second)
        val reopened = RagOriginalStore(root)
        assertArrayEquals(byteArrayOf(0x50, 0x4b, 1, 2), reopened.find("first")!!.readBytes())
        assertEquals("cover_page.docx", reopened.find("first")!!.name)
        assertArrayEquals(byteArrayOf(3, 4, 5), reopened.find("second")!!.readBytes())
        reopened.remove("first")
        assertNull(reopened.find("first"))
        assertTrue(second.isFile)
    }

    @Test fun cancelledReplacementPreservesPreviousOriginalAndLeavesNoPartialPreview() {
        val root = temporary.newFolder()
        val store = RagOriginalStore(root)
        val previous = store.save("doc", "source.pdf", byteArrayOf(1, 2).inputStream())
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            store.save("doc", "replacement.pdf", ByteArray(20_000).inputStream()) {
                throw kotlinx.coroutines.CancellationException("Stop")
            }
        }
        assertEquals(previous, store.find("doc"))
        assertArrayEquals(byteArrayOf(1, 2), previous.readBytes())
        assertEquals(listOf(previous), root.walkTopDown().filter { it.isFile }.toList())
    }

    @Test fun uploadedNameCannotEscapeItsOwnedDirectory() {
        val root = temporary.newFolder()
        val store = RagOriginalStore(root)
        val saved = store.save("../document", "../../file.docx", byteArrayOf(1).inputStream())
        assertTrue(saved.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator))
        assertEquals("file.docx", saved.name)
    }
}

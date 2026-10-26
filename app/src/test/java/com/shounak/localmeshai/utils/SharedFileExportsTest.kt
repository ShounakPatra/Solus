package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedFileExportsTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun onlyTheSelectedFileIsCopiedIntoAnIsolatedExportFolder() {
        val privateRoot = files.newFolder("private")
        val selected = privateRoot.resolve("notes.txt").apply { writeText("Selected content") }
        privateRoot.resolve("secret.txt").writeText("Unrelated private data")
        val cache = files.newFolder("cache")
        val exported = SharedFileExports.stage(cache, selected)
        assertTrue(exported.canonicalPath.startsWith(cache.resolve("shared_exports").canonicalPath + java.io.File.separator))
        assertEquals("Selected content", exported.readText())
        assertTrue(selected.exists())
        assertEquals(listOf("notes.txt"), exported.parentFile!!.listFiles()!!.map { it.name })
        assertNotEquals(exported.parent, SharedFileExports.stage(cache, selected).parent)
        val now = System.currentTimeMillis()
        exported.setLastModified(now - AppCacheCleaner.INPUT_RETENTION_MS - 1000)
        val lease = ActiveInputFiles.protect(exported)
        try { SharedFileExports.prune(cache, now); assertTrue(exported.exists()) } finally { lease.close() }
        SharedFileExports.prune(cache, now)
        assertFalse(exported.exists())
        assertTrue(selected.exists())
    }
}

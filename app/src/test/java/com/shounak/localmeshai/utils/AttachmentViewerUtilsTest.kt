package com.shounak.localmeshai.utils

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentViewerUtilsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(cache: File) = object : ContextWrapper(null) {
        override fun getCacheDir() = cache
    }

    @Test fun cachedAttachmentNamesCannotEscapeTheInputsFolder() {
        val cache = temporary.newFolder("cache")
        cache.resolve("multimodal_inputs").mkdirs()
        val unrelated = cache.resolve("private.txt").apply { writeText("private data") }
        val ctx = context(cache)
        for (name in listOf("../private.txt", "..\\private.txt", unrelated.absolutePath, ".", "..", "bad\u0000name")) {
            assertNull(name, AttachmentViewerUtils.findAttachmentFile(ctx, name))
        }
    }

    @Test fun ordinaryCachedAttachmentsAndExplicitPathsStillOpen() {
        val cache = temporary.newFolder("cache")
        val inputs = cache.resolve("multimodal_inputs").apply { mkdirs() }
        val cached = inputs.resolve("notes.pdf").apply { writeText("document") }
        val owned = temporary.newFile("owned.docx")
        val ctx = context(cache)
        assertEquals(cached, AttachmentViewerUtils.findAttachmentFile(ctx, "notes.pdf"))
        assertEquals(owned, AttachmentViewerUtils.findAttachmentFile(ctx, "Display name.docx", owned.absolutePath))
        assertNull(AttachmentViewerUtils.findAttachmentFile(ctx, "missing.pdf"))
    }
}

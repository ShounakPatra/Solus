package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceResolutionTest {

    @Test
    fun testProductionSelectionWithPlusCharacterInUrl() {
        val files = listOf(
            RepoFile(path = "models/model+chat.gguf", sizeBytes = 1000L),
            RepoFile(path = "models/model chat.gguf", sizeBytes = 2000L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        // URL containing literal '+' in path must match model+chat.gguf, NOT model chat.gguf
        val input = "https://huggingface.co/owner/repo/blob/main/models/model+chat.gguf"
        val parsed = HuggingFaceModelResolver.parseInput(input)
        assertEquals("models/model+chat.gguf", parsed.path)

        val selection = HuggingFaceModelResolver.selectVariants(parsed, allVariants)
        assertNotNull("Preselected variant must not be null", selection.preselectedVariant)
        assertEquals("models/model+chat.gguf", selection.preselectedVariant?.mainFiles?.first()?.path)
        assertEquals(1000L, selection.preselectedVariant?.mainFiles?.first()?.sizeBytes)
    }

    @Test
    fun testProductionSelectionWithEncodedPlusInUrl() {
        val files = listOf(
            RepoFile(path = "models/model+chat.gguf", sizeBytes = 1000L),
            RepoFile(path = "models/model chat.gguf", sizeBytes = 2000L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        // URL containing '%2B' in path decodes once to '+' and matches model+chat.gguf without double-decoding to space
        val input = "https://huggingface.co/owner/repo/blob/main/models/model%2Bchat.gguf"
        val parsed = HuggingFaceModelResolver.parseInput(input)
        assertEquals("models/model+chat.gguf", parsed.path)

        val selection = HuggingFaceModelResolver.selectVariants(parsed, allVariants)
        assertNotNull(selection.preselectedVariant)
        assertEquals("models/model+chat.gguf", selection.preselectedVariant?.mainFiles?.first()?.path)
    }

    @Test
    fun testProductionSelectionWithPercentTwentyEncodingInUrl() {
        val files = listOf(
            RepoFile(path = "models/model%20chat.gguf", sizeBytes = 1500L),
            RepoFile(path = "models/model chat.gguf", sizeBytes = 2500L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        // URL containing '%2520' decodes to literal '%20' and must match model%20chat.gguf, NOT model chat.gguf
        val input = "https://huggingface.co/owner/repo/blob/main/models/model%2520chat.gguf"
        val parsed = HuggingFaceModelResolver.parseInput(input)
        assertEquals("models/model%20chat.gguf", parsed.path)

        val selection = HuggingFaceModelResolver.selectVariants(parsed, allVariants)
        assertNotNull(selection.preselectedVariant)
        assertEquals("models/model%20chat.gguf", selection.preselectedVariant?.mainFiles?.first()?.path)
        assertEquals(1500L, selection.preselectedVariant?.mainFiles?.first()?.sizeBytes)
    }

    @Test
    fun testProductionSelectionWithSpacesInUrl() {
        val files = listOf(
            RepoFile(path = "models/model chat.gguf", sizeBytes = 2500L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        val input = "https://huggingface.co/owner/repo/blob/main/models/model%20chat.gguf"
        val parsed = HuggingFaceModelResolver.parseInput(input)
        assertEquals("models/model chat.gguf", parsed.path)

        val selection = HuggingFaceModelResolver.selectVariants(parsed, allVariants)
        assertNotNull(selection.preselectedVariant)
        assertEquals("models/model chat.gguf", selection.preselectedVariant?.mainFiles?.first()?.path)
    }

    @Test
    fun testProductionSelectionDistinguishesIdenticalBasenamesInDifferentFolders() {
        val files = listOf(
            RepoFile(path = "folder-A/model.gguf", sizeBytes = 1000L),
            RepoFile(path = "folder-B/model.gguf", sizeBytes = 2000L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        val inputB = "https://huggingface.co/owner/repo/blob/main/folder-B/model.gguf"
        val parsedB = HuggingFaceModelResolver.parseInput(inputB)
        val selectionB = HuggingFaceModelResolver.selectVariants(parsedB, allVariants)
        assertNotNull(selectionB.preselectedVariant)
        assertEquals("folder-B/model.gguf", selectionB.preselectedVariant?.mainFiles?.first()?.path)
        assertEquals(2000L, selectionB.preselectedVariant?.mainFiles?.first()?.sizeBytes)

        val inputA = "https://huggingface.co/owner/repo/blob/main/folder-A/model.gguf"
        val parsedA = HuggingFaceModelResolver.parseInput(inputA)
        val selectionA = HuggingFaceModelResolver.selectVariants(parsedA, allVariants)
        assertNotNull(selectionA.preselectedVariant)
        assertEquals("folder-A/model.gguf", selectionA.preselectedVariant?.mainFiles?.first()?.path)
        assertEquals(1000L, selectionA.preselectedVariant?.mainFiles?.first()?.sizeBytes)
    }

    @Test
    fun testProductionSelectionThrowsOnMissingExactFile() {
        val files = listOf(
            RepoFile(path = "folder-A/model.gguf", sizeBytes = 1000L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        val inputMissing = "https://huggingface.co/owner/repo/blob/main/folder-B/model.gguf"
        val parsedMissing = HuggingFaceModelResolver.parseInput(inputMissing)

        assertThrows(NoSuchElementException::class.java) {
            HuggingFaceModelResolver.selectVariants(parsedMissing, allVariants)
        }
    }

    @Test
    fun testProductionSelectionSelectsCompleteVariantForExactShard() {
        val files = listOf(
            RepoFile(path = "sub/model-00001-of-00002.gguf", sizeBytes = 1000L),
            RepoFile(path = "sub/model-00002-of-00002.gguf", sizeBytes = 1000L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )
        assertEquals(1, allVariants.size)

        val inputShard = "https://huggingface.co/owner/repo/blob/main/sub/model-00002-of-00002.gguf"
        val parsedShard = HuggingFaceModelResolver.parseInput(inputShard)

        val selection = HuggingFaceModelResolver.selectVariants(parsedShard, allVariants)
        val matched = selection.preselectedVariant
        assertNotNull(matched)
        assertTrue("Must be identified as split", matched!!.isSplit)
        assertEquals("Must contain all 2 shards", 2, matched.mainFiles.size)
        assertEquals("sub/model-00001-of-00002.gguf", matched.mainFiles[0].path)
        assertEquals("sub/model-00002-of-00002.gguf", matched.mainFiles[1].path)
    }

    @Test
    fun testProductionSelectionRequiresExplicitSelectionForRepoAndFolder() {
        val files = listOf(
            RepoFile(path = "folder-A/model1.gguf", sizeBytes = 1000L),
            RepoFile(path = "folder-B/model2.gguf", sizeBytes = 2000L)
        )
        val allVariants = HuggingFaceModelResolver.groupVariants(
            ResolvedRepoInfo(repoId = "owner/repo", requestedRevision = "main", commitSha = "sha1", files = files)
        )

        // Repo home link requires explicit selection (preselected = null)
        val repoParsed = HuggingFaceModelResolver.parseInput("https://huggingface.co/owner/repo")
        val repoSelection = HuggingFaceModelResolver.selectVariants(repoParsed, allVariants)
        assertNull("Repository link must require explicit variant selection", repoSelection.preselectedVariant)
        assertEquals(2, repoSelection.variants.size)

        // Folder link requires explicit selection and filters to folder
        val folderParsed = HuggingFaceModelResolver.parseInput("https://huggingface.co/owner/repo/tree/main/folder-A")
        val folderSelection = HuggingFaceModelResolver.selectVariants(folderParsed, allVariants)
        assertNull("Folder link must require explicit variant selection", folderSelection.preselectedVariant)
        assertEquals(1, folderSelection.variants.size)
        assertEquals("folder-A/model1.gguf", folderSelection.variants.first().mainFiles.first().path)
    }
}

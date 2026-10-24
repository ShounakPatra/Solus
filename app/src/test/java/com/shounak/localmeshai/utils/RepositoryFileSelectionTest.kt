package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Test

class RepositoryFileSelectionTest {
    private fun repo(vararg paths: String) = ResolvedRepoInfo("owner/repo", "main", "abc123", paths.map { RepoFile(it, 100) })
    private fun plan(repo: ResolvedRepoInfo, vararg paths: String) = RepositoryFileSelection.plan(repo, HuggingFaceModelResolver.groupVariants(repo), paths.toSet())

    @Test fun listsEveryFormatAndDocumentWithoutRequiringARunnableVariant() {
        val repo = repo("README.md", ".gitattributes", "pytorch_model.bin", "model.pt", "audio.wav", "nested/custom.xyz", "tokenizer.model")
        assertEquals(repo.files.toSet(), RepositoryFileSelection.visibleFiles(repo, ParsedHfInput(repo.repoId)).toSet())
        val plan = plan(repo, "pytorch_model.bin", "README.md", "nested/custom.xyz")
        assertTrue(plan.models.isEmpty())
        assertEquals(3, plan.extraFiles.size)
        assertEquals(3, plan.downloadFiles.size)
    }

    @Test fun folderLinksShowAllDescendantFormatsWithoutPrefixSiblingLeaks() {
        val repo = repo("audio/model.onnx", "audio/vocab.txt", "audio/sub/extra.bin", "audio2/model.onnx", "config.json")
        val files = RepositoryFileSelection.visibleFiles(repo, ParsedHfInput(repo.repoId, path = "audio", kind = HfInputKind.FOLDER))
        assertEquals(setOf("audio/model.onnx", "audio/vocab.txt", "audio/sub/extra.bin"), files.map { it.path }.toSet())
    }

    @Test fun selectsMultipleIndependentModelFormatsAndKeepsDependenciesDespiteUnselection() {
        val repo = repo("model.onnx", "model.safetensors", "model.tflite", "small.gguf", "model.task", "model.litertlm", "config.json", "tokenizer.json", "README.md")
        val plan = plan(repo, "model.onnx", "model.safetensors", "model.tflite", "small.gguf", "model.task", "model.litertlm", "README.md")
        assertEquals(6, plan.models.size)
        assertEquals(setOf("config.json", "tokenizer.json"), plan.automaticPaths)
        assertEquals(listOf("README.md"), plan.extraFiles.map { it.path })
        assertEquals(repo.files.size, plan.downloadFiles.size)
        val explicit = plan(repo, *repo.files.map { it.path }.toTypedArray())
        assertTrue(explicit.automaticPaths.isEmpty())
        assertEquals(plan.downloadFiles.toSet(), explicit.downloadFiles.toSet())
    }

    @Test fun selectingOneSplitGgufIncludesAllShardsAndMatchingProjector() {
        val repo = repo("vision-00001-of-00002.gguf", "vision-00002-of-00002.gguf", "mmproj-vision.gguf")
        val plan = plan(repo, "vision-00002-of-00002.gguf")
        assertEquals(1, plan.models.size)
        assertEquals("mmproj-vision.gguf", plan.models.single().second?.path)
        assertEquals(setOf("vision-00001-of-00002.gguf", "mmproj-vision.gguf"), plan.automaticPaths)
    }

    @Test fun selectingOneSafetensorsShardIncludesIndexAndTokenizer() {
        val repo = repo("model-00001-of-00002.safetensors", "model-00002-of-00002.safetensors", "model.safetensors.index.json", "config.json", "tokenizer.json")
        val plan = plan(repo, "model-00002-of-00002.safetensors")
        assertEquals(1, plan.models.size)
        assertEquals(5, plan.downloadFiles.size)
        assertEquals(4, plan.automaticPaths.size)
    }

    @Test fun pairedAudioOnnxGraphsAreOneBundleAndExternalDataIsIncluded() {
        val repo = repo("encoder_model.onnx", "decoder_model.onnx", "encoder_model.onnx_data", "config.json", "tokenizer.json", "preprocessor_config.json")
        val plan = plan(repo, "encoder_model.onnx", "decoder_model.onnx")
        assertEquals(1, plan.models.size)
        assertEquals(6, plan.downloadFiles.size)
        assertTrue("encoder_model.onnx_data" in plan.automaticPaths)
    }

    @Test fun selectingADiffusionComponentIncludesCompletePipelineOnlyOnce() {
        val repo = repo("model_index.json", "text_encoder/model.safetensors", "text_encoder/config.json", "unet/diffusion_pytorch_model.safetensors", "unet/config.json", "vae/diffusion_pytorch_model.safetensors", "vae/config.json", "scheduler/scheduler_config.json", "tokenizer/tokenizer.json", "tokenizer/vocab.json", "tokenizer/merges.txt")
        val plan = plan(repo, "text_encoder/model.safetensors", "vae/diffusion_pytorch_model.safetensors")
        assertEquals(1, plan.models.size)
        assertTrue(plan.models.single().first.name.startsWith("Complete diffusion"))
        assertEquals(repo.files.size, plan.downloadFiles.size)
        assertTrue("unet/diffusion_pytorch_model.safetensors" in plan.automaticPaths)
        assertTrue(plan.extraFiles.isEmpty())
    }

    @Test fun ancestorsProvideRequiredMetadataOutsideBrowsedFolder() {
        val repo = repo("onnx/model.onnx", "config.json", "tokenizer.json", "other/config.json")
        val plan = plan(repo, "onnx/model.onnx")
        assertEquals(setOf("config.json", "tokenizer.json"), plan.automaticPaths)
        assertFalse(plan.downloadFiles.any { it.path == "other/config.json" })
    }

    @Test fun unrelatedOnnxExternalWeightsAreNotPulledByAnotherQuantization() {
        val repo = repo("model.onnx", "model.onnx_data", "model_q4.onnx", "model_q4.onnx_data", "config.json", "tokenizer.json")
        val selected = plan(repo, "model_q4.onnx")
        assertTrue("model_q4.onnx_data" in selected.automaticPaths)
        assertFalse(selected.downloadFiles.any { it.path == "model.onnx_data" })
    }

    @Test fun clearingSelectionsDoesNotLeaveAutomaticDownloadsBehind() {
        val repo = repo("model.safetensors", "config.json", "tokenizer.json")
        assertTrue(plan(repo).downloadFiles.isEmpty())
        assertTrue(plan(repo, "config.json").models.isEmpty())
        assertEquals(1, plan(repo, "config.json").extraFiles.size)
    }

    @Test fun rejectsSelectionsFromAnotherRevision() {
        assertThrows(IllegalArgumentException::class.java) { plan(repo("model.gguf"), "missing.gguf") }
    }
}

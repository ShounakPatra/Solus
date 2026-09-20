package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomModelUrlTest {
    @Test
    fun normalizeHuggingFaceBlobUrl() {
        val blobUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        val normalized = MainViewModel.normalizeCustomModelUrl(blobUrl)

        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
            normalized
        )
    }

    @Test
    fun normalizeHuggingFaceShorthandRepoAndFile() {
        val shorthand = "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"
        val normalized = MainViewModel.normalizeCustomModelUrl(shorthand)

        assertEquals(
            "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf?download=true",
            normalized
        )
    }

    @Test
    fun normalizeDirectDownloadUrlPreservesHttps() {
        val directUrl = "https://example.com/models/custom-model.litertlm"
        val normalized = MainViewModel.normalizeCustomModelUrl(directUrl)

        assertEquals(directUrl, normalized)
    }
}

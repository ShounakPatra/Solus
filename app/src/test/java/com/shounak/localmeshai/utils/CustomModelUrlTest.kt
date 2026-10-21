package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.ui.viewmodels.ModelInputClassification
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CustomModelUrlTest {

    private lateinit var server1: MockWebServer
    private lateinit var server2: MockWebServer

    @Before
    fun setup() {
        server1 = MockWebServer()
        server2 = MockWebServer()
    }

    @After
    fun tearDown() {
        server1.shutdown()
        server2.shutdown()
    }

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

    @Test
    fun normalizeUppercaseHuggingFaceHostname() {
        val upperUrl = "https://HUGGINGFACE.CO/org/repo/blob/main/model.gguf"
        val normalized = MainViewModel.normalizeCustomModelUrl(upperUrl)

        assertEquals(
            "https://huggingface.co/org/repo/resolve/main/model.gguf?download=true",
            normalized
        )
    }

    @Test
    fun lookalikeDomainsDoNotRewriteBlobOrAddDownload() {
        val evilUrl = "https://evil-huggingface.co/org/repo/blob/main/model.gguf"
        val normalized = MainViewModel.normalizeCustomModelUrl(evilUrl)

        assertEquals(evilUrl, normalized)
        assertFalse(ModelDownloadUrlPolicy.isTokenEligible(evilUrl))
    }

    @Test
    fun queryContainingHuggingFaceDoesNotTriggerNormalization() {
        val searchUrl = "https://example.com/search?q=huggingface.co/blob/main"
        val normalized = MainViewModel.normalizeCustomModelUrl(searchUrl)

        assertEquals(searchUrl, normalized)
        assertFalse(ModelDownloadUrlPolicy.isTokenEligible(searchUrl))
    }

    @Test
    fun userInfoUrlStripsCredentials() {
        val userInfoUrl = "https://user:secret@huggingface.co/org/repo/resolve/main/model.gguf"
        val normalized = MainViewModel.normalizeCustomModelUrl(userInfoUrl)

        assertEquals(
            "https://huggingface.co/org/repo/resolve/main/model.gguf?download=true",
            normalized
        )
    }

    @Test
    fun tokenEligibilityAndRequirementsClassification() {
        assertTrue(ModelDownloadUrlPolicy.isTokenEligible("https://huggingface.co/org/repo/model.gguf"))
        assertTrue(ModelDownloadUrlPolicy.isTokenEligible("https://HUGGINGFACE.CO/org/repo/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.isTokenEligible("http://huggingface.co/org/repo/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.isTokenEligible("https://evil-huggingface.co/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.isTokenEligible("https://huggingface.co.evil.com/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.isTokenEligible("https://example.com/?query=huggingface.co"))

        // Origin check alone does not mandate authentication; unknown requirements permit anonymous attempts
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken("https://huggingface.co/org/repo/model.gguf"))
    }

    @Test
    fun tokenNeverSentToNonHuggingFaceOrigin() {
        server1.start()
        server1.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val dummyToken = "hf_dummy_token_12345"
        val client = ModelDownloadUrlPolicy.configureClient(OkHttpClient.Builder()) { dummyToken }.build()

        val request = Request.Builder()
            .url(server1.url("/download/model.gguf"))
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val recorded = server1.takeRequest()
        assertNull("Authorization must not be attached to non-HF origins", recorded.getHeader("Authorization"))
    }

    @Test
    fun crossOriginRedirectStripsAuthorization() {
        server1.start()
        server2.start()

        // server1 returns 302 redirecting to server2
        server1.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server2.url("/cdn/model.gguf").toString())
        )
        server2.enqueue(MockResponse().setResponseCode(200).setBody("cdn-content"))

        val dummyToken = "hf_dummy_secret"
        val client = ModelDownloadUrlPolicy.configureClient(OkHttpClient.Builder()) { dummyToken }.build()

        // Pretend initial request was sent with an Authorization header
        val initialRequest = Request.Builder()
            .url(server1.url("/resolve/main/model.gguf"))
            .header("Authorization", "Bearer $dummyToken")
            .build()

        val response = client.newCall(initialRequest).execute()
        assertEquals(200, response.code)

        val req1 = server1.takeRequest()
        val req2 = server2.takeRequest()

        assertNull("Authorization must be removed on cross-origin redirect", req2.getHeader("Authorization"))
    }

    @Test(expected = IOException::class)
    fun downgradeRedirectIsRejected() {
        server1.start()

        // server1 redirects to an insecure HTTP url
        server1.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://insecure-host.example.com/file.gguf")
        )

        val client = ModelDownloadUrlPolicy.configureClient(OkHttpClient.Builder()) { "dummy" }.build()

        // Request with HTTPS-like client call: simulate redirect
        val request = Request.Builder()
            .url(server1.url("/file.gguf").newBuilder().scheme("https").build())
            .build()

        client.newCall(request).execute()
    }

    @Test
    fun publicHuggingFaceUrlIsTokenEligibleWithoutRequiringToken() {
        val publicUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"
        // Eligible for token attachment if the user supplies one
        assertTrue(ModelDownloadUrlPolicy.isTokenEligible(publicUrl))
        // Does NOT require authentication; anonymous attempt is permitted
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken(publicUrl))
    }

    @Test
    fun gatedCatalogHuggingFaceModelRequiresToken() {
        val gatedGemmaUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
        assertTrue(ModelDownloadUrlPolicy.isTokenEligible(gatedGemmaUrl))
        assertTrue(ModelDownloadUrlPolicy.requiresHuggingFaceToken(gatedGemmaUrl))
    }

    @Test
    fun externalGgufLinksReachDirectDownloadingClassification() {
        val externalGguf = "https://example.com/models/deepseek-r1-distill-qwen-1.5b.gguf"
        val classification = MainViewModel.classifyModelInput(externalGguf)
        assertEquals(ModelInputClassification.DirectDownload, classification)
    }

    @Test
    fun externalTfliteAndLitertlmLinksReachDirectDownloadingClassification() {
        val externalTflite = "https://example.com/models/vision_model.tflite"
        assertEquals(ModelInputClassification.DirectDownload, MainViewModel.classifyModelInput(externalTflite))

        val externalTask = "https://storage.googleapis.com/mediapipe/gemma2-2b-it-gpu.task"
        assertEquals(ModelInputClassification.DirectDownload, MainViewModel.classifyModelInput(externalTask))

        val externalLitertlm = "https://example.com/models/model.litertlm"
        assertEquals(ModelInputClassification.DirectDownload, MainViewModel.classifyModelInput(externalLitertlm))

        val externalZip = "https://example.com/models/bundle.zip"
        assertEquals(ModelInputClassification.DirectDownload, MainViewModel.classifyModelInput(externalZip))
    }

    @Test
    fun huggingFaceLinksReachHuggingFaceClassification() {
        val hfFileUrl = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/blob/main/gemma-3-4b-it-Q4_K_M.gguf"
        assertEquals(ModelInputClassification.HuggingFace, MainViewModel.classifyModelInput(hfFileUrl))

        val hfRepoUrl = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF"
        assertEquals(ModelInputClassification.HuggingFace, MainViewModel.classifyModelInput(hfRepoUrl))

        val hfShorthand = "unsloth/gemma-3-4b-it-GGUF"
        assertEquals(ModelInputClassification.HuggingFace, MainViewModel.classifyModelInput(hfShorthand))
    }

    @Test
    fun invalidLinksAreRejected() {
        assertEquals(ModelInputClassification.Invalid, MainViewModel.classifyModelInput(""))
        assertEquals(ModelInputClassification.Invalid, MainViewModel.classifyModelInput("https://example.com/page.html"))
        assertEquals(ModelInputClassification.Invalid, MainViewModel.classifyModelInput("https://example.com/software.exe"))
        assertEquals(ModelInputClassification.Invalid, MainViewModel.classifyModelInput("just_random_string"))
    }

    @Test
    fun resolveModelPageUrlFromRepositorySpec() {
        val spec = com.shounak.localmeshai.models.ModelDownloadSpec(
            modelId = "test_hf_model",
            repository = "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
            artifacts = emptyList()
        )
        val resolved = MainViewModel.resolveModelPageUrl(spec, null)
        assertEquals("https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", resolved)
    }

    @Test
    fun resolveModelPageUrlFromHuggingFaceSourceUrl() {
        val blobUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/model.gguf"
        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            MainViewModel.resolveModelPageUrl(null, blobUrl)
        )

        val resolveUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/model.gguf?download=true"
        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            MainViewModel.resolveModelPageUrl(null, resolveUrl)
        )
    }

    @Test
    fun resolveModelPageUrlReturnsNullForNonHuggingFace() {
        assertNull(MainViewModel.resolveModelPageUrl(null, "content://com.android.providers.downloads/123"))
        assertNull(MainViewModel.resolveModelPageUrl(null, "https://example.com/models/model.gguf"))
        assertNull(MainViewModel.resolveModelPageUrl(null, null))
    }
}

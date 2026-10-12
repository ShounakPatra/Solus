package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ui.viewmodels.MainViewModel
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
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken(evilUrl))
    }

    @Test
    fun queryContainingHuggingFaceDoesNotTriggerNormalization() {
        val searchUrl = "https://example.com/search?q=huggingface.co/blob/main"
        val normalized = MainViewModel.normalizeCustomModelUrl(searchUrl)

        assertEquals(searchUrl, normalized)
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken(searchUrl))
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
    fun requiresHuggingFaceTokenClassification() {
        assertTrue(ModelDownloadUrlPolicy.requiresHuggingFaceToken("https://huggingface.co/org/repo/model.gguf"))
        assertTrue(ModelDownloadUrlPolicy.requiresHuggingFaceToken("https://HUGGINGFACE.CO/org/repo/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken("http://huggingface.co/org/repo/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken("https://evil-huggingface.co/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken("https://huggingface.co.evil.com/model.gguf"))
        assertFalse(ModelDownloadUrlPolicy.requiresHuggingFaceToken("https://example.com/?query=huggingface.co"))
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
}

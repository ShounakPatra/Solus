package com.shounak.localmeshai.models.state

import com.shounak.localmeshai.ai.voice.VoiceModelCatalog
import com.shounak.localmeshai.ai.voice.VoiceModelSpec
import com.shounak.localmeshai.ai.voice.VoiceRemoteArtifact
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class VoiceModeStateTest {

    @Test
    fun `readiness reports missing assets and allReady cleanly`() {
        val missingAll = VoiceAssetReadiness()
        assertFalse(missingAll.allReady)
        assertEquals(3, missingAll.missingNames.size)

        val ready = VoiceAssetReadiness(hasSileroVad = true, hasWhisperStt = true, hasTinyVoca = true)
        assertTrue(ready.allReady)
        assertTrue(ready.missingNames.isEmpty())
        assertEquals("Initializing…", VoiceModePhase.Initializing.displayLabel)
    }

    @Test
    fun `voice catalog specs carry pinned SHA-256 hashes`() {
        assertTrue(VoiceModelCatalog.SILERO_VAD.artifacts.all { it.expectedSha256.length == 64 && it.expectedSizeBytes > 0 })
        assertTrue(VoiceModelCatalog.WHISPER_TINY_EN.artifacts.all { it.expectedSha256.length == 64 && it.expectedSizeBytes > 0 })
    }

    @Test
    fun `resumable HTTP Range download completes partial file and verifies SHA-256`(): Unit = runBlocking {
        val payload = "Hello offline voice mode test payload 1234567890!".encodeToByteArray()
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val splitPoint = 16

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val start = rangeHeader.removePrefix("bytes=").substringBefore("-").toInt()
                    val tail = payload.copyOfRange(start, payload.size)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                        .setBody(Buffer().write(tail))
                }
                return MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
            }
        }
        server.start()
        try {
            val tmpDir = createTempDirectory("voice_resume_test").toFile()
            File(tmpDir, "silero_vad.onnx.part").writeBytes(payload.copyOfRange(0, splitPoint))
            val spec = VoiceModelSpec(
                id = "test-vad",
                displayName = "Test VAD",
                repository = "test/repo",
                revision = "main",
                description = "test",
                artifacts = listOf(
                    VoiceRemoteArtifact(
                        id = "vad",
                        relativeUrl = "silero_vad.onnx",
                        localFileName = "silero_vad.onnx",
                        expectedSizeBytes = payload.size.toLong(),
                        expectedSha256 = expectedSha
                    )
                )
            )
            VoiceModelCatalog.downloadSpec(
                dir = tmpDir,
                spec = spec,
                client = OkHttpClient(),
                urlsFor = { listOf(server.url("/silero_vad.onnx").toString()) }
            )
            val finalFile = File(tmpDir, "silero_vad.onnx")
            assertTrue(finalFile.isFile)
            assertArrayEquals(payload, finalFile.readBytes())
            val req = server.takeRequest()
            assertEquals("bytes=$splitPoint-", req.getHeader("Range"))
            tmpDir.deleteRecursively()
        } finally {
            server.shutdown()
        }
    }
}

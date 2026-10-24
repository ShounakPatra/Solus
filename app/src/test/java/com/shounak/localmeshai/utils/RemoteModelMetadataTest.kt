package com.shounak.localmeshai.utils

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RemoteModelMetadataTest {
    @Test fun exhaustedMetadataBudgetIsAnExplicitFailure() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val start = request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toLong()
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-${start + 65535}/${64 * 65536}")
                        .setBody(okio.Buffer().write(ByteArray(65536)))
                }
            }
            val metadata = RemoteModelMetadata(OkHttpClient(), server.url("/model").toString())
            repeat(32) { metadata.read(it * 65536L, 1) }
            val failure = assertThrows(MetadataProbeLimitException::class.java) { metadata.read(32 * 65536L, 1) }
            assertTrue(failure.message!!.contains("Compatibility could not be checked"))
            assertEquals(32, server.requestCount)
        }
    }
    @Test fun unresponsiveProbeIsCancelledByItsOverallDeadline() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val metadata = RemoteModelMetadata(OkHttpClient(), server.url("/model").toString(), timeoutMillis = 100)
            assertThrows(java.io.IOException::class.java) { metadata.read(0, 8) }
        }
    }
}

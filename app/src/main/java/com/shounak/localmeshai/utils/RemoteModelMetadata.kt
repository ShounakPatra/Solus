package com.shounak.localmeshai.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** At most 2 MiB of metadata, even if the server ignores Range. No weight download. */
internal class RemoteModelMetadata(private val client: OkHttpClient, private val url: String) {
    private val pages = mutableMapOf<Long, ByteArray>()
    var length: Long = -1
        private set

    fun read(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count in 0..1_048_576)
        val result = ByteArray(count)
        var copied = 0
        while (copied < count) {
            val position = offset + copied
            val start = position / PAGE * PAGE
            val page = pages.getOrPut(start) { fetch(start) }
            val from = (position - start).toInt()
            val size = minOf(count - copied, page.size - from)
            require(size > 0) { "Truncated model metadata" }
            page.copyInto(result, copied, from, from + size)
            copied += size
        }
        return result
    }

    private fun fetch(start: Long): ByteArray {
        if (pages.size >= 32) throw MetadataProbeLimitException()
        val stop = if (length >= 0) minOf(length - 1, start + PAGE - 1) else start + PAGE - 1
        require(stop >= start) { "Truncated model metadata" }
        val call = client.newCall(Request.Builder().url(url).header("Range", "bytes=$start-$stop")
            .header("Accept-Encoding", "identity").build())
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Cannot inspect model source (HTTP ${response.code})")
            if (response.code == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("Invalid metadata range response")
                require(range.groupValues[1].toLong() == start && range.groupValues[2].toLong() <= stop) { "Incorrect metadata range" }
                length = range.groupValues[3].toLong()
            } else {
                require(start == 0L) { "This source cannot provide bounded metadata ranges; import a complete local bundle instead" }
                length = response.body.contentLength()
            }
            val count = (stop - start + 1).toInt()
            val result = response.body.byteStream().readMetadataBytes(count)
            // Prevent OkHttp's close/discard path from reading a large ignored-Range body.
            if (response.code != 206 && (length < 0 || length > count)) call.cancel()
            return result
        }
    }

    companion object { private const val PAGE = 65536L }
}

internal class MetadataProbeLimitException : IllegalArgumentException("Export metadata exceeds the bounded preflight limit; remaining graph checks require the downloaded model")

internal fun java.io.InputStream.readMetadataBytes(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(minOf(limit, 8192))
    while (result.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - result.size()))
        if (count < 0) break
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

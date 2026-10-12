package com.shounak.localmeshai.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext

/**
 * Robust, interruptible streaming byte transfer utility with explicit cooperative
 * cancellation checks and progress reporting.
 */
object CancellableHttpTransfer {
    const val BUFFER_SIZE = 8192

    /**
     * Copies all bytes from [input] to [output] in bounded chunks, checking for
     * cancellation before and after each chunk read.
     *
     * @param input the source stream
     * @param output the target stream
     * @param coroutineContext optional coroutine context checked via [ensureActive]
     * @param isCancelled optional polling lambda to signal cancellation
     * @param onAbort optional callback to abort underlying transport (e.g. OkHttp Call)
     * @param onChunkTransferred invoked on each chunk with bytes read in this chunk and total transferred
     * @return total bytes transferred
     */
    suspend fun transfer(
        input: InputStream,
        output: OutputStream,
        coroutineContext: CoroutineContext? = null,
        isCancelled: (() -> Boolean)? = null,
        onAbort: (() -> Unit)? = null,
        onChunkTransferred: suspend (bytesRead: Int, totalTransferred: Long) -> Unit = { _, _ -> }
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalTransferred = 0L

        // Cooperatively abort the call or input stream when coroutine is cancelled while blocked on socket read
        val cancellationHandle = coroutineContext?.get(Job)?.invokeOnCompletion {
            try {
                onAbort?.invoke() ?: input.close()
            } catch (_: Throwable) {}
        }

        try {
            while (true) {
                coroutineContext?.ensureActive()
                if (isCancelled?.invoke() == true) {
                    onAbort?.invoke()
                    throw CancellationException("Byte transfer cancelled.")
                }

                val read = input.read(buffer)
                if (read == -1) break

                output.write(buffer, 0, read)
                totalTransferred += read

                coroutineContext?.ensureActive()
                if (isCancelled?.invoke() == true) {
                    onAbort?.invoke()
                    throw CancellationException("Byte transfer cancelled.")
                }

                onChunkTransferred(read, totalTransferred)
            }

            output.flush()
            return totalTransferred
        } finally {
            cancellationHandle?.dispose()
        }
    }
}

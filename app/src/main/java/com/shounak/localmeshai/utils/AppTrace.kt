package com.shounak.localmeshai.utils

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight safe wrapper around Android's [Trace] API.
 * Supports synchronous sections as well as asynchronous cross-coroutine spans.
 * Safely no-ops in JVM unit test environments without Android framework stubbing.
 */
object AppTrace {

    interface TraceListener {
        fun onBeginSection(name: String) {}
        fun onEndSection() {}
        fun onBeginAsyncSection(name: String, cookie: Int) {}
        fun onEndAsyncSection(name: String, cookie: Int) {}
    }

    @Volatile
    var listener: TraceListener? = null

    private val cookieSeq = AtomicInteger(1000)

    fun nextCookie(): Int = cookieSeq.incrementAndGet()

    inline fun <T> section(name: String, block: () -> T): T {
        beginSection(name)
        return try {
            block()
        } finally {
            endSection()
        }
    }

    fun beginSection(name: String) {
        val safeName = if (name.length > 120) name.take(120) else name
        listener?.onBeginSection(safeName)
        try {
            // Android platform Trace.beginSection enforces length <= 127
            Trace.beginSection(safeName)
        } catch (_: Throwable) {
            // Safe fallback for host JVM tests
        }
    }

    fun endSection() {
        listener?.onEndSection()
        try {
            Trace.endSection()
        } catch (_: Throwable) {
            // Safe fallback for host JVM tests
        }
    }

    fun beginAsyncSection(name: String, cookie: Int) {
        val safeName = if (name.length > 120) name.take(120) else name
        listener?.onBeginAsyncSection(safeName, cookie)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.beginAsyncSection(safeName, cookie)
            }
        } catch (_: Throwable) {
            // Safe fallback for host JVM tests
        }
    }

    fun endAsyncSection(name: String, cookie: Int) {
        val safeName = if (name.length > 120) name.take(120) else name
        listener?.onEndAsyncSection(safeName, cookie)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection(safeName, cookie)
            }
        } catch (_: Throwable) {
            // Safe fallback for host JVM tests
        }
    }

    inline fun <T> asyncSection(name: String, cookie: Int, block: () -> T): T {
        beginAsyncSection(name, cookie)
        return try {
            block()
        } finally {
            endAsyncSection(name, cookie)
        }
    }

    suspend inline fun <T> asyncSectionSuspend(name: String, cookie: Int, crossinline block: suspend () -> T): T {
        beginAsyncSection(name, cookie)
        return try {
            block()
        } finally {
            endAsyncSection(name, cookie)
        }
    }

    class AsyncSpan(val name: String, val cookie: Int) : AutoCloseable {
        override fun close() {
            endAsyncSection(name, cookie)
        }
    }

    fun startAsyncSpan(name: String, cookie: Int = nextCookie()): AsyncSpan {
        beginAsyncSection(name, cookie)
        return AsyncSpan(name, cookie)
    }
}

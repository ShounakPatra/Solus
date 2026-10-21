package com.shounak.localmeshai.utils

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AppTraceTest {

    private class TestTraceListener : AppTrace.TraceListener {
        val events = mutableListOf<String>()

        override fun onBeginSection(name: String) {
            events.add("beginSection:$name")
        }

        override fun onEndSection() {
            events.add("endSection")
        }

        override fun onBeginAsyncSection(name: String, cookie: Int) {
            events.add("beginAsync:$name:$cookie")
        }

        override fun onEndAsyncSection(name: String, cookie: Int) {
            events.add("endAsync:$name:$cookie")
        }
    }

    private lateinit var listener: TestTraceListener

    @Before
    fun setUp() {
        listener = TestTraceListener()
        AppTrace.listener = listener
    }

    @After
    fun tearDown() {
        AppTrace.listener = null
    }

    @Test
    fun testSynchronousSectionSuccess() {
        val result = AppTrace.section("test_sync") {
            42
        }
        assertEquals(42, result)
        assertEquals(listOf("beginSection:test_sync", "endSection"), listener.events)
    }

    @Test
    fun testSynchronousSectionException() {
        try {
            AppTrace.section("test_fail") {
                throw IOException("simulated failure")
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("simulated failure", e.message)
        }
        assertEquals(listOf("beginSection:test_fail", "endSection"), listener.events)
    }

    @Test
    fun testNameTruncationOver120Chars() {
        val longName = "A".repeat(150)
        AppTrace.section(longName) {
            // no-op
        }
        val expectedName = "A".repeat(120)
        assertEquals(listOf("beginSection:$expectedName", "endSection"), listener.events)
    }

    @Test
    fun testAsyncSectionLifecycle() {
        val cookie = 456
        AppTrace.asyncSection("async_op", cookie) {
            // inside async section
        }
        assertEquals(listOf("beginAsync:async_op:456", "endAsync:async_op:456"), listener.events)
    }

    @Test
    fun testAsyncSectionSuspend() = runBlocking {
        val cookie = 789
        val res = AppTrace.asyncSectionSuspend("async_suspend", cookie) {
            kotlinx.coroutines.delay(10)
            "hello"
        }
        assertEquals("hello", res)
        assertEquals(listOf("beginAsync:async_suspend:789", "endAsync:async_suspend:789"), listener.events)
    }

    @Test
    fun testStartAsyncSpanAutoCloseable() {
        val span = AppTrace.startAsyncSpan("span_block", 999)
        span.use {
            // work in span
        }
        assertEquals(listOf("beginAsync:span_block:999", "endAsync:span_block:999"), listener.events)
    }

    @Test
    fun testUniqueCookieGeneration() {
        val c1 = AppTrace.nextCookie()
        val c2 = AppTrace.nextCookie()
        assertTrue(c2 > c1)
    }
}

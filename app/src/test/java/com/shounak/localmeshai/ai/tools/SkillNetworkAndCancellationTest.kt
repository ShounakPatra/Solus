package com.shounak.localmeshai.ai.tools

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SkillNetworkAndCancellationTest {
    @Test fun wikipediaExcerptsDecodeEscapedQuotesAndUnicodeAfterRemovingMarkup() {
        assertEquals("A \"planet\" & Earth 🌍 <source>",
            WikipediaLookup.plainText("A &quot;planet&quot; &amp; <span>Earth</span> &#x1F30D; &lt;source&gt;"))
        assertEquals("&#xD800; &unknown;", WikipediaLookup.plainText("&#xD800; &unknown;"))
    }
    @Test fun wikipediaEncodesOnlyTheTopicAndReturnsSourceLinksWithoutHtmlMarkup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pages":[{"key":"Earth","title":"Earth","description":"Planet","excerpt":"The <span class=\"searchmatch\">Earth</span> is a planet."}]}"""))
            val result = JSONObject(WikipediaLookup.lookup("Earth & Mars", OkHttpClient(), server.url("/search").toString()))
            val request = server.takeRequest()
            assertEquals("Earth & Mars", request.requestUrl!!.queryParameter("q"))
            assertNull(request.getHeader("Authorization"))
            val page = result.getJSONArray("results").getJSONObject(0)
            assertEquals("The Earth is a planet.", page.getString("excerpt"))
            assertEquals("https://en.wikipedia.org/wiki/Earth", page.getString("url"))
        }
    }
    @Test fun networkFailuresAndOversizedResponsesAreBounded() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("x".repeat(132000)))
            repeat(2) { assertThrows(Exception::class.java) { WikipediaLookup.lookup("Earth", OkHttpClient(), server.url("/search").toString()) } }
        }
    }
    @Test fun stopReturnsCancellationInsteadOfThrowingAcrossTheSdkCallback() {
        val entered = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val delegate = object : ChatTool {
            override val name = "slow"
            override fun getToolDescriptionJsonString() = "{}"
            override fun execute(paramsJsonString: String): String { entered.countDown(); CountDownLatch(1).await(); return "done" }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<String> { BoundedSkillTool(delegate, stop::get).execute("{}") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            stop.set(true)
            assertEquals("Error: cancelled", future.get(3, TimeUnit.SECONDS))
            assertEquals("Error: cancelled", BoundedSkillTool(delegate, { true }).execute("{}"))
        } finally { worker.shutdownNow() }
    }
    @Test fun boundedSkillFieldsNeverEndWithHalfAnEmoji() {
        val text = "x".repeat(1199) + "🌍"
        assertEquals("x".repeat(1199), boundedSkillText(text, 1200))
        assertEquals(text, boundedSkillText(text, 1201))
        assertEquals("x".repeat(199), boundedSkillText("x".repeat(199) + "🌍", 200))
        assertEquals("x".repeat(399), boundedSkillText("x".repeat(399) + "🌍", 400))
    }
    @Test fun stoppingNetworkToolCancelsItsActualCallAndReleasesWorker() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            val stop = AtomicBoolean(false)
            val finished = CountDownLatch(1)
            val delegate = object : ChatTool {
                override val name = "query_wikipedia"
                override fun getToolDescriptionJsonString() = "{}"
                override fun execute(paramsJsonString: String): String = try {
                    WikipediaLookup.lookup("Earth", client, server.url("/search").toString())
                } finally { finished.countDown() }
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<String> { BoundedSkillTool(delegate, stop::get).execute("{}") }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                stop.set(true)
                assertEquals("Error: cancelled", result.get(3, TimeUnit.SECONDS))
                assertTrue("Network worker should not wait for its 20 second call timeout", finished.await(3, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun durableMoodWriteReturnsItsCommittedResultEvenWhenStopArrivesDuringCommit() {
        val stop = AtomicBoolean(false)
        val delegate = object : ChatTool {
            override val name = "mood_tracker"
            override fun getToolDescriptionJsonString() = "{}"
            override fun execute(paramsJsonString: String): String { stop.set(true); return "saved" }
        }
        assertEquals("saved", BoundedSkillTool(delegate, stop::get).execute("{}"))
    }

}

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
}

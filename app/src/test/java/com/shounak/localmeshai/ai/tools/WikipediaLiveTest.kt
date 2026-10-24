package com.shounak.localmeshai.ai.tools

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in live check; ordinary tests never require internet access. */
class WikipediaLiveTest {
    @Test fun productionLookupReturnsWikipediaArticles() {
        assumeTrue(System.getenv("SOLUS_LIVE_NETWORK_TEST") == "1")
        val result = JSONObject(WikipediaLookup.lookup("Earth", WikipediaLookup.client))
        val pages = result.getJSONArray("results")
        assertTrue(pages.length() in 1..3)
        assertTrue((0 until pages.length()).any { pages.getJSONObject(it).getString("title") == "Earth" })
        for (index in 0 until pages.length()) {
            assertTrue(pages.getJSONObject(index).getString("url").startsWith("https://en.wikipedia.org/wiki/"))
        }
        println("LIVE Wikipedia: " + result)
    }
}

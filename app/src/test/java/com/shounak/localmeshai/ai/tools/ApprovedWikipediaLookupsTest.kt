package com.shounak.localmeshai.ai.tools

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovedWikipediaLookupsTest {
    @Test fun modelCannotFetchOrChangeAQueryWithoutAnExactUserApproval() {
        val topic = "approved-topic-${java.util.UUID.randomUUID()}"
        val pending = JSONObject(ApprovedWikipediaLookups.request(topic))
        assertEquals("requires_user_action", pending.getString("status"))
        assertEquals(topic, pending.getString("topic"))
        var fetches = 0
        val result = """{"source":"Wikipedia","results":[]}"""
        ApprovedWikipediaLookups.approve(topic) { requested ->
            assertEquals(topic, requested)
            fetches++
            result
        }
        assertEquals(result, ApprovedWikipediaLookups.request(topic))
        assertEquals(1, fetches)
        assertEquals("requires_user_action", JSONObject(ApprovedWikipediaLookups.request("$topic private-data")).getString("status"))
        assertEquals("requires_user_action", JSONObject(ApprovedWikipediaLookups.request(topic, Long.MAX_VALUE)).getString("status"))
    }
}

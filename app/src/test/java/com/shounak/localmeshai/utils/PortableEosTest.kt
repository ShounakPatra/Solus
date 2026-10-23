package com.shounak.localmeshai.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableEosTest {
    @Test fun missingEosReportsConfigurationProblemBeforeInference() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PortableModelBundle.eosTokens(JSONObject(), JSONObject())
        }
        assertTrue(error.message.orEmpty().startsWith("Missing eos_token_id"))
    }

    @Test fun nestedAndNullMetadataUseTheSameResolver() {
        val config = JSONObject("""{"eos_token_id":null,"text_config":{"eos_token_id":[2,3]}}""")
        assertEquals(setOf(2,3), PortableModelBundle.eosTokens(config, JSONObject("""{"eos_token_id":null}""")))
        assertEquals(setOf(4), PortableModelBundle.eosTokens(config, JSONObject("""{"eos_token_id":4}""")))
    }
    @Test fun malformedStopsAreRejected() {
        listOf("[]", "[-1]", "1.5", "2147483648", "null").forEach {
            try { PortableModelBundle.eosTokens(JSONObject("{\"eos_token_id\":$it}")); fail(it) }
            catch (_: IllegalArgumentException) { }
        }
    }
}

package com.shounak.localmeshai.features

import com.shounak.localmeshai.utils.AppSettingsData
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableSettingsCompatibilityTest {
    @Test fun missingAndUnknownFieldsPreserveCurrentSettingsAndCredentials() {
        val current = AppSettingsData(enablePersistentMemory = false, ragTopK = 7, huggingFaceToken = "keep")
        val decoded = PortableSettings.decode(JSONObject().put("dark", false)
            .put("futureSetting", JSONObject.NULL).put("huggingFaceToken", "ignore"), current)
        assertEquals(current.copy(enableDarkMode = false), decoded)
        assertEquals(current, PortableSettings.decode(JSONObject(), current))
    }

    @Test fun presentKnownValuesStillRequireCorrectTypesAndRanges() {
        for ((key, value) in listOf("dark" to "true", "backend" to 3, "backend" to "OTHER",
            "ragTopK" to 2.5, "ragTopK" to 0, "autoUnloadMinutes" to -1, "ragMinSimilarity" to 1.1,
            "dark" to JSONObject.NULL)) {
            try { PortableSettings.decode(JSONObject().put(key, value), AppSettingsData()); fail("Accepted $key=$value") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun largerControlsAreOptInAndExistingChoicesRemain() {
        assertFalse(WorkspacePreferences(FeatureTestPreferences()).largeControls)
        assertFalse(WorkspaceVisuals().largeControls)
        assertTrue(WorkspacePreferences(FeatureTestPreferences(mapOf("large_controls" to true))).largeControls)
    }
}

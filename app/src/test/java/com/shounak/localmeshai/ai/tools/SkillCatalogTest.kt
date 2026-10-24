package com.shounak.localmeshai.ai.tools

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SkillCatalogTest {
    private val services = object : SkillServices {
        override fun calendar(date: LocalDate) = JSONObject().put("date", date.toString()).put("events", JSONArray()).toString()
        override fun mood(arguments: JSONObject) = JSONObject().put("operation", arguments.getString("operation")).toString()
        override fun wikipedia(topic: String) = JSONObject().put("topic", topic).toString()
        override fun reminders() = """{"reminders":[]}"""
    }
    private fun run(id: String, arguments: String) = SkillCatalog.builtins.single { it.id == id }.tool(services).execute(arguments)

    @Test fun everySkillSchemaParsesAndHasAUniqueBackendSafeName() {
        assertEquals(14, SkillCatalog.builtins.size)
        assertEquals(14, SkillCatalog.builtins.map { it.id }.distinct().size)
        for (definition in SkillCatalog.builtins) {
            val json = JSONObject(definition.schema())
            assertEquals(definition.id, json.getString("name"))
            assertTrue(definition.id.matches(Regex("[a-z][a-z0-9_]+")))
            assertFalse(json.getJSONObject("parameters").getBoolean("additionalProperties"))
        }
    }
    @Test fun allSkillsExecuteWithValidArgumentsAndReturnJson() {
        val inputs = mapOf(
            "calculate_hash" to """{"text":"hello"}""",
            "create_calendar_event" to """{"title":"Meeting","start":"2027-01-01T10:00:00+05:30","end":"2027-01-01T11:00:00+05:30"}""",
            "interactive_map" to """{"location":"Kolkata"}""", "kitchen_adventure" to "{}",
            "learn_something_new" to """{"topic":"Gravity","lesson":"Mass attracts mass."}""",
            "mood_tracker" to """{"operation":"history"}""", "qr_code" to """{"text":"https://example.com"}""",
            "query_wikipedia" to """{"topic":"Earth"}""", "read_calendar_events" to """{"date":"2027-01-01"}""",
            "schedule_notification" to """{"operation":"list"}""",
            "send_email" to """{"to":"friend@example.com","subject":"Hi","body":"Hello"}""",
            "text_spinner" to """{"options":["A","B"]}""",
            "convert_units" to """{"value":1,"from":"km","to":"m"}""",
            "text_statistics" to """{"text":"Hello world"}"""
        )
        for ((id, input) in inputs) { val result = run(id, input); assertFalse("$id: $result", result.startsWith("Error:")); JSONObject(result) }
    }
    @Test fun knownHashVectorsAreCorrect() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", JSONObject(run("calculate_hash", """{"text":"hello"}""")).getString("hash"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", JSONObject(run("calculate_hash", """{"text":"","algorithm":"MD5"}""")).getString("hash"))
        assertTrue(run("calculate_hash", """{"text":"hello","algorithm":"BAD"}""").startsWith("Error:"))
    }
    @Test fun actionToolsPrepareReviewableDraftsInsteadOfClaimingSideEffects() {
        for ((id, input) in listOf(
            "send_email" to """{"to":"a@example.com","subject":"Hi","body":"Draft"}""",
            "create_calendar_event" to """{"title":"Test","start":"2027-01-01T10:00:00Z","end":"2027-01-01T11:00:00Z"}"""
        )) assertEquals("requires_user_action", JSONObject(run(id, input)).getString("status"))
        val future = java.time.ZonedDateTime.now().plusDays(2).toString()
        val draft = JSONObject(run("schedule_notification", JSONObject().put("operation", "prepare").put("title", "Learn")
            .put("message", "Learn a concept").put("datetime", future).put("repeat", "daily").toString()))
        assertEquals("requires_user_action", draft.getString("status"))
        ToolReminders.validate(draft)
        assertTrue(draft.has("anchor"))
    }
    @Test fun invalidArgumentsAlwaysReturnErrors() {
        val examples = listOf(
            "send_email" to """{"to":"a@example.com,b@example.com","subject":"Hi","body":"Body"}""",
            "send_email" to """{"to":"a@example.com","subject":"Hi\nBcc: a@b.com","body":"Body"}""",
            "text_spinner" to """{"options":["one"]}""", "text_spinner" to """{"options":[1,2]}""",
            "create_calendar_event" to """{"title":"Bad","start":"2027-01-01T10:00:00Z","end":"2027-01-01T09:00:00Z"}""",
            "read_calendar_events" to """{"date":"bad"}""", "text_statistics" to """{"text":true}""",
            "calculate_hash" to """{"text":"ok","unexpected":1}"""
        )
        for ((id, input) in examples) assertTrue("$id accepted $input", run(id, input).startsWith("Error:"))
        SkillCatalog.builtins.forEach { assertTrue(it.tool(services).execute("not json").startsWith("Error:")) }
        assertTrue(run("qr_code", JSONObject().put("text", "文".repeat(501)).toString()).startsWith("Error:"))
    }
    @Test fun spinnerChoiceAndIndexReferToTheSameInputOption() {
        val options = listOf("Tea", "Coffee", "Water")
        repeat(20) {
            val result = JSONObject(run("text_spinner", JSONObject().put("options", JSONArray(options)).toString()))
            assertEquals(options[result.getInt("index")], result.getString("choice"))
        }
    }
    @Test fun textStatisticsHandleUnicodeAndEmptyText() {
        val result = JSONObject(run("text_statistics", JSONObject().put("text", "Hi 😀\n世界").toString()))
        assertEquals(7, result.getInt("characters")); assertEquals(2, result.getInt("words")); assertEquals(2, result.getInt("lines"))
        assertEquals(0, JSONObject(run("text_statistics", """{"text":""}""")).getInt("lines"))
    }
    @Test fun compatibleUnitsConvertAndInvalidDimensionsFail() {
        assertEquals(1609.344, UnitConversion.convert(1.0, "mi", "m"), 1e-9)
        assertEquals(0.0, UnitConversion.convert(32.0, "F", "C"), 1e-9)
        assertEquals(273.15, UnitConversion.convert(0.0, "C", "K"), 1e-9)
        assertEquals(1024.0, UnitConversion.convert(1.0, "KiB", "B"), 1e-9)
        for ((from, to) in listOf("m" to "kg", "C" to "m", "bad" to "m")) assertThrows(Exception::class.java) { UnitConversion.convert(1.0, from, to) }
        assertThrows(IllegalArgumentException::class.java) { UnitConversion.convert(-1.0, "K", "C") }
        assertThrows(IllegalArgumentException::class.java) { UnitConversion.convert(Double.POSITIVE_INFINITY, "m", "km") }
    }
}

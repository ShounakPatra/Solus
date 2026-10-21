package com.shounak.localmeshai.ui

import android.content.SharedPreferences
import com.shounak.localmeshai.ai.EmptyResponseReason
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.data.ChatSessionRepository
import com.shounak.localmeshai.data.SessionCodec
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SessionRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testV2ChatSession_EncodeDecodeRoundTrip() {
        val userMsgId = UUID.randomUUID().toString()
        val assistantMsgId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()

        val messages = listOf(
            ChatMessage(
                id = userMsgId,
                text = "Hello, can you help me?",
                isUser = true,
                documentName = "doc.txt",
                documentPath = "/path/to/doc.txt"
            ),
            ChatMessage(
                id = assistantMsgId,
                text = "Sure! I can help you.",
                isUser = false,
                terminalStatus = RequestTerminalStatus.Completed,
                requestId = requestId,
                attemptId = 1,
                userMessageId = userMsgId,
                reasoningText = "Thinking process",
                ragSources = listOf("source1", "source2"),
                ragChunkCount = 2,
                ragTopMatchPct = 85
            )
        )

        val session = ChatSession(
            id = "test-session-1",
            title = "Test chat",
            messages = messages,
            updatedAt = 123456789L,
            documentId = "doc-123"
        )

        val encodedJson = SessionCodec.encodeChatSessions(listOf(session))
        assertTrue(encodedJson.contains("\"version\":2"))

        val decoded = SessionCodec.decodeChatSessions(encodedJson)
        assertEquals(1, decoded.size)
        val decodedSession = decoded[0]
        assertEquals("test-session-1", decodedSession.id)
        assertEquals("Test chat", decodedSession.title)
        assertEquals(123456789L, decodedSession.updatedAt)
        assertEquals("doc-123", decodedSession.documentId)
        assertEquals(2, decodedSession.messages.size)

        val userDecoded = decodedSession.messages[0]
        assertEquals(userMsgId, userDecoded.id)
        assertEquals("Hello, can you help me?", userDecoded.text)
        assertTrue(userDecoded.isUser)
        assertEquals("doc.txt", userDecoded.documentName)
        assertEquals("/path/to/doc.txt", userDecoded.documentPath)

        val assistantDecoded = decodedSession.messages[1]
        assertEquals(assistantMsgId, assistantDecoded.id)
        assertEquals("Sure! I can help you.", assistantDecoded.text)
        assertFalse(assistantDecoded.isUser)
        assertEquals(RequestTerminalStatus.Completed, assistantDecoded.terminalStatus)
        assertEquals(requestId, assistantDecoded.requestId)
        assertEquals(1, assistantDecoded.attemptId)
        assertEquals(userMsgId, assistantDecoded.userMessageId)
        assertEquals("Thinking process", assistantDecoded.reasoningText)
        assertEquals(listOf("source1", "source2"), assistantDecoded.ragSources)
        assertEquals(2, assistantDecoded.ragChunkCount)
        assertEquals(85, assistantDecoded.ragTopMatchPct)
    }

    @Test
    fun testTrailingUserTurnPreserved_SynthesizesFailedAssistantTurn() {
        val userMsgId = UUID.randomUUID().toString()
        val messages = listOf(
            ChatMessage(
                id = userMsgId,
                text = "Unfinished user prompt",
                isUser = true
            )
        )

        val session = ChatSession(
            id = "sess-trailing",
            title = "Trailing user turn",
            messages = messages,
            updatedAt = 1000L
        )

        val encodedJson = SessionCodec.encodeChatSessions(listOf(session))
        val decoded = SessionCodec.decodeChatSessions(encodedJson)

        assertEquals(1, decoded.size)
        val msgs = decoded[0].messages
        // Should keep the user turn AND synthesize a failed assistant turn
        assertEquals(2, msgs.size)
        assertEquals(userMsgId, msgs[0].id)
        assertTrue(msgs[0].isUser)

        val failedAssistant = msgs[1]
        assertFalse(failedAssistant.isUser)
        assertEquals(RequestTerminalStatus.Failed, failedAssistant.terminalStatus)
        assertEquals(SessionCodec.INTERRUPTED_ERROR_MESSAGE, failedAssistant.errorMessage)
        assertEquals(userMsgId, failedAssistant.userMessageId)
        assertEquals(1, failedAssistant.attemptId)
    }

    @Test
    fun testInFlightAssistantTurn_NormalizedToFailedWithRetryAvailable() {
        val userMsgId = UUID.randomUUID().toString()
        val assistantMsgId = UUID.randomUUID().toString()
        val messages = listOf(
            ChatMessage(
                id = userMsgId,
                text = "User message",
                isUser = true
            ),
            ChatMessage(
                id = assistantMsgId,
                text = "Partial incomplete...",
                isUser = false,
                terminalStatus = null // In flight when crashed / restarted
            )
        )

        val session = ChatSession(
            id = "sess-inflight",
            title = "In flight turn",
            messages = messages,
            updatedAt = 1000L
        )

        val encodedJson = SessionCodec.encodeChatSessions(listOf(session))
        val decoded = SessionCodec.decodeChatSessions(encodedJson)

        assertEquals(1, decoded.size)
        val msgs = decoded[0].messages
        assertEquals(2, msgs.size)
        val assistant = msgs[1]
        assertEquals(RequestTerminalStatus.Failed, assistant.terminalStatus)
        assertEquals(SessionCodec.INTERRUPTED_ERROR_MESSAGE, assistant.errorMessage)
    }

    @Test
    fun testVisionBlankAnswer_PreservedWithoutDroppingSession() {
        val userMsgId = UUID.randomUUID().toString()
        val assistantMsgId = UUID.randomUUID().toString()
        val visionMessages = listOf(
            VisionChatMessage(
                id = userMsgId,
                text = "What is in this image?",
                isUser = true,
                imagePath = "/data/image.png"
            ),
            VisionChatMessage(
                id = assistantMsgId,
                text = "", // Blank answer due to failure
                isUser = false,
                terminalStatus = RequestTerminalStatus.Failed,
                errorMessage = "Inference failed."
            )
        )

        val visionSession = VisionChatSession(
            id = "vision-sess-1",
            title = "Image question",
            question = "What is in this image?",
            answer = "", // Blank answer
            updatedAt = 5000L,
            imagePath = "/data/image.png",
            messages = visionMessages
        )

        val encoded = SessionCodec.encodeVisionSessions(listOf(visionSession))
        val decoded = SessionCodec.decodeVisionSessions(encoded)

        assertEquals(1, decoded.size)
        val recovered = decoded[0]
        assertEquals("vision-sess-1", recovered.id)
        assertEquals(2, recovered.messages.size)
        assertEquals(userMsgId, recovered.messages[0].id)
        assertEquals("/data/image.png", recovered.messages[0].imagePath)
        assertEquals(RequestTerminalStatus.Failed, recovered.messages[1].terminalStatus)
    }

    @Test
    fun testCorruptFilePreservedForRecovery_ReturnsEmptyList() {
        val rootDir = tempFolder.newFolder("corrupt_test")
        val repo = ChatSessionRepository(rootDir)

        // Write corrupt JSON to repo.chatFile
        repo.chatFile.writeText("{ this is corrupt json invalid syntax ...")

        val loaded = repo.loadChatSessions()
        assertTrue("Corrupt file should yield empty list", loaded.isEmpty())

        // Ensure original corrupt file was preserved or backed up to .corrupt-<timestamp>
        val corruptBackups = rootDir.listFiles { _, name -> name.contains("corrupt") }
        assertTrue("Corrupt file must be preserved for recovery", corruptBackups != null && corruptBackups.isNotEmpty())
    }

    @Test
    fun testLegacySharedPreferencesMigration_MigratesAndRemovesLegacyKeyOnlyOnCommit() {
        val rootDir = tempFolder.newFolder("prefs_migration")

        val legacyJson = """
            [
              {
                "id": "legacy-session-1",
                "title": "Legacy Session",
                "updatedAt": 99999,
                "messages": [
                  {
                    "id": "u1",
                    "text": "Hello legacy",
                    "isUser": true
                  },
                  {
                    "id": "a1",
                    "text": "Hello assistant",
                    "isUser": false
                  }
                ]
              }
            ]
        """.trimIndent()

        val mockPrefs = InMemorySharedPreferences(mapOf("sessions_json" to legacyJson))
        val repo = ChatSessionRepository(rootDir, legacyChatPrefs = mockPrefs)

        val loaded = repo.loadChatSessions()
        assertEquals(1, loaded.size)
        assertEquals("legacy-session-1", loaded[0].id)
        assertEquals("Legacy Session", loaded[0].title)

        // Verifies V2 file was written
        assertTrue(repo.chatFile.exists())
        val v2Content = repo.chatFile.readText()
        assertTrue(v2Content.contains("\"version\":2"))

        // Verifies legacy preference key was removed after successful migration
        assertNull(mockPrefs.getString("sessions_json", null))
    }

    @Test
    fun testLegacyJsonWithoutUpdatedAt_PreservesSessionWithSensibleDefault() {
        val legacyJsonWithoutUpdatedAt = """
            [
              {
                "id": "legacy-no-updated-at",
                "title": "Old Session",
                "messages": [
                  {
                    "id": "u1",
                    "text": "Hello without timestamp",
                    "isUser": true
                  },
                  {
                    "id": "a1",
                    "text": "Answer without timestamp",
                    "isUser": false
                  }
                ]
              }
            ]
        """.trimIndent()

        val decoded = SessionCodec.decodeChatSessions(legacyJsonWithoutUpdatedAt)
        assertEquals(1, decoded.size)
        val session = decoded[0]
        assertEquals("legacy-no-updated-at", session.id)
        assertEquals("Old Session", session.title)
        assertTrue("updatedAt must be populated with a valid timestamp", session.updatedAt > 0L)
        assertEquals(2, session.messages.size)
        assertEquals("Hello without timestamp", session.messages[0].text)
        assertEquals("Answer without timestamp", session.messages[1].text)
    }

    @Test
    fun testConcurrentSessionWrites_SerializedSuccessfully() {
        val rootDir = tempFolder.newFolder("concurrent_sessions")
        val repo = ChatSessionRepository(rootDir)

        val threadCount = 6
        val writesPerThread = 5
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until writesPerThread) {
                        val session = ChatSession(
                            id = "thread-${t}-iter-$i",
                            title = "Chat $t $i",
                            messages = listOf(
                                ChatMessage(id = "msg-$t-$i", text = "Hi $t $i", isUser = true)
                            ),
                            updatedAt = System.currentTimeMillis()
                        )
                        val ok = repo.saveChatSessions(listOf(session))
                        if (!ok) errors.incrementAndGet()
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("No write errors should occur under concurrent writes", 0, errors.get())
        assertTrue("Chat file must exist and be valid", repo.chatFile.exists())
        val loaded = repo.loadChatSessions()
        assertTrue("Must be able to load sessions after concurrent writes", loaded.isNotEmpty())
    }

    private class InMemorySharedPreferences(initialValues: Map<String, Any?> = emptyMap()) : SharedPreferences {
        private val data = initialValues.toMutableMap()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class EditorImpl(private val prefs: InMemorySharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearPending = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = this }
            override fun clear(): SharedPreferences.Editor = apply { clearPending = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearPending) prefs.data.clear()
                pending.forEach { (k, v) ->
                    if (v === this) prefs.data.remove(k) else prefs.data[k] = v
                }
            }
        }
    }
}

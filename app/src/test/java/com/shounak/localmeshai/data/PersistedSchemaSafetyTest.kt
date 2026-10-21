package com.shounak.localmeshai.data

import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.store.RagChunkRecord
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import com.shounak.localmeshai.utils.AtomicReadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistedSchemaSafetyTest {
    @get:Rule val folder = TemporaryFolder()
    private fun text() = listOf(ChatSession("s", "Saved", listOf(ChatMessage(text = "Keep", isUser = true, id = "u")), 123))
    private fun vision() = listOf(VisionChatSession("s", "Saved", "Keep", "", 123,
        messages = listOf(VisionChatMessage(text = "Keep", isUser = true, id = "u"))))
    private fun valid(kind: ChatKind) = if (kind == ChatKind.Text) SessionCodec.encodeChatSessions(text()) else SessionCodec.encodeVisionSessions(vision())
    private fun damagedHistory(kind: ChatKind): List<String> {
        val raw = valid(kind)
        fun mutate(block: (JSONObject) -> Unit) = JSONObject(raw).also(block).toString()
        return listOf("", "null", "42", "{}", "[]oops",
            """{"version":2,"sessions":"broken"}""", """{"version":2,"sessions":[7]}""",
            """{"version":2,"sessions":null}""", """{"version":"2","sessions":[]}""",
            """{"version":0,"sessions":[]}""", """{"version":2.5,"sessions":[]}""") + listOf(
            mutate { it.getJSONArray("sessions").getJSONObject(0).put("messages", "broken") },
            mutate { it.getJSONArray("sessions").getJSONObject(0).put("messages", JSONArray().put(7)) },
            mutate { it.getJSONArray("sessions").getJSONObject(0).getJSONArray("messages").getJSONObject(0).put("text", 123) },
            mutate { it.getJSONArray("sessions").getJSONObject(0).getJSONArray("messages").getJSONObject(0).put("isUser", "true") },
            mutate { it.getJSONArray("sessions").getJSONObject(0).getJSONArray("messages").getJSONObject(0).remove("text") },
            mutate { it.getJSONArray("sessions").getJSONObject(0).getJSONArray("messages").getJSONObject(0).put("ragPassages", "broken") },
            mutate { it.getJSONArray("sessions").getJSONObject(0).put("updatedAt", "yesterday") }
        )
    }
    @Test fun damagedTextAndVisionRejectWritesAndDeletionWithoutLosingFiles() = runBlocking {
        for (kind in ChatKind.entries) for (damaged in damagedHistory(kind)) {
            val root = folder.newFolder()
            val repo = ChatSessionRepository(root)
            val file = if (kind == ChatKind.Text) repo.chatFile else repo.visionFile
            val backup = File(root, file.name + ".bak")
            file.writeText(damaged); backup.writeText(damaged)
            if (kind == ChatKind.Text) {
                assertTrue("Accepted damaged text: $damaged", repo.loadChatSessionsAsync() is AtomicReadResult.Corrupt)
                assertFalse(repo.isChatStoreHealthy())
                assertFalse(repo.saveChatSessions(text()))
            } else {
                assertTrue("Accepted damaged vision: $damaged", repo.loadVisionSessionsAsync() is AtomicReadResult.Corrupt)
                assertFalse(repo.isVisionStoreHealthy())
                assertFalse(repo.saveVisionSessions(vision()))
            }
            assertTrue(repo.deleteSessionAsync(kind, "s") is DeletionOutcome.FailedBeforeCommit)
            assertEquals(damaged, file.readText())
            assertEquals(damaged, backup.readText())
            file.writeText(valid(kind)); backup.writeText(valid(kind))
            if (kind == ChatKind.Text) {
                assertTrue(repo.loadChatSessionsAsync() is AtomicReadResult.Success)
                assertTrue(repo.saveChatSessions(repo.loadChatSessions()))
            } else {
                assertTrue(repo.loadVisionSessionsAsync() is AtomicReadResult.Success)
                assertTrue(repo.saveVisionSessions(repo.loadVisionSessions()))
            }
        }
    }
    @Test fun structurallyDamagedPrimaryRecoversValidHistoryBackup() {
        for (kind in ChatKind.entries) {
            val root = folder.newFolder()
            val repo = ChatSessionRepository(root)
            val file = if (kind == ChatKind.Text) repo.chatFile else repo.visionFile
            file.writeText("""{"version":2,"sessions":[7]}""")
            File(root, file.name + ".bak").writeText(valid(kind))
            val ids = if (kind == ChatKind.Text) repo.loadChatSessions().map { it.id } else repo.loadVisionSessions().map { it.id }
            assertEquals(listOf("s"), ids)
        }
    }
    @Test fun validEmptyAndLegacyHistoriesRemainReadable() {
        assertTrue(SessionCodec.decodeChatSessions("[]").isEmpty())
        assertTrue(SessionCodec.decodeVisionSessions("""{"version":2,"sessions":[]}""").isEmpty())
        val legacy = """[{"timestamp":123,"messages":[{"text":"hello","isUser":true}]}]"""
        val oldText = SessionCodec.decodeChatSessions(legacy).single()
        assertEquals(123, oldText.updatedAt)
        assertEquals("hello", oldText.messages.first().text)
        assertEquals(2, oldText.messages.size) // Interrupted request is still recoverable.
        val oldVision = SessionCodec.decodeVisionSessions("""[{"question":"hello","answer":"reply","updated_at":123}]""").single()
        assertEquals(123, oldVision.updatedAt)
        assertEquals(listOf("hello", "reply"), oldVision.messages.map { it.text })
    }
    private fun chunk() = RagChunkRecord("c", "d", "notes.txt", 0, "Keep knowledge", floatArrayOf(1f, 0f, 0f))
    private fun ragJson() = InMemoryRagVectorStore.serializeRecords(listOf(chunk()), dimension = 3)
    private fun rag(root: File, algorithm: String = "universal-fnv1a-384") = InMemoryRagVectorStore(
        storageDir = root, storageFileName = "rag.json", expectedDimension = 3, algorithmId = algorithm)
    @Test fun malformedRagCannotBecomeWritableEmptyOrReindexStore() {
        fun mutate(block: (JSONObject) -> Unit) = JSONObject(ragJson()).also(block).toString()
        val damagedValues = listOf("", "{}", "null", "[]oops", ragJson() + "oops", """{"version":2,"records":"broken"}""") + listOf(
            mutate { it.put("records", JSONArray().put(7)) },
            mutate { it.getJSONArray("records").getJSONObject(0).remove("emb") },
            mutate { it.getJSONArray("records").getJSONObject(0).put("emb", JSONArray().put("1").put(0).put(0)) },
            mutate { it.getJSONArray("records").getJSONObject(0).put("emb", JSONArray().put(1e100).put(0).put(0)) },
            mutate { it.getJSONArray("records").getJSONObject(0).put("emb", JSONArray().put(1)) },
            mutate { it.getJSONArray("records").getJSONObject(0).put("text", 7) },
            mutate { it.put("version", "2") },
            mutate { it.put("dimension", "3") }
        )
        for (algorithm in listOf("universal-fnv1a-384", "different-algorithm")) for (damaged in damagedValues) {
            val root = folder.newFolder()
            val file = root.resolve("rag.json").apply { writeText(damaged) }
            val backup = root.resolve("rag.json.bak").apply { writeText(damaged) }
            val store = rag(root, algorithm)
            store.load()
            assertTrue(store.loadState.value is StoreLoadState.RecoveryRequired)
            assertFalse(store.isMarkedForReindexing)
            assertFalse(store.addChunks(listOf(chunk())))
            assertFalse(store.replaceDocument("d", listOf(chunk())))
            store.clearAll(); store.persist()
            assertEquals(damaged, file.readText()); assertEquals(damaged, backup.readText())
        }
    }
    @Test fun ragValidBackupAndExplicitRepairRestoreAccess() {
        val root = folder.newFolder()
        val file = root.resolve("rag.json").apply { writeText("{}") }
        val backup = root.resolve("rag.json.bak").apply { writeText(ragJson()) }
        val store = rag(root)
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.Ready)
        assertEquals("Keep knowledge", store.getRecentChunks(1).single().text)
        file.writeText("{}"); backup.writeText("{}")
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.RecoveryRequired)
        file.writeText(ragJson()); backup.writeText(ragJson())
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.Ready)
        assertTrue(store.addChunks(listOf(chunk().copy(id = "new", documentId = "new-doc"))))
    }
    @Test fun ragLegacyEmptyAndValidConfigurationMismatchRemainSupported() {
        assertTrue(InMemoryRagVectorStore.deserializeRecords("[]").isEmpty())
        assertTrue(InMemoryRagVectorStore.deserializeRecords("""{"version":1,"records":[]}""").isEmpty())
        val legacy = JSONObject(ragJson()).getJSONArray("records").toString()
        assertEquals("Keep knowledge", InMemoryRagVectorStore.deserializeRecords(legacy).single().text)
        val root = folder.newFolder()
        root.resolve("rag.json").writeText(ragJson())
        val store = rag(root, "different-algorithm")
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.Ready)
        assertTrue(store.isMarkedForReindexing)
    }
}

package com.shounak.localmeshai.features

import com.shounak.localmeshai.data.*
import com.shounak.localmeshai.ui.viewmodels.*
import com.shounak.localmeshai.utils.ActiveInputFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFeaturesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
    private fun chat(id: String) = ChatSession(id, id, listOf(ChatMessage(text = "hello", isUser = true)), 1)
    private fun vision(id: String) = VisionChatSession(id, id, "q", "a", 1, messages = listOf(VisionChatMessage(text = "q", isUser = true)))

    @Test fun editingKeepsEarlierTurnsAndOriginalAttachmentButRemovesFollowingTurns() {
        val old = listOf(ChatMessage(id = "u1", text = "First", isUser = true), ChatMessage(id = "a1", text = "Answer", isUser = false),
            ChatMessage(id = "u2", text = "Original", isUser = true, documentPath = "/notes.txt"), ChatMessage(id = "a2", text = "Later answer", isUser = false),
            ChatMessage(id = "u3", text = "Followup", isUser = true))
        val revised = ConversationEdits.text(old, "u2", " Edited ")
        assertEquals(listOf("u1", "a1", "u2"), revised.map { it.id })
        assertEquals("Edited", revised.last().text); assertEquals("/notes.txt", revised.last().documentPath)
        assertEquals("Original", old[2].text); assertEquals(5, old.size)
        rejects { ConversationEdits.text(old, "a1", "invalid") }
        rejects { ConversationEdits.text(old, "u2", "  ") }
    }
    @Test fun regenerationUsesOriginalPromptAndPreservesVisionMedia() {
        val prompt = VisionChatMessage(id = "u", text = "", isUser = true, imagePath = "/image.jpg", audioPath = "/audio.wav")
        val old = listOf(prompt, VisionChatMessage(text = "successful answer", isUser = false))
        assertEquals(listOf(prompt), ConversationEdits.vision(old, "u", null))
        assertEquals("New question", ConversationEdits.vision(old, "u", "New question").single().text)
        assertEquals("/image.jpg", ConversationEdits.vision(old, "u", "New question").single().imagePath)
        rejects { ConversationEdits.vision(old, "missing", null) }
    }
    @Test fun profileRestorePublishesOneBatchSoSelectedModelsCanRefresh() = runBlocking {
        val prefs = WorkspacePreferences(FeatureTestPreferences())
        val change = async(start = CoroutineStart.UNDISPATCHED) { prefs.profileChanges.first() }
        prefs.import(JSONObject().put("profile:first", ModelProfile("A").toJson().toString())
            .put("profile:selected", ModelProfile("B").toJson().toString())) { _, id -> id }
        assertEquals(setOf("first", "selected"), change.await())
    }
    @Test fun profilesPersistAllOverridesAndClearingRestoresDefaults() {
        val store = FeatureTestPreferences()
        val first = WorkspacePreferences(store)
        val profile = ModelProfile("Be concise", 4096, 0.2f, 0.7f, 20)
        first.setProfile("a", profile)
        first.setProfile("b", ModelProfile("Be detailed", 2048))
        val restarted = WorkspacePreferences(store)
        assertEquals(profile, restarted.profile("a"))
        assertEquals("Be detailed", restarted.profile("b").systemPrompt)
        restarted.setProfile("a", ModelProfile())
        assertEquals(ModelProfile(), restarted.profile("a"))
        assertEquals(2048, restarted.profile("b").contextTokens)
    }
    @Test fun invalidProfilesDoNotReplaceExistingPreferences() {
        val prefs = WorkspacePreferences(FeatureTestPreferences())
        prefs.setProfile("model", ModelProfile("Keep", 2048))
        listOf(ModelProfile(contextTokens = 100), ModelProfile(contextTokens = 300_000), ModelProfile(temperature = Float.NaN),
            ModelProfile(temperature = 3f), ModelProfile(topP = 0f), ModelProfile(topK = -1), ModelProfile(systemPrompt = "x".repeat(16_001))).forEach { profile ->
            rejects { prefs.setProfile("model", profile) }
            assertEquals(ModelProfile("Keep", 2048), prefs.profile("model"))
        }
        rejects { ModelProfile.fromJson(JSONObject().put("contextTokens", 512.5)) }
        rejects { ModelProfile.fromJson(JSONObject().put("topK", "40")) }
        rejects { ModelProfile.fromJson(JSONObject().put("token", "secret")) }
    }
    @Test fun renameAndPinAreIndependentAcrossChatTypesAndSurviveRestart() {
        val data = FeatureTestPreferences()
        val prefs = WorkspacePreferences(data)
        prefs.rename("text", "same", "New name"); prefs.togglePin("text", "same")
        val restarted = WorkspacePreferences(data)
        assertEquals("New name", restarted.title("text", "same", "original"))
        assertEquals("vision", restarted.title("vision", "same", "vision"))
        assertTrue(restarted.pinned("text", "same")); assertFalse(restarted.pinned("vision", "same"))
        rejects { restarted.rename("text", "same", "   ") }
        assertEquals("New name", restarted.title("text", "same", "original"))
        restarted.togglePin("text", "same"); assertFalse(restarted.pinned("text", "same"))
    }
    @Test fun portablePreferencesNeverExportUnknownKeysOrTokens() {
        val data = FeatureTestPreferences(mapOf("hf_read_token" to "SECRET", "model_uri" to "content://private", "reduce_motion" to true))
        val prefs = WorkspacePreferences(data)
        prefs.rename("text", "id", "Title"); prefs.setProfile("model", ModelProfile("custom"))
        val json = prefs.export()
        assertFalse(json.has("hf_read_token")); assertFalse(json.has("model_uri")); assertFalse(json.toString().contains("SECRET"))
        val target = WorkspacePreferences(FeatureTestPreferences())
        target.import(json) { kind, id -> "$kind-new-$id" }
        assertEquals("Title", target.title("text", "text-new-id", "fallback"))
        assertTrue(target.reduceMotion)
        assertEquals("custom", target.profile("model").systemPrompt)
    }
    @Test fun invalidImportDoesNotApplyEarlierValidPreferences() {
        val prefs = WorkspacePreferences(FeatureTestPreferences())
        rejects { prefs.import(JSONObject().put("reduce_motion", true).put("title:bad", "malformed")) { _, id -> id } }
        assertFalse(prefs.reduceMotion)
    }
    @Test fun contextEstimateCountsUnicodeCodepointsAndAllVisibleMessages() {
        assertEquals(ContextBudgets.estimate("abc"), ContextBudgets.estimate("😀😀😀"))
        val messages = List(20) { "A long message with many words" }
        val estimate = ContextBudgets.summarize(messages, "draft", "instructions", 2048)
        assertEquals(8, estimate.omittedMessages)
        assertTrue(estimate.estimatedTokens > ContextBudgets.summarize(messages.takeLast(12), "draft", "instructions", 2048).estimatedTokens)
        assertFalse(estimate.warning)
        assertTrue(ContextBudgets.summarize(listOf("x".repeat(4000)), "", "", 512).warning)
    }
    @Test fun sourcePassagesSurviveTextAndVisionCodecsAndLegacyMessagesDefaultToEmpty() {
        val passage = RagPassage("d", "File.pdf", 7, "Exact passage\nSecond line")
        val text = chat("t").copy(messages = listOf(ChatMessage(text = "answer", isUser = false, ragPassages = listOf(passage))))
        assertEquals(listOf(passage), SessionCodec.decodeChatSessions(SessionCodec.encodeChatSessions(listOf(text))).single().messages.single().ragPassages)
        val vision = vision("v").copy(messages = listOf(VisionChatMessage(text = "answer", isUser = false, ragPassages = listOf(passage))))
        assertEquals(listOf(passage), SessionCodec.decodeVisionSessions(SessionCodec.encodeVisionSessions(listOf(vision))).single().messages.single().ragPassages)
        assertTrue(SessionCodec.decodeChatSessions(SessionCodec.encodeChatSessions(listOf(chat("old")))).single().messages.all { it.ragPassages.isEmpty() })
    }
    @Test fun restoringBothHistoriesPreservesImportedChatsAcrossStaleSavesAndDeletion() = runBlocking {
        val repo = ChatSessionRepository(folder.newFolder())
        repo.saveChatSessions(listOf(chat("old"))); repo.saveVisionSessions(listOf(vision("old")))
        assertEquals(2, repo.mergeRestoredSessions(listOf(chat("imported")), listOf(vision("imported"))))
        assertTrue(repo.saveChatSessions(listOf(chat("old")))); assertTrue(repo.saveVisionSessions(listOf(vision("old"))))
        assertEquals(2, repo.loadChatSessions().size); assertEquals(2, repo.loadVisionSessions().size)
        assertEquals(0, repo.mergeRestoredSessions(listOf(chat("imported")), listOf(vision("imported"))))
        assertEquals(DeletionOutcome.Completed, repo.deleteSessionAsync(ChatKind.Text, "imported"))
        assertEquals(0, repo.mergeRestoredSessions(listOf(chat("imported")), emptyList()))
        assertEquals(listOf("old"), repo.loadChatSessions().map { it.id })
    }
    @Test fun partialRestoreCanRetryWithoutDuplicatingOrLosingCommittedText() = runBlocking {
        val root = folder.newFolder(); val repo = ChatSessionRepository(root)
        repo.saveChatSessions(listOf(chat("old"))); repo.saveVisionSessions(emptyList())
        val block = root.resolve("vision_sessions_v2.json.tmp").apply { mkdirs(); resolve("block").writeText("block") }
        try { repo.mergeRestoredSessions(listOf(chat("new")), listOf(vision("new"))); fail("Expected failed vision commit") } catch (_: IllegalStateException) { }
        assertTrue(repo.saveChatSessions(listOf(chat("old"))))
        assertEquals(2, repo.loadChatSessions().size)
        block.resolve("block").delete(); block.delete()
        assertEquals(1, repo.mergeRestoredSessions(listOf(chat("new")), listOf(vision("new"))))
        assertEquals(2, repo.loadChatSessions().size); assertEquals(1, repo.loadVisionSessions().size)
    }
    @Test fun temporaryAttachmentsNeverEnterSavedHistoryOrDurableAttachments() {
        val root = folder.newFolder(); val durable = ChatSessionRepository(root.resolve("files"))
        val store = ChatAttachmentStore(root.resolve("cache/temporary_chats/text"))
        val source = root.resolve("input.txt").apply { writeText("temporary bytes") }
        val file = store.storeAttachmentFromFile(ChatKind.Text, "temporary", sourceFile = source, targetFileName = "input.txt")
        assertTrue(file.path.contains("temporary_chats"))
        assertFalse(durable.attachmentStore.isOwnedAttachment(file)); assertTrue(durable.loadChatSessions().isEmpty())
    }
    @Test fun temporaryCleanupPreservesLiveConversationAndReadersThenRemovesAbandonedFiles() {
        val root = folder.newFolder(); val dir = root.resolve("text/chat").apply { mkdirs() }
        val file = dir.resolve("input.txt").apply { writeText("private"); setLastModified(1000L) }
        TemporaryChatFiles.retain(dir)
        assertEquals(0, TemporaryChatFiles.prune(root, 172_800_000L)); assertTrue(file.exists())
        val reader = ActiveInputFiles.protect(file)
        TemporaryChatFiles.discard(dir); assertTrue(file.exists())
        reader.close()
        assertTrue(TemporaryChatFiles.prune(root, 172_800_000L) > 0); assertFalse(file.exists())
    }
    @Test fun temporaryCleanupPreservesFreshAbandonedInputs() {
        val root = folder.newFolder(); val file = root.resolve("fresh.txt").apply { writeText("draft") }
        TemporaryChatFiles.prune(root, System.currentTimeMillis()); assertTrue(file.exists())
    }
    @Test fun storageCountsNestedDataAndFormatsUnits() {
        val root = folder.newFolder(); root.resolve("nested").mkdirs()
        root.resolve("a").writeBytes(ByteArray(10)); root.resolve("nested/b").writeBytes(ByteArray(20))
        assertEquals(30L, StorageDashboard.size(root)); assertEquals("1.0 KB", StorageDashboard.format(1024))
    }
}

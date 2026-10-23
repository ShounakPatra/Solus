package com.shounak.localmeshai.features

import android.content.Context
import android.content.ContextWrapper
import com.shounak.localmeshai.data.*
import com.shounak.localmeshai.memory.*
import com.shounak.localmeshai.ui.viewmodels.*
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AppSettingsData
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceBackupTest {
    @get:Rule val folder = TemporaryFolder()
    private class Workspace(root: File) {
        val files = root.resolve("files").apply { mkdirs() }
        val cache = root.resolve("cache").apply { mkdirs() }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        val repository = ChatSessionRepository(files)
        val memory = PersistentMemoryManager(memoryStore = PersistentMemoryStore(storageDir = files))
        val prefs = WorkspacePreferences(FeatureTestPreferences())
        private val settingsPrefs = FeatureTestPreferences()
        val settings = AppSettings(settingsPrefs, com.shounak.localmeshai.utils.HuggingFaceTokenStore(
            settingsPrefs, com.shounak.localmeshai.utils.TestTokenCipher()))
        val backup = WorkspaceBackup(context, repository, memory, prefs, settings)
    }
    private fun chat(id: String) = ChatSession(id, "Title $id", listOf(ChatMessage(id = "user-$id", text = "Original prompt", isUser = true)), 123)
    private fun vision(id: String) = VisionChatSession(id, "Vision $id", "Question", "Answer", 123,
        messages = listOf(VisionChatMessage(id = "vision-$id", text = "Question", isUser = true)))
    private fun archive(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun v600BackupRestoresIntoV601WithLegacySettingsSchema() = runBlocking {
        val source = Workspace(folder.newFolder())
        source.repository.saveChatSessions(listOf(chat("legacy")))
        source.memory.addMemory("A legacy memory", MemoryCategory.FACT)
        val bytes = ByteArrayOutputStream(); source.backup.exportTo(bytes, false)
        val stage = folder.newFolder(); WorkspaceBackup.readArchive(bytes.toByteArray().inputStream(), stage)
        val manifest = JSONObject(stage.resolve("manifest.json").readText()).put("settings", JSONObject("""
            {"dark":false,"dynamic":true,"telemetry":true,"thermalGuard":true,"ramGuard":true,
             "suggestions":true,"personas":true,"bench":true,"hideBottomBar":false,
             "autoUnloadMinutes":5,"autoUpdates":true,"backend":"AUTO","memory":true,
             "autoMemories":true,"rag":false,"ragTopK":3,"ragMinSimilarity":0.35}
        """.trimIndent()))
        val target = Workspace(folder.newFolder())
        target.backup.restoreFrom(archive("manifest.json" to manifest.toString()).inputStream())
        assertEquals(1, target.repository.loadChatSessions().size)
        assertEquals(1, target.memory.getAllMemories().size)
        assertFalse(target.settings.settings.value.enableDarkMode)
        assertTrue(target.settings.settings.value.enableSuggestionPills)
    }

    @Test fun fullRoundTripPreservesExistingDataAttachmentsProfilesAndCitationsWithoutCredentials() = runBlocking {
        val source = Workspace(folder.newFolder())
        val attachment = source.repository.attachmentStore.getAttachmentDir(ChatKind.Text, "original", "input", true).resolve("notes.txt").apply { writeText("Exact attached text") }
        val passage = RagPassage("doc", "notes.txt", 2, "Exact retrieved passage")
        val toolUse = com.shounak.localmeshai.ai.tools.ToolUse("calculator", "{\"expression\":\"2+2\"}", "4")
        val original = chat("original").copy(messages = listOf(
            ChatMessage(id = "u", text = "Question", isUser = true, documentPath = attachment.path),
            ChatMessage(id = "a", text = "Answer", isUser = false, userMessageId = "u", ragPassages = listOf(passage), toolUses = listOf(toolUse))))
        assertTrue(source.repository.saveChatSessions(listOf(original)))
        assertTrue(source.repository.saveVisionSessions(listOf(vision("vision"))))
        assertNotNull(source.memory.addMemory("Prefer concise answers"))
        source.prefs.rename("text", original.id, "Renamed")
        source.prefs.togglePin("text", original.id)
        source.prefs.setProfile("model", ModelProfile("Custom instructions", 2048, 0.5f, 0.9f, 30))
        source.settings.updateSettings { it.copy(huggingFaceToken = "SOURCE_SECRET", enableSuggestionPills = true, enableToolCalling = true) }
        val output = ByteArrayOutputStream()
        source.backup.exportTo(output, true)
        val stage = folder.newFolder()
        val entries = WorkspaceBackup.readArchive(output.toByteArray().inputStream(), stage)
        assertEquals(setOf("manifest.json", "attachments/0"), entries)
        assertFalse(stage.resolve("manifest.json").readText().contains("SOURCE_SECRET"))
        assertFalse(stage.resolve("manifest.json").readText().contains("huggingFaceToken"))
        val target = Workspace(folder.newFolder())
        assertTrue(target.repository.saveChatSessions(listOf(chat("existing"))))
        target.settings.updateSettings { it.copy(huggingFaceToken = "KEEP_TARGET_SECRET") }
        assertNotNull(target.memory.addMemory("Keep existing memory"))
        target.backup.restoreFrom(output.toByteArray().inputStream())
        val restored = target.repository.loadChatSessions().single { it.id != "existing" }
        assertNotEquals(original.id, restored.id)
        assertEquals("Exact attached text", File(restored.messages.first().documentPath!!).readText())
        assertEquals(listOf(passage), restored.messages.last().ragPassages)
        assertEquals(listOf(toolUse), restored.messages.last().toolUses)
        assertTrue(target.settings.settings.value.enableToolCalling)
        assertEquals(restored.messages.first().id, restored.messages.last().userMessageId)
        assertEquals("Renamed", target.prefs.title("text", restored.id, "fallback"))
        assertTrue(target.prefs.pinned("text", restored.id))
        assertEquals(2048, target.prefs.profile("model").contextTokens)
        assertTrue(target.settings.settings.value.enableSuggestionPills)
        assertEquals("KEEP_TARGET_SECRET", target.settings.settings.value.huggingFaceToken)
        assertEquals(2, target.memory.getAllMemories().size)
        assertEquals(1, target.repository.loadVisionSessions().size)
        // A queued pre-restore ViewModel snapshot cannot erase imported chats.
        assertTrue(target.repository.saveChatSessions(listOf(chat("existing"))))
        assertEquals(2, target.repository.loadChatSessions().size)
        // Repeated restore preserves edits and does not duplicate data.
        target.prefs.rename("text", restored.id, "Changed after restore")
        target.backup.restoreFrom(output.toByteArray().inputStream())
        assertEquals(2, target.repository.loadChatSessions().size)
        assertEquals(2, target.memory.getAllMemories().size)
        assertEquals("Changed after restore", target.prefs.title("text", restored.id, "fallback"))
        assertEquals(DeletionOutcome.Completed, target.repository.deleteSessionAsync(ChatKind.Text, restored.id))
        target.backup.restoreFrom(output.toByteArray().inputStream())
        assertEquals(listOf("existing"), target.repository.loadChatSessions().map { it.id })
        assertTrue(target.cache.listFiles().orEmpty().none { it.name.startsWith("backup_restore_") })
    }

    @Test fun failedHistoryCommitRemovesUncommittedAttachments() = runBlocking {
        val source = Workspace(folder.newFolder())
        val photo = source.repository.attachmentStore.storeAttachmentFromFile(ChatKind.Vision, "source",
            sourceFile = File(source.cache, "photo.jpg").apply { writeBytes(ByteArray(1024) { 1 }) })
        source.repository.saveVisionSessions(listOf(vision("source").copy(imagePath = photo.absolutePath)))
        val output = ByteArrayOutputStream(); source.backup.exportTo(output, true)
        val target = Workspace(folder.newFolder())
        // Reads remain healthy, but a nonempty temporary-file directory prevents commit after copying.
        File(target.files, "${target.repository.visionFile.name}.tmp").apply {
            mkdirs(); resolve("blocker").writeText("prevent atomic write")
        }
        try { target.backup.restoreFrom(output.toByteArray().inputStream()); fail("Expected restore failure") }
        catch (_: IllegalStateException) { }
        assertFalse(target.files.walkTopDown().any { it.isFile && it.name == "photo.jpg" })
    }

    @Test fun repeatedAttachmentReferencesShareOneOwnedCopy() = runBlocking {
        val source = Workspace(folder.newFolder())
        val image = source.repository.attachmentStore.storeAttachmentFromFile(ChatKind.Vision, "source",
            sourceFile = File(source.cache, "photo.jpg").apply { writeBytes(ByteArray(1024) { 1 }) })
        source.repository.saveVisionSessions(listOf(vision("source").copy(imagePath = image.absolutePath,
            messages = listOf(VisionChatMessage(id = "m", text = "Photo", isUser = true, imagePath = image.absolutePath)))))
        val output = ByteArrayOutputStream(); source.backup.exportTo(output, true)
        val target = Workspace(folder.newFolder())
        target.backup.restoreFrom(output.toByteArray().inputStream())
        val restored = target.repository.loadVisionSessions().single()
        assertEquals(restored.imagePath, restored.messages.first { it.isUser }.imagePath)
        assertTrue(File(restored.imagePath!!).isFile)
        target.backup.restoreFrom(output.toByteArray().inputStream())
        assertEquals(restored.imagePath, target.repository.loadVisionSessions().single().imagePath)
    }

    @Test fun duplicateMemoryWithDifferentCapitalizationDoesNotStopRemainingRestore() = runBlocking {
        val source = Workspace(folder.newFolder())
        source.repository.saveChatSessions(listOf(chat("source")))
        assertNotNull(source.memory.addMemory("Prefer concise answers", MemoryCategory.PREFERENCE))
        assertNotNull(source.memory.addMemory("I live in Mumbai", MemoryCategory.FACT))
        val output = ByteArrayOutputStream()
        source.backup.exportTo(output, false)
        val target = Workspace(folder.newFolder())
        assertNotNull(target.memory.addMemory("PREFER CONCISE ANSWERS", MemoryCategory.PREFERENCE))
        val status = target.backup.restoreFrom(output.toByteArray().inputStream())
        assertTrue(status.contains("1 already present"))
        assertEquals(2, target.memory.getAllMemories().size)
        assertTrue(target.memory.getAllMemories().any { it.content == "I live in Mumbai" })
        assertEquals(1, target.repository.loadChatSessions().size)
        target.backup.restoreFrom(output.toByteArray().inputStream())
        assertEquals(2, target.memory.getAllMemories().size)
    }

    @Test fun restoredMemoriesPublishImmediatelyAndSurviveRestartWithMetadata() = runBlocking {
        val source = Workspace(folder.newFolder())
        val original = MemoryEntry("disabled", "Use short answers", MemoryCategory.INSTRUCTION, false, 123L, 456L)
        assertTrue(source.memory.memoryStore.add(original))
        val output = ByteArrayOutputStream()
        source.backup.exportTo(output, false)
        val target = Workspace(folder.newFolder())
        val observed = target.memory.memories
        assertTrue(observed.value.isEmpty())
        target.backup.restoreFrom(output.toByteArray().inputStream())
        val restored = observed.value.single()
        assertEquals(original.copy(id = restored.id), restored)
        assertNotEquals(original.id, restored.id)
        assertEquals(listOf(restored), PersistentMemoryStore(storageDir = target.files).getAll())
        assertTrue(target.memory.getActiveMemories().isEmpty())
    }

    @Test fun sameBackupCanRestoreDeletedMemoriesWhenItsChatsAlreadyExist() = runBlocking {
        val source = Workspace(folder.newFolder())
        source.repository.saveChatSessions(listOf(chat("source")))
        source.memory.addMemory("Keep this fact", MemoryCategory.FACT)
        val output = ByteArrayOutputStream()
        source.backup.exportTo(output, false)
        val target = Workspace(folder.newFolder())
        target.backup.restoreFrom(output.toByteArray().inputStream())
        target.memory.clearAllMemories()
        assertTrue(target.memory.memories.value.isEmpty())
        target.backup.restoreFrom(output.toByteArray().inputStream())
        assertEquals(1, target.repository.loadChatSessions().size)
        assertEquals("Keep this fact", target.memory.memories.value.single().content)
    }

    @Test fun excludingAttachmentsClearsOldPathsAndDoesNotCopyWeights() = runBlocking {
        val source = Workspace(folder.newFolder())
        val model = source.files.resolve("model.gguf").apply { writeText("weights") }
        assertTrue(source.repository.saveChatSessions(listOf(chat("source").copy(messages = listOf(ChatMessage(text = "Hi", isUser = true, documentPath = model.path))))))
        val output = ByteArrayOutputStream()
        source.backup.exportTo(output, true)
        val stage = folder.newFolder()
        assertEquals(setOf("manifest.json"), WorkspaceBackup.readArchive(output.toByteArray().inputStream(), stage))
        val target = Workspace(folder.newFolder())
        target.backup.restoreFrom(output.toByteArray().inputStream())
        assertNull(target.repository.loadChatSessions().single().messages.single { it.isUser }.documentPath)
    }

    @Test fun optionalAttachmentsAreNotCopiedWhenDisabled() = runBlocking {
        val source = Workspace(folder.newFolder())
        val file = source.repository.attachmentStore.getAttachmentDir(ChatKind.Text, "source", "input", true).resolve("notes.txt").apply { writeText("private") }
        assertTrue(source.repository.saveChatSessions(listOf(chat("source").copy(messages = listOf(ChatMessage(text = "Hi", isUser = true, documentPath = file.path))))))
        val output = ByteArrayOutputStream(); source.backup.exportTo(output, false)
        val stage = folder.newFolder()
        assertEquals(setOf("manifest.json"), WorkspaceBackup.readArchive(output.toByteArray().inputStream(), stage))
    }

    @Test fun malformedRestoreDoesNotCommitChatsOrSettings() = runBlocking {
        val source = Workspace(folder.newFolder()); source.repository.saveChatSessions(listOf(chat("source")))
        val output = ByteArrayOutputStream(); source.backup.exportTo(output, false)
        val stage = folder.newFolder(); WorkspaceBackup.readArchive(output.toByteArray().inputStream(), stage)
        val manifest = JSONObject(stage.resolve("manifest.json").readText())
        manifest.getJSONObject("preferences").put("title:invalid", "invalid")
        val target = Workspace(folder.newFolder()); target.repository.saveChatSessions(listOf(chat("keep")))
        try { target.backup.restoreFrom(archive("manifest.json" to manifest.toString()).inputStream()); fail("Expected validation failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf("keep"), target.repository.loadChatSessions().map { it.id })
        assertTrue(target.cache.listFiles().orEmpty().isEmpty())
    }
    @Test fun emptyUnavailableAttachmentsDoNotMakeAnUnrestorableBackup() = runBlocking {
        val source = Workspace(folder.newFolder())
        val file = source.repository.attachmentStore.getAttachmentDir(ChatKind.Text, "s", "i", true).resolve("empty.txt").apply { writeText("") }
        source.repository.saveChatSessions(listOf(chat("s").copy(messages = listOf(ChatMessage(text = "Question", isUser = true, documentPath = file.path)))))
        val output = ByteArrayOutputStream(); source.backup.exportTo(output, true)
        val target = Workspace(folder.newFolder()); target.backup.restoreFrom(output.toByteArray().inputStream())
        assertNull(target.repository.loadChatSessions().single().messages.single { it.isUser }.documentPath)
    }
    @Test fun corruptedDestinationIsNeverOverwrittenByRestore() = runBlocking {
        val source = Workspace(folder.newFolder()); source.repository.saveChatSessions(listOf(chat("source")))
        val output = ByteArrayOutputStream(); source.backup.exportTo(output, false)
        val target = Workspace(folder.newFolder()); target.repository.chatFile.writeText("{ corrupt history")
        try { target.backup.restoreFrom(output.toByteArray().inputStream()); fail("Restore must stop") } catch (_: IllegalStateException) { }
        assertEquals("{ corrupt history", target.repository.chatFile.readText())
        assertTrue(target.memory.getAllMemories().isEmpty())
    }

    @Test fun archiveRejectsPathTraversalAndUnexpectedFiles() {
        listOf("../escape", "attachments/../../escape", "models/model.gguf", "/absolute", "attachments/0/child").forEach { name ->
            rejects { WorkspaceBackup.readArchive(archive("manifest.json" to "{}", name to "payload").inputStream(), folder.newFolder()) }
        }
    }
    @Test fun archiveRequiresManifest() { rejects { WorkspaceBackup.readArchive(archive("attachments/0" to "data").inputStream(), folder.newFolder()) } }
    @Test fun attachmentAllowlistRejectsSettingsAndModels() {
        val root = folder.newFolder(); val cache = folder.newFolder()
        assertFalse(WorkspaceBackup.isBackupAttachment(root.resolve("preferences.json"), root, cache))
        assertFalse(WorkspaceBackup.isBackupAttachment(root.resolve("chat_attachments/model.gguf"), root, cache))
        assertTrue(WorkspaceBackup.isBackupAttachment(root.resolve("chat_attachments/text/id/document.txt"), root, cache))
        assertFalse(WorkspaceBackup.inside(root.resolve("../sibling/data"), root))
    }
    @Test fun malformedMetadataAndSecretsAreRejectedBeforeImport() {
        listOf(JSONObject().put("title:invalid", "title"), JSONObject().put("pin:other:id", true),
            JSONObject().put("title:text:", "title"), JSONObject().put("pin:text:id", "true"),
            JSONObject().put("huggingFaceToken", "secret"), JSONObject().put("profile:", "{}")).forEach { json -> rejects { WorkspaceBackup.validatePreferences(json) } }
    }
    @Test fun strictSessionValidationRejectsBooleanCoercionAndDuplicateMessages() {
        val valid = JSONObject(SessionCodec.encodeChatSessions(listOf(chat("s"))))
        WorkspaceBackup.validateSessions(valid)
        val session = valid.getJSONArray("sessions").getJSONObject(0)
        val turn = session.getJSONArray("messages").getJSONObject(0)
        turn.put("isUser", "true")
        rejects { WorkspaceBackup.validateSessions(valid) }
        turn.put("isUser", true); session.getJSONArray("messages").put(JSONObject(turn.toString()))
        rejects { WorkspaceBackup.validateSessions(valid) }
    }
    @Test fun portableSettingsRoundTripAllNonSecretOptionsAndKeepDestinationToken() {
        val source = AppSettingsData(enableDarkMode = false, enableDynamicThemes = false, showThermalGuard = false,
            showRamGuard = false, enableTelemetryBar = false, enableSuggestionPills = true, enablePersonaPresets = false,
            enableSolusBench = false, enableAutoHideBottomBar = true, autoUnloadMinutes = 10, autoCheckUpdates = false,
            llamaBackendPreference = "CPU", enableRag = true, ragTopK = 5, ragMinSimilarity = 0.6f,
            enablePersistentMemory = false, autoExtractMemories = false, huggingFaceToken = "SOURCE")
        val json = PortableSettings.encode(source)
        assertFalse(json.toString().contains("SOURCE"))
        assertEquals(source.copy(huggingFaceToken = "DEST"), PortableSettings.decode(json, AppSettingsData(huggingFaceToken = "DEST")))
        assertEquals("DEST", PortableSettings.decode(JSONObject(json.toString()).put("huggingFaceToken", "bad"),
            AppSettingsData(huggingFaceToken = "DEST")).huggingFaceToken)
        rejects { PortableSettings.decode(JSONObject(json.toString()).put("ragTopK", 1.2), AppSettingsData()) }
    }
}

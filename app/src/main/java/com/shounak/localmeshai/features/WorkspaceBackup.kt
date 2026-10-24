package com.shounak.localmeshai.features

import android.content.Context
import android.net.Uri
import com.shounak.localmeshai.data.ChatKind
import com.shounak.localmeshai.data.ChatSessionRepository
import com.shounak.localmeshai.data.SessionCodec
import com.shounak.localmeshai.data.StoreLoadState
import com.shounak.localmeshai.memory.MemoryCategory
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.PersistentMemoryManager
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AtomicReadResult
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable user data only. Never archives raw preferences, model weights, tokens or deletion journals. */
class WorkspaceBackup internal constructor(
    private val context: Context,
    private val repository: ChatSessionRepository,
    private val memory: PersistentMemoryManager,
    private val preferences: WorkspacePreferences,
    private val appSettings: AppSettings
) {
    constructor(context: Context) : this(context, ChatSessionRepository.getInstance(context),
        PersistentMemoryManager.getInstance(context), WorkspacePreferences.get(context), AppSettings.getInstance(context))

    suspend fun export(uri: Uri, includeAttachments: Boolean): String = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Could not open backup destination")
        output.use { exportTo(it, includeAttachments) }
    }
    internal suspend fun exportTo(output: java.io.OutputStream, includeAttachments: Boolean): String = withContext(Dispatchers.IO) {
        check(!ModelRuntimeCoordinator.isRuntimeBusy.value) { "Finish the active model operation before exporting" }
        val text = (repository.loadChatSessionsAsync() as? AtomicReadResult.Success)?.data
            ?: error("Text history requires recovery before export")
        val vision = (repository.loadVisionSessionsAsync() as? AtomicReadResult.Success)?.data
            ?: error("Vision history requires recovery before export")
        check(memory.loadAsync() is StoreLoadState.Ready) { "Memory requires recovery before export" }
        val textJson = JSONObject(SessionCodec.encodeChatSessions(text))
        val visionJson = JSONObject(SessionCodec.encodeVisionSessions(vision))
        val attachments = linkedMapOf<String, File>()
        val referencedPaths = mutableSetOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) visit(value.get(i))
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.get(key)
                    if (key in PATH_FIELDS && child is String && child.isNotBlank()) {
                        referencedPaths.add(child)
                        if (includeAttachments) {
                            val file = File(child).canonicalFile
                            if (file.isFile && file.length() > 0 && isBackupAttachment(file, context.filesDir, context.cacheDir)) attachments[child] = file
                        }
                    } else visit(child)
                }
            }
        }
        visit(textJson); visit(visionJson)
        require(attachments.size <= 5_000 && attachments.values.sumOf { it.length() } <= MAX_BYTES) { "Attachments exceed the 256 MB backup limit" }
        val mapping = JSONObject()
        attachments.keys.forEachIndexed { index, path -> mapping.put(path, "attachments/$index") }
        val memories = JSONArray().apply { memory.getAllMemories().forEach { entry -> put(JSONObject().apply {
            put("id", entry.id); put("content", entry.content); put("category", entry.category.name)
            put("enabled", entry.isEnabled); put("createdAt", entry.createdAt); put("updatedAt", entry.updatedAt)
        }) } }
        val settings = appSettings.settings.value
        val manifest = JSONObject().apply {
            put("format", "solus-backup"); put("version", 1)
            put("text", textJson); put("vision", visionJson); put("memories", memories)
            put("preferences", preferences.export()); put("attachments", mapping)
            put("settings", PortableSettings.encode(settings))
            put("skills", com.shounak.localmeshai.ai.tools.ToolSkillStore.get(context).export()
                .put("reminders", com.shounak.localmeshai.ai.tools.ToolReminders.records(context)))
        }.toString().toByteArray(Charsets.UTF_8)
        writeArchive(output, manifest, attachments.mapKeys { (path, _) -> mapping.getString(path) })
        "Exported ${text.size + vision.size} chats, ${memories.length()} memories and ${attachments.size} attachments. " +
            (if (includeAttachments && referencedPaths.size > attachments.size) "${referencedPaths.size - attachments.size} unavailable or unsupported attachments were omitted. " else "") +
            "Model weights and access tokens were excluded."
    }

    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open backup")
        input.use { restoreFrom(it) }
    }
    internal suspend fun restoreFrom(input: java.io.InputStream): String = withContext(Dispatchers.IO) {
        check(!ModelRuntimeCoordinator.isRuntimeBusy.value) { "Finish the active model operation before restoring" }
        val stage = File(context.cacheDir, "backup_restore_${UUID.randomUUID()}").apply { check(mkdirs()) }
        val copied = mutableMapOf<Triple<ChatKind, String, String>, File>()
        val committed = mutableSetOf<Pair<ChatKind, String>>()
        try {
            val seen = readArchive(input, stage)
            val manifest = JSONObject(stage.resolve("manifest.json").readText())
            require(manifest.getString("format") == "solus-backup" && manifest.getInt("version") == 1) { "Unsupported backup format" }
            val textJson = manifest.getJSONObject("text")
            val visionJson = manifest.getJSONObject("vision")
            validateSessions(textJson); validateSessions(visionJson)
            val originalText = SessionCodec.decodeChatSessions(textJson.toString())
            val originalVision = SessionCodec.decodeVisionSessions(visionJson.toString())
            val portable = manifest.getJSONObject("preferences")
            validatePreferences(portable)
            val settings = manifest.getJSONObject("settings")
            PortableSettings.decode(settings, appSettings.settings.value)
            val skillData = if (manifest.has("skills")) manifest.getJSONObject("skills") else null
            skillData?.let { data ->
                com.shounak.localmeshai.ai.tools.ToolSkillStore.validate(data)
                val reminders = data.getJSONArray("reminders"); require(reminders.length() <= 64)
                for (i in 0 until reminders.length()) com.shounak.localmeshai.ai.tools.ToolReminders.validate(reminders.getJSONObject(i))
            }
            val rawMemories = manifest.getJSONArray("memories")
            require(rawMemories.length() <= 10_000)
            val memories = (0 until rawMemories.length()).map { index -> rawMemories.getJSONObject(index).let { item ->
                val content = item.getString("content")
                require(item.get("content") is String && item.get("id") is String && item.get("enabled") is Boolean)
                require(content.isNotBlank() && content.length <= 20_000 && item.getString("id").isNotBlank())
                require(item.getLong("createdAt") >= 0 && item.getLong("updatedAt") >= 0)
                MemoryEntry(item.getString("id"), content, MemoryCategory.valueOf(item.getString("category")),
                    item.getBoolean("enabled"), item.getLong("createdAt"), item.getLong("updatedAt"))
            } }
            val assets = manifest.getJSONObject("attachments")
            assets.keys().forEach { key ->
                require(assets.get(key) is String && assets.getString(key) in seen && assets.getString(key) != "manifest.json") { "Backup attachment is missing" }
                require(stage.resolve(assets.getString(key)).length() > 0) { "Backup attachment is empty" }
            }
            // Hash the portable manifest rather than ZIP headers, making repeated restore idempotent.
            val backupId = MessageDigest.getInstance("SHA-256").digest(manifest.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            fun remap(kind: String, id: String) = UUID.nameUUIDFromBytes("$backupId:$kind:$id".toByteArray()).toString()
            check(repository.loadChatSessionsAsync() is AtomicReadResult.Success && repository.loadVisionSessionsAsync() is AtomicReadResult.Success) { "History requires recovery before import" }
            check(memory.loadAsync() is StoreLoadState.Ready) { "Memory requires recovery before import" }
            val existingText = repository.loadChatSessions().map { it.id }.toSet()
            val existingVision = repository.loadVisionSessions().map { it.id }.toSet()
            fun attachment(path: String?, kind: ChatKind, session: String): String? {
                if (path.isNullOrBlank() || !assets.has(path)) return null
                val entry = assets.getString(path)
                return copied.getOrPut(Triple(kind, session, entry)) {
                    repository.attachmentStore.storeAttachmentFromFile(kind, session,
                        sourceFile = stage.resolve(entry), targetFileName = File(path).name)
                }.absolutePath
            }
            val text = originalText.map { session ->
                val id = remap("text", session.id)
                if (id in existingText || repository.isTombstoned(ChatKind.Text, id)) return@map session.copy(id = id)
                session.copy(id = id, documentId = null, messages = session.messages.map { message -> message.copy(
                    id = remap("message", message.id), userMessageId = message.userMessageId?.let { remap("message", it) },
                    documentPath = attachment(message.documentPath, ChatKind.Text, id), progress = null, isThinkingActive = false
                ) })
            }
            val vision = originalVision.map { session ->
                val id = remap("vision", session.id)
                if (id in existingVision || repository.isTombstoned(ChatKind.Vision, id)) return@map session.copy(id = id)
                session.copy(id = id, documentId = null,
                    imagePath = attachment(session.imagePath, ChatKind.Vision, id), audioPath = attachment(session.audioPath, ChatKind.Vision, id),
                    messages = session.messages.map { message -> message.copy(
                        id = remap("message", message.id), userMessageId = message.userMessageId?.let { remap("message", it) },
                        imagePath = attachment(message.imagePath, ChatKind.Vision, id), audioPath = attachment(message.audioPath, ChatKind.Vision, id),
                        documentPath = attachment(message.documentPath, ChatKind.Vision, id), progress = null, isThinkingActive = false
                    ) })
            }
            val imported = repository.mergeRestoredSessions(text, vision) { kind, ids -> ids.forEach { committed.add(kind to it) } }
            var memoryCount = 0
            var skippedMemories = 0
            for (entry in memories) {
                val id = remap("memory", entry.id)
                if (memory.getAllMemories().any { it.id == id || it.content.trim().equals(entry.content.trim(), ignoreCase = true) }) {
                    skippedMemories++
                    continue
                }
                check(memory.memoryStore.add(entry.copy(id = id))) { "Chats restored, but memory import failed. Retry this backup to finish." }
                memoryCount++
            }
            appSettings.updateSettings { old -> PortableSettings.decode(settings, old) }
            preferences.import(portable, ::remap)
            skillData?.let { data ->
                com.shounak.localmeshai.ai.tools.ToolSkillStore.get(context).merge(data)
                com.shounak.localmeshai.ai.tools.ToolReminders.importPaused(context, data.getJSONArray("reminders"))
            }
            "Restored $imported new chats and $memoryCount memories ($skippedMemories already present). Existing chats were preserved. Models and document indexes are not part of backups. Restored reminders stay paused until enabled in Manage skills."
        } finally {
            copied.forEach { (key, file) ->
                if ((key.first to key.second) !in committed) {
                    file.delete()
                    file.parentFile?.delete() // only the empty, newly allocated attachment directory
                }
            }
            check(inside(stage, context.cacheDir))
            stage.deleteRecursively()
        }
    }

    companion object {
        const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_MANIFEST = 32 * 1024 * 1024
        private val PATH_FIELDS = setOf("documentPath", "imagePath", "audioPath")
        internal fun inside(child: File, root: File) = child.canonicalPath.startsWith(root.canonicalPath + File.separator)
        internal fun validateSessions(json: JSONObject) {
            require(json.getInt("version") in 1..SessionCodec.CURRENT_VERSION) { "Unsupported history schema" }
            val sessions = json.getJSONArray("sessions")
            require(sessions.length() <= 10_000)
            val ids = mutableSetOf<String>()
            var messages = 0
            for (i in 0 until sessions.length()) {
                val session = sessions.getJSONObject(i)
                require(session.getString("id").isNotBlank() && ids.add(session.getString("id"))) { "Invalid or duplicate chat ID" }
                require(session.get("id") is String && session.get("title") is String && session.get("updatedAt") is Number)
                val turns = session.getJSONArray("messages")
                val messageIds = mutableSetOf<String>()
                messages += turns.length(); require(messages <= 100_000)
                for (j in 0 until turns.length()) {
                    val turn = turns.getJSONObject(j)
                    require(turn.get("isUser") is Boolean && turn.get("text") is String)
                    require(turn.getString("text").length <= 1_000_000)
                    require(turn.get("id") is String && turn.getString("id").isNotBlank() && messageIds.add(turn.getString("id"))) { "Invalid or duplicate message ID" }
                    if (turn.has("toolUses")) com.shounak.localmeshai.ai.tools.ToolUseCodec.decode(turn.getJSONArray("toolUses"))
                    RagPassage.decode(turn.optJSONArray("ragPassages")).forEach { passage ->
                        require(passage.documentId.isNotBlank() && passage.chunkIndex >= 0 && passage.text.length <= 1_000_000)
                    }
                }
            }
        }
        internal fun validatePreferences(json: JSONObject) {
            json.keys().forEach { key ->
                when {
                    key.startsWith("profile:") -> {
                        require(key.removePrefix("profile:").isNotBlank() && json.get(key) is String)
                        ModelProfile.fromJson(JSONObject(json.getString(key)))
                    }
                    key.startsWith("title:") || key.startsWith("pin:") -> {
                        val parts = key.split(':', limit = 3)
                        require(parts.size == 3 && parts[1] in setOf("text", "vision") && parts[2].isNotBlank()) { "Invalid conversation metadata" }
                        if (parts[0] == "title") require(json.get(key) is String && json.getString(key).isNotBlank() && json.getString(key).length <= 120)
                        else require(json.get(key) is Boolean)
                    }
                    key in setOf("reduce_motion", "large_controls") -> require(json.get(key) is Boolean)
                    else -> throw IllegalArgumentException("Unsupported backup preference")
                }
            }
        }
        internal fun isBackupAttachment(file: File, filesDir: File, cacheDir: File): Boolean =
            listOf(filesDir.resolve("chat_attachments"), filesDir.resolve("vision_sessions"),
                cacheDir.resolve("camera_inputs"), cacheDir.resolve("multimodal_inputs"),
                cacheDir.resolve("vision_questions"), cacheDir.resolve("image_previews")).any { inside(file, it) } &&
                file.extension.lowercase() !in setOf("gguf", "task", "litertlm", "bin")

        internal fun writeArchive(output: java.io.OutputStream, manifest: ByteArray, attachments: Map<String, File>) {
            output.use { destination ->
                require(manifest.size <= MAX_MANIFEST) { "Chat data exceeds the 32 MB manifest limit" }
                require(attachments.size <= 5_000 && manifest.size + attachments.values.sumOf { it.length() } <= MAX_BYTES) { "Backup exceeds 256 MB" }
                ZipOutputStream(destination).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                    var total = manifest.size.toLong()
                    attachments.forEach { (name, file) ->
                        require(name.matches(Regex("attachments/[0-9]+")))
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                total += count; require(total <= MAX_BYTES) { "Attachment changed; backup exceeds 256 MB" }
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        internal fun readArchive(input: java.io.InputStream, stage: File): Set<String> {
            val seen = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && (entry.name == "manifest.json" || entry.name.matches(Regex("attachments/[0-9]+")))) { "Unexpected backup entry" }
                    require(seen.add(entry.name) && seen.size <= 5_001) { "Duplicate or excessive backup entries" }
                    val target = stage.resolve(entry.name).canonicalFile
                    require(inside(target, stage)) { "Unsafe backup path" }
                    target.parentFile!!.mkdirs()
                    var size = 0L
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer); if (count < 0) break
                            size += count; total += count
                            require(total <= MAX_BYTES && (entry.name != "manifest.json" || size <= MAX_MANIFEST)) { "Backup is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require("manifest.json" in seen) { "Backup manifest is missing" }
            return seen
        }
    }
}

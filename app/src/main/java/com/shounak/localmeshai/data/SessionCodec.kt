package com.shounak.localmeshai.data

import com.shounak.localmeshai.ai.EmptyResponseReason
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object SessionCodec {
    const val CURRENT_VERSION = 2
    const val INTERRUPTED_ERROR_MESSAGE = "Generation was interrupted; retry available."

    fun encodeChatSessions(sessions: List<ChatSession>, version: Int = CURRENT_VERSION): String {
        val root = JSONObject()
        root.put("version", version)
        val sessionsArray = JSONArray()

        sessions.forEach { session ->
            val sObj = JSONObject()
            sObj.put("id", session.id)
            sObj.put("title", session.title)
            sObj.put("updatedAt", session.updatedAt)
            sObj.put("documentId", session.documentId ?: "")

            val messagesArray = JSONArray()
            session.messages.forEach { msg ->
                val mObj = JSONObject()
                mObj.put("id", msg.id)
                mObj.put("text", if (msg.isUser) msg.text else ModelOutputSanitizer.clean(msg.text))
                mObj.put("isUser", msg.isUser)
                if (!msg.userMessageId.isNullOrBlank()) mObj.put("userMessageId", msg.userMessageId)
                if (!msg.requestId.isNullOrBlank()) mObj.put("requestId", msg.requestId)
                mObj.put("attemptId", msg.attemptId)
                if (!msg.reasoningText.isNullOrBlank()) mObj.put("reasoningText", msg.reasoningText)
                if (msg.terminalStatus != null) mObj.put("terminalStatus", msg.terminalStatus.name)
                if (!msg.errorMessage.isNullOrBlank()) mObj.put("errorMessage", msg.errorMessage)
                if (msg.emptyReason != null) mObj.put("emptyReason", msg.emptyReason.toString())
                if (!msg.documentName.isNullOrBlank()) mObj.put("documentName", msg.documentName)
                if (!msg.documentPath.isNullOrBlank()) mObj.put("documentPath", msg.documentPath)

                if (msg.ragSources.isNotEmpty()) {
                    val ragArr = JSONArray()
                    msg.ragSources.forEach { ragArr.put(it) }
                    mObj.put("ragSources", ragArr)
                    mObj.put("ragChunkCount", msg.ragChunkCount)
                    mObj.put("ragTopMatchPct", msg.ragTopMatchPct)
                }
                messagesArray.put(mObj)
            }
            sObj.put("messages", messagesArray)
            sessionsArray.put(sObj)
        }
        root.put("sessions", sessionsArray)
        return root.toString()
    }

    fun decodeChatSessions(rawJson: String): List<ChatSession> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()

        return if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            if (root.has("version")) {
                val version = root.optInt("version", CURRENT_VERSION)
                if (version > CURRENT_VERSION) {
                    throw com.shounak.localmeshai.utils.UnsupportedVersionException(version, CURRENT_VERSION)
                }
            }
            val isLegacy = !root.has("version")
            val sessionsArray = root.optJSONArray("sessions") ?: JSONArray()
            parseChatSessionsArray(sessionsArray, isLegacy = isLegacy)
        } else if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            parseChatSessionsArray(array, isLegacy = true)
        } else {
            emptyList()
        }
    }

    fun decodeLegacyChatSessions(rawJson: String): List<ChatSession> = decodeChatSessions(rawJson)

    private fun parseChatSessionsArray(array: JSONArray, isLegacy: Boolean = false): List<ChatSession> {
        val sessions = mutableListOf<ChatSession>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val sessionId = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            val title = item.optString("title").ifBlank { "New chat" }
            val rawUpdatedAt = when {
                item.has("updatedAt") -> item.optLong("updatedAt", 0L)
                item.has("updated_at") -> item.optLong("updated_at", 0L)
                item.has("timestamp") -> item.optLong("timestamp", 0L)
                else -> 0L
            }
            val updatedAt = if (rawUpdatedAt > 0L) rawUpdatedAt else System.currentTimeMillis()
            val documentId = item.optString("documentId").takeIf { it.isNotBlank() }

            val messagesJson = item.optJSONArray("messages") ?: JSONArray()
            val parsedMessages = mutableListOf<ChatMessage>()
            var lastUserMsgId: String? = null

            for (j in 0 until messagesJson.length()) {
                val mObj = messagesJson.optJSONObject(j) ?: continue
                val id = mObj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val isUser = mObj.optBoolean("isUser", false)
                val text = mObj.optString("text").orEmpty()
                val cleanText = if (isUser) text else ModelOutputSanitizer.clean(text)

                val userMessageId = mObj.optString("userMessageId").takeIf { it.isNotBlank() }
                    ?: if (!isUser) lastUserMsgId else null
                val requestId = mObj.optString("requestId").takeIf { it.isNotBlank() }
                val attemptId = mObj.optInt("attemptId", 1)
                val reasoningText = mObj.optString("reasoningText").takeIf { it.isNotBlank() }
                val docName = mObj.optString("documentName").takeIf { it.isNotBlank() }
                val docPath = mObj.optString("documentPath").takeIf { it.isNotBlank() }

                val statusStr = mObj.optString("terminalStatus").takeIf { it.isNotBlank() }
                val errorMsg = mObj.optString("errorMessage").takeIf { it.isNotBlank() }
                val emptyReasonStr = mObj.optString("emptyReason").takeIf { it.isNotBlank() }

                val emptyReason = when (emptyReasonStr) {
                    "NoVisibleAnswer" -> EmptyResponseReason.NoVisibleAnswer
                    "ReasoningOnly" -> EmptyResponseReason.ReasoningOnly
                    "AttachmentUnavailable" -> EmptyResponseReason.AttachmentUnavailable
                    else -> null
                }

                val terminalStatus = when (statusStr) {
                    RequestTerminalStatus.Completed.name -> RequestTerminalStatus.Completed
                    RequestTerminalStatus.Stopped.name -> RequestTerminalStatus.Stopped
                    RequestTerminalStatus.Failed.name -> RequestTerminalStatus.Failed
                    else -> {
                        if (isLegacy && !isUser) {
                            if (cleanText.isNotBlank() && errorMsg == null) {
                                RequestTerminalStatus.Completed
                            } else if (errorMsg != null) {
                                RequestTerminalStatus.Failed
                            } else {
                                null
                            }
                        } else null
                    }
                }

                val ragSourcesJson = mObj.optJSONArray("ragSources")
                val ragSources = if (ragSourcesJson != null) {
                    List(ragSourcesJson.length()) { k -> ragSourcesJson.optString(k, "") }.filter { it.isNotBlank() }
                } else emptyList()
                val ragChunkCount = mObj.optInt("ragChunkCount", 0)
                val ragTopMatchPct = mObj.optInt("ragTopMatchPct", 0)

                if (isUser) {
                    lastUserMsgId = id
                }

                parsedMessages.add(
                    ChatMessage(
                        text = cleanText,
                        isUser = isUser,
                        id = id,
                        ragSources = ragSources,
                        ragChunkCount = ragChunkCount,
                        ragTopMatchPct = ragTopMatchPct,
                        documentName = docName,
                        documentPath = docPath,
                        terminalStatus = terminalStatus,
                        errorMessage = errorMsg,
                        requestId = requestId,
                        attemptId = attemptId,
                        reasoningText = reasoningText,
                        userMessageId = userMessageId,
                        emptyReason = emptyReason
                    )
                )
            }

            // Normalization: Preserve incomplete turns and represent them as failed
            val normalizedMessages = normalizeChatMessages(parsedMessages)
            sessions.add(
                ChatSession(
                    id = sessionId,
                    title = title,
                    messages = normalizedMessages,
                    updatedAt = updatedAt,
                    documentId = documentId
                )
            )
        }
        return sessions
    }

    private fun normalizeChatMessages(messages: MutableList<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        for (i in 0 until messages.size) {
            val msg = messages[i]
            if (!msg.isUser && msg.terminalStatus == null) {
                val userMsg = messages.take(i).lastOrNull { it.isUser }
                messages[i] = msg.copy(
                    terminalStatus = RequestTerminalStatus.Failed,
                    errorMessage = msg.errorMessage ?: INTERRUPTED_ERROR_MESSAGE,
                    userMessageId = msg.userMessageId ?: userMsg?.id
                )
            }
        }

        val last = messages.last()
        if (last.isUser) {
            // Interrupted user turn without assistant reply
            messages.add(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = "",
                    isUser = false,
                    terminalStatus = RequestTerminalStatus.Failed,
                    errorMessage = INTERRUPTED_ERROR_MESSAGE,
                    userMessageId = last.id,
                    attemptId = 1
                )
            )
        }
        return messages
    }

    fun encodeVisionSessions(sessions: List<VisionChatSession>, version: Int = CURRENT_VERSION): String {
        val root = JSONObject()
        root.put("version", version)
        val sessionsArray = JSONArray()

        sessions.forEach { session ->
            val sObj = JSONObject()
            sObj.put("id", session.id)
            sObj.put("title", session.title)
            sObj.put("question", session.question)
            sObj.put("answer", ModelOutputSanitizer.clean(session.answer))
            sObj.put("updatedAt", session.updatedAt)
            sObj.put("imagePath", session.imagePath ?: "")
            sObj.put("audioPath", session.audioPath ?: "")
            sObj.put("audioName", session.audioName ?: "")
            sObj.put("audioDurationMs", session.audioDurationMs)
            sObj.put("documentName", session.documentName ?: "")
            sObj.put("documentId", session.documentId ?: "")

            val messagesArray = JSONArray()
            session.messages.forEach { msg ->
                val mObj = JSONObject()
                mObj.put("id", msg.id)
                mObj.put("text", if (msg.isUser) msg.text else ModelOutputSanitizer.clean(msg.text))
                mObj.put("isUser", msg.isUser)
                if (!msg.userMessageId.isNullOrBlank()) mObj.put("userMessageId", msg.userMessageId)
                if (!msg.requestId.isNullOrBlank()) mObj.put("requestId", msg.requestId)
                mObj.put("attemptId", msg.attemptId)
                if (!msg.reasoningText.isNullOrBlank()) mObj.put("reasoningText", msg.reasoningText)
                if (msg.terminalStatus != null) mObj.put("terminalStatus", msg.terminalStatus.name)
                if (!msg.errorMessage.isNullOrBlank()) mObj.put("errorMessage", msg.errorMessage)
                if (msg.emptyReason != null) mObj.put("emptyReason", msg.emptyReason.toString())
                if (!msg.imagePath.isNullOrBlank()) mObj.put("imagePath", msg.imagePath)
                if (!msg.audioPath.isNullOrBlank()) mObj.put("audioPath", msg.audioPath)
                if (!msg.audioName.isNullOrBlank()) mObj.put("audioName", msg.audioName)
                if (msg.audioDurationMs > 0L) mObj.put("audioDurationMs", msg.audioDurationMs)
                if (!msg.documentName.isNullOrBlank()) mObj.put("documentName", msg.documentName)
                if (!msg.documentPath.isNullOrBlank()) mObj.put("documentPath", msg.documentPath)

                if (msg.ragSources.isNotEmpty()) {
                    val ragArr = JSONArray()
                    msg.ragSources.forEach { ragArr.put(it) }
                    mObj.put("ragSources", ragArr)
                    mObj.put("ragChunkCount", msg.ragChunkCount)
                    mObj.put("ragTopMatchPct", msg.ragTopMatchPct)
                }
                messagesArray.put(mObj)
            }
            sObj.put("messages", messagesArray)
            sessionsArray.put(sObj)
        }
        root.put("sessions", sessionsArray)
        return root.toString()
    }

    fun decodeVisionSessions(rawJson: String): List<VisionChatSession> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()

        return if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            if (root.has("version")) {
                val version = root.optInt("version", CURRENT_VERSION)
                if (version > CURRENT_VERSION) {
                    throw com.shounak.localmeshai.utils.UnsupportedVersionException(version, CURRENT_VERSION)
                }
            }
            val isLegacy = !root.has("version")
            val sessionsArray = root.optJSONArray("sessions") ?: JSONArray()
            parseVisionSessionsArray(sessionsArray, isLegacy = isLegacy)
        } else if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            parseVisionSessionsArray(array, isLegacy = true)
        } else {
            emptyList()
        }
    }

    fun decodeLegacyVisionSessions(rawJson: String): List<VisionChatSession> = decodeVisionSessions(rawJson)

    private fun parseVisionSessionsArray(array: JSONArray, isLegacy: Boolean = false): List<VisionChatSession> {
        val sessions = mutableListOf<VisionChatSession>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val sessionId = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            val question = item.optString("question").orEmpty()
            val answer = ModelOutputSanitizer.clean(item.optString("answer").orEmpty())
            val title = item.optString("title").ifBlank { question.ifBlank { "Vision chat" } }
            val rawUpdatedAt = when {
                item.has("updatedAt") -> item.optLong("updatedAt", 0L)
                item.has("updated_at") -> item.optLong("updated_at", 0L)
                item.has("timestamp") -> item.optLong("timestamp", 0L)
                else -> 0L
            }
            val updatedAt = if (rawUpdatedAt > 0L) rawUpdatedAt else System.currentTimeMillis()
            val imagePath = item.optString("imagePath").takeIf { it.isNotBlank() }
            val audioPath = item.optString("audioPath").takeIf { it.isNotBlank() }
            val audioName = item.optString("audioName").takeIf { it.isNotBlank() }
            val audioDurationMs = item.optLong("audioDurationMs", 0L)
            val documentName = item.optString("documentName").takeIf { it.isNotBlank() }
            val documentId = item.optString("documentId").takeIf { it.isNotBlank() }

            val parsedMessages = mutableListOf<VisionChatMessage>()
            val messagesJson = item.optJSONArray("messages")
            var lastUserMsgId: String? = null

            if (messagesJson != null && messagesJson.length() > 0) {
                for (j in 0 until messagesJson.length()) {
                    val mObj = messagesJson.optJSONObject(j) ?: continue
                    val id = mObj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val isUser = mObj.optBoolean("isUser", false)
                    val text = mObj.optString("text").orEmpty()
                    val cleanText = if (isUser) text else ModelOutputSanitizer.clean(text)

                    val userMessageId = mObj.optString("userMessageId").takeIf { it.isNotBlank() }
                        ?: if (!isUser) lastUserMsgId else null
                    val requestId = mObj.optString("requestId").takeIf { it.isNotBlank() }
                    val attemptId = mObj.optInt("attemptId", 1)
                    val reasoningText = mObj.optString("reasoningText").takeIf { it.isNotBlank() }
                    val mImagePath = mObj.optString("imagePath").takeIf { it.isNotBlank() }
                    val mAudioPath = mObj.optString("audioPath").takeIf { it.isNotBlank() }
                    val mAudioName = mObj.optString("audioName").takeIf { it.isNotBlank() }
                    val mAudioDurationMs = mObj.optLong("audioDurationMs", 0L)
                    val mDocName = mObj.optString("documentName").takeIf { it.isNotBlank() }
                    val mDocPath = mObj.optString("documentPath").takeIf { it.isNotBlank() }

                    val statusStr = mObj.optString("terminalStatus").takeIf { it.isNotBlank() }
                    val errorMsg = mObj.optString("errorMessage").takeIf { it.isNotBlank() }
                    val emptyReasonStr = mObj.optString("emptyReason").takeIf { it.isNotBlank() }

                    val emptyReason = when (emptyReasonStr) {
                        "NoVisibleAnswer" -> EmptyResponseReason.NoVisibleAnswer
                        "ReasoningOnly" -> EmptyResponseReason.ReasoningOnly
                        "AttachmentUnavailable" -> EmptyResponseReason.AttachmentUnavailable
                        else -> null
                    }

                    val terminalStatus = when (statusStr) {
                        RequestTerminalStatus.Completed.name -> RequestTerminalStatus.Completed
                        RequestTerminalStatus.Stopped.name -> RequestTerminalStatus.Stopped
                        RequestTerminalStatus.Failed.name -> RequestTerminalStatus.Failed
                        else -> {
                            if (isLegacy && !isUser) {
                                if (cleanText.isNotBlank() && errorMsg == null) {
                                    RequestTerminalStatus.Completed
                                } else if (errorMsg != null) {
                                    RequestTerminalStatus.Failed
                                } else {
                                    null
                                }
                            } else null
                        }
                    }

                    val ragSourcesJson = mObj.optJSONArray("ragSources")
                    val ragSources = if (ragSourcesJson != null) {
                        List(ragSourcesJson.length()) { k -> ragSourcesJson.optString(k, "") }.filter { it.isNotBlank() }
                    } else emptyList()
                    val ragChunkCount = mObj.optInt("ragChunkCount", 0)
                    val ragTopMatchPct = mObj.optInt("ragTopMatchPct", 0)

                    if (isUser) {
                        lastUserMsgId = id
                    }

                    parsedMessages.add(
                        VisionChatMessage(
                            text = cleanText,
                            isUser = isUser,
                            id = id,
                            ragSources = ragSources,
                            ragChunkCount = ragChunkCount,
                            ragTopMatchPct = ragTopMatchPct,
                            imagePath = mImagePath,
                            audioPath = mAudioPath,
                            audioName = mAudioName,
                            audioDurationMs = mAudioDurationMs,
                            documentName = mDocName,
                            documentPath = mDocPath,
                            terminalStatus = terminalStatus,
                            errorMessage = errorMsg,
                            requestId = requestId,
                            attemptId = attemptId,
                            reasoningText = reasoningText,
                            userMessageId = userMessageId,
                            emptyReason = emptyReason
                        )
                    )
                }
            } else if (question.isNotBlank() || !imagePath.isNullOrBlank() || !documentName.isNullOrBlank()) {
                // Reconstruct from legacy top-level fields
                val uId = UUID.randomUUID().toString()
                parsedMessages.add(
                    VisionChatMessage(
                        id = uId,
                        text = question,
                        isUser = true,
                        imagePath = imagePath,
                        audioPath = audioPath,
                        audioName = audioName,
                        audioDurationMs = audioDurationMs,
                        documentName = documentName
                    )
                )
                if (answer.isNotBlank()) {
                    parsedMessages.add(
                        VisionChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = answer,
                            isUser = false,
                            terminalStatus = RequestTerminalStatus.Completed,
                            userMessageId = uId
                        )
                    )
                } else {
                    parsedMessages.add(
                        VisionChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = "",
                            isUser = false,
                            terminalStatus = RequestTerminalStatus.Failed,
                            errorMessage = INTERRUPTED_ERROR_MESSAGE,
                            userMessageId = uId
                        )
                    )
                }
            }

            val normalized = normalizeVisionMessages(parsedMessages)
            // Note: Never filter out a session solely because its answer is blank!
            sessions.add(
                VisionChatSession(
                    id = sessionId,
                    title = title,
                    question = question,
                    answer = answer,
                    updatedAt = updatedAt,
                    imagePath = imagePath,
                    audioPath = audioPath,
                    audioName = audioName,
                    audioDurationMs = audioDurationMs,
                    documentName = documentName,
                    documentId = documentId,
                    messages = normalized
                )
            )
        }
        return sessions
    }

    private fun normalizeVisionMessages(messages: MutableList<VisionChatMessage>): List<VisionChatMessage> {
        if (messages.isEmpty()) return emptyList()

        for (i in 0 until messages.size) {
            val msg = messages[i]
            if (!msg.isUser && msg.terminalStatus == null) {
                val userMsg = messages.take(i).lastOrNull { it.isUser }
                messages[i] = msg.copy(
                    terminalStatus = RequestTerminalStatus.Failed,
                    errorMessage = msg.errorMessage ?: INTERRUPTED_ERROR_MESSAGE,
                    userMessageId = msg.userMessageId ?: userMsg?.id
                )
            }
        }

        val last = messages.last()
        if (last.isUser) {
            // Incomplete trailing user turn
            messages.add(
                VisionChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = "",
                    isUser = false,
                    terminalStatus = RequestTerminalStatus.Failed,
                    errorMessage = INTERRUPTED_ERROR_MESSAGE,
                    userMessageId = last.id,
                    attemptId = 1
                )
            )
        }
        return messages
    }
}

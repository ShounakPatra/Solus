package com.shounak.localmeshai.rag

import com.shounak.localmeshai.data.ChatAttachmentStore
import com.shounak.localmeshai.data.ChatKind
import java.io.File
import java.util.UUID

/** Repair only a file whose ownership is established by the current chat's attachment directory. */
internal object LegacyDocumentLink {
    fun repairId(path: String?, existingId: String?, attachments: ChatAttachmentStore,
                 kind: ChatKind, sessionId: String?): String? {
        if (sessionId == null || path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || !attachments.isOwnedAttachment(file, kind, sessionId)) return null
        return existingId ?: UUID.randomUUID().toString()
    }
}

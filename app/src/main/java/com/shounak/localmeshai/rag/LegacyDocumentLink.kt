package com.shounak.localmeshai.rag

import com.shounak.localmeshai.data.ChatAttachmentStore
import com.shounak.localmeshai.data.ChatKind
import java.io.File
import java.util.UUID

/** Repair only a file whose ownership is established by the current chat's attachment directory. */
internal object LegacyDocumentLink {
    /**
     * Returns the id to index an owned upload under, or null when nothing needs repairing.
     *
     * An upload without an id (a pre-2dd59d5 chat) gets a new id. An upload that already has an id is left alone
     * unless [isIndexed] reports that id missing from the index (for example a chat restored from a backup, which
     * carries no index); then the upload's own id is re-linked. A linked, indexed upload is never re-extracted.
     */
    fun repairId(path: String?, existingId: String?, attachments: ChatAttachmentStore,
                 kind: ChatKind, sessionId: String?, isIndexed: (String) -> Boolean = { true }): String? {
        if (sessionId == null || path.isNullOrBlank()) return null
        if (existingId != null && (existingId.isBlank() || isIndexed(existingId))) return null
        val file = File(path)
        if (!file.isFile || !attachments.isOwnedAttachment(file, kind, sessionId)) return null
        return existingId ?: UUID.randomUUID().toString()
    }
}

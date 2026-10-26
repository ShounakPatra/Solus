package com.shounak.localmeshai.rag

/** A knowledge-base document chosen with "Use in chat" for the next message. */
data class LibraryDocumentSelection(val id: String, val name: String)

/** Why a knowledge-base document could not be chosen for the next message. */
enum class LibrarySelectionResult { Selected, Busy, TemporaryChat, AttachmentPending, Unavailable }

/**
 * Which document one chat turn reads, scopes retrieval to and may index.
 *
 * [indexTargetId] is only ever an id that belongs to an upload in this chat: the turn's own new upload, or a
 * repair of an earlier owned upload. It is never a library id, so a turn cannot overwrite a library document.
 */
internal data class TurnDocumentPlan(
    /** Earlier upload whose text this turn may read, or null. */
    val savedUpload: TurnDocumentPlanner.Entry?,
    /** The id this turn may index into once it completes, or null. */
    val indexTargetId: String?,
    /** Message that must carry [indexTargetId] once indexing succeeds (only written when it has no id yet). */
    val indexOwnerMessageId: String?,
    /** Library document the turn is scoped to, or null. */
    val libraryDocumentId: String?,
    /** Document ids retrieval may search for this turn. */
    val ragScope: Set<String>
)

internal object TurnDocumentPlanner {
    data class Entry(val id: String, val isUser: Boolean, val name: String?, val path: String?, val ragDocumentId: String?)

    /** The first user message carrying a path owns that upload; later messages with the same path only repeat it. */
    fun originalUploads(history: List<Entry>): List<Entry> =
        history.filter { it.isUser && !it.path.isNullOrBlank() }.distinctBy { it.path }

    fun isOriginalUpload(history: List<Entry>, messageId: String): Boolean =
        originalUploads(history).any { it.id == messageId }

    /** The latest upload before the turn, resolved to the message that originally attached it. */
    fun savedUpload(history: List<Entry>, turnMessageId: String?): Entry? {
        val turnIndex = turnMessageId?.let { id -> history.indexOfFirst { it.id == id } } ?: -1
        val before = if (turnIndex >= 0) history.take(turnIndex) else history
        val latest = before.lastOrNull { it.isUser && !it.path.isNullOrBlank() } ?: return null
        return originalUploads(history).firstOrNull { it.path == latest.path } ?: latest
    }

    /**
     * The library document an existing turn was sent with: its id when the turn is not itself an upload and the id
     * does not belong to any upload in this chat.
     */
    fun libraryDocumentId(history: List<Entry>, turnMessageId: String): String? {
        val turn = history.firstOrNull { it.id == turnMessageId && it.isUser } ?: return null
        val id = turn.ragDocumentId?.takeIf(String::isNotBlank) ?: return null
        val uploads = originalUploads(history)
        if (uploads.any { it.id == turn.id }) return null
        return id.takeUnless { candidate -> uploads.any { it.ragDocumentId == candidate } }
    }

    fun plan(
        history: List<Entry>,
        turnMessageId: String?,
        hasOwnAttachment: Boolean,
        ownUploadId: String?,
        libraryDocumentId: String?,
        isDocumentQuestion: (Entry) -> Boolean,
        repair: (Entry) -> String?
    ): TurnDocumentPlan {
        val chatScope = ChatDocumentScope.ids(history.map { it.isUser to it.ragDocumentId })
        if (hasOwnAttachment) {
            // A newly attached file wins over a pending library selection.
            return TurnDocumentPlan(null, ownUploadId, ownUploadId?.let { turnMessageId }, null,
                chatScope + listOfNotNull(ownUploadId))
        }
        if (libraryDocumentId != null) {
            // The chosen document is the whole scope: no earlier upload is read, repaired or re-indexed.
            return TurnDocumentPlan(null, null, null, libraryDocumentId, setOf(libraryDocumentId))
        }
        val saved = savedUpload(history, turnMessageId)?.takeIf(isDocumentQuestion)
        val repaired = saved?.let(repair)
        return TurnDocumentPlan(saved, repaired, if (repaired != null) saved?.id else null, null, chatScope + listOfNotNull(repaired))
    }
}

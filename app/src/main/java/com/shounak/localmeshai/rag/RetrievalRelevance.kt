package com.shounak.localmeshai.rag

internal object RetrievalRelevance {
    @Volatile
    private var nativeAvailable: Boolean = true

    fun isConversationOnly(query: String): Boolean {
        if (nativeAvailable) {
            try {
                return RagCoreLogicNative.isConversationOnly(query)
            } catch (_: Throwable) {
                nativeAvailable = false
            }
        }
        return com.shounak.localmeshai.rag.isConversationOnly(query)
    }

    // Hash-based embeddings can collide on unrelated words. Require actual content evidence as well.
    fun hasLexicalEvidence(query: String, content: String): Boolean {
        if (nativeAvailable) {
            try {
                return RagCoreLogicNative.hasLexicalEvidence(query, content)
            } catch (_: Throwable) {
                nativeAvailable = false
            }
        }
        return com.shounak.localmeshai.rag.hasLexicalEvidence(query, content)
    }

    fun isUnqualifiedOverview(query: String): Boolean {
        if (nativeAvailable) {
            try {
                return RagCoreLogicNative.isUnqualifiedOverview(query)
            } catch (_: Throwable) {
                nativeAvailable = false
            }
        }
        return com.shounak.localmeshai.rag.isUnqualifiedOverview(query)
    }
}

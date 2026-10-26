package com.shounak.localmeshai.ai.tools

/** Model-generated arguments cannot authorize a network request. Only the review UI calls approve. */
internal object ApprovedWikipediaLookups {
    private data class Entry(val result: String, val expiresAt: Long)
    private val approved = linkedMapOf<String, Entry>()

    @Synchronized fun request(topic: String, now: Long = System.currentTimeMillis()): String {
        approved.entries.removeAll { it.value.expiresAt <= now }
        return approved[topic]?.result ?: SkillCatalog.action("wikipedia").put("topic", topic)
            .put("message", "No request has been sent. The user must review this exact query. After approval, ask the model to continue; this tool can then read the approved result.").toString()
    }

    fun approve(topic: String, fetch: (String) -> String): String {
        require(topic.isNotBlank() && topic.length <= 300) { "Invalid Wikipedia topic" }
        val result = fetch(topic)
        synchronized(this) {
            while (approved.size >= 20) approved.remove(approved.keys.first())
            approved[topic] = Entry(result, System.currentTimeMillis() + 15 * 60_000L)
        }
        return result
    }
}

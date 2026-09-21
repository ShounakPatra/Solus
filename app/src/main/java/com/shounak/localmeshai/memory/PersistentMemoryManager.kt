package com.shounak.localmeshai.memory

import android.content.Context
import java.util.Locale

/**
 * Coordinator for managing persistent memories, preferences, and custom instructions.
 * Strictly decoupled from RAG and LLM engines.
 */
class PersistentMemoryManager(
    val context: Context? = null,
    val memoryStore: MemoryStore = PersistentMemoryStore(context)
) {

    val totalMemoryCount: Int
        get() = memoryStore.count()

    val activeMemoryCount: Int
        get() = memoryStore.getActive().size

    fun getAllMemories(): List<MemoryEntry> {
        return memoryStore.getAll()
    }

    fun getActiveMemories(): List<MemoryEntry> {
        return memoryStore.getActive()
    }

    fun addMemory(
        content: String,
        category: MemoryCategory = MemoryCategory.GENERAL
    ): MemoryEntry? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null

        val superseded = findSupersededMemory(trimmed)
        if (superseded != null) {
            val updated = superseded.copy(
                content = trimmed,
                category = category,
                isEnabled = true,
                updatedAt = System.currentTimeMillis()
            )
            val success = memoryStore.update(updated)
            return if (success) updated else null
        }

        val entry = MemoryEntry(
            content = trimmed,
            category = category,
            isEnabled = true
        )
        val added = memoryStore.add(entry)
        return if (added) entry else null
    }

    private fun findSupersededMemory(newContent: String): MemoryEntry? {
        val lowerNew = newContent.lowercase(Locale.ROOT)
        val all = memoryStore.getAll()

        val prefix = when {
            lowerNew.startsWith("my dog's name is ") -> "my dog's name is "
            lowerNew.startsWith("my cat's name is ") -> "my cat's name is "
            lowerNew.startsWith("my name is ") -> "my name is "
            lowerNew.startsWith("my full name is ") -> "my full name is "
            lowerNew.startsWith("call me ") -> "call me "
            lowerNew.startsWith("i live in ") -> "i live in "
            lowerNew.startsWith("i reside in ") -> "i reside in "
            lowerNew.startsWith("my birthday is ") -> "my birthday is "
            lowerNew.startsWith("my car is ") -> "my car is "
            lowerNew.startsWith("my job is ") -> "my job is "
            lowerNew.startsWith("my phone is ") -> "my phone is "
            lowerNew.startsWith("my email is ") -> "my email is "
            lowerNew.startsWith("my age is ") -> "my age is "
            lowerNew.startsWith("i am ") && lowerNew.contains(" years old") -> "i am "
            else -> null
        }

        if (prefix != null) {
            return all.firstOrNull {
                it.content.lowercase(Locale.ROOT).startsWith(prefix)
            }
        }
        return null
    }

    fun updateMemory(entry: MemoryEntry): Boolean {
        return memoryStore.update(entry)
    }

    fun deleteMemory(id: String): Boolean {
        return memoryStore.delete(id)
    }

    fun toggleMemory(id: String, enabled: Boolean): Boolean {
        return memoryStore.setEnabled(id, enabled)
    }

    fun clearAllMemories() {
        memoryStore.clearAll()
    }

    /**
     * Formats all active memories into a structured context block for injection into chat prompts.
     * Returns an empty string if there are no active memories.
     */
    fun formatMemoryContext(): String {
        val active = memoryStore.getActive()
        if (active.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[User Memory & Preferences]\n")
        sb.append("The following are persistent memories, facts, and instructions describing the USER chatting with you:\n")
        for (entry in active) {
            val label = when (entry.category) {
                MemoryCategory.FACT -> "Fact"
                MemoryCategory.PREFERENCE -> "Preference"
                MemoryCategory.INSTRUCTION -> "Instruction"
                MemoryCategory.GENERAL -> "Note"
            }
            sb.append("- [$label] ${entry.content}\n")
        }
        sb.append("IMPORTANT INSTRUCTION FOR ASSISTANT: These memories describe the USER chatting with you. When answering questions regarding the user's identity, preferences, or facts, always address the user directly as \"You\" / \"Your\" (for example: \"You prefer Kotlin\", \"Your name is Lola\"). NEVER say \"I prefer\" or claim the user's preferences, memories, or identity as your own.\n")
        sb.append("[End of User Memory]")
        return sb.toString()
    }

    /**
     * Automatically extracts potential memory content and category from conversational user messages.
     * Recognizes explicit triggers ("remember that..."), first-person facts ("my dog's name is Cooper", "I live in Berlin"),
     * behavioral instructions ("always reply in German", "from now on, speak in French"), preferences, and topic declarations.
     * Rejects questions and transient chat/greetings.
     */
    fun extractMemoryCandidate(userText: String): Pair<String, MemoryCategory>? {
        val trimmed = userText.trim()
        if (trimmed.length < 4) return null

        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Explicit triggers (highest priority)
        val explicitTriggers = listOf(
            "can you please remember that ",
            "can you remember that ",
            "can you please remember to ",
            "can you remember to ",
            "can you please remember: ",
            "can you remember: ",
            "can you please remember ",
            "can you remember ",
            "could you please remember that ",
            "could you remember that ",
            "could you please remember: ",
            "could you remember: ",
            "please remember that ",
            "please remember to ",
            "please remember: ",
            "please remember ",
            "always remember that ",
            "always remember to ",
            "always remember: ",
            "always remember ",
            "make sure to remember that ",
            "make sure to remember to ",
            "make sure to remember: ",
            "make sure to remember ",
            "make sure you remember that ",
            "make sure you remember to ",
            "make sure you remember ",
            "don't forget that ",
            "dont forget that ",
            "don't forget to ",
            "dont forget to ",
            "don't forget: ",
            "dont forget: ",
            "don't forget ",
            "dont forget ",
            "keep in mind that ",
            "keep in mind: ",
            "keep in mind ",
            "bear in mind that ",
            "bear in mind: ",
            "bear in mind ",
            "take note that ",
            "take note of ",
            "take note: ",
            "take note ",
            "save this fact: ",
            "save this memory: ",
            "save this: ",
            "save this that ",
            "save this ",
            "save that ",
            "store this fact: ",
            "store this memory: ",
            "store this: ",
            "store this ",
            "store that ",
            "memorize that ",
            "memorize: ",
            "memorize ",
            "record that ",
            "record: ",
            "record ",
            "fyi: ",
            "fyi ",
            "for your information: ",
            "for your information, ",
            "for your information ",
            "just so you know, ",
            "just so you know: ",
            "just so you know ",
            "note that ",
            "note: ",
            "note ",
            "remember that ",
            "remember to ",
            "remember: ",
            "remember "
        )

        for (trigger in explicitTriggers) {
            if (lower.startsWith(trigger)) {
                val extractedRaw = trimmed.substring(trigger.length).trim()
                val cleaned = cleanMemoryContent(extractedRaw) ?: return null
                val category = determineCategory(cleaned)
                return Pair(cleaned, category)
            }
        }

        // 2. Reject questions and common conversational commands
        if (trimmed.endsWith("?")) return null

        val nonMemoryStarters = listOf(
            "what ", "what's ", "whats ", "who ", "who's ", "whos ", "where ", "where's ", "wheres ",
            "when ", "when's ", "whens ", "why ", "why's ", "how ", "how's ", "which ", "whose ", "whom ",
            "is ", "are ", "am i ", "was ", "were ", "do ", "does ", "did ",
            "can you ", "could you ", "would you ", "should you ", "will you ", "shall ",
            "tell me ", "explain ", "describe ", "summarize ", "write ", "generate ",
            "create ", "find ", "search ", "show me ", "give me ", "help me ", "list ",
            "hello", "hi ", "hey", "good morning", "good evening", "good afternoon",
            "thank", "thanks", "ok ", "okay", "bye", "goodbye"
        )
        if (nonMemoryStarters.any { lower.startsWith(it) }) return null

        // 3. Behavioral instructions & rules
        if (lower.startsWith("from now on, ") || lower.startsWith("from now on ")) {
            val prefixLen = if (lower.startsWith("from now on, ")) 13 else 12
            val rule = trimmed.substring(prefixLen).trim().trimStart(',', ':', ' ').trim()
            val cleaned = cleanMemoryContent(rule) ?: return null
            return Pair(cleaned, MemoryCategory.INSTRUCTION)
        }

        if (lower.startsWith("always ") || lower.startsWith("never ") ||
            lower.startsWith("do not ") || lower.startsWith("don't ") || lower.startsWith("dont ")
        ) {
            val cleaned = cleanMemoryContent(trimmed) ?: return null
            return Pair(cleaned, MemoryCategory.INSTRUCTION)
        }

        if (lower.startsWith("reply in ") || lower.startsWith("respond in ") ||
            lower.startsWith("speak in ") || lower.startsWith("answer in ") || lower.startsWith("write in ")
        ) {
            val cleaned = cleanMemoryContent(trimmed) ?: return null
            return Pair(cleaned, MemoryCategory.INSTRUCTION)
        }

        // 4. First-person facts & attributes
        // "My [noun...] is/are/was [value...]"
        val myPattern = Regex("""^my\s+([a-zA-Z0-9'’\s]{2,30}?)\s+(?:is|are|was)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val myMatch = myPattern.matchEntire(trimmed)
        if (myMatch != null) {
            val noun = myMatch.groupValues[1].lowercase(Locale.ROOT).trim()
            val value = myMatch.groupValues[2].trim()
            val ignoredNouns = setOf("question", "doubt", "problem", "issue", "concern", "thought", "point")
            if (noun !in ignoredNouns && value.isNotBlank()) {
                val cleaned = cleanMemoryContent(trimmed) ?: return null
                val category = if (noun.contains("favorite") || noun.contains("favourite") || noun.contains("preferred")) {
                    MemoryCategory.PREFERENCE
                } else {
                    MemoryCategory.FACT
                }
                return Pair(cleaned, category)
            }
        }

        // "I am / I'm [state/profession/age...]"
        val iAmPattern = Regex("""^(?:i\s+am|i'm|im)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val iAmMatch = iAmPattern.matchEntire(trimmed)
        if (iAmMatch != null) {
            val predicate = iAmMatch.groupValues[1].trim()
            val lowerPred = predicate.lowercase(Locale.ROOT)
            val transientStates = setOf(
                "happy", "sad", "tired", "ready", "fine", "ok", "okay", "here", "back", "bored",
                "done", "sorry", "listening", "confused", "good", "bad", "hungry", "sleepy", "busy",
                "testing", "just testing", "looking", "trying", "wondering", "asking", "thinking", "curious"
            )
            val firstWord = lowerPred.substringBefore(" ")
            if (lowerPred !in transientStates && firstWord !in transientStates) {
                val cleaned = cleanMemoryContent(trimmed) ?: return null
                return Pair(cleaned, MemoryCategory.FACT)
            }
        }

        // "I live in / reside in / come from / am from"
        val locationPattern = Regex("""^(?:i\s+(?:live|reside|come)\s+from|i\s+(?:live|reside)\s+in|i\s+am\s+from|i'm\s+from)\s+(.+)$""", RegexOption.IGNORE_CASE)
        if (locationPattern.matches(trimmed)) {
            val cleaned = cleanMemoryContent(trimmed) ?: return null
            return Pair(cleaned, MemoryCategory.FACT)
        }

        // "I work at / work as / work in / study at"
        val workStudyPattern = Regex("""^i\s+(?:work|study)\s+(?:at|as|in|for)\s+(.+)$""", RegexOption.IGNORE_CASE)
        if (workStudyPattern.matches(trimmed)) {
            val cleaned = cleanMemoryContent(trimmed) ?: return null
            return Pair(cleaned, MemoryCategory.FACT)
        }

        // "I have a/an [pet/item] named/called [name]" or "I have [noun]"
        val iHavePattern = Regex("""^i\s+have\s+(?:a |an )?(.+)$""", RegexOption.IGNORE_CASE)
        val iHaveMatch = iHavePattern.matchEntire(trimmed)
        if (iHaveMatch != null) {
            val rest = iHaveMatch.groupValues[1].trim().lowercase(Locale.ROOT)
            val ignoredHave = listOf("question", "problem", "doubt", "idea", "hunch", "to go", "no idea")
            if (ignoredHave.none { rest.startsWith(it) }) {
                val cleaned = cleanMemoryContent(trimmed) ?: return null
                return Pair(cleaned, MemoryCategory.FACT)
            }
        }

        // "Call me [name]" / "You can call me [name]" / "Please call me [name]"
        val callMePattern = Regex("""^(?:you\s+can\s+call\s+me|please\s+call\s+me|call\s+me|refer\s+to\s+me\s+as)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val callMeMatch = callMePattern.matchEntire(trimmed)
        if (callMeMatch != null) {
            val name = callMeMatch.groupValues[1].trim()
            val cleanedName = cleanMemoryContent(name) ?: return null
            return Pair("Call me $cleanedName", MemoryCategory.FACT)
        }

        // Entity relationship assertions: "[Name] is my [relation/pet/possession]"
        val entityRelPattern = Regex("""^([a-zA-Z0-9_\s]{2,25}?)\s+is\s+my\s+([a-zA-Z0-9_\s]+)$""", RegexOption.IGNORE_CASE)
        val entityMatch = entityRelPattern.matchEntire(trimmed)
        if (entityMatch != null) {
            val subject = entityMatch.groupValues[1].trim()
            val lowerSubj = subject.lowercase(Locale.ROOT)
            val pronouns = setOf("this", "that", "it", "here", "there", "what", "which", "he", "she", "they", "we", "who")
            if (lowerSubj !in pronouns) {
                val cleaned = cleanMemoryContent(trimmed) ?: return null
                return Pair(cleaned, MemoryCategory.FACT)
            }
        }

        // 5. First-person preferences
        val prefPattern = Regex("""^i\s+(?:prefer|like|love|dislike|hate|don't like|dont like)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val prefMatch = prefPattern.matchEntire(trimmed)
        if (prefMatch != null) {
            val target = prefMatch.groupValues[1].trim().lowercase(Locale.ROOT)
            val trivial = setOf("it", "this", "that", "them", "you", "to ask")
            if (target !in trivial) {
                val cleaned = cleanMemoryContent(trimmed) ?: return null
                return Pair(cleaned, MemoryCategory.PREFERENCE)
            }
        }

        // 6. Topic declarations: "The topic is [X]" or "The project is [X]"
        val topicPattern = Regex("""^the\s+(?:topic|subject|project|app)\s+is\s+(.+)$""", RegexOption.IGNORE_CASE)
        if (topicPattern.matches(trimmed)) {
            val cleaned = cleanMemoryContent(trimmed) ?: return null
            return Pair(cleaned, MemoryCategory.GENERAL)
        }

        return null
    }

    private fun cleanMemoryContent(raw: String): String? {
        var text = raw.trim()
            .trimStart(':', '-', ' ', '\t', '"', '\'', '“', '”')
            .trimEnd('.', '!', '?', ';', ',', '"', '\'', '“', '”')
            .trim()

        if (text.lowercase(Locale.ROOT).startsWith("that ")) {
            text = text.substring(5).trim()
        }

        if (text.length < 3) return null

        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun determineCategory(content: String): MemoryCategory {
        val lower = content.lowercase(Locale.ROOT)
        return when {
            lower.contains("prefer") ||
            lower.contains("favorite") ||
            lower.contains("favourite") ||
            lower.startsWith("i like ") ||
            lower.startsWith("i love ") ||
            lower.startsWith("i dislike ") ||
            lower.startsWith("i hate ") ||
            lower.startsWith("i don't like ") ||
            lower.startsWith("i dont like ") -> MemoryCategory.PREFERENCE

            lower.startsWith("always ") ||
            lower.startsWith("never ") ||
            lower.startsWith("do not ") ||
            lower.startsWith("don't ") ||
            lower.startsWith("dont ") ||
            lower.startsWith("speak in ") ||
            lower.startsWith("reply in ") ||
            lower.startsWith("respond in ") ||
            lower.startsWith("answer in ") ||
            lower.startsWith("write in ") ||
            lower.startsWith("format ") ||
            lower.contains("bullet point") ||
            lower.contains("concise") -> MemoryCategory.INSTRUCTION

            lower.startsWith("my ") ||
            lower.startsWith("i am ") ||
            lower.startsWith("i'm ") ||
            lower.startsWith("im ") ||
            lower.startsWith("i live ") ||
            lower.startsWith("i work ") ||
            lower.startsWith("i study ") ||
            lower.startsWith("i have ") ||
            lower.startsWith("call me ") ||
            lower.contains(" is my ") ||
            lower.contains(" are my ") ||
            lower.contains(" is named ") ||
            lower.contains("'s name is ") -> MemoryCategory.FACT

            else -> MemoryCategory.GENERAL
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PersistentMemoryManager? = null

        fun getInstance(context: Context): PersistentMemoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PersistentMemoryManager(
                    context = context.applicationContext,
                    memoryStore = PersistentMemoryStore(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }
}

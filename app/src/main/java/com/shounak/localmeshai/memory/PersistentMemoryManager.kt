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
     * When [query] is provided:
     * - If [query] is a greeting or casual opening, factual memories are NEVER injected to prevent
     *   models from blurting out stored facts unprompted during greetings.
     * - If [query] asks about stored memories, matching memories (or all if open-ended) are included.
     * - For general queries, behavioral instructions are preserved, but facts/preferences are only
     *   injected if they are relevant to the query topic.
     * Returns an empty string if there are no matching or active memories.
     */
    fun formatMemoryContext(query: String = ""): String {
        val active = memoryStore.getActive()
        if (active.isEmpty()) return ""

        val trimmedQuery = query.trim()
        val isGreeting = isGreetingOrChitChat(trimmedQuery)

        val relevantEntries = if (trimmedQuery.isBlank()) {
            active
        } else if (isGreeting) {
            // For greetings, NEVER inject user facts, preferences, or notes!
            // Only behavioral instructions (e.g. "always reply in Spanish") may apply.
            active.filter { it.category == MemoryCategory.INSTRUCTION }
        } else if (isOpenEndedRecallQuery(trimmedQuery)) {
            active
        } else if (isMemoryRecallQuery(trimmedQuery)) {
            val matched = findRelevantMemories(trimmedQuery, active)
            if (matched.isNotEmpty()) matched else active
        } else {
            // For general queries, keep instructions and only include facts relevant to the query topic
            val instructions = active.filter { it.category == MemoryCategory.INSTRUCTION }
            val relevantFacts = findRelevantMemories(
                trimmedQuery,
                active.filter { it.category != MemoryCategory.INSTRUCTION }
            )
            instructions + relevantFacts
        }

        if (relevantEntries.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[User Memory & Preferences]\n")
        sb.append("The following are persistent memories, facts, and instructions describing the USER chatting with you:\n")
        for (entry in relevantEntries) {
            val label = when (entry.category) {
                MemoryCategory.FACT -> "Fact"
                MemoryCategory.PREFERENCE -> "Preference"
                MemoryCategory.INSTRUCTION -> "Instruction"
                MemoryCategory.GENERAL -> "Note"
            }
            sb.append("- [$label] ${entry.content}\n")
        }
        sb.append("IMPORTANT INSTRUCTIONS FOR ASSISTANT:\n")
        sb.append("1. Identity: You are Solus, a helpful and friendly on-device AI assistant. Never refer to yourself as 'Your' or take 'Your' as your name.\n")
        sb.append("2. Perspective: The facts and preferences above describe the USER, not you. Never claim the user's preferences, memories, or identity as your own.\n")
        sb.append("3. Greetings: When the user simply greets you (e.g. 'hi', 'hello', 'hey'), respond naturally with a brief, friendly greeting. Do NOT list, recite, or blurt out the user's stored memories unprompted during greetings.\n")
        sb.append("4. Relevant Use: Only refer to these stored memories when directly relevant to what the user asks or requests.\n")
        sb.append("5. Remembering Information: When the user asks or tells you to remember, save, or note down any fact, preference, or instruction, acknowledge it warmly and confirm that you have remembered it. Do NOT respond with a generic greeting.\n")
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

    /**
     * Converts a first-person user statement into a natural second-person phrasing.
     * E.g. "My dog name is lambda" -> "your dog name is lambda"
     *      "I love to eat ice-cream" -> "you love to eat ice-cream"
     */
    fun toSecondPerson(text: String): String {
        var result = text.trim()
        if (result.isBlank()) return ""

        result = result.replace(Regex("""\b(?:i\s+am|i'm|im)\b""", RegexOption.IGNORE_CASE), "you are")
        result = result.replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE), "your")
        result = result.replace(Regex("""\bmine\b""", RegexOption.IGNORE_CASE), "yours")
        result = result.replace(Regex("""\bmyself\b""", RegexOption.IGNORE_CASE), "yourself")
        result = result.replace(Regex("""\bme\b""", RegexOption.IGNORE_CASE), "you")
        result = result.replace(Regex("""\bi\b""", RegexOption.IGNORE_CASE), "you")

        return result.replaceFirstChar { it.lowercase(Locale.ROOT) }
    }

    /**
     * Formats a friendly confirmation message acknowledging that a memory or instruction has been recorded.
     */
    fun formatConfirmationMessage(rawContent: String, category: MemoryCategory): String {
        val trimmed = rawContent.trim()
        val secondPerson = toSecondPerson(trimmed)
        return when {
            category == MemoryCategory.INSTRUCTION -> {
                val cleanedInstruction = trimmed.trimStart().replaceFirstChar { it.lowercase(Locale.ROOT) }
                if (cleanedInstruction.startsWith("to ")) {
                    "Got it! I will remember $cleanedInstruction."
                } else {
                    "Got it! I will remember to $cleanedInstruction."
                }
            }
            secondPerson.startsWith("call you", ignoreCase = true) -> {
                "Got it! I will remember to $secondPerson."
            }
            else -> {
                "Got it! I will remember that $secondPerson."
            }
        }
    }

    fun isGreetingOrChitChat(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.ROOT).trim().removeSuffix("!").removeSuffix(".").removeSuffix("?").trim()
        val exactGreetings = setOf(
            "hi", "hello", "hey", "hii", "heyy", "hiii", "yo", "sup",
            "what's up", "whats up", "what's new", "whats new",
            "howdy", "how are you", "how are you doing", "how r u",
            "good morning", "good evening", "good afternoon", "good day",
            "greetings", "hey there", "hello there", "hi there",
            "ok", "okay", "cool", "thanks", "thank you"
        )
        if (lower in exactGreetings) return true
        val starters = listOf(
            "hi ", "hello ", "hey ", "howdy ", "good morning ", "good afternoon ", "good evening ", "greetings "
        )
        return starters.any { lower.startsWith(it) } && lower.length < 30
    }

    fun isOpenEndedRecallQuery(query: String): Boolean {
        if (query.isBlank()) return false
        val lower = query.lowercase(Locale.ROOT).trim()
        val openRecallTriggers = listOf(
            "what do you know about me",
            "what do you remember about me",
            "what do you remember",
            "what did i tell you to remember",
            "what are my memories",
            "what memories do you have",
            "tell me what you know about me",
            "tell me about myself",
            "what have you stored about me",
            "show my memories",
            "list my memories"
        )
        return openRecallTriggers.any { lower.contains(it) }
    }

    fun isMemoryRecallQuery(query: String): Boolean {
        if (query.isBlank()) return false
        val lower = query.lowercase(Locale.ROOT).trim()
        val queryStarters = listOf(
            "what things i like", "what things do i like", "what food i like", "what foods i like",
            "what do i like", "what i like", "what do i love", "what i love",
            "what do i prefer", "what i prefer", "what is my preference", "what are my preferences",
            "what do i eat", "what i eat", "what do i drink", "what i drink",
            "what is my name", "what's my name", "whats my name", "who am i",
            "where do i live", "where do i reside", "where do i work",
            "where do i like to go", "where i like to go", "where do i like", "where i like",
            "where do i go", "where do i travel",
            "what is my dog", "what's my dog", "what is my cat", "what's my cat",
            "what do you know about me", "what do you remember about me",
            "what did i tell you to remember", "what did i tell you", "what did i say earlier",
            "do you remember what i", "do you know what i", "tell me what i like", "tell me about myself"
        )
        return queryStarters.any { lower.startsWith(it) || lower.contains(it) }
    }

    fun findRelevantMemories(query: String, entries: List<MemoryEntry>): List<MemoryEntry> {
        val trimmedQuery = query.trim().lowercase(Locale.ROOT)
        if (trimmedQuery.length < 3 || entries.isEmpty()) return emptyList()

        val stopWords = setOf(
            "what", "is", "my", "i", "do", "to", "the", "a", "an", "can", "you",
            "tell", "me", "how", "who", "where", "when", "why", "are", "about",
            "remember", "know", "recall", "say", "said", "and", "or", "in", "on", "at", "for"
        )
        val queryKeywords = trimmedQuery
            .split(Regex("""[^a-zA-Z0-9]+"""))
            .filter { it.length > 2 && it !in stopWords }

        if (queryKeywords.isEmpty()) return emptyList()

        val expandedQuery = expandWithSynonyms(queryKeywords)

        return entries.mapNotNull { entry ->
            val contentWords = entry.content.lowercase(Locale.ROOT)
                .split(Regex("""[^a-zA-Z0-9]+"""))
                .filter { it.length > 2 && it !in stopWords }
                .toSet()
            val expandedContent = expandWithSynonyms(contentWords)
            val overlap = expandedQuery.count { it in expandedContent }
            if (overlap > 0) entry to overlap else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Answers questions about the user's stored memories, facts, and preferences (e.g. "what I like to eat",
     * "what is my dog's name", "where do I live", "what is my favorite food").
     * Returns a clear, natural second-person answer if a matching memory is found.
     */
    fun findAnswerForUserQuery(query: String): String? {
        val trimmedQuery = query.trim().lowercase(Locale.ROOT)
        if (trimmedQuery.length < 3) return null
        val active = memoryStore.getActive()
        if (active.isEmpty()) return null

        val stopWords = setOf(
            "what", "is", "my", "i", "do", "to", "the", "a", "an", "can", "you",
            "tell", "me", "how", "who", "where", "when", "why", "are", "about",
            "remember", "know", "recall", "say", "said"
        )
        val queryKeywords = trimmedQuery
            .split(Regex("""[^a-zA-Z0-9]+"""))
            .filter { it.length > 2 && it !in stopWords }

        if (queryKeywords.isEmpty()) return null

        val expandedQuery = expandWithSynonyms(queryKeywords)

        val matches = active.mapNotNull { entry ->
            val contentWords = entry.content.lowercase(Locale.ROOT)
                .split(Regex("""[^a-zA-Z0-9]+"""))
                .filter { it.length > 2 && it !in stopWords }
                .toSet()
            val expandedContent = expandWithSynonyms(contentWords)
            val overlap = expandedQuery.count { it in expandedContent }
            if (overlap > 0) entry to overlap else null
        }.sortedByDescending { it.second }

        if (matches.isEmpty()) return null

        val topScore = matches.first().second
        val topMatches = matches.filter { it.second == topScore }.take(3)
        val descriptions = if (topMatches.size == 1) {
            toSecondPerson(topMatches.first().first.content)
        } else {
            topMatches.joinToString(" and ") { toSecondPerson(it.first.content) }
        }

        return "Based on what you told me, $descriptions."
    }

    private fun expandWithSynonyms(words: Collection<String>): Set<String> {
        val expanded = words.toMutableSet()
        for (word in words) {
            for (group in SYNONYM_GROUPS) {
                if (word in group) {
                    expanded.addAll(group)
                }
            }
        }
        return expanded
    }

    companion object {
        private val SYNONYM_GROUPS = listOf(
            setOf("eat", "food", "foods", "eating", "dish", "dishes", "meal", "meals", "snack", "snacks"),
            setOf("drink", "drinks", "drinking", "beverage", "beverages"),
            setOf("like", "likes", "love", "loves", "prefer", "prefers", "preference", "preferences", "favorite", "favourite"),
            setOf("live", "lives", "living", "reside", "resides", "stay", "stays", "city", "country", "home", "town"),
            setOf("name", "names", "called", "call"),
            setOf("dog", "dogs", "puppy", "puppies", "cat", "cats", "kitten", "kittens", "pet", "pets"),
            setOf("work", "job", "profession", "career", "company", "occupation"),
            setOf("go", "going", "visit", "visiting", "travel", "traveling", "trip", "destination", "place", "places", "tour", "touring")
        )
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

package com.shounak.localmeshai.utils

import java.util.Locale

object ModelResponseQuality {
    fun isGenericNonAnswer(response: String, userText: String = ""): Boolean {
        val normalized = response.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return false

        val request = userText.trim().lowercase(Locale.US)

        // Internal reasoning / chain-of-thought leak (e.g. "Okay, the user just asked...")
        if (isReasoningLeak(normalized)) {
            return true
        }

        // Memory statements and user instructions:
        // When the user tells the model to remember something ("remember I like pizza"),
        // a valid answer MUST confirm memory retention. If it doesn't, or claims user's preference
        // or gives greetings/chit-chat without memory confirmation, it is a non-answer.
        if (isMemoryOrInstruction(request) && !isMemoryRecallQuery(request)) {
            val hasMemoryAck = normalized.contains("remember") ||
                normalized.contains("noted") ||
                normalized.contains("keep that in mind") ||
                normalized.contains("keep in mind") ||
                normalized.contains("got it") ||
                normalized.contains("i will keep") ||
                normalized.contains("i'll keep") ||
                normalized.contains("recorded") ||
                normalized.contains("saved") ||
                normalized.contains("stored") ||
                normalized.contains("acknowledged") ||
                normalized.contains("will keep")
            if (hasMemoryAck) {
                return false
            }
            // Did not acknowledge storing memory!
            if (normalized in GenericNonAnswerExact || GenericNonAnswerMarkers.any { normalized.contains(it) }) {
                return true
            }
            if (normalized.startsWith("hello") || normalized.startsWith("hi") || normalized.startsWith("hey") || normalized.startsWith("greetings")) {
                return true
            }
            if (normalized.contains("too!") || normalized.contains("i love") || normalized.contains("i like") || normalized.contains("me too") || normalized.contains("i also")) {
                return true
            }
            if (normalized.contains("recipe") || normalized.contains("let me know if") || normalized.contains("how can i") || normalized.contains("what can i")) {
                return true
            }
            val isExplicitStorageCmd = request.startsWith("remember") || request.startsWith("please remember") ||
                request.startsWith("don't forget") || request.startsWith("dont forget") ||
                request.startsWith("note that") || request.startsWith("save this") || request.startsWith("store this") ||
                request.startsWith("keep in mind")
            if (isExplicitStorageCmd) {
                return true
            }
        }

        // Memory recall queries (e.g. "what things I like to eat", "what is my name"):
        if (isMemoryRecallQuery(request)) {
            val expressesIgnorance = normalized.contains("don't know") ||
                normalized.contains("dont know") ||
                normalized.contains("do not know") ||
                normalized.contains("no idea") ||
                normalized.contains("can't remember") ||
                normalized.contains("cant remember") ||
                normalized.contains("cannot remember") ||
                normalized.contains("don't remember") ||
                normalized.contains("dont remember") ||
                normalized.contains("do not remember") ||
                normalized.contains("don't recall") ||
                normalized.contains("dont recall") ||
                normalized.contains("do not recall") ||
                normalized.contains("not sure what you") ||
                normalized.contains("haven't told me") ||
                normalized.contains("havent told me") ||
                normalized.contains("have not told me") ||
                normalized.contains("haven't shared") ||
                normalized.contains("havent shared") ||
                normalized.contains("have not shared") ||
                normalized.contains("haven't mentioned") ||
                normalized.contains("havent mentioned") ||
                normalized.contains("have not mentioned") ||
                normalized.contains("never mentioned") ||
                normalized.contains("never told me") ||
                normalized.contains("you didn't tell me") ||
                normalized.contains("you didnt tell me") ||
                normalized.contains("you didn't mention") ||
                normalized.contains("you didnt mention") ||
                normalized.contains("you haven't told me") ||
                normalized.contains("you havent told me") ||
                normalized.contains("don't have that information") ||
                normalized.contains("dont have that information") ||
                normalized.contains("don't have any information") ||
                normalized.contains("dont have any information") ||
                normalized.contains("don't have access") ||
                normalized.contains("dont have access") ||
                normalized.contains("do not have access") ||
                normalized.contains("have no information") ||
                normalized.contains("have no memory") ||
                normalized.contains("don't have memory") ||
                normalized.contains("dont have memory")
            if (expressesIgnorance) {
                return true
            }
            if (isDeflectionOrEcho(normalized, request)) {
                return true
            }
            val claimsOwn = normalized.contains("too!") || normalized.contains("i love") ||
                normalized.contains("i like") || normalized.contains("my favorite")
            val answersUser = normalized.contains("you like") || normalized.contains("you love") ||
                normalized.contains("you prefer") || normalized.contains("you told me") ||
                normalized.contains("based on")
            if (claimsOwn && !answersUser) {
                return true
            }
            if (normalized in GenericNonAnswerExact || GenericNonAnswerMarkers.any { normalized.contains(it) }) {
                return true
            }
        }

        // Deflection or echoing the user's question back
        if (isDeflectionOrEcho(normalized, request)) {
            return true
        }

        // Any response confirming memory retention, recording, or noting is valid.
        val hasMemoryAck = normalized.contains("remember") ||
            normalized.contains("noted") ||
            normalized.contains("keep that in mind") ||
            normalized.contains("keep in mind") ||
            normalized.contains("got it") ||
            normalized.contains("i will keep") ||
            normalized.contains("i'll keep") ||
            normalized.contains("recorded") ||
            normalized.contains("saved") ||
            normalized.contains("stored")

        if (hasMemoryAck) {
            return false
        }

        if (isGreeting(request)) {
            if (isReasoningLeak(normalized) || isDeflectionOrEcho(normalized, request)) {
                return true
            }
            if (normalized.contains("okay, i understand") ||
                normalized.contains("i will do my best to answer your request") ||
                normalized.contains("provide only the direct final answer") ||
                normalized.contains("final answer")
            ) {
                return true
            }
            // Memory blurt or entity echo detection during greetings:
            // e.g. user says "hi" or "what's up" and model blurts out "delhi", "pizza", or a stored memory statement
            val hasExplicitGreetingWord = Regex("""\b(?:hi|hello|hey|hii|heyy|howdy|greetings|yo|sup)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
            val isSingleWordEntity = !normalized.contains(" ") && normalized.length < 25
            val isMemoryBlurt = (normalized.startsWith("you like to go to") ||
                normalized.startsWith("you like") ||
                normalized.startsWith("your dog's name is") ||
                normalized.startsWith("you live in") ||
                normalized.startsWith("based on what you told me")) &&
                !hasExplicitGreetingWord
            if (isSingleWordEntity || isMemoryBlurt) {
                return true
            }
            val hasGreetingContent = hasExplicitGreetingWord ||
                normalized.contains("how are you") ||
                normalized.contains("how's it going") ||
                normalized.contains("how can i help") ||
                normalized.contains("how can i assist") ||
                normalized.contains("what can i help") ||
                normalized.contains("what can i do") ||
                normalized.contains("good morning") ||
                normalized.contains("good evening") ||
                normalized.contains("good afternoon") ||
                normalized.contains("great to see you") ||
                normalized.contains("good to see you") ||
                normalized.contains("nice to meet") ||
                normalized.contains("not much") ||
                normalized.contains("doing well") ||
                normalized.contains("all good") ||
                normalized.contains("i'm doing well") ||
                normalized.contains("i am doing well") ||
                normalized.contains("i'm good")
            if (!hasGreetingContent) {
                return true
            }
            return false
        }

        if (normalized in GenericNonAnswerExact) {
            return true
        }

        return GenericNonAnswerMarkers.any { marker ->
            if (marker == "okay, i understand" || marker == "ok, i understand") {
                normalized.startsWith(marker) && (normalized.length < 35 || isOnlyBoilerplate(normalized))
            } else {
                normalized.contains(marker)
            }
        }
    }

    fun isMemoryOrInstruction(request: String): Boolean {
        if (request.isBlank()) return false
        val lower = request.lowercase(Locale.ROOT)
        val triggers = listOf(
            "remember",
            "keep in mind",
            "bear in mind",
            "take note",
            "note that",
            "note:",
            "save this",
            "store this",
            "don't forget",
            "dont forget",
            "my name is",
            "my dog",
            "my cat",
            "call me",
            "i live in",
            "i prefer",
            "always ",
            "never ",
            "from now on"
        )
        return triggers.any { lower.contains(it) }
    }

    fun isGreeting(request: String): Boolean {
        if (request.isBlank()) return false
        val lower = request.lowercase(Locale.ROOT).trim().removeSuffix("!").removeSuffix(".").removeSuffix("?").trim()
        val exactGreetings = setOf(
            "hi", "hello", "hey", "hii", "heyy", "hiii", "yo", "sup",
            "what's up", "whats up", "what's new", "whats new",
            "howdy", "how are you", "how are you doing", "how r u",
            "good morning", "good evening", "good afternoon", "good day",
            "greetings", "hey there", "hello there", "hi there"
        )
        if (lower in exactGreetings) return true
        val starters = listOf(
            "hi ", "hello ", "hey ", "howdy ", "good morning ", "good afternoon ", "good evening ", "greetings "
        )
        return starters.any { lower.startsWith(it) } && lower.length < 30
    }

    fun isMemoryRecallQuery(request: String): Boolean {
        if (request.isBlank()) return false
        val lower = request.lowercase(Locale.ROOT).trim()
        val queryStarters = listOf(
            "what things i like",
            "what things do i like",
            "what food i like",
            "what foods i like",
            "what do i like",
            "what i like",
            "what do i love",
            "what i love",
            "what do i prefer",
            "what i prefer",
            "what is my preference",
            "what are my preferences",
            "what do i eat",
            "what i eat",
            "what do i drink",
            "what i drink",
            "what is my name",
            "what's my name",
            "whats my name",
            "who am i",
            "where do i live",
            "where do i reside",
            "where do i work",
            "where do i like to go",
            "where i like to go",
            "where do i like",
            "where i like",
            "where do i go",
            "where do i travel",
            "what is my dog",
            "what's my dog",
            "what is my cat",
            "what's my cat",
            "what do you know about me",
            "what do you remember about me",
            "what did i tell you to remember",
            "what did i tell you",
            "what did i say earlier",
            "do you remember what i",
            "do you know what i",
            "tell me what i like",
            "tell me about myself"
        )
        if (queryStarters.any { lower.startsWith(it) || lower.contains(it) }) return true

        val isSelfQuery = (lower.startsWith("what ") || lower.startsWith("what's ") || lower.startsWith("whats ") ||
            lower.startsWith("where ") || lower.startsWith("who ") || lower.startsWith("tell me ") ||
            lower.startsWith("do you know ") || lower.startsWith("do you remember ")) &&
            (lower.contains(" my ") || lower.contains(" i ") || lower.contains(" about me"))
        return isSelfQuery
    }

    fun isDeflectionOrEcho(response: String, userText: String): Boolean {
        val normalizedResponse = response.trim().lowercase(Locale.US)
        val normalizedRequest = userText.trim().lowercase(Locale.US)
        if (normalizedResponse.isBlank() || normalizedRequest.isBlank()) return false

        val containsQuestion = normalizedResponse.contains("?") ||
            normalizedResponse.contains("what do you") ||
            normalizedResponse.contains("what other") ||
            normalizedResponse.contains("tell me what you") ||
            normalizedResponse.contains("do you like") ||
            normalizedResponse.contains("do you have")

        if (!containsQuestion) return false

        val stopWords = setOf(
            "what", "is", "my", "i", "do", "to", "the", "a", "an", "can", "you",
            "tell", "me", "how", "who", "where", "when", "why", "are", "about",
            "remember", "know", "recall", "say", "said", "hi", "hello", "hey",
            "other", "things", "thing"
        )
        val requestKeywords = normalizedRequest
            .split(Regex("""[^a-zA-Z0-9]+"""))
            .filter { it.length > 2 && it !in stopWords }

        if (requestKeywords.isEmpty()) return false

        val questionSentences = normalizedResponse
            .split(Regex("""[.!?\n]+"""))
            .filter { it.isNotBlank() }

        for (sentence in questionSentences) {
            val sentenceWords = sentence
                .split(Regex("""[^a-zA-Z0-9]+"""))
                .filter { it.length > 2 && it !in stopWords }
                .toSet()
            val matches = requestKeywords.count { it in sentenceWords }
            if (matches >= 1 && (sentence.contains("you") || sentence.contains("your"))) {
                return true
            }
        }

        return false
    }

    private val ReasoningStarterRegex = Regex(
        """^(okay|ok|so|well|first|here|in this case|now|wait|hmm)?,?\s*(the user|the prompt|the human|the input|the query|the question)\s+(greeted|asked|is asking|wants|said|mentioned|preferences?|message|input|stated)""",
        RegexOption.IGNORE_CASE
    )

    private val ThinkingIntroRegex = Regex(
        """^(let('s|s| me)\s+(think|see|check|analyze|understand|figure|break|keep|formulate)|i (should|need to|must|have to)\s+(respond|answer|reply|check|analyze|address|mention|keep)|thinking process:|thought process:|internal thoughts:|chain of thought:)""",
        RegexOption.IGNORE_CASE
    )

    fun isReasoningLeak(response: String): Boolean {
        val normalized = response.trim().lowercase(Locale.US)
        if (normalized.length < 12) return false

        if (ReasoningStarterRegex.containsMatchIn(normalized)) return true
        if (ThinkingIntroRegex.containsMatchIn(normalized)) return true

        val starters = listOf(
            "okay, the user",
            "ok, the user",
            "okay so the user",
            "ok so the user",
            "the user greeted",
            "the user asked",
            "the user is asking",
            "the user wants",
            "the user said",
            "the user mentioned",
            "the user just",
            "the user's",
            "here, the user",
            "here the user",
            "in this case, the user",
            "in this context, the user",
            "for this request, the user",
            "first, the user",
            "let me check the stored memories",
            "let me check my memory",
            "let me check what the user",
            "let me think",
            "let's see",
            "lets see",
            "let me see",
            "let me analyze",
            "let me respond",
            "let me keep it",
            "so i need to respond",
            "so i should respond",
            "i should check the context",
            "i need to respond",
            "i should respond",
            "thinking process:",
            "thought process:",
            "internal thoughts:"
        )
        if (starters.any { normalized.startsWith(it) }) return true

        val reasoningClues = listOf(
            "let me ",
            "the user ",
            "maybe say ",
            "should i ",
            "stored memories",
            "so i should respond",
            "so i need to respond",
            "let me keep it short",
            "the user greeted me",
            "user's preferences are"
        )
        val endings = listOf(
            "that should be it.", "that should be it",
            "that should be all.", "that should be all",
            "that's it.", "that's it",
            "that covers it.", "that covers it"
        )
        if (endings.any { normalized.endsWith(it) } && reasoningClues.any { normalized.contains(it) }) {
            return true
        }

        val clueCount = reasoningClues.count { normalized.contains(it) }
        if (clueCount >= 2 && (normalized.contains("maybe say ") || normalized.contains("i should ") || normalized.contains("so i "))) {
            return true
        }

        return false
    }

    private fun isOnlyBoilerplate(normalized: String): Boolean {
        val boilerplateMarkers = listOf(
            "i will do my best to answer",
            "i will wait for your request",
            "please feel free to ask",
            "how can i help",
            "how may i help",
            "what can i do for you",
            "provide the final answer",
            "final answer"
        )
        val stripped = normalized.replace("okay, i understand", "")
            .replace("ok, i understand", "")
            .trim('.', '!', '?', ',', ' ')
        return stripped.isBlank() || boilerplateMarkers.any { stripped.contains(it) }
    }

    fun shouldSuppressLivePartial(response: String): Boolean {
        val normalized = response.trim().lowercase(Locale.US)
        if (normalized.length < 4) return false
        if (isReasoningLeak(normalized)) return true
        return LiveSuppressionMarkers.any { marker ->
            marker.startsWith(normalized) || normalized.startsWith(marker)
        }
    }

    private val GenericNonAnswerExact = setOf(
        "okay, i understand.",
        "okay, i understand",
        "the answer is the final answer.",
        "the answer is the final answer"
    )

    private val GenericNonAnswerMarkers = listOf(
        "i could not generate a response",
        "this model returned a generic non-answer",
        "okay, i understand",
        "ok, i understand",
        "i will do my best to answer your request",
        "i will wait for your request",
        "i am unable to provide information on the topic",
        "i cannot provide information on the topic",
        "please feel free to ask anything",
        "please feel free to ask me anything",
        "the answer is the final answer",
        "how can i help you today",
        "how can i assist you today",
        "how may i help you",
        "what can i do for you"
    )

    private val LiveSuppressionMarkers = listOf(
        "okay, i understand",
        "ok, i understand",
        "okay, the user",
        "ok, the user",
        "okay so the user",
        "the user greeted",
        "the user asked",
        "the user is asking",
        "let me check",
        "let me think",
        "let's see",
        "i will wait for your request",
        "i am unable to provide information on the topic",
        "i cannot provide information on the topic",
        "the answer is the final answer"
    )
}

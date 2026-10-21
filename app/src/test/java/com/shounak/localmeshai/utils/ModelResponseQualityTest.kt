package com.shounak.localmeshai.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResponseQualityTest {
    @Test
    fun detectsScreenshotBoilerplateResponses() {
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "Okay, I understand. I will do my best to answer your request. Please feel free to ask anything!",
                "hi"
            )
        )
        assertTrue(ModelResponseQuality.isGenericNonAnswer("Okay, I understand.", "what can you do"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("The answer is the final answer.", "what is it?"))
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "I could not generate a response. Try asking again with a little more detail.",
                "explain gravity"
            )
        )
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "This model returned a generic non-answer. Try a larger model.",
                "explain gravity"
            )
        )
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "Okay, I understand. I will wait for your request and provide the final answer.",
                "hi"
            )
        )
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "I am unable to provide information on the topic of what can you do.",
                "what can you do"
            )
        )
    }

    @Test
    fun allowsRealAnswers() {
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Hi! What would you like help with?", "hi"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Hi! How can I assist you today?", "hi"))
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "A Java program starts with a class and a main method.",
                "write a Java program"
            )
        )
    }

    @Test
    fun allowsMemoryAndInstructionAcknowledgements() {
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "I will remember that your dog's name is Cooper.",
                "Remember that my dog's name is Cooper."
            )
        )
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "Okay, I understand. I will remember that your dog's name is Cooper.",
                "Remember that my dog's name is Cooper."
            )
        )
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "Got it! Noted that you prefer dark theme.",
                "Keep in mind that I prefer dark theme."
            )
        )
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "Got it! I will remember that your dog name is lambda.",
                "remember my dog name is lambda"
            )
        )
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "Got it! I will remember that you love to eat ice-cream.",
                "remember I love to eat ice-cream"
            )
        )
        assertFalse(
            ModelResponseQuality.isGenericNonAnswer(
                "Okay, I understand. To implement binary search in Kotlin, use the following approach:\nfun binarySearch()...",
                "explain binary search"
            )
        )
    }

    @Test
    fun detectsGenericNonAnswersToMemoryStatements() {
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "Hello! How can I help you today?",
                "remember my dog name is lambda"
            )
        )
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "Hello! How can I help you today? 😊",
                "remember I love to eat ice-cream"
            )
        )
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "Okay, I understand.",
                "remember my dog name is lambda"
            )
        )
        assertTrue(
            ModelResponseQuality.isGenericNonAnswer(
                "Hello!",
                "remember my dog name is lambda"
            )
        )
    }

    @Test
    fun suppressesKnownAcknowledgementAndRefusalPrefixesWhileStreaming() {
        assertTrue(ModelResponseQuality.shouldSuppressLivePartial("Okay, I under"))
        assertTrue(ModelResponseQuality.shouldSuppressLivePartial("I am unable to provide"))
        assertFalse(ModelResponseQuality.shouldSuppressLivePartial("Quantum computing uses qubits"))
    }

    @Test
    fun detectsLeakedReasoning() {
        val leaked = """
            Okay, the user just asked, "what I like to eat". Let me check the stored memories. The user mentioned that they love to eat ice-cream. So I need to respond based on that. Since the user's preferences are about ice-cream, I should mention that. But I shouldn't list or recite their memories unless it's directly relevant. Let me make sure to keep it friendly and in line with the previous conversation. Maybe say something like, "I love to eat ice-cream!" to show my preference without using their name. That should be it.
        """.trimIndent()

        assertTrue(ModelResponseQuality.isReasoningLeak(leaked))
        assertTrue(ModelResponseQuality.isGenericNonAnswer(leaked, "what I like to eat"))
    }

    @Test
    fun detectsScreenshotTurnOneAndTurnTwoFailures() {
        // Screenshot Turn 1: user asked to remember, model gave recipe/chit-chat claiming preference as own
        val turn1Response = "Hi! I love pizza too! 🍕 Let me know if you'd like a pizza recipe or something!"
        assertTrue(ModelResponseQuality.isGenericNonAnswer(turn1Response, "remember I like pizza"))

        // Screenshot Turn 2: user asked what they like to eat, model deflected by asking question back
        val turn2Response = "Hi! I love pizza too! 🍕 What other things do you like to eat?"
        assertTrue(ModelResponseQuality.isDeflectionOrEcho(turn2Response, "what things I like to eat"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer(turn2Response, "what things I like to eat"))

        // Valid memory confirmation should be accepted
        val turn1Valid = "Got it! I will remember that you like pizza."
        assertFalse(ModelResponseQuality.isGenericNonAnswer(turn1Valid, "remember I like pizza"))

        // Valid memory query answer should be accepted
        val turn2Valid = "Based on what you told me, you like pizza."
        assertFalse(ModelResponseQuality.isGenericNonAnswer(turn2Valid, "what things I like to eat"))
        assertFalse(ModelResponseQuality.isDeflectionOrEcho(turn2Valid, "what things I like to eat"))
    }

    @Test
    fun detectsUntaggedReasoningMonologueLeakFromUserScreenshot() {
        val screenshotOutput = "Okay, the user greeted me with \"hi,\" so I should respond naturally. Since the user's preferences are about liking pizza and ice-cream, I should mention those in a friendly way. Let me keep it short and positive. Maybe say something like, \"Hi! I love pizza and"
        assertTrue(ModelResponseQuality.isReasoningLeak(screenshotOutput))
        assertTrue(ModelResponseQuality.isGenericNonAnswer(screenshotOutput, "hi"))
        assertTrue(ModelResponseQuality.shouldSuppressLivePartial(screenshotOutput))
    }

    @Test
    fun detectsVariousUntaggedReasoningMonologuesAcrossModels() {
        val qwenLeak = "The user greeted me with \"hello\". Let me respond warmly and ask how I can help."
        assertTrue(ModelResponseQuality.isReasoningLeak(qwenLeak))

        val gemmaLeak = "Let's see, the user wants to know how to bake a cake. I should list ingredients first."
        assertTrue(ModelResponseQuality.isReasoningLeak(gemmaLeak))

        val deepseekLeak = "Thinking process: 1. Understand user intent 2. Formulate response."
        assertTrue(ModelResponseQuality.isReasoningLeak(deepseekLeak))

        val llamaLeak = "In this case, the user asked for a summary. I will keep it short and clear."
        assertTrue(ModelResponseQuality.isReasoningLeak(llamaLeak))

        // Legitimate direct responses should NOT be flagged
        assertFalse(ModelResponseQuality.isReasoningLeak("Hi! How can I help you today?"))
        assertFalse(ModelResponseQuality.isReasoningLeak("You like pizza and ice-cream! 😊"))
        assertFalse(ModelResponseQuality.isReasoningLeak("To bake a cake, you need flour, sugar, and eggs."))
    }

    @Test
    fun detectsModelIgnoranceToMemoryRecallQuery() {
        // Gemma 4 E2B IT response from screenshot when user asked "what i like to eat"
        val screenshotGemmaAnswer = "I don't know what you like to eat! 😊 To give you a suggestion, I need a little more information. Tell me:"
        assertTrue(ModelResponseQuality.isGenericNonAnswer(screenshotGemmaAnswer, "what i like to eat"))

        assertTrue(ModelResponseQuality.isGenericNonAnswer("I have no idea what you like to eat.", "what i like to eat"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("I don't remember what your name is.", "what is my name"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("You haven't told me what your dog's name is.", "what is my dog name"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("I don't have that information about what you like.", "what i like to eat"))

        // Legitimate answers should NOT be flagged
        assertFalse(ModelResponseQuality.isGenericNonAnswer("You told me that you like pizza and ice-cream! 😊", "what i like to eat"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Your dog's name is Lambda. 🐾", "what is my dog name"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Based on what you told me, you like to eat pizza.", "what i like to eat"))
    }

    @Test
    fun testDetectsMemoryBlurtsOnGreetings() {
        // Blurting out stored memory entities during greetings is a non-answer
        assertTrue(ModelResponseQuality.isGenericNonAnswer("delhi", "hi"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("delhi", "what's up"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("You like to go to delhi.", "hi"))
        assertTrue(ModelResponseQuality.isGenericNonAnswer("You like to go to delhi.", "what's up"))

        // Legitimate greetings are valid
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Hello! How can I help you today?", "hi"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Hi there! How can I assist you?", "hi"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Not much! How can I help you today?", "what's up"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("All good! What's on your mind?", "what's up"))

        // Answering from memory when specifically asked about destination is valid
        assertFalse(ModelResponseQuality.isGenericNonAnswer("You like to go to delhi.", "where do I like to go"))
        assertFalse(ModelResponseQuality.isGenericNonAnswer("Based on what you told me, you like to go to delhi.", "where do I like to go"))

        // Greeting classification checks
        assertTrue(ModelResponseQuality.isGreeting("hi"))
        assertTrue(ModelResponseQuality.isGreeting("what's up"))
        assertTrue(ModelResponseQuality.isGreeting("whats up"))
        assertTrue(ModelResponseQuality.isGreeting("Good morning!"))
        assertFalse(ModelResponseQuality.isGreeting("where do I like to go"))
    }
}

package com.shounak.localmeshai.memory

import org.junit.Assert.*
import org.junit.Test

class PersistentMemoryManagerTest {

    @Test
    fun testFormatMemoryContext_FormatsCleanly() {
        val store = PersistentMemoryStore(null)
        val manager = PersistentMemoryManager(context = null, memoryStore = store)

        manager.addMemory("User is building an Android app called Solus", MemoryCategory.FACT)
        manager.addMemory("I prefer concise responses", MemoryCategory.PREFERENCE)
        manager.addMemory("Always write code in Kotlin", MemoryCategory.INSTRUCTION)

        val context = manager.formatMemoryContext()
        assertTrue(context.contains("[User Memory & Preferences]"))
        assertTrue(context.contains("[Fact] User is building an Android app called Solus"))
        assertTrue(context.contains("[Preference] I prefer concise responses"))
        assertTrue(context.contains("[Instruction] Always write code in Kotlin"))
        assertTrue(context.contains("5. Remembering Information:"))
        assertFalse(context.contains("such as 'Hello! How can I help you today?'"))
        assertTrue(context.contains("[End of User Memory]"))
    }

    @Test
    fun testFormatConfirmationMessage() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        val confirmation1 = manager.formatConfirmationMessage("My dog name is lambda", MemoryCategory.FACT)
        assertEquals("Got it! I will remember that your dog name is lambda.", confirmation1)

        val confirmation2 = manager.formatConfirmationMessage("I love to eat ice-cream", MemoryCategory.PREFERENCE)
        assertEquals("Got it! I will remember that you love to eat ice-cream.", confirmation2)

        val confirmation3 = manager.formatConfirmationMessage("Speak in French", MemoryCategory.INSTRUCTION)
        assertEquals("Got it! I will remember to speak in French.", confirmation3)

        val confirmation4 = manager.formatConfirmationMessage("Call me Alex", MemoryCategory.FACT)
        assertEquals("Got it! I will remember to call you Alex.", confirmation4)
    }

    @Test
    fun testFindAnswerForUserQuery() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))
        manager.addMemory("I love to eat ice-cream", MemoryCategory.PREFERENCE)
        manager.addMemory("My dog name is lambda", MemoryCategory.FACT)

        val answer1 = manager.findAnswerForUserQuery("what I like to eat")
        assertEquals("Based on what you told me, you love to eat ice-cream.", answer1)

        val answer2 = manager.findAnswerForUserQuery("what is my dog name")
        assertEquals("Based on what you told me, your dog name is lambda.", answer2)
    }

    @Test
    fun testFindAnswerForUserQuery_PizzaMemory() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))
        manager.addMemory("I like pizza", MemoryCategory.PREFERENCE)

        val answer = manager.findAnswerForUserQuery("what things I like to eat")
        assertEquals("Based on what you told me, you like pizza.", answer)
    }

    @Test
    fun testFormatMemoryContext_WhenEmpty_ReturnsBlank() {
        val store = PersistentMemoryStore(null)
        val manager = PersistentMemoryManager(context = null, memoryStore = store)

        assertEquals("", manager.formatMemoryContext())

        // Add a disabled memory
        store.add(MemoryEntry(content = "Disabled fact", isEnabled = false))
        assertEquals("", manager.formatMemoryContext())
    }

    @Test
    fun testExtractMemoryCandidate_Preferences() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        val result1 = manager.extractMemoryCandidate("Remember that I prefer dark theme")
        assertNotNull(result1)
        assertEquals("I prefer dark theme", result1?.first)
        assertEquals(MemoryCategory.PREFERENCE, result1?.second)

        val result2 = manager.extractMemoryCandidate("Please remember that I love Kotlin coroutines.")
        assertNotNull(result2)
        assertEquals("I love Kotlin coroutines", result2?.first)
        assertEquals(MemoryCategory.PREFERENCE, result2?.second)
    }

    @Test
    fun testExtractMemoryCandidate_Instructions() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        val result1 = manager.extractMemoryCandidate("Remember to speak in French")
        assertNotNull(result1)
        assertEquals("Speak in French", result1?.first)
        assertEquals(MemoryCategory.INSTRUCTION, result1?.second)

        val result2 = manager.extractMemoryCandidate("Always remember: keep answers concise!")
        assertNotNull(result2)
        assertEquals("Keep answers concise", result2?.first)
        assertEquals(MemoryCategory.INSTRUCTION, result2?.second)

        val result3 = manager.extractMemoryCandidate("Keep in mind that all responses should be bullet points")
        assertNotNull(result3)
        assertEquals("All responses should be bullet points", result3?.first)
        assertEquals(MemoryCategory.INSTRUCTION, result3?.second)
    }

    @Test
    fun testExtractMemoryCandidate_Facts() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        val result1 = manager.extractMemoryCandidate("Remember that my name is Alice")
        assertNotNull(result1)
        assertEquals("My name is Alice", result1?.first)
        assertEquals(MemoryCategory.FACT, result1?.second)

        val result2 = manager.extractMemoryCandidate("Note: my dog's name is Cooper")
        assertNotNull(result2)
        assertEquals("My dog's name is Cooper", result2?.first)
        assertEquals(MemoryCategory.FACT, result2?.second)

        val result3 = manager.extractMemoryCandidate("Don't forget that I live in Tokyo.")
        assertNotNull(result3)
        assertEquals("I live in Tokyo", result3?.first)
        assertEquals(MemoryCategory.FACT, result3?.second)
    }

    @Test
    fun testExtractMemoryCandidate_NonMemoryQueries_ReturnNull() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        assertNull(manager.extractMemoryCandidate("What is the capital of France?"))
        assertNull(manager.extractMemoryCandidate("Hello, how are you?"))
        assertNull(manager.extractMemoryCandidate("Tell me a joke"))
        assertNull(manager.extractMemoryCandidate("Rem"))
    }

    @Test
    fun testExtractMemoryCandidate_NaturalStatements() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        // Natural first-person facts
        val res1 = manager.extractMemoryCandidate("My dog's name is Cooper")
        assertNotNull(res1)
        assertEquals("My dog's name is Cooper", res1?.first)
        assertEquals(MemoryCategory.FACT, res1?.second)

        val res2 = manager.extractMemoryCandidate("Cooper is my dog")
        assertNotNull(res2)
        assertEquals("Cooper is my dog", res2?.first)
        assertEquals(MemoryCategory.FACT, res2?.second)

        val res3 = manager.extractMemoryCandidate("I live in Berlin")
        assertNotNull(res3)
        assertEquals("I live in Berlin", res3?.first)
        assertEquals(MemoryCategory.FACT, res3?.second)

        val res4 = manager.extractMemoryCandidate("I work at Google")
        assertNotNull(res4)
        assertEquals("I work at Google", res4?.first)
        assertEquals(MemoryCategory.FACT, res4?.second)

        val res5 = manager.extractMemoryCandidate("Call me Alex")
        assertNotNull(res5)
        assertEquals("Call me Alex", res5?.first)
        assertEquals(MemoryCategory.FACT, res5?.second)

        val res6 = manager.extractMemoryCandidate("I have a dog named Cooper")
        assertNotNull(res6)
        assertEquals("I have a dog named Cooper", res6?.first)
        assertEquals(MemoryCategory.FACT, res6?.second)

        // Behavioral rules & instructions
        val res7 = manager.extractMemoryCandidate("Always reply in German")
        assertNotNull(res7)
        assertEquals("Always reply in German", res7?.first)
        assertEquals(MemoryCategory.INSTRUCTION, res7?.second)

        val res8 = manager.extractMemoryCandidate("From now on, speak in French")
        assertNotNull(res8)
        assertEquals("Speak in French", res8?.first)
        assertEquals(MemoryCategory.INSTRUCTION, res8?.second)

        val res9 = manager.extractMemoryCandidate("Never use emojis")
        assertNotNull(res9)
        assertEquals("Never use emojis", res9?.first)
        assertEquals(MemoryCategory.INSTRUCTION, res9?.second)

        // Preferences & Topics
        val res10 = manager.extractMemoryCandidate("I prefer dark mode")
        assertNotNull(res10)
        assertEquals("I prefer dark mode", res10?.first)
        assertEquals(MemoryCategory.PREFERENCE, res10?.second)

        val res11 = manager.extractMemoryCandidate("The project is Solus")
        assertNotNull(res11)
        assertEquals("The project is Solus", res11?.first)
        assertEquals(MemoryCategory.GENERAL, res11?.second)
    }

    @Test
    fun testExtractMemoryCandidate_QuestionsAndTransientState_ReturnNull() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))

        // Questions ending in ?
        assertNull(manager.extractMemoryCandidate("What is my dog's name?"))
        assertNull(manager.extractMemoryCandidate("Where do I live?"))
        assertNull(manager.extractMemoryCandidate("Who is Cooper?"))
        assertNull(manager.extractMemoryCandidate("How do I make HTTP requests?"))
        assertNull(manager.extractMemoryCandidate("Can you help me?"))

        // Questions without ?
        assertNull(manager.extractMemoryCandidate("What is my dog's name"))
        assertNull(manager.extractMemoryCandidate("Where do I live"))

        // Transient emotions or conversational requests
        assertNull(manager.extractMemoryCandidate("I am tired"))
        assertNull(manager.extractMemoryCandidate("I am happy"))
        assertNull(manager.extractMemoryCandidate("I am ready"))
        assertNull(manager.extractMemoryCandidate("I have a question"))
        assertNull(manager.extractMemoryCandidate("Good morning!"))
    }

    @Test
    fun testAddMemory_SupersedesContradictingFact() {
        val store = PersistentMemoryStore(null)
        val manager = PersistentMemoryManager(context = null, memoryStore = store)

        manager.addMemory("My dog's name is Max", MemoryCategory.FACT)
        assertEquals(1, manager.totalMemoryCount)
        assertEquals("My dog's name is Max", manager.getAllMemories().first().content)

        // User updates the dog's name
        manager.addMemory("My dog's name is Cooper", MemoryCategory.FACT)
        assertEquals(1, manager.totalMemoryCount)
        assertEquals("My dog's name is Cooper", manager.getAllMemories().first().content)
    }

    @Test
    fun testFormatMemoryContext_OmitsFactsOnGreetings() {
        val store = PersistentMemoryStore(null)
        val manager = PersistentMemoryManager(context = null, memoryStore = store)

        manager.addMemory("I like to go to delhi", MemoryCategory.FACT)
        manager.addMemory("Always write code in Kotlin", MemoryCategory.INSTRUCTION)

        val greetingContextHi = manager.formatMemoryContext("hi")
        assertTrue(greetingContextHi.contains("Always write code in Kotlin"))
        assertFalse(greetingContextHi.contains("delhi"))

        val greetingContextWhatsUp = manager.formatMemoryContext("what's up")
        assertTrue(greetingContextWhatsUp.contains("Always write code in Kotlin"))
        assertFalse(greetingContextWhatsUp.contains("delhi"))
    }

    @Test
    fun testFormatMemoryContext_IncludesRelevantFactsOnTopicQuery() {
        val store = PersistentMemoryStore(null)
        val manager = PersistentMemoryManager(context = null, memoryStore = store)

        manager.addMemory("I like to go to delhi", MemoryCategory.FACT)
        manager.addMemory("My dog name is lambda", MemoryCategory.FACT)

        val relevantContext = manager.formatMemoryContext("where do I like to go")
        assertTrue(relevantContext.contains("delhi"))
        assertFalse(relevantContext.contains("lambda"))
    }

    @Test
    fun testFormatMemoryContext_OmitsIrrelevantFactsOnUnrelatedQuery() {
        val store = PersistentMemoryStore(null)
        val manager = PersistentMemoryManager(context = null, memoryStore = store)

        manager.addMemory("I like to go to delhi", MemoryCategory.FACT)

        val irrelevantContext = manager.formatMemoryContext("what is photosynthesis")
        assertEquals("", irrelevantContext)
    }

    @Test
    fun testFindAnswerForUserQuery_DestinationQuery() {
        val manager = PersistentMemoryManager(context = null, memoryStore = PersistentMemoryStore(null))
        manager.addMemory("I like to go to delhi", MemoryCategory.FACT)

        val answer = manager.findAnswerForUserQuery("where do I like to go")
        assertEquals("Based on what you told me, you like to go to delhi.", answer)
    }
}

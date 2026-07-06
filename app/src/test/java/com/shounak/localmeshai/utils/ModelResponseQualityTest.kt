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
}

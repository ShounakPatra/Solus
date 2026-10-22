package com.shounak.localmeshai.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PortableTokenLoopTest {
    @Test fun defaultTensorFlowExportInputNamesResolveToTokenInputs() {
        assertEquals("input_ids", PortableTextEngine.tokenInputName("serving_default_input_ids:0"))
        assertEquals("attention_mask", PortableTextEngine.tokenInputName("attention_mask"))
        assertEquals("position_ids", PortableTextEngine.tokenInputName("model/position_ids:0"))
    }
    @Test fun eosDoesNotLeakAndPromptIsNotDecodedAsAnswer() = runBlocking {
        val chunks = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val seen = mutableListOf<List<Int>>()
        val result = PortableTokenLoop.generate(intArrayOf(10, 20), 16, 10, setOf(99), { false },
            next = { tokens, step -> seen.add(tokens.toList()); if (step == 2) 99 else step + 1 },
            decode = { it.joinToString("") }, onText = chunks::add, onToken = counts::add)
        assertEquals("12", result); assertEquals(listOf("1", "2"), chunks)
        assertEquals(listOf(1, 2), counts)
        assertEquals(listOf(10, 20, 1, 2), seen.last())
    }

    @Test fun stopDuringInferenceSuppressesLateToken() = runBlocking {
        var stopped = false
        val result = PortableTokenLoop.generate(intArrayOf(1), 8, 4, setOf(99), { stopped },
            next = { _, _ -> stopped = true; 2 }, decode = { error("Must not decode a stopped token") },
            onText = { error("Must not publish a late token") }, onToken = { error("Must not count a late token") })
        assertEquals("", result)
    }

    @Test fun contextLimitBoundsInferenceCalls() = runBlocking {
        var calls = 0
        val result = PortableTokenLoop.generate(intArrayOf(1, 2), 4, 100, emptySet(), { false },
            next = { _, _ -> calls++; 3 }, decode = { "x".repeat(it.size) }, onText = {}, onToken = {})
        assertEquals(2, calls); assertEquals("xx", result)
    }

    @Test fun partialUnicodeIsHeldUntilItCanBeDecoded() = runBlocking {
        val chunks = mutableListOf<String>()
        val result = PortableTokenLoop.generate(intArrayOf(1), 10, 2, emptySet(), { false },
            next = { _, _ -> 2 }, decode = { if (it.size == 1) "\uFFFD" else "\u20AC" }, onText = chunks::add, onToken = {})
        assertEquals("\u20AC", result); assertEquals(listOf("\u20AC"), chunks)
    }

    @Test fun cancellationPropagatesWithoutPublishing() = runBlocking {
        try {
            PortableTokenLoop.generate(intArrayOf(1), 10, 2, emptySet(), { false },
                next = { _, _ -> throw CancellationException("Cancelled") }, decode = { "" },
                onText = { error("Unexpected output") }, onToken = {})
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}

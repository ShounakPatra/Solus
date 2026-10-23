package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType

class TfliteTextPlanTest {
    private fun tensor(name: String, shape: IntArray, signature: IntArray = shape, type: DataType = DataType.INT32) =
        TfliteTextPlan.Tensor(name, shape, signature, type)
    private fun logits() = tensor("Identity", intArrayOf(1, 64, 50257), type = DataType.FLOAT32)

    @Test fun officialGpt2Fixed64ExportAcceptsLegacyInputAndAuxiliaryCacheOutputs() {
        // Metadata read from openai-community/gpt2/64-8bits.tflite, not inferred from its filename.
        val outputs = listOf(logits()) + (1..12).map {
            tensor("Identity_$it", intArrayOf(1, 2, 12, 64, 64), type = DataType.FLOAT32)
        }
        val plan = TfliteTextPlan(listOf(tensor("input_1", intArrayOf(1, 64))), outputs, "gpt2", 50257)
        assertEquals(listOf("input_ids"), plan.names)
        assertEquals(64, plan.fixedSequence)
        assertEquals(0, plan.logitsIndex)
        val input = plan.input(0, listOf(42, 17), 50256).asIntBuffer()
        assertEquals(64, input.remaining())
        assertEquals(42, input.get()); assertEquals(17, input.get())
        while (input.hasRemaining()) assertEquals(50256, input.get())
        assertEquals(50257 * 4, plan.logitsOffset(intArrayOf(1, 64, 50257), 2))
        assertEquals(63 * 50257 * 4, plan.logitsOffset(intArrayOf(1, 64, 50257), 64))
        assertThrows(IllegalArgumentException::class.java) { plan.sequenceLength(65) }
    }

    @Test fun dynamicInputsKeepTheirTypesAndLogitsNeedNotBeOutputZero() {
        val plan = TfliteTextPlan(listOf(
            tensor("serving_default_input_ids:0", intArrayOf(1, 1), intArrayOf(-1, -1), DataType.INT64),
            tensor("attention_mask", intArrayOf(1, 1), intArrayOf(1, -1)),
            tensor("position_ids", intArrayOf(1, 1), intArrayOf(1, -1))),
            listOf(tensor("cache", intArrayOf(1, 2, 12, 1, 64), type = DataType.FLOAT32), logits()), "qwen2", 50257)
        assertNull(plan.fixedSequence)
        assertEquals(1, plan.logitsIndex)
        assertEquals(2, plan.sequenceLength(2))
        val ids = plan.input(0, listOf(3, 8), 0).asLongBuffer()
        assertEquals(3L, ids.get()); assertEquals(8L, ids.get())
        val mask = plan.input(1, listOf(3, 8), 0).asIntBuffer()
        assertEquals(1, mask.get()); assertEquals(1, mask.get())
        val positions = plan.input(2, listOf(3, 8), 0).asIntBuffer()
        assertEquals(0, positions.get()); assertEquals(1, positions.get())
    }

    @Test fun fixedMasksExcludePaddingAndDynamicLogitsCanBeLastTokenOnly() {
        val plan = TfliteTextPlan(listOf(tensor("input_ids", intArrayOf(1, 4)), tensor("attention_mask", intArrayOf(1, 4))),
            listOf(tensor("logits", intArrayOf(1, 4, 50257), type = DataType.FLOAT32)), "gpt2", 50257)
        val mask = plan.input(1, listOf(7, 3), 0).asIntBuffer()
        assertEquals(listOf(1, 1, 0, 0), List(4) { mask.get() })
        assertEquals(0, plan.logitsOffset(intArrayOf(1, 50257), 2))
        assertEquals(0, plan.logitsOffset(intArrayOf(1, 1, 50257), 2))
        val dynamic = TfliteTextPlan(listOf(tensor("input_ids", intArrayOf(1, 1), intArrayOf(1, -1))),
            listOf(tensor("logits", intArrayOf(1, 50257), type = DataType.FLOAT32)), "gpt2", 50257)
        assertEquals(0, dynamic.logitsOffset(intArrayOf(1, 50257), 2))
    }

    @Test fun unknownInputsCachesAndAmbiguousLogitsAreStillRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TfliteTextPlan(listOf(tensor("input_1", intArrayOf(1, 64))), listOf(logits()), "unknown", 50257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TfliteTextPlan(listOf(tensor("input_ids", intArrayOf(1, 64)), tensor("past_key_values", intArrayOf(1, 64))), listOf(logits()), "gpt2", 50257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TfliteTextPlan(listOf(tensor("input_ids", intArrayOf(1, 64))), listOf(logits(), logits()), "gpt2", 50257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TfliteTextPlan(listOf(tensor("input_ids", intArrayOf(1, 64)), tensor("attention_mask", intArrayOf(1, 32))), listOf(logits()), "gpt2", 50257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TfliteTextPlan(listOf(tensor("input_ids", intArrayOf(1, 64))),
                listOf(tensor("logits", intArrayOf(1, 50257), type = DataType.FLOAT32)), "gpt2", 50257)
        }
    }
}

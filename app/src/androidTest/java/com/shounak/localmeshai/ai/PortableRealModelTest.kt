package com.shounak.localmeshai.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.shounak.localmeshai.utils.PortableModelBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in phone coverage. Supply -e portableModelPath /path/to/model with complete companions.
 * Run separately for a supported TFLite export, cached ONNX decoder and safetensors checkpoint.
 * No model is downloaded by the test and missing fixtures are reported as skipped.
 */
class PortableRealModelTest {
    @Test fun realTokenizerGenerationResetAndMultiTurnHistory() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("portableModelPath")
        assumeTrue("Supply portableModelPath to run real-model inference", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        val format = requireNotNull(PortableModelBundle.format(file.name))
        PortableTextEngine.open(file, format, 2048).use { engine ->
            suspend fun answer(turns: List<Pair<String, String>>): String {
                var tokens = 0
                val result = engine.generate(engine.renderPrompt(turns, "", ""), 8, 0f, 1f, 1, {}, { tokens = it })
                assertTrue("Model must produce tokens", tokens > 0)
                assertTrue("Model must decode readable output", result.isNotBlank())
                return result
            }
            val first = answer(listOf("user" to "The capital of France is"))
            assertEquals("Reset must preserve deterministic generation", first,
                answer(listOf("user" to "The capital of France is")))
            answer(listOf("user" to "Hello ".repeat(4096), "assistant" to "Hello", "user" to "The capital of France is"))
            Unit
        }
    }
}

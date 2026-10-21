package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.models.ThinkingControlStrategy
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiteRtMetadataRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private val originalReader = NonGgufBundleInspector.nativeModelInfoReader
    @After fun restoreReader() { NonGgufBundleInspector.nativeModelInfoReader = originalReader }

    private fun field(number: Int, body: ByteArray): ByteArray {
        require(body.size < 128 && number < 16)
        return byteArrayOf(((number shl 3) or 2).toByte(), body.size.toByte()) + body
    }

    /** Real section-table layout; fake graph bodies because these tests do not run a native engine. */
    private fun container(metadata: ByteArray, name: String = "model.litertlm"): File {
        val bytes = ByteArray(1024)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "LITERTLM".toByteArray().copyInto(bytes)
        out.putInt(8, 1); out.putLong(24, 256)
        val fb = ByteBuffer.wrap(bytes, 32, 224).slice().order(ByteOrder.LITTLE_ENDIAN)
        fun short(p: Int, n: Int) { fb.putShort(p, n.toShort()) }
        fb.putInt(0, 16)
        short(4, 8); short(6, 8); short(10, 4)
        fb.putInt(16, 12); fb.putInt(20, 16)
        short(28, 6); short(30, 8); short(32, 4)
        fb.putInt(36, 8); fb.putInt(40, 12)
        fb.putInt(52, 3)
        short(80, 12); short(82, 24); short(86, 4); short(88, 12); short(90, 20)
        for ((index, type) in listOf(5, 3, 4).withIndex()) {
            val table = 96 + index * 32
            val slot = 56 + index * 4
            fb.putInt(slot, table - slot); fb.putInt(table, table - 80)
            val start = listOf(256L, 512L, 768L)[index]
            val end = if (index == 0) start + metadata.size else start + 256
            fb.putLong(table + 4, start); fb.putLong(table + 12, end); fb.put(table + 20, type.toByte())
        }
        metadata.copyInto(bytes, 256)
        return folder.newFile(name).apply { writeBytes(bytes) }
    }

    private fun useNativeFalse() {
        NonGgufBundleInspector.nativeModelInfoReader = object : NativeModelInfoReader {
            override fun inspect(filePath: String) = NativeModelInfoResult.Success(false, false, false, false, 4096)
        }
    }

    @Test fun legacyModelPrefixPersistsInitialReasoningWithoutInferringItFromOtherRoles() {
        useNativeFalse()
        val prefix = field(3, field(2, field(1, "<|im_start|>assistant\n<think>\n".toByteArray())))
        val result = NonGgufBundleInspector.inspectFile(container(prefix, "prefilled.litertlm"))
        assertTrue(result.errorMessage, result.isValid)
        val caps = ModelCapabilities.fromJson(result.capabilities.toJson())
        assertTrue(caps.reasoningPrefill)
        assertEquals(ThinkingCapability.ModelControlled, caps.thinking)
        val adapter = com.shounak.localmeshai.ai.LiteRtStreamAdapter("metadata", thinkingMode = true,
            startsInReasoning = caps.reasoningPrefill)
        adapter.onMessage(com.google.ai.edge.litertlm.Message.model("Reason"))
        assertEquals("Reason", adapter.currentSnapshot().reasoningText)
        assertEquals("", adapter.currentSnapshot().answerText)

        val userOnly = field(3, field(1, field(1, "<think>".toByteArray())))
        assertFalse(NonGgufBundleInspector.inspectFile(container(userOnly, "user.litertlm")).capabilities.reasoningPrefill)
        val closed = field(3, field(2, field(1, "<think></think>".toByteArray())))
        assertFalse(NonGgufBundleInspector.inspectFile(container(closed, "closed.litertlm")).capabilities.reasoningPrefill)
        val override = prefix + field(7, "{{ messages }}".toByteArray())
        assertFalse(NonGgufBundleInspector.inspectFile(container(override, "override.litertlm")).capabilities.reasoningPrefill)
        assertFalse(ModelCapabilities.fromJson(org.json.JSONObject()).reasoningPrefill)
    }

    @Test fun absentThinkingFieldRemainsUnknownWhenLegacyNativeApiReturnsFalse() {
        useNativeFalse()
        val oldPrompt = field(3, field(2, field(1, "<|im_start|>assistant\n".toByteArray())))
        val result = NonGgufBundleInspector.inspectFile(container(oldPrompt))
        assertTrue(result.errorMessage, result.isValid)
        assertEquals(ThinkingCapability.Unknown, result.capabilities.thinking)
    }

    @Test fun explicitFalseAndPositiveReasoningEvidenceAreDistinguished() {
        assertEquals(ThinkingCapability.Unsupported, NonGgufBundleInspector.parseLiteRtThinkingForTesting(byteArrayOf(88, 0)))
        assertEquals(ThinkingCapability.ModelControlled, NonGgufBundleInspector.parseLiteRtThinkingForTesting(byteArrayOf(88, 1)))
        val channel = field(8, field(1, "analysis".toByteArray()) + byteArrayOf(32, 1))
        assertEquals(ThinkingCapability.ModelControlled, NonGgufBundleInspector.parseLiteRtThinkingForTesting(channel))
        useNativeFalse()
        val customChannel = field(8, field(1, "deliberation".toByteArray()) + byteArrayOf(32, 1))
        val capabilities = NonGgufBundleInspector.inspectFile(container(customChannel, "custom.litertlm")).capabilities
        assertEquals(setOf("deliberation"), capabilities.reasoningChannels)
        assertEquals(capabilities, ModelCapabilities.fromJson(capabilities.toJson()))
        val legacyPrompt = field(3, field(2, field(1, "<think>".toByteArray())))
        assertEquals(ThinkingCapability.ModelControlled, NonGgufBundleInspector.parseLiteRtThinkingForTesting(legacyPrompt))
    }

    @Test fun templateControlIsPreservedRegardlessOfImportHintOrExtension() {
        useNativeFalse()
        val template = field(7, "{% if enable_thinking %}<think>{% endif %}".toByteArray())
        val file = container(template, "renamed.task")
        val storage = NonGgufBundleInspector.inspectFile(file)
        val url = NonGgufBundleInspector.inspectFile(file, ModelFormat.MediaPipeTask)
        assertTrue(storage.errorMessage, storage.isValid)
        assertEquals(ModelFormat.LiteRtLm, url.format)
        assertEquals(storage.capabilities, url.capabilities)
        assertEquals(ThinkingCapability.Toggleable, url.capabilities.thinking)
        assertEquals(ThinkingControlStrategy.NativeTemplate, url.capabilities.thinkingControl)
        assertTrue(ModelDownloader.isLikelyTaskBundle(file))
    }

    @Test(timeout = 2000) fun overflowingAndTruncatedMetadataAreRejectedWithoutLooping() {
        val invalid = listOf(
            byteArrayOf(34, 0xfa.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 15),
            byteArrayOf(58, 100, 1), byteArrayOf(0), byteArrayOf(88, 2),
            byteArrayOf(42) + ByteArray(10) { 0xff.toByte() }
        )
        invalid.forEachIndexed { index, bytes ->
            val result = NonGgufBundleInspector.inspectFile(container(bytes, "bad$index.litertlm"))
            assertFalse(result.isValid)
            assertTrue(result.errorMessage.orEmpty().contains("metadata"))
        }
    }

    @Test fun legacyProfileRequiresExactContentAndPreservesItsControlAfterPersistence() {
        val strategy = LegacyThinkingProfiles.match(LegacyThinkingProfiles.QWEN_SIZE, LegacyThinkingProfiles.QWEN_SHA256)
        assertEquals(ThinkingControlStrategy.QwenSoftSwitch, strategy)
        assertEquals(ThinkingControlStrategy.None, LegacyThinkingProfiles.match(LegacyThinkingProfiles.QWEN_SIZE, "wrong"))
        assertEquals(ThinkingControlStrategy.None, LegacyThinkingProfiles.match(100, LegacyThinkingProfiles.QWEN_SHA256))
        val caps = ModelCapabilities(thinking = ThinkingCapability.Toggleable, thinkingControl = strategy)
        assertEquals(caps, ModelCapabilities.fromJson(caps.toJson()))
        assertEquals("Hi\n/no_think", LegacyThinkingProfiles.applyPrompt("Hi", strategy, false))
        assertEquals("Hi\n/think", LegacyThinkingProfiles.applyPrompt("Hi", strategy, true))
        assertEquals("Hi\n/no_think", LegacyThinkingProfiles.applyPrompt("Hi\n/no_think", strategy, false))
        assertEquals("Hi", LegacyThinkingProfiles.applyPrompt("Hi", ThinkingControlStrategy.None, false))
    }
}

package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PortableExtendedProfilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun lite(root: File) = File(root,"model.tflite").apply { writeBytes(byteArrayOf(16,0,0,0)+"TFL3".toByteArray()+ByteArray(32)) }
    @Test fun tfliteImageClassificationNeedsProcessorConfigRatherThanTokenizer() {
        val root = temp.newFolder()
        File(root,"config.json").writeText("""{"model_type":"vit","image_size":224}""")
        val file = lite(root)
        assertEquals(ModelTask.ImageClassification,PortableModelBundle.inspect(file,ModelFormat.TensorFlowLite).task)
        assertNull(PortableModelBundle.runtimeRestriction(file,ModelFormat.TensorFlowLite))
    }
    @Test fun ctcSpeechDoesNotNeedChatEosOrAHuggingFaceTokenizer() {
        val root = temp.newFolder()
        File(root,"config.json").writeText("""{"model_type":"wav2vec2","architectures":["Wav2Vec2ForCTC"],"pad_token_id":0}""")
        File(root,"vocab.json").writeText("""{"<pad>":0,"a":1,"|":2}""")
        val file = lite(root)
        val caps = PortableModelBundle.inspect(file,ModelFormat.TensorFlowLite)
        assertTrue(caps.supportsAudio); assertFalse(caps.supportsVision)
        assertEquals(ModelTask.SpeechRecognition,caps.task)
        assertNull(PortableModelBundle.runtimeRestriction(file,ModelFormat.TensorFlowLite))
        assertNotNull(PortableModelBundle.runtimeRestriction(file,ModelFormat.Safetensors))
    }
    @Test fun whisperTfliteIsRestrictedRatherThanIncorrectlyRunAsCtc() {
        val root = temp.newFolder()
        File(root,"config.json").writeText("""{"model_type":"whisper"}""")
        assertNotNull(PortableModelBundle.runtimeRestriction(lite(root),ModelFormat.TensorFlowLite))
    }
    @Test fun originalPaligemmaGetsAVisionTaskButPaligemma2RequiresAnotherAdapter() {
        val root = temp.newFolder()
        val config = File(root,"config.json")
        config.writeText("""{"model_type":"paligemma","text_config":{"model_type":"gemma","eos_token_id":2}}""")
        File(root,"tokenizer.json").writeText("""{"model":{"vocab":{"a":0}}}""")
        val header = """{"w":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".toByteArray()
        val file = File(root,"model.safetensors").apply { writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array()+header+ByteArray(4)) }
        val caps = PortableModelBundle.inspect(file,ModelFormat.Safetensors)
        assertEquals(ModelTask.VisionLanguage,caps.task); assertTrue(caps.supportsVision)
        assertNull(PortableModelBundle.runtimeRestriction(file,ModelFormat.Safetensors))
        config.writeText(config.readText().replace("\"gemma\"","\"gemma2\""))
        assertNotNull(PortableModelBundle.runtimeRestriction(file,ModelFormat.Safetensors))
    }
    @Test fun portableThinkingSurvivesImportMetadataInspection() {
        val root = temp.newFolder()
        File(root,"config.json").writeText("""{"model_type":"qwen2","eos_token_id":2}""")
        File(root,"tokenizer.json").writeText("""{"model":{"vocab":{"a":0}}}""")
        File(root,"tokenizer_config.json").writeText(JSONObject().put("chat_template","{% if enable_thinking %}<think>{% endif %}").toString())
        assertEquals(ThinkingCapability.Toggleable,PortableModelBundle.inspect(lite(root),ModelFormat.TensorFlowLite).thinking)
    }
}

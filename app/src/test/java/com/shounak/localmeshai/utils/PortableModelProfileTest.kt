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

class PortableModelProfileTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun weights(file: File): File = file.apply {
        parentFile?.mkdirs()
        val header = """{"weight":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".toByteArray()
        writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array()+header+ByteArray(4))
    }
    @Test fun supportedTextArchitecturesAreNotRestrictedToQwen() {
        for (type in PortableModelProfile.textArchitectures) {
            val dir = temporary.newFolder()
            File(dir,"config.json").writeText("""{"model_type":"$type","eos_token_id":2,"max_position_embeddings":2048}""")
            File(dir,"tokenizer.json").writeText("""{"model":{"vocab":{"a":0}}}""")
            val primary = weights(File(dir,"model.safetensors"))
            assertEquals(ModelTask.Chat,PortableModelBundle.inspect(primary,ModelFormat.Safetensors).task)
            assertNull(type,PortableModelBundle.runtimeRestriction(primary,ModelFormat.Safetensors))
        }
    }
    @Test fun imageClassifierDoesNotRequireTextTokenizerOrEos() {
        val dir = temporary.newFolder()
        File(dir,"config.json").writeText("""{"model_type":"vit","image_size":224,"id2label":{"0":"cat"}}""")
        val caps = PortableModelBundle.inspect(weights(File(dir,"model.safetensors")),ModelFormat.Safetensors)
        assertEquals(ModelTask.ImageClassification,caps.task)
        assertTrue(caps.supportsVision); assertFalse(caps.supportsText); assertFalse(caps.supportsAudio)
    }
    @Test fun speechAndImageGenerationTasksAreIdentified() {
        assertEquals(ModelTask.SpeechRecognition,PortableModelProfile.task(JSONObject("""{"model_type":"whisper"}""")))
        for (pipeline in listOf("StableDiffusionPipeline","OnnxStableDiffusionPipeline","ORTStableDiffusionPipeline")) {
            assertEquals(ModelTask.ImageGeneration,PortableModelProfile.task(JSONObject(),JSONObject().put("_class_name",pipeline)))
        }
        assertEquals(ModelTask.Unknown,PortableModelProfile.task(JSONObject(),JSONObject().put("_class_name","CustomRemotePipeline")))
    }
    @Test fun unknownWeightsCanBeAcquiredButAreNotRunnableByExtension() {
        val primary = weights(File(temporary.newFolder(),"unknown.safetensors"))
        val caps = PortableModelBundle.inspect(primary,ModelFormat.Safetensors)
        assertEquals(ModelTask.Unknown,caps.task)
        assertFalse(caps.supportsText)
        assertEquals(LoadRestrictionCategory.RuntimeIncompatibility,PortableModelBundle.runtimeRestriction(primary,ModelFormat.Safetensors)?.category)
    }
    @Test fun taskMetadataRoundTripsAndLegacyMediaKeepsItsFlags() {
        for (task in ModelTask.entries) {
            val caps = ModelCapabilities(task=task)
            assertEquals(caps,ModelCapabilities.fromJson(caps.toJson()))
        }
        assertTrue(ModelCapabilities(task=ModelTask.ImageGeneration).isMediaModel)
        assertTrue(ModelCapabilities.fromJson(JSONObject().put("supportsAudio",true)).isMediaModel)
    }
    @Test fun nestedComponentsArePublishedWithoutCollisions() {
        val storage = ModelArtifactStorage(temporary.newFolder())
        val staging = storage.getStagingDir("pipeline").apply { mkdirs() }
        val names = listOf("unet/model.onnx","vae_decoder/model.onnx","text_encoder/model.onnx","model_index.json")
        val items = names.mapIndexed { index,name ->
            StagedArtifactItem(File(staging,"source$index").apply { writeText("component $index") },name,isPrimary=index==0,order=index)
        }
        val tx = storage.beginTransaction("pipeline",items,names.first(),"test",format=ModelFormat.Onnx,
            capabilities=ModelCapabilities(task=ModelTask.ImageGeneration))
        tx.commit()
        val bundle = requireNotNull(storage.getPublishedBundle("pipeline"))
        assertEquals(ModelTask.ImageGeneration,bundle.capabilities.task)
        assertEquals(4,bundle.mainArtifacts.size)
        bundle.mainArtifacts.forEachIndexed { index,artifact -> assertEquals("component $index",File(artifact.source.location).readText()) }
        assertNotEquals(storage.getStagingFile("pipeline","unet/model.onnx"),storage.getStagingFile("pipeline","vae_decoder/model.onnx"))
    }
    @Test fun unsafeComponentPathsRemainRejected() {
        for(path in listOf("../weights","unet/../../weights","/weights","C:/weights","unet\\weights","unet/%2e%2e/weights","unet//weights")) {
            try { PortableBundlePath.normalize(path); fail("Accepted $path") } catch (_:IllegalArgumentException) { }
        }
        assertEquals("unet/model.onnx",PortableBundlePath.normalize("unet/model.onnx"))
    }
    @Test fun runtimePolicyRefreshUnblocksNewAdaptersButPreservesOtherRestrictions() {
        val dir = temporary.newFolder()
        File(dir,"config.json").writeText("""{"model_type":"gpt2","eos_token_id":2}""")
        val primary = weights(File(dir,"model.safetensors"))
        val bundle = ModelBundle("gpt2", "GPT2", listOf(ModelArtifact(ArtifactRole.Main, primary.name, ArtifactSource.AppManaged(primary.path), primary.length(), primary.lastModified())),
            format=ModelFormat.Safetensors,loadRestriction=LoadRestriction("Only Qwen2 is supported",LoadRestrictionCategory.RuntimeIncompatibility))
        assertNull(PortableModelBundle.refreshRuntimeMetadata(bundle).loadRestriction)
        val restriction = LoadRestriction("Device memory limit", LoadRestrictionCategory.DeviceCompatibility)
        assertEquals(restriction,PortableModelBundle.refreshRuntimeMetadata(bundle.copy(loadRestriction=restriction)).loadRestriction)
    }
    @Test fun diffusionScheduleRejectsIncompatibleTrainingContracts() {
        assertTrue(PortableModelBundle.supportsDiffusionSchedule(JSONObject().put("prediction_type","v_prediction")))
        assertFalse(PortableModelBundle.supportsDiffusionSchedule(JSONObject().put("prediction_type","flow_prediction")))
        assertFalse(PortableModelBundle.supportsDiffusionSchedule(JSONObject().put("beta_schedule","linear")))
        assertFalse(PortableModelBundle.supportsDiffusionSchedule(JSONObject().put("num_train_timesteps",500)))
    }
    @Test fun repositoryPipelinesKeepIdenticallyNamedGraphsSeparate() {
        val names = listOf("bundle/model_index.json", "bundle/text_encoder/model.onnx", "bundle/unet/model.onnx", "bundle/vae_decoder/model.onnx", "bundle/tokenizer/tokenizer.json", "bundle/scheduler/scheduler_config.json")
        val files = names.map { RepoFile(it) }
        val repo = ResolvedRepoInfo("owner/model", "main", "abcdef", files)
        val variant = HuggingFaceModelResolver.groupVariants(repo).first { it.name.startsWith("Complete diffusion") }
        val spec = HuggingFaceModelResolver.createDownloadSpec(repo, variant, "pipeline")
        assertEquals(names.map { it.removePrefix("bundle/") }.toSet(), spec.artifacts.map { it.fileName }.toSet())
        assertEquals("unet/model.onnx", spec.artifacts.first().fileName)
    }
}

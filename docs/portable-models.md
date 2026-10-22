# Portable model imports and runtimes

Solus imports ONNX and safetensors model data and provides explicit pipelines for
text generation, image classification, speech transcription and text-to-image.
A file extension does not identify an architecture or provide a complete pipeline.
This is **not universal compatibility with arbitrary checkpoints or ONNX graphs**.

## Importing

Use **Models > Add model > Phone files**. For a single-directory model, select the
weights, configuration, tokenizer and processing files together. For a pipeline
with component folders, package the complete directory in a `.zip` or `.solusmodel`
ZIP archive and select that archive. Directory paths are preserved, so
`unet/model.onnx` and `vae_decoder/model.onnx` remain separate components.
Archives cannot contain traversal paths or names that collide ignoring case.

Hugging Face repository and file links discover supported formats and companion
files at the same immutable revision. Complete Diffusers component repositories
are offered as pipeline variants. ONNX external data references are resolved
relative to each graph, including component subdirectories. Direct HTTP text-model
URLs fetch `config.json` and `tokenizer.json` from the same URL folder; use a
repository or archive for complete media pipelines.

Unsupported architectures can be imported and show a runtime restriction. They are
not relabelled GGUF. Runtime restrictions are refreshed after app upgrades while
device and prior-crash restrictions are preserved. Runtime graph validation still
has the final say: an import succeeding does not prove its tensor interface or
operators are executable.

## Implemented adapters

| Task | Safetensors / Candle CPU | ONNX Runtime CPU |
| --- | --- | --- |
| Text generation | Dense GPT-2, Qwen2/Qwen2.5, Llama, Mistral, Gemma (original), Phi and Phi-3 | Decoder-only graphs with INT32/INT64 `input_ids`, optional masks/positions and supported merged-decoder controls; logits may be FP32/FP16/BF16 |
| Image classification | ViT, RGB NCHW input with processor normalization and label mapping | One FLOAT32 NCHW pixel input and floating `[1, classes]` scores |
| Speech transcription | Whisper, 16 kHz mono, up to 30 seconds per request | Whisper `encoder_model.onnx` plus a no-cache `decoder_model.onnx`, configuration and tokenizer |
| Image generation | Complete Diffusers StableDiffusionPipeline v1/v2: CLIP, UNet and VAE | Complete SD1/SD2 CLIP, UNet and VAE-decoder graphs with FLOAT32 graph inputs |

Text models need `config.json` and a complete Hugging Face `tokenizer.json`, or
BPE `vocab.json` plus `merges.txt`. EOS IDs may come from `generation_config.json`
or `config.json`. Sharded safetensors need `model.safetensors.index.json` and every
referenced shard. Prequantized safetensors, MoE variants, unsupported RoPE scaling,
LoRA-only weights, multimodal chat checkpoints, SDXL and Flux need other adapters.
Supported family names do not guarantee every variant of that family.

Hugging Face chat templates from `tokenizer_config.json` use the existing native
Jinja renderer without executing repository Python. Family-specific prompt
fallbacks are available. GPT-2 is a completion model, so its output should not be
judged as instruction-following chat. `solus_prompt_format` can override the
fallback. ONNX KV caches currently accept rank-4 `[B,H,P,D]` and rank-5
`[2,B,H,P,D]` layouts named `past_key_values.*` with matching `present.*` outputs;
other layouts require adapters. No-cache graphs recompute the prefix.

Classification and transcription produce their task results in the media chat.
They do not turn classifiers or ASR models into visual assistants. Image
classification uses the supplied resize/normalization processor contract.
Image generation currently uses an experimental fixed 256 x 256 output, 20 DDIM
steps and guidance 7.5. The scheduler configuration must use the supported SD1/SD2
training noise contract and epsilon or v_prediction. Generated images are stored
with the assistant message and use existing history and backup attachment handling.
Image editing, arbitrary resolutions, speech synthesis and general audio models
are not implemented.

All these paths run on CPU. Safetensors are loaded as FP32, which can need much
more memory than their disk size. Complete diffusion pipelines are especially
expensive on a phone. Existing acquisition and post-import load policies apply.

TensorFlow Lite retains its existing decoder-only text contract (dynamic INT32 or
INT64 token inputs and a FLOAT32 logits output); this work does not add TFLite media
adapters. Existing GGUF, LiteRT-LM and MediaPipe backends remain available.

## Native build and verification

Gradle's `buildCandleAndroid` task packages `libsolus_candle.so` for arm64-v8a.
The helper uses the Android NDK and either system Rust or the private ignored
installation in `tools/solus-rust`; cargo-ndk is unnecessary. LiteRT-LM stays at
0.18.0. The user runs `./gradlew assembleDebug`.

Focused Rust checks (substitute the installed NDK version if different):

```powershell
.\tools\build-candle.ps1 -NdkPath "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358" -OutputDirectory .\app\build\generated\candleJni -Check
.\tools\build-candle.ps1 -NdkPath "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358" -OutputDirectory .\app\build\generated\candleJni -Test
```

Host tests execute real ONNX graphs for INT32 inputs, cached prefill/decode/reset,
and FP16 logits. Native GPT-2 tests compare cached decoding with full-prefix
inference and verify Hugging Face Conv1D weight orientation. Import and metadata
tests cover safe paths, nested publication, runtime policy and task routing.
These checks cannot establish physical-device model quality or memory viability.
Validate one real bundle per task/format on an arm64 phone, including cancel,
unload/reload, saved history and generated-image backup/restore before release.

References: [Candle](https://github.com/huggingface/candle),
[ONNX Runtime Java](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OnnxTensor.html).

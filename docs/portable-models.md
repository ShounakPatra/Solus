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

Image questions now have an experimental safetensors adapter for original
PaLiGemma (SigLIP + original Gemma), and fused ONNX/TFLite decoder adapters with
one RGB image tensor plus integer `input_ids`, optional `attention_mask` and
`position_ids`, and floating logits. These fused graph adapters recompute the
prefix and image features per token. Separate vision-encoder/embedding/decoder
exports and cache-dependent vision decoders still need architecture adapters.
PaLiGemma 2, arbitrary LLaVA/Qwen-VL safetensors and arbitrary VLMs are not enabled
by recognizing their filename. Configure image placeholder IDs/counts and
`solus_vision_prompt` when the exported graph needs them.

TFLite additionally supports single-output RGB classifiers with NCHW/NHWC layouts,
FLOAT32 or per-tensor INT8/UINT8 inputs and outputs. Quantized values use each
tensor's actual scale and zero point; processor resize, normalization and labels
must match the export. Graphs requiring SELECT_TF_OPS/Flex need a matching custom
runtime build: Maven does not publish Select TF Ops at this project's pinned
2.17.0 version. LiteRT-LM stays 0.18.0.

ONNX/TFLite CTC speech exports accept one mono waveform tensor and an optional
integer or floating attention mask, and return `[1, frames, vocabulary]` logits.
Supply `vocab.json`, preprocessing configuration (including sample rate and
normalization), and a `ForCTC` architecture or `solus_audio_decoder: "ctc"`.
Decoding removes blank tokens and collapses adjacent repeats while preserving
repeats separated by blanks. TFLite Whisper component exports, speech synthesis,
and arbitrary audio feature interfaces require further adapters.

Thinking is supported in portable text and fused vision chat when the model's
tokenizer template declares it. Templates using `enable_thinking` receive the
actual request setting; fixed `<think>` templates are model-controlled. Reasoning
prefills are passed to the existing reasoning parser so the reasoning section and
answer remain separate. `solus_thinking: "model_controlled"` can declare a fixed
reasoning model, and `"unsupported"` disables detection. A thinking flag cannot
teach an ordinary model to reason or turn a fixed reasoning template into a
toggleable one. Distilled reasoning models using supported Llama/Qwen2 weights
use those existing safetensors adapters.

Text models need `config.json` and a complete Hugging Face `tokenizer.json`, or
BPE `vocab.json` plus `merges.txt`. EOS IDs may come from `generation_config.json`
or `config.json`. Sharded safetensors need `model.safetensors.index.json` and every
referenced shard. Prequantized safetensors, MoE variants, unsupported RoPE scaling,
LoRA-only weights, other multimodal architectures, SDXL and Flux need other adapters.
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

TensorFlow Lite retains its decoder-only text contract (dynamic INT32 or INT64
token inputs and a FLOAT32 logits output) alongside the media adapters above.
Existing GGUF, LiteRT-LM and MediaPipe backends remain available.

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

# Solus Upstream llama.cpp Integration

## 1. Upstream Pin
- **Upstream Commit:** `5cdadfc326163ed1fa66cf609fb1761f7928900c` (14 October 2026 master revision, following v0.6.0 stable release).
- **Repository:** `https://github.com/ggml-org/llama.cpp`
- **Vendoring Mode:** In-tree source tracking in `app/src/main/cpp/llama.cpp` (no git submodules).

## 2. Dependency Pins & Tooling
- **SPIRV-Headers:** Commit `86f980c731e62ae4eaf383d320449d71687936bf` in `app/src/main/cpp/spirv-headers`.
- **Android NDK:** `28.2.13676358` (API level 28 min, 64-bit arm64-v8a target, 16 KB page-size alignment, lld linker).
- **CMake:** `3.22.1` with Ninja generator. Host toolchain scoped strictly to `CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows"`; discovery of Linux/macOS host compilers and shader tools is automatic.
- **Shader Compilation:** Host `vulkan-shaders-gen` built via portable LLVM-MinGW 20261006 (UCRT x86_64) using `tools/prepare-native-tools.ps1` and `app/src/main/cpp/cmake/windows-host-toolchain.cmake`; device shader compiler uses NDK `glslc.exe`.
- **Concurrency Bounds:** Ninja job pools configured with `compile_pool=2` and `link_pool=1` to prevent memory exhaustion during parallel C++ compilation.

## 3. Implemented Architecture & Completed Repairs

### Multimodal Pipeline (`mtmd`) & Chat Templating
1. **Structured Multimodal Requests:**
   - Media parts (`MultimodalMediaPart`) associate an explicit `message_index` with conversation turns.
   - Upstream Jinja chat formatting applies to both text and multimodal conversations, placing media markers (`<image>`, `<audio>`) within the corresponding user turn prior to template expansion instead of prepending them globally.
   - Forwarding system instructions, conversational history, generation prompt, and `inputs.enable_thinking` dynamically.
2. **Audio Processing & Resampling:**
   - RIFF/WAVE container header chunk parsing (`fmt `, `data`, 16-bit PCM, 32-bit float PCM) in `AudioUtils.kt`.
   - Audio resampling to match the projector's native required sample rate (`engine.getMetadata()?.audioSampleRate ?: 16000`), preventing audio distortion on 24 kHz projectors.
3. **Encoder-Decoder & Model Lifecycle:**
   - Full support for models with encoders (`llama_model_has_encoder`), configuring decoder start tokens (`llama_model_decoder_start_token`) or BOS tokens.
   - Projector initialization failures in `nativeInitModelBundle` strictly return error code `-4` rather than falsely reporting success.
   - True decode failures (`SOLUS_FAILURE_PROMPT_DECODE_FAILED` / `SOLUS_FAILURE_GENERATION_DECODE_FAILED`) are accurately reported and separated from user cancellations.
   - Automatic 1-time CPU recovery fallback on recoverable Vulkan execution errors (`NumericalInstability`, `BackendExecution`, `GenerationDecodeFailed`, `PromptDecodeFailed`).

### Phone Storage Import & In-Place Weights
1. **Direct JNI Procfs Access:**
   - File descriptors from Android Storage Access Framework (`openFileDescriptor(uri, "r")`) are accessed directly via `/proc/self/fd/${pfd.fd}` through native JNI, avoiding weight copying.
2. **Metadata Inspection & Split Set Validation:**
   - Native metadata inspection using upstream GGUF reader without loading tensors.
   - Split GGUF sets validate unique contiguous zero-based indices `0..splitCount - 1`, matching architecture, and at most one projector.
3. **Permission Lifecycle & Relinking:**
   - Persistable URI read permissions tracked and rolled back upon inspection failure, exception, or cancellation.
   - Startup verification (`checkBundleAccessible`) validates physical accessibility of all artifacts. If permissions are revoked or files moved, models transition to `Failed` with a prompt to relink.
   - UI provides "Relink files" action via SAF document picker to reacquire permissions and update bundle registrations.
   - Unlinking a storage import removes only the registration, preserving original user files on device storage.

### Atomic Artifact Bundles & Storage
1. **Multi-Artifact Publication:**
   - `ModelArtifactStorage.publishArtifactBundle` stages all model shards and companion projectors into an isolated generation directory before committing `manifest.json`.
   - Manifest stores all relative artifact paths and validates individual checksums.

### Local Patches Retained
1. **Numerical Anomaly Instrumentation:**
   - `ggml-quants.h` / `ggml-quants.c`: Atomic flag recording and bounds checks for NaN and non-finite values during quantization.
2. **True CPU Backend Isolation:**
   - `gguf-backend-config.h`: Policy version 2 isolates CPU-only execution with `s_cpu_only_devices = { nullptr }`, zero GPU layers, disabled KQV offloading, and verification of zero attached GPU compute buffers.
3. **Memory Management API:**
   - KV cache clearing migrated to `llama_memory_clear` and `llama_memory_seq_rm` through `llama_get_memory`.

## 4. Pending Physical Device Verification
*These scenarios are not executed during local builds and require physical on-device verification:*
1. Existing catalog GGUF text generation and unchanged LiteRT/MediaPipe loading.
2. Single-file and split-file storage imports, restart persistence, relinking, and removal without deleting originals.
3. Vision GGUF with matching projector and audio-input model with text responses.
4. Missing/mismatched projector, incomplete shards, unsupported provider, insufficient memory, cancellation, and model switching.
5. Recurrent-model history handling, Jinja templates, and CPU recovery after GPU failure.

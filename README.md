<div align="center">

<img src="https://github.com/user-attachments/assets/6d0fec0c-5f12-4c6d-83b4-9478065aca5b" width="160" alt="Solus logo" />

# Solus

### Your models. Your conversations. Your device.

Chat, write, code and explore documents with AI models running on your Android phone.

**v6.0.5 · Android 11+ · ARM64-v8a**

<a href="https://github.com/ShounakPatra/Solus/releases/download/v6.0.5/app-release.apk" title="Download Solus v6.0.5">
  <img src="docs/assets/download-solus-apk.svg" alt="Get the latest signed Solus APK from GitHub Releases" width="360" height="72" />
</a>

[![Android CI](https://github.com/ShounakPatra/Solus/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ShounakPatra/Solus/actions/workflows/android-ci.yml)
![Version 6.0.5](https://img.shields.io/badge/version-6.0.5-0EA5E9)
![Android 11 or newer](https://img.shields.io/badge/Android-11%2B-3DDC84)
![ARM64](https://img.shields.io/badge/ABI-arm64--v8a-22C55E)
![Apache 2.0](https://img.shields.io/badge/license-Apache_2.0-A970FF)

[Get started](#get-started) · [Features](#made-for-your-own-models) · [Compare apps](#compare-apps) · [Model compatibility](#model-compatibility) · [Build from source](#build-from-source)

</div>

> [!NOTE]
> The download button points to the v6.0.5 release APK. Check the release asset for its exact download size and checksum before installing. Model weights are downloaded separately.

---

## App preview

<p align="center">
  <img src="docs/screenshots/chat-response.jpeg" width="260" alt="Solus chat and response controls" />
  &nbsp;
  <img src="docs/screenshots/model-manager-overview.jpeg" width="260" alt="Solus model library" />
  &nbsp;
  <img src="docs/screenshots/model-download-progress.jpeg" width="260" alt="Solus model download progress" />
</p>

<p align="center"><sub>Chat, manage models and follow downloads in the glass workspace. Controls vary by version and model.</sub></p>

## Get started

1. **Install** the [v6.0.5 signed APK](https://github.com/ShounakPatra/Solus/releases/download/v6.0.5/app-release.apk). v6.0.5 requires **Android 11+** and an **ARM64-v8a** device.
2. **Choose a model** in **Models**, or use **Add model** to import one from your phone, Hugging Face or a direct URL.
3. **Start a chat** after the model finishes loading. A small model is a good place to start if your phone has limited RAM.
4. **Add context** with a PDF or DOCX. Image and audio inputs require a compatible model and export.

An existing installation can be updated without uninstalling when Android accepts the package and signing identity. Back up important chats first. Model weights need additional storage and are not included in workspace backups.

## What's new in v6.0.5

Solus v6.0.5 expands supported model pipelines and improves the existing workspace:

- **More model formats:** architecture-specific ONNX, TensorFlow Lite and SafeTensors paths alongside GGUF, LiteRT-LM and MediaPipe.
- **More complete imports:** companion configuration, tokenizer and weight files; preserved archive folders; checks for missing files and unsupported export contracts.
- **A Hugging Face file browser:** select individual or multiple files, include required companions automatically, or save files without registering them as runnable models.
- **Conversation skills:** tools for compatible text and vision models, saved tool-use records, and settings that apply to the next message in the current chat.
- **Optional neural document search:** a small downloadable BGE model, local fallback search, re-indexing and clearer document management.
- **Runtime preparation and diagnostics:** optional warm-up, backend/thread tuning, prompt/first-token timing, and improved thinking and cancellation handling.
- **Reliability repairs:** document scoping and retained originals, history/deletion handling, download recovery, audio decoding and signed-update selection.

History search and pinning, temporary chats, backups, document sources, per-model profiles and the glass interface already existed; they are retained and improved.

Read the release notes on [GitHub Releases](https://github.com/ShounakPatra/Solus/releases). Available features and performance depend on the model export, runtime and device; not every model supports every input or tool.

## Made for your own models

Use the workspace for a quick question or build a longer conversation around your own files.

| | What you can do |
| --- | --- |
| 💬 **Chat, write and code** | Local generation, formatted code and math, personas, prompt editing and regeneration |
| 📚 **Ask about documents** | PDF, DOCX, text and code extraction; chat-scoped context and original-file previews |
| 🎙️ **Use images and audio** | Camera, photos, audio files and microphone input with compatible models |
| 🛠️ **Give models tools** | Built-in and custom skills, with collapsible **Used tool** records |
| 🗂️ **Keep your workspace** | Search and pin history, temporary chats, saved memories and per-model profiles |
| 💾 **Back up and restore** | Move chats, memories, settings and available attachments between installations |
| 📦 **Manage model files** | Catalog and Hugging Face downloads, companion bundles, imports and relinking |
| ⚙️ **Tune your experience** | Compatible CPU/GPU options, optional warm-up, performance telemetry and glass controls |

## Compare apps

These projects have different goals. The table summarizes their publicly documented
features; **Not verified** means the linked documentation did not establish the
answer, not that the feature is absent. Model, version and device support vary.

| Feature | Solus v6.0.5 | AI Edge Gallery | PocketPal AI | LM Playground | ChatterUI |
| --- | --- | --- | --- | --- | --- |
| Local model formats | GGUF, LiteRT-LM, MediaPipe and selected portable exports | LiteRT | GGUF | GGUF | GGUF |
| Import `.gguf`, `.litertlm` and `.task` | Supported exports | Not verified for all three | GGUF documented | GGUF documented | GGUF documented |
| Images, audio and document chat | Available with compatible models and document extraction | Image and audio features documented; document chat not verified | Voice output documented; other inputs not verified | Image, voice dictation and document Q&A documented | Text-to-speech documented; other inputs not verified |
| Thinking and tools | Available on compatible GGUF and LiteRT-LM models; model-dependent | Thinking on supported models; separate mobile actions | Tools documented; thinking not verified here | Reasoning and tools documented | Not verified |
| Source passages for document answers | Retrieved passages available | Not verified | Not verified | Document retrieval documented; passage display not verified | Not verified |
| Temporary chats excluded from history | Yes | Not verified | Not verified | Not verified | Not verified |
| Backup chats, memories and settings | Yes; model weights excluded | Not verified | Not verified | Not verified | Not verified |
| Android requirement | Android 11+ | Android 12+ | Check project | Check project | Check project |
| Pricing | Free and open source | Open source | Free app; optional paid Pals | Check project | Check project |

Sources: [AI Edge Gallery](https://github.com/google-ai-edge/gallery),
[PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai),
[LM Playground](https://github.com/andriydruk/LMPlayground), and
[ChatterUI](https://github.com/Vali-98/ChatterUI). Features and pricing can change;
check each project's latest release before deciding.

## Tools and thinking

Enable **Tool calling** in Settings and select only the skills you need in **Manage tool skills**. Tool and document-search setting changes apply to the **next message in the current chat**.

Skills include calculator, date/time, document search, hashes, text statistics, unit conversion, random selection, QR codes, learning and adventure prompts, mood tracking, reminders, calendar actions, maps, Wikipedia and email drafts. Custom prompt skills can also be added.

Tools require compatible model behavior and templates. A plausible answer is not proof that a tool ran: check the **Used tool** record. Many enabled schemas consume context and can increase prompt-processing time; small models may ignore tool requests.

**Thinking can remain enabled while tools are on**, including supported LiteRT vision conversations. Toggleable models follow the Think setting; model-controlled reasoning and unsupported models have different controls. A model need not return a separate reasoning block on every response.

Wikipedia uses the network. Calendar/email actions can open another app or a review screen, and external services may need connectivity. Review actions before completing them.

## Documents and sources

Attach a document using **+ → Files (RAG)**, or select a library document through **Knowledge Base**. Document context is scoped to the current chat's uploads and explicitly selected library documents; a new chat should not silently search another chat's uploads.

Solus can use extracted text directly or retrieve relevant passages. **Sources** identifies retrieved context, not independent verification of every claim. An answer using directly attached text does not necessarily have a Sources chip.

The optional **bge-small-en-v1.5** search model is approximately **33 MiB**, runs locally after download, and can be removed separately from your documents. Built-in local search is available without the neural model. Neural-model changes can require re-indexing.

Text-based PDF and DOCX files are supported. **Scanned/image-only PDFs require OCR first**; password protection, damaged archives, unsupported layouts or extraction limits can prevent useful text extraction.

## Model compatibility

| Format | Runtime / scope | Required assets |
| --- | --- | --- |
| `.gguf` | llama.cpp CPU or qualified Vulkan backend | Complete split set and compatible projector when required |
| `.litertlm` | LiteRT-LM **0.18.0** | A compatible packaged export; media/backend support is artifact-specific |
| `.task` | MediaPipe GenAI | A compatible packaged task model |
| `.onnx` | ONNX Runtime CPU; supported text and media tensor contracts | Configuration, processing/tokenizer files and any external data/component graphs |
| `.safetensors` | Candle CPU; implemented architecture adapters | Configuration, tokenizer/processor assets and all indexed shards/components |
| `.tflite` | TensorFlow Lite CPU; supported graph/task contracts | Supported embedded metadata or the export's required processing assets |

A **file extension is not a compatibility guarantee**. Recognizing a repository, filename or model family cannot supply a missing architecture adapter, custom operator, tokenizer or vision/audio pipeline.

- SafeTensors text adapters cover supported dense GPT-2, Qwen2/Qwen2.5, Llama, Mistral, original Gemma, Phi and Phi-3 layouts. Quantized checkpoints, MoE and other variants can require different adapters.
- Portable media paths include supported classification, transcription, original PaLiGemma/fused vision and SD1/SD2 diffusion contracts. These are experimental CPU paths, not support for arbitrary vision or audio checkpoints.
- Raw TFLite LLM exports with custom operations or unsupported external KV-cache interfaces may need the author's packaged `.task`/`.litertlm` version or a different runtime.
- SafeTensors loading can use FP32 memory substantially larger than the file's disk size. Diffusion and multimodal pipelines can be especially demanding.
- Importing or initializing successfully does not establish output quality, numerical correctness or acceptable performance.

See the [portable-model contract guide](docs/portable-models.md) and [llama.cpp integration notes](docs/llama-upstream.md) for detailed limits.

### Import methods

**Hugging Face / URL:** Paste a repository, folder or file link. HF imports resolve an immutable revision, select bundle companions and check compatibility before full weight downloads. **Save files only** stores files without claiming inference support.

**Phone files:** Select a complete model bundle. A `.zip` or `.solusmodel` archive can preserve component folders. ZIP packaging does not add support for the model inside it.

**Direct HTTP URLs:** Portable text exports need their configuration and tokenizer companions. Use a repository link or complete archive when metadata/components are in different folders.

Keep phone-imported originals accessible; use **Relink files** after moving them or changing storage permissions. Unlinking a storage import preserves original files; deleting an app-managed download removes that managed bundle.

## Privacy and offline use

Model inference and document retrieval run locally after required assets are available. Solus does not automatically upload conversations or crash reports.

Network operations include model/search-model downloads, update checks, Wikipedia and external services you choose to use. Maps and other Android intents may hand information to another app.

- Temporary chats skip saved conversation history and memory extraction.
- HF access tokens are encrypted using Android Keystore-backed storage and excluded from workspace backups.
- Backups can contain private chats, memories and documents. They exclude model weights, access tokens and document indexes; restore or re-index source documents when needed.
- Sharing a report, opening an external app or exporting a backup is a separate action. Review the destination and contents.

Read the [security policy](SECURITY.md) for storage, credentials and reporting details.

## Performance and recovery

Warm-up moves some one-time runtime work into model preparation. It can increase loading time and may be skipped on low-memory devices. A new conversation, long history, document context, tool schemas or media encoders can still make the next prompt expensive.

**Tune speed** and backend qualification are device/model-specific. Auto may select CPU when no qualifying GPU result exists. GPU availability does not guarantee correctness or a speed improvement.

Prompt timeouts and Stop controls help recover from stalled work while preserving runtime ownership until native execution unwinds. They do not guarantee an immediate stop for every native operation. Reduce enabled skills, context or model size if a phone remains slow.

## Build from source

Use the checked-in Gradle wrapper, a Gradle JVM supported by the configured Android Gradle Plugin, Android SDK **37**, NDK **28.2.13676358** and CMake **3.22.1**. Java/Kotlin bytecode targets Java 17. The wrapper is **Gradle 9.8.2**; the UI uses Kotlin **2.4.20** and Compose.

Portable SafeTensors support also needs Rust and the `aarch64-linux-android` target. On Windows, [native tooling preparation](tools/prepare-native-tools.ps1) and the [Candle build helper](tools/build-candle.ps1) support the private ignored tool installations.

Build and run the checks with the Gradle wrapper:

```bash
git clone https://github.com/ShounakPatra/Solus.git
cd Solus
rustup target add aarch64-linux-android

./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

On Windows, use `.\gradlew.bat`. Build parallelism can be adjusted to suit the available CPU and memory.

Keep `local.properties`, `keystore.properties`, signing keys, model weights and diagnostics out of commits. A local signed release needs your signing configuration:

```bash
./gradlew assembleRelease
```

APK locations are `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`. Inspect generated output metadata for the actual filename.

### Validation and production releases

CI runs host native/Rust checks, unit tests, lint, instrumentation-test compilation, Android builds and APK packaging checks. **Compiling instrumentation tests does not run them on a phone.** Local tests and successful builds do not establish compatibility with every model.

Before publication, verify the exact signed APK's package/version, Android minimum, signing certificate, DEX/native packaging, upgrade behavior and device smoke tests. Use [the release upload guide](docs/release-artifact-upload.md): production signing uses the approval-protected **release** environment and a successful matching CI run from `main`.

## Contributors & community

- [Report a reproducible issue](https://github.com/ShounakPatra/Solus/issues): include the app version/code, model file/revision, runtime, device, enabled tools and relevant redacted logs.
- [Contribute](CONTRIBUTING.md) · [Report a security issue privately](SECURITY.md).
- Maintainer: **Shounak Patra** · [GitHub](https://github.com/ShounakPatra) · [Email](mailto:shounakpatra68@gmail.com).
- AI-assisted development: **Claude (Anthropic)** and **Codex (OpenAI)** contributed to implementation, review and documentation. Project maintenance and release decisions remain with Shounak Patra.

Solus is licensed under [Apache 2.0](LICENSE). Imported models and third-party runtimes retain their own licenses.

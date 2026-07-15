<div align="center">

<img src="https://github.com/user-attachments/assets/6d0fec0c-5f12-4c6d-83b4-9478065aca5b" width="170" alt="Solus logo" />

# Solus

### Private, local AI running entirely on your Android device.

Chat, reason, code, and explore documents with local language models. Once a model is downloaded, your prompts, documents, and generated responses stay 100% on your phone.

<p>
  <a href="https://github.com/ShounakPatra/Solus/releases/download/v1.1.0/release.apk">
    <img src="https://img.shields.io/badge/Download-Solus_1.1.0_APK-20C997?style=for-the-badge&logo=android&logoColor=white" alt="Download Solus 1.1.0 APK" height="42" />
  </a>
</p>

<p>
  <img src="https://img.shields.io/github/stars/ShounakPatra/Solus?style=for-the-badge&logo=github&label=Stars&color=FFD700" alt="GitHub stars" />
  <img src="https://img.shields.io/github/downloads/ShounakPatra/Solus/total?style=for-the-badge&label=Downloads&color=20C997" alt="Total downloads" />
  <img src="https://github.com/ShounakPatra/Solus/actions/workflows/android-ci.yml/badge.svg" alt="Android CI" />
  <img src="https://img.shields.io/badge/version-1.1.0-4C8DFF?style=for-the-badge" alt="Version 1.1.0" />
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0 or newer" />
  <img src="https://img.shields.io/github/license/ShounakPatra/Solus?style=for-the-badge&color=A970FF" alt="Apache 2.0 license" />
</p>

**🔒 No cloud inference. 💳 No subscriptions. 📡 Works entirely offline.**

</div>

---

## 📱 App Preview

<p align="center">
  <img src="docs/screenshots/chat-response.jpeg" width="280" alt="Solus chat UI" style="border-radius: 12px; margin: 10px;" />
  <img src="docs/screenshots/model-manager-overview.jpeg" width="280" alt="Solus Model Manager" style="border-radius: 12px; margin: 10px;" />
</p>
<p align="center">
  <sub><b>Private Local Chat</b> (Left) &bull; <b>Guided Model Management</b> (Right)</sub>
</p>

---

## ✨ Features & Highlights

*   **💬 Local Multi-turn AI Chat:** Powered directly by your Android device's hardware.
*   **🧠 Advanced Reasoning (Thinking Mode):** Toggle thinking mode control for reasoning-optimized models (like DeepSeek R1).
*   **🖼️ Multimodal Vision Support:** Ask questions about images, photos, and camera inputs using compatible vision-language models.
*   **📐 Math & Formula Rendering:** Beautiful, native LaTeX rendering with horizontal scroll containers, copy actions, and text selection support.
*   **📄 Deep Document Analysis:** Analyze local text, Markdown, code files, and documents directly inside your chats.
*   **⏬ Resumable Downloads:** Storage-friendly download manager featuring download speed, progress indicators, pause/resume, and crash recovery.
*   **📐 Device-Aware Model Guidance:** RAM, chipset, runtime, and compatibility flags help you pick the perfect model for your phone.
*   **✨ Premium Glassmorphic UI:** A responsive, modern user interface built using Jetpack Compose and Haze blur effects.

---

## 🛠️ Tech Stack

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/MediaPipe_GenAI-0097A7?style=for-the-badge&logo=google&logoColor=white" alt="MediaPipe" />
  <img src="https://img.shields.io/badge/LiteRT_LM-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white" alt="LiteRT LM" />
  <img src="https://img.shields.io/badge/Material_Design_3-757575?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Material 3" />
</p>

- **UI & Layout:** Jetpack Compose + Material 3 + Haze (Glassmorphic blur backdrop)
- **Local Runtimes:** MediaPipe GenAI (LLM & Vision inference) + LiteRT-LM (TensorFlow Lite runtime)
- **Document Processing:** PDFBox Android + custom file-format parsers
- **Build System:** Gradle (Kotlin DSL)

---

## 📊 Solus vs Google AI Edge Gallery

Solus focuses on providing a clean, assistant-like private chat app with document ingestion and download resume support. Google AI Edge Gallery is a broader playground highlighting official Google AI Edge capabilities.

| Feature / Capability | Solus | Google AI Edge Gallery |
|---|---|---|
| **On-device model inference** | ✅ Local generation after model download | ✅ Local generation after model download |
| **Multi-turn Chat** | ✅ | ✅ |
| **Thinking Mode Toggle** | ✅ Supported reasoning models | ✅ Supported models (Gemma 4, etc.) |
| **Image Questions** | ✅ Multi-modal vision support | ✅ Ask Image workflow |
| **Persistent Chat History** | ✅ | ✅ Session continuity |
| **In-app model downloads** | ✅ Curated catalog | ✅ Curated LiteRT models |
| **Download Recovery & Resume** | ✅ Pause, resume, and progress info | ❌ Basic download stream |
| **Hardware & RAM Guidance** | ✅ Safety checks | ❌ Basic benchmarks |
| **Document/Code Analysis** | ✅ PDF, DOCX, ODT, RTF, Markdown, Code | ❌ Not a core built-in workflow |
| **Target Android Version** | ✅ Android 8.0+ | Android 12+ |
| **License** | ✅ Apache 2.0 | ✅ Apache 2.0 |

---

## 🎯 Model Compatibility Guide

Select the model that best fits your storage, memory capacity, and target task:

| Need | Recommended Starting Point | Size | Gated |
|---|---|:---:|:---:|
| **Everyday conversation & summarization** | Qwen 2.5 Instruct / Gemma 3 | ~1.5 - 3 GB | No / Yes |
| **Kotlin, Python, and coding help** | Qwen 2.5 Coder | ~2.2 GB | No |
| **Math, planning & deep reasoning** | DeepSeek R1 Distill | ~1.8 GB | No |
| **Image description & visual search** | Gemma 3n Vision / FastVLM | ~2.5 GB | Yes |
| **Limited RAM / Storage testing** | Qwen 2.5 0.5B / TinyLlama | ~400 MB | No |

---

## 📂 Project Structure

```text
Solus
├── app
│   ├── src
│   │   ├── main
│   │   │   ├── java/com/shounak/localmeshai
│   │   │   │   ├── ai/            # Model inference, session management, and parsing
│   │   │   │   ├── ui/            # Jetpack Compose UI (screens, themes, components)
│   │   │   │   │   ├── components/# Reusable elements (bubbles, math cards)
│   │   │   │   │   ├── screens/   # Primary views (Chat, Models, ImageGen)
│   │   │   │   │   └── theme/     # Glassmorphic themes and color palettes
│   │   │   │   └── utils/         # Markdown, LaTeX parser, math normalizers, glass shaders
│   │   │   └── res/               # Layouts, drawables, XML assets
│   │   └── test/                  # Unit and integration test suites
│   └── build.gradle.kts
└── gradle/                        # Dependency version catalog (libs.versions.toml)
```

---

## 📥 Installation

1. Go to the [Solus Releases](https://github.com/ShounakPatra/Solus/releases) page.
2. Download the latest `release.apk`.
3. Open the APK file on your device. (Enable "Install unknown apps" from settings if prompted).
4. Run the app, head over to the **Models** tab, and select a compatible model to download.

*Requires **Android 8.0 (API 26) or newer** and a compatible ARM64 processor.*

---

## 🏗️ Build from Source

### Requirements
- Android Studio Ladybug (or newer)
- Android SDK 36
- JDK 17

```bash
# Clone the repository
git clone https://github.com/ShounakPatra/Solus.git
cd Solus

# Compile the debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🔐 Privacy by Design

Solus is built from the ground up to respect user privacy:
- All LLM and vision inference takes place locally on your GPU/CPU.
- Prompt history is stored securely in SQLite database files inside local app storage.
- Internet permissions are used *exclusively* for model file downloads and external hyperlinks.
- Hugging Face API keys/tokens are only stored locally on your device (used for gated downloads).

Please report security issues privately via our [Security Policy](SECURITY.md).

---

## 💡 FAQ

<details>
<summary><b>Does Solus run entirely offline?</b></summary>
<p>Yes. Once a model is downloaded, you can disable Wi-Fi/cellular data entirely. Inference and chat history are processed locally without making network calls.</p>
</details>

<details>
<summary><b>Why is the initial APK download around 200MB?</b></summary>
<p>The APK bundles multiple heavy native runtimes (MediaPipe, LiteRT) and native C++ libraries for various CPU/GPU architectures to make on-device inference as fast as possible.</p>
</details>

<details>
<summary><b>Can I import my own custom GGUF or ONNX model files?</b></summary>
<p>Not directly. The current runtimes require models converted to a verified Android-compatible format (like <code>.task</code> or <code>.litertlm</code>) containing the correct tokenizer configurations.</p>
</details>

---

## 🗺️ Roadmap

- [x] **v1.1.0 Releases:** Reliable thinking controls, resumable downloads, device hardware checks, glassmorphic UI polish, and unit testing coverage.
- [ ] **v1.2.0 (Next):** Download integrity checksums, cleaner model validation feedback, accessibility improvements, and setup documentation.
- [ ] **v2.0.0 (Researching):** On-device speech recognition (whisper pipelines), local model conversions, custom benchmarks, and encrypted chat exports.

---

## 🤝 Contributing

We welcome bug reports, model conversion suggestions, and code updates.
- Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request.
- Follow our [Code of Conduct](CODE_OF_CONDUCT.md) inside comments and issues.

---

## 👤 Author

**Shounak Patra**
* GitHub: [@ShounakPatra](https://github.com/ShounakPatra)

---

## 📄 License

Solus is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

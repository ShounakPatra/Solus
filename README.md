<div align="center">

<img src="https://github.com/user-attachments/assets/6d0fec0c-5f12-4c6d-83b4-9478065aca5b" width="170" alt="Solus logo" />

# Solus

### Private AI, running entirely on your Android device.

Chat, reason, code, and explore documents with local language models. Once a model is downloaded, your prompts and generated responses stay on your phone.

<p>
  <a href="https://github.com/ShounakPatra/Solus/releases/download/v1.1.0/release.apk">
    <img src="https://img.shields.io/badge/Download-Solus_1.1.0_APK-20C997?style=for-the-badge&logo=android&logoColor=white" alt="Download Solus 1.1.0 APK" height="42" />
  </a>
</p>

<p>
  <img src="https://img.shields.io/github/stars/ShounakPatra/Solus?style=flat-square&logo=github&label=Stars" alt="GitHub stars" />
  <img src="https://img.shields.io/github/downloads/ShounakPatra/Solus/total?style=flat-square&label=Downloads&color=20C997" alt="Total downloads" />
  <img src="https://github.com/ShounakPatra/Solus/actions/workflows/android-ci.yml/badge.svg" alt="Android CI" />
  <img src="https://img.shields.io/badge/version-1.1.0-4C8DFF?style=flat-square" alt="Version 1.1.0" />
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 or newer" />
  <img src="https://img.shields.io/github/license/ShounakPatra/Solus?style=flat-square&color=A970FF" alt="Apache 2.0 license" />
  <img src="https://img.shields.io/badge/status-active-20C997?style=flat-square" alt="Active development" />
</p>

**No cloud inference. No subscription. No prompt history leaving your device.**

</div>

---

## App Preview

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/chat-response.jpeg" width="280" alt="Solus answering a question with a local Gemma model" />
    </td>
    <td align="center">
      <img src="docs/screenshots/model-manager-overview.jpeg" width="280" alt="Solus model manager with device guidance and Hugging Face access" />
    </td>
  </tr>
  <tr>
    <td align="center"><b>Private local chat</b></td>
    <td align="center"><b>Guided model management</b></td>
  </tr>
</table>

---

## Why Solus?

Most AI assistants send every message to a remote server. Solus brings the model to your phone instead. Choose a compatible model, download it once, and continue chatting without sending your conversations to an AI API.

|                       | Experience                                                                                      |
| --------------------- | ----------------------------------------------------------------------------------------------- |
| **Private by design** | Prompts, chat history, and generation remain on-device.                                         |
| **Works offline**     | After the selected model is downloaded, inference does not need an internet connection.         |
| **Choose your model** | Pick from compact chat, coding, reasoning, and vision-capable model options.                    |
| **Explore documents** | Ask questions about documents, spreadsheets, presentations, source code, and other local files. |
| **Built for Android** | Device-aware model guidance helps you choose an option that fits your phone.                    |

> Solus uses the internet to download model files. Some gated models also require accepting their licence and supplying a Hugging Face read token.

---

## Highlights

* **Local AI chat** powered directly by your Android device
* **Multiple model families**, including Qwen, Gemma, Llama, Phi, DeepSeek, TinyLlama, and FastVLM
* **Thinking-mode control** for compatible reasoning models
* **Image questions** with supported vision-language models
* **Document analysis** for PDF, DOCX, PPTX, XLSX, ODT, HTML, RTF, Markdown, source code, and more
* **Resumable model downloads** with progress, speed, storage, and recovery states
* **Conversation history**, response sharing, and generation-speed feedback
* **Clean output handling** that removes model control tokens and malformed thinking tags
* **Glass-inspired Jetpack Compose UI** with light and dark theme support

---

## Supported Model Families

<div align="center">

<p>
  <img src="https://img.shields.io/badge/Qwen-615CED?style=for-the-badge&logo=alibabacloud&logoColor=white" alt="Qwen models" />
  <img src="https://img.shields.io/badge/Gemma-4285F4?style=for-the-badge&logo=google&logoColor=white" alt="Gemma models" />
  <img src="https://img.shields.io/badge/Llama-6A5ACD?style=for-the-badge&logo=meta&logoColor=white" alt="Llama models" />
  <img src="https://img.shields.io/badge/Phi-5E5E5E?style=for-the-badge&logo=microsoft&logoColor=white" alt="Phi models" />
</p>

<p>
  <img src="https://img.shields.io/badge/DeepSeek-4D6BFE?style=for-the-badge&logo=deepseek&logoColor=white" alt="DeepSeek models" />
  <img src="https://img.shields.io/badge/TinyLlama-F59E0B?style=for-the-badge&logoColor=white" alt="TinyLlama models" />
  <img src="https://img.shields.io/badge/FastVLM-20C997?style=for-the-badge&logo=apple&logoColor=white" alt="FastVLM models" />
</p>

</div>

Model availability depends on whether a compatible Android-ready artifact exists. Some listed models may require conversion before they can run through the supported on-device runtimes.

---

## Solus vs Google AI Edge Gallery

Both projects provide open-source tools for running generative AI directly on mobile hardware. Solus is designed as a focused private Android assistant with document support, guided model selection, and reliable model management.

| Feature                                                         | Solus | Google AI Edge Gallery |
| --------------------------------------------------------------- | :---: | :--------------------: |
| Fully offline inference                                         |   ✅   |            ✅           |
| Open source                                                     |   ✅   |            ✅           |
| Free                                                            |   ✅   |            ✅           |
| Local conversation history                                      |   ✅   |            ✅           |
| Vision models                                                   |   ✅   |            ✅           |
| Document chat for PDF, DOCX, PPTX, XLSX, and other formats      |   ✅   |            ❌           |
| Multiple model families                                         |   ✅   |            ✅           |
| Thinking mode                                                   |   ✅   |            ✅           |
| Download manager with resume support                            |   ✅   |            ✅           |
| Device-aware model recommendations                              |   ✅   |            ❌           |
| Response cleanup for control tokens and malformed thinking tags |   ✅   |            ❌           |

### Why Choose Solus?

Choose **Solus** when you want:

* A private AI assistant rather than an experimentation-focused showcase
* Local document chat across PDFs, Word files, presentations, spreadsheets, code, and other formats
* Device-aware recommendations based on your phone and available resources
* Resumable model downloads with progress and recovery support
* Clean model output without exposed control tokens or malformed thinking tags
* Support for chat, coding, reasoning, and vision model families
* Compatibility with Android 8.0 and newer
* A focused Android interface for daily local AI use

> This comparison covers built-in user-facing features. Both projects are under active development, so capabilities may change in future releases.

> Solus is an independent open-source project and is not affiliated with, endorsed by, or sponsored by Google.

---

## From Download to Private Chat

|  Step | What happens                                                                                           |
| :---: | ------------------------------------------------------------------------------------------------------ |
| **1** | Install Solus from the GitHub Releases page.                                                           |
| **2** | Open **Models** and choose a model suited to your device.                                              |
| **3** | Review its size, requirements, runtime, context window, and compatibility guidance.                    |
| **4** | Download the model once. Public models need no account; gated models may require a Hugging Face token. |
| **5** | Start chatting. Inference and conversation history remain on your phone.                               |

<p align="center">
  <img src="docs/screenshots/chat-empty-state.jpeg" width="280" alt="Solus first-run chat screen before a model is selected" />
  <img src="docs/screenshots/model-download-progress.jpeg" width="280" alt="Solus model download with progress, speed, pause, and cancel controls" />
</p>

<p align="center">
  <sub>Start with clear setup guidance, then monitor and control large model downloads directly in the app.</sub>
</p>

---

## Built for Different Tasks

| Need                                         | Good starting point           |
| -------------------------------------------- | ----------------------------- |
| Everyday chat, rewriting, and summaries      | Qwen 2.5 Instruct or Gemma 3  |
| Kotlin, Python, shell, and code explanations | Qwen 2.5 Coder                |
| Math, planning, and structured reasoning     | DeepSeek R1 Distill or Qwen 3 |
| Image descriptions and visual questions      | Gemma 3n Vision or FastVLM    |
| Lightweight testing on limited storage       | Qwen 2.5 0.5B or TinyLlama    |

Model availability and performance depend on the Android-ready artifact, available storage, RAM, chipset, selected runtime, context length, and prompt.

Solus identifies models that require conversion or are not currently ready for direct on-device use.

---

## Install

1. Open the [Solus Releases](https://github.com/ShounakPatra/Solus/releases) page.
2. Download the newest APK attached to the latest release.
3. Allow **Install unknown apps** for your browser or file manager if Android asks.
4. Install and open Solus.
5. Open the model manager and select a model suitable for your device.
6. Download the model and begin a private local conversation.

The app requires **Android 8.0 (API 26) or newer**.

Model storage and memory requirements vary from roughly 500 MB for compact options to several gigabytes for larger models.

Releases are signed and published through GitHub. Verify that you are downloading from the official `ShounakPatra/Solus` repository.

---

## Privacy

Solus is designed around local inference:

* Chat prompts and generated answers are processed on-device.
* Saved conversation history remains in local app storage.
* Imported document content is processed locally.
* Selected images are analysed locally by compatible vision models.
* No paid AI API key is required for public model downloads.
* Internet access is used for model downloads and external model-page links.
* Hugging Face tokens are only needed for models whose publishers gate access behind a licence.
* Solus does not require a cloud inference subscription.

Removing the application or clearing its storage may remove locally saved conversations and app data. Downloaded model-file behaviour may depend on their storage location.

Please report security concerns privately using the repository's [security policy](SECURITY.md).

---

## Build from Source

### Requirements

* Android Studio with Android SDK 36
* JDK 17
* Android device or emulator running API 26 or newer

Clone the repository:

```bash
git clone https://github.com/ShounakPatra/Solus.git
cd Solus
```

Open the project in Android Studio and allow Gradle sync to complete.

Alternatively, build the debug APK from the Android Studio terminal or a PowerShell window:

```powershell
.\gradlew.bat assembleDebug
```

Run local unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

The generated debug APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Model files, APKs, keystores, build output, local credentials, and IDE-specific configuration are intentionally excluded from Git.

---

## Tech Stack

<p>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/MediaPipe-0097A7?style=for-the-badge&logo=google&logoColor=white" alt="MediaPipe" />
  <img src="https://img.shields.io/badge/LiteRT_LM-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white" alt="LiteRT LM" />
</p>

* **Kotlin and Jetpack Compose** for the native Android experience
* **MediaPipe GenAI** for Android-ready local language-model and vision inference
* **LiteRT-LM** for compatible on-device model packages
* **PDFBox Android** and structured document readers for local file analysis
* **Material 3 and Haze** for the responsive glass-inspired interface

---

## Contributing

Bug reports, model-compatibility findings, documentation improvements, and focused pull requests are welcome.

* Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
* Use [GitHub Issues](https://github.com/ShounakPatra/Solus/issues) for bugs and feature ideas.
* Check [CHANGELOG.md](CHANGELOG.md) for release history.
* Follow the [Code of Conduct](CODE_OF_CONDUCT.md) when participating.
* Never include private tokens, signing keys, downloaded model files, or personal documents in a contribution.

---

## Author

**Shounak Patra**
GitHub: [@ShounakPatra](https://github.com/ShounakPatra)

---

## License

Solus is open source under the [Apache License 2.0](LICENSE).

Third-party models remain subject to the licences and acceptable-use requirements established by their respective publishers.

---

<div align="center">

### Your phone. Your models. Your conversations.

If Solus is useful to you, consider giving the repository a star. It helps more people discover private, on-device AI.

</div>

<div align="center">

<img src="<img width="400" height="400" alt="Solus-logo-final 1" src="https://github.com/user-attachments/assets/6d0fec0c-5f12-4c6d-83b4-9478065aca5b" />
" width="170" alt="Solus logo" />

# Solus

### Private AI, running entirely on your Android device.

Chat, reason, code, and explore documents with local language models. Once a model is downloaded, your prompts and generated responses stay on your phone.

<p>
  <a href="https://github.com/ShounakPatra/Solus/releases">
    <img src="https://img.shields.io/badge/Download-Latest_APK-20C997?style=for-the-badge&logo=android&logoColor=white" alt="Download the latest Solus APK" height="42" />
  </a>
</p>

<p>
  <img src="https://img.shields.io/github/stars/ShounakPatra/Solus?style=flat-square&logo=github&label=Stars" alt="GitHub stars" />
  <img src="https://img.shields.io/github/downloads/ShounakPatra/Solus/total?style=flat-square&label=Downloads&color=20C997" alt="Total downloads" />
  <img src="https://img.shields.io/badge/version-1.0.0-4C8DFF?style=flat-square" alt="Version 1.0.0" />
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 or newer" />
  <img src="https://img.shields.io/github/license/ShounakPatra/Solus?style=flat-square&color=A970FF" alt="Apache 2.0 license" />
  <img src="https://img.shields.io/badge/status-active-20C997?style=flat-square" alt="Active development" />
</p>

**No cloud inference. No subscription. No prompt history leaving your device.**

</div>

---

## Why Solus?

Most AI assistants send every message to a remote server. Solus brings the model to your phone instead. Choose a compatible model, download it once, and continue chatting without sending your conversations to an AI API.

| | Experience |
|---|---|
| **Private by design** | Prompts, chat history, and generation remain on-device. |
| **Works offline** | After the selected model is downloaded, inference does not need an internet connection. |
| **Choose your model** | Pick from compact chat, coding, reasoning, and vision-capable model options. |
| **Built for Android** | Device-aware model guidance helps you choose an option that fits your phone. |

> Solus uses the internet to download model files. Some gated models also require accepting their license and supplying a Hugging Face read token.

---

## Highlights

- **Local AI chat** powered directly by your Android device
- **Multiple model families**, including Qwen, Gemma, Llama, Phi, DeepSeek, TinyLlama, and FastVLM
- **Thinking mode control** for compatible reasoning models
- **Image questions** with supported vision-language models
- **Document analysis** for PDF, DOCX, PPTX, XLSX, ODT, HTML, RTF, Markdown, source code, and more
- **Resumable model downloads** with progress, speed, storage, and recovery states
- **Conversation history**, response sharing, and generation-speed feedback
- **Clean output handling** that removes model control tokens and malformed thinking tags
- **Glass-inspired Jetpack Compose UI** with light and dark theme support

---

## From Download to Private Chat

| Step | What happens |
|:---:|---|
| **1** | Install Solus from the GitHub Releases page. |
| **2** | Open **Models** and choose a model suited to your device. |
| **3** | Download the model once. Public models need no account; gated models may need a Hugging Face token. |
| **4** | Start chatting. Inference and conversation history stay on your phone. |

---

## Built for Different Tasks

| Need | Good starting point |
|---|---|
| Everyday chat, rewriting, and summaries | Qwen 2.5 Instruct or Gemma 3 |
| Kotlin, Python, shell, and code explanations | Qwen 2.5 Coder |
| Math, planning, and structured reasoning | DeepSeek R1 Distill or Qwen 3 |
| Image description and visual questions | Gemma 3n Vision or FastVLM |
| Lightweight testing on limited storage | Qwen 2.5 0.5B or TinyLlama |

Model availability and performance depend on the Android-ready artifact, available storage, RAM, and chipset. Solus labels models that require conversion or are not ready for on-device use.

---

## Install

1. Open the [Solus Releases](https://github.com/ShounakPatra/Solus/releases) page.
2. Download the newest APK attached to the latest release.
3. Allow **Install unknown apps** for your browser or file manager if Android asks.
4. Install and open Solus.
5. Download a model from the in-app model manager.

The app requires **Android 8.0 (API 26) or newer**. Model storage and memory requirements vary from roughly 500 MB for compact options to several gigabytes for larger models.

> The first APK release has not been published yet. The download button above will become the permanent download destination as soon as an APK is attached to a GitHub Release.

---

## Privacy

Solus is designed around local inference:

- Chat prompts and generated answers are processed on-device.
- Saved conversation history remains in local app storage.
- No paid AI API key is required for public model downloads.
- Internet access is used for model downloads and model-page links.
- Hugging Face tokens are only needed for models whose publishers gate access behind a license.

Please report security concerns privately using the repository's [security policy](SECURITY.md).

---

## Build from Source

### Requirements

- Android Studio with Android SDK 36
- JDK 17
- Android device or emulator running API 26+

```bash
git clone https://github.com/ShounakPatra/Solus.git
cd Solus
```

Open the project in Android Studio and let Gradle sync complete, or build from the Android Studio terminal:

```powershell
.\gradlew.bat assembleDebug
```

Run local unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Model files, APKs, keystores, build output, and local IDE configuration are intentionally excluded from Git.

---

## Tech Stack

<p>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/MediaPipe-0097A7?style=for-the-badge&logo=google&logoColor=white" alt="MediaPipe" />
  <img src="https://img.shields.io/badge/LiteRT_LM-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white" alt="LiteRT LM" />
</p>

- **Kotlin + Jetpack Compose** for the Android experience
- **MediaPipe GenAI** for Android-ready local LLM and vision inference
- **LiteRT-LM** for compatible local model packages
- **PDFBox Android** and structured document readers for file analysis
- **Material 3 + Haze** for the responsive glass interface

---

## Contributing

Bug reports, model compatibility findings, and focused pull requests are welcome.

- Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
- Use [GitHub Issues](https://github.com/ShounakPatra/Solus/issues) for bugs and feature ideas.
- Check [CHANGELOG.md](CHANGELOG.md) for release history.
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md) when participating.

---

## Author

**Shounak Patra**<br>
GitHub: [@ShounakPatra](https://github.com/ShounakPatra)

---

## License

Solus is open source under the [Apache License 2.0](LICENSE).

---

<div align="center">

### Your phone. Your models. Your conversations.

If Solus is useful to you, consider giving the repository a star. It helps more people discover private, on-device AI.

</div>

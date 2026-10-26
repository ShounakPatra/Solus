# 🤝 Contributing to Solus

First off, thank you for considering contributing to **Solus**! 🎉

Solus is an open-source, on-device AI assistant for Android built with Kotlin, Jetpack Compose, C++17, Rust, and local inference runtimes. Inference and document retrieval run locally; downloads, updates and selected tools can use the network. Contributions from developers, designers, writers, and AI enthusiasts help improve the Android experience.

Please take a moment to review this document before submitting your contribution.

---

## 📜 Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md). Please read it to ensure a welcoming, respectful, and collaborative environment for everyone.

---

## 🚀 How Can I Contribute?

There are many ways you can contribute to Solus:

- 🐛 **Report Bugs:** File detailed bug reports when something breaks.
- 💡 **Suggest Features:** Propose new UI enhancements, model runtimes, or UX improvements.
- 💻 **Submit Code:** Fix open issues, optimize C++ NDK inference runtimes, or enhance Jetpack Compose components.
- 📖 **Improve Documentation:** Refine setup guides, architecture diagrams, or inline code docs.
- 🧪 **Test & Benchmark:** Run performance tests on different Android SoCs/RAM configurations.

---

## 🛠️ Development Setup & Building from Source

### Prerequisites

Ensure you have the following installed on your development machine:

- **Android Studio:** A version compatible with the configured Android Gradle Plugin (`9.4.1`).
- **Gradle JVM:** Use Android Studio's bundled JBR. Kotlin and Java bytecode target Java 17; that target is separate from the JVM running Gradle.
- **Android SDK:** API Level 37 (`compileSdk = 37`, `targetSdk = 37`, `minSdk = 30` / Android 11).
- **Android NDK:** `28.2.13676358`, as pinned in `app/build.gradle.kts`.
- **CMake:** `3.22.1`.
- **Gradle:** Use the checked-in `9.8.2` wrapper; do not substitute a global Gradle installation.
- **Git:** Latest version.
- **Rust:** Install the `aarch64-linux-android` target for the Candle runtime. See [portable models](docs/portable-models.md) and the platform build helpers in `tools/`.

Adjust Gradle and native build parallelism to suit your machine. Do not delete build outputs or native toolchains while another build is active.

### Setup Instructions

1. **Fork the Repository:** Click the **Fork** button at the top right of [ShounakPatra/Solus](https://github.com/ShounakPatra/Solus).
2. **Clone your Fork:**
   ```bash
   git clone https://github.com/YOUR-USERNAME/Solus.git
   cd Solus
   ```
3. **Open in Android Studio:**
   - Launch Android Studio and select **Open**.
   - Navigate to the cloned `Solus` directory and let Gradle sync.

Keep `local.properties`, `keystore.properties`, signing keys, header dumps, and diagnostic logs out of commits. Signing a release requires your own signing configuration.

4. **Build Debug APK via CLI:**
   ```bash
   ./gradlew assembleDebug
   ```

5. **Run Unit Tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```

---

## 🌿 Git Branching Strategy & Workflow

To keep our commit history clean and manageable:

1. **Branch off `main`:** Create a descriptive branch name for your feature or bug fix:
   ```bash
   git checkout -b feat/add-vulkan-backend
   # or
   git checkout -b fix/haze-blur-performance
   ```
   *Naming Conventions:* `feat/`, `fix/`, `docs/`, `refactor/`, `perf/`, `test/`.

2. **Make Small, Atomic Commits:** Keep changes focused on a single logical task.

3. **Follow Conventional Commits:**
   - `feat: add support for GGUF K-Quants in native engine`
   - `fix: resolve token counter overflow on long context windows`
   - `docs: update setup steps in README`
   - `perf: optimize Jetpack Compose recomposition in ChatScreen`

---

## 🎨 Coding & Architectural Guidelines

### Kotlin & Jetpack Compose
- **Material 3 Design System:** All UI components must use Material 3 design tokens (`MaterialTheme.colorScheme`) and follow our Liquid Glassmorphism design system.
- **Unidirectional Data Flow (UDF):** UI components consume state via `StateFlow` from ViewModels and trigger actions via callbacks (`onAction`).
- **Performance:** Avoid unnecessary recompositions by using `remember`, `key()`, and `derivedStateOf`. Use proper `Modifier` order.

### Native C++ & JNI
- **C++ Standard:** Follow C++17 standards.
- **Memory Safety:** Use RAII pattern and smart pointers (`std::unique_ptr`, `std::shared_ptr`). Avoid raw pointers and manual `malloc`/`free`.
- **JNI Best Practices:** Always release local JNI references (`DeleteLocalRef`) and handle native exceptions safely to prevent crashes on the main UI looper.

---

## 📋 Pull Request (PR) Submission Checklist

Before submitting your Pull Request, ensure:

- [ ] Relevant builds and tests pass; include the commands and their results.
- [ ] Changed native/runtime behavior has focused regressions and explicitly recorded device checks where required. A host test or build is not phone verification.
- [ ] Code follows project formatting and style guidelines.
- [ ] No sensitive keys, keystores, or temporary build artifacts are included.
- [ ] PR title is descriptive and follows Conventional Commits format.
- [ ] Linked issue(s) are referenced in the PR description (e.g., `Fixes #42`).

---

## 🐛 Reporting Issues

When filing an issue on [Solus Issues](https://github.com/ShounakPatra/Solus/issues):

- **Use the Search Function:** Check if the issue has already been reported.
- **Provide Environment Details:**
  - Installed app version and version code (current version: `6.0.5`, code `11`)
  - Device Model & SoC (e.g., MediaTek Dimensity 7050, Snapdragon 8 Gen 2)
  - Installed RAM (e.g., 8 GB, 12 GB)
  - Android OS Version (Android 13, 14, 15, 16, 17)
- **Include Logs & Steps to Reproduce:** Include exact steps to trigger the bug and `adb logcat` logs if relevant.

---

Thank you for building the future of private, on-device AI with us! 🚀

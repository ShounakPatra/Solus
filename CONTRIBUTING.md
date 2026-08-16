# 🤝 Contributing to Solus

First off, thank you for considering contributing to **Solus**! 🎉

Solus is an open-source, 100% offline, on-device AI assistant for Android built with Kotlin, Jetpack Compose, C++20, and native ML runtimes (Google LiteRT, MediaPipe GenAI, and `llama.cpp`). Contributions from developers, designers, writers, and AI enthusiasts help make local on-device AI accessible, private, and fast for everyone.

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

- **Android Studio:** Ladybug (2024.2.1+) or newer recommended.
- **JDK:** Java Development Kit 17 (Java 17).
- **Android SDK:** API Level 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`).
- **Android NDK:** NDK version `26.x` or newer (for C++ JNI CMake builds).
- **CMake:** Version 3.22.1+.
- **Git:** Latest version.

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
- **C++ Standard:** Follow C++20 standards.
- **Memory Safety:** Use RAII pattern and smart pointers (`std::unique_ptr`, `std::shared_ptr`). Avoid raw pointers and manual `malloc`/`free`.
- **JNI Best Practices:** Always release local JNI references (`DeleteLocalRef`) and handle native exceptions safely to prevent crashes on the main UI looper.

---

## 📋 Pull Request (PR) Submission Checklist

Before submitting your Pull Request, ensure:

- [ ] Code builds cleanly via `./gradlew assembleDebug` without errors.
- [ ] Unit tests pass cleanly via `./gradlew testDebugUnitTest`.
- [ ] Code follows project formatting and style guidelines.
- [ ] No sensitive keys, keystores, or temporary build artifacts are included.
- [ ] PR title is descriptive and follows Conventional Commits format.
- [ ] Linked issue(s) are referenced in the PR description (e.g., `Fixes #42`).

---

## 🐛 Reporting Issues

When filing an issue on [Solus Issues](https://github.com/ShounakPatra/Solus/issues):

- **Use the Search Function:** Check if the issue has already been reported.
- **Provide Environment Details:**
  - App Version (`v1.5.0`, etc.)
  - Device Model & SoC (e.g., MediaTek Dimensity 7050, Snapdragon 8 Gen 2)
  - Installed RAM (e.g., 8 GB, 12 GB)
  - Android OS Version (Android 13, 14, 15, 16, 17)
- **Include Logs & Steps to Reproduce:** Include exact steps to trigger the bug and `adb logcat` logs if relevant.

---

Thank you for building the future of private, on-device AI with us! 🚀

# Solus Release APK Size Optimization Report

> Historical optimization measurements below describe the pre-v6 runtime bundle.
> For v6.0.0, the verified signed APK is 39,849,556 bytes (39.85 MB / 38.00 MiB),
> and CI enforces a 42,000,000-byte ceiling. LiteRT-LM 0.18.0 ships
> `liblitertlm_jni.so` without the older separate `libLiteRt.so` and
> `libLiteRtClGlAccelerator.so`. The current checker requires that JNI library,
> `libllm_inference_engine_jni.so`, `libsolus_llama.so` and
> `libandroidx.graphics.path.so`, while retaining ABI, emoji-font and DEX-compression
> checks. The historical sizes, six-library list and 36 MB budget below are not
> current v6 release requirements.

## 1. Executive Summary

This document details the optimizations performed to reduce the **Solus release APK** footprint from **48,187,140 bytes (~48.19 MB / 45.95 MiB)** down to **33,070,919 bytes (33.07 MB / 31.54 MiB)**, achieving an absolute reduction of **15,116,221 bytes (-15.12 MB / -14.42 MiB, -31.37%)**. This exceeds the target budget of 36,000,000 bytes by nearly 3 MB while preserving 100% of Solus runtime features, including GGUF (`llama.cpp`), LiteRT-LM, MediaPipe, CPU/Vulkan acceleration, vision, audio, PDF parsing, RAG, and offline operation.

---

## 2. Quantitative Size Progression

| Milestone | APK Size (Bytes) | Decimal Size | Binary Size | Reduction from Baseline |
|---|---:|---:|---:|---:|
| **Baseline Release APK** | 48,187,140 | 48.19 MB | 45.95 MiB | 0 B (0.00%) |
| **Step B: Remove Bundled Emoji Font** | 38,322,199 | 38.32 MB | 36.55 MiB | -9,864,941 B (-20.47%) |
| **Step C: DEX Compression (`useLegacyPackaging`)** | 34,313,288 | 34.31 MB | 32.72 MiB | -13,873,852 B (-28.79%) |
| **Step D: Native Exports & ThinLTO** | 33,077,220 | 33.08 MB | 31.54 MiB | -15,109,920 B (-31.36%) |
| **Step E & F: R8 Tightening & Font Cleanup** | **33,070,919** | **33.07 MB** | **31.54 MiB** | **-15,116,221 B (-31.37%)** |

### Breakdown by Category

| Category | Baseline Size | Optimized Size | Change (Bytes) | Change (%) |
|---|---:|---:|---:|---:|
| **Emoji Fonts** | 9,864,941 B | 0 B | -9,864,941 B | -100.00% |
| **DEX (`classes.dex`)** | 6,906,056 B | 2,897,145 B | -4,008,911 B | -58.05% |
| **Native Libraries (`.so`)** | 23,665,907 B | 22,430,644 B | -1,235,263 B | -5.22% |
| **Bouncy Castle Resources** | 4,150,840 B | 4,150,840 B | 0 B | 0.00% |
| **PDFBox Assets** | 1,618,605 B | 1,618,605 B | 0 B | 0.00% |
| **Remaining Assets / Metadata** | 1,846,221 B | 1,839,920 B | -6,301 B | -0.34% |
| **Total APK** | **48,187,140 B** | **33,070,919 B** | **-15,116,221 B** | **-31.37%** |

---

## 3. Optimization Techniques Implemented

### Step A — Repeatable Size Measurement Tool
- Implemented `tools/apk_size_report.py` using standard Python 3 libraries (`argparse`, `zipfile`, `json`, `hashlib`, `pathlib`).
- Generates JSON reports with categorization for native libraries, DEX, emoji, PDFBox assets, Bouncy Castle resources, and remaining entries.
- Provides `--verify-optimized` validation checking:
  1. No ABI other than `arm64-v8a` packaged.
  2. Zero bundled emoji font assets.
  3. All DEX entries compressed (`method != ZIP_STORED`).
  4. All 6 required native libraries present (`libsolus_llama.so`, `libLiteRt.so`, `libLiteRtClGlAccelerator.so`, `liblitertlm_jni.so`, `libllm_inference_engine_jni.so`, `libandroidx.graphics.path.so`).
  5. Maximum APK size threshold enforcement (`--max-apk-bytes 36000000`).

### Step B — Elimination of Bundled Emoji Font
- Removed direct dependencies `androidx.emoji2:emoji2-bundled` and `androidx.emoji2:emoji2`.
- Migrated UI rendering to system emoji fonts.
- Disabled `EmojiCompatInitializer` via manifest merge (`<provider android:name="androidx.startup.InitializationProvider" ... tools:node="merge"><meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer" tools:node="remove" /></provider>`).
- Removed startup initialization code from `MainActivity.kt`.
- **Savings:** -9.86 MB (-100% of emoji category).

### Step C — Compressed Release DEX
- Configured Android Gradle Plugin via `androidComponents`:
  ```kotlin
  androidComponents {
      onVariants(selector().withBuildType("release")) { variant ->
          variant.packaging.dex.useLegacyPackaging.set(true)
      }
  }
  ```
- Resulted in `classes.dex` stored with DEFLATE compression instead of STORED.
- **Savings:** -4.01 MB (-58.05% of DEX footprint).

### Step D — Restricted Native Exports, ThinLTO, and Dead-Stripping
- Created GNU linker version script `app/src/main/cpp/solus-llama.map`:
  - Scoped global symbols strictly to `Java_com_shounak_localmeshai_ai_LlamaCppEngine_*`.
  - Hidden (`local: *;`) all internal `ggml`, `llama`, `vulkan`, and runtime helper symbols.
  - Reduced defined global dynamic symbols from **2,951 down to exactly 21 JNI entry points** (0 leaked internal symbols).
- Enabled Link-Time Optimization (`ThinLTO` / `CMAKE_INTERPROCEDURAL_OPTIMIZATION ON` and `GGML_LTO ON`) and linker garbage collection (`--gc-sections`, `-ffunction-sections -fdata-sections`).
- Created `app/src/main/cpp/cmake/CheckIPOSupported.cmake` to pass NDK r28 toolchain flags and `-fuse-ld=lld` into CMake's IPO validation check.
- **Savings:** `libsolus_llama.so` reduced from 3.49 MB to 2.26 MB compressed (-1.24 MB savings).

### Step E — Tightened ProGuard / R8 Rules
- Removed blanket `-keep class com.shounak.localmeshai.models.** { *; }` and broad `memory.**` keep rules.
- Retained exact serialization keep rules for model config, chat entities, and DB models.
- Removed legacy `jlatexmath` keep rules.
- Removed package-wide `com.tom_roush.pdfbox.**` rules, allowing AAR consumer rules to drive precise retention.
- Added explicit keep for `LlamaTokenCallback.onFirstToken()`.

### Step F — Resource and Font Pruning
- Deleted 8 unused `eb_garamond_*.ttf` font assets from `app/src/main/res/font/`.
- Preserved active application typography (`FontFamily.SansSerif`) and math fonts (`latinmodern-math.otf`).

---

## 4. Final APK Verification Details

- **Artifact Path:** `app/build/outputs/apk/release/app-release.apk`
- **Total Bytes:** `33,070,919` bytes (33.07 MB / 31.54 MiB)
- **SHA-256:** `cb4905c388d86e732131d4e09ac213e00cc35edf1c71e05f69f85218b36af051`
- **Build Command:**
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain --no-configuration-cache
  ```

### Completed Verification Checks

| Verification Test | Command / Tool | Result |
|---|---|---|
| **Structural & Size Budget Check** | `python tools/apk_size_report.py --verify-optimized --max-apk-bytes 36000000` | **PASSED** (33.07 MB $\le$ 36.00 MB budget) |
| **16 KB Page Alignment** | `zipalign -c -P 16 -v 4 app-release.apk` | **PASSED** (Android 15+ 16 KB compliant) |
| **ELF Segment Alignment** | `llvm-readelf -l` on all 6 `.so` files | **PASSED** (`p_align = 0x4000` / 16 KB) |
| **Signature Verification** | `apksigner verify --verbose app-release.apk` | **PASSED** (APK Signature Scheme v2 valid) |
| **Native Symbol Restriction** | `llvm-readelf --dyn-symbols libsolus_llama.so` | **PASSED** (21 JNI symbols, 0 internal symbols) |
| **Unit Tests** | `.\gradlew.bat :app:testDebugUnitTest` | **PASSED** (100% tests green) |
| **Android Lint** | `.\gradlew.bat :app:lintDebug` | **PASSED** (0 fatal errors) |
| **Release Instrumentation Compile** | `.\gradlew.bat :app:assembleReleaseAndroidTest -PsolusReleaseSmoke=true` | **PASSED** (Smoke tests build with R8) |

### Unverified Checks (Pending Hardware Connection)
- **On-Device Instrument Test Execution:** Running `ReleaseOptimizationSmokeTest` on a live physical device (no Android device was attached during build verification).
- **Infinix Note 50s Vulkan FPS / Latency:** Physical Mali GPU verification for Vulkan prompt processing and generation speed.

---

## 5. Architectural Trade-offs & Decisions

### Download Size vs. Installed Storage
Compressing `classes.dex` (`variant.packaging.dex.useLegacyPackaging.set(true)`) reduces network download size from 6.91 MB to 2.90 MB (-4.01 MB). When installed on Android 9.0+, ART (Android Runtime) extracts or compiles the DEX files into `.vdex`/`.odex`/`.oat` files in the app's private code cache directory. The compressed DEX entry inside the APK introduces zero runtime performance regression during app execution, making this an ideal download-size optimization.

### Bouncy Castle Resource Preservation
Inspected R8 mapping and usage files showed active references to Bouncy Castle cryptographic curve definitions and post-quantum property files (`lowmc.properties`, `sike/*.properties`). Stripping these 4.15 MB of resources would risk runtime `NoSuchAlgorithmException` or crypto failures during encrypted PDF parsing. Therefore, per safety constraints, Bouncy Castle resources were completely preserved.

### CMake LTO & NDK 28 Toolchain
CMake 3.22's default `CheckIPOSupported` module attempts to invoke `try_compile` with `-fuse-ld=gold`, which Clang 19 in NDK r28 rejects. Creating `app/src/main/cpp/cmake/CheckIPOSupported.cmake` to pass NDK toolchain arguments and `-fuse-ld=lld` resolved the compile-time check cleanly, allowing ThinLTO to be safely applied.

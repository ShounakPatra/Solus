# Solus 6.0.6 dependency review

Scope: versionName 6.0.6, versionCode 12, package com.shounak.localmeshai.
The official candidate uses F-Droid's signing key. This report records evidence;
F-Droid maintainers decide inclusion and any required AntiFeatures.

## Android release runtime

The Gradle releaseRuntimeClasspath resolves 133 artifacts locally. The inventory
contains exact resolved coordinates, artifact hashes, POM license declarations,
project/source URLs where supplied, and AAR native-library names. Re-run it in the
cloud on the submitted source; reports are attached to the workflow artifact.

- Repositories are limited to Google Maven and Maven Central for app dependencies.
  The Gradle Plugin Portal is used for build plugins.
- Declared runtime licenses are Apache-2.0, MIT, BSD-3-Clause and GPL-2.0 with the
  Classpath exception. These declarations require retaining their relevant notices;
  they do not independently prove the binary corresponds to the stated source.
- Firebase, Google Play Services and Android Data Transport groups are excluded
  from the resolved shipping runtime. The audit fails if they reappear.
- Three Bouncy Castle 1.72 artifacts have empty license metadata in their POMs.
  Their exact upstream release's MIT notice is preserved at
  app/src/main/assets/licenses/bouncycastle-1.72.html. The audit verifies its hash
  and records the upstream r1rv72 source URL rather than ignoring the finding.

Native code supplied by Maven artifacts:

| Artifact | Native libraries | Declared license / source |
| --- | --- | --- |
| androidx.graphics:graphics-path:1.0.1 | libandroidx.graphics.path.so | Apache-2.0, AndroidX |
| com.google.ai.edge.litert:litert:1.0.1 | libtensorflowlite_jni.so | Apache-2.0, TensorFlow/LiteRT |
| com.google.ai.edge.litertlm:litertlm-android:0.18.0 | liblitertlm_jni.so | Apache-2.0, google-ai-edge/LiteRT-LM |
| com.google.mediapipe:tasks-genai:0.10.35 | libllm_inference_engine_jni.so | Apache-2.0, google-ai-edge/mediapipe |
| com.microsoft.onnxruntime:onnxruntime-android:1.22.0 | libonnxruntime.so, libonnxruntime4j_jni.so | MIT, microsoft/onnxruntime |

No Qualcomm dispatch or other vendor-specific runtime is present in these AARs.
Other ABIs in those dependencies are excluded by the ARM64-only app packaging.
Maintainers should confirm acceptability/source availability of these native Maven
artifacts; a clean scanner alone does not certify their contents.

## Native source and Rust

- llama.cpp is vendored at the declared upstream revision
  5cdadfc326163ed1fa66cf609fb1761f7928900c. Its MIT notice and vendor notices are
  retained in the source tree. Examples, tests, server and bundled web UI are off.
- Vulkan-Headers and SPIRV-Headers retain their source license files. The app's
  native bridge is compiled using the Android NDK; host shader tools come from apt.
- Candle 0.9.1, tokenizers 0.21.1 and all Rust transitives are pinned by Cargo.lock.
  The cloud audit records resolved Rust package SPDX declarations after building
  with Rust 1.90.0 and flags missing license metadata for manual investigation.
- The only tracked JARs are the root and vendored example Gradle wrappers; no
  precompiled app JNI libraries, model weights, AARs or executable model plugins
  are checked into the repository.

## Models, assets and network behavior

- No model weights ship in the APK. Downloads are initiated by the user; compatible
  local imports work without a Hugging Face account or network service.
- The optional catalog includes both permissive models and Gemma/Llama models
  with additional license restrictions. Do not treat those weights as Apache-2.0
  merely because Solus's app code has that license. The submission discloses this
  to maintainers for assessment of required labels or catalog changes.
- Optional BGE document embeddings have separate source/license notices and a
  built-in lexical fallback. EB Garamond's OFL notice is in app assets.
- F-Droid builds disable all GitHub APK-update network checks and installation.
  Their manifest does not request REQUEST_INSTALL_PACKAGES. This is checked in CI.

## Validation and remaining external checks

The prior source build passed F-Droid source and APK scanning on commit 64a264b.
The updated recipe must pass a fresh cloud rehearsal, license audit and focused
F-Droid distribution tests. Its pinned release source must then be tested on the
official Debian buildserver and reviewed by maintainers. Physical-phone testing
of the final F-Droid-signed APK is still required.

# Solus latency and GGUF correctness handoff

## Behavior and scope

An accepted request creates the user message, progress, and Stop action before model loading or attachment processing. A selected but idle-unloaded model loads automatically for that request. Idle unloading remains enabled and respects the configured timeout. Actual first-token latency still includes model loading, prompt evaluation, and supported media encoding; immediate acknowledgement is a separate measurement.

The implementation retains GPU-first Auto, sequential CPU/Vulkan numerical validation, model-specific templates, Think controls, media capability checks, and the existing MediaPipe native-close workaround. Baseline thread and batch profiles (4 decode threads, 4 prefill threads, 4096 context, 512 batch, 256 microbatch) remain the production default pending physical device benchmarking on the target Infinix Note 50s.

## Implemented architecture & changes (Sections A – E)

### A. Lifecycle ownership & cancellation
- **Single cancellation token per operation**: Every actual GGUF load operation receives a unique nonzero native operation control ID. Shared in-flight operations reuse the existing token rather than creating a duplicate or cancelling ongoing work.
- **Strict cleanup ordering in manager**: `ChatInferenceManager` completes native `close()` of the previous engine before registering a new operation control ID. Cancellation arriving during the wait remains latched to prevent beginning a new load.
- **Bound cancellation callbacks**: Watchdog and UI cancellation callbacks are strictly keyed to their specific operation ID, preventing stale watchdogs from cancelling newer operations.
- **Safe token release**: Cancellation tokens are released exactly once after native loading/probing has returned and unsuccessful engines have closed. On successful validation, the validated GPU engine transfers directly to the manager.
- **Completion-aware controller transitions**: Replaced `job.isActive` checks with awaiting job completion and native cleanup. Replacement loads cannot start until the previous operation's cleanup finishes. Mutex is not held while awaiting job completion.
- **Monotonic clock & watchdog**: Injected monotonic clock (`System.nanoTime() / 1_000_000L`). Watchdog emits warning after 30s without progress, cancels and transitions to `Stopping` after 120s stall, enforces 10-minute maximum timeout, and enters `ShutdownStalled` if native shutdown exceeds 30s.
- **Typed failure reasons**: `ModelLoadState.kt` defines `ModelLoadFailureReason` (Cancelled, StalledInitialization, TotalTimeout, CpuReferenceFailed, GpuDisagreement, InitializationFailed, StalledShutdown).

### B. Progress reporting & Compose responsiveness
- **Throttled UI progress updates**: Progress updates are throttled to at most once per 100 ms, avoiding recomposition floods in Compose, while stage changes and terminal states publish immediately.
- **Native progress snapshotting**: Added `Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetOperationControlSnapshot` returning JSON snapshots with stage, completed units, total units, and description.
- **Background snapshot polling**: Controller polls the native snapshot every 100 ms while the operation is active and updates stage and unit counts.
- **Hashing progress throttling**: `ModelDownloader.computeSha256` retains per-64 KiB cancellation checks while throttling progress emissions to <=100 ms.
- **Granular load stages**: Distinguished `WaitingForRuntime`, `VerifyingFile`, `CpuLoading`, `CpuReference`, `GpuLoading`, `GpuProbe`, `ContextPreparation`, and `General`, supporting indeterminate progress when units are unknown.

### C. Configuration identity & prefill tuning
- **Immutable execution profile**: Created `GgufExecutionProfile.kt` (`Baseline`, `FewerPrefillThreads`, `SmallerBatches`, `Combined`) with unified profile resolution (`DeviceUtils.recommendedThreadCount`, `recommendedContextWindow`, etc.).
- **Separate prefill threads**: Propagated `prefillThreads` through `LlamaCppEngine`, JNI bridge, and `llama_context_params.n_threads_batch`, separating prompt evaluation threading from decode token threading (`n_threads`).
- **Protocol v4 validation receipts**: `GgufGpuValidator` receipts store `requestedPrefillThreads` and `actualPrefillThreads` alongside decode threads, invalidating older schemas.
- **Preserved production baseline**: 4 decode threads, 4 prefill threads, 4096 context, 512 batch, 256 microbatch retained as default.

### D. System tracing, realistic diagnostics & responsiveness testing
- **Native & JVM tracing**: Linked `android` library in CMake. Integrated `ATrace` via `ScopedTrace` and `TRACE_BEGIN`/`TRACE_END` in `llama-jni.cpp` for `"cpu_gpu_loading"`, `"tokenization"`, `"cached_prefix_reuse"`, `"prompt_decoding"`, `"sampling"`, `"probe_reference"`, and `"probe_compare"`. Added `AppTrace.kt` with JVM-safe fallbacks for Kotlin code.
- **Pre-close diagnostic capture**: `GgufDiagnosticRunner` captures actual configuration, backend used, active backend, numerical anomaly reports, and response quality before calling `engine.close()`, preventing misreporting of Vulkan as CPU.
- **True token metrics**: Throughput is calculated using native decode duration and token counts (`decodeDurationMs`); prompt tokens are not faked with word counts. Monotonic timestamps used throughout.
- **Diagnostic policies**: Explicit `DiagnosticPolicy` (`AUTO`, `FORCED_CPU`, `FORCED_VULKAN`).
- **Responsiveness instrumentation**: Added `ChatRuntimeResponsivenessTest` in `app/src/androidTest/java/com/shounak/localmeshai/ui/ChatRuntimeResponsivenessTest.kt` testing message typing, scrolling, and Stop action under delayed runtime loading.

### E. Numerical stability atomics
- **Atomic anomaly detection in quants**: Converted `g_solus_numerical_anomaly` in `ggml-quants.c` to standard C11/C++ atomics (`std::atomic<int>` in C++, `atomic_int` with `<stdatomic.h>` in C11) using `memory_order_release` and `memory_order_acquire`.
- **Function prototypes**: Declared `solus_record_numerical_anomaly`, `solus_has_numerical_anomaly`, and `solus_clear_numerical_anomaly` in `ggml-quants.h`.

## Fresh verification

Verification executed with Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease --no-configuration-cache
```

### Verification Outcomes:
- **Unit Tests**: **349 unit tests pass** (0 failures, 0 skipped).
  - `ModelLoadControllerTest`: In-flight load reuse, cooperative cancellation transitions, watchdog timeout, reset behavior.
  - `LlamaCppEngineTest`: Updated native bridge signatures (`nThreadsBatch`), configuration propagation, cancellation handling.
  - `GgufGpuValidatorTest`: Receipt v4 validation, cache keys, sequential probe execution.
  - `MessagePresentationTest`: LRU caching, thinking block separation, code block parsing, placeholder detection.
  - `SharedRequestPipelineTest`, `ModelRuntimeCoordinatorTest`, `LiteRtRuntimeCacheTest`: Multi-turn state, cache protection.
- **Android Lint**: **0 errors** (`abortOnError = true` in `app/build.gradle.kts`). `lintDebug` passes cleanly.
- **Native & App Assembly**: `assembleDebug` and `assembleRelease` both succeed.
  - Native library `libsolus_llama.so` and test runner executable `llama_native_test_runner` compiled cleanly via CMake with arm64-v8a page-alignment (`-Wl,-z,max-page-size=16384`) and `-O3`.
  - Release APK minified and packaged successfully via R8.
- **Instrumented Test**: `ChatRuntimeResponsivenessTest` in `app/src/androidTest/java/com/shounak/localmeshai/ui/ChatRuntimeResponsivenessTest.kt`.

## Device benchmark table

No physical Android device is connected (`adb devices` returned no attached hardware). These entries are deliberately unmeasured.

| Runtime/model | Warm TTFT | Cold TTFT | Idle reload | Sustained decode | Coherent response |
|---|---|---|---|---|---|
| Llama 3.2 1B GGUF CPU | Not measured | Not measured | Not measured | Not measured | Unverified |
| Llama 3.2 1B validated Vulkan | Not measured | Not measured | Not measured | Not measured | Unverified |
| Another GGUF family | Not measured | Not measured | Not measured | Not measured | Unverified |
| LiteRT text and supported multimodal | Not measured | Not measured | Not measured | Not measured | Unverified |
| Supported MediaPipe model | Not measured | Not measured | Not measured | Not measured | Unverified |

The next device run must use the release APK on the same physical phone (Infinix Note 50s, 8 GB RAM) for before/after comparison. Record phone/OS, model SHA-256, native build, actual backend, allocated context and batches, thread count, thermal state, prompt size, output token limit, and media dimensions/duration. Use 20 warm and five cold samples per representative short-text, long-history, document, image, and supported audio scenario. Also compare idle reload and cached/uncached prompt evaluation. Preserve comparable output limits and media quality.

Acceptance targets: acknowledgement within 100 ms; publish first available visible text within 50 ms without another chunk; demonstrate reduced latency in preparation/cache paths and sustained decode without formatting stalls. Investigate repeatable regressions above 5% before accepting a tuning change. Do not claim improvement from shorter answers.

## Explicitly unverified scenarios

- On-device latency, throughput, thermal behavior, peak memory, and the reported gibberish remain unverified because no physical phone is connected.
- CPU and validated GPU responses to “Explain quantum computing in simple terms” and simple control prompts on the Infinix Note 50s.
- Real SDK/native cancellation and resource shutdown during preparation, model loading, decoding, activity recreation, and rapid model/session switching on physical hardware.
- Full device attachment flows: scanned-PDF vision fallback, image/audio quality, durable history, legacy-image migration, and persistent compilation cache reuse after restart.
- Native runner execution, real-model cached-versus-fresh logits, reduced-context allocation under memory pressure, GPU-driver mismatch, and recovery on a physical device.
- Instrumented UI timing and all runtime/media combinations; host unit tests do not establish these outcomes.

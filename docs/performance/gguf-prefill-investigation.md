# Solus GGUF Prefill Latency, Backend Isolation, and UI-Freezing Investigation

## 1. Executive Summary & Forensic Findings

During forensic analysis of inference runs on the Infinix Note 50s (MediaTek Dimensity 7020 / Mali-G615 MC2, 8 GB RAM), two severe issues were diagnosed:
1. **Unintended Vulkan Opportunistic Offloading on "CPU" Paths**: When the application requested CPU execution (or fell back to CPU after GPU numerical validation failure), native `llama.cpp` still initialized a Vulkan compute device (`Vulkan0 Mali-G615 MC2`) and allocated a **332.74 MiB Vulkan compute buffer**.
2. **Extreme Initial Prompt-Processing (Prefill) Delays**:
   - **Llama 3.2 1B (CPU Fallback)**: Prompt processing took **47.26 seconds** for 38 input tokens (181 graph splits for the batch reserved graph).
   - **Qwen 2.5 0.5B (Vulkan)**: Prompt processing took **15.75 seconds** for 36 input tokens.
   - Initial acknowledgement was instantaneous (1 ms), proving that the latency bottleneck was entirely native prompt evaluation and backend graph compilation/splitting, not coroutine scheduling or queue delays.
3. **Severe UI Thread Stalls & Rendering Backpressure**:
   - In a 99-second Perfetto trace, the main UI thread accumulated **53.37 seconds stalled in `postAndWait`**, with individual frame stalls up to **883 ms**.
   - 698 of 1,891 recorded frame records suffered deadline misses due to GPU pipeline serialization and RenderThread backpressure.

### Forensic Metrics Baseline (Debug Builds on Infinix Note 50s)

| Measurement | Llama 3.2 1B (Reported CPU) | Qwen 2.5 0.5B (Vulkan) |
|---|---:|---:|
| Requested Backend | CPU (after cached validation failure) | Vulkan (cached pass) |
| Native Initialisation | ~0.75 s | ~3.03 s |
| Input Tokens | 38 | 36 |
| Prompt Processing (Prefill) | **47.26 s** | **15.75 s** |
| Send to First Visible Token | 47.52 s | 15.93 s |
| Generation Rate (Single Token) | 4.86 tokens/s | 8.74 tokens/s |
| Generated Tokens | 7 | 9 |
| Allocated Vulkan Compute Buffer | **332.74 MiB** (Unintended!) | Active Vulkan device |
| Reserved Graph Splits | 181 splits | 1 split (single-token) |

---

## 2. Root Cause Analysis

### A. Vendored llama.cpp Device Selection Invariant
In the vendored version of `llama.cpp`:
- Setting `model_params.devices = nullptr` instructs the runtime to **automatically discover and enable all available compute backends**, including Vulkan.
- Even when `n_gpu_layers = 0`, the Vulkan backend contains opportunistic kernel offload rules that activate whenever batch size $N \ge 32$. During initial prompt evaluation (where batch size was 256 or 512), graph operations were opportunistically sliced across CPU and Vulkan, incurring massive synchronization overhead and 181 graph splits.
- To **completely and strictly disable GPU compute**, the runtime requires a non-null, empty device array:
  ```cpp
  static ggml_backend_dev_t s_cpu_only_devices[] = { nullptr };
  model_params.devices = s_cpu_only_devices;
  model_params.n_gpu_layers = 0;
  model_params.split_mode = LLAMA_SPLIT_MODE_LAYER;
  context_params.offload_kqv = false;
  ```
  *(Note: `LLAMA_SPLIT_MODE_NONE` in this vendored version expects a valid GPU index, so `LLAMA_SPLIT_MODE_LAYER` must be retained).*

### B. Controller Concurrency Races
- In `ModelLoadController.kt`, `inFlightDeferred!!.await()` was previously invoked inside `mutex.withLock`. Any concurrent caller attempting to acquire the mutex to cancel or check state would be blocked, causing coroutine serialization and deadlocks.
- Checking only `job.isActive` was insufficient to guard native lifecycle, as cancellation immediately sets `isActive = false` before native code finishes exiting.

---

## 3. Implemented Architecture & Changes

### A. Centralized Native Backend Isolation (`gguf-backend-config.h`, `llama-jni.cpp`)
- Created `app/src/main/cpp/gguf-backend-config.h` defining:
  - Stable storage `s_cpu_only_devices[] = { nullptr }`.
  - `configure_cpu_backend_params(model_params, context_params)`: Sets CPU device list, 0 GPU layers, `LLAMA_SPLIT_MODE_LAYER`, and `offload_kqv = false`.
  - `configure_vulkan_backend_params(model_params, context_params, devices_storage, gpu_layers)`: Explicitly assigns the designated Vulkan device and stores it for the model lifetime.
  - `inspect_gguf_backend(ctx, model)`: Extracts compute backend names, checks for attached GPU backends, and measures GPU buffer bytes.
  - `verify_cpu_backend_isolation(ctx, model)`: Enforces that zero GPU backends and zero GPU buffer memory are attached during CPU execution.
- Updated `llama-jni.cpp`:
  - Applied CPU configuration helper across **all** CPU code paths: explicit CPU selection, Auto fallback, CPU reference validation, initialization fallback, generation recovery, and diagnostics.
  - Recreates both model and context parameters when falling back from Vulkan to CPU.
  - Guarded `op_control->is_cancelled()` against null pointers when `operationControlId = 0`.
  - Exported metadata via `nativeGetMetadata`: `actual_compute_backends`, `effective_offload_kqv`, `is_cpu_isolated`, `gpu_compute_buffer_bytes`, `backend_policy_version`.

### B. Native Test Runner Boundary Invariant (`llama-native-test-runner.cpp`)
- Fixed prompt batch evaluation to chunk prompt tokens by `cparams.n_batch`, preventing buffer overflows on longer prompts.
- Added deterministic unit test `test_cpu_backend_isolation_and_boundary_batching()` validating:
  - Device list invariant `s_cpu_only_devices[0] == nullptr`.
  - Boundary token evaluations (31, 32, 33, 64, 128 tokens) ensuring no Vulkan compute backend is ever attached during CPU evaluation.

### C. Trustworthy Backend Metadata & Protocol v5 Validation (`GgufGpuValidator.kt`, `ModelLoadState.kt`)
- Incremented `GgufGpuValidator.PROTOCOL_VERSION` from 4 to 5.
- Incorporated `backendPolicyVersion` and `isCpuIsolated` into receipt structure and cache key.
- Invalidated older receipts (treating them as pending/unverified).
- Verified `cpuMeta?.isCpuIsolated == true` before generating reference probabilities.
- Maintained strict total-variation distance requirement: $\text{TV} \le 0.05$ across all 8 teacher-forced positions.

### D. Conservative Auto Selection Policy (`GgufBackendPolicy.kt`, `GgufPerformanceStore.kt`)
- Created shared selection policy:
  - **No performance check receipt** $\rightarrow$ True CPU.
  - **Numerical validation missing, failed, or stale** $\rightarrow$ True CPU.
  - **Performance check favors CPU** $\rightarrow$ True CPU.
  - **Numerical PASS AND qualifying performance receipt** $\rightarrow$ Vulkan.
- Retained explicit CPU/Vulkan selection: explicit Vulkan respects known permanent numerical failures by falling back to CPU; failed GPU runs perform an atomic single retry on true CPU after releasing the failed GPU engine.

### E. Real User-Started Benchmark (`GgufBenchmarkRunner.kt`, `ModelManagerScreen.kt`)
- Replaced the hardcoded/simulated `Latency (TTFT): ~135 ms` placeholder with a real user-started check in the Diagnostics screen.
- Runs 5 cold samples and 20 warm samples per scenario.
- Captures frame render timings to detect UI stalls $> 100\text{ ms}$.
- Requires $\ge 10\%$ warm TTFT improvement, $\le 5\%$ cold/decode/p95 frame regressions, and zero rendering stalls to qualify Vulkan.

### F. Controller Concurrency & Watchdog Hardening (`ModelLoadController.kt`)
- **Await outside mutex**: Captured `inFlightDeferred` under mutex lock and awaited outside the lock in `loadOrAttach`.
- **Completion-aware lifecycle**: Checked `job.isCompleted == false` instead of `isActive`.
- **Unified 30s shutdown path**: Routed cancellation, reset, and replacement through `shutdown(reason)`. If native shutdown exceeds 30 seconds, transitioned to `ShutdownStalled` and prohibited subsequent loads.
- **Stale update rejection**: Enforced checks against both `currentOperationId` and `currentEpoch` across progress and completion handlers.
- **Removed unrestricted backdoor**: Removed unused `markReady()`.

### G. Metrics Accuracy & Synchronized Tracing (`InferenceTiming.kt`, `ChatViewModel.kt`, Perfetto)
- Extended `InferenceMetrics` with native prompt tokens, reused tokens, evaluated tokens, and prefill duration.
- Updated `ChatViewModel` to log the populated metrics object in `finally` block instead of unpopulated empty summaries.
- Added `beginAsyncSection` and `endAsyncSection` to `AppTrace.kt`.
- Created `tools/perfetto/gguf.pbtxt` with `atrace_apps: "com.shounak.localmeshai"` for full app-marker trace capture.
- Created `tools/capture-gguf-trace.ps1` for simultaneous ADB Logcat and Perfetto capture.

---

## 4. Fresh Verification Results

Verification command executed using Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease --no-configuration-cache
```

### Build & Test Summary:
- **Unit Tests**: All unit tests passing (0 failures, 0 skipped).
  - `GgufBackendPolicyTest`: Verified Auto defaulting to CPU, explicit fallback on numerical failure, and Vulkan qualification.
  - `ModelLoadControllerTest`: Verified load reuse without mutex deadlocks, timeout transitions, and state clearing.
  - `LlamaCppEngineTest`: Verified metadata parsing, CPU isolation flags, and lifecycle safety.
  - `GgufGpuValidatorTest`: Verified Protocol v5 cache keys, sequential probe lifecycle, and isolation requirement.
  - `InferenceTimingTest`: Verified token metrics recording and throughput calculation over decode intervals.
- **Android Lint**: Clean pass (`abortOnError = true`). Zero errors.
- **Native & App Assembly**:
  - `libsolus_llama.so` and `llama_native_test_runner` compiled cleanly for `arm64-v8a` with 16 KiB ELF page alignment (`-Wl,-z,max-page-size=16384`) and `-O3`.
  - Debug and Release APKs packaged successfully; R8 minification succeeded with full reflection and native keep rules intact.

---

## 5. Physical Device Benchmark Table (Infinix Note 50s)

> [!NOTE]
> Physical hardware was not connected during this execution (`adb devices` returned no attached devices). Physical device latency, throughput, thermals, and memory measurements remain unverified.

| Runtime / Model | Warm TTFT | Cold TTFT | Idle Reload | Sustained Decode | Numerical Agreement |
|---|---|---|---|---|---|
| **Llama 3.2 1B (True Isolated CPU)** | *Unverified on device* | *Unverified on device* | *Unverified on device* | *Unverified on device* | Reference Baseline |
| **Llama 3.2 1B (Vulkan GPU)** | *Unverified on device* | *Unverified on device* | *Unverified on device* | *Unverified on device* | TV > 0.05 (Known Disagreement) |
| **Qwen 2.5 0.5B (True Isolated CPU)** | *Unverified on device* | *Unverified on device* | *Unverified on device* | *Unverified on device* | Reference Baseline |
| **Qwen 2.5 0.5B (Vulkan GPU)** | *Unverified on device* | *Unverified on device* | *Unverified on device* | *Unverified on device* | Prior Pass (Protocol v4, pending v5 check) |
| **LiteRT Text (CPU / GPU)** | *Unverified on device* | *Unverified on device* | *Unverified on device* | *Unverified on device* | Reference Baseline |

---

## 6. Explicitly Unverified Scenarios

The following physical device scenarios remain explicitly unverified until tested on physical hardware:
1. **Physical On-Device Latency & Throughput**: Exact wall-clock TTFT reduction on the Infinix Note 50s for true CPU vs Vulkan across short prompts, long documents, and chat sessions.
2. **Thermal Throttling & Sustained Decode**: Sustained tokens/second and device skin temperature over multi-turn conversations.
3. **Physical Vulkan Shader Compilation Stalls**: Direct measurement of initial Mali-G615 pipeline creation time on device reboot vs warm execution.
4. **Reported Gibberish / Numerical Anomaly on Hardware**: Verification of coherent response generation on physical hardware for complex queries ("Explain quantum computing in simple terms") and arithmetic tasks.
5. **Simultaneous Capture on Hardware**: Live verification using `tools/capture-gguf-trace.ps1` with attached phone under active user typing and scrolling.

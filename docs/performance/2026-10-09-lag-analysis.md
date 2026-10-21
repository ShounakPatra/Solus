# Solus Chat Responsiveness & Lag Analysis (2026-10-09)

## 1. Executive Summary & Trace Evidence

An in-depth performance analysis was conducted on Solus v5.0.0 based on system traces (`DOC-20261009-WA0004.bin`) and logcat captures during inference on LiteRT CPU models.

### Key Trace Findings
- **Main Thread Execution:** Approximately **33.3 seconds** of main-thread execution time was consumed during active model inference.
- **Draw Pass Latency:** Individual draw passes approached **500 ms**, causing severe frame drops, UI freezes, and missed SurfaceFlinger frame deadlines.
- **Scope & Applicability:** While the supplied trace originated from LiteRT CPU execution, the bottlenecks were architectural and affected all supported runtimes (LiteRT-LM, MediaPipe text & vision, and llama.cpp/GGUF CPU and Vulkan).

---

## 2. Root Cause Analysis

Investigation revealed six compounding factors causing main-thread starvation and rendering lag:

### A. Synchronous Presentation & Repeated Parsing During Streaming
- `MessagePresentation.getFastInitial()` executed synchronous regular expressions and thinking tag extraction on the main thread during composition.
- For every emitted token chunk, rich Markdown, LaTeX/math expressions, and code blocks were reparsed from scratch.
- The entire message list was wrapped inside a top-level `SelectionContainer`, forcing re-registration and re-layout of all text layout results on each token update.
- Competing auto-scroll coroutines repeatedly forced `scrollOffset = 100_000` on every token, causing continuous scroll layout churn.

### B. High-Frequency Streaming Publisher Callbacks & Contention
- Tokens were published at unconstrained frequencies or with per-chunk synchronized blocks that performed heavy transformations under locks.
- Stale callbacks from earlier retry or recovery attempts could overwrite new attempt state.
- In some fallback paths, empty answer outputs erroneously fell back to raw thinking buffers or reasoning-only text.

### C. CPU Thread Oversubscription
- On mobile devices with 8 cores, runtime configurations frequently allocated too many worker threads (e.g. 6–8 threads), starving the Android main UI thread (`com.shounak.localmeshai`), the RenderThread, and SurfaceFlinger.
- Low-memory devices (<6GB RAM) and devices in battery-saver mode suffered severe CPU thermal throttling.

### D. Blocking Runtime Ownership & Mutex Contention
- In `ModelRuntimeCoordinator`, a single transition mutex was held across the entire generation lifecycle (`block()`), locking out `requestStop()` and state inspections for the full duration of generation (30+ seconds).
- Native control tokens were susceptible to multiple release attempts, and `ensureModelReady()` lacked strict deduplication for in-flight requests.

### E. Per-Frame Compose Allocations & AGSL Shaders
- In `GlassUtils.animatedGlassHalo`, `Path().apply { addRoundRect(...) }` was instantiated on every single animation frame inside `onDrawWithContent`, creating heavy garbage collection churn.
- Runtime AGSL shaders (`causticShader`, `dispersionEffect`) executed heavy fragment math on the GPU even during intense CPU/GPU model inference.

### F. Synchronous Persistence & Store Deadlocks
- `ChatSessionRepository`, `MemoryStore`, and `RagVectorStore` used `writeMutex` with `runBlocking` on the main thread, causing ANRs and lock-order inversion deadlocks.
- Store schema version changes resulted in destructive file deletion instead of graceful recovery or non-destructive migration.

---

## 3. Systematic Architecture Fixes Implemented

### 1. Presentation & Scrolling Pipeline
- **Decoupled Plain vs. Rich Presentation:** During streaming, plain answer text and reasoning text are displayed without parsing. Terminal rich Markdown and math formatting are computed once off-thread on `Dispatchers.Default` and cached by `(messageId, requestId, attemptId, revision)`.
- **Single Auto-Scroll Controller:** A unified `snapshotFlow` debounced to 100 ms follows user scrolling only when the user is at the bottom. It stops immediately when the user drags away and resumes when they return to the bottom.
- **Targeted Selection:** Removed list-wide `SelectionContainer`; applied selection containers strictly to completed, non-streaming message bubbles.

### 2. Streaming Response Publisher Contract
- **50 ms Conflation Window:** Tokens are buffered and published at 50 ms intervals with immediate publication of the first non-empty token and immediate terminal flush.
- **Monotonic Attempt Isolation:** Each generation is stamped with an immutable `requestId` and monotonic `attemptId`. Callbacks from earlier attempts are dropped.
- **Strict Separation of Reasoning & Answer:** Reasoning-only output is never substituted for an answer; empty answers are strictly flagged as `EmptyResponseReason.ReasoningOnly`.

### 3. Centralized RuntimeExecutionPolicy
- **Unified Formula:** `(availableProcessors - 2).coerceIn(1, 4)`.
- **Resource Constraints:** Automatically capped at **2 threads** when total RAM is under 6 GB or when the device is in power-save mode.
- **Zero Model-Name Checks:** Applied uniformly across LiteRT, MediaPipe, and GGUF without string matching on model family or parameter size.

### 4. Non-Blocking Runtime Ownership & Cancellation
- **Handoff-Only Mutex:** `transitionMutex` now only protects runtime ownership transitions and is released before generation starts.
- **Instantaneous Stop:** `requestStop()` executes without acquiring generation locks, cancelling active native and coroutine operations immediately.
- **Optimized Rendering:** Cached `Path()` allocation in `drawWithCache` and suppressed AGSL shaders and animated halos during `isRuntimeBusy`.

### 5. Suspending Persistence & Store Safety
- **Fully Suspending Storage:** Converted repository calls to suspend functions; removed `runBlocking` and `writeMutex` deadlocks.
- **Non-Destructive Version Handling:** Introduced `AtomicReadResult.UnsupportedVersion` to preserve unrecognized files without overwriting or deleting user data.
- **Turn Preservation on Retry:** Preserved subsequent assistant and user turns when retrying earlier messages.

### 6. Interruptible Downloads & Document Safety
- **Cancellable HTTP Transfers:** Introduced `CancellableHttpTransfer` for bounded, interruptible byte transfers.
- **Lock-Free Network Transfers:** Mutexes in `DownloadController` are released during network I/O, allowing pause and cancel to execute immediately.
- **Committed Artifact Protection:** `ModelArtifactStorage.publishArtifact` treats manifest write as the commit point and will not delete generation directories after commit. Added generation lease tracking.
- **Bounded Stream Draining:** `ArchiveTextExtractor` drains uncompressed entry bytes safely against remaining archive budgets.

### 7. Journal-Backed Deletion, Lease Tracking & Aggressive Cache Pruning
- **Isolated Attachment Storage:** `ChatAttachmentStore` guarantees deletions operate strictly within app-owned partitions (`filesDir/chat_attachments/<kind>/<sessionId>/<attachmentId>/`), preventing accidental deletion of user media or external files.
- **Tombstone-Protected Deletions:** `ChatDeletionJournal` records deletions atomically with tombstones before removing disk resources, eliminating race conditions where concurrent reloads resurrect deleted items.
- **Zero-Age Inactive Cache Purge:** `CacheCleanupCoordinator` and `LiteRtRuntimeCache.clearInactive` clear all unleased compiled model kernels and disposable multimodal inputs on app background, reopen, model reload, model switch, and model unload regardless of age or count.
- **Reference-Counted Leases:** Active model generation and multimodal readers hold canonical leases (`ModelArtifactStorage.leaseArtifact`, `ActiveInputFiles.protect`, `LiteRtRuntimeCache.lease`) ensuring cache clearing never deletes files actively being processed.

---

## 4. Physical Device Acceptance Matrix

| Metric | Target | Verification Status |
|---|---|---|
| UI acknowledgement of typing / Stop | p95 < 100 ms | **NOT RUN** (Host verification only; physical hardware not attached) |
| First answer delta to Main-state application | p95 < 100 ms | **NOT RUN** (Host verification only) |
| Warm interaction frame duration | No frame > 100 ms | **NOT RUN** (Host verification only) |
| Warm interaction deadline-miss rate | < 5% | **NOT RUN** (Host verification only) |
| GGUF CPU Execution | Pass | Host unit tests **PASS**; device **NOT RUN** |
| GGUF Vulkan Execution | Pass | Host unit tests **PASS**; device **NOT RUN** |
| LiteRT CPU Execution | Pass | Host unit tests **PASS**; device **NOT RUN** |
| LiteRT GPU Execution | Pass | Host unit tests **PASS**; device **NOT RUN** |
| MediaPipe Text & Vision | Pass | Host unit tests **PASS**; device **NOT RUN** |
| Custom/Imported Models | Pass | Host unit tests **PASS**; device **NOT RUN** |

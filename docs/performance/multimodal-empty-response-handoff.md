# Solus Multimodal Response Correctness & Empty-Answer Handoff

## 1. Root Cause Analysis

Investigation identified multiple independent mechanisms that previously caused answers to disappear during multimodal inference or led to "No answer generated" fallbacks:

1. **Empty Chunk Erasing In-Flight Answers**:
   In both `ChatInferenceManager` and `VisionInferenceManager`, empty string deltas (`""`) triggered an accumulator reset (`publisher.reset()`). When a model emitted an empty token or synchronization boundary, previously accumulated text was wiped.
   *Resolution*: Empty text deltas are safely ignored (`if (delta.isNotEmpty()) publisher.appendDelta(delta)`). `StreamingResponsePublisher.appendDelta("")` is a strict no-op that never clears buffers.

2. **LiteRT Channels Omission in String Representation**:
   LiteRT-LM 0.13.1's `message.toString()` includes standard message contents but excludes `message.channels`. Consequently, channel-only events (such as `thought` or `reasoning`) appeared empty, triggering the aforementioned reset bug and dropping answers.
   *Resolution*: Implemented `LiteRtStreamAdapter`, which natively implements LiteRT's `MessageCallback` interface. It processes `Content.Text` chunks into the primary answer buffer and separates named channels (`thought`, `reasoning`, `thinking`) into dedicated reasoning buffers without modifying answer state.

3. **Overly Aggressive Final Response Cleanup**:
   Previously, response formatting heuristic `extractFinalAnswerOnly()` rejected answers that began with phrases like "Let's see", treating them as thinking artifacts and wiping the response. Furthermore, synthetic greeting substitutions overwrote actual model output.
   *Resolution*: Removed subjective text-matching heuristics and synthetic answer substitutions. Explicit tags (`<think>...</think>`) and dedicated reasoning channels are separated cleanly, preserving legitimate answer content.

4. **Image-Only Requests Lacking Default Prompts**:
   When users submitted an image with an empty text prompt, the inference engine received blank prompt input, causing models to complete immediately without generating text.
   *Resolution*: `VisionRequestContextResolver` standardizes `DEFAULT_IMAGE_PROMPT = "Describe this image."` whenever an image is provided without a question.

5. **Image Follow-Up Loss Across Conversation Turns**:
   Follow-up questions like "What color is it?" or "Describe the image above" lost image context following session resets or idle unloading.
   *Resolution*: `VisionRequestContextResolver` implements deterministic media precedence:
   - Rule 1: A newly attached image takes top precedence.
   - Rule 2: For text-only follow-ups, the most recent user image in the active session is retained and resolved.
   - Rule 3: Explicit document or audio attachments override image reuse.
   - Rule 4: Messages from other sessions or assistant thumbnail outputs are never reused.
   - Rule 5: LiteRT conversation restoration via `buildRestorationHistory` excludes transient placeholders and failed attempts.

6. **Terminal Outcome Handling & Partial Answer Preservation**:
   Earlier implementations overwrote accumulated partial text with error messages or the "No answer generated." fallback when a request terminated unexpectedly.
   *Resolution*: `ResponseFinalizer` produces typed terminal states (`Completed`, `Stopped`, `Failed(ReasoningOnly)`, `Failed(NoVisibleAnswer)`). `ChatMessage` and `VisionChatMessage` store model text in `text` and error reasons in `errorMessage`. Partial output is displayed in the UI bubble alongside an error card with a non-duplicating "Retry" button.

---

## 2. Modified and Created Files

### Created Components
- `app/src/main/java/com/shounak/localmeshai/ai/ResponseFinalizer.kt`: Single authoritative finalizer enforcing non-blank completions, preserving partial text on error/stop, and categorizing empty responses (`NoVisibleAnswer` vs `ReasoningOnly`).
- `app/src/main/java/com/shounak/localmeshai/ai/LiteRtStreamAdapter.kt`: Concrete `MessageCallback` implementation isolating text deltas from reasoning channels, ensuring thread safety and ignoring late events.
- `app/src/main/java/com/shounak/localmeshai/ai/VisionRequestContextResolver.kt`: Main-thread request snapshot resolver managing prompt defaults (`DEFAULT_IMAGE_PROMPT`), image file validation, media precedence, and conversation history restoration.
- `app/src/test/java/com/shounak/localmeshai/ai/ResponseFinalizerTest.kt`: Unit tests for terminal status transitions and empty reason mapping.
- `app/src/test/java/com/shounak/localmeshai/ai/LiteRtStreamAdapterTest.kt`: Unit tests for chunk streaming, reasoning channels, and empty-event resilience.
- `app/src/test/java/com/shounak/localmeshai/ai/VisionRequestContextResolverTest.kt`: Unit tests for prompt default substitution and media resolution.
- `app/src/androidTest/java/com/shounak/localmeshai/ui/VisionAnswerCompletionTest.kt`: Instrumented Android test suite validating answer preservation and prompt resolution.

### Modified Components
- `app/src/main/java/com/shounak/localmeshai/ai/VisionInferenceManager.kt`: Replaced legacy event loops with `LiteRtStreamAdapter` and `ResponseFinalizer`.
- `app/src/main/java/com/shounak/localmeshai/ai/ChatInferenceManager.kt`: Integrated `LiteRtStreamAdapter` and updated GGUF CPU fallback to invoke `publisher?.beginAttempt(2)`.
- `app/src/main/java/com/shounak/localmeshai/utils/StreamingResponsePublisher.kt`: Added `beginAttempt()`, protected accumulated content from empty deltas, and guarded terminal `finish()`.
- `app/src/main/java/com/shounak/localmeshai/ui/viewmodels/VisionViewModel.kt`: Integrated `VisionRequestContextResolver`, retained last failed request, added `retryLastFailedMessage()`, and separated `text` from `errorMessage`.
- `app/src/main/java/com/shounak/localmeshai/ui/viewmodels/ChatViewModel.kt`: Added `errorMessage` to `ChatMessage`, retained last failed request, and implemented `retryLastFailedMessage()`.
- `app/src/main/java/com/shounak/localmeshai/ui/screens/ImageGenScreen.kt`: Added `errorMessage` and `onRetry` parameters to `VisionChatBubble`, rendering partial answers with an error card and retry action.
- `app/src/main/java/com/shounak/localmeshai/ui/screens/ChatScreen.kt`: Wired `errorMessage` and `onRetry` into `ChatBubble` and `VisionChatBubble`.
- `app/src/test/java/com/shounak/localmeshai/utils/StreamingResponsePublisherTest.kt`: Added test coverage for `beginAttempt`, empty delta resilience, and blank finish protection.

---

## 3. Device Testing Status & Disclaimer

> [!WARNING]
> **On-Device Gemma 4 Verification Status**:
> No physical Android device was connected during this audit (`adb devices` reported no hardware targets). The response-loss paths were addressed in code and automated tests, but Gemma 4 image answering and the reported on-device failure remain unverified on physical hardware.

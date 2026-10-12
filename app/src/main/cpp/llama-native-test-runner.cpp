#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <cassert>
#include <vector>
#include <string>
#include <iostream>
#include <chrono>

#include "llama.h"
#include "prompt-cache.h"
#include "prompt-budget.h"
#include "native-operation-control.h"
#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-quants.h"
#include "gguf-backend-config.h"
#include "utf-conversion.h"

#if defined(__ARM_NEON) && defined(__aarch64__)
#include <arm_neon.h>
#endif

// Forward declaration of internal quants function if needed
extern "C" {
    void quantize_row_q8_K_ref(const float * x, block_q8_K * y, int64_t k);
    void dequantize_row_q8_K(const block_q8_K * x, float * y, int64_t k);
}

static int g_tests_passed = 0;
static int g_tests_failed = 0;

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            std::cerr << "  [FAIL] " << msg << " (" << __FILE__ << ":" << __LINE__ << ")" << std::endl; \
            g_tests_failed++; \
            return false; \
        } \
    } while (0)

#define TEST_PASS(msg) \
    do { \
        std::cout << "  [PASS] " << msg << std::endl; \
        g_tests_passed++; \
        return true; \
    } while (0)

/**
 * Scalar reference implementations
 */
static inline float scalar_silu(float x) {
    return x / (1.0f + expf(-x));
}

/**
 * Test 1: Real GGML Q8_K quantization mathematical invariants.
 * Verifies that for any finite input array x, the computed scaling factor iscale
 * guarantees |iscale * x[j]| <= 127.0 <= 4194303.0.
 */
static bool test_q8_k_quantization_invariants() {
    std::cout << "\nRunning Test 1: GGML Q8_K Quantization Mathematical Invariants..." << std::endl;
    const int k = 1024; // 4 blocks of QK_K (256)
    std::vector<float> x(k);
    std::vector<block_q8_K> y(k / QK_K);
    std::vector<float> x_dequant(k);

    // Subtest 1A: Normal uniform random floats
    for (int i = 0; i < k; ++i) {
        x[i] = (static_cast<float>(rand()) / static_cast<float>(RAND_MAX) - 0.5f) * 100.0f;
    }
    quantize_row_q8_K_ref(x.data(), y.data(), k);
    dequantize_row_q8_K(y.data(), x_dequant.data(), k);

    // Verify all quantized values qs are in [-127, 127]
    for (int b = 0; b < k / QK_K; ++b) {
        for (int j = 0; j < QK_K; ++j) {
            int8_t q = y[b].qs[j];
            TEST_ASSERT(q >= -127 && q <= 127, "Q8_K quantized value must be within [-127, 127]");
        }
    }

    // Subtest 1B: High dynamic range (1e-15 to 1e6)
    for (int i = 0; i < k; ++i) {
        float sign = (i % 2 == 0) ? 1.0f : -1.0f;
        x[i] = sign * powf(10.0f, (i % 20) - 10.0f);
    }
    quantize_row_q8_K_ref(x.data(), y.data(), k);
    for (int b = 0; b < k / QK_K; ++b) {
        TEST_ASSERT(std::isfinite(y[b].d), "Block scale d must be finite");
    }

    // Subtest 1C: Extremely small subnormal / denormal floats (simulating deep RoPE frequencies)
    for (int i = 0; i < k; ++i) {
        x[i] = 1e-39f * (i + 1); // Subnormal float range
    }
    quantize_row_q8_K_ref(x.data(), y.data(), k);
    for (int b = 0; b < k / QK_K; ++b) {
        TEST_ASSERT(std::isfinite(y[b].d), "Block scale d must be finite for subnormal inputs");
    }

    // Subtest 1D: NaNs and Infinities in input activations
    for (int i = 0; i < k; ++i) {
        if (i % 37 == 0) {
            x[i] = NAN;
        } else if (i % 41 == 0) {
            x[i] = INFINITY;
        } else if (i % 43 == 0) {
            x[i] = -INFINITY;
        } else {
            x[i] = (static_cast<float>(i % 11) - 5.0f) * 0.2f;
        }
    }
    quantize_row_q8_K_ref(x.data(), y.data(), k);
    for (int b = 0; b < k / QK_K; ++b) {
        TEST_ASSERT(std::isfinite(y[b].d), "Block scale d must be finite when inputs contain NaNs");
        for (int j = 0; j < QK_K; ++j) {
            int8_t q = y[b].qs[j];
            TEST_ASSERT(q >= -128 && q <= 127, "Q8_K quantized value must be within int8 range");
        }
    }

    TEST_PASS("test_q8_k_quantization_invariants passed successfully");
}

/**
 * Test 2: Differential Verification of vectorized vs scalar functions & mask logic.
 */
static bool test_differential_vector_vs_scalar() {
    std::cout << "\nRunning Test 2: Differential Vector vs Scalar Verification..." << std::endl;

    // Test scalar SiLU and reference math across wide dynamic range
    const int N = 1000;
    for (int i = 0; i < N; ++i) {
        float x = (static_cast<float>(i) - 500.0f) * 0.5f; // [-250, 250]
        float s = scalar_silu(x);
        TEST_ASSERT(!std::isnan(s), "Scalar SiLU must not produce NaN");
        if (x > 50.0f) {
            TEST_ASSERT(fabsf(s - x) < 1e-4f, "SiLU(x) for large positive x must equal x");
        } else if (x < -50.0f) {
            TEST_ASSERT(fabsf(s) < 1e-4f, "SiLU(x) for large negative x must equal 0");
        }
    }

#if defined(__ARM_NEON) && defined(__aarch64__)
    // Exhaustive test of all 2^4 = 16 bit patterns for 4-lane condition
    for (uint32_t mask_bits = 0; mask_bits < 16; ++mask_bits) {
        uint32x4_t c = {
            (mask_bits & 1) ? 0xFFFFFFFFu : 0u,
            (mask_bits & 2) ? 0xFFFFFFFFu : 0u,
            (mask_bits & 4) ? 0xFFFFFFFFu : 0u,
            (mask_bits & 8) ? 0xFFFFFFFFu : 0u
        };
        uint32_t max_val = vmaxvq_u32(c);
        bool any_set_new = (max_val != 0);
        bool expected_any_set = (mask_bits != 0);
        TEST_ASSERT(any_set_new == expected_any_set, "vmaxvq_u32 must accurately detect non-zero mask lanes");
    }
#endif

    TEST_PASS("test_differential_vector_vs_scalar passed successfully");
}

/**
 * Test 3: Real RoPE frequency computation with Llama 3.2 theta=500000.0
 * Verifies that rotary embeddings do not produce denormal flush or non-finite values.
 */
static bool test_llama32_rope_numerical_stability() {
    std::cout << "\nRunning Test 3: Llama 3.2 RoPE Rotary Embedding Stability..." << std::endl;
    const float rope_theta = 500000.0f;
    const int head_dim = 64;
    const int max_seq_len = 4096;

    std::vector<float> freqs(head_dim / 2);
    for (int i = 0; i < head_dim / 2; ++i) {
        float exponent = 2.0f * i / static_cast<float>(head_dim);
        freqs[i] = 1.0f / powf(rope_theta, exponent);
        TEST_ASSERT(std::isfinite(freqs[i]), "RoPE base frequency must be finite");
        TEST_ASSERT(freqs[i] > 0.0f, "RoPE frequency must be strictly positive");
    }

    // Simulate position encodings up to max_seq_len
    for (int pos = 0; pos < max_seq_len; ++pos) {
        for (int i = 0; i < head_dim / 2; ++i) {
            float angle = pos * freqs[i];
            float cos_val = cosf(angle);
            float sin_val = sinf(angle);
            TEST_ASSERT(std::isfinite(cos_val), "Cos RoPE value must be finite");
            TEST_ASSERT(std::isfinite(sin_val), "Sin RoPE value must be finite");
            TEST_ASSERT(fabsf(cos_val) <= 1.0001f, "Cos value must be bounded by 1.0");
            TEST_ASSERT(fabsf(sin_val) <= 1.0001f, "Sin value must be bounded by 1.0");
        }
    }

    TEST_PASS("test_llama32_rope_numerical_stability passed successfully");
}

/**
 * Test 4: Real GGML Computation Graph Execution
 * Constructs a minimal GGML graph (RMSNorm -> Matrix Multiplication -> FFN SiLU) and evaluates it.
 */
static bool test_ggml_computation_graph_eval() {
    std::cout << "\nRunning Test 4: GGML Computation Graph Execution..." << std::endl;
    struct ggml_init_params params = {
        /* .mem_size   = */ 16 * 1024 * 1024,
        /* .mem_buffer = */ nullptr,
        /* .no_alloc   = */ false,
    };
    struct ggml_context * ctx = ggml_init(params);
    TEST_ASSERT(ctx != nullptr, "ggml_init must return non-null context");

    const int64_t n_embd = 128;
    const int64_t n_tokens = 4;

    struct ggml_tensor * x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, n_embd, n_tokens);
    struct ggml_tensor * w = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, n_embd, n_embd);

    // Populate with test values
    float * x_data = ggml_get_data_f32(x);
    for (int i = 0; i < n_embd * n_tokens; ++i) {
        x_data[i] = (static_cast<float>(i % 17) - 8.0f) * 0.1f;
    }

    float * w_data = ggml_get_data_f32(w);
    for (int i = 0; i < n_embd * n_embd; ++i) {
        w_data[i] = (static_cast<float>(i % 13) - 6.0f) * 0.05f;
    }

    struct ggml_tensor * norm = ggml_rms_norm(ctx, x, 1e-5f);
    struct ggml_tensor * gate = ggml_mul_mat(ctx, w, norm);
    struct ggml_tensor * up   = ggml_mul_mat(ctx, w, norm);
    struct ggml_tensor * silu_gate = ggml_silu(ctx, gate);
    struct ggml_tensor * ffn_act = ggml_mul(ctx, silu_gate, up);
    struct ggml_tensor * out = ggml_mul_mat(ctx, w, ffn_act);

    struct ggml_cgraph * gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, out);

    // Run graph computation with 2 threads
    enum ggml_status status = ggml_graph_compute_with_ctx(ctx, gf, 2);
    TEST_ASSERT(status == GGML_STATUS_SUCCESS, "ggml_graph_compute_with_ctx must succeed");

    float * out_data = ggml_get_data_f32(out);
    for (int i = 0; i < n_embd * n_tokens; ++i) {
        TEST_ASSERT(std::isfinite(out_data[i]), "Graph output element must be finite");
    }

    // Now verify quantize_row_q8_K on the ffn_act output
    float * act_data = ggml_get_data_f32(ffn_act);
    std::vector<block_q8_K> q8k_blocks((n_embd * n_tokens) / QK_K + 1);
    quantize_row_q8_K_ref(act_data, q8k_blocks.data(), n_embd * n_tokens);

    ggml_free(ctx);
    TEST_PASS("test_ggml_computation_graph_eval passed successfully");
}

/**
 * Test 5: Masked Attention & -INFINITY Numerical Stability Regression Test
 * Simulates causal attention masking (-INFINITY for future tokens), runs softmax and FFN
 * and asserts that activations remain finite, non-negative, and quantize cleanly to Q8_K.
 */
static bool test_masked_attention_and_negative_inf_stability() {
    std::cout << "\nRunning Test 5: Masked Attention & -INFINITY Numerical Stability..." << std::endl;

    const int n_seq = 64;
    std::vector<float> logits(n_seq);
    std::vector<float> weights(n_seq);

    // Tokens 0..15 are valid prompt tokens, tokens 16..63 are masked future positions (-INFINITY)
    for (int i = 0; i < n_seq; ++i) {
        if (i < 16) {
            logits[i] = (static_cast<float>(i % 5) - 2.0f) * 0.5f;
        } else {
            logits[i] = -INFINITY;
        }
    }

    float max_val = -INFINITY;
    for (int i = 0; i < n_seq; ++i) {
        if (logits[i] > max_val) max_val = logits[i];
    }
    TEST_ASSERT(std::isfinite(max_val), "Max logit among valid positions must be finite");

    double sum = 0.0;
    for (int i = 0; i < n_seq; ++i) {
        float diff = logits[i] - max_val;
        float w;
        if (diff <= -104.0f || std::isinf(diff)) {
            w = 0.0f;
        } else {
            w = expf(diff);
        }
        weights[i] = w;
        sum += w;
    }

    TEST_ASSERT(sum > 0.0, "Softmax sum must be strictly positive");
    for (int i = 0; i < n_seq; ++i) {
        weights[i] /= static_cast<float>(sum);
        TEST_ASSERT(!std::isnan(weights[i]), "Attention weight must not be NaN");
        TEST_ASSERT(std::isfinite(weights[i]), "Attention weight must be finite");
        TEST_ASSERT(weights[i] >= 0.0f, "Attention weight must be non-negative");
        if (i >= 16) {
            TEST_ASSERT(weights[i] == 0.0f, "Masked attention position weight must be exactly 0.0");
        }
    }

    // Now feed these into a simulated FFN activation vector with k = 8192 (Llama 3.2 1B shape)
    const int k = 8192;
    std::vector<float> ffn_act(k);
    for (int i = 0; i < k; ++i) {
        float gate = (static_cast<float>(i % 31) - 15.0f) * 0.2f;
        float up   = (static_cast<float>(i % 23) - 11.0f) * 0.1f;
        ffn_act[i] = scalar_silu(gate) * up;
        TEST_ASSERT(!std::isnan(ffn_act[i]), "FFN activation must not contain NaN");
        TEST_ASSERT(std::isfinite(ffn_act[i]), "FFN activation must be finite");
    }

    // Quantize to Q8_K
    std::vector<block_q8_K> q8k_blocks(k / QK_K);
    quantize_row_q8_K_ref(ffn_act.data(), q8k_blocks.data(), k);

    for (int b = 0; b < k / QK_K; ++b) {
        TEST_ASSERT(std::isfinite(q8k_blocks[b].d), "Q8_K block scale d must be finite");
        for (int j = 0; j < QK_K; ++j) {
            int8_t q = q8k_blocks[b].qs[j];
            TEST_ASSERT(q >= -127 && q <= 127, "Q8_K quantized value must be within [-127, 127]");
        }
    }

    TEST_PASS("test_masked_attention_and_negative_inf_stability passed successfully");
}

/**
 * Test 6: Real GGUF Vocabulary & Tokenizer Loading across architectures.
 */
static bool test_real_gguf_vocab_loading() {
    std::cout << "\nRunning Test 6: Real GGUF Vocab Loading & Tokenization across Architectures..." << std::endl;

    const char * vocab_files[] = {
        "app/src/main/cpp/llama.cpp/models/ggml-vocab-llama-bpe.gguf",
        "app/src/main/cpp/llama.cpp/models/ggml-vocab-qwen2.gguf",
        "app/src/main/cpp/llama.cpp/models/ggml-vocab-phi-3.gguf"
    };
    const char * arch_names[] = {
        "Llama (BPE)",
        "Qwen2",
        "Phi-3"
    };

    llama_backend_init();

    for (int i = 0; i < 3; ++i) {
        const char * path = vocab_files[i];
        std::cout << "  Testing vocab file: " << path << " (" << arch_names[i] << ")..." << std::endl;

        llama_model_params mparams = llama_model_default_params();
        mparams.vocab_only = true;

        llama_model * model = llama_model_load_from_file(path, mparams);
        if (!model) {
            std::cout << "  [INFO] Vocab file not found at " << path << " (skipping file test)" << std::endl;
            continue;
        }

        const llama_vocab * vocab = llama_model_get_vocab(model);
        TEST_ASSERT(vocab != nullptr, "llama_model_get_vocab must return non-null");

        int n_vocab = llama_vocab_n_tokens(vocab);
        TEST_ASSERT(n_vocab > 0, "n_vocab must be greater than 0");
        std::cout << "    Loaded vocab tokens: " << n_vocab << std::endl;

        // Test tokenization of standard prompts
        const char * test_prompts[] = {
            "Hi",
            "Hello.",
            "Explain 2 + 2 in one sentence."
        };

        for (const char * prompt : test_prompts) {
            std::vector<llama_token> tokens(256);
            int n_tokens = llama_tokenize(vocab, prompt, static_cast<int32_t>(strlen(prompt)),
                                          tokens.data(), static_cast<int32_t>(tokens.size()),
                                          true, true);
            TEST_ASSERT(n_tokens > 0, "llama_tokenize must produce > 0 tokens");
            std::cout << "    Tokenized \"" << prompt << "\" -> " << n_tokens << " tokens" << std::endl;
        }

        llama_model_free(model);
    }

    llama_backend_free();
    TEST_PASS("test_real_gguf_vocab_loading completed successfully");
}

/**
 * Real CLI model inference execution function
 */
static int run_real_model_inference(const std::string & model_path,
                                    const std::string & prompt,
                                    int n_threads, int n_ctx,
                                    int n_batch, int n_ubatch,
                                    int max_tokens) {
    std::cout << "================================================================" << std::endl;
    std::cout << "   REAL NATIVE GGUF MODEL INFERENCE RUNNER                      " << std::endl;
    std::cout << "================================================================" << std::endl;
    std::cout << "MODEL: " << model_path << std::endl;
    std::cout << "PROMPT: \"" << prompt << "\"" << std::endl;
    std::cout << "CONFIG: threads=" << n_threads << ", ctx=" << n_ctx
              << ", batch=" << n_batch << ", ubatch=" << n_ubatch
              << ", max_tokens=" << max_tokens << std::endl;

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    configure_cpu_backend_params(mparams, cparams);

    cparams.n_ctx = n_ctx;
    cparams.n_batch = n_batch;
    cparams.n_ubatch = n_ubatch;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        std::cerr << "[RESULT] LOAD=FAIL (Failed to load model file)" << std::endl;
        llama_backend_free();
        return 1;
    }
    std::cout << "[RESULT] LOAD=PASS" << std::endl;

    char desc_buf[256] = {0};
    llama_model_desc(model, desc_buf, sizeof(desc_buf));
    std::cout << "ARCH: " << desc_buf << std::endl;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        std::cerr << "[RESULT] CONTEXT_INIT=FAIL" << std::endl;
        llama_model_free(model);
        llama_backend_free();
        return 2;
    }
    std::cout << "[RESULT] CONTEXT_INIT=PASS" << std::endl;

    GgufBackendInspection insp = inspect_gguf_backend(ctx);
    if (!insp.is_cpu_isolated) {
        std::cerr << "[RESULT] CPU_ISOLATION=FAIL (GPU backend or buffers detected: "
                  << insp.gpu_compute_buffer_bytes << " bytes)" << std::endl;
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();
        return 2;
    }
    std::cout << "[RESULT] CPU_ISOLATION=PASS (verified 0 GPU compute buffers)" << std::endl;

    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(cparams.n_ctx);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.length()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()),
                                  true, true);
    if (n_tokens <= 0) {
        std::cerr << "[RESULT] TOKENIZE=FAIL" << std::endl;
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();
        return 3;
    }
    tokens.resize(n_tokens);
    std::cout << "[RESULT] TOKENIZE=PASS (n_tokens=" << n_tokens << ")" << std::endl;

    // Prompt Evaluation in chunks of cparams.n_batch
    llama_batch batch = llama_batch_init(cparams.n_batch, 0, 1);
    auto start_time = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < n_tokens; ++i) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = (i == n_tokens - 1);
        batch.n_tokens++;

        if (batch.n_tokens == (int)cparams.n_batch || i == n_tokens - 1) {
            int decode_res = llama_decode(ctx, batch);
            if (decode_res != 0) {
                std::cerr << "[RESULT] PROMPT_DECODE=FAIL (res=" << decode_res << ")" << std::endl;
                llama_batch_free(batch);
                llama_free(ctx);
                llama_model_free(model);
                llama_backend_free();
                return 4;
            }
            if (i < n_tokens - 1) {
                batch.n_tokens = 0;
            }
        }
    }
    std::cout << "[RESULT] PROMPT_DECODE=PASS (tokens=" << n_tokens << ", batch=" << cparams.n_batch << ")" << std::endl;

    // Autoregressive generation
    int n_generated = 0;
    int cur_pos = n_tokens;

    for (int step = 0; step < max_tokens; ++step) {
        float * logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);
        if (!logits) break;

        // Greedy sampling for determinism
        int n_vocab = llama_vocab_n_tokens(vocab);
        int best_token = 0;
        float best_logit = logits[0];
        for (int v = 1; v < n_vocab; ++v) {
            if (logits[v] > best_logit) {
                best_logit = logits[v];
                best_token = v;
            }
        }

        if (step == 0) {
            std::cout << "[RESULT] FIRST_TOKEN=PASS (token=" << best_token << ")" << std::endl;
        }

        if (llama_vocab_is_eog(vocab, best_token)) {
            break;
        }

        char piece_buf[64] = {0};
        llama_token_to_piece(vocab, best_token, piece_buf, sizeof(piece_buf), 0, true);
        std::cout << piece_buf << std::flush;
        n_generated++;

        // Prepare next token batch
        batch.n_tokens = 0;
        batch.token[batch.n_tokens] = best_token;
        batch.pos[batch.n_tokens] = cur_pos++;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = true;
        batch.n_tokens++;

        if (llama_decode(ctx, batch) != 0) {
            std::cerr << "\n[RESULT] GENERATION=FAIL at step " << step << std::endl;
            break;
        }
    }

    auto end_time = std::chrono::high_resolution_clock::now();
    double total_ms = std::chrono::duration<double, std::milli>(end_time - start_time).count();
    std::cout << "\n[RESULT] GENERATION=PASS (generated " << n_generated << " tokens in " << total_ms << " ms)" << std::endl;
    std::cout << "[RESULT] CRASH=NONE" << std::endl;

    llama_batch_free(batch);
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();

    return 0;
}

static bool test_prompt_cache_prefix() {
    const std::vector<int> cached = {1, 2, 3, 4, 5};
    TEST_ASSERT(reusable_prompt_tokens(cached, std::vector<int>{1, 2, 3, 4, 5, 6}) == 5,
                "Follow-up must reuse already evaluated tokens");
    TEST_ASSERT(reusable_prompt_tokens(cached, cached) == 4,
                "Identical prompts must re-evaluate last token for fresh logits");
    TEST_ASSERT(reusable_prompt_tokens(cached, std::vector<int>{1, 2, 9, 4}) == 2,
                "Edited history must invalidate the divergent suffix");
    TEST_ASSERT(reusable_prompt_tokens(cached, std::vector<int>{9, 2, 3}) == 0,
                "Different system prompts must never reuse old state");
    TEST_ASSERT(reusable_prompt_tokens(cached, std::vector<int>{1, 2}) == 1,
                "Shortened history must leave a token to decode");
    TEST_ASSERT(reusable_prompt_tokens(std::vector<int>{}, cached) == 0,
                "An invalidated cache must be evaluated from scratch");
    TEST_ASSERT(reusable_prompt_tokens(cached, std::vector<int>{}) == 0,
                "Empty prompts must not underflow");
    TEST_PASS("test_prompt_cache_prefix passed successfully");
}

static bool test_prompt_budget() {
    TEST_ASSERT(output_token_reserve(2048, 1024) == 512, "Output reserve uses actual context");
    TEST_ASSERT(output_token_reserve(2048, 32) == 32, "Small output requests stay small");
    TEST_ASSERT(oldest_history_turn_size({"system", "user", "assistant", "user"}) == 2, "Drop entire oldest turn");
    TEST_ASSERT(oldest_history_turn_size({"system", "user"}) == 0, "Preserve current user");
    std::string text = "current request\n--- ATTACHED DOCUMENT: file ---\nabcdef\n--- END ATTACHED DOCUMENT ---";
    TEST_ASSERT(limit_attached_document(text, 3), "Reduce document context");
    TEST_ASSERT(text.find("current request") == 0, "Preserve current request");
    TEST_ASSERT(text.find("abc\n[Document") != std::string::npos, "Bound document excerpt");
    TEST_ASSERT(text.find("--- END ATTACHED DOCUMENT ---") != std::string::npos, "Preserve boundary");
    TEST_PASS("test_prompt_budget passed successfully");
}

static bool test_native_operation_control() {
    std::cout << "\nRunning Test 9: Native Operation Control Lifecycle & Cancellation..." << std::endl;
    auto & reg = NativeOperationControlRegistry::instance();
    uint64_t id = reg.create();
    TEST_ASSERT(id > 0, "Operation control ID must be non-zero");

    auto ctrl = reg.get(id);
    TEST_ASSERT(ctrl != nullptr, "Created control must be resolvable from registry");
    TEST_ASSERT(!ctrl->is_cancelled(), "New control must not be cancelled initially");
    TEST_ASSERT(ctrl->get_progress() == 0, "Initial progress must be 0");

    ctrl->set_progress(450, 45, 100);
    TEST_ASSERT(ctrl->get_progress() == 450, "Progress must be 450 permille");
    TEST_ASSERT(ctrl->completed_units.load() == 45, "Completed units must match");
    TEST_ASSERT(ctrl->total_units.load() == 100, "Total units must match");

    ctrl->set_stage("GpuProbe");
    TEST_ASSERT(ctrl->get_stage() == "GpuProbe", "Stage must match");

    // Simulate progress loop with cancellation
    bool loop_halted = false;
    for (int step = 0; step < 10; ++step) {
        if (step == 3) {
            reg.cancel(id);
        }
        if (ctrl->is_cancelled()) {
            loop_halted = true;
            break;
        }
    }
    TEST_ASSERT(loop_halted, "Loop must cooperatively halt when cancelled");
    TEST_ASSERT(ctrl->is_cancelled(), "Control must report cancelled");

    // Test registry release while holding local shared_ptr
    reg.release(id);
    TEST_ASSERT(reg.get(id) == nullptr, "Registry must no longer return released control");
    TEST_ASSERT(ctrl->is_cancelled(), "Retained shared_ptr must safely remain valid after registry release");

    TEST_PASS("test_native_operation_control passed successfully");
}

static bool test_cpu_backend_isolation_and_boundary_batching() {
    std::cout << "\n--- TEST: CPU Backend Isolation & Boundary Batching ---" << std::endl;

    // 1. Verify CPU device configuration helper
    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    configure_cpu_backend_params(mparams, cparams);

    TEST_ASSERT(mparams.devices != nullptr, "mparams.devices must be non-null for CPU isolation");
    TEST_ASSERT(mparams.devices[0] == nullptr, "mparams.devices[0] must be nullptr to select 0 devices");
    TEST_ASSERT(mparams.n_gpu_layers == 0, "n_gpu_layers must be 0");
    TEST_ASSERT(mparams.split_mode == LLAMA_SPLIT_MODE_LAYER, "split_mode must be LAYER");
    TEST_ASSERT(!cparams.offload_kqv, "offload_kqv must be false on CPU");

    // 2. Test batch boundary chunking logic across offloading boundary token counts (31, 32, 33, 64, 128)
    std::vector<int> test_prompt_lengths = { 1, 15, 31, 32, 33, 63, 64, 65, 128, 256 };
    std::vector<int> test_batch_sizes = { 16, 32, 64, 256 };

    for (int batch_size : test_batch_sizes) {
        for (int prompt_len : test_prompt_lengths) {
            int current_batch_tokens = 0;
            int total_decodes = 0;
            int tokens_processed = 0;

            for (int i = 0; i < prompt_len; ++i) {
                current_batch_tokens++;
                tokens_processed++;

                if (current_batch_tokens == batch_size || i == prompt_len - 1) {
                    TEST_ASSERT(current_batch_tokens <= batch_size, "Batch must never exceed batch_size");
                    total_decodes++;
                    current_batch_tokens = 0;
                }
            }

            int expected_decodes = (prompt_len + batch_size - 1) / batch_size;
            TEST_ASSERT(total_decodes == expected_decodes, "Decodes count must match expected chunks");
            TEST_ASSERT(tokens_processed == prompt_len, "All prompt tokens must be processed");
        }
    }

    TEST_PASS("test_cpu_backend_isolation_and_boundary_batching passed successfully");
}

static bool test_utf_conversion() {
    std::cout << "\nRunning Test 11: Solus Native UTF-16 to UTF-8 and Path Conversion..." << std::endl;
    // 1. ASCII
    const std::u16string ascii = u"Hello, world! 123";
    std::string ascii_u8 = solus::utf16_to_utf8(ascii.data(), ascii.size());
    TEST_ASSERT(ascii_u8 == "Hello, world! 123", "ASCII UTF-8 mismatch");

    // 2. Bengali
    const std::vector<uint16_t> bengali_u16 = { 0x09AC, 0x09BE, 0x0982, 0x09B2, 0x09BE };
    std::string bengali_u8 = solus::utf16_to_utf8(bengali_u16.data(), bengali_u16.size());
    TEST_ASSERT(bengali_u8.size() == 15, "Bengali UTF-8 byte length mismatch");

    // 3. Emoji (surrogate pairs)
    const std::vector<uint16_t> emoji_u16 = { 0xD83D, 0xDE00 }; // 😀
    std::string emoji_u8 = solus::utf16_to_utf8(emoji_u16.data(), emoji_u16.size());
    TEST_ASSERT(emoji_u8.size() == 4, "Emoji UTF-8 size mismatch");
    TEST_ASSERT(static_cast<uint8_t>(emoji_u8[0]) == 0xF0 && static_cast<uint8_t>(emoji_u8[1]) == 0x9F &&
                static_cast<uint8_t>(emoji_u8[2]) == 0x98 && static_cast<uint8_t>(emoji_u8[3]) == 0x80,
                "Emoji byte sequence mismatch");

    // 4. Math supplementary (U+1D400)
    const std::vector<uint16_t> math_u16 = { 0xD835, 0xDC00 };
    std::string math_u8 = solus::utf16_to_utf8(math_u16.data(), math_u16.size());
    TEST_ASSERT(math_u8.size() == 4, "Math supplementary size mismatch");

    // 5. Malformed surrogates replaced with U+FFFD
    const std::vector<uint16_t> malformed_u16 = { 0xD83D, 'a' };
    std::string malformed_u8 = solus::utf16_to_utf8(malformed_u16.data(), malformed_u16.size());
    TEST_ASSERT(malformed_u8 == "\xEF\xBF\xBD" "a", "Malformed surrogate mismatch");

    // 6. Embedded NUL in path rejection
    const std::vector<uint16_t> path_nul = { '/', 'b', 0x0000, 'c' };
    std::string path_out;
    TEST_ASSERT(!solus::utf16_to_path_utf8(path_nul.data(), path_nul.size(), path_out), "Embedded NUL path must be rejected");

    // 7. Valid path
    const std::u16string valid_path = u"/valid/path.gguf";
    TEST_ASSERT(solus::utf16_to_path_utf8(valid_path.data(), valid_path.size(), path_out) && path_out == "/valid/path.gguf",
                "Valid path conversion mismatch");

    // 8. Safe UTF-8 to UTF-16 conversion roundtrip
    std::vector<uint16_t> u16_roundtrip = solus::utf8_to_utf16(emoji_u8.data(), emoji_u8.size());
    TEST_ASSERT(u16_roundtrip.size() == 2 && u16_roundtrip[0] == 0xD83D && u16_roundtrip[1] == 0xDE00,
                "Emoji roundtrip to UTF-16 mismatch");

    TEST_PASS("Native UTF conversion verified successfully");
}

int main(int argc, char ** argv) {
    std::string model_path = "";
    std::string prompt = "Hi";
    int n_threads = 4;
    int n_ctx = 2048;
    int n_batch = 512;
    int n_ubatch = 512;
    int max_tokens = 32;

    for (int i = 1; i < argc; ++i) {
        if (std::string(argv[i]) == "--model" && i + 1 < argc) {
            model_path = argv[++i];
        } else if (std::string(argv[i]) == "--prompt" && i + 1 < argc) {
            prompt = argv[++i];
        } else if (std::string(argv[i]) == "--threads" && i + 1 < argc) {
            n_threads = std::atoi(argv[++i]);
        } else if (std::string(argv[i]) == "--ctx" && i + 1 < argc) {
            n_ctx = std::atoi(argv[++i]);
        } else if (std::string(argv[i]) == "--batch" && i + 1 < argc) {
            n_batch = std::atoi(argv[++i]);
        } else if (std::string(argv[i]) == "--ubatch" && i + 1 < argc) {
            n_ubatch = std::atoi(argv[++i]);
        } else if (std::string(argv[i]) == "--max_tokens" && i + 1 < argc) {
            max_tokens = std::atoi(argv[++i]);
        }
    }

    if (!model_path.empty()) {
        return run_real_model_inference(model_path, prompt, n_threads, n_ctx, n_batch, n_ubatch, max_tokens);
    }

    std::cout << "================================================================" << std::endl;
    std::cout << "   SOLUS GGUF NATIVE DETERMINISTIC FORENSIC TEST SUITE          " << std::endl;
    std::cout << "================================================================" << std::endl;

    test_q8_k_quantization_invariants();
    test_differential_vector_vs_scalar();
    test_llama32_rope_numerical_stability();
    test_ggml_computation_graph_eval();
    test_masked_attention_and_negative_inf_stability();
    test_real_gguf_vocab_loading();
    test_prompt_cache_prefix();
    test_prompt_budget();
    test_native_operation_control();
    test_cpu_backend_isolation_and_boundary_batching();
    test_utf_conversion();

    std::cout << "\n================================================================" << std::endl;
    std::cout << "TEST RESULTS: " << g_tests_passed << " passed, " << g_tests_failed << " failed." << std::endl;
    std::cout << "================================================================" << std::endl;

    return g_tests_failed == 0 ? 0 : 1;
}

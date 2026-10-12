#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <thread>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <map>
#include <android/log.h>

#include "llama.h"
#include "prompt-cache.h"
#include "prompt-budget.h"
#include "llama-arch.h"
#include "native-operation-control.h"
#include "ggml.h"
#include "gguf.h"
#include "gguf-backend-config.h"
#include "utf-conversion.h"

#ifdef GGML_USE_VULKAN
#include <vulkan/vulkan.h>
#include "ggml-vulkan.h"
#endif

#if defined(__ANDROID__)
#include <android/trace.h>
#define TRACE_BEGIN(name) ATrace_beginSection(name)
#define TRACE_END() ATrace_endSection()
#else
#define TRACE_BEGIN(name) ((void)0)
#define TRACE_END() ((void)0)
#endif

struct ScopedTrace {
    explicit ScopedTrace(const char * name) { TRACE_BEGIN(name); }
    ~ScopedTrace() { TRACE_END(); }
};

enum SolusBackend {
    SOLUS_BACKEND_CPU = 0,
    SOLUS_BACKEND_VULKAN = 1
};

extern "C" {
    void solus_record_numerical_anomaly(const char * reason);
    int solus_has_numerical_anomaly(void);
    void solus_clear_numerical_anomaly(void);
}

enum SolusFailureCategory {
    SOLUS_FAILURE_NONE = 0,
    SOLUS_FAILURE_UNKNOWN = 1,
    SOLUS_FAILURE_PROMPT_EMPTY = 2,
    SOLUS_FAILURE_UNSUPPORTED_TEMPLATE = 3,
    SOLUS_FAILURE_TOKENIZATION_ERROR = 4,
    SOLUS_FAILURE_PROMPT_DECODE_FAILED = 5,
    SOLUS_FAILURE_GENERATION_DECODE_FAILED = 6,
    SOLUS_FAILURE_NUMERICAL_INSTABILITY = 7,
    SOLUS_FAILURE_BACKEND_EXECUTION = 8,
    SOLUS_FAILURE_OUT_OF_MEMORY = 9,
    SOLUS_FAILURE_USER_STOPPED = 10,
    SOLUS_FAILURE_CONTEXT_CAPACITY = 11
};

static std::atomic<int> g_last_failure_category{SOLUS_FAILURE_NONE};
static std::atomic<int> g_last_failure_code{0};

static const char * solus_failure_category_name(int cat) {
    switch (cat) {
        case SOLUS_FAILURE_NONE: return "None";
        case SOLUS_FAILURE_CONTEXT_CAPACITY: return "ContextCapacityExceeded";
        case SOLUS_FAILURE_PROMPT_EMPTY: return "PromptEmpty";
        case SOLUS_FAILURE_UNSUPPORTED_TEMPLATE: return "UnsupportedTemplate";
        case SOLUS_FAILURE_TOKENIZATION_ERROR: return "TokenizationError";
        case SOLUS_FAILURE_PROMPT_DECODE_FAILED: return "PromptDecodeFailed";
        case SOLUS_FAILURE_GENERATION_DECODE_FAILED: return "GenerationDecodeFailed";
        case SOLUS_FAILURE_NUMERICAL_INSTABILITY: return "NumericalInstability";
        case SOLUS_FAILURE_BACKEND_EXECUTION: return "BackendExecution";
        case SOLUS_FAILURE_OUT_OF_MEMORY: return "OutOfMemory";
        case SOLUS_FAILURE_USER_STOPPED: return "UserStopped";
        default: return "Unknown";
    }
}

struct ProbePositionRecord {
    llama_token token;
    std::vector<float> probs;
};

static std::mutex g_probe_mutex;
static std::map<int, std::vector<ProbePositionRecord>> g_probe_reference_runs;

#define LOG_TAG "SolusLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_log_mutex;
static std::string g_last_error_log;
static std::vector<std::string> g_recent_logs;

static void set_last_failure(int category, int code, const std::string & detail = "") {
    g_last_failure_category.store(category, std::memory_order_release);
    g_last_failure_code.store(code, std::memory_order_release);
    if (!detail.empty()) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error_log = detail;
    }
}

static void llama_jni_log_callback(enum ggml_log_level level, const char * text, void * /* user_data */) {
    if (!text) return;

    int android_prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            android_prio = ANDROID_LOG_ERROR;
            break;
        case GGML_LOG_LEVEL_WARN:
            android_prio = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_INFO:
            android_prio = ANDROID_LOG_INFO;
            break;
        case GGML_LOG_LEVEL_DEBUG:
            android_prio = ANDROID_LOG_DEBUG;
            break;
        default:
            android_prio = ANDROID_LOG_INFO;
            break;
    }
    __android_log_print(android_prio, LOG_TAG, "%s", text);

    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string msg(text);
    while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) {
        msg.pop_back();
    }
    if (!msg.empty()) {
        if (level == GGML_LOG_LEVEL_ERROR || msg.find("error") != std::string::npos || msg.find("failed") != std::string::npos) {
            g_last_error_log = msg;
        }
        if (g_recent_logs.size() >= 60) {
            g_recent_logs.erase(g_recent_logs.begin());
        }
        g_recent_logs.push_back(msg);
    }
}

static void ensure_logging_and_backend_initialized() {
    static std::once_flag init_flag;
    std::call_once(init_flag, []() {
        llama_log_set(llama_jni_log_callback, nullptr);
        ggml_log_set(llama_jni_log_callback, nullptr);
        llama_backend_init();
        LOGI("SolusLlamaJNI: Global llama.cpp logging and backend initialized");
    });
}

static std::string escape_json(const std::string & s) {
    std::ostringstream o;
    for (auto c = s.cbegin(); c != s.cend(); c++) {
        switch (*c) {
        case '"': o << "\\\""; break;
        case '\\': o << "\\\\"; break;
        case '\b': o << "\\b"; break;
        case '\f': o << "\\f"; break;
        case '\n': o << "\\n"; break;
        case '\r': o << "\\r"; break;
        case '\t': o << "\\t"; break;
        default:
            if ('\x00' <= *c && *c <= '\x1f') {
                o << "\\u"
                  << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(*c);
            } else {
                o << *c;
            }
        }
    }
    return o.str();
}

struct LlamaContextHolder {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string model_path;
    int n_threads = 4;
    int n_threads_batch = 4;
    int n_ctx = 2048;
    int n_batch = 256;
    int n_ubatch = 128;
    std::vector<llama_token> cached_tokens;

    std::atomic<uint64_t> active_generation_id{0};
    std::atomic<uint64_t> cancelled_generation_id{0};
    std::atomic<bool> stop_requested{false};
    std::atomic<bool> is_generating{false};
    std::atomic<bool> is_closed{false};
    std::atomic<int> active_leases{0};

    std::mutex state_mutex;
    std::condition_variable gen_cv;

    int requested_backend = SOLUS_BACKEND_CPU;
    int active_backend = SOLUS_BACKEND_CPU;
    int n_gpu_layers = 0;
    std::shared_ptr<NativeOperationControl> op_control = nullptr;
    std::vector<ggml_backend_dev_t> gpu_devices_storage;
    GgufBackendInspection backend_inspection;
};

static void clear_prompt_cache(LlamaContextHolder * holder) {
    holder->cached_tokens.clear();
    llama_kv_cache_clear(holder->ctx);
}

// GGML abort callback - executed cooperatively inside llama_decode() operations
static bool llama_jni_abort_callback(void * data) {
    if (!data) return false;
    auto * holder = static_cast<LlamaContextHolder *>(data);
    if (holder->op_control && holder->op_control->is_cancelled()) {
        return true;
    }
    return holder->stop_requested.load(std::memory_order_relaxed) ||
           holder->is_closed.load(std::memory_order_relaxed);
}

/**
 * Audits the last-decode logit buffer for NaN/Inf values.
 *
 * Called AFTER llama_decode() returns successfully. Reads the float logit slice
 * for the single last-token position and scans for non-finite values.
 *
 * NOTE: The scan iterates through up to 256k vocabulary entries per step.
 * Its elapsed time is measured below. It is not zero overhead, but is essential
 * to catch numerical collapse early and prevent NaN propagation.
 *
 * @param ctx        llama context (used to get logit pointer and vocab size)
 * @param phase      "PROMPT_DECODE" or "TOKEN_GENERATION"
 * @param token_pos  current token position in the sequence
 */
static bool audit_logits_numerics(llama_context * ctx, const char * phase, int token_pos) {
    if (!ctx) return true;

    // Retrieve the logit float buffer for the last token in the previous decode call.
    const float * logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return true;

    // Get vocabulary size to know how many floats to scan
    const llama_model * mdl = llama_get_model(ctx);
    if (!mdl) return true;

    int n_vocab = 0;
    {
        const llama_vocab * v = llama_model_get_vocab(mdl);
        if (v) n_vocab = llama_vocab_n_tokens(v);
    }
    if (n_vocab <= 0 || n_vocab > 256 * 1024) return true;

    // Measure scan duration to accurately quantify sampling overhead
    auto scan_start = std::chrono::steady_clock::now();
    bool any_bad = false;
    for (int i = 0; i < n_vocab; ++i) {
        float v = logits[i];
        if (v != v || v > 3.4028235e+38f || v < -3.4028235e+38f) { // NaN or ±Inf
            any_bad = true;
            break;
        }
    }
    auto scan_duration_us = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - scan_start).count();
    (void)scan_duration_us;
    if (!any_bad) return true; // Vocabulary scan passed (measured duration ~scan_duration_us us)

    // Bad values detected. Run full scan for diagnostics.
    int nan_count = 0;
    int inf_pos_count = 0;
    int inf_neg_count = 0;
    int first_bad_idx = -1;
    float max_abs = 0.0f;
    float min_val = 0.0f;
    float max_val = 0.0f;

    for (int i = 0; i < n_vocab; ++i) {
        float v = logits[i];
        if (v != v) { // NaN
            nan_count++;
            if (first_bad_idx < 0) first_bad_idx = i;
        } else if (v > 3.4028235e+38f) { // +Inf
            inf_pos_count++;
            if (first_bad_idx < 0) first_bad_idx = i;
        } else if (v < -3.4028235e+38f) { // -Inf
            inf_neg_count++;
            if (first_bad_idx < 0) first_bad_idx = i;
        } else {
            float av = v < 0.0f ? -v : v;
            if (av > max_abs) max_abs = av;
            if (v > max_val) max_val = v;
            if (v < min_val) min_val = v;
        }
    }

    char report_buf[1024];
    snprintf(report_buf, sizeof(report_buf),
         "INVALID LOGIT VALUES: phase=%s token_pos=%d n_vocab=%d "
         "nan=%d +inf=%d -inf=%d first_bad_idx=%d "
         "max_abs_finite=%.4g min_finite=%.4g max_finite=%.4g",
         phase, token_pos, n_vocab,
         nan_count, inf_pos_count, inf_neg_count, first_bad_idx,
         (double)max_abs, (double)min_val, (double)max_val);

    LOGE("SolusLlamaJNI: [NUMERICAL_AUDIT] INVALID LOGIT VALUES DETECTED!"
         " phase=%s token_pos=%d n_vocab=%d"
         " nan=%d +inf=%d -inf=%d first_bad_idx=%d"
         " max_abs_finite=%.4g min_finite=%.4g max_finite=%.4g",
         phase, token_pos, n_vocab,
         nan_count, inf_pos_count, inf_neg_count, first_bad_idx,
         (double)max_abs, (double)min_val, (double)max_val);

    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_error_log = std::string("[NUMERICAL_AUDIT] ") + report_buf;
    return false;
}

static bool compute_softmax(const float * logits, int n_vocab, std::vector<float> & out_probs) {
    if (!logits || n_vocab <= 0) return false;
    out_probs.resize(n_vocab);
    float max_l = -1e30f;
    for (int i = 0; i < n_vocab; ++i) {
        float v = logits[i];
        if (!std::isfinite(v)) return false;
        if (v > max_l) max_l = v;
    }
    double sum = 0.0;
    for (int i = 0; i < n_vocab; ++i) {
        float exp_v = expf(logits[i] - max_l);
        out_probs[i] = exp_v;
        sum += exp_v;
    }
    if (sum <= 0.0 || !std::isfinite(sum)) return false;
    float inv_sum = static_cast<float>(1.0 / sum);
    for (int i = 0; i < n_vocab; ++i) {
        out_probs[i] *= inv_sum;
    }
    return true;
}

static float compute_tv_distance(const std::vector<float> & p, const std::vector<float> & q) {
    if (p.size() != q.size() || p.empty()) return 1.0f;
    double sum = 0.0;
    for (size_t i = 0; i < p.size(); ++i) {
        sum += std::fabs(p[i] - q[i]);
    }
    return static_cast<float>(0.5 * sum);
}

static void batch_add(llama_batch & batch, llama_token id, llama_pos pos, const std::vector<llama_seq_id> & seq_ids, bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = seq_ids.size();
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits  [batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

// Safely converts UTF-8 bytes to a Java jstring using UTF-16 code units (env->NewString).
// Standard env->NewStringUTF expects Modified UTF-8 (CESU-8) and crashes with SIGABRT
// on Android ART whenever 4-byte UTF-8 sequences (emojis, math symbols) or incomplete/invalid
// bytes are passed. env->NewString accepts standard UTF-16 code units directly and is immune to ART aborts.
static jstring safe_new_string(JNIEnv *env, const char *utf8, size_t len) {
    if (!env || !utf8) return nullptr;
    if (len == 0) {
        return env->NewString(reinterpret_cast<const jchar *>(u""), 0);
    }

    std::vector<uint16_t> utf16 = solus::utf8_to_utf16(utf8, len);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

static jstring safe_new_string(JNIEnv *env, const char *utf8) {
    if (!utf8) return nullptr;
    return safe_new_string(env, utf8, strlen(utf8));
}

static jstring safe_new_string(JNIEnv *env, const std::string & str) {
    return safe_new_string(env, str.data(), str.size());
}

// Safely converts a Java jstring (UTF-16) to standard UTF-8 std::string.
static std::string jstring_to_utf8(JNIEnv *env, jstring jstr) {
    if (!env || !jstr) return "";
    jsize len = env->GetStringLength(jstr);
    if (len == 0) return "";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return "";
    std::string result = solus::utf16_to_utf8(reinterpret_cast<const uint16_t *>(chars), static_cast<size_t>(len));
    env->ReleaseStringChars(jstr, chars);
    return result;
}

// Safely converts a Java jstring to standard UTF-8 filesystem path.
// Rejects paths with embedded NUL characters (returns false).
static bool jstring_to_path_utf8(JNIEnv *env, jstring jstr, std::string &out_path) {
    if (!env || !jstr) return false;
    jsize len = env->GetStringLength(jstr);
    if (len == 0) {
        out_path.clear();
        return true;
    }
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return false;
    bool ok = solus::utf16_to_path_utf8(reinterpret_cast<const uint16_t *>(chars), static_cast<size_t>(len), out_path);
    env->ReleaseStringChars(jstr, chars);
    return ok;
}

// Exception-safe JNI method callers
static bool call_jni_void_method_checked(JNIEnv *env, jobject obj, jmethodID method) {
    if (!obj || !method) return false;
    env->CallVoidMethod(obj, method);
    if (env->ExceptionCheck()) {
        LOGE("SolusLlamaJNI: Exception occurred in JNI void callback");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return true;
}

static bool call_jni_string_method_checked(JNIEnv *env, jobject obj, jmethodID method, const char *str) {
    if (!obj || !method || !str) return false;
    jstring jstr = safe_new_string(env, str);
    if (!jstr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return false;
    }
    env->CallVoidMethod(obj, method, jstr);
    env->DeleteLocalRef(jstr);
    if (env->ExceptionCheck()) {
        LOGE("SolusLlamaJNI: Exception occurred in JNI string callback");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return true;
}

static bool format_and_tokenize_prompt(
        LlamaContextHolder * holder,
        JNIEnv * env,
        jstring prompt_str,
        jobjectArray roles_array,
        jobjectArray contents_array,
        std::vector<llama_token> & out_tokens,
        std::string & out_error,
        int & out_failure_category,
        int requested_output_tokens = 1024,
        size_t history_skip = 0,
        size_t document_limit = SIZE_MAX) {
    if (!holder || !holder->model || !holder->vocab || !holder->ctx) {
        out_error = "Model context is invalid";
        out_failure_category = SOLUS_FAILURE_UNKNOWN;
        return false;
    }

    std::vector<std::string> roles_storage;
    std::vector<std::string> contents_storage;
    std::vector<llama_chat_message> chat_msgs;
    bool had_structured_messages = false;

    if (roles_array != nullptr && contents_array != nullptr) {
        jsize msg_count = env->GetArrayLength(roles_array);
        jsize content_count = env->GetArrayLength(contents_array);

        if (msg_count > 0 && msg_count == content_count) {
            had_structured_messages = true;
            roles_storage.reserve(msg_count);
            contents_storage.reserve(msg_count);
            chat_msgs.reserve(msg_count);

            for (jsize i = 0; i < msg_count; ++i) {
                auto role_obj = (jstring)env->GetObjectArrayElement(roles_array, i);
                auto content_obj = (jstring)env->GetObjectArrayElement(contents_array, i);

                std::string r_val = "user";
                std::string c_val = "";

                if (role_obj != nullptr) {
                    r_val = jstring_to_utf8(env, role_obj);
                    if (r_val.empty()) r_val = "user";
                    env->DeleteLocalRef(role_obj);
                }

                if (content_obj != nullptr) {
                    c_val = jstring_to_utf8(env, content_obj);
                    env->DeleteLocalRef(content_obj);
                }

                roles_storage.push_back(r_val);
                contents_storage.push_back(c_val);
                chat_msgs.push_back({ roles_storage.back().c_str(), contents_storage.back().c_str() });
            }
        }
    }

    // If chat_msgs is empty, check if prompt_str is provided
    if (chat_msgs.empty() && prompt_str != nullptr) {
        std::string raw_p = jstring_to_utf8(env, prompt_str);
        if (!raw_p.empty()) {
            roles_storage.push_back("user");
            contents_storage.push_back(raw_p);
            chat_msgs.push_back({ roles_storage.back().c_str(), contents_storage.back().c_str() });
        }
    }

    if (history_skip > 0 && !roles_storage.empty()) {
        size_t last_user = roles_storage.size();
        for (size_t i = 0; i < roles_storage.size(); ++i) if (roles_storage[i] == "user") last_user = i;
        size_t removed = 0;
        for (size_t i = 0; i < last_user && removed < history_skip;) {
            if (roles_storage[i] == "system") { ++i; continue; }
            roles_storage.erase(roles_storage.begin() + i);
            contents_storage.erase(contents_storage.begin() + i);
            --last_user;
            ++removed;
        }
    }
    if (!contents_storage.empty() && document_limit != SIZE_MAX) {
        limit_attached_document(contents_storage.back(), document_limit);
    }
    chat_msgs.clear();
    for (size_t i = 0; i < roles_storage.size(); ++i) {
        chat_msgs.push_back({roles_storage[i].c_str(), contents_storage[i].c_str()});
    }
    const llama_vocab * vocab = holder->vocab;
    const char * model_tmpl = llama_model_chat_template(holder->model, nullptr);

    // ── Llama 3 / 3.1 / 3.2 Instruct detection ──
    bool is_llama3 = false;
    if (model_tmpl) {
        std::string tmpl_str(model_tmpl);
        is_llama3 = (tmpl_str.find("<|start_header_id|>") != std::string::npos &&
                     tmpl_str.find("<|end_header_id|>")   != std::string::npos) ||
                    (tmpl_str == "llama3");
    }
    if (!is_llama3 && vocab) {
        llama_token header_tok = LLAMA_TOKEN_NULL;
        int n_tok = llama_tokenize(vocab, "<|start_header_id|>", 19, &header_tok, 1, false, true);
        if (n_tok == 1 && header_tok != LLAMA_TOKEN_NULL && llama_vocab_is_control(vocab, header_tok)) {
            is_llama3 = true;
        }
    }
    if (is_llama3) {
        LOGI("SolusLlamaJNI: Detected Llama 3 / 3.1 / 3.2 Instruct model architecture");
        if (!model_tmpl || strlen(model_tmpl) == 0) {
            model_tmpl = "llama3";
            LOGI("SolusLlamaJNI: Defaulted chat template to 'llama3'");
        }
    }

    std::string formatted_prompt;
    bool applied_chat_template = false;

    if (!chat_msgs.empty()) {
        if (!model_tmpl && !is_llama3) {
            if (had_structured_messages) {
                out_error = "Model does not contain a native chat template for structured messages";
                out_failure_category = SOLUS_FAILURE_UNSUPPORTED_TEMPLATE;
                LOGE("SolusLlamaJNI: %s", out_error.c_str());
                return false;
            }
        } else {
            if (is_llama3) {
                bool has_system = false;
                for (const auto & msg : chat_msgs) {
                    if (msg.role && std::string(msg.role) == "system") {
                        has_system = true;
                        break;
                    }
                }
                if (!has_system) {
                    roles_storage.insert(roles_storage.begin(), "system");
                    contents_storage.insert(contents_storage.begin(), "You are a helpful AI assistant.");
                    chat_msgs.clear();
                    chat_msgs.reserve(roles_storage.size());
                    for (size_t i = 0; i < roles_storage.size(); ++i) {
                        chat_msgs.push_back({ roles_storage[i].c_str(), contents_storage[i].c_str() });
                    }
                    LOGI("SolusLlamaJNI: Injected default system prompt for Llama 3 model");
                }
            }

            std::vector<char> tmpl_buf(4096);
            int32_t tmpl_res = llama_chat_apply_template(
                    model_tmpl,
                    chat_msgs.data(),
                    chat_msgs.size(),
                    true,
                    tmpl_buf.data(),
                    tmpl_buf.size()
            );

            if (tmpl_res > (int32_t)tmpl_buf.size()) {
                tmpl_buf.resize(tmpl_res + 1);
                tmpl_res = llama_chat_apply_template(
                        model_tmpl,
                        chat_msgs.data(),
                        chat_msgs.size(),
                        true,
                        tmpl_buf.data(),
                        tmpl_buf.size()
                );
            }

            if (tmpl_res < 0 && !roles_storage.empty() && roles_storage[0] == "system") {
                // Try merging system prompt into first user turn
                std::vector<llama_chat_message> test_msgs;
                std::vector<std::string> test_roles(roles_storage.begin() + 1, roles_storage.end());
                std::vector<std::string> test_contents(contents_storage.begin() + 1, contents_storage.end());
                test_msgs.reserve(test_roles.size());
                for (size_t i = 0; i < test_roles.size(); ++i) {
                    test_msgs.push_back({ test_roles[i].c_str(), test_contents[i].c_str() });
                }

                bool system_role_is_cause = false;
                if (!test_msgs.empty()) {
                    std::vector<char> test_buf(1024);
                    int32_t test_res = llama_chat_apply_template(
                            model_tmpl,
                            test_msgs.data(),
                            test_msgs.size(),
                            false,
                            test_buf.data(),
                            test_buf.size()
                    );
                    system_role_is_cause = (test_res > 0);
                }

                if (system_role_is_cause) {
                    LOGW("SolusLlamaJNI: Model template rejects system role; merging into user turn");
                    std::string sys_text = contents_storage[0];
                    roles_storage.erase(roles_storage.begin());
                    contents_storage.erase(contents_storage.begin());
                    if (!contents_storage.empty() && roles_storage[0] == "user") {
                        contents_storage[0] = sys_text + "\n\n" + contents_storage[0];
                    } else {
                        roles_storage.insert(roles_storage.begin(), "user");
                        contents_storage.insert(contents_storage.begin(), sys_text);
                    }
                    chat_msgs.resize(roles_storage.size());
                    for (size_t i = 0; i < roles_storage.size(); ++i) {
                        chat_msgs[i] = { roles_storage[i].c_str(), contents_storage[i].c_str() };
                    }
                    tmpl_res = llama_chat_apply_template(
                            model_tmpl,
                            chat_msgs.data(),
                            chat_msgs.size(),
                            true,
                            tmpl_buf.data(),
                            tmpl_buf.size()
                    );
                    if (tmpl_res > (int32_t)tmpl_buf.size()) {
                        tmpl_buf.resize(tmpl_res + 1);
                        tmpl_res = llama_chat_apply_template(
                                model_tmpl,
                                chat_msgs.data(),
                                chat_msgs.size(),
                                true,
                                tmpl_buf.data(),
                                tmpl_buf.size()
                        );
                    }
                }
            }

            if (tmpl_res < 0) {
                out_error = "Model chat template failed or is unsupported (result code: " + std::to_string(tmpl_res) + ")";
                out_failure_category = SOLUS_FAILURE_UNSUPPORTED_TEMPLATE;
                LOGE("SolusLlamaJNI: %s", out_error.c_str());
                return false;
            }

            if (tmpl_res > 0) {
                formatted_prompt = std::string(tmpl_buf.data(), tmpl_res);
                applied_chat_template = true;
                LOGI("SolusLlamaJNI: Applied model chat template (%d bytes)", tmpl_res);
            }
        }
    }

    if (!applied_chat_template) {
        if (had_structured_messages) {
            out_error = "Structured messages provided but chat template could not be applied";
            out_failure_category = SOLUS_FAILURE_UNSUPPORTED_TEMPLATE;
            LOGE("SolusLlamaJNI: %s", out_error.c_str());
            return false;
        }
        if (prompt_str != nullptr) {
            formatted_prompt = jstring_to_utf8(env, prompt_str);
        }
    }

    if (formatted_prompt.empty()) {
        out_error = "Prompt is empty";
        out_failure_category = SOLUS_FAILURE_PROMPT_EMPTY;
        LOGE("SolusLlamaJNI: %s", out_error.c_str());
        return false;
    }

    const bool add_special = false;
    const bool parse_special = true;
    int n_tokens_req = -llama_tokenize(vocab, formatted_prompt.c_str(), formatted_prompt.length(), nullptr, 0, add_special, parse_special);
    if (n_tokens_req <= 0) {
        n_tokens_req = static_cast<int>(formatted_prompt.length() + 8);
    }

    out_tokens.resize(n_tokens_req);
    int n_tokens = llama_tokenize(
            vocab,
            formatted_prompt.c_str(),
            formatted_prompt.length(),
            out_tokens.data(),
            out_tokens.size(),
            add_special,
            parse_special
    );

    if (n_tokens < 0) {
        out_tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
                vocab,
                formatted_prompt.c_str(),
                formatted_prompt.length(),
                out_tokens.data(),
                out_tokens.size(),
                add_special,
                parse_special
        );
    }

    if (n_tokens <= 0) {
        out_error = "Failed to tokenize prompt";
        out_failure_category = SOLUS_FAILURE_TOKENIZATION_ERROR;
        LOGE("SolusLlamaJNI: %s", out_error.c_str());
        return false;
    }

    out_tokens.resize(n_tokens);

    uint32_t ctx_size = llama_n_ctx(holder->ctx);
    const uint32_t reserve = output_token_reserve(ctx_size, requested_output_tokens);
    const uint32_t max_prompt_tokens = ctx_size > reserve + 1 ? ctx_size - reserve - 1 : 1;

    // Preserve exactly one required BOS token at position 0
    llama_token bos = llama_vocab_bos(vocab);
    bool should_add_bos = llama_vocab_get_add_bos(vocab) || is_llama3;
    if (should_add_bos && bos != LLAMA_TOKEN_NULL) {
        if (out_tokens.empty() || out_tokens[0] != bos) {
            out_tokens.insert(out_tokens.begin(), bos);
            LOGI("SolusLlamaJNI: Prepended model BOS token (%d)", bos);
        }
    }

    // Deduplicate any consecutive leading BOS tokens
    while (out_tokens.size() >= 2 && out_tokens[0] == bos && out_tokens[1] == bos && bos != LLAMA_TOKEN_NULL) {
        LOGW("SolusLlamaJNI: Detected duplicate leading BOS token (%d), removing duplicate", bos);
        out_tokens.erase(out_tokens.begin());
    }

    if (out_tokens.size() > max_prompt_tokens) {
        const size_t drop = oldest_history_turn_size(roles_storage);
        if (drop > 0 && had_structured_messages) {
            return format_and_tokenize_prompt(holder, env, prompt_str, roles_array, contents_array,
                out_tokens, out_error, out_failure_category, requested_output_tokens, history_skip + drop, document_limit);
        }
        if (!contents_storage.empty() && document_limit > 64) {
            size_t next_limit = document_limit == SIZE_MAX ? contents_storage.back().size() / 2 : document_limit / 2;
            std::string limited = contents_storage.back();
            while (next_limit > 64 && !limit_attached_document(limited, next_limit)) next_limit /= 2;
            if (limited != contents_storage.back() || limit_attached_document(limited, next_limit)) {
                return format_and_tokenize_prompt(holder, env, prompt_str, roles_array, contents_array,
                    out_tokens, out_error, out_failure_category, requested_output_tokens, history_skip, next_limit);
            }
        }
        out_error = "The current message and system instructions exceed the model context capacity";
        out_failure_category = SOLUS_FAILURE_CONTEXT_CAPACITY;
        return false;
    }
    return true;
}

// RAII Scope Guard for native generation lease and resources
struct NativeLeaseGuard {
    LlamaContextHolder * holder = nullptr;
    llama_sampler * smpl = nullptr;
    llama_batch batch{};
    bool batch_initialized = false;

    explicit NativeLeaseGuard(LlamaContextHolder * h) : holder(h) {}

    ~NativeLeaseGuard() {
        if (batch_initialized) {
            llama_batch_free(batch);
            batch_initialized = false;
        }
        if (smpl) {
            llama_sampler_free(smpl);
            smpl = nullptr;
        }
        if (holder) {
            holder->is_generating.store(false, std::memory_order_release);
            holder->active_leases.fetch_sub(1, std::memory_order_acq_rel);
            std::lock_guard<std::mutex> lock(holder->state_mutex);
            holder->gen_cv.notify_all();
        }
    }
};

// Extract complete UTF-8 sequences from buffer
static std::string extract_complete_utf8(std::string & buffer) {
    size_t valid_end = 0;
    size_t i = 0;
    const size_t len = buffer.size();

    while (i < len) {
        unsigned char c = static_cast<unsigned char>(buffer[i]);
        size_t char_len = 0;

        if (c < 0x80) {
            char_len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            char_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            char_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            char_len = 4;
        } else {
            // Invalid byte: skip it
            i++;
            valid_end = i;
            continue;
        }

        if (i + char_len <= len) {
            bool valid_continuation = true;
            for (size_t j = 1; j < char_len; ++j) {
                unsigned char cont = static_cast<unsigned char>(buffer[i + j]);
                if ((cont & 0xC0) != 0x80) {
                    valid_continuation = false;
                    break;
                }
            }
            if (valid_continuation) {
                i += char_len;
                valid_end = i;
            } else {
                i++;
                valid_end = i;
            }
        } else {
            // Incomplete UTF-8 sequence at end of buffer
            break;
        }
    }

    if (valid_end == 0) {
        return "";
    }

    std::string result = buffer.substr(0, valid_end);
    buffer.erase(0, valid_end);
    return result;
}

static bool is_vulkan_supported_internal() {
#ifdef GGML_USE_VULKAN
    ensure_logging_and_backend_initialized();
    try {
        int count = ggml_backend_vk_get_device_count();
        return count > 0;
    } catch (...) {
        return false;
    }
#else
    return false;
#endif
}

static std::string get_vulkan_driver_identity() {
#ifdef GGML_USE_VULKAN
    static const std::string identity = []() -> std::string {
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo creation{};
        creation.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        creation.pApplicationInfo = &app;
        VkInstance instance = VK_NULL_HANDLE;
        if (vkCreateInstance(&creation, nullptr, &instance) != VK_SUCCESS) return "";
        uint32_t count = 0;
        std::ostringstream result;
        if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count > 0) {
            std::vector<VkPhysicalDevice> devices(count);
            if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
                for (uint32_t i = 0; i < count; ++i) {
                    VkPhysicalDeviceProperties properties{};
                    vkGetPhysicalDeviceProperties(devices[i], &properties);
                    result << properties.vendorID << ':' << properties.deviceID << ':'
                           << properties.driverVersion << ':' << properties.apiVersion << ';';
                }
            }
        }
        vkDestroyInstance(instance, nullptr);
        return result.str();
    }();
    return identity;
#else
    return "";
#endif
}

static std::string get_vulkan_device_info_json() {
#ifdef GGML_USE_VULKAN
    ensure_logging_and_backend_initialized();
    int count = 0;
    try {
        count = ggml_backend_vk_get_device_count();
    } catch (...) {
        count = 0;
    }
    std::ostringstream ss;
    ss << "{";
    ss << "\"available\":" << (count > 0 ? "true" : "false") << ",";
    ss << "\"device_count\":" << count << ",";
    ss << "\"driver_identity\":\"" << escape_json(get_vulkan_driver_identity()) << "\",";
    ss << "\"devices\":[";
    for (int i = 0; i < count; ++i) {
        if (i > 0) ss << ",";
        char desc[256] = {0};
        ggml_backend_vk_get_device_description(i, desc, sizeof(desc));
        size_t free_mem = 0, total_mem = 0;
        ggml_backend_vk_get_device_memory(i, &free_mem, &total_mem);
        ss << "{";
        ss << "\"index\":" << i << ",";
        ss << "\"name\":\"" << escape_json(desc) << "\",";
        ss << "\"free_memory_mb\":" << (free_mem / (1024 * 1024)) << ",";
        ss << "\"total_memory_mb\":" << (total_mem / (1024 * 1024));
        ss << "}";
    }
    ss << "]}";
    return ss.str();
#else
    return "{\"available\":false,\"device_count\":0,\"devices\":[]}";
#endif
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeIsVulkanAvailable(
        JNIEnv * /* env */,
        jclass /* clazz */) {
    return is_vulkan_supported_internal() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetVulkanDeviceInfo(
        JNIEnv *env,
        jclass /* clazz */) {
    std::string json = get_vulkan_device_info_json();
    return safe_new_string(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetActiveBackend(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return safe_new_string(env, "NONE");
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (holder->active_backend == SOLUS_BACKEND_VULKAN) {
        return safe_new_string(env, "Vulkan");
    }
    return safe_new_string(env, "CPU");
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetLastError(
        JNIEnv *env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_last_error_log.empty()) {
        return nullptr;
    }
    return safe_new_string(env, g_last_error_log);
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInspectModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring model_path_str) {
    if (!model_path_str) return nullptr;
    std::string model_path;
    if (!jstring_to_path_utf8(env, model_path_str, model_path) || model_path.empty()) {
        std::ostringstream json;
        json << "{\"valid\":false,\"error\":\"Invalid model path or embedded NUL\",\"is_supported\":false}";
        return safe_new_string(env, json.str());
    }

    ensure_logging_and_backend_initialized();

    struct gguf_init_params params = {
        /*.no_alloc =*/ true,
        /*.ctx      =*/ nullptr,
    };

    struct gguf_context * ctx = gguf_init_from_file(model_path.c_str(), params);
    if (!ctx) {
        std::string err = "Failed to parse GGUF header/file: ";
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            if (!g_last_error_log.empty()) {
                err += g_last_error_log;
            } else {
                err += "invalid GGUF magic or file unreadable";
            }
        }
        std::ostringstream json;
        json << "{\"valid\":false,\"error\":\"" << escape_json(err) << "\",\"is_supported\":false}";
        return safe_new_string(env, json.str());
    }

    uint32_t version = gguf_get_version(ctx);
    int64_t n_tensors = gguf_get_n_tensors(ctx);

    std::string arch = "";
    int64_t arch_key = gguf_find_key(ctx, "general.architecture");
    if (arch_key >= 0 && gguf_get_kv_type(ctx, arch_key) == GGUF_TYPE_STRING) {
        arch = gguf_get_val_str(ctx, arch_key);
    }

    std::string name = "";
    int64_t name_key = gguf_find_key(ctx, "general.name");
    if (name_key >= 0 && gguf_get_kv_type(ctx, name_key) == GGUF_TYPE_STRING) {
        name = gguf_get_val_str(ctx, name_key);
    }

    std::string desc = "";
    int64_t desc_key = gguf_find_key(ctx, "general.description");
    if (desc_key >= 0 && gguf_get_kv_type(ctx, desc_key) == GGUF_TYPE_STRING) {
        desc = gguf_get_val_str(ctx, desc_key);
    }

    int64_t n_ctx_train = 0;
    std::string ctx_key_str = arch + ".context_length";
    int64_t ctx_key = gguf_find_key(ctx, ctx_key_str.c_str());
    if (ctx_key >= 0) {
        enum gguf_type kt = gguf_get_kv_type(ctx, ctx_key);
        if (kt == GGUF_TYPE_UINT32) n_ctx_train = gguf_get_val_u32(ctx, ctx_key);
        else if (kt == GGUF_TYPE_INT32) n_ctx_train = gguf_get_val_i32(ctx, ctx_key);
        else if (kt == GGUF_TYPE_UINT64) n_ctx_train = gguf_get_val_u64(ctx, ctx_key);
        else if (kt == GGUF_TYPE_INT64) n_ctx_train = gguf_get_val_i64(ctx, ctx_key);
    }

    int64_t n_embd = 0;
    std::string embd_key_str = arch + ".embedding_length";
    int64_t embd_key = gguf_find_key(ctx, embd_key_str.c_str());
    if (embd_key >= 0) {
        enum gguf_type kt = gguf_get_kv_type(ctx, embd_key);
        if (kt == GGUF_TYPE_UINT32) n_embd = gguf_get_val_u32(ctx, embd_key);
        else if (kt == GGUF_TYPE_INT32) n_embd = gguf_get_val_i32(ctx, embd_key);
    }

    int64_t n_layer = 0;
    std::string layer_key_str = arch + ".block_count";
    int64_t layer_key = gguf_find_key(ctx, layer_key_str.c_str());
    if (layer_key >= 0) {
        enum gguf_type kt = gguf_get_kv_type(ctx, layer_key);
        if (kt == GGUF_TYPE_UINT32) n_layer = gguf_get_val_u32(ctx, layer_key);
        else if (kt == GGUF_TYPE_INT32) n_layer = gguf_get_val_i32(ctx, layer_key);
    }

    bool has_chat_template = (gguf_find_key(ctx, "tokenizer.chat_template") >= 0);

    std::map<std::string, int> quant_counts;
    bool has_unsupported_tensor_type = false;
    std::string unsupported_type_name = "";

    for (int64_t i = 0; i < n_tensors; ++i) {
        enum ggml_type tt = gguf_get_tensor_type(ctx, i);
        if (tt >= GGML_TYPE_COUNT || tt < 0) {
            has_unsupported_tensor_type = true;
            unsupported_type_name = "type_" + std::to_string(static_cast<int>(tt));
        } else {
            const char * tname = ggml_type_name(tt);
            if (!tname) {
                has_unsupported_tensor_type = true;
                unsupported_type_name = "unknown_type_" + std::to_string(static_cast<int>(tt));
            } else {
                quant_counts[tname]++;
            }
        }
    }

    std::string primary_quant = "unknown";
    int max_q_count = 0;
    for (const auto & pair : quant_counts) {
        if (pair.first != "F32" && pair.first != "F16" && pair.second > max_q_count) {
            max_q_count = pair.second;
            primary_quant = pair.first;
        }
    }
    if (primary_quant == "unknown" && !quant_counts.empty()) {
        primary_quant = quant_counts.begin()->first;
    }

    llm_arch arch_id = arch.empty() ? LLM_ARCH_UNKNOWN : llm_arch_from_string(arch);
    bool arch_supported = (arch_id != LLM_ARCH_UNKNOWN);

    bool is_supported = arch_supported && !has_unsupported_tensor_type;
    std::string error_msg = "";
    if (arch.empty()) {
        error_msg = "Model missing general.architecture metadata";
    } else if (!arch_supported) {
        error_msg = "Architecture '" + arch + "' is not supported by llama.cpp runtime";
    } else if (has_unsupported_tensor_type) {
        error_msg = "Model uses unsupported tensor quantization type: " + unsupported_type_name;
    }

    gguf_free(ctx);

    std::ostringstream json;
    json << "{"
         << "\"valid\":true,"
         << "\"version\":" << version << ","
         << "\"architecture\":\"" << escape_json(arch) << "\","
         << "\"name\":\"" << escape_json(name) << "\","
         << "\"desc\":\"" << escape_json(desc) << "\","
         << "\"n_tensors\":" << n_tensors << ","
         << "\"n_ctx_train\":" << n_ctx_train << ","
         << "\"n_embd\":" << n_embd << ","
         << "\"n_layer\":" << n_layer << ","
         << "\"has_chat_template\":" << (has_chat_template ? "true" : "false") << ","
         << "\"primary_quant\":\"" << escape_json(primary_quant) << "\","
         << "\"is_supported\":" << (is_supported ? "true" : "false") << ","
         << "\"error\":\"" << escape_json(error_msg) << "\""
         << "}";

    return safe_new_string(env, json.str());
}

JNIEXPORT jlong JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInitModel(
        JNIEnv *env,
        jclass /*clazz*/,
        jstring model_path_str,
        jint n_threads,
        jint n_threads_batch,
        jint n_ctx,
        jint n_batch,
        jint n_ubatch,
        jint backend,
        jint n_gpu_layers,
        jlong operation_control_id) {

    if (model_path_str == nullptr) {
        LOGE("nativeInitModel called with null model path");
        return 0;
    }

    std::shared_ptr<NativeOperationControl> op_control =
        NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(operation_control_id));

    if (op_control && op_control->is_cancelled()) {
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled before start");
        return 0;
    }

    ensure_logging_and_backend_initialized();

    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error_log.clear();
    }

    std::string model_path;
    if (!jstring_to_path_utf8(env, model_path_str, model_path) || model_path.empty()) {
        LOGE("nativeInitModel called with invalid model path or embedded NUL");
        set_last_failure(SOLUS_FAILURE_UNKNOWN, -1, "Invalid model path or embedded NUL");
        return 0;
    }

    int requested_backend = backend;
    int active_backend = SOLUS_BACKEND_CPU;
    int effective_gpu_layers = 0;

    LOGI("SolusLlamaJNI: Initializing GGUF model: %s (threads: %d, prefill_threads: %d, ctx: %d, batch: %d/%d, backend: %d, gpu_layers: %d, op_ctrl: %lld)",
         model_path.c_str(), n_threads, n_threads_batch, n_ctx, n_batch, n_ubatch, backend, n_gpu_layers, (long long)operation_control_id);

    auto *holder = new LlamaContextHolder();
    holder->requested_backend = requested_backend;
    holder->op_control = op_control;

    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    mparams.use_mmap = true;

    if (op_control) {
        op_control->set_stage(requested_backend == SOLUS_BACKEND_VULKAN ? "GpuLoading" : "CpuLoading");
        mparams.progress_callback_user_data = op_control.get();
        mparams.progress_callback = [](float progress, void * user_data) -> bool {
            if (!user_data) return true;
            auto * op = static_cast<NativeOperationControl *>(user_data);
            if (op->is_cancelled()) {
                return false;
            }
            op->set_progress(static_cast<int>(progress * 1000.0f), static_cast<int64_t>(progress * 1000.0f), 1000);
            return true;
        };
    }

    if (requested_backend == SOLUS_BACKEND_VULKAN) {
        if (is_vulkan_supported_internal() && configure_vulkan_backend_params(mparams, cparams, holder->gpu_devices_storage, n_gpu_layers)) {
            effective_gpu_layers = (n_gpu_layers > 0) ? n_gpu_layers : 99;
            LOGI("SolusLlamaJNI: Vulkan backend requested. Configured Vulkan with %d layers.", effective_gpu_layers);
        } else {
            LOGW("SolusLlamaJNI: Vulkan backend requested, but no Vulkan devices available. Safely falling back to CPU.");
            configure_cpu_backend_params(mparams, cparams);
            holder->gpu_devices_storage.clear();
            effective_gpu_layers = 0;
            requested_backend = SOLUS_BACKEND_CPU;
            holder->requested_backend = SOLUS_BACKEND_CPU;
        }
    } else {
        configure_cpu_backend_params(mparams, cparams);
        holder->gpu_devices_storage.clear();
        effective_gpu_layers = 0;
        LOGI("SolusLlamaJNI: CPU backend requested (0 GPU layers). Preserving true CPU pipeline.");
    }

    TRACE_BEGIN("cpu_gpu_loading");
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);

    if (op_control && op_control->is_cancelled()) {
        TRACE_END();
        if (model) llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
        return 0;
    }

    if (!model && mparams.use_mmap) {
        if (op_control && op_control->is_cancelled()) {
            TRACE_END();
            delete holder;
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
            return 0;
        }
        LOGW("SolusLlamaJNI: Failed to load GGUF model with mmap=true, retrying with mmap=false: %s", model_path.c_str());
        mparams.use_mmap = false;
        model = llama_model_load_from_file(model_path.c_str(), mparams);
        if (op_control && op_control->is_cancelled()) {
            TRACE_END();
            if (model) llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
            return 0;
        }
    }

    // Safe Vulkan Fallback: If loading failed on Vulkan GPU, fallback to CPU (only if not cancelled!)
    if (!model && mparams.n_gpu_layers > 0) {
        if (op_control && op_control->is_cancelled()) {
            TRACE_END();
            delete holder;
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
            return 0;
        }
        LOGW("SolusLlamaJNI: Failed to load GGUF model with Vulkan GPU backend. Safely falling back to CPU backend!");
        configure_cpu_backend_params(mparams, cparams);
        holder->gpu_devices_storage.clear();
        effective_gpu_layers = 0;
        requested_backend = SOLUS_BACKEND_CPU;
        holder->requested_backend = SOLUS_BACKEND_CPU;
        mparams.use_mmap = true;
        model = llama_model_load_from_file(model_path.c_str(), mparams);
        if (op_control && op_control->is_cancelled()) {
            TRACE_END();
            if (model) llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
            return 0;
        }
        if (!model) {
            mparams.use_mmap = false;
            model = llama_model_load_from_file(model_path.c_str(), mparams);
            if (op_control && op_control->is_cancelled()) {
                TRACE_END();
                if (model) llama_model_free(model);
                delete holder;
                set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
                return 0;
            }
        }
    }
    TRACE_END();

    if (!model) {
        std::string err_detail;
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            err_detail = g_last_error_log.empty() ? "unsupported architecture, missing tensors, or invalid GGUF format" : g_last_error_log;
        }
        LOGE("SolusLlamaJNI: Failed to load GGUF model from path %s: %s", model_path.c_str(), err_detail.c_str());
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_last_error_log = "GGUF load failed: " + err_detail;
        }
        delete holder;
        return 0;
    }

    if (op_control && op_control->is_cancelled()) {
        llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled before vocab extraction");
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("SolusLlamaJNI: Failed to get vocabulary from GGUF model: %s", model_path.c_str());
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_last_error_log = "Failed to extract vocabulary from GGUF model";
        }
        llama_model_free(model);
        delete holder;
        return 0;
    }

    int train_ctx = llama_model_n_ctx_train(model);
    int effective_ctx = n_ctx > 0 ? n_ctx : 2048;
    if (train_ctx > 0 && effective_ctx > train_ctx) {
        effective_ctx = train_ctx;
    }
    if (effective_ctx <= 0 || effective_ctx > 8192) {
        effective_ctx = 2048;
    }

    int effective_threads = n_threads > 0 ? n_threads : (int)std::max(1u, std::thread::hardware_concurrency());
    if (effective_threads > 8) effective_threads = 8;

    int effective_threads_batch = n_threads_batch > 0 ? n_threads_batch : effective_threads;
    if (effective_threads_batch > 8) effective_threads_batch = 8;

    uint32_t eff_batch = n_batch > 0 ? static_cast<uint32_t>(n_batch) : 256;
    uint32_t eff_ubatch = n_ubatch > 0 ? static_cast<uint32_t>(n_ubatch) : 128;
    if (eff_batch > static_cast<uint32_t>(effective_ctx)) eff_batch = static_cast<uint32_t>(effective_ctx);
    if (eff_ubatch > eff_batch) eff_ubatch = eff_batch;

    holder->model = model;
    holder->vocab = vocab;
    holder->model_path = model_path;
    holder->n_threads = effective_threads;
    holder->n_threads_batch = effective_threads_batch;
    holder->n_ctx = effective_ctx;
    holder->n_batch = static_cast<int>(eff_batch);
    holder->n_ubatch = static_cast<int>(eff_ubatch);
    holder->requested_backend = requested_backend;
    holder->active_backend = active_backend;
    holder->n_gpu_layers = effective_gpu_layers;
    holder->active_generation_id = 0;
    holder->cancelled_generation_id = 0;
    holder->stop_requested = false;
    holder->is_generating = false;
    holder->is_closed = false;
    holder->active_leases = 0;
    holder->op_control = op_control;

    cparams.n_ctx = static_cast<uint32_t>(effective_ctx);
    cparams.n_batch = eff_batch;
    cparams.n_ubatch = eff_ubatch;
    cparams.n_threads = effective_threads;
    cparams.n_threads_batch = effective_threads_batch;
    cparams.abort_callback = llama_jni_abort_callback;
    cparams.abort_callback_data = holder;

    if (op_control) {
        op_control->set_stage("ContextPreparation");
        op_control->set_progress(1000, 1000, 1000);
    }

    if (op_control && op_control->is_cancelled()) {
        llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled before context creation");
        return 0;
    }

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx && mparams.n_gpu_layers > 0) {
        if (op_control && op_control->is_cancelled()) {
            llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
            return 0;
        }
        LOGW("SolusLlamaJNI: Failed to create context with Vulkan backend. Safely falling back to CPU backend!");
        llama_model_free(model);
        model = nullptr;
        vocab = nullptr;
        configure_cpu_backend_params(mparams, cparams);
        holder->gpu_devices_storage.clear();
        effective_gpu_layers = 0;
        requested_backend = SOLUS_BACKEND_CPU;
        holder->requested_backend = SOLUS_BACKEND_CPU;
        mparams.use_mmap = true;
        model = llama_model_load_from_file(model_path.c_str(), mparams);
        if (!model && (!op_control || !op_control->is_cancelled())) {
            mparams.use_mmap = false;
            model = llama_model_load_from_file(model_path.c_str(), mparams);
        }
        if (model) {
            vocab = llama_model_get_vocab(model);
            holder->model = model;
            holder->vocab = vocab;
            if (vocab && (!op_control || !op_control->is_cancelled())) {
                ctx = llama_init_from_model(model, cparams);
            }
        }
    }

    if (!ctx && model && vocab && effective_ctx > 1024) {
        if (op_control && op_control->is_cancelled()) {
            llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
            return 0;
        }
        LOGW("SolusLlamaJNI: Failed to allocate context size %d, retrying with safe context 1024", effective_ctx);
        effective_ctx = 1024;
        eff_batch = std::min(eff_batch, 128u);
        eff_ubatch = std::min(eff_ubatch, 64u);
        cparams.n_ctx = 1024;
        cparams.n_batch = eff_batch;
        cparams.n_ubatch = eff_ubatch;
        ctx = llama_init_from_model(model, cparams);
    }

    if (op_control && op_control->is_cancelled()) {
        if (ctx) llama_free(ctx);
        llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
        return 0;
    }

    if (ctx) {
        holder->n_ctx = static_cast<int>(llama_n_ctx(ctx));
        holder->n_batch = static_cast<int>(eff_batch);
        holder->n_ubatch = static_cast<int>(eff_ubatch);
    }

    if (!ctx) {
        std::string err_detail;
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            err_detail = g_last_error_log.empty() ? "insufficient memory for KV cache or context allocation" : g_last_error_log;
        }
        LOGE("SolusLlamaJNI: Failed to create llama_context for model %s: %s", model_path.c_str(), err_detail.c_str());
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_last_error_log = "Context creation failed: " + err_detail;
        }
        llama_model_free(model);
        delete holder;
        return 0;
    }

    active_backend = (mparams.n_gpu_layers > 0) ? SOLUS_BACKEND_VULKAN : SOLUS_BACKEND_CPU;
    holder->active_backend = active_backend;
    holder->n_gpu_layers = effective_gpu_layers;
    holder->ctx = ctx;
    llama_set_abort_callback(ctx, llama_jni_abort_callback, holder);

    holder->backend_inspection = inspect_gguf_backend(ctx);
    if (mparams.n_gpu_layers == 0) {
        if (!holder->backend_inspection.is_cpu_isolated) {
            LOGE("SolusLlamaJNI: CPU isolation check failed on CPU context! GPU compute attached: %d, GPU buffer: %zu bytes",
                 holder->backend_inspection.has_gpu_compute_backend ? 1 : 0,
                 holder->backend_inspection.gpu_compute_buffer_bytes);
            llama_free(ctx);
            llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_BACKEND_EXECUTION, -8, "CPU isolation check failed: GPU compute backend or buffer attached");
            return 0;
        }
        LOGI("SolusLlamaJNI: CPU isolation verified successfully (0 GPU buffers, 0 GPU layers, offload_kqv=false).");
    }

    LOGI("SolusLlamaJNI: Successfully initialized GGUF model on %s backend: %s (ctx=%d, threads=%d, batch=%d/%d, gpu_layers=%d, vocab_size=%d)",
         (active_backend == SOLUS_BACKEND_VULKAN ? "Vulkan GPU" : "CPU"),
         model_path.c_str(), effective_ctx, effective_threads, eff_batch, eff_ubatch, effective_gpu_layers, llama_vocab_n_tokens(vocab));

    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGenerateStream(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jlong generation_id,
        jstring prompt_str,
        jobjectArray roles_array,
        jobjectArray contents_array,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jfloat min_p,
        jfloat repeat_penalty,
        jint max_tokens,
        jint seed,
        jobject callback_obj) {

    if (handle == 0 || callback_obj == nullptr) {
        LOGE("SolusLlamaJNI: nativeGenerateStream called with invalid arguments");
        return -1;
    }

    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->model || !holder->ctx || !holder->vocab || holder->is_closed.load(std::memory_order_acquire)) {
        LOGE("SolusLlamaJNI: Context is closed or invalid");
        return -1;
    }

    // Acquire native lease
    holder->active_leases.fetch_add(1, std::memory_order_acq_rel);

    // RAII guard guarantees lease is released, sampler/batch freed, and condition variable notified
    NativeLeaseGuard lease_guard(holder);

    bool expected = false;
    if (!holder->is_generating.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        LOGW("SolusLlamaJNI: Generation already in progress on this context");
        return -1;
    }

    uint64_t gid = static_cast<uint64_t>(generation_id);
    holder->active_generation_id.store(gid, std::memory_order_release);

    jclass callback_class = env->GetObjectClass(callback_obj);
    if (!callback_class) {
        return -1;
    }

    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_complete_method = env->GetMethodID(callback_class, "onComplete", "()V");
    jmethodID on_stop_method = env->GetMethodID(callback_class, "onStop", "()V");
    jmethodID on_error_method = env->GetMethodID(callback_class, "onError", "(Ljava/lang/String;)V");
    jmethodID on_metrics_method = env->GetMethodID(callback_class, "onMetrics", "(JJI)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_metrics_ext_method = env->GetMethodID(callback_class, "onMetrics", "(JJIIII)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_first_token_method = env->GetMethodID(callback_class, "onFirstToken", "()V");

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (!on_token_method) {
        LOGE("SolusLlamaJNI: Callback method onToken not found");
        return -1;
    }

    // Check if this generation ID was already cancelled before entering generation loop
    if (gid != 0 && holder->cancelled_generation_id.load(std::memory_order_acquire) >= gid) {
        LOGI("SolusLlamaJNI: Generation %llu was cancelled before start", (unsigned long long)gid);
        if (on_stop_method) {
            call_jni_void_method_checked(env, callback_obj, on_stop_method);
        }
        return 1;
    }

    holder->stop_requested.store(false, std::memory_order_release);
    LOGI("SolusLlamaJNI: [PHASE: START] Generation started (gen_id=%llu, max_tokens=%d)", (unsigned long long)gid, max_tokens);

    // 1. Format and tokenize prompt using model's chat template
    g_last_failure_category.store(SOLUS_FAILURE_NONE, std::memory_order_release);
    g_last_failure_code.store(0, std::memory_order_release);
    solus_clear_numerical_anomaly();

    TRACE_BEGIN("tokenization");
    std::vector<llama_token> prompt_tokens;
    std::string prompt_err;
    int prompt_cat = SOLUS_FAILURE_NONE;
    if (!format_and_tokenize_prompt(holder, env, prompt_str, roles_array, contents_array, prompt_tokens, prompt_err, prompt_cat, max_tokens)) {
        TRACE_END();
        set_last_failure(prompt_cat, -4, prompt_err);
        if (on_error_method) {
            call_jni_string_method_checked(env, callback_obj, on_error_method, prompt_err.c_str());
        }
        return -4;
    }

    const llama_vocab * vocab = holder->vocab;
    const char * model_tmpl = llama_model_chat_template(holder->model, nullptr);
    bool is_llama3 = false;
    if (model_tmpl) {
        std::string tmpl_str(model_tmpl);
        is_llama3 = (tmpl_str.find("<|start_header_id|>") != std::string::npos &&
                     tmpl_str.find("<|end_header_id|>")   != std::string::npos) ||
                    (tmpl_str == "llama3");
    }
    if (!is_llama3 && vocab) {
        llama_token header_tok = LLAMA_TOKEN_NULL;
        int n_tok = llama_tokenize(vocab, "<|start_header_id|>", 19, &header_tok, 1, false, true);
        if (n_tok == 1 && header_tok != LLAMA_TOKEN_NULL && llama_vocab_is_control(vocab, header_tok)) {
            is_llama3 = true;
        }
    }
    TRACE_END();

    TRACE_BEGIN("cached_prefix_reuse");
    LOGI("SolusLlamaJNI: [PHASE: PROMPT_TOKENIZED] Tokenized prompt into %zu tokens", prompt_tokens.size());
    size_t reused_tokens = reusable_prompt_tokens(holder->cached_tokens, prompt_tokens);
    // Recurrent architectures may reject a partial removal; rebuild safely in that case.
    if (reused_tokens == 0 || !llama_kv_cache_seq_rm(holder->ctx, -1, static_cast<llama_pos>(reused_tokens), -1)) {
        clear_prompt_cache(holder);
        reused_tokens = 0;
    } else {
        holder->cached_tokens.resize(reused_tokens);
    }
    LOGI("SolusLlamaJNI: Reusing %zu prompt tokens; evaluating %zu new tokens",
         reused_tokens, prompt_tokens.size() - reused_tokens);
    TRACE_END();

    // 3. Initialize Sampler Chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    lease_guard.smpl = smpl;

    float eff_repeat_penalty = repeat_penalty > 0.0f ? repeat_penalty : 1.1f;
    if (eff_repeat_penalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, eff_repeat_penalty, 0.0f, 0.0f));
    }

    int32_t eff_top_k = top_k > 0 ? top_k : 40;
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(eff_top_k));

    float eff_top_p = (top_p > 0.0f && top_p <= 1.0f) ? top_p : 0.95f;
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(eff_top_p, 1));

    float eff_min_p = min_p > 0.0f ? min_p : 0.05f;
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(eff_min_p, 1));

    float eff_temp = temperature >= 0.0f ? temperature : 0.7f;
    if (eff_temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(eff_temp));
        uint32_t eff_seed = seed != 0 ? static_cast<uint32_t>(seed) : LLAMA_DEFAULT_SEED;
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(eff_seed));
    }

    // 4. Evaluate prompt tokens in batches
    uint32_t ctx_size = llama_n_ctx(holder->ctx);
    int batch_size = holder->n_batch > 0 ? holder->n_batch : 256;
    lease_guard.batch = llama_batch_init(batch_size, 0, 1);
    lease_guard.batch_initialized = true;
    llama_batch & batch = lease_guard.batch;
    std::vector<llama_seq_id> seq_ids = { 0 };

    LOGI("SolusLlamaJNI: [PHASE: PROMPT_DECODE_STARTED] Evaluating %zu prompt tokens (batch_size=%d, ctx=%u)",
         prompt_tokens.size(), batch_size, ctx_size);
    const auto prefill_started = std::chrono::steady_clock::now();

    TRACE_BEGIN("prompt_decoding");
    bool aborted = false;
    for (size_t i = reused_tokens; i < prompt_tokens.size(); ++i) {
        if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
            LOGI("SolusLlamaJNI: Stop requested before prompt token %zu", i);
            aborted = true;
            break;
        }
        bool is_last = (i == prompt_tokens.size() - 1);
        batch_add(batch, prompt_tokens[i], static_cast<llama_pos>(i), seq_ids, is_last);

        if (batch.n_tokens == batch_size || is_last) {
            if (llama_decode(holder->ctx, batch) != 0) {
                if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
                    LOGI("SolusLlamaJNI: Decode aborted during prompt processing via stop request");
                    set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Stop requested");
                    aborted = true;
                } else {
                    LOGE("SolusLlamaJNI: Generation failed: llama_decode failed during prompt processing at token %zu", i);
                    set_last_failure(SOLUS_FAILURE_PROMPT_DECODE_FAILED, -5, "llama_decode failed during prompt processing");
                    clear_prompt_cache(holder);
                    if (on_error_method) {
                        call_jni_string_method_checked(env, callback_obj, on_error_method, "llama_decode failed during prompt processing");
                    }
                    TRACE_END();
                    return -5;
                }
                break;
            }
            // [NUMERICAL_AUDIT] Check for NaN/Inf in logits immediately after decode.
            if (is_last || solus_has_numerical_anomaly()) {
                if (solus_has_numerical_anomaly() || !audit_logits_numerics(holder->ctx, "PROMPT_DECODE", static_cast<int>(i))) {
                    LOGE("SolusLlamaJNI: Non-finite logits or numerical anomaly after prompt decode");
                    set_last_failure(SOLUS_FAILURE_NUMERICAL_INSTABILITY, -2, "Numerical instability: non-finite logits after prompt decode");
                    clear_prompt_cache(holder);
                    if (on_error_method) {
                        call_jni_string_method_checked(env, callback_obj, on_error_method, "Numerical instability: non-finite logits after prompt decode");
                    }
                    TRACE_END();
                    return -2;
                }
            }
            batch.n_tokens = 0;
        }
    }
    TRACE_END();

    if (aborted) {
        clear_prompt_cache(holder);
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Generation stopped during prompt evaluation");
        LOGI("SolusLlamaJNI: [PHASE: END] Generation stopped during prompt evaluation");
        if (on_stop_method) {
            call_jni_void_method_checked(env, callback_obj, on_stop_method);
        }
        return 1;
    }

    LOGI("SolusLlamaJNI: [PHASE: PROMPT_DECODE_FINISHED] Prompt evaluation complete (%zu tokens)", prompt_tokens.size());

    holder->cached_tokens = prompt_tokens;

    // 5. Autoregressive token generation loop
    const auto decode_started = std::chrono::steady_clock::now();
    LOGI("SolusLlamaJNI: [PHASE: FIRST_TOKEN_ATTEMPT] Beginning generation loop");
    int n_cur = static_cast<int>(prompt_tokens.size());
    int n_generated = 0;
    int max_gen = max_tokens > 0 ? max_tokens : 1024;
    std::string utf8_stream_buf;

    llama_token eos_token = llama_vocab_eos(vocab);
    llama_token eot_token = llama_vocab_eot(vocab);

    while (n_generated < max_gen && n_cur < (int)ctx_size - 1) {
        if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
            LOGI("SolusLlamaJNI: Stop requested at generation step %d", n_generated);
            set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Stop requested");
            aborted = true;
            break;
        }

        // [PRE-SAMPLING LOGITS AUDIT] Check logits before EVERY sampling step!
        if (!audit_logits_numerics(holder->ctx, "PRE_SAMPLE", n_cur) || solus_has_numerical_anomaly()) {
            LOGE("SolusLlamaJNI: Non-finite logits or numerical anomaly before sampling at step %d", n_generated);
            set_last_failure(SOLUS_FAILURE_NUMERICAL_INSTABILITY, -2, "Numerical instability: non-finite logits detected before sampling at step " + std::to_string(n_generated));
            clear_prompt_cache(holder);
            if (on_error_method) {
                call_jni_string_method_checked(env, callback_obj, on_error_method, "Numerical instability: non-finite logits before sampling");
            }
            return -2;
        }

        TRACE_BEGIN("sampling");
        llama_token token = llama_sampler_sample(smpl, holder->ctx, -1);
        TRACE_END();
        // NOTE: do NOT call llama_sampler_accept here. llama_sampler_sample() already
        // calls llama_sampler_accept internally (see llama-sampling.cpp). Calling it
        // again doubles every token's repetition penalty, corrupting the sampler state
        // and causing gibberish / out-of-distribution output.

        if (llama_vocab_is_eog(vocab, token) || token == eos_token || token == eot_token ||
            (is_llama3 && llama_vocab_is_control(vocab, token))) {
            LOGI("SolusLlamaJNI: Generation completed at step %d (EOG/EOS/Control token %d)", n_generated, token);
            break;
        }

        if (n_generated == 0 && on_first_token_method) {
            call_jni_void_method_checked(env, callback_obj, on_first_token_method);
        }
        char piece_buf[256];
        int n_piece = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, false);
        if (n_piece < 0) {
            std::vector<char> big_piece(-n_piece);
            n_piece = llama_token_to_piece(vocab, token, big_piece.data(), big_piece.size(), 0, false);
            if (n_piece > 0) {
                utf8_stream_buf.append(big_piece.data(), n_piece);
            }
        } else if (n_piece > 0) {
            utf8_stream_buf.append(piece_buf, n_piece);
        }

        std::string piece_to_emit = extract_complete_utf8(utf8_stream_buf);
        if (!piece_to_emit.empty() && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
            if (!call_jni_string_method_checked(env, callback_obj, on_token_method, piece_to_emit.c_str())) {
                LOGE("SolusLlamaJNI: onToken threw exception, aborting generation loop");
                holder->stop_requested.store(true, std::memory_order_release);
                set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "onToken threw exception");
                aborted = true;
                break;
            }
        }

        batch.n_tokens = 0;
        batch_add(batch, token, static_cast<llama_pos>(n_cur), seq_ids, true);

        if (llama_decode(holder->ctx, batch) != 0) {
            if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
                LOGI("SolusLlamaJNI: Decode aborted during token generation at step %d via stop request", n_generated);
                set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Stop requested");
                aborted = true;
            } else {
                LOGE("SolusLlamaJNI: Generation failed: llama_decode failed at generation step %d", n_generated);
                set_last_failure(SOLUS_FAILURE_GENERATION_DECODE_FAILED, -6, "llama_decode failed during token generation");
                clear_prompt_cache(holder);
                if (on_error_method) {
                    call_jni_string_method_checked(env, callback_obj, on_error_method, "llama_decode failed during token generation");
                }
                return -6;
            }
            break;
        }

        holder->cached_tokens.push_back(token);
        n_cur++;
        n_generated++;
    }

    if (!utf8_stream_buf.empty() && !aborted && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
        call_jni_string_method_checked(env, callback_obj, on_token_method, utf8_stream_buf.c_str());
    }

    const auto decode_finished = std::chrono::steady_clock::now();
    const double prefill_ms = std::chrono::duration<double, std::milli>(decode_started - prefill_started).count();
    const double decode_ms = std::chrono::duration<double, std::milli>(decode_finished - decode_started).count();

    const int total_prompt_tokens = static_cast<int>(prompt_tokens.size());
    const int reused_prompt_tokens = static_cast<int>(reused_tokens);
    const int evaluated_prompt_tokens = static_cast<int>(prompt_tokens.size() > reused_tokens ? prompt_tokens.size() - reused_tokens : 0);

    if (aborted) {
        clear_prompt_cache(holder);
        LOGI("SolusLlamaJNI: [PHASE: END] Generation stopped after %d tokens", n_generated);
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Generation stopped");
        if (on_metrics_ext_method) {
            env->CallVoidMethod(callback_obj, on_metrics_ext_method,
                static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated),
                static_cast<jint>(total_prompt_tokens), static_cast<jint>(reused_prompt_tokens), static_cast<jint>(evaluated_prompt_tokens));
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else if (on_metrics_method) {
            env->CallVoidMethod(callback_obj, on_metrics_method,
                static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (on_stop_method) {
            call_jni_void_method_checked(env, callback_obj, on_stop_method);
        }
        return 1;
    }

    LOGI("SolusLlamaJNI: [PHASE: END] Generation completed successfully (%d tokens)", n_generated);
    LOGI("SolusLlamaJNI: [PERF] prompt_tokens=%d reused_tokens=%d evaluated_tokens=%d prefill_ms=%.1f decode_ms=%.1f tokens=%d decode_tok_s=%.2f",
         total_prompt_tokens, reused_prompt_tokens, evaluated_prompt_tokens, prefill_ms, decode_ms, n_generated,
         decode_ms > 0 ? n_generated * 1000.0 / decode_ms : 0.0);
    set_last_failure(SOLUS_FAILURE_NONE, 0, "");
    if (on_metrics_ext_method) {
        env->CallVoidMethod(callback_obj, on_metrics_ext_method,
            static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated),
            static_cast<jint>(total_prompt_tokens), static_cast<jint>(reused_prompt_tokens), static_cast<jint>(evaluated_prompt_tokens));
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (on_metrics_method) {
        env->CallVoidMethod(callback_obj, on_metrics_method,
            static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (on_complete_method) {
        call_jni_void_method_checked(env, callback_obj, on_complete_method);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeStop(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong handle,
        jlong generation_id) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    uint64_t gid = static_cast<uint64_t>(generation_id);
    if (gid == 0 || gid >= holder->cancelled_generation_id.load(std::memory_order_relaxed)) {
        holder->cancelled_generation_id.store(gid, std::memory_order_release);
        holder->stop_requested.store(true, std::memory_order_release);
        LOGI("SolusLlamaJNI: Stop requested for GGUF handle %p (gen_id=%llu)", holder, (unsigned long long)gid);
    }
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetMetadata(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return nullptr;
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->model) return nullptr;

    char desc_buf[256] = {0};
    llama_model_desc(holder->model, desc_buf, sizeof(desc_buf));

    char arch_buf[128] = {0};
    llama_model_meta_val_str(holder->model, "general.architecture", arch_buf, sizeof(arch_buf));

    uint64_t n_params = llama_model_n_params(holder->model);
    int32_t n_ctx_train = llama_model_n_ctx_train(holder->model);
    int32_t n_vocab = holder->vocab ? llama_vocab_n_tokens(holder->vocab) : 0;
    const char *chat_tmpl = llama_model_chat_template(holder->model, nullptr);

    std::ostringstream json;
    json << "{"
         << "\"desc\":\"" << escape_json(desc_buf) << "\","
         << "\"architecture\":\"" << escape_json(arch_buf) << "\","
         << "\"n_params\":" << n_params << ","
         << "\"n_ctx_train\":" << n_ctx_train << ","
         << "\"n_vocab\":" << n_vocab << ","
         << "\"has_chat_template\":" << (chat_tmpl != nullptr ? "true" : "false") << ","
         << "\"backend\":\"" << (holder->active_backend == SOLUS_BACKEND_VULKAN ? "Vulkan" : "CPU") << "\","
         << "\"n_ctx\":" << holder->n_ctx << ","
         << "\"n_batch\":" << holder->n_batch << ","
         << "\"n_ubatch\":" << holder->n_ubatch << ","
         << "\"n_gpu_layers\":" << holder->n_gpu_layers << ","
         << "\"effective_offload_kqv\":" << (holder->backend_inspection.effective_offload_kqv ? "true" : "false") << ","
         << "\"is_cpu_isolated\":" << (holder->backend_inspection.is_cpu_isolated ? "true" : "false") << ","
         << "\"gpu_compute_buffer_bytes\":" << static_cast<uint64_t>(holder->backend_inspection.gpu_compute_buffer_bytes) << ","
         << "\"backend_policy_version\":" << holder->backend_inspection.backend_policy_version << ","
         << "\"actual_compute_backends\":[";
    for (size_t i = 0; i < holder->backend_inspection.compute_backend_names.size(); ++i) {
        if (i > 0) json << ",";
        json << "\"" << escape_json(holder->backend_inspection.compute_backend_names[i]) << "\"";
    }
    json << "]}";

    return safe_new_string(env, json.str());
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeClearProbeReferences(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_probe_mutex);
    g_probe_reference_runs.clear();
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeResetAfterValidation(
        JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    clear_prompt_cache(holder);
    solus_clear_numerical_anomaly();
    std::lock_guard<std::mutex> lock(g_probe_mutex);
    g_probe_reference_runs.clear();
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeFree(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    LOGI("SolusLlamaJNI: Freeing GGUF handle %p", holder);

    // 1. Mark closed and trigger stop so decode aborts immediately
    holder->is_closed.store(true, std::memory_order_release);
    holder->stop_requested.store(true, std::memory_order_release);

    // 2. Wait safely for all in-flight generation leases to release and exit
    std::unique_lock<std::mutex> lock(holder->state_mutex);
    holder->gen_cv.wait(lock, [holder]() {
        return holder->active_leases.load(std::memory_order_acquire) == 0 &&
               !holder->is_generating.load(std::memory_order_acquire);
    });

    // 3. Clean up native structures safely
    if (holder->ctx) {
        llama_free(holder->ctx);
        holder->ctx = nullptr;
    }
    if (holder->model) {
        llama_model_free(holder->model);
        holder->model = nullptr;
    }
    lock.unlock();
    delete holder;
    LOGI("SolusLlamaJNI: Successfully freed GGUF handle");
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetLastFailureCategory(
        JNIEnv *env,
        jclass /* clazz */) {
    int cat = g_last_failure_category.load(std::memory_order_acquire);
    return safe_new_string(env, solus_failure_category_name(cat));
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetLastFailureCode(
        JNIEnv * /* env */,
        jclass /* clazz */) {
    return g_last_failure_code.load(std::memory_order_acquire);
}

JNIEXPORT jlong JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeCreateOperationControl(
        JNIEnv * /* env */,
        jclass /* clazz */) {
    return static_cast<jlong>(NativeOperationControlRegistry::instance().create());
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeCancelOperationControl(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong control_id) {
    NativeOperationControlRegistry::instance().cancel(static_cast<uint64_t>(control_id));
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeReleaseOperationControl(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong control_id) {
    NativeOperationControlRegistry::instance().release(static_cast<uint64_t>(control_id));
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetOperationControlProgress(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong control_id) {
    auto ctrl = NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(control_id));
    return ctrl ? ctrl->get_progress() : -1;
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetOperationControlSnapshot(
        JNIEnv * env,
        jclass /* clazz */,
        jlong control_id) {
    auto ctrl = NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(control_id));
    if (!ctrl) return nullptr;
    return safe_new_string(env, ctrl->get_snapshot_json());
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeProbeReference(
        JNIEnv *env,
        jclass /* clazz */,
        jlong handle,
        jint prompt_index,
        jobjectArray roles_array,
        jobjectArray contents_array,
        jint n_positions,
        jlong operation_control_id) {
    ScopedTrace trace("probe_reference");
    if (handle == 0) return safe_new_string(env, "{\"success\":false,\"error\":\"Null handle\"}");
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->model || !holder->ctx || !holder->vocab || holder->is_closed.load(std::memory_order_acquire)) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Closed context\"}");
    }

    if (!holder->backend_inspection.is_cpu_isolated) {
        LOGE("SolusLlamaJNI: nativeProbeReference rejected because context is not verified CPU isolated");
        return safe_new_string(env, "{\"success\":false,\"error\":\"CPU reference probe requires verified CPU-isolated context\"}");
    }

    std::shared_ptr<NativeOperationControl> op_control =
        NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(operation_control_id));
    if (op_control && op_control->is_cancelled()) {
        return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
    }

    std::vector<llama_token> prompt_tokens;
    std::string err;
    int cat = SOLUS_FAILURE_NONE;
    if (!format_and_tokenize_prompt(holder, env, nullptr, roles_array, contents_array, prompt_tokens, err, cat)) {
        std::string res = std::string("{\"success\":false,\"error\":\"") + escape_json(err) + "\",\"category\":\"" + solus_failure_category_name(cat) + "\"}";
        return safe_new_string(env, res);
    }

    clear_prompt_cache(holder);
    solus_clear_numerical_anomaly();

    int n_pos = (n_positions > 0 && n_positions <= 64) ? n_positions : 8;
    int total_probe_units = static_cast<int>(prompt_tokens.size()) + n_pos;
    if (op_control) {
        op_control->set_stage("CpuReference");
        op_control->set_progress(0, 0, total_probe_units);
    }

    int batch_size = holder->n_batch > 0 ? holder->n_batch : 256;
    llama_batch batch = llama_batch_init(batch_size, 0, 1);
    std::vector<llama_seq_id> seq_ids = { 0 };

    bool decode_ok = true;
    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
        if (op_control && op_control->is_cancelled()) {
            llama_batch_free(batch);
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }
        bool is_last = (i == prompt_tokens.size() - 1);
        batch_add(batch, prompt_tokens[i], static_cast<llama_pos>(i), seq_ids, is_last);
        if (batch.n_tokens == batch_size || is_last) {
            if (llama_decode(holder->ctx, batch) != 0) {
                decode_ok = false;
                break;
            }
            if (solus_has_numerical_anomaly() || (is_last && !audit_logits_numerics(holder->ctx, "PROBE_REF_PROMPT", static_cast<int>(i)))) {
                decode_ok = false;
                break;
            }
            batch.n_tokens = 0;
        }
        if (op_control) {
            int completed = static_cast<int>(i + 1);
            int permille = static_cast<int>((static_cast<float>(completed) / static_cast<float>(total_probe_units)) * 1000.0f);
            op_control->set_progress(permille, completed, total_probe_units);
        }
    }
    llama_batch_free(batch);

    if (op_control && op_control->is_cancelled()) {
        return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
    }

    if (!decode_ok || solus_has_numerical_anomaly()) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Prompt decode failed or non-finite logits\"}");
    }

    int n_vocab = llama_vocab_n_tokens(holder->vocab);
    int n_cur = static_cast<int>(prompt_tokens.size());

    std::vector<ProbePositionRecord> records;
    records.reserve(n_pos);

    for (int p = 0; p < n_pos; ++p) {
        if (op_control && op_control->is_cancelled()) {
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }
        const float * logits = llama_get_logits_ith(holder->ctx, -1);
        if (!logits) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Null logits\"}");
        }
        std::vector<float> probs;
        if (!compute_softmax(logits, n_vocab, probs) || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Non-finite logits at probe position\"}");
        }

        llama_token best_tok = 0;
        float best_p = -1.0f;
        for (int v = 0; v < n_vocab; ++v) {
            if (probs[v] > best_p) {
                best_p = probs[v];
                best_tok = v;
            }
        }

        records.push_back({ best_tok, std::move(probs) });

        llama_batch step_batch = llama_batch_init(1, 0, 1);
        batch_add(step_batch, best_tok, static_cast<llama_pos>(n_cur), seq_ids, true);
        int d_res = llama_decode(holder->ctx, step_batch);
        llama_batch_free(step_batch);

        if (op_control && op_control->is_cancelled()) {
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }

        if (d_res != 0 || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Token decode failed in probe reference\"}");
        }
        n_cur++;

        if (op_control) {
            int completed = static_cast<int>(prompt_tokens.size()) + p + 1;
            int permille = static_cast<int>((static_cast<float>(completed) / static_cast<float>(total_probe_units)) * 1000.0f);
            op_control->set_progress(permille, completed, total_probe_units);
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_probe_mutex);
        g_probe_reference_runs[prompt_index] = std::move(records);
    }

    return safe_new_string(env, "{\"success\":true}");
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeProbeCompare(
        JNIEnv *env,
        jclass /* clazz */,
        jlong handle,
        jint prompt_index,
        jobjectArray roles_array,
        jobjectArray contents_array,
        jint n_positions,
        jlong operation_control_id) {
    ScopedTrace trace("probe_compare");
    if (handle == 0) return safe_new_string(env, "{\"success\":false,\"error\":\"Null handle\"}");
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->model || !holder->ctx || !holder->vocab || holder->is_closed.load(std::memory_order_acquire)) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Closed context\"}");
    }

    std::shared_ptr<NativeOperationControl> op_control =
        NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(operation_control_id));
    if (op_control && op_control->is_cancelled()) {
        return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
    }

    std::vector<ProbePositionRecord> ref_records;
    {
        std::lock_guard<std::mutex> lock(g_probe_mutex);
        auto it = g_probe_reference_runs.find(prompt_index);
        if (it == g_probe_reference_runs.end()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"No reference run found for prompt index\"}");
        }
        ref_records = it->second;
    }

    int n_pos = (n_positions > 0 && n_positions <= 64) ? n_positions : 8;
    if ((int)ref_records.size() < n_pos) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Reference records shorter than requested positions\"}");
    }

    std::vector<llama_token> prompt_tokens;
    std::string err;
    int cat = SOLUS_FAILURE_NONE;
    if (!format_and_tokenize_prompt(holder, env, nullptr, roles_array, contents_array, prompt_tokens, err, cat)) {
        std::string res = std::string("{\"success\":false,\"error\":\"") + escape_json(err) + "\",\"category\":\"" + solus_failure_category_name(cat) + "\"}";
        return safe_new_string(env, res);
    }

    clear_prompt_cache(holder);
    solus_clear_numerical_anomaly();

    int total_probe_units = static_cast<int>(prompt_tokens.size()) + n_pos;
    if (op_control) {
        op_control->set_stage("GpuProbe");
        op_control->set_progress(0, 0, total_probe_units);
    }

    int batch_size = holder->n_batch > 0 ? holder->n_batch : 256;
    llama_batch batch = llama_batch_init(batch_size, 0, 1);
    std::vector<llama_seq_id> seq_ids = { 0 };

    bool decode_ok = true;
    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
        if (op_control && op_control->is_cancelled()) {
            llama_batch_free(batch);
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }
        bool is_last = (i == prompt_tokens.size() - 1);
        batch_add(batch, prompt_tokens[i], static_cast<llama_pos>(i), seq_ids, is_last);
        if (batch.n_tokens == batch_size || is_last) {
            if (llama_decode(holder->ctx, batch) != 0) {
                decode_ok = false;
                break;
            }
            if (solus_has_numerical_anomaly() || (is_last && !audit_logits_numerics(holder->ctx, "PROBE_CMP_PROMPT", static_cast<int>(i)))) {
                decode_ok = false;
                break;
            }
            batch.n_tokens = 0;
        }
        if (op_control) {
            int completed = static_cast<int>(i + 1);
            int permille = static_cast<int>((static_cast<float>(completed) / static_cast<float>(total_probe_units)) * 1000.0f);
            op_control->set_progress(permille, completed, total_probe_units);
        }
    }
    llama_batch_free(batch);

    if (op_control && op_control->is_cancelled()) {
        return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
    }

    if (!decode_ok || solus_has_numerical_anomaly()) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Prompt decode failed or non-finite logits on GPU\"}");
    }

    int n_vocab = llama_vocab_n_tokens(holder->vocab);
    int n_cur = static_cast<int>(prompt_tokens.size());
    float max_tv = 0.0f;

    for (int p = 0; p < n_pos; ++p) {
        if (op_control && op_control->is_cancelled()) {
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }
        const float * logits = llama_get_logits_ith(holder->ctx, -1);
        if (!logits) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Null logits on GPU\"}");
        }
        std::vector<float> gpu_probs;
        if (!compute_softmax(logits, n_vocab, gpu_probs) || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Non-finite logits on GPU at position\"}");
        }

        float tv = compute_tv_distance(ref_records[p].probs, gpu_probs);
        if (tv > max_tv) max_tv = tv;

        if (tv > 0.05f) {
            std::ostringstream ss;
            ss << "{\"success\":false,"
               << "\"error\":\"TV distance " << tv << " exceeded 0.05 threshold at position " << p << "\","
               << "\"pos\":" << p << ","
               << "\"tv\":" << tv << ","
               << "\"max_tv\":" << max_tv << "}";
            return safe_new_string(env, ss.str());
        }

        // Feed CPU-selected token!
        llama_token ref_tok = ref_records[p].token;
        llama_batch step_batch = llama_batch_init(1, 0, 1);
        batch_add(step_batch, ref_tok, static_cast<llama_pos>(n_cur), seq_ids, true);
        int d_res = llama_decode(holder->ctx, step_batch);
        llama_batch_free(step_batch);

        if (op_control && op_control->is_cancelled()) {
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }

        if (d_res != 0 || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Token decode failed on GPU probe\"}");
        }
        n_cur++;

        if (op_control) {
            int completed = static_cast<int>(prompt_tokens.size()) + p + 1;
            int permille = static_cast<int>((static_cast<float>(completed) / static_cast<float>(total_probe_units)) * 1000.0f);
            op_control->set_progress(permille, completed, total_probe_units);
        }
    }

    std::ostringstream ss;
    ss << "{\"success\":true,\"max_tv\":" << max_tv << "}";
    return safe_new_string(env, ss.str());
}

} // extern "C"

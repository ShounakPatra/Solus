#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <map>
#include <android/log.h>

#include "llama.h"
#include "llama-arch.h"
#include "ggml.h"
#include "gguf.h"

#ifdef GGML_USE_VULKAN
#include <vulkan/vulkan.h>
#include "ggml-vulkan.h"
#endif

enum SolusBackend {
    SOLUS_BACKEND_CPU = 0,
    SOLUS_BACKEND_VULKAN = 1
};

#define LOG_TAG "SolusLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_log_mutex;
static std::string g_last_error_log;
static std::vector<std::string> g_recent_logs;

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
    int n_ctx = 2048;
    int n_batch = 256;
    int n_ubatch = 128;

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
};

// GGML abort callback - executed cooperatively inside llama_decode() operations
static bool llama_jni_abort_callback(void * data) {
    if (!data) return false;
    auto * holder = static_cast<LlamaContextHolder *>(data);
    return holder->stop_requested.load(std::memory_order_relaxed) ||
           holder->is_closed.load(std::memory_order_relaxed);
}

/**
 * Audits the last-decode logit buffer for NaN/Inf values.
 *
 * Called AFTER llama_decode() returns successfully.  Reads the float logit slice
 * for the single last-token position and scans for non-finite values.
 *
 * Zero overhead in the normal case: the early-exit branch fires immediately when
 * all values are finite (which is the expected path).
 *
 * This does NOT touch ggml-quants.c or weaken any assertion.
 * It identifies WHEN activations first become invalid so the root cause can be traced.
 *
 * @param ctx        llama context (used to get logit pointer and vocab size)
 * @param phase      "PROMPT_DECODE" or "TOKEN_GENERATION"
 * @param token_pos  current token position in the sequence
 * @param batch_end  index of the last decoded token in the batch (0-indexed within batch)
 */
static void audit_logits_numerics(llama_context * ctx, const char * phase, int token_pos) {
    if (!ctx) return;

    // Retrieve the logit float buffer for the last token in the previous decode call.
    // llama_get_logits_ith(ctx, -1) fetches logits for the last batch position.
    const float * logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return;

    // Get vocabulary size to know how many floats to scan
    const llama_model * mdl = llama_get_model(ctx);
    if (!mdl) return;

    int n_vocab = 0;
    {
        const llama_vocab * v = llama_model_get_vocab(mdl);
        if (v) n_vocab = llama_vocab_n_tokens(v);
    }
    if (n_vocab <= 0 || n_vocab > 256 * 1024) return;

    // Fast path: check for any non-finite value first
    bool any_bad = false;
    for (int i = 0; i < n_vocab; ++i) {
        float v = logits[i];
        if (v != v || v > 3.4028235e+38f || v < -3.4028235e+38f) { // NaN or ±Inf
            any_bad = true;
            break;
        }
    }
    if (!any_bad) return; // No bad values — zero overhead normal path

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
    LOGE("SolusLlamaJNI: [NUMERICAL_AUDIT] >>> This is the point where activations became"
         " non-finite. The GGML quantize_row_q8_K assertion will fire on the NEXT decode"
         " call if this is not fixed. Root cause is upstream in the attention/RoPE/RMSNorm"
         " or operator graph, NOT in the quantizer itself. <<<");

    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_error_log = std::string("[NUMERICAL_AUDIT] ") + report_buf;
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

    std::vector<jchar> utf16;
    utf16.reserve(len);

    size_t i = 0;
    while (i < len) {
        uint8_t c = static_cast<uint8_t>(utf8[i]);
        if (c < 0x80) {
            utf16.push_back(static_cast<jchar>(c));
            i++;
        } else if ((c & 0xE0) == 0xC0) {
            if (i + 1 < len) {
                uint8_t c2 = static_cast<uint8_t>(utf8[i + 1]);
                if ((c2 & 0xC0) == 0x80) {
                    uint32_t cp = ((c & 0x1F) << 6) | (c2 & 0x3F);
                    if (cp >= 0x80) {
                        utf16.push_back(static_cast<jchar>(cp));
                        i += 2;
                        continue;
                    }
                }
            }
            // Invalid or truncated 2-byte sequence -> replacement char
            utf16.push_back(0xFFFD);
            i++;
        } else if ((c & 0xF0) == 0xE0) {
            if (i + 2 < len) {
                uint8_t c2 = static_cast<uint8_t>(utf8[i + 1]);
                uint8_t c3 = static_cast<uint8_t>(utf8[i + 2]);
                if ((c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                    uint32_t cp = ((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                    if (cp >= 0x0800 && (cp < 0xD800 || cp > 0xDFFF)) {
                        utf16.push_back(static_cast<jchar>(cp));
                        i += 3;
                        continue;
                    }
                }
            }
            // Invalid or truncated 3-byte sequence
            utf16.push_back(0xFFFD);
            i++;
        } else if ((c & 0xF8) == 0xF0) {
            if (i + 3 < len) {
                uint8_t c2 = static_cast<uint8_t>(utf8[i + 1]);
                uint8_t c3 = static_cast<uint8_t>(utf8[i + 2]);
                uint8_t c4 = static_cast<uint8_t>(utf8[i + 3]);
                if ((c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80 && (c4 & 0xC0) == 0x80) {
                    uint32_t cp = ((c & 0x07) << 18) | ((c2 & 0x3F) << 12) | ((c3 & 0x3F) << 6) | (c4 & 0x3F);
                    if (cp >= 0x10000 && cp <= 0x10FFFF) {
                        // UTF-16 surrogate pair for SMP characters (emojis, math symbols, etc.)
                        cp -= 0x10000;
                        utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
                        utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
                        i += 4;
                        continue;
                    }
                }
            }
            // Invalid or truncated 4-byte sequence
            utf16.push_back(0xFFFD);
            i++;
        } else {
            // Stray continuation byte or invalid byte
            utf16.push_back(0xFFFD);
            i++;
        }
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

static jstring safe_new_string(JNIEnv *env, const char *utf8) {
    if (!utf8) return nullptr;
    return safe_new_string(env, utf8, strlen(utf8));
}

static jstring safe_new_string(JNIEnv *env, const std::string & str) {
    return safe_new_string(env, str.data(), str.size());
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
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    if (!model_path) return nullptr;

    ensure_logging_and_backend_initialized();

    struct gguf_init_params params = {
        /*.no_alloc =*/ true,
        /*.ctx      =*/ nullptr,
    };

    struct gguf_context * ctx = gguf_init_from_file(model_path, params);
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
        env->ReleaseStringUTFChars(model_path_str, model_path);
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
    env->ReleaseStringUTFChars(model_path_str, model_path);

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
        jobject /* thiz */,
        jstring model_path_str,
        jint n_threads,
        jint n_ctx,
        jint n_batch,
        jint n_ubatch,
        jint backend,
        jint n_gpu_layers) {

    if (model_path_str == nullptr) {
        LOGE("nativeInitModel called with null model path");
        return 0;
    }

    ensure_logging_and_backend_initialized();

    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error_log.clear();
    }

    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    if (!model_path) {
        return 0;
    }

    int requested_backend = backend;
    int active_backend = SOLUS_BACKEND_CPU;
    int effective_gpu_layers = 0;

    LOGI("SolusLlamaJNI: Initializing GGUF model: %s (threads: %d, ctx: %d, batch: %d/%d, backend: %d, gpu_layers: %d)",
         model_path, n_threads, n_ctx, n_batch, n_ubatch, backend, n_gpu_layers);

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;

    if (requested_backend == SOLUS_BACKEND_VULKAN) {
        if (is_vulkan_supported_internal()) {
            effective_gpu_layers = (n_gpu_layers > 0) ? n_gpu_layers : 99;
            mparams.n_gpu_layers = effective_gpu_layers;
            LOGI("SolusLlamaJNI: Vulkan backend requested. Offloading %d layers to GPU", effective_gpu_layers);
        } else {
            LOGW("SolusLlamaJNI: Vulkan backend requested, but no Vulkan devices available. Safely falling back to CPU.");
            mparams.n_gpu_layers = 0;
            effective_gpu_layers = 0;
            requested_backend = SOLUS_BACKEND_CPU;
        }
    } else {
        mparams.n_gpu_layers = 0;
        effective_gpu_layers = 0;
        LOGI("SolusLlamaJNI: CPU backend requested (0 GPU layers). Preserving CPU pipeline.");
    }

    llama_model * model = llama_model_load_from_file(model_path, mparams);
    if (!model && mparams.use_mmap) {
        LOGW("SolusLlamaJNI: Failed to load GGUF model with mmap=true, retrying with mmap=false: %s", model_path);
        mparams.use_mmap = false;
        model = llama_model_load_from_file(model_path, mparams);
    }

    // Safe Vulkan Fallback: If loading failed on Vulkan GPU, fallback to CPU!
    if (!model && mparams.n_gpu_layers > 0) {
        LOGW("SolusLlamaJNI: Failed to load GGUF model with Vulkan GPU backend. Safely falling back to CPU backend!");
        mparams.n_gpu_layers = 0;
        effective_gpu_layers = 0;
        mparams.use_mmap = true;
        model = llama_model_load_from_file(model_path, mparams);
        if (!model) {
            mparams.use_mmap = false;
            model = llama_model_load_from_file(model_path, mparams);
        }
    }

    if (!model) {
        std::string err_detail;
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            err_detail = g_last_error_log.empty() ? "unsupported architecture, missing tensors, or invalid GGUF format" : g_last_error_log;
        }
        LOGE("SolusLlamaJNI: Failed to load GGUF model from path %s: %s", model_path, err_detail.c_str());
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_last_error_log = "GGUF load failed: " + err_detail;
        }
        env->ReleaseStringUTFChars(model_path_str, model_path);
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("SolusLlamaJNI: Failed to get vocabulary from GGUF model: %s", model_path);
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_last_error_log = "Failed to extract vocabulary from GGUF model";
        }
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_str, model_path);
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

    uint32_t eff_batch = n_batch > 0 ? static_cast<uint32_t>(n_batch) : 256;
    uint32_t eff_ubatch = n_ubatch > 0 ? static_cast<uint32_t>(n_ubatch) : 128;
    if (eff_batch > static_cast<uint32_t>(effective_ctx)) eff_batch = static_cast<uint32_t>(effective_ctx);
    if (eff_ubatch > eff_batch) eff_ubatch = eff_batch;

    auto *holder = new LlamaContextHolder();
    holder->model = model;
    holder->vocab = vocab;
    holder->model_path = model_path;
    holder->n_threads = effective_threads;
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

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(effective_ctx);
    cparams.n_batch = eff_batch;
    cparams.n_ubatch = eff_ubatch;
    cparams.n_threads = effective_threads;
    cparams.n_threads_batch = effective_threads;
    cparams.abort_callback = llama_jni_abort_callback;
    cparams.abort_callback_data = holder;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx && mparams.n_gpu_layers > 0) {
        LOGW("SolusLlamaJNI: Failed to create context with Vulkan backend. Safely falling back to CPU backend!");
        llama_model_free(model);
        model = nullptr;
        vocab = nullptr;
        mparams.n_gpu_layers = 0;
        effective_gpu_layers = 0;
        mparams.use_mmap = true;
        model = llama_model_load_from_file(model_path, mparams);
        if (!model) {
            mparams.use_mmap = false;
            model = llama_model_load_from_file(model_path, mparams);
        }
        if (model) {
            vocab = llama_model_get_vocab(model);
            holder->model = model;
            holder->vocab = vocab;
            ctx = llama_init_from_model(model, cparams);
        }
    }

    if (!ctx && effective_ctx > 1024) {
        LOGW("SolusLlamaJNI: Failed to allocate context size %d, retrying with safe context 1024", effective_ctx);
        effective_ctx = 1024;
        eff_batch = std::min(eff_batch, 128u);
        eff_ubatch = std::min(eff_ubatch, 64u);
        cparams.n_ctx = 1024;
        cparams.n_batch = eff_batch;
        cparams.n_ubatch = eff_ubatch;
        ctx = llama_init_from_model(model, cparams);
    }

    if (!ctx) {
        std::string err_detail;
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            err_detail = g_last_error_log.empty() ? "insufficient memory for KV cache or context allocation" : g_last_error_log;
        }
        LOGE("SolusLlamaJNI: Failed to create llama_context for model %s: %s", model_path, err_detail.c_str());
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_last_error_log = "Context creation failed: " + err_detail;
        }
        llama_model_free(model);
        delete holder;
        env->ReleaseStringUTFChars(model_path_str, model_path);
        return 0;
    }

    active_backend = (mparams.n_gpu_layers > 0) ? SOLUS_BACKEND_VULKAN : SOLUS_BACKEND_CPU;
    holder->active_backend = active_backend;
    holder->n_gpu_layers = effective_gpu_layers;
    holder->ctx = ctx;
    llama_set_abort_callback(ctx, llama_jni_abort_callback, holder);

    LOGI("SolusLlamaJNI: Successfully initialized GGUF model on %s backend: %s (ctx=%d, threads=%d, batch=%d/%d, gpu_layers=%d, vocab_size=%d)",
         (active_backend == SOLUS_BACKEND_VULKAN ? "Vulkan GPU" : "CPU"),
         model_path, effective_ctx, effective_threads, eff_batch, eff_ubatch, effective_gpu_layers, llama_vocab_n_tokens(vocab));

    env->ReleaseStringUTFChars(model_path_str, model_path);
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

    // 1. Format prompt using model's native chat template
    std::string formatted_prompt;
    bool applied_chat_template = false;

    std::vector<std::string> roles_storage;
    std::vector<std::string> contents_storage;
    std::vector<llama_chat_message> chat_msgs;

    if (roles_array != nullptr && contents_array != nullptr) {
        jsize msg_count = env->GetArrayLength(roles_array);
        jsize content_count = env->GetArrayLength(contents_array);

        if (msg_count > 0 && msg_count == content_count) {
            roles_storage.reserve(msg_count);
            contents_storage.reserve(msg_count);
            chat_msgs.reserve(msg_count);

            for (jsize i = 0; i < msg_count; ++i) {
                auto role_obj = (jstring)env->GetObjectArrayElement(roles_array, i);
                auto content_obj = (jstring)env->GetObjectArrayElement(contents_array, i);

                std::string r_val = "user";
                std::string c_val = "";

                if (role_obj != nullptr) {
                    const char *r_chars = env->GetStringUTFChars(role_obj, nullptr);
                    if (r_chars != nullptr) {
                        r_val = r_chars;
                        env->ReleaseStringUTFChars(role_obj, r_chars);
                    }
                    env->DeleteLocalRef(role_obj);
                }

                if (content_obj != nullptr) {
                    const char *c_chars = env->GetStringUTFChars(content_obj, nullptr);
                    if (c_chars != nullptr) {
                        c_val = c_chars;
                        env->ReleaseStringUTFChars(content_obj, c_chars);
                    }
                    env->DeleteLocalRef(content_obj);
                }

                roles_storage.push_back(r_val);
                contents_storage.push_back(c_val);
                chat_msgs.push_back({ roles_storage.back().c_str(), contents_storage.back().c_str() });
            }
        }
    }

    // If chat_msgs is empty, synthesize a single user message from prompt_str
    if (chat_msgs.empty() && prompt_str != nullptr) {
        const char *p_chars = env->GetStringUTFChars(prompt_str, nullptr);
        std::string raw_p = p_chars ? p_chars : "";
        if (p_chars) {
            env->ReleaseStringUTFChars(prompt_str, p_chars);
        }

        if (!raw_p.empty()) {
            roles_storage.push_back("user");
            contents_storage.push_back(raw_p);
            chat_msgs.push_back({ roles_storage.back().c_str(), contents_storage.back().c_str() });
        }
    }

    const llama_vocab * vocab = holder->vocab;
    const char * model_tmpl = llama_model_chat_template(holder->model, nullptr);

    // ── Llama 3 / 3.1 / 3.2 Instruct detection ──────────────────────────────────
    // Detect Llama 3 by:
    // 1. Chat template keywords ("<|start_header_id|>", "llama3")
    // 2. Or presence of control token "<|start_header_id|>" in the model vocabulary
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

    if (!chat_msgs.empty()) {
        if (is_llama3) {
            // Check if the caller already provided a system turn.
            bool has_system = false;
            for (const auto & msg : chat_msgs) {
                if (msg.role && std::string(msg.role) == "system") {
                    has_system = true;
                    break;
                }
            }
            if (!has_system) {
                // Inject a minimal system prompt at position 0.
                // This is required for Llama 3.2 Instruct to produce coherent English output.
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
                LOGW("SolusLlamaJNI: Model template rejects system role (confirmed by retry); "
                     "merging system prompt into first user turn");
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
            } else {
                LOGE("SolusLlamaJNI: Chat template failed for this model and the failure is not "
                     "isolated to the system role (tmpl_res=%d). "
                     "Model may use an unsupported or malformed template.", tmpl_res);
            }
        }

        if (tmpl_res > 0) {
            formatted_prompt = std::string(tmpl_buf.data(), tmpl_res);
            applied_chat_template = true;
            LOGI("SolusLlamaJNI: Applied model chat template (%d bytes)", tmpl_res);
        }
    }

    if (!applied_chat_template) {
        if (prompt_str != nullptr) {
            const char *p_chars = env->GetStringUTFChars(prompt_str, nullptr);
            formatted_prompt = p_chars ? p_chars : "";
            if (p_chars) {
                env->ReleaseStringUTFChars(prompt_str, p_chars);
            }
        }
    }

    if (formatted_prompt.empty()) {
        LOGE("SolusLlamaJNI: Prompt is empty");
        if (on_error_method) {
            call_jni_string_method_checked(env, callback_obj, on_error_method, "Prompt is empty");
        }
        return -1;
    }

    // 2. Tokenize prompt
    // Use add_special=false, parse_special=true so special tokens in the template
    // (such as <|start_header_id|>, <|eot_id|>, etc.) are tokenized as their control IDs,
    // while preventing the tokenizer from automatically appending an unwanted EOS token
    // to the end of the prompt (which would close generation prematurely).
    const bool add_special = false;
    const bool parse_special = true;
    int n_tokens_req = -llama_tokenize(vocab, formatted_prompt.c_str(), formatted_prompt.length(), nullptr, 0, add_special, parse_special);
    if (n_tokens_req <= 0) {
        n_tokens_req = static_cast<int>(formatted_prompt.length() + 8);
    }

    std::vector<llama_token> prompt_tokens(n_tokens_req);
    int n_tokens = llama_tokenize(
            vocab,
            formatted_prompt.c_str(),
            formatted_prompt.length(),
            prompt_tokens.data(),
            prompt_tokens.size(),
            add_special,
            parse_special
    );

    if (n_tokens < 0) {
        prompt_tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
                vocab,
                formatted_prompt.c_str(),
                formatted_prompt.length(),
                prompt_tokens.data(),
                prompt_tokens.size(),
                add_special,
                parse_special
        );
    }

    if (n_tokens <= 0) {
        LOGE("SolusLlamaJNI: Failed to tokenize prompt");
        if (on_error_method) {
            call_jni_string_method_checked(env, callback_obj, on_error_method, "Failed to tokenize prompt");
        }
        return -1;
    }

    prompt_tokens.resize(n_tokens);
    LOGI("SolusLlamaJNI: [PHASE: PROMPT_TOKENIZED] Tokenized prompt into %d tokens", n_tokens);

    uint32_t ctx_size = llama_n_ctx(holder->ctx);
    uint32_t max_prompt_tokens = ctx_size > 16 ? ctx_size - 16 : (ctx_size > 2 ? ctx_size - 2 : 1);
    if (prompt_tokens.size() > max_prompt_tokens && max_prompt_tokens > 0) {
        size_t tokens_to_drop = prompt_tokens.size() - max_prompt_tokens;
        LOGW("SolusLlamaJNI: Prompt length (%zu tokens) exceeds context limit (%u tokens), truncating %zu oldest tokens",
             prompt_tokens.size(), max_prompt_tokens, tokens_to_drop);
        prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.begin() + tokens_to_drop);
    }

    // ── Ensure BOS (Beginning-Of-Sentence) token at position 0 ──────────────────
    // Models like Llama 3, Gemma, Mistral, Llama 2 require their BOS token at position 0
    // to calibrate attention sinks and RoPE positional offsets.
    // llama_chat_apply_template does NOT emit BOS in its string output because llama.cpp CLI
    // typically tokenizes with add_special=true.
    // Since we tokenize with add_special=false (to prevent unwanted EOS tokens at prompt end),
    // we must explicitly ensure position 0 contains the model's BOS token.
    llama_token bos = llama_vocab_bos(vocab);
    bool should_add_bos = llama_vocab_get_add_bos(vocab) || is_llama3;
    if (should_add_bos && bos != LLAMA_TOKEN_NULL) {
        if (prompt_tokens.empty() || prompt_tokens[0] != bos) {
            prompt_tokens.insert(prompt_tokens.begin(), bos);
            LOGI("SolusLlamaJNI: Prepended model BOS token (%d)", bos);
        }
    }

    // Deduplicate any consecutive leading BOS tokens (if template or caller already included one)
    while (prompt_tokens.size() >= 2 && prompt_tokens[0] == bos && prompt_tokens[1] == bos && bos != LLAMA_TOKEN_NULL) {
        LOGW("SolusLlamaJNI: Detected duplicate leading BOS token (%d), removing duplicate", bos);
        prompt_tokens.erase(prompt_tokens.begin());
    }

    // Diagnostic logging: log prompt details and first tokens
    {
        std::string token_ids_str;
        for (size_t i = 0; i < std::min<size_t>(prompt_tokens.size(), 10); ++i) {
            token_ids_str += std::to_string(prompt_tokens[i]) + " ";
        }
        LOGI("SolusLlamaJNI: Prompt token count=%zu, first tokens: [%s]",
             prompt_tokens.size(), token_ids_str.c_str());
    }

    llama_kv_cache_clear(holder->ctx);

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
    int batch_size = holder->n_batch > 0 ? holder->n_batch : 256;
    lease_guard.batch = llama_batch_init(batch_size, 0, 1);
    lease_guard.batch_initialized = true;
    llama_batch & batch = lease_guard.batch;
    std::vector<llama_seq_id> seq_ids = { 0 };

    LOGI("SolusLlamaJNI: [PHASE: PROMPT_DECODE_STARTED] Evaluating %zu prompt tokens (batch_size=%d, ctx=%u)",
         prompt_tokens.size(), batch_size, ctx_size);

    bool aborted = false;
    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
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
                    aborted = true;
                } else {
                    LOGE("SolusLlamaJNI: Generation failed: llama_decode failed during prompt processing at token %zu", i);
                    llama_kv_cache_clear(holder->ctx);
                    if (on_error_method) {
                        call_jni_string_method_checked(env, callback_obj, on_error_method, "llama_decode failed during prompt processing");
                    }
                    return -1;
                }
                break;
            }
            // [NUMERICAL_AUDIT] Check for NaN/Inf in logits immediately after decode.
            // Only runs full scan if any non-finite value is detected (zero cost otherwise).
            if (is_last) {
                audit_logits_numerics(holder->ctx, "PROMPT_DECODE", static_cast<int>(i));
            }
            batch.n_tokens = 0;
        }
    }

    if (aborted) {
        llama_kv_cache_clear(holder->ctx);
        LOGI("SolusLlamaJNI: [PHASE: END] Generation stopped during prompt evaluation");
        if (on_stop_method) {
            call_jni_void_method_checked(env, callback_obj, on_stop_method);
        }
        return 1;
    }

    LOGI("SolusLlamaJNI: [PHASE: PROMPT_DECODE_FINISHED] Prompt evaluation complete (%zu tokens)", prompt_tokens.size());

    // 5. Autoregressive token generation loop
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
            aborted = true;
            break;
        }

        llama_token token = llama_sampler_sample(smpl, holder->ctx, -1);
        // NOTE: do NOT call llama_sampler_accept here. llama_sampler_sample() already
        // calls llama_sampler_accept internally (see llama-sampling.cpp). Calling it
        // again doubles every token's repetition penalty, corrupting the sampler state
        // and causing gibberish / out-of-distribution output.

        if (llama_vocab_is_eog(vocab, token) || token == eos_token || token == eot_token ||
            (is_llama3 && llama_vocab_is_control(vocab, token))) {
            LOGI("SolusLlamaJNI: Generation completed at step %d (EOG/EOS/Control token %d)", n_generated, token);
            break;
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
                aborted = true;
                break;
            }
        }

        batch.n_tokens = 0;
        batch_add(batch, token, static_cast<llama_pos>(n_cur), seq_ids, true);

        if (llama_decode(holder->ctx, batch) != 0) {
            if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
                LOGI("SolusLlamaJNI: Decode aborted during token generation at step %d via stop request", n_generated);
                aborted = true;
            } else {
                LOGE("SolusLlamaJNI: Generation failed: llama_decode failed at generation step %d", n_generated);
                llama_kv_cache_clear(holder->ctx);
                if (on_error_method) {
                    call_jni_string_method_checked(env, callback_obj, on_error_method, "llama_decode failed during token generation");
                }
                return -1;
            }
            break;
        }

        // [NUMERICAL_AUDIT] Audit logits for the first 8 generated tokens only.
        // After that, if values are finite the check is not needed on every step.
        if (n_generated < 8) {
            audit_logits_numerics(holder->ctx, "TOKEN_GENERATION", n_cur);
        }

        n_cur++;
        n_generated++;
    }

    if (!utf8_stream_buf.empty() && !aborted && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
        call_jni_string_method_checked(env, callback_obj, on_token_method, utf8_stream_buf.c_str());
    }

    if (aborted) {
        llama_kv_cache_clear(holder->ctx);
        LOGI("SolusLlamaJNI: [PHASE: END] Generation stopped after %d tokens", n_generated);
        if (on_stop_method) {
            call_jni_void_method_checked(env, callback_obj, on_stop_method);
        }
        return 1;
    }

    LOGI("SolusLlamaJNI: [PHASE: END] Generation completed successfully (%d tokens)", n_generated);
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
         << "\"n_gpu_layers\":" << holder->n_gpu_layers
         << "}";

    return safe_new_string(env, json.str());
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

} // extern "C"

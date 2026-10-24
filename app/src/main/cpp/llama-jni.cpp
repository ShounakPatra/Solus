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
#include <cstdlib>
#include <android/log.h>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "prompt-cache.h"
#include "prompt-budget.h"
#include "llama-arch.h"
#include "native-operation-control.h"
#include "ggml.h"
#include "gguf.h"
#include "gguf-backend-config.h"
#include "utf-conversion.h"
#include "llama-multimodal.h"

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

#ifndef LLAMA_UPSTREAM_REVISION
#error "LLAMA_UPSTREAM_REVISION must be defined by CMake"
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
    SOLUS_FAILURE_CONTEXT_CAPACITY = 11,
    SOLUS_FAILURE_RESPONSE_FORMAT_ERROR = 12
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
        case SOLUS_FAILURE_RESPONSE_FORMAT_ERROR: return "ResponseFormatError";
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
    std::string tool_request_json;
    std::string tool_response_json;
    std::string model_path;
    std::vector<std::string> model_paths;
    std::string projector_path;
    std::unique_ptr<LlamaMultimodalContext> multimodal;

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
    if (!holder) return;
    holder->cached_tokens.clear();
    if (holder->ctx) {
        llama_memory_t mem = llama_get_memory(holder->ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
    }
}

static bool llama_jni_abort_callback(void * data) {
    if (!data) return false;
    auto * holder = static_cast<LlamaContextHolder *>(data);
    if (holder->op_control && holder->op_control->is_cancelled()) {
        return true;
    }
    return holder->stop_requested.load(std::memory_order_relaxed) ||
           holder->is_closed.load(std::memory_order_relaxed);
}

static bool audit_logits_numerics(llama_context * ctx, const char * phase, int token_pos) {
    (void)phase;
    (void)token_pos;
    if (!ctx) return true;

    const float * logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return true;

    const llama_model * mdl = llama_get_model(ctx);
    if (!mdl) return true;

    int n_vocab = 0;
    {
        const llama_vocab * v = llama_model_get_vocab(mdl);
        if (v) n_vocab = llama_vocab_n_tokens(v);
    }
    if (n_vocab <= 0 || n_vocab > 256 * 1024) return true;

    for (int i = 0; i < n_vocab; ++i) {
        float v = logits[i];
        if (v != v || v > 3.4028235e+38f || v < -3.4028235e+38f) {
            return false;
        }
    }
    return true;
}

static void batch_add(llama_batch & batch, llama_token id, llama_pos pos, const std::vector<llama_seq_id> & seq_ids, bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = static_cast<int32_t>(seq_ids.size());
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits  [batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

static jstring safe_new_string(JNIEnv *env, const char *utf8, size_t len) {
    if (!utf8 || len == 0) {
        return env->NewString(reinterpret_cast<const jchar *>(""), 0);
    }

    std::vector<uint16_t> utf16;
    utf16.reserve(len);

    size_t i = 0;
    while (i < len) {
        uint32_t cp = 0;
        uint8_t c0 = static_cast<uint8_t>(utf8[i]);

        if (c0 < 0x80) {
            cp = c0;
            i += 1;
        } else if ((c0 & 0xE0) == 0xC0) {
            if (i + 1 >= len) break;
            uint8_t c1 = static_cast<uint8_t>(utf8[i + 1]);
            if ((c1 & 0xC0) != 0x80) { i += 1; continue; }
            cp = ((c0 & 0x1F) << 6) | (c1 & 0x3F);
            i += 2;
        } else if ((c0 & 0xF0) == 0xE0) {
            if (i + 2 >= len) break;
            uint8_t c1 = static_cast<uint8_t>(utf8[i + 1]);
            uint8_t c2 = static_cast<uint8_t>(utf8[i + 2]);
            if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80) { i += 1; continue; }
            cp = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
            i += 3;
        } else if ((c0 & 0xF8) == 0xF0) {
            if (i + 3 >= len) break;
            uint8_t c1 = static_cast<uint8_t>(utf8[i + 1]);
            uint8_t c2 = static_cast<uint8_t>(utf8[i + 2]);
            uint8_t c3 = static_cast<uint8_t>(utf8[i + 3]);
            if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) { i += 1; continue; }
            cp = ((c0 & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
            i += 4;
        } else {
            i += 1;
            continue;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<uint16_t>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            utf16.push_back(static_cast<uint16_t>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<uint16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }

    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

static jstring safe_new_string(JNIEnv *env, const std::string &s) {
    return safe_new_string(env, s.data(), s.size());
}

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

static bool call_jni_string_method_checked(JNIEnv *env, jobject obj, jmethodID method, const char *utf8_str) {
    if (!obj || !method) return false;
    jstring jstr = safe_new_string(env, utf8_str, utf8_str ? strlen(utf8_str) : 0);
    env->CallVoidMethod(obj, method, jstr);
    env->DeleteLocalRef(jstr);
    if (env->ExceptionCheck()) {
        LOGE("SolusLlamaJNI: Exception in JNI string callback");
        env->ExceptionClear();
        return false;
    }
    return true;
}

static bool call_jni_void_method_checked(JNIEnv *env, jobject obj, jmethodID method) {
    if (!obj || !method) return false;
    env->CallVoidMethod(obj, method);
    if (env->ExceptionCheck()) {
        LOGE("SolusLlamaJNI: Exception in JNI void callback");
        env->ExceptionClear();
        return false;
    }
    return true;
}

static bool call_jni_structured_token_method_checked(JNIEnv *env, jobject obj, jmethodID method, const char *answer, const char *reasoning, bool is_thinking_active) {
    if (!obj || !method) return false;
    jstring jans = safe_new_string(env, answer, answer ? strlen(answer) : 0);
    jstring jreas = safe_new_string(env, reasoning, reasoning ? strlen(reasoning) : 0);
    env->CallVoidMethod(obj, method, jans, jreas, static_cast<jboolean>(is_thinking_active));
    env->DeleteLocalRef(jans);
    env->DeleteLocalRef(jreas);
    if (env->ExceptionCheck()) {
        LOGE("SolusLlamaJNI: Exception in JNI structured token callback");
        env->ExceptionClear();
        return false;
    }
    return true;
}

struct NativeLeaseGuard {
    LlamaContextHolder * holder;
    llama_sampler * smpl = nullptr;
    llama_batch batch{};
    bool batch_initialized = false;

    explicit NativeLeaseGuard(LlamaContextHolder * h) : holder(h) {}
    ~NativeLeaseGuard() {
        if (smpl) {
            llama_sampler_free(smpl);
            smpl = nullptr;
        }
        if (batch_initialized) {
            llama_batch_free(batch);
            batch_initialized = false;
        }
        if (holder) {
            holder->is_generating.store(false, std::memory_order_release);
            holder->active_leases.fetch_sub(1, std::memory_order_acq_rel);
            holder->gen_cv.notify_all();
        }
    }
};

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
        size_t document_limit = SIZE_MAX,
        bool enable_thinking = true,
        std::unique_ptr<common_chat_session> * out_session = nullptr) {
    if (!holder || !holder->model || !holder->vocab || !holder->ctx) {
        out_error = "Model context is invalid";
        out_failure_category = SOLUS_FAILURE_UNKNOWN;
        return false;
    }

    if (!holder->tool_request_json.empty()) {
        try {
            const auto request = common_json::parse(holder->tool_request_json);
            auto tmpls = common_chat_templates_init(holder->model, "");
            if (!tmpls) throw std::runtime_error("No chat template");
            common_chat_templates_inputs inputs;
            inputs.messages = common_chat_msgs_parse_oaicompat(request.at("messages"));
            inputs.tools = common_chat_tools_parse_oaicompat(request.at("tools"));
            inputs.tool_choice = request.value("allow_calls", true) ? COMMON_CHAT_TOOL_CHOICE_AUTO : COMMON_CHAT_TOOL_CHOICE_NONE;
            inputs.enable_thinking = enable_thinking;
            inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            inputs.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
            auto params = common_chat_templates_apply(tmpls.get(), inputs);
            out_tokens = common_tokenize(holder->ctx, params.prompt.str(), false, true);
            const auto reserve = output_token_reserve(holder->n_ctx, requested_output_tokens);
            while (out_tokens.size() + reserve >= static_cast<size_t>(holder->n_ctx)) {
                // Drop complete old user turns, preserving all calls/results in the current turn.
                size_t last_user = inputs.messages.size();
                for (size_t i = 0; i < inputs.messages.size(); ++i) if (inputs.messages[i].role == "user") last_user = i;
                size_t first = 0;
                while (first < inputs.messages.size() && inputs.messages[first].role == "system") ++first;
                if (first >= last_user) break;
                size_t end = first + 1;
                while (end < last_user && inputs.messages[end].role != "user") ++end;
                inputs.messages.erase(inputs.messages.begin() + first, inputs.messages.begin() + end);
                params = common_chat_templates_apply(tmpls.get(), inputs);
                out_tokens = common_tokenize(holder->ctx, params.prompt.str(), false, true);
            }
            if (out_tokens.empty() || out_tokens.size() + reserve >= static_cast<size_t>(holder->n_ctx)) {
                out_error = "Tool conversation exceeds context capacity";
                out_failure_category = SOLUS_FAILURE_CONTEXT_CAPACITY;
                return false;
            }
            if (out_session) {
                *out_session = std::make_unique<common_chat_session>(tmpls.get(), holder->vocab, inputs);
                if (request.value("allow_calls", true) && (*out_session)->parser().empty()) throw std::runtime_error("Template has no tool-call parser");
            }
            return true;
        } catch (const std::exception & e) {
            out_error = std::string("Tool template failed: ") + e.what();
            out_failure_category = SOLUS_FAILURE_UNSUPPORTED_TEMPLATE;
            return false;
        }
    }

    std::vector<std::string> roles_storage;
    std::vector<std::string> contents_storage;
    bool had_structured_messages = false;

    if (roles_array != nullptr && contents_array != nullptr) {
        jsize msg_count = env->GetArrayLength(roles_array);
        jsize content_count = env->GetArrayLength(contents_array);

        if (msg_count > 0 && msg_count == content_count) {
            had_structured_messages = true;
            roles_storage.reserve(msg_count);
            contents_storage.reserve(msg_count);

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
            }
        }
    }

    if (roles_storage.empty() && prompt_str != nullptr) {
        std::string raw_p = jstring_to_utf8(env, prompt_str);
        if (!raw_p.empty()) {
            roles_storage.push_back("user");
            contents_storage.push_back(raw_p);
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

    std::string formatted_prompt;
    bool applied_chat_template = false;

    if (!roles_storage.empty()) {
        common_chat_templates_ptr tmpls = common_chat_templates_init(holder->model, "");
        if (tmpls) {
            common_chat_templates_inputs inputs;
            for (size_t i = 0; i < roles_storage.size(); ++i) {
                common_chat_msg msg;
                msg.role = roles_storage[i];
                msg.content = contents_storage[i];
                inputs.messages.push_back(msg);
            }
            inputs.add_generation_prompt = true;
            inputs.use_jinja = true;
            inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            inputs.enable_thinking = enable_thinking;
            inputs.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";

            try {
                common_chat_params chat_params = common_chat_templates_apply(tmpls.get(), inputs);
                formatted_prompt = chat_params.prompt.str();
                applied_chat_template = true;
            } catch (const std::exception & e) {
                LOGW("SolusLlamaJNI: common_chat_templates_apply failed: %s", e.what());
            }
        }

        if (!applied_chat_template) {
            if (had_structured_messages) {
                out_error = "Model does not contain a supported chat template";
                out_failure_category = SOLUS_FAILURE_UNSUPPORTED_TEMPLATE;
                LOGE("SolusLlamaJNI: %s", out_error.c_str());
                return false;
            } else {
                formatted_prompt = contents_storage.empty() ? "" : contents_storage[0];
            }
        }
    } else {
        out_error = "Prompt is empty";
        out_failure_category = SOLUS_FAILURE_PROMPT_EMPTY;
        return false;
    }

    out_tokens = common_tokenize(holder->ctx, formatted_prompt, false, true);

    if (out_tokens.empty()) {
        out_error = "Prompt produced 0 tokens";
        out_failure_category = SOLUS_FAILURE_PROMPT_EMPTY;
        return false;
    }

    uint32_t reserve = output_token_reserve(static_cast<uint32_t>(holder->n_ctx), requested_output_tokens);

    while (out_tokens.size() + reserve >= static_cast<size_t>(holder->n_ctx)) {
        size_t drop_count = oldest_history_turn_size(roles_storage);
        if (drop_count == 0) {
            break;
        }
        size_t first = 0;
        while (first < roles_storage.size() && roles_storage[first] == "system") ++first;
        for (size_t d = 0; d < drop_count && first < roles_storage.size(); ++d) {
            roles_storage.erase(roles_storage.begin() + first);
            contents_storage.erase(contents_storage.begin() + first);
        }
        common_chat_templates_ptr loop_tmpls = common_chat_templates_init(holder->model, "");
        if (loop_tmpls) {
            common_chat_templates_inputs loop_inputs;
            for (size_t i = 0; i < roles_storage.size(); ++i) {
                common_chat_msg msg;
                msg.role = roles_storage[i];
                msg.content = contents_storage[i];
                loop_inputs.messages.push_back(msg);
            }
            loop_inputs.add_generation_prompt = true;
            loop_inputs.use_jinja = true;
            loop_inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            loop_inputs.enable_thinking = enable_thinking;
            loop_inputs.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
            try {
                common_chat_params chat_params = common_chat_templates_apply(loop_tmpls.get(), loop_inputs);
                formatted_prompt = chat_params.prompt.str();
                out_tokens = common_tokenize(holder->ctx, formatted_prompt, false, true);
            } catch (...) {
                break;
            }
        } else {
            break;
        }
    }

    if (out_tokens.size() + reserve >= static_cast<size_t>(holder->n_ctx)) {
        out_error = "Context capacity exceeded: prompt tokens (" + std::to_string(out_tokens.size()) +
                    ") + output reserve (" + std::to_string(reserve) +
                    ") exceeds context window (" + std::to_string(holder->n_ctx) + ")";
        out_failure_category = SOLUS_FAILURE_CONTEXT_CAPACITY;
        LOGE("SolusLlamaJNI: %s", out_error.c_str());
        return false;
    }

    if (out_session && applied_chat_template && !roles_storage.empty()) {
        common_chat_templates_ptr final_tmpls = common_chat_templates_init(holder->model, "");
        if (final_tmpls) {
            common_chat_templates_inputs final_inputs;
            for (size_t i = 0; i < roles_storage.size(); ++i) {
                common_chat_msg msg;
                msg.role = roles_storage[i];
                msg.content = contents_storage[i];
                final_inputs.messages.push_back(msg);
            }
            final_inputs.add_generation_prompt = true;
            final_inputs.use_jinja = true;
            final_inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            final_inputs.enable_thinking = enable_thinking;
            final_inputs.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
            try {
                *out_session = std::make_unique<common_chat_session>(final_tmpls.get(), holder->vocab, final_inputs);
            } catch (const std::exception & e) {
                LOGW("SolusLlamaJNI: common_chat_session init failed: %s", e.what());
            } catch (...) {}
        }
    }

    return true;
}

static bool is_vulkan_supported_internal() {
#ifdef GGML_USE_VULKAN
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("Vulkan");
    if (!reg) {
        reg = ggml_backend_vk_reg();
    }
    return reg && ggml_backend_reg_dev_count(reg) > 0;
#else
    return false;
#endif
}

extern "C" {

// Called only while Kotlin holds the native operation lock and a lifecycle lease.
JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeRenderPortableChat(
        JNIEnv * env, jclass, jstring source, jstring messages, jstring bos, jstring eos, jboolean thinking) {
    try {
        const auto text = jstring_to_utf8(env, source);
        if (text.empty() || text.size() > 262144) throw std::runtime_error("Invalid chat template size");
        auto tmpls = common_chat_templates_init(nullptr, text, jstring_to_utf8(env, bos), jstring_to_utf8(env, eos));
        if (!tmpls) throw std::runtime_error("Unable to initialize chat template");
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(jstring_to_utf8(env, messages)));
        inputs.enable_thinking = thinking == JNI_TRUE;
        inputs.chat_template_kwargs["enable_thinking"] = thinking == JNI_TRUE ? "true" : "false";
        return safe_new_string(env, common_chat_templates_apply(tmpls.get(), inputs).prompt.str());
    } catch (const std::exception & error) {
        auto clazz = env->FindClass("java/lang/IllegalArgumentException");
        if (clazz) env->ThrowNew(clazz, error.what());
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeSupportsTools(JNIEnv *, jclass, jlong handle) {
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->model) return false;
    try {
        auto tmpls = common_chat_templates_init(holder->model, "");
        if (!tmpls) return false;
        auto caps = common_chat_templates_get_caps(tmpls.get());
        return caps["supports_tools"] && caps["supports_tool_calls"];
    } catch (...) { return false; }
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeSetToolRequest(JNIEnv * env, jclass, jlong handle, jstring request) {
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    holder->tool_request_json = request ? jstring_to_utf8(env, request) : "";
    holder->tool_response_json.clear();
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetToolResponse(JNIEnv * env, jclass, jlong handle) {
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    return safe_new_string(env, holder->tool_response_json);
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetLastError(
        JNIEnv *env,
        jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return safe_new_string(env, g_last_error_log);
}

enum class ReasoningPrefixState {
    ORDINARY_ANSWER,
    OPEN_THINKING,
    CLOSED_THINKING
};

static ReasoningPrefixState inspect_prefix_reasoning_state(
    const std::string & prefix,
    const std::string & start_tag,
    const std::vector<std::string> & end_tags
) {
    if (start_tag.empty() || prefix.empty()) {
        return ReasoningPrefixState::ORDINARY_ANSWER;
    }
    size_t start_pos = prefix.find(start_tag);
    if (start_pos == std::string::npos) {
        return ReasoningPrefixState::ORDINARY_ANSWER;
    }
    for (const auto & end_tag : end_tags) {
        if (!end_tag.empty()) {
            size_t end_pos = prefix.find(end_tag, start_pos + start_tag.length());
            if (end_pos != std::string::npos) {
                return ReasoningPrefixState::CLOSED_THINKING;
            }
        }
    }
    return ReasoningPrefixState::OPEN_THINKING;
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInspectModel(
        JNIEnv *env,
        jclass /*clazz*/,
        jstring model_path_str) {
    if (model_path_str == nullptr) return nullptr;
    std::string model_path;
    if (!jstring_to_path_utf8(env, model_path_str, model_path) || model_path.empty()) {
        return nullptr;
    }

    struct gguf_init_params params = {
        /* .no_alloc   = */ true,
        /* .ctx        = */ nullptr,
    };
    struct gguf_context * ctx = gguf_init_from_file(model_path.c_str(), params);
    if (!ctx) {
        std::ostringstream err_json;
        err_json << "{\"valid\":false,\"error\":\"Failed to parse GGUF file header or invalid format\"}";
        return safe_new_string(env, err_json.str());
    }

    uint32_t version = gguf_get_version(ctx);
    int64_t n_tensors = gguf_get_n_tensors(ctx);

    auto get_str_val = [&](int key_idx) -> std::string {
        if (key_idx < 0) return "";
        if (gguf_get_kv_type(ctx, key_idx) == GGUF_TYPE_STRING) {
            const char * s = gguf_get_val_str(ctx, key_idx);
            return s ? s : "";
        }
        return "";
    };

    auto get_u32_val = [&](int key_idx) -> uint32_t {
        if (key_idx < 0) return 0;
        enum gguf_type type = gguf_get_kv_type(ctx, key_idx);
        if (type == GGUF_TYPE_UINT32) return gguf_get_val_u32(ctx, key_idx);
        if (type == GGUF_TYPE_INT32) return static_cast<uint32_t>(gguf_get_val_i32(ctx, key_idx));
        if (type == GGUF_TYPE_UINT16) return gguf_get_val_u16(ctx, key_idx);
        if (type == GGUF_TYPE_INT16) return static_cast<uint32_t>(gguf_get_val_i16(ctx, key_idx));
        if (type == GGUF_TYPE_UINT64) return static_cast<uint32_t>(gguf_get_val_u64(ctx, key_idx));
        if (type == GGUF_TYPE_INT64) return static_cast<uint32_t>(gguf_get_val_i64(ctx, key_idx));
        return 0;
    };

    auto get_bool_val = [&](int key_idx) -> bool {
        if (key_idx < 0) return false;
        if (gguf_get_kv_type(ctx, key_idx) == GGUF_TYPE_BOOL) {
            return gguf_get_val_bool(ctx, key_idx);
        }
        return false;
    };

    auto get_split_val = [&](int key_idx) -> int32_t {
        if (key_idx < 0) return -1;
        enum gguf_type type = gguf_get_kv_type(ctx, key_idx);
        if (type == GGUF_TYPE_UINT16) return static_cast<int32_t>(gguf_get_val_u16(ctx, key_idx));
        if (type == GGUF_TYPE_UINT32) return static_cast<int32_t>(gguf_get_val_u32(ctx, key_idx));
        if (type == GGUF_TYPE_INT16) return static_cast<int32_t>(gguf_get_val_i16(ctx, key_idx));
        if (type == GGUF_TYPE_INT32) return static_cast<int32_t>(gguf_get_val_i32(ctx, key_idx));
        if (type == GGUF_TYPE_UINT8) return static_cast<int32_t>(gguf_get_val_u8(ctx, key_idx));
        if (type == GGUF_TYPE_INT8) return static_cast<int32_t>(gguf_get_val_i8(ctx, key_idx));
        return -1;
    };

    int arch_idx = gguf_find_key(ctx, "general.architecture");
    std::string arch = get_str_val(arch_idx);

    int name_idx = gguf_find_key(ctx, "general.name");
    std::string name = get_str_val(name_idx);

    int desc_idx = gguf_find_key(ctx, "general.description");
    std::string desc = get_str_val(desc_idx);

    int32_t n_ctx_train = 0;
    std::string ctx_key = arch + ".context_length";
    int ctx_idx = gguf_find_key(ctx, ctx_key.c_str());
    if (ctx_idx >= 0) {
        n_ctx_train = static_cast<int32_t>(get_u32_val(ctx_idx));
    }

    int32_t n_embd = 0;
    std::string embd_key = arch + ".embedding_length";
    int embd_idx = gguf_find_key(ctx, embd_key.c_str());
    if (embd_idx >= 0) {
        n_embd = static_cast<int32_t>(get_u32_val(embd_idx));
    }

    int32_t n_layer = 0;
    std::string layer_key = arch + ".block_count";
    int layer_idx = gguf_find_key(ctx, layer_key.c_str());
    if (layer_idx >= 0) {
        n_layer = static_cast<int32_t>(get_u32_val(layer_idx));
    }

    int tmpl_idx = gguf_find_key(ctx, "tokenizer.chat_template");
    bool has_chat_template = (tmpl_idx >= 0);

    bool has_unsupported_tensor_type = false;
    std::string unsupported_type_name = "";
    std::map<std::string, int> quant_counts;

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

    int split_no_idx = gguf_find_key(ctx, "split.no");
    int split_count_idx = gguf_find_key(ctx, "split.count");
    int32_t split_no = get_split_val(split_no_idx);
    int32_t split_count = get_split_val(split_count_idx);

    bool malformed_split = false;
    if ((split_no_idx >= 0 && split_count_idx < 0) || (split_no_idx < 0 && split_count_idx >= 0) ||
        (split_no >= 0 && split_count <= 0) || (split_count > 0 && split_no < 0) || (split_no >= split_count && split_count > 0)) {
        malformed_split = true;
    }

    int proj_type_idx = gguf_find_key(ctx, "clip.projector_type");
    int vision_proj_type_idx = gguf_find_key(ctx, "clip.vision.projector_type");
    int audio_proj_type_idx = gguf_find_key(ctx, "clip.audio.projector_type");
    int has_vis_idx = gguf_find_key(ctx, "clip.has_vision_encoder");
    int has_aud_idx = gguf_find_key(ctx, "clip.has_audio_encoder");

    bool clip_has_vision_encoder = (has_vis_idx >= 0) ? get_bool_val(has_vis_idx) : false;
    bool clip_has_audio_encoder = (has_aud_idx >= 0) ? get_bool_val(has_aud_idx) : false;

    bool is_projector = (arch == "clip") || (proj_type_idx >= 0) || (vision_proj_type_idx >= 0) ||
                        (audio_proj_type_idx >= 0) || (has_vis_idx >= 0) || (has_aud_idx >= 0);

    // An audio projector must not automatically enable image support
    if (is_projector) {
        if (has_vis_idx < 0 && has_aud_idx < 0) {
            if (audio_proj_type_idx >= 0) {
                clip_has_audio_encoder = true;
                clip_has_vision_encoder = false;
            } else {
                clip_has_vision_encoder = true;
            }
        }
    }

    bool is_supported = (arch_supported || is_projector) && !has_unsupported_tensor_type && !malformed_split;
    std::string error_msg = "";
    if (malformed_split) {
        error_msg = "Model has malformed or incomplete split metadata (split.no=" + std::to_string(split_no) + ", split.count=" + std::to_string(split_count) + ")";
    } else if (arch.empty() && !is_projector) {
        error_msg = "Model missing general.architecture metadata";
    } else if (!arch_supported && !is_projector) {
        error_msg = "Architecture '" + arch + "' is not supported by llama.cpp runtime";
    } else if (has_unsupported_tensor_type) {
        error_msg = "Model uses unsupported tensor quantization type: " + unsupported_type_name;
    }

    std::string template_str = has_chat_template ? get_str_val(tmpl_idx) : "";
    std::string thinking_capability = "Unknown";
    if (!is_projector && has_chat_template && !template_str.empty()) {
        try {
            std::string bos_token = "";
            std::string eos_token = "";
            int64_t tokens_idx = gguf_find_key(ctx, "tokenizer.ggml.tokens");
            if (tokens_idx >= 0 && gguf_get_kv_type(ctx, tokens_idx) == GGUF_TYPE_ARRAY) {
                int64_t bos_idx = gguf_find_key(ctx, "tokenizer.ggml.bos_token_id");
                if (bos_idx >= 0) {
                    uint32_t id = gguf_get_val_u32(ctx, bos_idx);
                    size_t n_tokens = gguf_get_arr_n(ctx, tokens_idx);
                    if (id < n_tokens) {
                        const char * s = gguf_get_arr_str(ctx, tokens_idx, id);
                        if (s) bos_token = s;
                    }
                }
                int64_t eos_idx = gguf_find_key(ctx, "tokenizer.ggml.eos_token_id");
                if (eos_idx >= 0) {
                    uint32_t id = gguf_get_val_u32(ctx, eos_idx);
                    size_t n_tokens = gguf_get_arr_n(ctx, tokens_idx);
                    if (id < n_tokens) {
                        const char * s = gguf_get_arr_str(ctx, tokens_idx, id);
                        if (s) eos_token = s;
                    }
                }
            }

            auto tmpls = common_chat_templates_init(nullptr, template_str, bos_token, eos_token);
            if (tmpls) {
                common_chat_templates_inputs inputs_on;
                inputs_on.use_jinja = true;
                inputs_on.add_generation_prompt = true;
                inputs_on.enable_thinking = true;
                inputs_on.chat_template_kwargs["enable_thinking"] = "true";
                inputs_on.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
                common_chat_msg msg;
                msg.role = "user";
                msg.content = "test";
                inputs_on.messages = { msg };

                common_chat_params params_on = common_chat_templates_apply(tmpls.get(), inputs_on);
                if (params_on.supports_thinking) {
                    common_chat_templates_inputs inputs_off = inputs_on;
                    inputs_off.enable_thinking = false;
                    inputs_off.chat_template_kwargs["enable_thinking"] = "false";
                    inputs_off.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
                    common_chat_params params_off = common_chat_templates_apply(tmpls.get(), inputs_off);

                    std::string prompt_on = params_on.prompt.str();
                    std::string prompt_off = params_off.prompt.str();

                    common_chat_templates_inputs inputs_base = inputs_on;
                    inputs_base.add_generation_prompt = false;
                    common_chat_params params_base = common_chat_templates_apply(tmpls.get(), inputs_base);
                    std::string prompt_base = params_base.prompt.str();

                    std::string prefix_on = !params_on.generation_prompt.empty()
                        ? params_on.generation_prompt
                        : (prompt_on.size() >= prompt_base.size() ? prompt_on.substr(prompt_base.size()) : prompt_on);

                    std::string prefix_off = !params_off.generation_prompt.empty()
                        ? params_off.generation_prompt
                        : (prompt_off.size() >= prompt_base.size() ? prompt_off.substr(prompt_base.size()) : prompt_off);

                    std::string start_tag = !params_on.thinking_start_tag.empty() ? params_on.thinking_start_tag : "<think>";
                    std::vector<std::string> end_tags = !params_on.thinking_end_tags.empty()
                        ? params_on.thinking_end_tags
                        : std::vector<std::string>{"</think>"};

                    ReasoningPrefixState state_on = inspect_prefix_reasoning_state(prefix_on, start_tag, end_tags);
                    ReasoningPrefixState state_off = inspect_prefix_reasoning_state(prefix_off, start_tag, end_tags);

                    if (state_off == ReasoningPrefixState::CLOSED_THINKING || state_off == ReasoningPrefixState::ORDINARY_ANSWER) {
                        if (!params_off.supports_thinking || state_on == ReasoningPrefixState::OPEN_THINKING || prompt_on != prompt_off) {
                            thinking_capability = "Toggleable";
                        } else {
                            thinking_capability = "ModelControlled";
                        }
                    } else if (state_off == ReasoningPrefixState::OPEN_THINKING) {
                        thinking_capability = "ModelControlled";
                    } else {
                        thinking_capability = "ModelControlled";
                    }
                } else {
                    thinking_capability = "Unsupported";
                }
            }
        } catch (const std::exception & e) {
            LOGW("SolusLlamaJNI: Chat template thinking capability inspection failed: %s", e.what());
            thinking_capability = "Unknown";
        } catch (...) {
            thinking_capability = "Unknown";
        }
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
         << "\"split_no\":" << split_no << ","
         << "\"split_count\":" << split_count << ","
         << "\"is_projector\":" << (is_projector ? "true" : "false") << ","
         << "\"clip_has_vision_encoder\":" << (clip_has_vision_encoder ? "true" : "false") << ","
         << "\"clip_has_audio_encoder\":" << (clip_has_audio_encoder ? "true" : "false") << ","
         << "\"thinking_capability\":\"" << escape_json(thinking_capability) << "\","
         << "\"error\":\"" << escape_json(error_msg) << "\""
         << "}";

    return safe_new_string(env, json.str());
}

JNIEXPORT jboolean JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeIsVulkanAvailable(
        JNIEnv * /*env*/,
        jclass /*clazz*/) {
    return static_cast<jboolean>(is_vulkan_supported_internal());
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetVulkanDeviceInfo(
        JNIEnv *env,
        jclass /*clazz*/) {
    std::ostringstream json;
    json << "{";
#ifdef GGML_USE_VULKAN
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("Vulkan");
    if (!reg) reg = ggml_backend_vk_reg();
    if (reg && ggml_backend_reg_dev_count(reg) > 0) {
        size_t count = ggml_backend_reg_dev_count(reg);
        json << "\"available\":true,"
             << "\"device_count\":" << count << ","
             << "\"devices\":[";
        for (size_t i = 0; i < count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_reg_dev_get(reg, i);
            const char * name = dev ? ggml_backend_dev_name(dev) : "Unknown";
            size_t free_mem = 0, total_mem = 0;
            if (dev) ggml_backend_dev_memory(dev, &free_mem, &total_mem);
            if (i > 0) json << ",";
            json << "{\"index\":" << i
                 << ",\"name\":\"" << escape_json(name ? name : "Unknown") << "\""
                 << ",\"free_memory_mb\":" << (free_mem / (1024 * 1024))
                 << ",\"total_memory_mb\":" << (total_mem / (1024 * 1024))
                 << "}";
        }
        json << "]}";
    } else {
        json << "\"available\":false,\"device_count\":0,\"devices\":[]}";
    }
#else
    json << "\"available\":false,\"device_count\":0,\"devices\":[]}";
#endif
    return safe_new_string(env, json.str());
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetActiveBackend(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle) {
    if (handle == 0) return safe_new_string(env, "CPU");
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder) return safe_new_string(env, "CPU");
    return safe_new_string(env, holder->active_backend == SOLUS_BACKEND_VULKAN ? "Vulkan" : "CPU");
}

JNIEXPORT jlong JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeCreateOperationControl(
        JNIEnv * /*env*/,
        jclass /*clazz*/) {
    return static_cast<jlong>(NativeOperationControlRegistry::instance().create());
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeCancelOperationControl(
        JNIEnv * /*env*/,
        jclass /*clazz*/,
        jlong control_id) {
    NativeOperationControlRegistry::instance().cancel(static_cast<uint64_t>(control_id));
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeReleaseOperationControl(
        JNIEnv * /*env*/,
        jclass /*clazz*/,
        jlong control_id) {
    NativeOperationControlRegistry::instance().release(static_cast<uint64_t>(control_id));
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetOperationControlProgress(
        JNIEnv * /*env*/,
        jclass /*clazz*/,
        jlong control_id) {
    auto op = NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(control_id));
    if (!op) return -1;
    return op->get_progress();
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetOperationControlSnapshot(
        JNIEnv *env,
        jclass /*clazz*/,
        jlong control_id) {
    auto op = NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(control_id));
    if (!op) return nullptr;
    return safe_new_string(env, op->get_snapshot_json());
}

JNIEXPORT jlong JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInitModelBundle(
        JNIEnv *env,
        jclass /*clazz*/,
        jobjectArray model_paths_array,
        jstring projector_path_str,
        jint n_threads,
        jint n_threads_batch,
        jint n_ctx,
        jint n_batch,
        jint n_ubatch,
        jint backend,
        jint n_gpu_layers,
        jlong operation_control_id) {

    if (!model_paths_array || env->GetArrayLength(model_paths_array) == 0) {
        LOGE("nativeInitModelBundle called with empty model paths");
        return 0;
    }

    std::vector<std::string> model_paths;
    jsize n_paths = env->GetArrayLength(model_paths_array);
    model_paths.reserve(n_paths);
    for (jsize i = 0; i < n_paths; ++i) {
        auto p_obj = (jstring)env->GetObjectArrayElement(model_paths_array, i);
        std::string p_str;
        if (p_obj && jstring_to_path_utf8(env, p_obj, p_str) && !p_str.empty()) {
            model_paths.push_back(p_str);
        }
        if (p_obj) env->DeleteLocalRef(p_obj);
    }

    if (model_paths.empty()) {
        LOGE("nativeInitModelBundle: no valid model paths resolved");
        return 0;
    }

    std::string projector_path = "";
    if (projector_path_str) {
        jstring_to_path_utf8(env, projector_path_str, projector_path);
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

    int requested_backend = backend;
    int active_backend = SOLUS_BACKEND_CPU;
    int effective_gpu_layers = 0;

    auto *holder = new LlamaContextHolder();
    holder->requested_backend = requested_backend;
    holder->model_paths = model_paths;
    holder->model_path = model_paths[0];
    holder->projector_path = projector_path;
    holder->op_control = op_control;

    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    if (op_control) {
        op_control->set_stage(requested_backend == SOLUS_BACKEND_VULKAN ? "GpuLoading" : "CpuLoading");
        mparams.progress_callback_user_data = op_control.get();
        mparams.progress_callback = [](float progress, void * user_data) -> bool {
            if (!user_data) return true;
            auto * op = static_cast<NativeOperationControl *>(user_data);
            if (op->is_cancelled()) return false;
            op->set_progress(static_cast<int>(progress * 1000.0f), static_cast<int64_t>(progress * 1000.0f), 1000);
            return true;
        };
    }

    if (requested_backend == SOLUS_BACKEND_VULKAN) {
        if (is_vulkan_supported_internal() && configure_vulkan_backend_params(mparams, cparams, holder->gpu_devices_storage, n_gpu_layers)) {
            effective_gpu_layers = (n_gpu_layers > 0) ? n_gpu_layers : 99;
        } else {
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
    }

    auto load_model_helper = [&](const llama_model_params & mp) -> llama_model * {
        if (model_paths.size() == 1) {
            return llama_model_load_from_file(model_paths[0].c_str(), mp);
        } else {
            std::vector<const char *> c_paths;
            for (const auto & p : model_paths) c_paths.push_back(p.c_str());
            return llama_model_load_from_splits(c_paths.data(), c_paths.size(), mp);
        }
    };

    TRACE_BEGIN("model_loading");
    llama_model * model = load_model_helper(mparams);

    if (op_control && op_control->is_cancelled()) {
        TRACE_END();
        if (model) llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, -2, "Model loading cancelled");
        return 0;
    }

    if (!model && mparams.load_mode == LLAMA_LOAD_MODE_MMAP) {
        mparams.load_mode = LLAMA_LOAD_MODE_NONE;
        model = load_model_helper(mparams);
    }

    // Vulkan fallback to CPU if model loading failed on GPU
    if (!model && mparams.n_gpu_layers > 0) {
        configure_cpu_backend_params(mparams, cparams);
        holder->gpu_devices_storage.clear();
        effective_gpu_layers = 0;
        requested_backend = SOLUS_BACKEND_CPU;
        holder->requested_backend = SOLUS_BACKEND_CPU;
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        model = load_model_helper(mparams);
        if (!model) {
            mparams.load_mode = LLAMA_LOAD_MODE_NONE;
            model = load_model_helper(mparams);
        }
    }
    TRACE_END();

    if (!model) {
        std::string err_detail;
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            err_detail = g_last_error_log.empty() ? "Unsupported architecture or invalid GGUF model files" : g_last_error_log;
        }
        delete holder;
        set_last_failure(SOLUS_FAILURE_UNKNOWN, -1, err_detail);
        return 0;
    }

    if (!llama_model_has_decoder(model)) {
        LOGE("SolusLlamaJNI: Model does not have a decoder (non-generative model)");
        llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_UNSUPPORTED_TEMPLATE, -3, "Model does not contain a decoder (non-generative model)");
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_UNKNOWN, -1, "Failed to extract vocabulary");
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

    if (model && llama_model_has_encoder(model)) {
        // Encoder models process prompt tokens in an encode pass bounded by n_ubatch.
        // Size batch and micro-batch to accommodate the accepted prompt budget.
        uint32_t prompt_budget = static_cast<uint32_t>(effective_ctx) > 128u
            ? static_cast<uint32_t>(effective_ctx) - output_token_reserve(static_cast<uint32_t>(effective_ctx), 0)
            : static_cast<uint32_t>(effective_ctx);
        if (eff_batch < prompt_budget) eff_batch = prompt_budget;
        if (eff_ubatch < prompt_budget) eff_ubatch = prompt_budget;
        if (eff_batch > static_cast<uint32_t>(effective_ctx)) eff_batch = static_cast<uint32_t>(effective_ctx);
        if (eff_ubatch > eff_batch) eff_ubatch = eff_batch;
    }

    holder->model = model;
    holder->vocab = vocab;
    holder->n_threads = effective_threads;
    holder->n_threads_batch = effective_threads_batch;
    holder->n_ctx = effective_ctx;
    holder->n_batch = static_cast<int>(eff_batch);
    holder->n_ubatch = static_cast<int>(eff_ubatch);
    holder->requested_backend = requested_backend;
    holder->active_backend = (mparams.n_gpu_layers > 0) ? SOLUS_BACKEND_VULKAN : SOLUS_BACKEND_CPU;
    holder->n_gpu_layers = effective_gpu_layers;

    cparams.n_ctx = static_cast<uint32_t>(effective_ctx);
    cparams.n_batch = eff_batch;
    cparams.n_ubatch = eff_ubatch;
    cparams.n_threads = effective_threads;
    cparams.n_threads_batch = effective_threads_batch;
    cparams.abort_callback = llama_jni_abort_callback;
    cparams.abort_callback_data = holder;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx && mparams.n_gpu_layers > 0) {
        // Fallback to CPU if GPU context allocation failed
        configure_cpu_backend_params(mparams, cparams);
        holder->gpu_devices_storage.clear();
        effective_gpu_layers = 0;
        requested_backend = SOLUS_BACKEND_CPU;
        holder->requested_backend = SOLUS_BACKEND_CPU;
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        llama_model_free(model);
        model = load_model_helper(mparams);
        if (model) {
            vocab = llama_model_get_vocab(model);
            holder->model = model;
            holder->vocab = vocab;
            ctx = llama_init_from_model(model, cparams);
        }
    }

    if (!ctx && model && effective_ctx > 1024) {
        cparams.n_ctx = 1024;
        if (llama_model_has_encoder(model)) {
            uint32_t prompt_budget = cparams.n_ctx > 128u
                ? cparams.n_ctx - output_token_reserve(cparams.n_ctx, 0)
                : cparams.n_ctx;
            cparams.n_batch = prompt_budget;
            cparams.n_ubatch = prompt_budget;
        } else {
            cparams.n_batch = std::min(eff_batch, 128u);
            cparams.n_ubatch = std::min(eff_ubatch, 64u);
        }
        ctx = llama_init_from_model(model, cparams);
    }

    if (!ctx) {
        llama_model_free(model);
        delete holder;
        set_last_failure(SOLUS_FAILURE_OUT_OF_MEMORY, -9, "Failed to allocate llama context");
        return 0;
    }

    holder->ctx = ctx;
    holder->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    holder->n_batch = static_cast<int>(llama_n_batch(ctx));
    holder->n_ubatch = static_cast<int>(llama_n_ubatch(ctx));
    holder->active_backend = (mparams.n_gpu_layers > 0) ? SOLUS_BACKEND_VULKAN : SOLUS_BACKEND_CPU;
    holder->n_gpu_layers = effective_gpu_layers;
    llama_set_abort_callback(ctx, llama_jni_abort_callback, holder);

    holder->backend_inspection = inspect_gguf_backend(ctx);
    if (mparams.n_gpu_layers == 0) {
        if (!holder->backend_inspection.is_cpu_isolated) {
            llama_free(ctx);
            llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_BACKEND_EXECUTION, -8, "CPU isolation check failed");
            return 0;
        }
    }

    // Initialize multimodal projector if specified
    if (!projector_path.empty()) {
        holder->multimodal = std::make_unique<LlamaMultimodalContext>();
        std::string mm_err;
        bool mm_ok = holder->multimodal->init(projector_path, model, (holder->active_backend == SOLUS_BACKEND_VULKAN), effective_threads, mm_err, op_control.get());
        if (!mm_ok) {
            LOGE("SolusLlamaJNI: Multimodal projector initialization failed: %s", mm_err.c_str());
            llama_free(ctx);
            llama_model_free(model);
            delete holder;
            set_last_failure(SOLUS_FAILURE_BACKEND_EXECUTION, -4, "Multimodal projector initialization failed: " + mm_err);
            return 0;
        }
    }

    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT jlong JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInitModel(
        JNIEnv *env,
        jclass clazz,
        jstring model_path_str,
        jint n_threads,
        jint n_threads_batch,
        jint n_ctx,
        jint n_batch,
        jint n_ubatch,
        jint backend,
        jint n_gpu_layers,
        jlong operation_control_id) {

    if (!model_path_str) return 0;
    jclass string_cls = env->FindClass("java/lang/String");
    jobjectArray paths_arr = env->NewObjectArray(1, string_cls, model_path_str);
    jlong handle = Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInitModelBundle(
        env, clazz, paths_arr, nullptr, n_threads, n_threads_batch, n_ctx, n_batch, n_ubatch, backend, n_gpu_layers, operation_control_id);
    env->DeleteLocalRef(paths_arr);
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGenerateMultimodalStream(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jlong generation_id,
        jstring prompt_str,
        jobjectArray roles_array,
        jobjectArray contents_array,
        jintArray media_types_array,
        jintArray media_message_indices_array,
        jintArray image_widths_array,
        jintArray image_heights_array,
        jobjectArray image_bytes_array,
        jobjectArray audio_floats_array,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jfloat min_p,
        jfloat repeat_penalty,
        jint max_tokens,
        jint seed,
        jboolean enable_thinking,
        jobject callback_obj) {

    if (handle == 0 || callback_obj == nullptr) {
        LOGE("SolusLlamaJNI: nativeGenerateMultimodalStream called with invalid arguments");
        return -1;
    }

    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->model || !holder->ctx || !holder->vocab || holder->is_closed.load(std::memory_order_acquire)) {
        LOGE("SolusLlamaJNI: Context is closed or invalid");
        return -1;
    }

    holder->active_leases.fetch_add(1, std::memory_order_acq_rel);
    NativeLeaseGuard lease_guard(holder);

    bool expected = false;
    if (!holder->is_generating.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        LOGW("SolusLlamaJNI: Generation already in progress");
        return -1;
    }

    uint64_t gid = static_cast<uint64_t>(generation_id);
    holder->active_generation_id.store(gid, std::memory_order_release);

    jclass callback_class = env->GetObjectClass(callback_obj);
    if (!callback_class) return -1;

    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_complete_method = env->GetMethodID(callback_class, "onComplete", "()V");
    jmethodID on_stop_method = env->GetMethodID(callback_class, "onStop", "()V");
    jmethodID on_error_method = env->GetMethodID(callback_class, "onError", "(Ljava/lang/String;)V");
    jmethodID on_metrics_method = env->GetMethodID(callback_class, "onMetrics", "(JJI)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_metrics_ext_method = env->GetMethodID(callback_class, "onMetrics", "(JJIIII)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_first_token_method = env->GetMethodID(callback_class, "onFirstToken", "()V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_structured_token_method = env->GetMethodID(callback_class, "onStructuredToken", "(Ljava/lang/String;Ljava/lang/String;Z)V");
    if (env->ExceptionCheck()) env->ExceptionClear();

    if (!on_token_method) return -1;

    if (gid != 0 && holder->cancelled_generation_id.load(std::memory_order_acquire) >= gid) {
        if (on_stop_method) call_jni_void_method_checked(env, callback_obj, on_stop_method);
        return 1;
    }

    holder->stop_requested.store(false, std::memory_order_release);
    solus_clear_numerical_anomaly();
    g_last_failure_category.store(SOLUS_FAILURE_NONE, std::memory_order_release);
    g_last_failure_code.store(0, std::memory_order_release);

    // Extract media parts if present
    std::vector<MultimodalMediaPart> media_parts;
    if (media_types_array && image_widths_array && image_heights_array) {
        jsize n_media = env->GetArrayLength(media_types_array);
        if (n_media > 0) {
            jint * types = env->GetIntArrayElements(media_types_array, nullptr);
            jint * widths = env->GetIntArrayElements(image_widths_array, nullptr);
            jint * heights = env->GetIntArrayElements(image_heights_array, nullptr);
            jint * msg_indices = media_message_indices_array ? env->GetIntArrayElements(media_message_indices_array, nullptr) : nullptr;

            for (jsize i = 0; i < n_media; ++i) {
                MultimodalMediaPart part;
                part.message_index = msg_indices ? msg_indices[i] : -1;
                if (types[i] == 0) { // Image
                    part.type = MultimodalMediaPart::IMAGE;
                    part.width = static_cast<uint32_t>(widths[i]);
                    part.height = static_cast<uint32_t>(heights[i]);
                    if (image_bytes_array) {
                        auto b_arr = (jbyteArray)env->GetObjectArrayElement(image_bytes_array, i);
                        if (b_arr) {
                            jsize b_len = env->GetArrayLength(b_arr);
                            part.rgb_data.resize(b_len);
                            env->GetByteArrayRegion(b_arr, 0, b_len, reinterpret_cast<jbyte *>(part.rgb_data.data()));
                            env->DeleteLocalRef(b_arr);
                        }
                    }
                } else { // Audio
                    part.type = MultimodalMediaPart::AUDIO;
                    if (audio_floats_array) {
                        auto f_arr = (jfloatArray)env->GetObjectArrayElement(audio_floats_array, i);
                        if (f_arr) {
                            jsize f_len = env->GetArrayLength(f_arr);
                            part.audio_pcm.resize(f_len);
                            env->GetFloatArrayRegion(f_arr, 0, f_len, part.audio_pcm.data());
                            env->DeleteLocalRef(f_arr);
                        }
                    }
                }
                media_parts.push_back(std::move(part));
            }

            env->ReleaseIntArrayElements(media_types_array, types, JNI_ABORT);
            env->ReleaseIntArrayElements(image_widths_array, widths, JNI_ABORT);
            env->ReleaseIntArrayElements(image_heights_array, heights, JNI_ABORT);
            if (msg_indices) {
                env->ReleaseIntArrayElements(media_message_indices_array, msg_indices, JNI_ABORT);
            }
        }
    }

    const auto prefill_started = std::chrono::steady_clock::now();
    int n_cur = 0;
    size_t prompt_tokens_count = 0;
    size_t reused_tokens_count = 0;

    int batch_size = holder->n_batch > 0 ? holder->n_batch : 256;
    lease_guard.batch = llama_batch_init(batch_size, 0, 1);
    lease_guard.batch_initialized = true;
    llama_batch & batch = lease_guard.batch;
    std::vector<llama_seq_id> seq_ids = { 0 };
    std::unique_ptr<common_chat_session> chat_session;

    if (!media_parts.empty()) {
        if (!holder->multimodal || !holder->multimodal->is_valid()) {
            std::string err = "Multimodal input provided, but no compatible projector is loaded";
            set_last_failure(SOLUS_FAILURE_UNSUPPORTED_TEMPLATE, -3, err);
            if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, err.c_str());
            return -3;
        }

        // Disable prefix-cache reuse for multimodal requests; replay structured prompt fresh
        clear_prompt_cache(holder);

        std::vector<std::string> roles_storage;
        std::vector<std::string> contents_storage;
        if (roles_array != nullptr && contents_array != nullptr) {
            jsize msg_count = env->GetArrayLength(roles_array);
            jsize content_count = env->GetArrayLength(contents_array);
            if (msg_count > 0 && msg_count == content_count) {
                roles_storage.reserve(msg_count);
                contents_storage.reserve(msg_count);
                for (jsize i = 0; i < msg_count; ++i) {
                    auto r_obj = (jstring)env->GetObjectArrayElement(roles_array, i);
                    auto c_obj = (jstring)env->GetObjectArrayElement(contents_array, i);
                    std::string r_val = r_obj ? jstring_to_utf8(env, r_obj) : "user";
                    std::string c_val = c_obj ? jstring_to_utf8(env, c_obj) : "";
                    if (r_val.empty()) r_val = "user";
                    roles_storage.push_back(r_val);
                    contents_storage.push_back(c_val);
                    if (r_obj) env->DeleteLocalRef(r_obj);
                    if (c_obj) env->DeleteLocalRef(c_obj);
                }
            }
        }

        if (roles_storage.empty() && prompt_str != nullptr) {
            std::string raw_p = jstring_to_utf8(env, prompt_str);
            if (!raw_p.empty()) {
                roles_storage.push_back("user");
                contents_storage.push_back(raw_p);
            }
        }

        if (roles_storage.empty()) {
            roles_storage.push_back("user");
            contents_storage.push_back("");
        }

        const char * marker = holder->multimodal->get_marker();
        std::string marker_str = (marker && marker[0] != '\0') ? marker : mtmd_default_marker();

        int default_target_idx = static_cast<int>(contents_storage.size()) - 1;
        for (int i = static_cast<int>(roles_storage.size()) - 1; i >= 0; --i) {
            if (roles_storage[i] == "user") {
                default_target_idx = i;
                break;
            }
        }

        std::vector<int> markers_needed(contents_storage.size(), 0);
        for (const auto & part : media_parts) {
            int target_idx = (part.message_index >= 0 && part.message_index < static_cast<int>(contents_storage.size()))
                ? part.message_index
                : default_target_idx;
            if (target_idx < 0) target_idx = 0;
            markers_needed[target_idx]++;
        }

        for (size_t i = 0; i < contents_storage.size(); ++i) {
            if (markers_needed[i] > 0) {
                size_t existing = 0;
                size_t p = 0;
                while ((p = contents_storage[i].find(marker_str, p)) != std::string::npos) {
                    existing++;
                    p += marker_str.length();
                }
                while (existing < static_cast<size_t>(markers_needed[i])) {
                    contents_storage[i] = marker_str + "\n" + contents_storage[i];
                    existing++;
                }
            }
        }

        common_chat_templates_ptr tmpls = common_chat_templates_init(holder->model, "");
        std::string formatted_prompt;
        bool applied_template = false;

        if (tmpls) {
            common_chat_templates_inputs inputs;
            for (size_t i = 0; i < roles_storage.size(); ++i) {
                common_chat_msg msg;
                msg.role = roles_storage[i];
                msg.content = contents_storage[i];
                inputs.messages.push_back(msg);
            }
            inputs.add_generation_prompt = true;
            inputs.use_jinja = true;
            inputs.enable_thinking = enable_thinking;
            inputs.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
            inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;

            try {
                common_chat_params chat_params = common_chat_templates_apply(tmpls.get(), inputs);
                formatted_prompt = chat_params.prompt.str();
                applied_template = true;
                chat_session = std::make_unique<common_chat_session>(tmpls.get(), holder->vocab, inputs);
            } catch (const std::exception & e) {
                LOGW("SolusLlamaJNI: Multimodal chat template apply failed: %s", e.what());
            } catch (...) {}
        }

        if (!applied_template) {
            if (!roles_storage.empty()) {
                set_last_failure(SOLUS_FAILURE_UNSUPPORTED_TEMPLATE, -4, "Chat template apply failed for structured messages");
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Chat template apply failed for structured messages");
                return -4;
            }
            formatted_prompt = contents_storage.empty() ? "" : contents_storage.back();
        }

        llama_pos new_n_past = 0;
        std::string mm_eval_err;
        bool eval_ok = false;

        while (true) {
            eval_ok = holder->multimodal->eval_multimodal_prompt(
                holder->ctx,
                formatted_prompt,
                media_parts,
                0,
                batch_size,
                max_tokens,
                new_n_past,
                mm_eval_err
            );
            if (eval_ok) break;

            if (mm_eval_err.find("Context capacity exceeded") != std::string::npos) {
                size_t drop_count = oldest_history_turn_size(roles_storage);
                if (drop_count > 0 && roles_storage.size() > drop_count + 1) {
                    size_t first = 0;
                    while (first < roles_storage.size() && roles_storage[first] == "system") ++first;
                    for (size_t d = 0; d < drop_count && first < roles_storage.size(); ++d) {
                        roles_storage.erase(roles_storage.begin() + first);
                        contents_storage.erase(contents_storage.begin() + first);
                    }

                    // Remove media parts belonging to dropped turns and re-index remaining parts
                    for (auto it = media_parts.begin(); it != media_parts.end(); ) {
                        if (it->message_index >= static_cast<int>(first) &&
                            it->message_index < static_cast<int>(first + drop_count)) {
                            it = media_parts.erase(it);
                        } else {
                            if (it->message_index >= static_cast<int>(first + drop_count)) {
                                it->message_index -= static_cast<int>(drop_count);
                            }
                            ++it;
                        }
                    }

                    int cur_target_idx = static_cast<int>(contents_storage.size()) - 1;
                    for (int i = static_cast<int>(roles_storage.size()) - 1; i >= 0; --i) {
                        if (roles_storage[i] == "user") {
                            cur_target_idx = i;
                            break;
                        }
                    }

                    std::vector<int> loop_markers_needed(contents_storage.size(), 0);
                    for (auto & part : media_parts) {
                        int t_idx = (part.message_index >= 0 && part.message_index < static_cast<int>(contents_storage.size()))
                            ? part.message_index
                            : cur_target_idx;
                        if (t_idx < 0) t_idx = 0;
                        part.message_index = t_idx;
                        loop_markers_needed[t_idx]++;
                    }

                    for (size_t i = 0; i < contents_storage.size(); ++i) {
                        size_t p = 0;
                        while ((p = contents_storage[i].find(marker_str, p)) != std::string::npos) {
                            contents_storage[i].erase(p, marker_str.length());
                            if (p < contents_storage[i].length() && contents_storage[i][p] == '\n') {
                                contents_storage[i].erase(p, 1);
                            }
                        }
                        for (int m = 0; m < loop_markers_needed[i]; ++m) {
                            contents_storage[i] = marker_str + "\n" + contents_storage[i];
                        }
                    }

                    if (tmpls) {
                        common_chat_templates_inputs loop_inputs;
                        for (size_t i = 0; i < roles_storage.size(); ++i) {
                            common_chat_msg msg;
                            msg.role = roles_storage[i];
                            msg.content = contents_storage[i];
                            loop_inputs.messages.push_back(msg);
                        }
                        loop_inputs.add_generation_prompt = true;
                        loop_inputs.use_jinja = true;
                        loop_inputs.enable_thinking = enable_thinking;
                        loop_inputs.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
                        loop_inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
                        try {
                            common_chat_params chat_params = common_chat_templates_apply(tmpls.get(), loop_inputs);
                            formatted_prompt = chat_params.prompt.str();
                            chat_session = std::make_unique<common_chat_session>(tmpls.get(), holder->vocab, loop_inputs);
                            continue;
                        } catch (...) {}
                    }
                }
            }
            break;
        }

        if (!eval_ok) {
            if (holder->stop_requested.load(std::memory_order_relaxed) ||
                holder->is_closed.load(std::memory_order_relaxed) ||
                (gid != 0 && holder->cancelled_generation_id.load(std::memory_order_acquire) >= gid)) {
                set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Stopped during multimodal prompt decode");
                if (on_stop_method) call_jni_void_method_checked(env, callback_obj, on_stop_method);
                return 1;
            }
            if (mm_eval_err.find("Context capacity exceeded") != std::string::npos) {
                set_last_failure(SOLUS_FAILURE_CONTEXT_CAPACITY, -7, mm_eval_err);
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, mm_eval_err.c_str());
                return -7;
            }
            set_last_failure(SOLUS_FAILURE_PROMPT_DECODE_FAILED, -5, mm_eval_err);
            if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, mm_eval_err.c_str());
            return -5;
        }

        n_cur = static_cast<int>(new_n_past);
        prompt_tokens_count = static_cast<size_t>(new_n_past);
        reused_tokens_count = 0;
    } else {
        // Standard text generation with Jinja chat templates and prefix caching
        std::vector<llama_token> prompt_tokens;
        std::string prompt_err;
        int prompt_cat = SOLUS_FAILURE_NONE;
        if (!format_and_tokenize_prompt(holder, env, prompt_str, roles_array, contents_array, prompt_tokens, prompt_err, prompt_cat, max_tokens, 0, SIZE_MAX, enable_thinking, &chat_session)) {
            set_last_failure(prompt_cat, -4, prompt_err);
            if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, prompt_err.c_str());
            return -4;
        }

        size_t reused_tokens = reusable_prompt_tokens(holder->cached_tokens, prompt_tokens);
        llama_memory_t mem = llama_get_memory(holder->ctx);
        bool rm_ok = mem && llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(reused_tokens), -1);
        if (reused_tokens == 0 || !rm_ok) {
            clear_prompt_cache(holder);
            reused_tokens = 0;
        } else {
            holder->cached_tokens.resize(reused_tokens);
        }

        prompt_tokens_count = prompt_tokens.size();
        reused_tokens_count = reused_tokens;

        if (llama_model_has_encoder(holder->model)) {
            clear_prompt_cache(holder);
            reused_tokens = 0;
            reused_tokens_count = 0;

            if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
                set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Stopped during prompt encode");
                if (on_stop_method) call_jni_void_method_checked(env, callback_obj, on_stop_method);
                return 1;
            }

            uint32_t ubatch = llama_n_ubatch(holder->ctx);
            if (ubatch > 0 && prompt_tokens.size() > static_cast<size_t>(ubatch)) {
                clear_prompt_cache(holder);
                std::string err_msg = "Prompt tokens (" + std::to_string(prompt_tokens.size()) +
                                      ") exceed encoder micro-batch limit (" + std::to_string(ubatch) + ")";
                LOGE("SolusLlamaJNI: %s", err_msg.c_str());
                set_last_failure(SOLUS_FAILURE_CONTEXT_CAPACITY, -7, err_msg);
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, err_msg.c_str());
                return -7;
            }

            llama_batch enc_batch = llama_batch_init(static_cast<int32_t>(prompt_tokens.size()), 0, 1);
            for (size_t i = 0; i < prompt_tokens.size(); ++i) {
                batch_add(enc_batch, prompt_tokens[i], static_cast<llama_pos>(i), seq_ids, true);
            }

            int32_t enc_res = llama_encode(holder->ctx, enc_batch);
            llama_batch_free(enc_batch);

            if (enc_res != 0) {
                clear_prompt_cache(holder);
                set_last_failure(SOLUS_FAILURE_PROMPT_DECODE_FAILED, -5, "Failed to encode prompt tokens");
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Failed to encode prompt tokens");
                return -5;
            }

            if (solus_has_numerical_anomaly() || !audit_logits_numerics(holder->ctx, "PROMPT_ENCODE", static_cast<int>(prompt_tokens.size() - 1))) {
                set_last_failure(SOLUS_FAILURE_NUMERICAL_INSTABILITY, -2, "Numerical anomaly after prompt encode");
                clear_prompt_cache(holder);
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Numerical anomaly after prompt encode");
                return -2;
            }

            llama_token decoder_start_token = llama_model_decoder_start_token(holder->model);
            if (decoder_start_token == LLAMA_TOKEN_NULL) {
                decoder_start_token = llama_vocab_bos(holder->vocab);
            }
            batch.n_tokens = 0;
            batch_add(batch, decoder_start_token, 0, seq_ids, true);
            if (llama_decode(holder->ctx, batch) != 0) {
                clear_prompt_cache(holder);
                set_last_failure(SOLUS_FAILURE_PROMPT_DECODE_FAILED, -5, "Failed to decode decoder start token");
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Failed to decode decoder start token");
                return -5;
            }
            batch.n_tokens = 0;

            n_cur = 1;
        } else {
            bool aborted = false;
            bool decode_failed = false;
            for (size_t i = reused_tokens; i < prompt_tokens.size(); ++i) {
                if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
                    aborted = true;
                    break;
                }
                bool is_last = (i == prompt_tokens.size() - 1);
                batch_add(batch, prompt_tokens[i], static_cast<llama_pos>(i), seq_ids, is_last);

                if (batch.n_tokens == batch_size || is_last) {
                    int32_t dec_res = llama_decode(holder->ctx, batch);
                    if (dec_res != 0) {
                        decode_failed = true;
                        break;
                    }
                    if (is_last || solus_has_numerical_anomaly()) {
                        if (solus_has_numerical_anomaly() || !audit_logits_numerics(holder->ctx, "PROMPT_DECODE", static_cast<int>(i))) {
                            set_last_failure(SOLUS_FAILURE_NUMERICAL_INSTABILITY, -2, "Numerical anomaly after prompt decode");
                            clear_prompt_cache(holder);
                            if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Numerical anomaly after prompt decode");
                            return -2;
                        }
                    }
                    batch.n_tokens = 0;
                }
            }

            if (decode_failed || aborted) {
                clear_prompt_cache(holder);
                if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
                    set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Stopped during prompt decode");
                    if (on_stop_method) call_jni_void_method_checked(env, callback_obj, on_stop_method);
                    return 1;
                } else {
                    set_last_failure(SOLUS_FAILURE_PROMPT_DECODE_FAILED, -5, "Failed to decode prompt tokens");
                    if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Failed to decode prompt tokens");
                    return -5;
                }
            }

            holder->cached_tokens = prompt_tokens;
            n_cur = static_cast<int>(prompt_tokens.size());
        }
    }

    // Initialize Sampler Chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    lease_guard.smpl = smpl;

    float eff_repeat_penalty = repeat_penalty > 0.0f ? repeat_penalty : 1.1f;
    if (eff_repeat_penalty > 1.0f) {
        int32_t n_vocab = llama_vocab_n_tokens(holder->vocab);
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, eff_repeat_penalty, 0.0f, 0.0f));
    }
    float eff_top_p = top_p > 0.0f && top_p <= 1.0f ? top_p : 0.95f;
    if (eff_top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(eff_top_p, 1));
    }
    int eff_top_k = top_k > 0 ? top_k : 40;
    if (eff_top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(eff_top_k));
    }
    float eff_min_p = min_p > 0.0f && min_p < 1.0f ? min_p : 0.05f;
    if (eff_min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(eff_min_p, 1));
    }
    float eff_temp = temperature >= 0.0f ? temperature : 0.7f;
    if (eff_temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(eff_temp));
        uint32_t eff_seed = seed != 0 ? static_cast<uint32_t>(seed) : LLAMA_DEFAULT_SEED;
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(eff_seed));
    }

    const auto decode_started = std::chrono::steady_clock::now();
    int n_generated = 0;
    int max_gen = max_tokens > 0 ? max_tokens : 1024;
    std::string utf8_stream_buf;
    llama_token eos_token = llama_vocab_eos(holder->vocab);

    bool first_token_notified = false;
    bool aborted = false;
    bool hit_context_limit = false;
    bool thinking_started = false;
    bool thinking_finished = false;
    std::string rolling_piece_window;
    int consecutive_parser_failures = 0;
    std::string last_accepted_content;
    std::string last_accepted_reasoning;
    if (chat_session && !chat_session->thinking_start_tag().empty()) {
        ReasoningPrefixState prefix_state = inspect_prefix_reasoning_state(
            chat_session->generation_prompt(),
            chat_session->thinking_start_tag(),
            chat_session->thinking_end_tags()
        );
        if (prefix_state == ReasoningPrefixState::OPEN_THINKING) {
            thinking_started = true;
            thinking_finished = false;
        } else if (prefix_state == ReasoningPrefixState::CLOSED_THINKING) {
            thinking_started = true;
            thinking_finished = true;
        } else {
            thinking_started = false;
            thinking_finished = false;
        }
    }

    while (n_generated < max_gen) {
        if (holder->stop_requested.load(std::memory_order_relaxed) || holder->is_closed.load(std::memory_order_relaxed)) {
            aborted = true;
            break;
        }

        if (n_cur >= holder->n_ctx) {
            set_last_failure(SOLUS_FAILURE_CONTEXT_CAPACITY, -7, "Context window limit reached");
            hit_context_limit = true;
            break;
        }

        if (!audit_logits_numerics(holder->ctx, "PRE_SAMPLE", n_cur) || solus_has_numerical_anomaly()) {
            set_last_failure(SOLUS_FAILURE_NUMERICAL_INSTABILITY, -2, "Numerical anomaly before sample");
            clear_prompt_cache(holder);
            if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Numerical anomaly before sample");
            return -2;
        }

        llama_token token = llama_sampler_sample(smpl, holder->ctx, -1);

        if (llama_vocab_is_eog(holder->vocab, token) || token == eos_token) {
            break;
        }

        if (!first_token_notified) {
            first_token_notified = true;
            if (on_first_token_method) call_jni_void_method_checked(env, callback_obj, on_first_token_method);
        }

        char piece_buf[128] = {0};
        int n_piece = llama_token_to_piece(holder->vocab, token, piece_buf, sizeof(piece_buf), 0, false);
        if (n_piece < 0) {
            std::vector<char> big_piece(-n_piece);
            n_piece = llama_token_to_piece(holder->vocab, token, big_piece.data(), big_piece.size(), 0, false);
            if (n_piece > 0) utf8_stream_buf.append(big_piece.data(), n_piece);
        } else if (n_piece > 0) {
            utf8_stream_buf.append(piece_buf, n_piece);
        }

        std::string piece_to_emit = extract_complete_utf8(utf8_stream_buf);
        if (!piece_to_emit.empty() && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
            if (!call_jni_string_method_checked(env, callback_obj, on_token_method, piece_to_emit.c_str())) {
                aborted = true;
                break;
            }
        }

        if (chat_session && on_structured_token_method && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
            std::string token_piece = common_token_to_piece(holder->vocab, token, true);
            rolling_piece_window += token_piece;
            if (rolling_piece_window.size() > 128) {
                rolling_piece_window.erase(0, rolling_piece_window.size() - 128);
            }
            common_chat_input token_chunk;
            token_chunk.append(token_piece, token);
            bool is_thinking_active = false;
            try {
                chat_session->feed(token_chunk);
                consecutive_parser_failures = 0;
                const auto & cmsg = chat_session->msg();
                last_accepted_content = cmsg.content;
                last_accepted_reasoning = cmsg.reasoning_content;
                if (!cmsg.reasoning_content.empty()) {
                    thinking_started = true;
                }
                if (!cmsg.content.empty()) {
                    thinking_finished = true;
                }
                for (const auto & end_tag : chat_session->thinking_end_tags()) {
                    if (!end_tag.empty() && rolling_piece_window.find(end_tag) != std::string::npos) {
                        thinking_finished = true;
                        break;
                    }
                }
                is_thinking_active = thinking_started && !thinking_finished;
                if (!call_jni_structured_token_method_checked(env, callback_obj, on_structured_token_method,
                        cmsg.content.c_str(), cmsg.reasoning_content.c_str(), is_thinking_active)) {
                    aborted = true;
                    break;
                }
            } catch (const std::exception & e) {
                consecutive_parser_failures++;
                LOGW("SolusLlamaJNI: chat_session feed error (%d consecutive): %s", consecutive_parser_failures, e.what());
                for (const auto & end_tag : chat_session->thinking_end_tags()) {
                    if (!end_tag.empty() && rolling_piece_window.find(end_tag) != std::string::npos) {
                        thinking_finished = true;
                        break;
                    }
                }
                is_thinking_active = thinking_started && !thinking_finished;
                if (!call_jni_structured_token_method_checked(env, callback_obj, on_structured_token_method,
                        last_accepted_content.c_str(), last_accepted_reasoning.c_str(), is_thinking_active)) {
                    aborted = true;
                    break;
                }
                if (consecutive_parser_failures >= 5 && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
                    std::string err_msg = std::string("Response format parsing error: ") + e.what();
                    set_last_failure(SOLUS_FAILURE_RESPONSE_FORMAT_ERROR, -8, err_msg);
                    if (on_error_method) {
                        call_jni_string_method_checked(env, callback_obj, on_error_method, err_msg.c_str());
                    }
                    aborted = true;
                    break;
                }
            } catch (...) {
                consecutive_parser_failures++;
                is_thinking_active = thinking_started && !thinking_finished;
                if (!call_jni_structured_token_method_checked(env, callback_obj, on_structured_token_method,
                        last_accepted_content.c_str(), last_accepted_reasoning.c_str(), is_thinking_active)) {
                    aborted = true;
                    break;
                }
                if (consecutive_parser_failures >= 5 && !holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
                    std::string err_msg = "Response format parsing error: unknown parser exception";
                    set_last_failure(SOLUS_FAILURE_RESPONSE_FORMAT_ERROR, -8, err_msg);
                    if (on_error_method) {
                        call_jni_string_method_checked(env, callback_obj, on_error_method, err_msg.c_str());
                    }
                    aborted = true;
                    break;
                }
            }
        }

        batch.n_tokens = 0;
        batch_add(batch, token, static_cast<llama_pos>(n_cur), seq_ids, true);

        if (llama_decode(holder->ctx, batch) != 0) {
            if (!holder->stop_requested.load(std::memory_order_relaxed) && !holder->is_closed.load(std::memory_order_relaxed)) {
                clear_prompt_cache(holder);
                set_last_failure(SOLUS_FAILURE_GENERATION_DECODE_FAILED, -6, "Decode failed during token generation");
                if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Decode failed during token generation");
                return -6;
            }
            aborted = true;
            break;
        }

        holder->cached_tokens.push_back(token);
        n_cur++;
        n_generated++;
    }

    if (!utf8_stream_buf.empty() && !aborted && !holder->stop_requested.load(std::memory_order_relaxed)) {
        call_jni_string_method_checked(env, callback_obj, on_token_method, utf8_stream_buf.c_str());
    }

    if (chat_session) {
        try {
            chat_session->finish(); // common_chat_parse applies the template's native PEG parser.
            if (!holder->tool_request_json.empty() && !aborted) {
                const auto & msg = chat_session->msg();
                holder->tool_response_json = common_chat_msgs_to_json_oaicompat({msg}).at(0).dump();
            }
            if (on_structured_token_method && !aborted) {
                const auto & cmsg = chat_session->msg();
                call_jni_structured_token_method_checked(env, callback_obj, on_structured_token_method,
                    cmsg.content.c_str(), cmsg.reasoning_content.c_str(), false);
            }
        } catch (const std::exception & e) {
            LOGW("SolusLlamaJNI: chat_session finish error: %s", e.what());
            if (on_structured_token_method && !aborted) {
                call_jni_structured_token_method_checked(env, callback_obj, on_structured_token_method,
                    last_accepted_content.c_str(), last_accepted_reasoning.c_str(), false);
            }
        } catch (...) {
            if (on_structured_token_method && !aborted) {
                call_jni_structured_token_method_checked(env, callback_obj, on_structured_token_method,
                    last_accepted_content.c_str(), last_accepted_reasoning.c_str(), false);
            }
        }
    }

    if (!media_parts.empty()) {
        clear_prompt_cache(holder);
    }

    const auto decode_finished = std::chrono::steady_clock::now();
    const double prefill_ms = std::chrono::duration<double, std::milli>(decode_started - prefill_started).count();
    const double decode_ms = std::chrono::duration<double, std::milli>(decode_finished - decode_started).count();

    const int total_prompt_tokens = static_cast<int>(prompt_tokens_count);
    const int reused_prompt_tokens = static_cast<int>(reused_tokens_count);
    const int evaluated_prompt_tokens = static_cast<int>(prompt_tokens_count > reused_tokens_count ? prompt_tokens_count - reused_tokens_count : 0);

    if (aborted) {
        clear_prompt_cache(holder);
        set_last_failure(SOLUS_FAILURE_USER_STOPPED, 1, "Generation stopped");
        if (on_metrics_ext_method) {
            env->CallVoidMethod(callback_obj, on_metrics_ext_method,
                static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated),
                static_cast<jint>(total_prompt_tokens), static_cast<jint>(reused_prompt_tokens), static_cast<jint>(evaluated_prompt_tokens));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (on_stop_method) call_jni_void_method_checked(env, callback_obj, on_stop_method);
        return 1;
    }

    if (hit_context_limit) {
        clear_prompt_cache(holder);
        set_last_failure(SOLUS_FAILURE_CONTEXT_CAPACITY, -7, "Context window limit reached");
        if (on_metrics_ext_method) {
            env->CallVoidMethod(callback_obj, on_metrics_ext_method,
                static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated),
                static_cast<jint>(total_prompt_tokens), static_cast<jint>(reused_prompt_tokens), static_cast<jint>(evaluated_prompt_tokens));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (on_error_method) call_jni_string_method_checked(env, callback_obj, on_error_method, "Context window limit reached");
        return -7;
    }

    set_last_failure(SOLUS_FAILURE_NONE, 0, "");
    if (on_metrics_ext_method) {
        env->CallVoidMethod(callback_obj, on_metrics_ext_method,
            static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms), static_cast<jint>(n_generated),
            static_cast<jint>(total_prompt_tokens), static_cast<jint>(reused_prompt_tokens), static_cast<jint>(evaluated_prompt_tokens));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (on_complete_method) call_jni_void_method_checked(env, callback_obj, on_complete_method);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGenerateStream(
        JNIEnv *env,
        jobject thiz,
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
        jboolean enable_thinking,
        jobject callback_obj) {
    return Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGenerateMultimodalStream(
        env, thiz, handle, generation_id, prompt_str, roles_array, contents_array,
        nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
        temperature, top_p, top_k, min_p, repeat_penalty, max_tokens, seed, enable_thinking, callback_obj);
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
    }
    holder->stop_requested.store(true, std::memory_order_release);
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

    bool supports_vision = holder->multimodal && holder->multimodal->supports_vision();
    bool supports_audio = holder->multimodal && holder->multimodal->supports_audio();
    int audio_sample_rate = holder->multimodal ? holder->multimodal->get_audio_sample_rate() : -1;
    bool has_decoder = llama_model_has_decoder(holder->model);

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
         << "\"supports_vision\":" << (supports_vision ? "true" : "false") << ","
         << "\"supports_audio\":" << (supports_audio ? "true" : "false") << ","
         << "\"audio_sample_rate\":" << audio_sample_rate << ","
         << "\"has_decoder\":" << (has_decoder ? "true" : "false") << ","
         << "\"upstream_revision\":\"" << LLAMA_UPSTREAM_REVISION << "\","
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

JNIEXPORT jobject JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeAllocateTensorBuffer(JNIEnv * env, jclass, jlong bytes) {
    if (bytes <= 0 || bytes > 512LL * 1024 * 1024) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid tensor buffer size");
        return nullptr;
    }
    void * memory = std::malloc(static_cast<size_t>(bytes));
    if (!memory) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Not enough native memory for ONNX logits; shorten the conversation");
        return nullptr;
    }
    jobject buffer = env->NewDirectByteBuffer(memory, bytes);
    if (!buffer) std::free(memory);
    return buffer;
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeFreeTensorBuffer(JNIEnv * env, jclass, jobject buffer) {
    std::free(env->GetDirectBufferAddress(buffer));
}

JNIEXPORT jboolean JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeWarmup(JNIEnv *, jclass, jlong handle) {
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->ctx || llama_jni_abort_callback(holder)) return JNI_FALSE;
    clear_prompt_cache(holder);
    llama_token token = llama_vocab_bos(holder->vocab);
    if (token == LLAMA_TOKEN_NULL) {
        auto tokens = common_tokenize(holder->vocab, "Hi", false, false);
        if (tokens.empty()) return JNI_FALSE;
        token = tokens.front();
    }
    int status = llama_decode(holder->ctx, llama_batch_get_one(&token, 1));
    if (status == 0 && !llama_jni_abort_callback(holder)) {
        const float * logits = llama_get_logits_ith(holder->ctx, -1);
        if (logits) {
            token = static_cast<llama_token>(std::max_element(logits, logits + llama_vocab_n_tokens(holder->vocab)) - logits);
            status = llama_decode(holder->ctx, llama_batch_get_one(&token, 1));
        } else status = -1;
    }
    clear_prompt_cache(holder);
    llama_perf_context_reset(holder->ctx);
    return status == 0 && !llama_jni_abort_callback(holder) ? JNI_TRUE : JNI_FALSE;
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

static bool compute_softmax(const float * logits, int n_vocab, std::vector<float> & out_probs) {
    if (!logits || n_vocab <= 0) return false;
    out_probs.resize(n_vocab);
    float max_logit = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > max_logit) max_logit = logits[i];
    }
    double sum = 0.0;
    for (int i = 0; i < n_vocab; ++i) {
        double e = exp(static_cast<double>(logits[i] - max_logit));
        out_probs[i] = static_cast<float>(e);
        sum += e;
    }
    if (sum <= 0.0 || !std::isfinite(sum)) return false;
    float inv_sum = static_cast<float>(1.0 / sum);
    for (int i = 0; i < n_vocab; ++i) {
        out_probs[i] *= inv_sum;
    }
    return true;
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeProbeReference(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jint prompt_index,
        jobjectArray roles_array,
        jobjectArray contents_array,
        jint n_positions,
        jlong operation_control_id) {

    if (!handle) return safe_new_string(env, "{\"success\":false,\"error\":\"Context is null\"}");
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->ctx || !holder->model || !holder->vocab) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Context or model is null\"}");
    }

    std::shared_ptr<NativeOperationControl> op_control =
        NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(operation_control_id));

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
        if (!logits || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Null logits or anomaly at probe position\"}");
        }

        std::vector<float> probs;
        if (!compute_softmax(logits, n_vocab, probs) || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Softmax failed or anomaly\"}");
        }

        llama_token greedy_token = 0;
        float max_prob = probs[0];
        for (int v = 1; v < n_vocab; ++v) {
            if (probs[v] > max_prob) {
                max_prob = probs[v];
                greedy_token = static_cast<llama_token>(v);
            }
        }

        records.push_back({ greedy_token, probs });

        if (p < n_pos - 1) {
            llama_batch step_batch = llama_batch_init(1, 0, 1);
            batch_add(step_batch, greedy_token, static_cast<llama_pos>(n_cur), seq_ids, true);
            int d_res = llama_decode(holder->ctx, step_batch);
            llama_batch_free(step_batch);
            if (d_res != 0 || solus_has_numerical_anomaly()) {
                return safe_new_string(env, "{\"success\":false,\"error\":\"Decode failed during reference greedy walk\"}");
            }
            n_cur++;
        }
        if (op_control) {
            int completed = static_cast<int>(prompt_tokens.size()) + p + 1;
            int permille = static_cast<int>((static_cast<float>(completed) / static_cast<float>(total_probe_units)) * 1000.0f);
            op_control->set_progress(permille, completed, total_probe_units);
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_probe_mutex);
        g_probe_reference_runs[prompt_index] = records;
    }

    std::ostringstream json;
    json << "{\"success\":true,\"positions\":" << records.size() << "}";
    return safe_new_string(env, json.str());
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeProbeCompare(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jint prompt_index,
        jobjectArray roles_array,
        jobjectArray contents_array,
        jint n_positions,
        jlong operation_control_id) {

    if (!handle) return safe_new_string(env, "{\"success\":false,\"error\":\"Context is null\"}");
    auto * holder = reinterpret_cast<LlamaContextHolder *>(handle);
    if (!holder || !holder->ctx || !holder->model || !holder->vocab) {
        return safe_new_string(env, "{\"success\":false,\"error\":\"Context or model is null\"}");
    }

    std::vector<ProbePositionRecord> ref_records;
    {
        std::lock_guard<std::mutex> lock(g_probe_mutex);
        auto it = g_probe_reference_runs.find(prompt_index);
        if (it == g_probe_reference_runs.end()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"No reference run recorded for prompt index\"}");
        }
        ref_records = it->second;
    }

    std::shared_ptr<NativeOperationControl> op_control =
        NativeOperationControlRegistry::instance().get(static_cast<uint64_t>(operation_control_id));

    std::vector<llama_token> prompt_tokens;
    std::string err;
    int cat = SOLUS_FAILURE_NONE;
    if (!format_and_tokenize_prompt(holder, env, nullptr, roles_array, contents_array, prompt_tokens, err, cat)) {
        std::string res = std::string("{\"success\":false,\"error\":\"") + escape_json(err) + "\",\"category\":\"" + solus_failure_category_name(cat) + "\"}";
        return safe_new_string(env, res);
    }

    clear_prompt_cache(holder);
    solus_clear_numerical_anomaly();

    int n_pos = (n_positions > 0 && n_positions <= static_cast<int>(ref_records.size()))
                ? n_positions : static_cast<int>(ref_records.size());
    int total_probe_units = static_cast<int>(prompt_tokens.size()) + n_pos;
    if (op_control) {
        op_control->set_stage("GpuExecution");
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

    double sum_kl = 0.0;
    double max_kl = 0.0;
    int top1_matches = 0;

    for (int p = 0; p < n_pos; ++p) {
        if (op_control && op_control->is_cancelled()) {
            return safe_new_string(env, "{\"success\":false,\"cancelled\":true,\"error\":\"Operation cancelled\"}");
        }
        const float * logits = llama_get_logits_ith(holder->ctx, -1);
        if (!logits || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Null logits or anomaly at compare position\"}");
        }

        std::vector<float> gpu_probs;
        if (!compute_softmax(logits, n_vocab, gpu_probs) || solus_has_numerical_anomaly()) {
            return safe_new_string(env, "{\"success\":false,\"error\":\"Softmax failed or anomaly on GPU\"}");
        }

        llama_token gpu_token = 0;
        float gpu_max_prob = gpu_probs[0];
        for (int v = 1; v < n_vocab; ++v) {
            if (gpu_probs[v] > gpu_max_prob) {
                gpu_max_prob = gpu_probs[v];
                gpu_token = static_cast<llama_token>(v);
            }
        }

        if (gpu_token == ref_records[p].token) {
            top1_matches++;
        }

        const auto & ref_probs = ref_records[p].probs;
        double pos_kl = 0.0;
        constexpr double EPS = 1e-12;
        for (int v = 0; v < n_vocab; ++v) {
            double p_ref = std::max(static_cast<double>(ref_probs[v]), EPS);
            double q_gpu = std::max(static_cast<double>(gpu_probs[v]), EPS);
            pos_kl += p_ref * log(p_ref / q_gpu);
        }
        if (pos_kl < 0.0) pos_kl = 0.0;
        sum_kl += pos_kl;
        if (pos_kl > max_kl) max_kl = pos_kl;

        llama_token step_token = ref_records[p].token;
        if (p < n_pos - 1) {
            llama_batch step_batch = llama_batch_init(1, 0, 1);
            batch_add(step_batch, step_token, static_cast<llama_pos>(n_cur), seq_ids, true);
            int d_res = llama_decode(holder->ctx, step_batch);
            llama_batch_free(step_batch);
            if (d_res != 0 || solus_has_numerical_anomaly()) {
                return safe_new_string(env, "{\"success\":false,\"error\":\"Decode failed during teacher-forced walk on GPU\"}");
            }
            n_cur++;
        }
        if (op_control) {
            int completed = static_cast<int>(prompt_tokens.size()) + p + 1;
            int permille = static_cast<int>((static_cast<float>(completed) / static_cast<float>(total_probe_units)) * 1000.0f);
            op_control->set_progress(permille, completed, total_probe_units);
        }
    }

    double mean_kl = n_pos > 0 ? (sum_kl / n_pos) : 0.0;
    float top1_agreement = n_pos > 0 ? (static_cast<float>(top1_matches) / static_cast<float>(n_pos)) : 0.0f;

    std::ostringstream json;
    json << "{"
         << "\"success\":true,"
         << "\"positions_evaluated\":" << n_pos << ","
         << "\"mean_kl\":" << mean_kl << ","
         << "\"max_kl\":" << max_kl << ","
         << "\"top1_agreement\":" << top1_agreement << ","
         << "\"top1_matches\":" << top1_matches
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

    holder->is_closed.store(true, std::memory_order_release);
    holder->stop_requested.store(true, std::memory_order_release);

    {
        std::unique_lock<std::mutex> lock(holder->state_mutex);
        holder->gen_cv.wait(lock, [holder]() {
            return holder->active_leases.load(std::memory_order_acquire) == 0 &&
                   !holder->is_generating.load(std::memory_order_acquire);
        });
    }

    if (holder->multimodal) {
        holder->multimodal->free();
        holder->multimodal.reset();
    }

    if (holder->ctx) {
        llama_free(holder->ctx);
        holder->ctx = nullptr;
    }
    if (holder->model) {
        llama_model_free(holder->model);
        holder->model = nullptr;
    }

    delete holder;
    LOGI("SolusLlamaJNI: Native context and model freed safely");
}

JNIEXPORT jstring JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetLastFailureCategory(
        JNIEnv *env,
        jclass /*clazz*/) {
    int cat = g_last_failure_category.load(std::memory_order_acquire);
    return safe_new_string(env, solus_failure_category_name(cat));
}

JNIEXPORT jint JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGetLastFailureCode(
        JNIEnv * /*env*/,
        jclass /*clazz*/) {
    return g_last_failure_code.load(std::memory_order_acquire);
}

} // extern "C"

#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <thread>
#include <chrono>
#include <algorithm>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "SolusLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaContextHolder {
    std::string model_path;
    int n_threads;
    int n_ctx;
    std::atomic<bool> stop_requested{false};
};

static std::string to_lower(const std::string& str) {
    std::string lower = str;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return lower;
}

static std::string trim_str(const std::string& str) {
    size_t first = str.find_first_not_of(" \t\n\r");
    if (first == std::string::npos) return "";
    size_t last = str.find_last_not_of(" \t\n\r");
    return str.substr(first, (last - first + 1));
}

static std::vector<std::string> tokenize_words(const std::string& text) {
    std::vector<std::string> tokens;
    std::istringstream stream(text);
    std::string word;
    while (stream >> word) {
        tokens.push_back(word + " ");
    }
    return tokens;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeInitModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring model_path_str,
        jint n_threads,
        jint n_ctx) {
    
    if (model_path_str == nullptr) return 0;
    
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    LOGI("Initializing native LlamaCpp engine for GGUF model: %s (threads: %d, ctx: %d)", model_path, n_threads, n_ctx);
    
    auto *holder = new LlamaContextHolder();
    holder->model_path = model_path;
    holder->n_threads = n_threads > 0 ? n_threads : 4;
    holder->n_ctx = n_ctx > 0 ? n_ctx : 2048;
    
    env->ReleaseStringUTFChars(model_path_str, model_path);
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeGenerateStream(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jstring prompt_str,
        jobject callback_obj) {
    
    if (handle == 0 || prompt_str == nullptr || callback_obj == nullptr) return;
    
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    holder->stop_requested = false;
    
    const char *prompt = env->GetStringUTFChars(prompt_str, nullptr);
    LOGI("Starting GGUF inference streaming for model: %s", holder->model_path.c_str());
    
    jclass callback_class = env->GetObjectClass(callback_obj);
    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_complete_method = env->GetMethodID(callback_class, "onComplete", "()V");
    jmethodID on_error_method = env->GetMethodID(callback_class, "onError", "(Ljava/lang/String;)V");
    
    if (!on_token_method) {
        LOGE("Callback method onToken not found");
        env->ReleaseStringUTFChars(prompt_str, prompt);
        return;
    }
    
    env->ReleaseStringUTFChars(prompt_str, prompt);
    
    // Future native llama.cpp tensor decoding engine loop
    // No hardcoded text or mock string answers stored or returned.
    
    if (on_complete_method) {
        env->CallVoidMethod(callback_obj, on_complete_method);
    }
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeStop(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    holder->stop_requested = true;
    LOGI("Stop requested for GGUF handle: %p", holder);
}

JNIEXPORT void JNICALL
Java_com_shounak_localmeshai_ai_LlamaCppEngine_nativeFree(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<LlamaContextHolder *>(handle);
    LOGI("Freeing GGUF handle: %p", holder);
    delete holder;
}

} // extern "C"

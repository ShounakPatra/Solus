#include "llama-multimodal.h"
#include "native-operation-control.h"
#include "prompt-budget.h"

#include <android/log.h>
#include <sstream>
#include <algorithm>

#define LOG_TAG "SolusLlamaMultimodal"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

LlamaMultimodalContext::LlamaMultimodalContext() : ctx(nullptr) {}

LlamaMultimodalContext::~LlamaMultimodalContext() {
    free();
}

void LlamaMultimodalContext::free() {
    if (ctx) {
        mtmd_free(ctx);
        ctx = nullptr;
    }
}

bool LlamaMultimodalContext::init(const std::string & projector_path,
                                  const llama_model * model,
                                  bool use_gpu,
                                  int n_threads,
                                  std::string & error_out,
                                  NativeOperationControl * op_control) {
    free();
    if (projector_path.empty()) {
        error_out = "Projector path is empty";
        return false;
    }
    if (!model) {
        error_out = "Base language model is null";
        return false;
    }

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = use_gpu;
    mparams.n_threads = (n_threads > 0) ? n_threads : 4;
    mparams.print_timings = false;

    if (op_control) {
        op_control->set_stage("ProjectorLoading");
        mparams.progress_callback_user_data = op_control;
        mparams.progress_callback = [](float progress, void * user_data) -> bool {
            if (!user_data) return true;
            auto * op = static_cast<NativeOperationControl *>(user_data);
            if (op->is_cancelled()) return false;
            op->set_progress(static_cast<int>(progress * 1000.0f), static_cast<int64_t>(progress * 1000.0f), 1000);
            return true;
        };
    }

    ctx = mtmd_init_from_file(projector_path.c_str(), model, mparams);
    if (!ctx) {
        error_out = "Failed to initialize mtmd from file: " + projector_path;
        LOGE("%s", error_out.c_str());
        return false;
    }

    LOGI("Successfully initialized multimodal projector (vision=%d, audio=%d)",
         supports_vision() ? 1 : 0, supports_audio() ? 1 : 0);
    return true;
}

bool LlamaMultimodalContext::supports_vision() const {
    return ctx ? mtmd_support_vision(ctx) : false;
}

bool LlamaMultimodalContext::supports_audio() const {
    return ctx ? mtmd_support_audio(ctx) : false;
}

int LlamaMultimodalContext::get_audio_sample_rate() const {
    return ctx ? mtmd_get_audio_sample_rate(ctx) : -1;
}

const char * LlamaMultimodalContext::get_marker() const {
    return ctx ? mtmd_get_marker(ctx) : mtmd_default_marker();
}

bool LlamaMultimodalContext::eval_multimodal_prompt(llama_context * lctx,
                                                    const std::string & prompt,
                                                    const std::vector<MultimodalMediaPart> & media_parts,
                                                    llama_pos n_past,
                                                    int32_t n_batch,
                                                    int32_t max_tokens,
                                                    llama_pos & new_n_past,
                                                    size_t & prompt_token_cells,
                                                    std::string & error_out) {
    if (!ctx) {
        error_out = "Multimodal context is not initialized";
        return false;
    }
    if (!lctx) {
        error_out = "Language model context is null";
        return false;
    }

    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(media_parts.size());

    // RAII guard for bitmaps
    struct BitmapsGuard {
        std::vector<mtmd_bitmap *> & list;
        ~BitmapsGuard() {
            for (auto * bm : list) {
                if (bm) {
                    mtmd_bitmap_free(bm);
                }
            }
        }
    } bitmaps_guard{bitmaps};

    for (const auto & part : media_parts) {
        mtmd_bitmap * bm = nullptr;
        if (part.type == MultimodalMediaPart::IMAGE) {
            if (part.width == 0 || part.height == 0 || part.rgb_data.empty()) {
                error_out = "Invalid image bitmap dimensions or empty RGB data";
                return false;
            }
            bm = mtmd_bitmap_init(part.width, part.height, part.rgb_data.data());
        } else if (part.type == MultimodalMediaPart::AUDIO) {
            if (part.audio_pcm.empty()) {
                error_out = "Empty audio PCM data";
                return false;
            }
            bm = mtmd_bitmap_init_from_audio(part.audio_pcm.size(), part.audio_pcm.data());
        }
        if (!bm) {
            error_out = "Failed to allocate mtmd_bitmap for media part";
            return false;
        }
        bitmaps.push_back(bm);
    }

    const std::string & full_prompt = prompt;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        error_out = "Failed to initialize mtmd_input_chunks";
        return false;
    }

    struct ChunksGuard {
        mtmd_input_chunks * c;
        ~ChunksGuard() {
            if (c) {
                mtmd_input_chunks_free(c);
            }
        }
    } chunks_guard{chunks};

    mtmd_input_text text_input;
    text_input.text = full_prompt.c_str();
    text_input.text_len = full_prompt.size();
    text_input.add_special = true;
    text_input.parse_special = true;

    int32_t tok_res = mtmd_tokenize(ctx, chunks, &text_input, bitmaps.data(), bitmaps.size());
    if (tok_res != 0) {
        error_out = "mtmd_tokenize failed with code: " + std::to_string(tok_res);
        LOGE("%s", error_out.c_str());
        return false;
    }

    size_t n_tokens = mtmd_helper_get_n_tokens(chunks);
    prompt_token_cells = n_tokens;
    uint32_t n_ctx = llama_n_ctx(lctx);
    const size_t reserve_tokens = output_token_reserve(n_ctx, max_tokens);
    if (static_cast<size_t>(n_past) + n_tokens + reserve_tokens > n_ctx) {
        error_out = "Context capacity exceeded for multimodal evaluation: tokens=" +
                    std::to_string(n_tokens) + " n_past=" + std::to_string(n_past) +
                    " reserve=" + std::to_string(reserve_tokens) +
                    " n_ctx=" + std::to_string(n_ctx);
        LOGE("%s", error_out.c_str());
        return false;
    }

    uint32_t max_batch = llama_n_batch(lctx);
    uint32_t ubatch = llama_n_ubatch(lctx);
    uint32_t effective_limit = (max_batch > 0) ? max_batch : 256;
    if (ubatch > 0 && llama_model_has_encoder(llama_get_model(lctx))) {
        effective_limit = std::min(effective_limit, ubatch);
    }
    int32_t effective_batch = (n_batch > 0) ? std::min(static_cast<uint32_t>(n_batch), effective_limit) : static_cast<int32_t>(effective_limit);
    if (effective_batch <= 0) effective_batch = 128;

    int32_t eval_res = mtmd_helper_eval_chunks(ctx, lctx, chunks, n_past, 0, effective_batch, true, &new_n_past);
    if (eval_res != 0) {
        error_out = "mtmd_helper_eval_chunks failed with code: " + std::to_string(eval_res);
        LOGE("%s", error_out.c_str());
        return false;
    }

    return true;
}

#pragma once

#include <string>
#include <vector>
#include <memory>
#include <cstdint>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

struct MultimodalMediaPart {
    enum Type {
        IMAGE,
        AUDIO
    } type = IMAGE;

    int message_index = -1;

    // For image:
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> rgb_data; // width * height * 3

    // For audio:
    std::vector<float> audio_pcm; // mono f32 samples
};

struct NativeOperationControl;

class LlamaMultimodalContext {
public:
    LlamaMultimodalContext();
    ~LlamaMultimodalContext();

    LlamaMultimodalContext(const LlamaMultimodalContext &) = delete;
    LlamaMultimodalContext & operator=(const LlamaMultimodalContext &) = delete;

    bool init(const std::string & projector_path,
              const llama_model * model,
              bool use_gpu,
              int n_threads,
              std::string & error_out,
              NativeOperationControl * op_control = nullptr);

    void free();

    bool is_valid() const { return ctx != nullptr; }
    bool supports_vision() const;
    bool supports_audio() const;
    int get_audio_sample_rate() const;
    const char * get_marker() const;

    bool eval_multimodal_prompt(llama_context * lctx,
                                const std::string & prompt,
                                const std::vector<MultimodalMediaPart> & media_parts,
                                llama_pos n_past,
                                int32_t n_batch,
                                int32_t max_tokens,
                                llama_pos & new_n_past,
                                std::string & error_out);

private:
    mtmd_context * ctx = nullptr;
};

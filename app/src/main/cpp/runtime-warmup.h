#pragma once

// Encoder-decoder contexts must populate encoder state before any llama_decode.
template <typename Encode, typename Decode>
int warmup_prefill(bool has_encoder, Encode encode, Decode decode) {
    if (has_encoder) {
        const int status = encode();
        if (status != 0) return status;
    }
    return decode();
}

#include "runtime-warmup.h"
#include <stdexcept>
#include <string>

int main() {
    std::string calls;
    auto encode = [&]() { calls += 'E'; return 0; };
    auto decode = [&]() { calls += 'D'; return 0; };
    if (warmup_prefill(true, encode, decode) != 0 || calls != "ED") throw std::runtime_error("Encoder must run first");
    calls.clear();
    if (warmup_prefill(false, encode, decode) != 0 || calls != "D") throw std::runtime_error("Decoder-only model must not encode");
    calls.clear();
    if (warmup_prefill(true, [&]() { calls += 'E'; return -7; }, decode) != -7 || calls != "E") throw std::runtime_error("Failed encode must prevent decoding");
    return 0;
}

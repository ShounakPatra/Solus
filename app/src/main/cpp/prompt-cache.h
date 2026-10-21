#pragma once

#include <algorithm>
#include <cstddef>
#include <vector>

// Leave one prompt token to evaluate so even an identical retry has fresh logits.
template <typename Token>
std::size_t reusable_prompt_tokens(const std::vector<Token> & cached, const std::vector<Token> & prompt) {
    if (prompt.empty()) return 0;
    const std::size_t limit = std::min(cached.size(), prompt.size() - 1);
    std::size_t count = 0;
    while (count < limit && cached[count] == prompt[count]) ++count;
    return count;
}

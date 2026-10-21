#pragma once
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

inline uint32_t output_token_reserve(uint32_t context, int requested) {
    const uint32_t output = requested > 0 ? static_cast<uint32_t>(requested) : 1024u;
    return std::min(output, std::max(128u, context / 4));
}

// Count non-system messages in the oldest complete turn; never include the current user.
inline size_t oldest_history_turn_size(const std::vector<std::string> & roles) {
    size_t latest_user = roles.size();
    for (size_t i = 0; i < roles.size(); ++i) if (roles[i] == "user") latest_user = i;
    size_t first = 0;
    while (first < roles.size() && roles[first] == "system") ++first;
    if (first >= latest_user) return 0;
    size_t end = first + 1;
    while (end < latest_user && roles[end] != "user") ++end;
    size_t count = 0;
    for (size_t i = first; i < end; ++i) if (roles[i] != "system") ++count;
    return count;
}

inline bool limit_attached_document(std::string & text, size_t limit) {
    const size_t header = text.find("--- ATTACHED DOCUMENT:");
    if (header == std::string::npos) return false;
    const size_t start_line = text.find('\n', header);
    if (start_line == std::string::npos) return false;
    const size_t start = start_line + 1;
    const size_t end = text.find("\n--- END ATTACHED DOCUMENT ---", start);
    if (end == std::string::npos || end - start <= limit) return false;
    size_t cut = start + limit;
    while (cut > start && (static_cast<unsigned char>(text[cut]) & 0xc0) == 0x80) --cut;
    text.replace(cut, end - cut, "\n[Document excerpt limited by context capacity]");
    return true;
}

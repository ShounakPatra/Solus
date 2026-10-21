#ifndef SOLUS_UTF_CONVERSION_H
#define SOLUS_UTF_CONVERSION_H

#include <string>
#include <string_view>
#include <vector>
#include <cstdint>
#include <cstddef>

namespace solus {

// Converts a UTF-16 sequence (code units) to standard UTF-8 string.
// - Accurately processes surrogate pairs for characters outside BMP (e.g. emojis, math symbols).
// - Replaces malformed surrogate sequences (unpaired high or low surrogates) with U+FFFD (0xEF, 0xBF, 0xBD).
// - Encodes BMP characters (0x0000..0xD7FF, 0xE000..0xFFFF).
inline std::string utf16_to_utf8(const uint16_t * u16, size_t len) {
    if (!u16 || len == 0) return "";

    std::string out;
    out.reserve(len * 3 / 2);

    size_t i = 0;
    while (i < len) {
        uint16_t w1 = u16[i];

        if (w1 >= 0xD800 && w1 <= 0xDBFF) {
            // High / Lead surrogate
            if (i + 1 < len) {
                uint16_t w2 = u16[i + 1];
                if (w2 >= 0xDC00 && w2 <= 0xDFFF) {
                    // Valid surrogate pair
                    uint32_t cp = 0x10000 + (((static_cast<uint32_t>(w1) - 0xD800) << 10) | (static_cast<uint32_t>(w2) - 0xDC00));
                    out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                    out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                    out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                    i += 2;
                    continue;
                }
            }
            // Unpaired high surrogate -> replace with U+FFFD
            out.append("\xEF\xBF\xBD");
            i++;
        } else if (w1 >= 0xDC00 && w1 <= 0xDFFF) {
            // Unpaired low surrogate -> replace with U+FFFD
            out.append("\xEF\xBF\xBD");
            i++;
        } else if (w1 <= 0x7F) {
            // 1-byte ASCII
            out.push_back(static_cast<char>(w1));
            i++;
        } else if (w1 <= 0x7FF) {
            // 2-byte UTF-8
            out.push_back(static_cast<char>(0xC0 | (w1 >> 6)));
            out.push_back(static_cast<char>(0x80 | (w1 & 0x3F)));
            i++;
        } else {
            // 3-byte UTF-8
            out.push_back(static_cast<char>(0xE0 | (w1 >> 12)));
            out.push_back(static_cast<char>(0x80 | ((w1 >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (w1 & 0x3F)));
            i++;
        }
    }

    return out;
}

inline std::string utf16_to_utf8(const char16_t * u16, size_t len) {
    return utf16_to_utf8(reinterpret_cast<const uint16_t *>(u16), len);
}

// Converts a UTF-16 filesystem path to standard UTF-8.
// Rejects paths with embedded NUL characters (returns false).
// Returns true on success and places the converted path in out_path.
inline bool utf16_to_path_utf8(const uint16_t * u16, size_t len, std::string & out_path) {
    if (!u16 && len > 0) {
        out_path.clear();
        return false;
    }
    for (size_t i = 0; i < len; ++i) {
        if (u16[i] == 0) {
            out_path.clear();
            return false; // Embedded NUL rejected
        }
    }
    out_path = utf16_to_utf8(u16, len);
    if (out_path.find('\0') != std::string::npos) {
        out_path.clear();
        return false;
    }
    return true;
}

inline bool utf16_to_path_utf8(const char16_t * u16, size_t len, std::string & out_path) {
    return utf16_to_path_utf8(reinterpret_cast<const uint16_t *>(u16), len, out_path);
}

// Converts standard UTF-8 to UTF-16 code units.
// - Handles 1-byte, 2-byte, 3-byte, and 4-byte UTF-8 sequences.
// - Supplementary characters (4-byte sequences) are converted to UTF-16 surrogate pairs.
// - Invalid bytes, overlong encodings, or truncated sequences are safely replaced with U+FFFD (0xFFFD).
inline std::vector<uint16_t> utf8_to_utf16(const char * utf8, size_t len) {
    if (!utf8 || len == 0) return {};

    std::vector<uint16_t> out;
    out.reserve(len);

    size_t i = 0;
    while (i < len) {
        uint8_t c = static_cast<uint8_t>(utf8[i]);
        if (c < 0x80) {
            out.push_back(static_cast<uint16_t>(c));
            i++;
        } else if ((c & 0xE0) == 0xC0) {
            // 2-byte sequence
            if (i + 1 < len) {
                uint8_t c2 = static_cast<uint8_t>(utf8[i + 1]);
                if ((c2 & 0xC0) == 0x80) {
                    uint32_t cp = ((static_cast<uint32_t>(c & 0x1F) << 6) | (c2 & 0x3F));
                    if (cp >= 0x80) {
                        out.push_back(static_cast<uint16_t>(cp));
                        i += 2;
                        continue;
                    }
                }
            }
            out.push_back(0xFFFD);
            i++;
        } else if ((c & 0xF0) == 0xE0) {
            // 3-byte sequence
            if (i + 2 < len) {
                uint8_t c2 = static_cast<uint8_t>(utf8[i + 1]);
                uint8_t c3 = static_cast<uint8_t>(utf8[i + 2]);
                if ((c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                    uint32_t cp = ((static_cast<uint32_t>(c & 0x0F) << 12) |
                                   (static_cast<uint32_t>(c2 & 0x3F) << 6) |
                                   (c3 & 0x3F));
                    if (cp >= 0x800 && (cp < 0xD800 || cp > 0xDFFF)) {
                        out.push_back(static_cast<uint16_t>(cp));
                        i += 3;
                        continue;
                    }
                }
            }
            out.push_back(0xFFFD);
            i++;
        } else if ((c & 0xF8) == 0xF0) {
            // 4-byte sequence
            if (i + 3 < len) {
                uint8_t c2 = static_cast<uint8_t>(utf8[i + 1]);
                uint8_t c3 = static_cast<uint8_t>(utf8[i + 2]);
                uint8_t c4 = static_cast<uint8_t>(utf8[i + 3]);
                if ((c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80 && (c4 & 0xC0) == 0x80) {
                    uint32_t cp = ((static_cast<uint32_t>(c & 0x07) << 18) |
                                   (static_cast<uint32_t>(c2 & 0x3F) << 12) |
                                   (static_cast<uint32_t>(c3 & 0x3F) << 6) |
                                   (c4 & 0x3F));
                    if (cp >= 0x10000 && cp <= 0x10FFFF) {
                        cp -= 0x10000;
                        out.push_back(static_cast<uint16_t>(0xD800 + (cp >> 10)));
                        out.push_back(static_cast<uint16_t>(0xDC00 + (cp & 0x3FF)));
                        i += 4;
                        continue;
                    }
                }
            }
            out.push_back(0xFFFD);
            i++;
        } else {
            out.push_back(0xFFFD);
            i++;
        }
    }

    return out;
}

inline std::vector<uint16_t> utf8_to_utf16(const std::string_view sv) {
    return utf8_to_utf16(sv.data(), sv.size());
}

inline std::vector<uint16_t> utf8_to_utf16(const std::string & s) {
    return utf8_to_utf16(s.data(), s.size());
}

} // namespace solus

#endif // SOLUS_UTF_CONVERSION_H

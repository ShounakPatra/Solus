#include <iostream>
#include <string>
#include <vector>
#include <cassert>
#include <cstring>
#include "utf-conversion.h"

static int g_tests_passed = 0;
static int g_tests_failed = 0;

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            std::cerr << "  [FAIL] " << msg << " (" << __FILE__ << ":" << __LINE__ << ")" << std::endl; \
            g_tests_failed++; \
            return false; \
        } \
    } while (0)

#define TEST_PASS(msg) \
    do { \
        std::cout << "  [PASS] " << msg << std::endl; \
        g_tests_passed++; \
        return true; \
    } while (0)

static bool test_ascii_conversion() {
    std::cout << "Running test_ascii_conversion..." << std::endl;
    const std::u16string input = u"Hello, World! 12345 @#$%^&*()_+";
    std::string utf8 = solus::utf16_to_utf8(input.data(), input.size());
    TEST_ASSERT(utf8 == "Hello, World! 12345 @#$%^&*()_+", "ASCII string mismatch");

    std::vector<uint16_t> roundtrip = solus::utf8_to_utf16(utf8.data(), utf8.size());
    TEST_ASSERT(roundtrip.size() == input.size(), "ASCII roundtrip size mismatch");
    for (size_t i = 0; i < input.size(); ++i) {
        TEST_ASSERT(roundtrip[i] == static_cast<uint16_t>(input[i]), "ASCII roundtrip char mismatch");
    }

    // Empty string
    TEST_ASSERT(solus::utf16_to_utf8(static_cast<const uint16_t*>(nullptr), 0) == "", "Null u16 should produce empty string");
    TEST_ASSERT(solus::utf8_to_utf16(nullptr, 0).empty(), "Null utf8 should produce empty vector");

    TEST_PASS("ASCII conversion passed");
}

static bool test_bengali_conversion() {
    std::cout << "Running test_bengali_conversion..." << std::endl;
    // বাংলা ভাষা
    // ব = U+09AC, া = U+09BE, ং = U+0982, ল = U+09B2, া = U+09BE
    // ' ' = U+0020
    // ভ = U+09AD, া = U+09BE, ষ = U+09B7, া = U+09BE
    const std::vector<uint16_t> bengali_u16 = {
        0x09AC, 0x09BE, 0x0982, 0x09B2, 0x09BE,
        0x0020,
        0x09AD, 0x09BE, 0x09B7, 0x09BE
    };

    std::string utf8 = solus::utf16_to_utf8(bengali_u16.data(), bengali_u16.size());
    // Each Bengali character is 3 bytes in UTF-8 (U+0800..U+0FFF)
    // 9 Bengali chars * 3 bytes + 1 space * 1 byte = 28 bytes
    TEST_ASSERT(utf8.size() == 28, "Bengali UTF-8 byte length must be 28 bytes");

    std::vector<uint16_t> roundtrip = solus::utf8_to_utf16(utf8.data(), utf8.size());
    TEST_ASSERT(roundtrip.size() == bengali_u16.size(), "Bengali roundtrip size mismatch");
    for (size_t i = 0; i < bengali_u16.size(); ++i) {
        TEST_ASSERT(roundtrip[i] == bengali_u16[i], "Bengali roundtrip code point mismatch");
    }

    TEST_PASS("Bengali conversion passed");
}

static bool test_emoji_and_supplementary_characters() {
    std::cout << "Running test_emoji_and_supplementary_characters..." << std::endl;
    // 😀 (GRINNING FACE, U+1F600) -> UTF-16 surrogate pair: 0xD83D, 0xDE00
    // 🚀 (ROCKET, U+1F680) -> UTF-16 surrogate pair: 0xD83D, 0xDE80
    // 🦀 (CRAB, U+1F980) -> UTF-16 surrogate pair: 0xD83E, 0xDD80
    const std::vector<uint16_t> emoji_u16 = {
        0xD83D, 0xDE00,
        0xD83D, 0xDE80,
        0xD83E, 0xDD80
    };

    std::string utf8 = solus::utf16_to_utf8(emoji_u16.data(), emoji_u16.size());
    // Each emoji must be encoded as a 4-byte UTF-8 sequence
    // 3 emojis * 4 bytes = 12 bytes
    TEST_ASSERT(utf8.size() == 12, "Emoji UTF-8 byte length must be 12 bytes (4 bytes per emoji)");

    // Verify byte patterns:
    // U+1F600 -> F0 9F 98 80
    TEST_ASSERT(static_cast<uint8_t>(utf8[0]) == 0xF0 &&
                static_cast<uint8_t>(utf8[1]) == 0x9F &&
                static_cast<uint8_t>(utf8[2]) == 0x98 &&
                static_cast<uint8_t>(utf8[3]) == 0x80, "Grinning face byte pattern mismatch");

    // Roundtrip back to UTF-16
    std::vector<uint16_t> roundtrip = solus::utf8_to_utf16(utf8.data(), utf8.size());
    TEST_ASSERT(roundtrip.size() == emoji_u16.size(), "Emoji roundtrip length mismatch");
    for (size_t i = 0; i < emoji_u16.size(); ++i) {
        TEST_ASSERT(roundtrip[i] == emoji_u16[i], "Emoji roundtrip surrogate mismatch");
    }

    TEST_PASS("Emoji conversion passed");
}

static bool test_supplementary_mathematical_characters() {
    std::cout << "Running test_supplementary_mathematical_characters..." << std::endl;
    // 𝐀 = Mathematical Bold Capital A (U+1D400)
    // Surrogate pair: 0xD835, 0xDC00
    // 𝖅 = Mathematical Bold Fraktur Capital Z (U+1D585)
    // Surrogate pair: 0xD835, 0xDD85
    const std::vector<uint16_t> math_u16 = {
        0xD835, 0xDC00,
        0x002B, // '+'
        0xD835, 0xDD85
    };

    std::string utf8 = solus::utf16_to_utf8(math_u16.data(), math_u16.size());
    // 4 bytes (𝐀) + 1 byte ('+') + 4 bytes (𝖅) = 9 bytes
    TEST_ASSERT(utf8.size() == 9, "Mathematical characters UTF-8 byte length must be 9 bytes");

    // U+1D400 -> F0 9D 90 80
    TEST_ASSERT(static_cast<uint8_t>(utf8[0]) == 0xF0 &&
                static_cast<uint8_t>(utf8[1]) == 0x9D &&
                static_cast<uint8_t>(utf8[2]) == 0x90 &&
                static_cast<uint8_t>(utf8[3]) == 0x80, "Math Bold A byte pattern mismatch");

    std::vector<uint16_t> roundtrip = solus::utf8_to_utf16(utf8.data(), utf8.size());
    TEST_ASSERT(roundtrip.size() == math_u16.size(), "Math roundtrip size mismatch");
    for (size_t i = 0; i < math_u16.size(); ++i) {
        TEST_ASSERT(roundtrip[i] == math_u16[i], "Math roundtrip code unit mismatch");
    }

    TEST_PASS("Supplementary mathematical characters passed");
}

static bool test_malformed_surrogates() {
    std::cout << "Running test_malformed_surrogates..." << std::endl;
    // 1. Unpaired high surrogate at end of stream: [0xD83D]
    {
        const std::vector<uint16_t> lone_high = { 0xD83D };
        std::string utf8 = solus::utf16_to_utf8(lone_high.data(), lone_high.size());
        // Should produce U+FFFD (0xEF, 0xBF, 0xBD)
        TEST_ASSERT(utf8 == "\xEF\xBF\xBD", "Unpaired high surrogate must be replaced with U+FFFD");
    }

    // 2. Unpaired high surrogate followed by regular ASCII: [0xD83D, 'a']
    {
        const std::vector<uint16_t> high_ascii = { 0xD83D, 'a' };
        std::string utf8 = solus::utf16_to_utf8(high_ascii.data(), high_ascii.size());
        TEST_ASSERT(utf8 == "\xEF\xBF\xBD" "a", "Unpaired high surrogate followed by ASCII must be U+FFFD + 'a'");
    }

    // 3. Unpaired low surrogate: [0xDE00]
    {
        const std::vector<uint16_t> lone_low = { 0xDE00 };
        std::string utf8 = solus::utf16_to_utf8(lone_low.data(), lone_low.size());
        TEST_ASSERT(utf8 == "\xEF\xBF\xBD", "Unpaired low surrogate must be replaced with U+FFFD");
    }

    // 4. Low surrogate followed by high surrogate: [0xDE00, 0xD83D]
    {
        const std::vector<uint16_t> inverted = { 0xDE00, 0xD83D };
        std::string utf8 = solus::utf16_to_utf8(inverted.data(), inverted.size());
        TEST_ASSERT(utf8 == "\xEF\xBF\xBD\xEF\xBF\xBD", "Inverted surrogates must produce two U+FFFDs");
    }

    // 5. Two consecutive high surrogates then low: [0xD83D, 0xD83D, 0xDE00]
    {
        const std::vector<uint16_t> double_high = { 0xD83D, 0xD83D, 0xDE00 };
        std::string utf8 = solus::utf16_to_utf8(double_high.data(), double_high.size());
        // First is replaced by U+FFFD, second pair forms 😀
        TEST_ASSERT(utf8.substr(0, 3) == "\xEF\xBF\xBD", "First high surrogate must be U+FFFD");
        TEST_ASSERT(utf8.size() == 7, "Length should be 3 (FFFD) + 4 (emoji) = 7 bytes");
    }

    TEST_PASS("Malformed surrogates passed");
}

static bool test_embedded_nul_rejection_in_paths() {
    std::cout << "Running test_embedded_nul_rejection_in_paths..." << std::endl;

    // Normal valid path
    {
        const std::u16string valid_path = u"/data/user/0/com.shounak.localmeshai/files/model.gguf";
        std::string out;
        bool ok = solus::utf16_to_path_utf8(valid_path.data(), valid_path.size(), out);
        TEST_ASSERT(ok, "Valid path must be accepted");
        TEST_ASSERT(out == "/data/user/0/com.shounak.localmeshai/files/model.gguf", "Path content mismatch");
    }

    // Path with embedded NUL in middle
    {
        const std::vector<uint16_t> path_with_nul = {
            '/', 'd', 'a', 't', 'a', '/', 'm', 'o', 'd', 'e', 'l',
            0x0000,
            '.', 'g', 'g', 'u', 'f'
        };
        std::string out;
        bool ok = solus::utf16_to_path_utf8(path_with_nul.data(), path_with_nul.size(), out);
        TEST_ASSERT(!ok, "Path with embedded NUL in middle must be rejected");
        TEST_ASSERT(out.empty(), "Rejected path out_path must be empty");
    }

    // Path with embedded NUL at start
    {
        const std::vector<uint16_t> nul_at_start = { 0x0000, '/', 'e', 't', 'c' };
        std::string out;
        bool ok = solus::utf16_to_path_utf8(nul_at_start.data(), nul_at_start.size(), out);
        TEST_ASSERT(!ok, "Path with NUL at start must be rejected");
        TEST_ASSERT(out.empty(), "Rejected path out_path must be empty");
    }

    // Path with embedded NUL at end before len
    {
        const std::vector<uint16_t> nul_at_end = { '/', 'b', 'i', 'n', 0x0000 };
        std::string out;
        bool ok = solus::utf16_to_path_utf8(nul_at_end.data(), nul_at_end.size(), out);
        TEST_ASSERT(!ok, "Path with NUL before length must be rejected");
        TEST_ASSERT(out.empty(), "Rejected path out_path must be empty");
    }

    // Empty path
    {
        std::string out;
        bool ok = solus::utf16_to_path_utf8(static_cast<const uint16_t*>(nullptr), 0, out);
        TEST_ASSERT(ok, "Empty path should return true with empty out");
        TEST_ASSERT(out.empty(), "Empty path output should be empty");
    }

    TEST_PASS("Embedded NUL rejection in paths passed");
}

static bool test_utf8_to_utf16_error_handling() {
    std::cout << "Running test_utf8_to_utf16_error_handling..." << std::endl;

    // Overlong 2-byte sequence for ASCII NUL (0xC0, 0x80) - Modified UTF-8
    {
        const char overlong_nul[] = "\xC0\x80";
        std::vector<uint16_t> u16 = solus::utf8_to_utf16(overlong_nul, 2);
        // In standard UTF-8, 0xC0 0x80 is an illegal overlong encoding; should be replaced by U+FFFD
        TEST_ASSERT(u16.size() >= 1, "Should output replacement character");
        TEST_ASSERT(u16[0] == 0xFFFD, "Overlong sequence must produce U+FFFD");
    }

    // Truncated 4-byte sequence (3 bytes of a 4-byte sequence)
    {
        const char truncated[] = "\xF0\x9F\x98";
        std::vector<uint16_t> u16 = solus::utf8_to_utf16(truncated, 3);
        TEST_ASSERT(!u16.empty() && u16[0] == 0xFFFD, "Truncated sequence must produce U+FFFD");
    }

    // Stray continuation byte
    {
        const char stray[] = "\x80\xBF";
        std::vector<uint16_t> u16 = solus::utf8_to_utf16(stray, 2);
        TEST_ASSERT(u16.size() == 2 && u16[0] == 0xFFFD && u16[1] == 0xFFFD, "Stray continuation bytes must produce U+FFFD");
    }

    TEST_PASS("UTF-8 to UTF-16 error handling passed");
}

int main() {
    std::cout << "============================================================" << std::endl;
    std::cout << "  SOLUS NATIVE UTF-CONVERSION TEST SUITE                    " << std::endl;
    std::cout << "============================================================" << std::endl;

    test_ascii_conversion();
    test_bengali_conversion();
    test_emoji_and_supplementary_characters();
    test_supplementary_mathematical_characters();
    test_malformed_surrogates();
    test_embedded_nul_rejection_in_paths();
    test_utf8_to_utf16_error_handling();

    std::cout << "============================================================" << std::endl;
    std::cout << "RESULTS: " << g_tests_passed << " passed, " << g_tests_failed << " failed." << std::endl;
    std::cout << "============================================================" << std::endl;

    return g_tests_failed == 0 ? 0 : 1;
}

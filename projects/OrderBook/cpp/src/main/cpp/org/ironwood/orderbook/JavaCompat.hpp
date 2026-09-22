// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_JAVA_COMPAT_HPP
#define ORG_IRONWOOD_ORDERBOOK_JAVA_COMPAT_HPP

#include <cstdint>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include <time.h>

/*
 * The few Java platform behaviors that the translated sources rely on.
 * IllegalArgumentException maps to std::invalid_argument and
 * IllegalStateException maps to std::logic_error.
 */
namespace org::ironwood::orderbook {

// System.nanoTime(). Reads CLOCK_MONOTONIC directly, like the Ironwood runtime
// and HotSpot on Linux, instead of depending on the library's steady_clock.
inline std::int64_t nanoTime() {
    timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(value.tv_sec) * UINT64_C(1000000000)
            + static_cast<std::uint64_t>(value.tv_nsec));
}

// Integer.parseInt(String) for ASCII text: an optional sign followed by decimal
// digits within the int range, without surrounding whitespace.
inline std::int32_t parseInt(const std::string& text) {
    std::size_t index = 0;
    bool negative = false;
    if (!text.empty() && (text[0] == '-' || text[0] == '+')) {
        negative = text[0] == '-';
        index = 1;
    }
    if (index == text.size()) throw std::invalid_argument("For input string: \"" + text + "\"");

    std::int64_t limit = negative ? INT64_C(2147483648) : INT64_C(2147483647);
    std::int64_t value = 0;
    for (; index < text.size(); index++) {
        char digit = text[index];
        if (digit < '0' || digit > '9') throw std::invalid_argument("For input string: \"" + text + "\"");
        value = value * 10 + (digit - '0');
        if (value > limit) throw std::invalid_argument("For input string: \"" + text + "\"");
    }
    return static_cast<std::int32_t>(negative ? -value : value);
}

// The Java launcher's contract: status 0 after main returns, or status 1 after
// an uncaught exception, whose message goes to standard error.
inline int runMain(void (*entryPoint)(const std::vector<std::string>&), int argc, char** argv) {
    try {
        entryPoint(std::vector<std::string>(argc > 0 ? argv + 1 : argv, argv + argc));
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "error: " << exception.what() << '\n';
        return 1;
    }
}

}

#endif

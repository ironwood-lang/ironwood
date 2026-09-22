// SPDX-License-Identifier: MIT OR Apache-2.0
#include "org/ironwood/orderbook/LatencyReport.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace org::ironwood::orderbook {

namespace {

constexpr std::array<double, 6> FRACTIONS{0.75, 0.9, 0.99, 0.999, 0.9999, 0.99999};

constexpr std::array<std::string_view, 6> LABELS{"75%", "90%", "99%", "99.9%", "99.99%", "99.999%"};

constexpr std::array<const char*, 4> UNITS{"nano", "micro", "milli", "second"};

// Math.round(double): the nearest long with ties toward positive infinity,
// zero for NaN, and saturation at the long range.
std::int64_t javaRound(double value) {
    if (std::isnan(value)) return 0;
    double floor = std::floor(value);
    double rounded = value - floor >= 0.5 ? floor + 1.0 : floor;
    if (rounded >= 9223372036854775808.0) return std::numeric_limits<std::int64_t>::max();
    if (rounded < -9223372036854775808.0) return std::numeric_limits<std::int64_t>::min();
    return static_cast<std::int64_t>(rounded);
}

// String.format(Locale.ROOT, "%,d", value).
std::string groupThousands(std::int64_t value) {
    std::string digits = std::to_string(value);
    std::size_t start = digits[0] == '-' ? 1 : 0;
    for (std::size_t position = digits.size(); position > start + 3; position -= 3) {
        digits.insert(position - 3, 1, ',');
    }
    return digits;
}

}

std::string LatencyReport::results(const std::vector<std::int64_t>& samples, std::int32_t warmup) {
    std::int32_t length = static_cast<std::int32_t>(samples.size());
    if (warmup < 0 || warmup > length) throw std::invalid_argument("invalid warmup count");
    std::int32_t count = length - warmup;
    std::string report = "Measurements: " + groupThousands(count) + " | Warm-Up: " + groupThousands(warmup)
            + " | Iterations: " + groupThousands(length) + "\n";
    if (count == 0) return report;

    std::vector<std::int64_t> sorted(samples.begin() + warmup, samples.end());
    std::sort(sorted.begin(), sorted.end());
    std::int64_t sum = 0;
    for (std::int64_t sample : sorted) sum += sample;
    double mean = static_cast<double>(sum) / count;
    if (mean <= static_cast<double>(std::numeric_limits<std::int64_t>::max()) / 100.0) {
        mean = static_cast<double>(javaRound(mean * 100.0)) / 100.0;
    }
    report += "Avg Time: " + time(mean)
            + " | Min Time: " + time(static_cast<double>(sorted[0]))
            + " | Max Time: " + time(static_cast<double>(sorted[static_cast<std::size_t>(count) - 1])) + "\n";

    std::int32_t consumed = 0;
    std::int64_t prefixSum = 0;
    for (std::size_t index = 0; index < FRACTIONS.size(); index++) {
        std::int32_t rank = static_cast<std::int32_t>(javaRound(FRACTIONS[index] * count));
        while (consumed < rank) prefixSum += sorted[static_cast<std::size_t>(consumed++)];
        report += LABELS[index];
        report += " = [avg: " + time(static_cast<double>(prefixSum) / rank)
                + ", max: " + time(static_cast<double>(sorted[static_cast<std::size_t>(rank) - 1])) + "]\n";
    }
    return report;
}

std::string LatencyReport::time(double nanos) {
    double divisor = 1.0;
    std::size_t unit = 0;
    while (unit < UNITS.size() - 1 && nanos >= divisor * 1000.0) {
        divisor *= 1000.0;
        unit++;
    }
    double value = static_cast<double>(javaRound(nanos / divisor * 1000.0)) / 1000.0;
    // Values come from long samples, so they need far fewer than 64 characters.
    char text[64];
    std::snprintf(text, sizeof text, "%.3f %s%s", value, UNITS[unit], value > 1.0 ? "s" : "");
    return text;
}

}

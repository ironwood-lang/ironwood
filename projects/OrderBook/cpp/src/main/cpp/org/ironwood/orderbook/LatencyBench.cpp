// SPDX-License-Identifier: MIT OR Apache-2.0
#include "org/ironwood/orderbook/LatencyBench.hpp"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

#include "org/ironwood/orderbook/Bench.hpp"
#include "org/ironwood/orderbook/JavaCompat.hpp"
#include "org/ironwood/orderbook/LatencyReport.hpp"
#include "org/ironwood/orderbook/OrderBook.hpp"

namespace org::ironwood::orderbook {

namespace {

// Double.toString(double): the shortest digits that round-trip, in plain
// notation from 10^-3 up to 10^7 and computerized scientific notation, such
// as 1.0E7, otherwise.
std::string doubleToString(double value) {
    if (std::isnan(value)) return "NaN";
    if (std::isinf(value)) return value > 0.0 ? "Infinity" : "-Infinity";
    if (value == 0.0) return std::signbit(value) ? "-0.0" : "0.0";

    char scientific[32];
    for (int precision = 0; precision <= 16; precision++) {
        std::snprintf(scientific, sizeof scientific, "%.*e", precision, value);
        if (std::strtod(scientific, nullptr) == value) break;
    }

    std::string text(scientific);
    std::string sign;
    if (text[0] == '-') {
        sign = "-";
        text.erase(0, 1);
    }
    std::size_t exponentStart = text.find('e');
    int exponent = std::atoi(text.c_str() + exponentStart + 1);
    std::string digits;
    for (std::size_t position = 0; position < exponentStart; position++) {
        if (text[position] != '.') digits += text[position];
    }

    double magnitude = std::fabs(value);
    if (magnitude >= 1e-3 && magnitude < 1e7) {
        if (exponent < 0) return sign + "0." + std::string(static_cast<std::size_t>(-exponent - 1), '0') + digits;
        std::size_t integerDigits = static_cast<std::size_t>(exponent) + 1;
        if (digits.size() <= integerDigits) {
            return sign + digits + std::string(integerDigits - digits.size(), '0') + ".0";
        }
        return sign + digits.substr(0, integerDigits) + "." + digits.substr(integerDigits);
    }
    std::string fraction = digits.size() > 1 ? digits.substr(1) : "0";
    return sign + digits.substr(0, 1) + "." + fraction + "E" + std::to_string(exponent);
}

}

void LatencyBench::printClockCheck() {
    std::int32_t checks = 1000000;
    std::int64_t sum = 0;
    std::int64_t smallestPositive = std::numeric_limits<std::int64_t>::max();
    std::int64_t begin = nanoTime();
    for (std::int32_t index = 0; index < checks; index++) {
        std::int64_t start = nanoTime();
        std::int64_t elapsed = nanoTime() - start;
        sum += elapsed;
        if (elapsed > 0 && elapsed < smallestPositive) smallestPositive = elapsed;
    }
    std::int64_t total = nanoTime() - begin;
    std::cout << "Empty interval average (ns): ";
    std::cout << doubleToString(static_cast<double>(sum) / checks) << '\n';
    std::cout << "Two clock reads plus loop average (ns): ";
    std::cout << doubleToString(static_cast<double>(total) / checks) << '\n';
    std::cout << "Smallest observed positive clock delta (ns): ";
    std::cout << (smallestPositive == std::numeric_limits<std::int64_t>::max() ? 0 : smallestPositive) << '\n';
}

void LatencyBench::main(const std::vector<std::string>& args) {
    if (args.size() != 3) throw std::invalid_argument("expected warmup batches, measured batches, and cycles per batch");
    std::int32_t warmup = parseInt(args[0]);
    std::int32_t measurements = parseInt(args[1]);
    std::int32_t cyclesPerBatch = parseInt(args[2]);
    std::int32_t count = sampleCount(warmup, measurements, cyclesPerBatch);
    std::vector<std::int64_t> samples(static_cast<std::size_t>(count));
    // The book and pool graph live until process exit, as in Ironwood.
    OrderBook* book = new OrderBook(8, 4);
    printClockCheck();
    std::int64_t nextOrderId = collect(*book, samples, cyclesPerBatch);
    Bench::verify(*book, nextOrderId, static_cast<std::int64_t>(count) * cyclesPerBatch);

    std::cout << "Cycles per batch: ";
    std::cout << cyclesPerBatch << '\n';
    std::cout << "Operations per batch: ";
    std::cout << static_cast<std::int64_t>(cyclesPerBatch) * 8 << '\n';
    std::cout << "Measured operations: ";
    std::cout << static_cast<std::int64_t>(measurements) * cyclesPerBatch * 8 << '\n';
    std::cout << "Batch latency (clock overhead included):\n";
    std::cout << LatencyReport::results(samples, warmup) << '\n';
}

}

int main(int argc, char** argv) {
    return org::ironwood::orderbook::runMain(org::ironwood::orderbook::LatencyBench::main, argc, argv);
}

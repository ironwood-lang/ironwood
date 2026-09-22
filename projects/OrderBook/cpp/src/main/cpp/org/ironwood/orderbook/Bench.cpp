// SPDX-License-Identifier: MIT OR Apache-2.0
#include "org/ironwood/orderbook/Bench.hpp"

#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "org/ironwood/orderbook/JavaCompat.hpp"
#include "org/ironwood/orderbook/OrderBook.hpp"

namespace org::ironwood::orderbook {

void Bench::main(const std::vector<std::string>& args) {
    if (args.size() != 2) throw std::invalid_argument("expected warmup and measured operation counts in millions");

    std::int32_t warmupMillions = parseInt(args[0]);
    std::int32_t measuredMillions = parseInt(args[1]);
    if (warmupMillions < 0) throw std::invalid_argument("warmup must not be negative");
    if (measuredMillions <= 0) throw std::invalid_argument("measurement must be positive");

    OrderBook book(8, 4);
    std::int64_t nextOrderId = run(book, static_cast<std::int64_t>(warmupMillions) * CYCLES_PER_MILLION, 1);

    std::int64_t start = nanoTime();
    nextOrderId = run(book, static_cast<std::int64_t>(measuredMillions) * CYCLES_PER_MILLION, nextOrderId);
    std::int64_t elapsed = nanoTime() - start;

    std::int64_t totalMillions = static_cast<std::int64_t>(warmupMillions) + measuredMillions;
    std::int64_t totalCycles = totalMillions * CYCLES_PER_MILLION;
    verify(book, nextOrderId, totalCycles);

    std::cout << elapsed << '\n';
}

}

int main(int argc, char** argv) {
    return org::ironwood::orderbook::runMain(org::ironwood::orderbook::Bench::main, argc, argv);
}

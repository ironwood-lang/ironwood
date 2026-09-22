// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_LATENCY_BENCH_HPP
#define ORG_IRONWOOD_ORDERBOOK_LATENCY_BENCH_HPP

#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

#include "org/ironwood/orderbook/Bench.hpp"
#include "org/ironwood/orderbook/JavaCompat.hpp"
#include "org/ironwood/orderbook/OrderBook.hpp"

namespace org::ironwood::orderbook {

/**
 * Times batches of the throughput benchmark's eight-operation cycle.
 * Arguments are warmup batches, measured batches, and cycles per batch.
 * Reports describe whole batches, including clock overhead, and exit zero
 * only after validating the workload. Reporting follows sample collection.
 */
class LatencyBench final {
public:
    LatencyBench() = delete;

    static std::int32_t sampleCount(std::int32_t warmup, std::int32_t measurements, std::int32_t cyclesPerBatch) {
        if (warmup < 0) throw std::invalid_argument("warmup batches must not be negative");
        if (measurements <= 0) throw std::invalid_argument("measured batches must be positive");
        if (cyclesPerBatch <= 0) throw std::invalid_argument("cycles per batch must be positive");
        std::int64_t count = static_cast<std::int64_t>(warmup) + measurements;
        if (count > std::numeric_limits<std::int32_t>::max()) throw std::invalid_argument("too many batches for sample storage");
        if (count * cyclesPerBatch > std::numeric_limits<std::int64_t>::max() / 250) throw std::invalid_argument("workload counters would overflow");
        return static_cast<std::int32_t>(count);
    }

    static std::int64_t collect(OrderBook& book, std::vector<std::int64_t>& samples, std::int32_t cyclesPerBatch) {
        std::int64_t nextOrderId = 1;
        for (std::size_t index = 0; index < samples.size(); index++) {
            std::int64_t start = nanoTime();
            nextOrderId = Bench::run(book, cyclesPerBatch, nextOrderId);
            std::int64_t elapsed = nanoTime() - start;
            // Store after the closing clock read. Histogram work happens later.
            samples[index] = elapsed;
        }
        return nextOrderId;
    }

    static void main(const std::vector<std::string>& args);

private:
    static void printClockCheck();
};

}

#endif

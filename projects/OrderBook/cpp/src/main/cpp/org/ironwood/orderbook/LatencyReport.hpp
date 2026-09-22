// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_LATENCY_REPORT_HPP
#define ORG_IRONWOOD_ORDERBOOK_LATENCY_REPORT_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace org::ironwood::orderbook {

/**
 * Post-processing for the latency driver, with the same report conventions as
 * the Java LatencyReport and ironwood.bench.Bench. Sorting and formatting run
 * after all timed batches.
 */
class LatencyReport final {
public:
    LatencyReport() = delete;

    static std::string results(const std::vector<std::int64_t>& samples, std::int32_t warmup);

private:
    static std::string time(double nanos);
};

}

#endif

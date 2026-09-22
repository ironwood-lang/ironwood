// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_BENCH_HPP
#define ORG_IRONWOOD_ORDERBOOK_BENCH_HPP

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

#include "org/ironwood/orderbook/Order.hpp"
#include "org/ironwood/orderbook/OrderBook.hpp"

namespace org::ironwood::orderbook {

/**
 * Deterministic throughput benchmark for a capacity-stable order-book workload.
 * Arguments are warmup and measured operation counts in millions. Standard
 * output contains only the measured total in nanoseconds.
 */
class Bench final {
public:
    Bench() = delete;

    // Shared by throughput and latency so both execute the same cycle.
    static std::int64_t run(OrderBook& book, std::int64_t cycles, std::int64_t nextOrderId) {
        // Each cycle starts and ends empty, so all pooled capacity is reused.
        for (std::int64_t cycle = 0; cycle < cycles; cycle++) {
            Order& bestBid = book.createLimit(nextOrderId++, Side::BUY, 100, BID_PRICE_1);
            book.createLimit(nextOrderId++, Side::BUY, 100, BID_PRICE_2);
            Order& bestAsk = book.createLimit(nextOrderId++, Side::SELL, 100, ASK_PRICE_1);
            book.createLimit(nextOrderId++, Side::SELL, 100, ASK_PRICE_2);

            bestBid.reduceTo(50);
            bestAsk.cancel();

            book.createMarket(nextOrderId++, Side::SELL, 150);
            book.createMarket(nextOrderId++, Side::BUY, 100);
        }
        return nextOrderId;
    }

    static void verify(const OrderBook& book, std::int64_t nextOrderId, std::int64_t totalCycles) {
        std::int64_t expectedNextOrderId = totalCycles * ORDERS_PER_CYCLE + 1;
        bool completed = nextOrderId == expectedNextOrderId
                && book.getMatchCount() == totalCycles * 3
                && book.getMatchedVolume() == totalCycles * 250
                && book.getLastExecutedPrice() == ASK_PRICE_2
                && book.getLastMakerOrderId() == expectedNextOrderId - 3
                && book.isEmpty()
                && book.hasFullPoolCapacity();
        if (!completed) throw std::logic_error("benchmark workload did not complete correctly");
    }

    static void main(const std::vector<std::string>& args);

private:
    using Side = Order::Side;

    static constexpr std::int32_t OPERATIONS_PER_CYCLE = 8;

    static constexpr std::int32_t CYCLES_PER_MILLION = 1'000'000 / OPERATIONS_PER_CYCLE;

    static constexpr std::int32_t ORDERS_PER_CYCLE = 6;

    static constexpr std::int64_t BID_PRICE_1 = 10'000'000'000;

    static constexpr std::int64_t BID_PRICE_2 = 9'900'000'000;

    static constexpr std::int64_t ASK_PRICE_1 = 10'200'000'000;

    static constexpr std::int64_t ASK_PRICE_2 = 10'300'000'000;
};

}

#endif

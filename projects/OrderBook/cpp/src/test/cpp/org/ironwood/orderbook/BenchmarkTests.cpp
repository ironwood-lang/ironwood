// SPDX-License-Identifier: MIT OR Apache-2.0
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

#include "org/ironwood/orderbook/Bench.hpp"
#include "org/ironwood/orderbook/JavaCompat.hpp"
#include "org/ironwood/orderbook/LatencyBench.hpp"
#include "org/ironwood/orderbook/LatencyReport.hpp"
#include "org/ironwood/orderbook/OrderBook.hpp"

namespace {

// Counts heap allocations in place of the JDK's thread-allocation counter.
std::int64_t allocationCount = 0;
std::int64_t liveAllocationCount = 0;
std::int64_t allocationsBeforeFailure = -1;
bool recordAllocations = false;
std::array<std::size_t, 17> allocationSizes{};
std::size_t recordedAllocations = 0;

}

// Replace both scalar and array forms so sanitizers also use these counters.
void* operator new(std::size_t size) {
    if (allocationsBeforeFailure == 0) throw std::bad_alloc();
    if (allocationsBeforeFailure > 0) allocationsBeforeFailure--;
    if (void* pointer = std::malloc(size == 0 ? 1 : size)) {
        allocationCount++;
        liveAllocationCount++;
        if (recordAllocations && recordedAllocations < allocationSizes.size()) {
            allocationSizes[recordedAllocations++] = size;
        }
        return pointer;
    }
    throw std::bad_alloc();
}

void* operator new[](std::size_t size) {
    return ::operator new(size);
}

void operator delete(void* pointer) noexcept {
    if (pointer != nullptr) liveAllocationCount--;
    std::free(pointer);
}

void operator delete(void* pointer, std::size_t) noexcept {
    ::operator delete(pointer);
}

void operator delete[](void* pointer) noexcept {
    ::operator delete(pointer);
}

void operator delete[](void* pointer, std::size_t) noexcept {
    ::operator delete(pointer);
}

namespace org::ironwood::orderbook {

/** C++ counterparts of the Java workload tests, plus report checks. */
class BenchmarkTests final {
public:
    BenchmarkTests() = delete;

    static void main(const std::vector<std::string>& args) {
        if (args.size() == 1 && args[0] == "reports") {
            printReports();
            return;
        }
        if (!args.empty()) throw std::invalid_argument("unexpected test arguments");
        run("bookStorageIsAllocatedInSourceOrder", bookStorageIsAllocatedInSourceOrder);
        run("failedConstructionReleasesEveryAllocation", failedConstructionReleasesEveryAllocation);
        run("workloadPreservesCountsAndReusesPools", workloadPreservesCountsAndReusesPools);
        run("collectionWritesEverySampleWithoutAllocating", collectionWritesEverySampleWithoutAllocating);
        run("acceptsZeroWarmupAndDefaultSampleCounts", acceptsZeroWarmupAndDefaultSampleCounts);
        run("rejectsInvalidCountsAndCounterOverflow", rejectsInvalidCountsAndCounterOverflow);
        run("reportExcludesWarmupAndHandlesEmptySamples", reportExcludesWarmupAndHandlesEmptySamples);
        run("reportPreservesSamplesAndSelectsPartialBuckets", reportPreservesSamplesAndSelectsPartialBuckets);
        std::cout << "PASS: 8 C++ benchmark tests" << '\n';
    }

private:
    static void check(bool condition) {
        if (!condition) throw std::runtime_error("benchmark check failed");
    }

    static bool startsWith(const std::string& text, const std::string& prefix) {
        return text.compare(0, prefix.size(), prefix) == 0;
    }

    static bool contains(const std::string& text, const std::string& part) {
        return text.find(part) != std::string::npos;
    }

    static std::int64_t constructBookWithRestingOrder() {
        std::int64_t before = allocationCount;
        OrderBook book(8, 4);
        check(book.isEmpty() && book.hasFullPoolCapacity());
        check(book.getLevelCount(Order::Side::BUY) == 0 && book.getLevelCount(Order::Side::SELL) == 0);
        book.createLimit(1, Order::Side::BUY, 100, 99);
        // Destruction must also reclaim objects absent from the free pools.
        return allocationCount - before;
    }

    static void bookStorageIsAllocatedInSourceOrder() {
        std::int64_t before = liveAllocationCount;
        recordedAllocations = 0;
        recordAllocations = true;
        constructBookWithRestingOrder();
        recordAllocations = false;
        check(liveAllocationCount == before);

        // The first allocations must be the order pool, eight individual
        // orders, the price-level pool, four individual price levels, and
        // separate two-element head, tail, and level-count arrays.
        check(recordedAllocations == allocationSizes.size());
        check(allocationSizes[0] == 8 * sizeof(Order*));
        for (std::size_t index = 1; index <= 8; index++) check(allocationSizes[index] == sizeof(Order));
        check(allocationSizes[9] == 4 * sizeof(PriceLevel*));
        for (std::size_t index = 10; index < 14; index++) check(allocationSizes[index] == sizeof(PriceLevel));
        check(allocationSizes[14] == 2 * sizeof(PriceLevel*));
        check(allocationSizes[15] == 2 * sizeof(PriceLevel*));
        check(allocationSizes[16] == 2 * sizeof(std::int32_t));
    }

    static void failedConstructionReleasesEveryAllocation() {
        std::int64_t allocations = constructBookWithRestingOrder();
        for (std::int64_t failure = 0; failure < allocations; failure++) {
            std::int64_t before = liveAllocationCount;
            allocationsBeforeFailure = failure;
            bool rejected = false;
            try {
                constructBookWithRestingOrder();
            } catch (const std::bad_alloc&) {
                rejected = true;
            }
            allocationsBeforeFailure = -1;
            check(rejected);
            check(liveAllocationCount == before);
        }
    }

    static void workloadPreservesCountsAndReusesPools() {
        OrderBook book(8, 4);
        std::int64_t next = Bench::run(book, 0, 1);
        check(next == 1 && book.isEmpty());
        next = Bench::run(book, 1, next);
        Bench::verify(book, next, 1);
        next = Bench::run(book, 10, next);
        Bench::verify(book, next, 11);
        check(next == 67 && book.getMatchCount() == 33);
        check(book.getMatchedVolume() == 2750 && book.getLastMakerOrderId() == 64);
    }

    // Java first warms up class initialization and the JIT. Native code has
    // neither, so observation starts immediately.
    static void collectionWritesEverySampleWithoutAllocating() {
        std::vector<std::int64_t> samples(8);
        for (std::int32_t cycles = 1; cycles <= 1000; cycles *= 10) {
            std::fill(samples.begin(), samples.end(), -1);
            OrderBook book(8, 4);
            std::int64_t before = allocationCount;
            std::int64_t next = LatencyBench::collect(book, samples, cycles);
            check(allocationCount == before);
            Bench::verify(book, next, static_cast<std::int64_t>(samples.size()) * cycles);
            for (std::int64_t sample : samples) check(sample >= 0);
        }
    }

    static void acceptsZeroWarmupAndDefaultSampleCounts() {
        check(LatencyBench::sampleCount(0, 1, 1) == 1);
        check(LatencyBench::sampleCount(10000, 50000, 1000) == 60000);
    }

    static bool rejects(std::int32_t warmup, std::int32_t measurements, std::int32_t cycles) {
        try {
            LatencyBench::sampleCount(warmup, measurements, cycles);
            return false;
        } catch (const std::invalid_argument&) {
            return true;
        }
    }

    static void rejectsInvalidCountsAndCounterOverflow() {
        constexpr std::int32_t INT_MAX_VALUE = std::numeric_limits<std::int32_t>::max();
        check(rejects(-1, 1, 1));
        check(rejects(0, 0, 1));
        check(rejects(0, -1, 1));
        check(rejects(0, 1, 0));
        check(rejects(0, 1, -1));
        check(rejects(INT_MAX_VALUE, 1, 1));
        check(rejects(0, INT_MAX_VALUE, INT_MAX_VALUE));
    }

    static void reportExcludesWarmupAndHandlesEmptySamples() {
        std::string report = LatencyReport::results({999, 10, 11}, 1);
        check(startsWith(report, "Measurements: 2 | Warm-Up: 1 | Iterations: 3\n"));
        check(contains(report, "Avg Time: 10.500 nanos | Min Time: 10.000 nanos | Max Time: 11.000 nanos"));
        check(contains(report, "75% = [avg: 10.500 nanos, max: 11.000 nanos]"));
        check(LatencyReport::results({99, 88}, 2)
                == "Measurements: 0 | Warm-Up: 2 | Iterations: 2\n");
    }

    static void reportPreservesSamplesAndSelectsPartialBuckets() {
        std::vector<std::int64_t> samples{999, 20, 10, 10, 10, 10, 10, 0, 0};
        std::vector<std::int64_t> original = samples;
        std::string report = LatencyReport::results(samples, 1);
        check(samples == original);
        check(contains(report, "75% = [avg: 6.667 nanos, max: 10.000 nanos]"));
    }

    static void printReports() {
        constexpr std::int64_t LONG_MAX_VALUE = std::numeric_limits<std::int64_t>::max();
        std::cout << LatencyReport::results({999, 10, 11}, 1) << '\n';
        std::cout << LatencyReport::results({999, 20, 10, 10, 10, 10, 10, 0, 0}, 1) << '\n';
        std::cout << LatencyReport::results({LONG_MAX_VALUE, LONG_MAX_VALUE,
                0, 999, 1000, 1000, 1999, 2000, 1000000, 1000000000}, 2) << '\n';
        std::cout << LatencyReport::results({100, 42}, 1) << '\n';
        std::cout << LatencyReport::results({99, 88}, 2) << '\n';
    }

    static void run(const char* name, void (*test)()) {
        std::cout << "RUN - " << name << '\n';
        test();
        std::cout << "ok - " << name << '\n';
    }
};

}

int main(int argc, char** argv) {
    return org::ironwood::orderbook::runMain(org::ironwood::orderbook::BenchmarkTests::main, argc, argv);
}

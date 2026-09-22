// SPDX-License-Identifier: MIT OR Apache-2.0
#include "org/ironwood/orderbook/JavaCompat.hpp"

#include <time.h>

namespace org::ironwood::orderbook {

std::int64_t nanoTime() {
    timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(value.tv_sec) * UINT64_C(1000000000)
            + static_cast<std::uint64_t>(value.tv_nsec));
}

}

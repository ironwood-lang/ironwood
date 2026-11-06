// SPDX-License-Identifier: MIT OR Apache-2.0
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

static uint64_t nanos(struct timespec value) {
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}

int main(void) {
    struct timespec resolution, previous, current;
    if (clock_getres(CLOCK_MONOTONIC, &resolution) || clock_gettime(CLOCK_MONOTONIC, &previous)) return 1;
    uint64_t minimum = UINT64_MAX, zeros = 0;
    for (int i = 0; i < 100000; i++) {
        if (clock_gettime(CLOCK_MONOTONIC, &current)) return 2;
        uint64_t delta = nanos(current) - nanos(previous);
        if (!delta) zeros++;
        else if (delta < minimum) minimum = delta;
        previous = current;
    }
    printf("{\"clock\":\"CLOCK_MONOTONIC\",\"resolution_ns\":%" PRIu64
           ",\"min_positive_step_ns\":%" PRIu64 ",\"zero_deltas\":%" PRIu64 ",\"reads\":100000}\n",
           nanos(resolution), minimum, zeros);
    return minimum == UINT64_MAX ? 3 : 0;
}

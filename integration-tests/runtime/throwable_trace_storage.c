// SPDX-License-Identifier: MIT OR Apache-2.0

// This harness includes the runtime after system headers, so enable POSIX first.
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* Count native trace storage and inject failures independently of managed
 * allocation counters. No production instrumentation is required. */
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unwind.h>
#include <unistd.h>

static int live, calls, fail_at;
static void *trace_calloc(size_t count, size_t size) {
    calls++;
    if (calls == fail_at) { return NULL; }
    void *result = calloc(count, size);
    if (result != NULL) { live++; }
    return result;
}
static void trace_free(void *value) {
    if (value != NULL) { live--; }
    free(value);
}
#define calloc trace_calloc
#define free trace_free
#include "../../runtime/src/ironwood_runtime.c"
#undef calloc
#undef free
#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "line %d\n", __LINE__); return 1; } } while (0)

int main(void) {
    _Static_assert(sizeof(uintptr_t) == sizeof(uint64_t), "Throwable slot width");
    _Static_assert(offsetof(struct ironwood_throwable, trace_state) == 24, "Throwable slot offset");
    const unsigned char membership[] = {1, 1};
    const struct ironwood_type_info type = {.membership = membership};
    struct ironwood_throwable value = {.type = &type}, other = {.type = &type}, emergency = {.type = &type};
    const struct ironwood_trace_site first = {
        .guid = 1, .callable = "Main.run", .file = "Main.iron",
        .probe_index = 1, .line = 12
    };
    const struct ironwood_trace_site second = {
        .guid = 1, .callable = "Main.run", .file = "Main.iron",
        .probe_index = 2, .line = 13
    };
    struct ironwood_exception_metadata *metadata = create_exception_metadata(&value);
    CHECK(metadata != NULL);
    metadata->trace = trace_calloc(1, sizeof(*metadata->trace));
    CHECK(metadata->trace != NULL);
    metadata->trace[0].site = &first;
    metadata->trace_count = 1;
    metadata->trace_state = IRONWOOD_TRACE_CAPTURED;
    CHECK(live == 2 && value.trace_state != 0);

    struct ironwood_exception_metadata *other_metadata = create_exception_metadata(&other);
    CHECK(other_metadata != NULL);
    other_metadata->trace = trace_calloc(1, sizeof(*other_metadata->trace));
    CHECK(other_metadata->trace != NULL);
    other_metadata->trace[0].site = &first;
    other_metadata->trace_count = 1;
    other_metadata->trace_state = IRONWOOD_TRACE_CAPTURED;
    CHECK(live == 4 && ironwood_throwable_trace_common(&value, &other) == 1);

    trace_free(metadata->trace);
    metadata->trace = trace_calloc(1, sizeof(*metadata->trace));
    CHECK(metadata->trace != NULL);
    metadata->trace[0].site = &second;
    CHECK(live == 4 && ironwood_throwable_trace_common(&value, &other) == 0);
    ironwood_exception_add_secondary(&value, &other);
    CHECK(live == 5 && ironwood_exception_secondary_at(&value, 0) == &other);
    ironwood_throwable_trace_release(&value);
    CHECK(live == 2 && value.trace_state == 0 && other.trace_state != 0);
    ironwood_throwable_trace_release(&other);
    CHECK(live == 0 && other.trace_state == 0);

    for (int failure = 1; failure <= 2; failure++) {
        fail_at = calls + failure;
        ironwood_throwable_trace_capture(&value);
        CHECK(live == failure - 1);
        if (failure == 1) { CHECK(value.trace_state == 1); }
        else { CHECK(find_exception_metadata(&value)->trace_state == IRONWOOD_TRACE_UNAVAILABLE); }
        ironwood_throwable_trace_release(&value);
        CHECK(live == 0 && value.trace_state == 0);
    }

    fail_at = 0;
    int baseline = calls;
    prepare_implicit_failure(&emergency);
    capture_exception_trace(&emergency);
    CHECK(calls == baseline && live == 0);
    CHECK(implicit_failure_metadata.trace_state == IRONWOOD_TRACE_UNAVAILABLE);
    ironwood_throwable_trace_release(&emergency);
    CHECK(live == 0 && emergency.trace_state == 0);
    return 0;
}

// SPDX-License-Identifier: MIT OR Apache-2.0
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* Current runtime trace/secondary storage, with deterministic native frames.
 * Only frame acquisition is simulated. Real decoding, capture, allocation,
 * refresh, emergency delivery and exact GC graph tracing remain exercised. */
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <unwind.h>

static int native_live, native_calls, fail_native;
static void *trace_calloc(size_t count, size_t size) {
    native_calls++;
    if (fail_native) return NULL;
    void *result = calloc(count, size);
    if (result != NULL) native_live++;
    return result;
}
static void trace_free(void *value) {
    if (value != NULL) native_live--;
    free(value);
}
static uintptr_t test_ip(struct _Unwind_Context *context) {
    (void)context;
    return 0x1009;
}
static uintptr_t test_region(struct _Unwind_Context *context) {
    (void)context;
    return 0x1000;
}
static _Unwind_Reason_Code test_backtrace(_Unwind_Trace_Fn action, void *argument) {
    (void)action(NULL, argument);
    return _URC_END_OF_STACK;
}

#include "gc_harness_alloc.h"
#define calloc trace_calloc
#define free trace_free
#define _Unwind_GetIP test_ip
#define _Unwind_GetRegionStart test_region
#define _Unwind_Backtrace test_backtrace
#include "../../runtime/src/ironwood_runtime.c"
#undef calloc
#undef free
#undef _Unwind_GetIP
#undef _Unwind_GetRegionStart
#undef _Unwind_Backtrace
#include "gc_harness_state.h"
#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "line %d\n", __LINE__); return 1; } } while (0)

static const struct ironwood_trace_site first = {
    .guid = 1, .callable = "Main.run", .file = "Main.iron", .probe_index = 1, .line = 12
};
static const struct ironwood_trace_site second = {
    .guid = 1, .callable = "Main.run", .file = "Main.iron", .probe_index = 2, .line = 13
};
static const struct ironwood_trace_function function = {1, (const void *)(uintptr_t)0x1000};
/* LLVM pseudoprobe node: GUID 1, one probe, no children; probe index 1,
 * block kind, absolute PC 0x1008. Qualified targets are 64-bit little endian. */
static uint8_t section[] = {1,0,0,0,0,0,0,0, 1,0, 1,0, 8,16,0,0,0,0,0,0};

static void configure_trace(const struct ironwood_trace_site *site) {
    trace_sites = site;
    trace_site_count = 1;
    trace_functions = &function;
    trace_function_count = 1;
    section[10] = (uint8_t)site->probe_index;
    trace_section = section;
    trace_section_size = sizeof(section);
}

static struct ironwood_throwable *new_throwable(void) {
    return ironwood_allocate(sizeof(struct ironwood_throwable), &gc_harness_error_type, allocation_error);
}

static int trace_graph(void) {
    struct ironwood_throwable *value = new_throwable(), *other = new_throwable();
    configure_trace(&first);
    ironwood_throwable_trace_capture(value);
    ironwood_throwable_trace_capture(other);
    void *roots[] = {value, other};
    gc_harness_collect(roots, 2);
    CHECK(iw_gc_snapshot().sidecars == 4 && native_live == 0);
    CHECK(value->trace_metadata->trace_storage != NULL && value->trace_metadata->trace_count == 1);
    CHECK(ironwood_throwable_trace_common(value, other) == 1);
    uint64_t total = iw_gc_total_count();
    configure_trace(&second);
    ironwood_throwable_trace_capture(value);
    gc_harness_collect(roots, 2);
    CHECK(iw_gc_snapshot().sidecars == 4 && iw_gc_total_count() == total);
    CHECK(value->trace_metadata->trace[0].site == &second);
    CHECK(ironwood_throwable_trace_common(value, other) == 0);
    ironwood_exception_add_secondary(value, other);
    roots[1] = NULL;
    // Only the primary is supplied; its secondary retains the other trace graph.
    for (int phase = 0; phase < 3; phase++) {
        gc_harness_collect(roots, 1);
        CHECK(iw_gc_live_count() == 3 && iw_gc_snapshot().sidecars == 5);
        CHECK(ironwood_exception_secondary_at(value, 0) == other);
        CHECK(other->trace_metadata->trace[0].site == &first && native_live == 0);
    }
    return 0;
}

static int capture_failure(int mode) {
    configure_trace(&first);
    struct ironwood_throwable *value = new_throwable();
    uint64_t total = iw_gc_total_count(), attempts = gc_sidecar_attempts;
    // Fail metadata, native PC scratch, then actual managed trace storage.
    if (mode == 0) gc_fail_sidecar_at = attempts + 1;
    if (mode == 1) fail_native = 1;
    if (mode == 2) gc_fail_sidecar_at = attempts + 2;
    ironwood_throwable_trace_capture(value);
    gc_fail_sidecar_at = 0;
    fail_native = 0;
    if (mode == 0) CHECK(value->trace_metadata == NULL && value->trace_status == IRONWOOD_TRACE_UNAVAILABLE);
    else CHECK(value->trace_metadata != NULL && value->trace_metadata->trace_state == IRONWOOD_TRACE_UNAVAILABLE);
    CHECK(gc_sidecar_attempts == attempts + (mode == 2 ? 2 : 1));
    void *roots[] = {value};
    gc_harness_collect(roots, 1);
    CHECK(iw_gc_snapshot().sidecars == (mode == 0 ? 0 : 1));
    CHECK(native_live == 0 && iw_gc_total_count() == total);
    // Failure leaves a reusable exception; a later refresh captures normally.
    ironwood_throwable_trace_capture(value);
    gc_harness_collect(roots, 1);
    CHECK(iw_gc_snapshot().sidecars == 2 && value->trace_metadata->trace_count == 1);
    CHECK(value->trace_metadata->trace[0].site == &first && native_live == 0);
    return 0;
}

static int emergency_trace(void) {
    configure_trace(&first);
    struct ironwood_throwable *value = new_throwable();
    uint64_t attempts = gc_sidecar_attempts, language = gc_language_attempts;
    int calls = native_calls;
    gc_fail_sidecar_at = attempts + 1;
    gc_fail_language = fail_native = 1;
    prepare_implicit_failure(value);
    capture_exception_trace(value);
    CHECK(value->trace_metadata == &implicit_failure_metadata);
    CHECK(implicit_failure_metadata.trace_state == IRONWOOD_TRACE_CAPTURED && implicit_failure_metadata.trace_count == 1);
    CHECK(gc_sidecar_attempts == attempts && gc_language_attempts == language && native_calls == calls);
    gc_fail_sidecar_at = 0;
    gc_fail_language = fail_native = 0;
    ironwood_exception_caught(value);
    void *roots[] = {value};
    gc_harness_collect(roots, 1);
    CHECK(implicit_failure_metadata.trace[0].site == &first && iw_gc_snapshot().sidecars == 0);
    return 0;
}

int main(void) {
    gc_harness_start();
    CHECK(trace_graph() == 0);
    gc_harness_collect(NULL, 0);
    CHECK(iw_gc_live_count() == 1 && iw_gc_snapshot().sidecars == 0);
    for (int mode = 0; mode < 3; mode++) {
        CHECK(capture_failure(mode) == 0);
        gc_harness_collect(NULL, 0);
        CHECK(iw_gc_live_count() == 1 && iw_gc_snapshot().sidecars == 0);
    }
    CHECK(emergency_trace() == 0);
    gc_harness_collect(NULL, 0);
    CHECK(iw_gc_live_count() == 1 && iw_gc_snapshot().sidecars == 0);
    gc_harness_finish();
    CHECK(native_live == 0);
    return 0;
}

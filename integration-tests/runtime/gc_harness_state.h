// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_HARNESS_STATE_H
#define IRONWOOD_GC_HARNESS_STATE_H

/* Direct C runtime probes use explicit native roots, not compiler stack maps.
 * Include after ironwood_runtime.c and after removing native interposers.
 * Unused compiler-entry/root-walk functions are discarded by the test linker;
 * none is stubbed or admitted with fabricated protocol metadata. */
#undef GC_generic_malloc
#undef iw_gc_new_storage
#include <assert.h>

#ifndef GC_HARNESS_ARRAY_KIND
#define GC_HARNESS_ARRAY_KIND IRONWOOD_ARRAY_BYTE
#endif
static struct ironwood_type_info string_type = {.name = "String"};
static struct ironwood_type_info array_type = {.name = "array"};
static const unsigned char gc_harness_membership[] = {1, 1};
static struct ironwood_type_info gc_harness_error_type = {
    .name = "GcHarnessFailure", .membership = gc_harness_membership
};
static struct ironwood_throwable *allocation_error;
const char iw_gc_expected_configuration[] = IRONWOOD_GC_CONFIG_SHA256;

_Static_assert(sizeof(struct iw_gc_string) == sizeof(struct ironwood_string), "String layout");
_Static_assert(offsetof(struct iw_gc_string, data) == offsetof(struct ironwood_string, units), "String tail");
_Static_assert(sizeof(struct iw_gc_array) == sizeof(struct ironwood_array), "array layout");
_Static_assert(offsetof(struct ironwood_throwable, trace_metadata) == 3 * sizeof(void *), "Throwable metadata slot");

static void gc_harness_start(void) {
    ironwood_gc_start();
    const struct iw_gc_layout string = {1, IW_GC_STRING, 1, 0, sizeof(struct ironwood_string), 0, NULL};
    const struct iw_gc_layout array = {1, IW_GC_ARRAY, 1, GC_HARNESS_ARRAY_KIND, sizeof(struct ironwood_array), 0, NULL};
    static const size_t refs[] = {
        offsetof(struct ironwood_throwable, message), offsetof(struct ironwood_throwable, cause),
        offsetof(struct ironwood_throwable, trace_metadata), offsetof(struct ironwood_throwable, owned_message)
    };
    const struct iw_gc_layout throwable = {1, IW_GC_FIXED, 1, 0, sizeof(struct ironwood_throwable), 4, refs};
    assert(iw_gc_bind_type(&string_type, &string));
    assert(iw_gc_bind_type(&array_type, &array));
    assert(iw_gc_bind_type(&gc_harness_error_type, &throwable));
    /* Paired rejection protects the qualification helper's exact-layout premise. */
    const size_t bad[] = {sizeof(void *) + 1};
    const struct iw_gc_layout invalid = {1, IW_GC_FIXED, 1, 0, sizeof(struct ironwood_throwable), 1, bad};
    assert(iw_gc_describe(&invalid) == NULL);
    void *immortals[3 + IRONWOOD_EMERGENCY_SECONDARY_CAPACITY];
    immortals[0] = &implicit_failure_metadata;
    immortals[1] = &emergency_association_metadata;
    immortals[2] = &implicit_failure_storage;
    for (size_t i = 0; i < IRONWOOD_EMERGENCY_SECONDARY_CAPACITY; i++) immortals[3 + i] = &emergency_secondary_pool[i];
    assert(iw_gc_register_immortals(immortals, 3 + IRONWOOD_EMERGENCY_SECONDARY_CAPACITY));
    assert(iw_gc_weak_native(&implicit_failure_metadata));
    assert(iw_gc_weak_native(&emergency_association_metadata));
    assert(iw_gc_weak_native_slot(&emergency_association_metadata, &emergency_association_metadata_in_use,
            sizeof(emergency_association_metadata_in_use)));
    for (size_t i = 0; i < IRONWOOD_EMERGENCY_SECONDARY_CAPACITY; i++) assert(iw_gc_weak_native(&emergency_secondary_pool[i]));
    allocation_error = ironwood_allocate(sizeof(*allocation_error), &gc_harness_error_type, NULL);
}

static void *gc_harness_roots[128];
static size_t gc_harness_root_count;

static int gc_harness_visit(uintptr_t value) {
    if (gc_harness_root_count == 128) return 0;
    gc_harness_roots[gc_harness_root_count++] = (void *)value;
    return iw_gc_expand_native_roots((void *)value, gc_harness_visit);
}

/* Only the supplied roots and current runtime owners survive. C locals are not
 * scanned. Call after last consumers/helper returns; never use dropped values. */
static void gc_harness_collect(void *const *values, size_t count) {
    memset(gc_harness_roots, 0, sizeof(gc_harness_roots));
    gc_harness_root_count = 0;
    iw_gc_prepare_native_graphs();
    if (allocation_error != NULL) assert(gc_harness_visit((uintptr_t)allocation_error));
    for (size_t i = 0; i < count; i++) {
        assert(values[i] == NULL || GC_base(values[i]) == values[i]);
        assert(gc_harness_visit((uintptr_t)values[i]));
    }
    assert(enumerate_runtime_owners(gc_harness_visit));
    assert(iw_gc_roots(gc_harness_root_count ? gc_harness_roots : NULL, gc_harness_root_count));
    assert(iw_gc_collect());
    assert(iw_gc_roots(NULL, 0));
}

static void gc_harness_finish(void) {
    allocation_error = NULL;
    gc_harness_collect(NULL, 0);
    struct iw_gc_snapshot state = iw_gc_snapshot();
    assert(state.live == 0 && state.sidecars == 0 && state.disabled);
    assert(active_exceptions == NULL && active_implicit_failure == NULL);
}
#endif

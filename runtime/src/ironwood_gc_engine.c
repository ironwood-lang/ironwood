// SPDX-License-Identifier: MIT OR Apache-2.0
// Isolated engine qualification. NativeBackend does not select this component.
#include "ironwood_gc_engine.h"
#include "ironwood_gc_configuration.h"
#include "gc.h"
#include "gc_mark.h"
#include <limits.h>
#include <string.h>

extern char **environ;
static unsigned kinds[4];
static int initialized;
static void **root_slots;
static size_t root_count;
static uint64_t total, total_at_collection, live_at_collection;
static uint64_t sidecars_at_collection, administration_at_collection;

const char *iw_gc_configuration(void) {
    return IRONWOOD_GC_CONFIG_SHA256;
}

int iw_gc_init(const char *configuration_sha256, size_t max_heap, unsigned retries) {
    if (initialized || configuration_sha256 == NULL || max_heap == 0
            || strcmp(configuration_sha256, iw_gc_configuration()) != 0) return 0;
    for (char **entry = environ; *entry != NULL; ++entry) {
        if (strncmp(*entry, "GC_", 3) == 0) return 0;
    }
    GC_set_dont_precollect(1);
    GC_set_finalize_on_demand(1);
    GC_set_max_heap_size(max_heap);
    GC_set_max_retries(retries);
    GC_INIT();
    GC_disable();
    if (GC_get_gc_no() != 0 || GC_is_incremental_mode() || !GC_is_disabled()) return 0;
    GC_word high = (GC_word)1 << (sizeof(GC_word) * CHAR_BIT - 1);
    kinds[IW_GC_LEAF] = GC_new_kind(GC_new_free_list(), 0, 0, 1);
    kinds[IW_GC_PAIR] = GC_new_kind(GC_new_free_list(), high | (high >> 1) | GC_DS_BITMAP, 0, 1);
    kinds[IW_GC_SIDECAR] = GC_new_kind(GC_new_free_list(), high | GC_DS_BITMAP, 0, 1);
    kinds[IW_GC_TRACE] = GC_new_kind(GC_new_free_list(), 0, 0, 1);
    initialized = 1;
    return 1;
}

void *iw_gc_allocate(enum iw_gc_fixture_kind kind, size_t bytes) {
    if (!initialized || !GC_is_disabled() || (unsigned)kind >= 4
            || bytes < 2 * sizeof(void *)) return NULL;
    void *result = GC_generic_malloc(bytes, kinds[kind]);
    if (result != NULL && (kind == IW_GC_LEAF || kind == IW_GC_PAIR)) ++total;
    return result;
}

int iw_gc_roots(void **scratch, size_t count) {
    if (!initialized || !GC_is_disabled() || (scratch == NULL) != (count == 0)
            || count > SIZE_MAX / sizeof(void *)
            || (uintptr_t)scratch > UINTPTR_MAX - count * sizeof(void *)) return 0;
    /* Cold fixture boundary only. There are no production allocation checks.
     * Check each slot address so a range cannot straddle a managed allocation.
     */
    for (size_t index = 0; index < count; ++index) {
        if (GC_base(scratch + index) != NULL) return 0;
    }
    if (root_count != 0) GC_remove_roots(root_slots, root_slots + root_count);
    root_slots = scratch;
    root_count = count;
    if (count != 0) GC_add_roots(scratch, scratch + count);
    return 1;
}

struct counts { uint64_t language, sidecars, administration; };

static void visit(void *object, size_t bytes, void *data) {
    (void)bytes;
    struct counts *counts = data;
    int kind = GC_get_kind_and_size(object, NULL);
    if (kind == (int)kinds[IW_GC_LEAF] || kind == (int)kinds[IW_GC_PAIR]) ++counts->language;
    else if (kind == (int)kinds[IW_GC_SIDECAR] || kind == (int)kinds[IW_GC_TRACE]) ++counts->sidecars;
    else ++counts->administration;
}

static void *enumerate(void *data) {
    GC_enumerate_reachable_objects_inner(visit, data);
    return NULL;
}

int iw_gc_collect(void) {
    if (!initialized || !GC_is_disabled()) return 0;
    GC_word before = GC_get_gc_no();
    /* All roots/scratch already exist. This balanced window contains only the
     * synchronous engine operation: no managed allocation, user code, finalizer
     * registration/invocation or exposed callback. EAGER_SWEEP completes here.
     */
    GC_enable();
    GC_gcollect();
    GC_disable();
    if (GC_get_gc_no() != before + 1 || !GC_is_disabled()) return 0;
    struct counts counts = {0};
    GC_call_with_alloc_lock(enumerate, &counts);
    live_at_collection = counts.language;
    total_at_collection = total;
    sidecars_at_collection = counts.sidecars;
    administration_at_collection = counts.administration;
    return 1;
}

struct iw_gc_snapshot iw_gc_snapshot(void) {
    struct GC_prof_stats_s stats = {0};
    if (initialized) GC_get_prof_stats(&stats, sizeof(stats));
    struct iw_gc_snapshot result = {
        initialized ? GC_get_gc_no() : 0, total,
        live_at_collection + (total - total_at_collection),
        sidecars_at_collection, administration_at_collection,
        stats.heapsize_full, stats.free_bytes_full, stats.bytes_reclaimed_since_gc,
        initialized && GC_is_disabled()
    };
    return result;
}

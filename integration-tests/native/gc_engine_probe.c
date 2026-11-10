// SPDX-License-Identifier: MIT OR Apache-2.0
// Isolated GC1 fixture. These fixed C layouts are not production Ironwood maps.
#include "ironwood_gc_engine.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void *volatile ambient_static;

static void initialize(void) {
    assert(!iw_gc_init("wrong-configuration", 32 * 1024 * 1024, 2));
    assert(iw_gc_snapshot().collections == 0);
    assert(iw_gc_init(iw_gc_configuration(), 32 * 1024 * 1024, 2));
    assert(!iw_gc_init(iw_gc_configuration(), 32 * 1024 * 1024, 2));
    assert(iw_gc_snapshot().disabled && iw_gc_snapshot().collections == 0);
}

static void *allocate(enum iw_gc_fixture_kind kind, size_t bytes) {
    unsigned char *object = iw_gc_allocate(kind, bytes);
    assert(object != NULL);
    for (size_t index = 0; index < bytes; ++index) assert(object[index] == 0);
    return object;
}

static void collect(uint64_t language, uint64_t sidecars) {
    struct iw_gc_snapshot before = iw_gc_snapshot();
    assert(iw_gc_collect());
    struct iw_gc_snapshot after = iw_gc_snapshot();
    assert(after.disabled && after.collections == before.collections + 1);
    assert(after.live == language && after.sidecars == sidecars);
    printf("{\"case\":\"accounting\",\"gc\":%llu,\"language\":%llu,\"sidecars\":%llu,"
           "\"administration\":%llu,\"total\":%llu}\n",
           (unsigned long long)after.collections, (unsigned long long)after.live,
           (unsigned long long)after.sidecars, (unsigned long long)after.administration,
           (unsigned long long)after.total);
}

static void policy(void) {
    size_t count = 0;
    while (count < 1000000 && iw_gc_allocate(IW_GC_LEAF, 64) != NULL) ++count;
    struct iw_gc_snapshot exhausted = iw_gc_snapshot();
    assert(count < 1000000 && count > 200000);
    assert(exhausted.collections == 0 && exhausted.disabled && exhausted.total == count);
    collect(0, 0);
    struct iw_gc_snapshot before = iw_gc_snapshot();
    for (size_t index = 0; index < 200000; ++index) allocate(IW_GC_LEAF, 64);
    struct iw_gc_snapshot after = iw_gc_snapshot();
    assert(after.collections == 1 && after.disabled && after.live == 200000);
    assert(after.heap_bytes == before.heap_bytes); // Actual heap storage reused.
    assert(after.reclaimed_since_collection == before.reclaimed_since_collection);
    printf("{\"case\":\"policy\",\"startup_collections\":0,\"automatic_collections\":0,"
           "\"allocations_until_null\":%zu,\"replacement_allocations\":200000,"
           "\"deferred_reclaimed_bytes\":0,\"heap_bytes\":%zu}\n", count, after.heap_bytes);
}

static void heap_reservation(void) {
    assert(!iw_gc_reserve(1));
    assert(!iw_gc_reserve(64 * 1024 * 1024)); // Above the initialized 32 MiB cap.
    assert(iw_gc_reserve(8 * 1024 * 1024));
    size_t capacity = iw_gc_snapshot().heap_bytes;
    assert(capacity >= 8 * 1024 * 1024 && capacity <= 32 * 1024 * 1024);
    assert(iw_gc_reserve(8 * 1024 * 1024));
    assert(iw_gc_snapshot().heap_bytes == capacity && iw_gc_snapshot().collections == 0);
    // Cross the collector's idle-page unmapping threshold. Reserved capacity is
    // still reusable; -Xms is not a promise about resident physical pages.
    for (int round = 0; round < 10; ++round) collect(0, 0);
    assert(iw_gc_snapshot().heap_bytes == capacity);
    assert(iw_gc_reserve(8 * 1024 * 1024));
    allocate(IW_GC_LEAF, 4 * 1024 * 1024);
    assert(iw_gc_snapshot().heap_bytes == capacity && iw_gc_snapshot().collections == 10);
    puts("{\"case\":\"heap-reservation\",\"minimum_retained\":true,\"automatic_collections\":0}");
}

static void accounting(void) {
    void **roots = calloc(4096, sizeof(*roots));
    assert(roots != NULL && iw_gc_roots(roots, 4096));
    for (size_t index = 0; index < 4000; ++index) {
        void **object = allocate(IW_GC_PAIR, index % 3 ? 64 : 96);
        object[0] = allocate(IW_GC_LEAF, 64);
        if (index % 2 == 0) roots[index] = object;
    }
    for (size_t index = 0; index < 3; ++index) {
        void *large = allocate(IW_GC_LEAF, 128 * 1024);
        if (index < 2) roots[4000 + index] = large;
    }
    for (size_t index = 0; index < 7; ++index) {
        void **sidecar = allocate(IW_GC_SIDECAR, 64);
        sidecar[0] = allocate(IW_GC_LEAF, 64);
        roots[4010 + index] = sidecar;
    }
    for (size_t index = 0; index < 5; ++index) roots[4020 + index] = allocate(IW_GC_TRACE, 64);
    assert(iw_gc_snapshot().total == 8010 && iw_gc_snapshot().collections == 0);
    collect(4009, 12);
    assert(iw_gc_snapshot().administration == 6); // Six bounded kind free-list arrays.
    for (size_t index = 0; index < 123; ++index) allocate(IW_GC_LEAF, 64);
    assert(iw_gc_snapshot().live == 4132 && iw_gc_snapshot().collections == 1);
    void *kept_holder = roots[0], *kept_large = roots[4000], *kept_sidecar = roots[4010];
    memset(roots, 0, 4096 * sizeof(*roots));
    roots[0] = kept_holder;
    roots[1] = kept_large;
    roots[2] = kept_sidecar;
    collect(4, 1);
    memset(roots, 0, 4096 * sizeof(*roots));
    collect(0, 0);
    for (size_t round = 0; round < 3; ++round) {
        for (size_t index = 0; index < 2000; ++index) {
            void *object = allocate(IW_GC_LEAF, 64);
            if (index < 17) roots[index] = object;
        }
        collect(17, 0);
        memset(roots, 0, 4096 * sizeof(*roots));
        collect(0, 0);
    }
    assert(iw_gc_roots(NULL, 0));
    free(roots);
}

static void domains_and_roots(void) {
    void **roots = calloc(8, sizeof(*roots));
    assert(roots != NULL && iw_gc_roots(roots, 8));
    void **managed = allocate(IW_GC_PAIR, 64);
    assert(!iw_gc_roots(managed, 8));
    assert(!iw_gc_roots(managed + 1, 1));
    assert(!iw_gc_roots(NULL, 1) && !iw_gc_roots(roots, SIZE_MAX));
    assert(iw_gc_allocate((enum iw_gc_fixture_kind)99, 64) == NULL);
    assert(iw_gc_allocate(IW_GC_PAIR, 1) == NULL);
    roots[0] = managed;
    managed[0] = allocate(IW_GC_LEAF, 64);
    // Pointer-shaped primitive data must not retain its target.
    ((uintptr_t *)managed)[2] = (uintptr_t)allocate(IW_GC_LEAF, 64);
    collect(2, 0);
    roots[0] = NULL;
    ambient_static = allocate(IW_GC_LEAF, 64);
    void *volatile ambient_stack = allocate(IW_GC_LEAF, 64);
    void **native_unregistered = calloc(1, sizeof(void *));
    assert(native_unregistered != NULL);
    native_unregistered[0] = allocate(IW_GC_LEAF, 64);
    collect(0, 0); // Static, stack/register and native-library storage are not roots.
    assert(ambient_static != NULL && ambient_stack != NULL && native_unregistered[0] != NULL);
    ambient_static = NULL;
    ambient_stack = NULL;
    free(native_unregistered);
    assert(iw_gc_roots(NULL, 0));
    free(roots);
    puts("{\"case\":\"domains-and-roots\",\"managed_scratch_rejected\":true,"
         "\"ambient_roots\":false,\"numeric_field_retention\":false}");
}

static int run(const char *mode) {
    if (strcmp(mode, "environment") == 0) {
        assert(!iw_gc_init(iw_gc_configuration(), 32 * 1024 * 1024, 2));
        assert(iw_gc_snapshot().collections == 0);
        puts("{\"case\":\"environment\",\"rejected_before_initialization\":true}");
        return 0;
    }
    initialize();
    if (strcmp(mode, "policy") == 0) policy();
    else if (strcmp(mode, "heap") == 0) heap_reservation();
    else if (strcmp(mode, "accounting") == 0) accounting();
    else if (strcmp(mode, "domains") == 0) domains_and_roots();
    else abort();
    return 0;
}
#ifdef IW_GC_IMAGE
/* The sole exported fixture surface. Full two-image execution belongs to GC6. */
__attribute__((visibility("default"))) int ironwood_gc1_probe(const char *mode);
int ironwood_gc1_probe(const char *mode) {
    return run(mode);
}
#else
int main(int argc, char **argv) {
    assert(argc == 2);
    return run(argv[1]);
}
#endif

// SPDX-License-Identifier: MIT OR Apache-2.0
// Isolated GC0 accounting experiment, never linked into Ironwood programs.
// Enumerate only after full eager collection, excluding runtime-only kinds.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gc.h"
#include "gc_mark.h"

typedef struct TypeInfo { GC_word descriptor; } TypeInfo;
typedef struct Object { const TypeInfo *type; void *child; } Object;
typedef struct Counts { size_t language, sidecars, other; } Counts;
static TypeInfo leaf, holder;
static unsigned language_kind, sidecar_kind, trace_kind;
static uint64_t total, total_at_collection, live_at_collection;

static Object *allocate(size_t bytes, const TypeInfo *type) {
    Object *object = GC_generic_malloc(bytes, language_kind);
    assert(object != NULL);
    object->type = type;
    ++total; // Models the existing successful-language-allocation counter.
    return object;
}

static void visit(void *object, size_t bytes, void *data) {
    Counts *counts = data;
    size_t size;
    int kind = GC_get_kind_and_size(object, &size);
    assert(size == bytes);
    if (kind == (int)language_kind) ++counts->language;
    else if (kind == (int)sidecar_kind || kind == (int)trace_kind) ++counts->sidecars;
    else ++counts->other;
}

static void *enumerate(void *data) {
    GC_enumerate_reachable_objects_inner(visit, data);
    return NULL;
}

static void collect(size_t expected_language, size_t expected_sidecars) {
    GC_word before = GC_get_gc_no();
    GC_enable();
    GC_gcollect();
    GC_disable();
    assert(GC_get_gc_no() == before + 1);
    Counts counts = {0};
    GC_call_with_alloc_lock(enumerate, &counts);
    assert(counts.language == expected_language);
    assert(counts.sidecars == expected_sidecars);
    // Collector administration may itself be enumerable. Only designated
    // language kinds contribute; neither other kinds nor sidecars do.
    total_at_collection = total;
    live_at_collection = counts.language;
    printf("{\"case\":\"accounting\",\"gc\":%lu,\"language\":%zu,"
           "\"sidecars\":%zu,\"other\":%zu,\"total\":%llu}\n",
           (unsigned long)GC_get_gc_no(), counts.language, counts.sidecars,
           counts.other, (unsigned long long)total);
}

int main(void) {
    GC_set_dont_precollect(1);
    GC_INIT();
    GC_disable();
    holder.descriptor = ((GC_word)1 << (sizeof(GC_word) * 8 - 2)) | GC_DS_BITMAP;
    GC_word indirect = (GC_word)(-(intptr_t)GC_INDIR_PER_OBJ_BIAS + GC_DS_PER_OBJECT);
    language_kind = GC_new_kind(GC_new_free_list(), indirect, 0, 1);
    sidecar_kind = GC_new_kind(GC_new_free_list(),
            ((GC_word)1 << (sizeof(GC_word) * 8 - 1)) | GC_DS_BITMAP, 0, 1);
    trace_kind = GC_new_kind(GC_new_free_list(), 0, 0, 1);
    void **roots = calloc(4096, sizeof(*roots));
    assert(roots != NULL);
    GC_add_roots(roots, roots + 4096);
    for (size_t index = 0; index < 4000; ++index) {
        Object *object = allocate(index % 3 ? 64 : 96, &holder);
        object->child = allocate(64, &leaf);
        if (index % 2 == 0) roots[index] = object;
    }
    for (size_t index = 0; index < 3; ++index) {
        Object *large = allocate(128 * 1024, &leaf);
        if (index < 2) roots[4000 + index] = large;
    }
    for (size_t index = 0; index < 7; ++index) {
        void **sidecar = GC_generic_malloc(64, sidecar_kind);
        assert(sidecar != NULL);
        sidecar[0] = allocate(64, &leaf);
        roots[4010 + index] = sidecar;
    }
    for (size_t index = 0; index < 5; ++index) {
        roots[4020 + index] = GC_generic_malloc(64, trace_kind);
        assert(roots[4020 + index] != NULL);
    }
    assert(total == 8010 && GC_get_gc_no() == 0);
    collect(4009, 12);
    for (size_t index = 0; index < 123; ++index) allocate(64, &leaf);
    assert(live_at_collection + (total - total_at_collection) == 4132);
    assert(GC_get_gc_no() == 1); // Allocation cannot trigger collection.
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
            Object *object = allocate(64, &leaf);
            if (index < 17) roots[index] = object;
        }
        collect(17, 0);
        memset(roots, 0, 4096 * sizeof(*roots));
        collect(0, 0);
    }
    GC_remove_roots(roots, roots + 4096);
    free(roots);
    return 0;
}

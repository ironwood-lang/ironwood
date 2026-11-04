// SPDX-License-Identifier: MIT OR Apache-2.0
// Isolated engine qualification. NativeBackend does not select this component.
#include "ironwood_gc_engine.h"
#include "ironwood_gc_configuration.h"
#include "gc.h"
#include "gc_mark.h"
#include <limits.h>
#include <string.h>
#include <stdlib.h>

extern char **environ;
static unsigned kinds[4], language_kind, sidecar_kind, map_proc;
struct iw_gc_type {
    uintptr_t identity; /* descriptor is deliberately not at free-list link offset */
    GC_word descriptor;
    struct iw_gc_layout layout;
};
static size_t metadata_remaining = SIZE_MAX;

static size_t element_size(unsigned kind) {
    static const unsigned char sizes[] = {0, 1, 1, 2, 2, 4, 8, 4, 8, sizeof(void *)};
    return kind < sizeof(sizes) ? sizes[kind] : 0;
}

static struct GC_ms_entry *scan(GC_word *address, struct GC_ms_entry *top,
                                struct GC_ms_entry *limit, GC_word env) {
    (void)env;
    const struct iw_gc_type *type = *(const struct iw_gc_type **)address;
    const struct iw_gc_layout *layout = &type->layout;
    size_t count = layout->reference_count;
    if (layout->shape == IW_GC_ARRAY) count = ((struct iw_gc_array *)address)->length;
    for (size_t index = 0; index < count; ++index) {
        size_t offset = layout->shape == IW_GC_ARRAY
                ? sizeof(struct iw_gc_array) + index * sizeof(void *) : layout->references[index];
        void **slot = (void **)((unsigned char *)address + offset);
        top = GC_MARK_AND_PUSH(*slot, top, limit, slot);
    }
    return top;
}
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
    map_proc = GC_new_proc(scan);
    GC_word indirect = (GC_word)(-(intptr_t)offsetof(struct iw_gc_type, descriptor)
                                - GC_INDIR_PER_OBJ_BIAS + GC_DS_PER_OBJECT);
    language_kind = GC_new_kind(GC_new_free_list(), indirect, 0, 1);
    sidecar_kind = GC_new_kind(GC_new_free_list(), indirect, 0, 1);
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

int iw_gc_metadata_budget(size_t bytes) {
    if (!initialized || !GC_is_disabled()) return 0;
    metadata_remaining = bytes;
    return 1;
}

const struct iw_gc_type *iw_gc_describe(const struct iw_gc_layout *layout) {
    if (!initialized || !GC_is_disabled() || layout == NULL || layout->version != 1
            || layout->language > 1 || layout->size < sizeof(void *)
            || layout->shape > IW_GC_STRING
            || layout->reference_count > (SIZE_MAX - sizeof(struct iw_gc_type)) / sizeof(size_t)
            || (layout->reference_count != 0 && layout->references == NULL)) return NULL;
    if (layout->shape == IW_GC_FIXED) {
        if (layout->element_kind != 0) return NULL;
        for (size_t index = 0; index < layout->reference_count; ++index) {
            size_t offset = layout->references[index];
            if (offset < sizeof(void *) || offset % sizeof(void *) != 0
                    || offset > layout->size - sizeof(void *)
                    || (index != 0 && offset <= layout->references[index - 1])) return NULL;
        }
    } else if (layout->reference_count != 0 || !layout->language
            || (layout->shape == IW_GC_ARRAY && (layout->size != sizeof(struct iw_gc_array)
                                                  || element_size(layout->element_kind) == 0))
            || (layout->shape == IW_GC_STRING && (layout->size != sizeof(struct iw_gc_string)
                                                   || layout->element_kind != 0))) return NULL;
    size_t bytes = sizeof(struct iw_gc_type) + layout->reference_count * sizeof(size_t);
    if (bytes > metadata_remaining) return NULL;
    struct iw_gc_type *type = calloc(1, bytes);
    if (type == NULL) return NULL;
    metadata_remaining -= bytes;
    type->identity = 1;
    type->layout = *layout;
    size_t *offsets = (size_t *)(type + 1);
    if (layout->reference_count != 0) memcpy(offsets, layout->references,
                                            layout->reference_count * sizeof(size_t));
    type->layout.references = offsets;
    GC_word bitmap = 0;
    int large = 0;
    for (size_t index = 0; index < layout->reference_count; ++index) {
        size_t word = offsets[index] / sizeof(void *);
        if (word >= sizeof(GC_word) * CHAR_BIT - GC_DS_TAG_BITS) { large = 1; break; }
        bitmap |= (GC_word)1 << (sizeof(GC_word) * CHAR_BIT - 1 - word);
    }
    type->descriptor = large || (layout->shape == IW_GC_ARRAY && layout->element_kind == 9)
            ? GC_MAKE_PROC(map_proc, 0) : bitmap == 0 ? 0 : bitmap | GC_DS_BITMAP;
    return type;
}

void *iw_gc_new(const struct iw_gc_type *type, size_t length) {
    if (!initialized || !GC_is_disabled() || type == NULL) return NULL;
    const struct iw_gc_layout *layout = &type->layout;
    size_t bytes = layout->size, stride = 0;
    if (layout->shape == IW_GC_FIXED) {
        if (length != 0) return NULL;
    } else {
        if (length > INT32_MAX) return NULL;
        stride = layout->shape == IW_GC_ARRAY ? element_size(layout->element_kind) : sizeof(uint16_t);
        if (length > (SIZE_MAX - bytes) / stride) return NULL;
        bytes += length * stride;
    }
    void *object = GC_generic_malloc(bytes, layout->language ? language_kind : sidecar_kind);
    if (object == NULL) return NULL;
    /* Disabled, synchronous engine: no callback/collection between allocation,
     * full zeroing by the kind, final dynamic header and tail initialization.
     * Constructors must never replace the dynamic header with a base header.
     */
    *(const struct iw_gc_type **)object = type;
    if (layout->shape == IW_GC_ARRAY) {
        struct iw_gc_array *array = object;
        array->length = length;
        array->element_size = stride;
        array->element_kind = layout->element_kind;
    } else if (layout->shape == IW_GC_STRING) {
        ((struct iw_gc_string *)object)->length = (int32_t)length;
    }
    if (layout->language) ++total;
    return object;
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
    if (kind == (int)kinds[IW_GC_LEAF] || kind == (int)kinds[IW_GC_PAIR] || kind == (int)language_kind) ++counts->language;
    else if (kind == (int)kinds[IW_GC_SIDECAR] || kind == (int)kinds[IW_GC_TRACE] || kind == (int)sidecar_kind) ++counts->sidecars;
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

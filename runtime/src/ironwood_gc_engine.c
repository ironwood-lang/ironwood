// SPDX-License-Identifier: MIT OR Apache-2.0
// Exact, synchronous, private-per-image collector adapter.
#include "ironwood_gc_engine.h"
#include "ironwood_gc_configuration.h"
#include "gc.h"
#include "gc_mark.h"
#include <limits.h>
#include <string.h>
#include <stdlib.h>

extern char **environ;
unsigned iw_gc_language_kind;
static unsigned kinds[4], sidecar_kind, map_proc;
struct iw_gc_type {
    uintptr_t identity; /* descriptor is deliberately not at free-list link offset */
    GC_word descriptor;
    struct iw_gc_layout layout;
};
struct known_type { const struct iw_gc_type *type; struct known_type *next; };
static struct known_type *known_types;
struct native_object {
    uintptr_t base;
    const struct iw_gc_type *type;
    uint64_t walk;
    unsigned live, weak;
};
static struct native_object *native_objects;
static size_t native_count, *native_work;
static uint64_t native_walk;
struct weak_slot { size_t owner, bytes; void *slot; };
static struct weak_slot weak_slots[8];
static size_t weak_slot_count;
static size_t metadata_remaining = SIZE_MAX;

static size_t native_index(uintptr_t base) {
    if (native_count == 0 || base < native_objects[0].base
            || base > native_objects[native_count - 1].base) return SIZE_MAX;
    size_t low = 0, high = native_count;
    while (low < high) {
        size_t middle = low + (high - low) / 2;
        if (native_objects[middle].base < base) low = middle + 1;
        else high = middle;
    }
    return low < native_count && native_objects[low].base == base ? low : SIZE_MAX;
}

static uint64_t begin_native_walk(void) {
    if (++native_walk == 0) {
        for (size_t i = 0; i < native_count; ++i) native_objects[i].walk = 0;
        ++native_walk;
    }
    return native_walk;
}

static size_t reference_offset(const struct iw_gc_layout *layout, size_t index) {
    return layout->shape == IW_GC_ARRAY
            ? sizeof(struct iw_gc_array) + index * sizeof(void *) : layout->references[index];
}

static size_t reference_count(const struct iw_gc_layout *layout, void *base) {
    return layout->shape == IW_GC_ARRAY
            ? layout->element_kind == 9 ? ((struct iw_gc_array *)base)->length : 0 : layout->reference_count;
}

static struct GC_ms_entry *push_exact(void *base, struct GC_ms_entry *top,
                                      struct GC_ms_entry *limit, void **source) {
    size_t first = native_index((uintptr_t)base);
    if (first == SIZE_MAX) return GC_MARK_AND_PUSH(base, top, limit, source);
    uint64_t walk = begin_native_walk();
    size_t head = 0, tail = 1;
    native_work[0] = first;
    native_objects[first].walk = walk;
    while (head < tail) {
        struct native_object *object = &native_objects[native_work[head++]];
        object->live = 1;
        const struct iw_gc_layout *layout = &object->type->layout;
        size_t count = reference_count(layout, (void *)object->base);
        for (size_t i = 0; i < count; ++i) {
            void **slot = (void **)(object->base + reference_offset(layout, i));
            size_t index = native_index((uintptr_t)*slot);
            if (index == SIZE_MAX) top = GC_MARK_AND_PUSH(*slot, top, limit, slot);
            else if (native_objects[index].walk != walk) {
                native_objects[index].walk = walk;
                native_work[tail++] = index;
            }
        }
    }
    /* Each invocation walks anew. BDWGC overflow recovery may rescan the same
     * managed object; a previous native visit must not suppress its edges. */
    return top;
}

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
        top = push_exact(*slot, top, limit, slot);
    }
    return top;
}
static int initialized;
static void **root_slots;
static size_t root_count;
uint64_t iw_gc_language_total;
uint64_t iw_gc_total_at_collection, iw_gc_live_at_collection;
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
    iw_gc_language_kind = GC_new_kind(GC_new_free_list(), indirect, 0, 1);
    sidecar_kind = GC_new_kind(GC_new_free_list(), indirect, 0, 1);
    initialized = 1;
    return 1;
}

void *iw_gc_allocate(enum iw_gc_fixture_kind kind, size_t bytes) {
    if (!initialized || !GC_is_disabled() || (unsigned)kind >= 4
            || bytes < 2 * sizeof(void *)) return NULL;
    void *result = GC_generic_malloc(bytes, kinds[kind]);
    if (result != NULL && (kind == IW_GC_LEAF || kind == IW_GC_PAIR)) ++iw_gc_language_total;
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
            || layout->reference_count > (SIZE_MAX - sizeof(struct iw_gc_type) - sizeof(struct known_type)) / sizeof(size_t)
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
    size_t bytes = sizeof(struct iw_gc_type) + layout->reference_count * sizeof(size_t) + sizeof(struct known_type);
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
    struct known_type *record = (struct known_type *)(offsets + layout->reference_count);
    *record = (struct known_type){type, known_types};
    known_types = record;
    type->descriptor = layout->reference_count != 0 || (layout->shape == IW_GC_ARRAY && layout->element_kind == 9)
            ? GC_MAKE_PROC(map_proc, 0) : 0;
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
    void *object = GC_generic_malloc(bytes, layout->language ? iw_gc_language_kind : sidecar_kind);
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
    if (layout->language) ++iw_gc_language_total;
    return object;
}

int iw_gc_bind_type(void *metadata, const struct iw_gc_layout *layout) {
    const struct iw_gc_type *described = iw_gc_describe(layout);
    if (metadata == NULL || described == NULL) return 0;
    struct iw_gc_type *type = metadata;
    if (sizeof(struct known_type) > metadata_remaining) return 0;
    struct known_type *record = calloc(1, sizeof(*record));
    if (record == NULL) return 0;
    metadata_remaining -= sizeof(*record);
    type->descriptor = described->descriptor;
    type->layout = described->layout;
    *record = (struct known_type){type, known_types};
    known_types = record;
    return 1;
}

void *iw_gc_new_storage(const struct iw_gc_type *type, size_t bytes) {
    /* Cold image validation and the explicit collector boundary establish the
     * disabled engine invariant. Allocation never polls or schedules collection. */
    if (type == NULL || bytes < type->layout.size) return NULL;
    const struct iw_gc_layout *layout = &type->layout;
    if (layout->shape == IW_GC_FIXED) {
        if (bytes != layout->size && (layout->language || layout->reference_count)) return NULL;
    } else {
        size_t stride = layout->shape == IW_GC_STRING ? sizeof(uint16_t) : element_size(layout->element_kind);
        if (stride == 0 || (bytes - layout->size) % stride
                || (bytes - layout->size) / stride > INT32_MAX) return NULL;
    }
    void *object = GC_generic_malloc(bytes, layout->language ? iw_gc_language_kind : sidecar_kind);
    if (object != NULL) {
        *(const struct iw_gc_type **)object = type;
        if (layout->language) ++iw_gc_language_total;
    }
    return object;
}

uint64_t iw_gc_total_count(void) { return iw_gc_language_total; }
uint64_t iw_gc_live_count(void) { return iw_gc_live_at_collection + iw_gc_language_total - iw_gc_total_at_collection; }

static int compare_native(const void *left, const void *right) {
    uintptr_t a = ((const struct native_object *)left)->base, b = ((const struct native_object *)right)->base;
    return (a > b) - (a < b);
}

int iw_gc_register_immortals(void *const *bases, size_t count) {
    if (!initialized || !GC_is_disabled() || native_objects != NULL || count == 0 || count > 1048576
            || count > SIZE_MAX / (sizeof(struct native_object) + sizeof(size_t))) return 0;
    struct native_object *objects = calloc(count, sizeof(*objects));
    size_t *work = calloc(count, sizeof(*work));
    if (objects == NULL || work == NULL) { free(objects); free(work); return 0; }
    for (size_t i = 0; i < count; ++i) {
        if (bases[i] == NULL || GC_base(bases[i]) != NULL) goto invalid;
        const struct iw_gc_type *type = *(const struct iw_gc_type **)bases[i];
        struct known_type *known = known_types;
        while (known != NULL && known->type != type) known = known->next;
        if (known == NULL) goto invalid;
        objects[i] = (struct native_object){(uintptr_t)bases[i], type, 0, 0, 0};
    }
    qsort(objects, count, sizeof(*objects), compare_native);
    for (size_t i = 1; i < count; ++i) if (objects[i - 1].base == objects[i].base) goto invalid;
    native_objects = objects; native_work = work; native_count = count;
    return 1;
invalid:
    free(objects); free(work); return 0;
}

int iw_gc_weak_native(void *base) {
    size_t index = native_index((uintptr_t)base);
    if (index == SIZE_MAX || native_objects[index].type->layout.shape != IW_GC_FIXED
            || native_objects[index].type->layout.language) return 0;
    native_objects[index].weak = 1;
    return 1;
}

int iw_gc_weak_native_slot(void *base, void *slot, size_t bytes) {
    size_t index = native_index((uintptr_t)base);
    if (index == SIZE_MAX || !native_objects[index].weak || slot == NULL || bytes == 0
            || bytes > sizeof(uintptr_t) || GC_base(slot) != NULL || weak_slot_count == 8) return 0;
    weak_slots[weak_slot_count++] = (struct weak_slot){index, bytes, slot};
    return 1;
}

void iw_gc_prepare_native_graphs(void) {
    for (size_t i = 0; i < native_count; ++i) native_objects[i].live = 0;
}

int iw_gc_expand_native_roots(void *base, int (*visit)(uintptr_t)) {
    size_t first = native_index((uintptr_t)base);
    if (first == SIZE_MAX) return 1;
    uint64_t walk = begin_native_walk();
    size_t head = 0, tail = 1;
    native_work[0] = first; native_objects[first].walk = walk;
    while (head < tail) {
        struct native_object *object = &native_objects[native_work[head++]];
        object->live = 1;
        const struct iw_gc_layout *layout = &object->type->layout;
        size_t count = reference_count(layout, (void *)object->base);
        for (size_t i = 0; i < count; ++i) {
            uintptr_t value;
            memcpy(&value, (void *)(object->base + reference_offset(layout, i)), sizeof(value));
            size_t index = native_index(value);
            if (index == SIZE_MAX) { if (!visit(value)) return 0; }
            else if (native_objects[index].walk != walk) {
                native_objects[index].walk = walk;
                native_work[tail++] = index;
            }
        }
    }
    return 1;
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
    if (kind == (int)kinds[IW_GC_LEAF] || kind == (int)kinds[IW_GC_PAIR] || kind == (int)iw_gc_language_kind) ++counts->language;
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
    for (size_t i = 0; i < native_count; ++i) {
        struct native_object *object = &native_objects[i];
        if (object->weak && !object->live) {
            memset((void *)(object->base + sizeof(void *)), 0, object->type->layout.size - sizeof(void *));
        }
    }
    for (size_t i = 0; i < weak_slot_count; ++i) {
        if (!native_objects[weak_slots[i].owner].live) memset(weak_slots[i].slot, 0, weak_slots[i].bytes);
    }
    struct counts counts = {0};
    GC_call_with_alloc_lock(enumerate, &counts);
    iw_gc_live_at_collection = counts.language;
    iw_gc_total_at_collection = iw_gc_language_total;
    sidecars_at_collection = counts.sidecars;
    administration_at_collection = counts.administration;
    return 1;
}

struct iw_gc_snapshot iw_gc_snapshot(void) {
    struct GC_prof_stats_s stats = {0};
    if (initialized) GC_get_prof_stats(&stats, sizeof(stats));
    struct iw_gc_snapshot result = {
        initialized ? GC_get_gc_no() : 0, iw_gc_language_total,
        iw_gc_live_at_collection + (iw_gc_language_total - iw_gc_total_at_collection),
        sidecars_at_collection, administration_at_collection,
        stats.heapsize_full, stats.free_bytes_full, stats.bytes_reclaimed_since_gc,
        initialized && GC_is_disabled()
    };
    return result;
}

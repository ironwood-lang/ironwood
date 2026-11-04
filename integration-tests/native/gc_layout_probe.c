// SPDX-License-Identifier: MIT OR Apache-2.0
// GC2 only. Explicit native roots, no compiler stack/root coverage is implied.
#define _POSIX_C_SOURCE 200809L
#include "ironwood_gc_engine.h"
#include "../../runtime/include/ironwood_bridge.h"
#include "gc.h"
#include "gc_mark.h"
#include "layouts.h"
#include <assert.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

_Static_assert(sizeof(struct iw_gc_array) == sizeof(struct ironwood_array), "production array header size");
_Static_assert(offsetof(struct iw_gc_array, data) == offsetof(struct ironwood_array, data), "production array tail");
_Static_assert(offsetof(struct iw_gc_array, length) == offsetof(struct ironwood_array, length), "production array length");
_Static_assert(offsetof(struct iw_gc_array, element_size) == offsetof(struct ironwood_array, element_size), "production array stride");
_Static_assert(offsetof(struct iw_gc_array, element_kind) == offsetof(struct ironwood_array, element_kind), "production array kind");
_Static_assert(sizeof(struct iw_gc_string) == sizeof(struct ironwood_string), "production String header size");
_Static_assert(offsetof(struct iw_gc_string, data) == offsetof(struct ironwood_string, units), "production String tail");
_Static_assert(offsetof(struct iw_gc_string, length) == offsetof(struct ironwood_string, utf16_length), "production String length");
_Static_assert(offsetof(struct iw_gc_string, utf8_length) == offsetof(struct ironwood_string, utf8_length), "production String UTF-8 length");

/* Qualification observes unmodified pinned collector diagnostics. This private
 * diagnostic flag is never used by the adapter or generated production code. */
extern int GC_print_stats;
static void **roots;
static const struct iw_gc_type *leaf;
static const struct iw_gc_layout leaf_layout = {1, IW_GC_FIXED, 1, 0, 16, 0, NULL};

static void initialize(size_t maximum) {
    assert(iw_gc_init(iw_gc_configuration(), maximum, 0));
    roots = calloc(64, sizeof(void *));
    assert(roots != NULL && iw_gc_roots(roots, 64));
    leaf = iw_gc_describe(&leaf_layout);
    assert(leaf != NULL);
}

static void collect(uint64_t expected, uint64_t sidecars) {
    assert(iw_gc_collect());
    struct iw_gc_snapshot state = iw_gc_snapshot();
    assert(state.disabled && state.live == expected && state.sidecars == sidecars);
    assert(state.administration == 6);
}

static void clear(void) {
    memset(roots, 0, 64 * sizeof(void *));
    collect(0, 0);
}

static void *new_object(const struct iw_gc_type *type, size_t length) {
    void *object = iw_gc_new(type, length);
    assert(object != NULL && (uintptr_t)object % _Alignof(void *) == 0);
    return object;
}

static void agreement(void) {
    for (size_t index = 0; index < sizeof(c_layouts) / sizeof(c_layouts[0]); ++index) {
        const struct iw_gc_layout *c = c_layouts[index], *ll = llvm_layouts[index];
        assert(c->version == ll->version && c->shape == ll->shape && c->language == ll->language);
        assert(c->element_kind == ll->element_kind && c->size == ll->size);
        assert(c->reference_count == ll->reference_count);
        for (size_t ref = 0; ref < c->reference_count; ++ref) assert(c->references[ref] == ll->references[ref]);
        const struct iw_gc_type *type = iw_gc_describe(ll);
        assert(type != NULL);
        if (c->shape != IW_GC_FIXED) continue;
        unsigned char *object = new_object(type, 0);
        roots[0] = object;
        for (size_t byte = sizeof(void *); byte < c->size; ++byte) assert(object[byte] == 0);
        collect(1, 0); // Incomplete object, before any constructor writes.
        if (c->reference_count != 0) {
            void *base_child = new_object(leaf, 0);
            memcpy(object + c->references[0], &base_child, sizeof(base_child));
            collect(2, 0); // Base initialization, derived slots still null.
        }
        void *numeric_target = new_object(leaf, 0);
        for (size_t offset = sizeof(void *); offset + sizeof(void *) <= c->size; offset += sizeof(void *)) {
            int reference = 0;
            for (size_t ref = 0; ref < c->reference_count; ++ref) reference |= c->references[ref] == offset;
            if (!reference) memcpy(object + offset, &numeric_target, sizeof(void *));
        }
        for (size_t ref = 1; ref < c->reference_count; ++ref) {
            void *child = new_object(leaf, 0);
            memcpy(object + c->references[ref], &child, sizeof(child));
        }
        collect(1 + c->reference_count, 0);
        assert(!GC_is_marked(numeric_target));
        for (size_t ref = 0; ref < c->reference_count; ++ref) {
            void *child;
            memcpy(&child, object + c->references[ref], sizeof(child));
            assert(GC_is_marked(child));
        }
        clear();
    }
    // One bounded kind handles arbitrarily many independently described types.
    for (size_t index = 0; index < 1024; ++index) {
        const struct iw_gc_type *type = iw_gc_describe(c_layouts[index % 7]);
        assert(type != NULL);
        roots[0] = new_object(type, 0);
        collect(1, 0);
    }
    clear();
    printf("{\"case\":\"compiler_layouts\",\"c_llvm_agreement\":%zu,\"types\":1024,\"described_kinds\":2,\"footer_bytes\":0}\n",
           sizeof(c_layouts) / sizeof(c_layouts[0]));
}

static void arrays(void) {
    uint64_t initial = iw_gc_snapshot().total;
    for (size_t kind = 1; kind <= 9; ++kind) {
        const struct iw_gc_type *type = iw_gc_describe(c_layouts[6 + kind]);
        assert(type != NULL);
        for (size_t variant = 0; variant < 3; ++variant) {
            size_t length = variant == 0 ? 0 : variant == 1 ? 7 : 131072;
            struct iw_gc_array *array = new_object(type, length);
            roots[0] = array;
            assert(array->length == length && array->element_kind == kind && array->reserved == 0);
            for (size_t byte = 0; byte < length * array->element_size; ++byte) assert(array->data[byte] == 0);
            void *numeric = new_object(leaf, 0);
            if (kind == 9) {
                if (length != 0) ((void **)array->data)[length - 1] = new_object(leaf, 0);
            } else {
                for (size_t offset = 0; offset + sizeof(void *) <= length * array->element_size; offset += sizeof(void *)) {
                    memcpy(array->data + offset, &numeric, sizeof(void *));
                }
            }
            collect(1 + (kind == 9 && length != 0), 0);
            assert(!GC_is_marked(numeric));
            assert(iw_gc_new(type, SIZE_MAX) == NULL);
            assert(iw_gc_new(type, (size_t)INT32_MAX + 1) == NULL);
            clear();
        }
    }
    const struct iw_gc_type *multi = iw_gc_describe(c_layouts[16]);
    const struct iw_gc_type *ints = iw_gc_describe(c_layouts[11]);
    struct iw_gc_array *outer = new_object(multi, 3);
    roots[0] = outer;
    ((void **)outer->data)[0] = new_object(ints, 0);
    ((void **)outer->data)[2] = new_object(ints, 10000);
    collect(3, 0);
    // Physical slots remain strong even when a containing collection size is 0.
    ((void **)outer->data)[0] = NULL;
    collect(2, 0);
    clear();
    const struct iw_gc_type *string = iw_gc_describe(c_layouts[17]);
    for (size_t variant = 0; variant < 3; ++variant) {
        size_t length = variant == 0 ? 0 : variant == 1 ? 13 : 131072;
        struct iw_gc_string *value = new_object(string, length);
        roots[0] = value;
        assert(value->length == (int32_t)length && value->utf8_length == 0);
        for (size_t unit = 0; unit < length; ++unit) assert(value->data[unit] == 0);
        void *numeric = new_object(leaf, 0);
        for (size_t offset = 0; offset + sizeof(void *) <= length * sizeof(uint16_t); offset += sizeof(void *)) {
            memcpy((unsigned char *)value->data + offset, &numeric, sizeof(void *));
        }
        collect(1, 0);
        assert(!GC_is_marked(numeric));
        clear();
    }
    assert(iw_gc_snapshot().total > initial);
    puts("{\"case\":\"arrays_strings\",\"primitive_kinds\":8,\"reference_kind\":true,\"multidimensional\":true,\"inline_string\":true}");
}

struct link { const struct iw_gc_type *type; void *object, *next; uintptr_t numeric; };
struct metadata { const struct iw_gc_type *type; void *primary, *first, *last, *trace; uint64_t state; };
static void sidecars(void) {
    static const size_t link_refs[] = {offsetof(struct link, object), offsetof(struct link, next)};
    static const size_t meta_refs[] = {offsetof(struct metadata, primary), offsetof(struct metadata, first),
                                      offsetof(struct metadata, last), offsetof(struct metadata, trace)};
    const struct iw_gc_layout link_layout = {1, IW_GC_FIXED, 0, 0, sizeof(struct link), 2, link_refs};
    const struct iw_gc_layout meta_layout = {1, IW_GC_FIXED, 0, 0, sizeof(struct metadata), 4, meta_refs};
    const struct iw_gc_layout trace_layout = {1, IW_GC_FIXED, 0, 0, 4096, 0, NULL};
    const struct iw_gc_type *link_type = iw_gc_describe(&link_layout);
    const struct iw_gc_type *meta_type = iw_gc_describe(&meta_layout);
    const struct iw_gc_type *trace_type = iw_gc_describe(&trace_layout);
    struct metadata *meta = new_object(meta_type, 0);
    roots[0] = meta;
    meta->primary = new_object(leaf, 0);
    struct link *link = new_object(link_type, 0);
    link->object = new_object(leaf, 0);
    link->next = new_object(link_type, 0);
    link->numeric = (uintptr_t)new_object(leaf, 0);
    meta->first = meta->last = link;
    meta->trace = new_object(trace_type, 0);
    ((void **)meta->trace)[1] = new_object(leaf, 0); // Trace/site bits are not managed edges.
    collect(2, 4);
    assert(!GC_is_marked((void *)link->numeric));
    uint64_t total = iw_gc_snapshot().total;
    new_object(leaf, 0); // Abandoned construction still counted until collection.
    assert(iw_gc_snapshot().live == 3 && iw_gc_snapshot().total == total + 1);
    collect(2, 4);
    clear();
    puts("{\"case\":\"sidecars\",\"language\":2,\"internal\":4,\"administration\":6,\"subsequent_allocation_formula\":true}");
}

static void metadata_failure(void) {
    assert(iw_gc_metadata_budget(4096));
    size_t successful = 0;
    while (iw_gc_describe(c_layouts[6]) != NULL) ++successful;
    assert(successful > 0 && successful < 10);
    uint64_t count = iw_gc_snapshot().total;
    assert(iw_gc_new(NULL, 0) == NULL && iw_gc_snapshot().total == count);
    assert(iw_gc_snapshot().collections == 0);
    assert(iw_gc_metadata_budget(SIZE_MAX));
    struct iw_gc_layout bad = *c_layouts[1];
    size_t offset = 1;
    bad.reference_count = 1; bad.references = &offset;
    assert(iw_gc_describe(&bad) == NULL);
    bad = *c_layouts[1]; bad.version = 99;
    assert(iw_gc_describe(&bad) == NULL);
    bad = *c_layouts[1]; bad.shape = 99;
    assert(iw_gc_describe(&bad) == NULL);
    bad = *c_layouts[1]; bad.reference_count = SIZE_MAX;
    assert(iw_gc_describe(&bad) == NULL);
    roots[0] = new_object(leaf, 0);
    collect(1, 0);
    clear();
    printf("{\"case\":\"metadata_exhaustion\",\"budget_bytes\":4096,\"large_descriptors_before_refusal\":%zu,\"approximation\":false}\n", successful);
}

static void allocation_failure(void) {
    struct iw_gc_snapshot before = iw_gc_snapshot();
    const struct iw_gc_type *arrays_type = iw_gc_describe(c_layouts[15]);
    const struct iw_gc_type *string_type = iw_gc_describe(c_layouts[17]);
    assert(iw_gc_new(arrays_type, INT32_MAX) == NULL);
    assert(iw_gc_new(string_type, INT32_MAX) == NULL);
    const struct iw_gc_layout huge_language = {1, IW_GC_FIXED, 1, 0, 512 * 1024 * 1024, 0, NULL};
    const struct iw_gc_layout huge_sidecar = {1, IW_GC_FIXED, 0, 0, 512 * 1024 * 1024, 0, NULL};
    const struct iw_gc_type *object = iw_gc_describe(&huge_language);
    const struct iw_gc_type *sidecar = iw_gc_describe(&huge_sidecar);
    assert(object != NULL && sidecar != NULL);
    assert(iw_gc_new(object, 0) == NULL && iw_gc_new(sidecar, 0) == NULL);
    assert(iw_gc_new(leaf, 1) == NULL);
    struct iw_gc_snapshot after = iw_gc_snapshot();
    assert(after.total == before.total && after.live == before.live);
    assert(after.collections == before.collections && after.disabled);
    roots[0] = new_object(leaf, 0);
    collect(1, 0);
    clear();
    puts("{\"case\":\"described_allocation_failure\",\"families\":4,\"automatic_collections\":0,\"failed_allocation_events\":0}");
}

static void overflow(void) {
    const size_t count = 65536;
    static const size_t offsets[] = {offsetof(struct link, object), offsetof(struct link, next)};
    const struct iw_gc_layout layout = {1, IW_GC_FIXED, 1, 0, sizeof(struct link), 2, offsets};
    const struct iw_gc_type *type = iw_gc_describe(&layout);
    const struct iw_gc_type *array_type = iw_gc_describe(c_layouts[15]);
    struct iw_gc_array *array = new_object(array_type, count);
    roots[0] = array;
    for (size_t index = 0; index < count; ++index) {
        struct link *item = new_object(type, 0);
        item->object = new_object(leaf, 0);
        item->numeric = (uintptr_t)new_object(leaf, 0);
        ((void **)array->data)[index] = item;
    }
    GC_set_log_fd(2);
    GC_print_stats = 1;
    collect(1 + 2 * count, 0); // Wide graph actually overflows, unlike a deep chain.
    GC_print_stats = 0;
    for (size_t index = 0; index < count; ++index) {
        struct link *item = ((void **)array->data)[index];
        assert(GC_is_marked(item) && GC_is_marked(item->object));
        assert(!GC_is_marked((void *)item->numeric));
    }
    clear();
    // Root an unused small cell to exercise indirect descriptor/free-list links.
    void *old = new_object(type, 0);
    collect(0, 0);
    roots[0] = old;
    collect(0, 0); // Free-list marks are removed before kind-filtered enumeration.
    clear();
    for (size_t index = 0; index < 10000; ++index) new_object(type, 0);
    collect(0, 0);
    puts("{\"case\":\"overflow_recovery\",\"children\":65536,\"exact_live\":131073,\"numeric_targets_reclaimed\":65536,\"free_list_cells_excluded\":true}");
}

/* Native-only construction scratch models reserve_file_result and final shrink.
 * No collector pointer is passed to realloc/free. Only successful final language
 * materialization counts; earlier failures allocated no language storage.
 */
static void *native_resize(void *scratch, size_t bytes, int fail) {
    return fail ? NULL : realloc(scratch, bytes);
}

static int reserve_native(unsigned char **scratch, size_t *capacity, size_t required, int fail) {
    if (required <= *capacity) return 1;
    size_t next = *capacity == 0 ? 2 : *capacity;
    while (next < required) {
        if (next > SIZE_MAX / 2) return 0;
        next *= 2;
    }
    void *grown = native_resize(*scratch, next, fail);
    if (grown == NULL) return 0; // Old allocation and capacity remain valid.
    *scratch = grown; *capacity = next;
    return 1;
}

static void file_result(void) {
    const struct iw_gc_type *bytes_type = iw_gc_describe(c_layouts[8]);
    const struct iw_gc_type *string_type = iw_gc_describe(c_layouts[17]);
    for (unsigned failure = 0; failure < 7; ++failure) {
        uint64_t before = iw_gc_snapshot().total;
        int descriptors[2];
        assert(pipe(descriptors) == 0);
        size_t expected_length = failure == 6 ? 0 : 3;
        assert(write(descriptors[1], "abc", expected_length) == (ssize_t)expected_length);
        assert(close(descriptors[1]) == 0);
        unsigned char *scratch = NULL;
        size_t length = 0, capacity = 0;
        int failed = 0;
        while (!failed) {
            unsigned char chunk[2];
            ssize_t amount = read(descriptors[0], chunk, sizeof(chunk));
            if (amount <= 0) break;
            if (!reserve_native(&scratch, &capacity, length + (size_t)amount,
                                failure == 1 || (failure == 2 && capacity != 0))) {
                failed = 1; break;
            }
            memcpy(scratch + length, chunk, (size_t)amount); length += (size_t)amount;
        }
        int descriptor = descriptors[0];
        assert(close(descriptor) == 0);
        assert(fcntl(descriptor, F_GETFD) == -1);
        if (failure == 3) failed = 1; // Read/decode/close failure, before materialization.
        if (!failed) {
            void *compact = length == 0 ? NULL : native_resize(scratch, length, failure == 5);
            if (compact != NULL) scratch = compact;
            if (failure == 5) assert(compact == NULL && capacity == 4);
            struct iw_gc_array *result = new_object(bytes_type, length);
            roots[0] = result;
            if (length != 0) memcpy(result->data, scratch, length);
            assert(length == expected_length && (length == 0 || memcmp(result->data, "abc", length) == 0));
            // A failure after language allocation must retain the event count.
            if (failure == 4) roots[0] = NULL;
            collect(failure == 4 ? 0 : 1, 0);
        }
        free(scratch);
        assert(iw_gc_snapshot().total == before + (failed ? 0 : 1));
        clear();
    }
    unsigned char *overflow_scratch = NULL;
    size_t overflow_capacity = 0;
    assert(!reserve_native(&overflow_scratch, &overflow_capacity, SIZE_MAX, 0));
    assert(overflow_scratch == NULL && overflow_capacity == 0);
    uint16_t *scratch = malloc(32 * sizeof(uint16_t));
    assert(scratch != NULL);
    scratch[0] = 'A'; scratch[1] = 0xD83D; scratch[2] = 0xDE00;
    void *compact = native_resize(scratch, 3 * sizeof(uint16_t), 1);
    assert(compact == NULL && scratch[2] == 0xDE00);
    struct iw_gc_string *text = new_object(string_type, 3);
    roots[0] = text;
    memcpy(text->data, scratch, 3 * sizeof(uint16_t)); text->utf8_length = 5;
    free(scratch);
    collect(1, 0);
    assert(text->data[2] == 0xDE00 && text->utf8_length == 5);
    clear();
    puts("{\"case\":\"native_construction\",\"success_language_events\":1,\"pre_materialization_failure_events\":0,\"post_materialization_failure_events\":1,\"descriptor_cleanup\":true}");
}

static void resident(void) {
    const struct iw_gc_type *type = iw_gc_describe(c_layouts[12]); // long[]
    const size_t bytes = 128 * 1024 * 1024;
    struct iw_gc_array *array = new_object(type, bytes / sizeof(uint64_t));
    roots[0] = array;
    uint64_t *values = (uint64_t *)array->data;
    for (size_t index = 0; index < bytes / sizeof(uint64_t); ++index) assert(values[index] == 0);
    memset(array->data, 0x5A, bytes); // Deliberately resident, separate from span test.
    collect(1, 0);
    assert(values[0] == UINT64_C(0x5A5A5A5A5A5A5A5A));
    clear();
    printf("{\"case\":\"resident_payload\",\"bytes_written\":%zu,\"zeroed\":true}\n", bytes);
}

static void virtual_span(void) {
    const size_t bytes = (size_t)5 * 1024 * 1024 * 1024;
    // Engine address-span control only. Atomic storage is not a zeroed language
    // allocation and is excluded from language counts. Touch just two pages.
    unsigned char *value = GC_malloc_atomic(bytes);
    assert(value != NULL); roots[0] = value;
    value[0] = 17; value[bytes - 1] = 29;
    assert(iw_gc_collect());
    assert(value[0] == 17 && value[bytes - 1] == 29 && iw_gc_snapshot().live == 0);
    memset(roots, 0, 64 * sizeof(void *));
    assert(iw_gc_collect() && iw_gc_snapshot().free_bytes >= bytes);
    printf("{\"case\":\"virtual_span\",\"bytes\":%zu,\"payload_pages_touched\":2,\"language_capacity_claim\":false}\n", bytes);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    initialize(strcmp(argv[1], "virtual") == 0 ? (size_t)8 * 1024 * 1024 * 1024 : 256 * 1024 * 1024);
    if (strcmp(argv[1], "layouts") == 0) agreement();
    else if (strcmp(argv[1], "arrays") == 0) arrays();
    else if (strcmp(argv[1], "sidecars") == 0) sidecars();
    else if (strcmp(argv[1], "metadata") == 0) metadata_failure();
    else if (strcmp(argv[1], "failure") == 0) allocation_failure();
    else if (strcmp(argv[1], "overflow") == 0) overflow();
    else if (strcmp(argv[1], "construction") == 0) file_result();
    else if (strcmp(argv[1], "resident") == 0) resident();
    else if (strcmp(argv[1], "virtual") == 0) virtual_span();
    else abort();
    assert(iw_gc_roots(NULL, 0)); free(roots);
    return 0;
}

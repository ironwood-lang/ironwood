// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_ENGINE_H
#define IRONWOOD_GC_ENGINE_H

#include <stddef.h>
#include <stdint.h>

/* GC1/GC2 qualification only. Not part of the production runtime ABI.
 * Single caller or external serialization, just like the future image contract.
 * Only allocation bases in explicit native root slots retain objects. Never
 * pass managed storage to libc free/realloc or exchange it with another image.
 * There is deliberately no managed deallocate/reallocate or callback API.
 */
enum iw_gc_fixture_kind {
    IW_GC_LEAF,        /* pointer-free language allocation */
    IW_GC_PAIR,        /* first two words are references */
    IW_GC_SIDECAR,     /* first word is a reference, excluded from language counts */
    IW_GC_TRACE        /* pointer-free internal storage */
};

/* Version 1 experimental layout ABI, distinct from production typeinfo.
 * Offsets are compiler/C ABI expressions, never source-declaration guesses.
 * Type metadata is immutable native storage retained for the image lifetime.
 */
enum iw_gc_shape { IW_GC_FIXED, IW_GC_ARRAY, IW_GC_STRING };
struct iw_gc_layout {
    uint32_t version, shape, language, element_kind;
    size_t size, reference_count;
    const size_t *references;
};
struct iw_gc_type;
struct iw_gc_array {
    const struct iw_gc_type *type;
    size_t length, element_size;
    uint32_t element_kind, reserved;
    unsigned char data[];
};
struct iw_gc_string {
    const struct iw_gc_type *type;
    int32_t length, utf8_length;
    uint16_t data[];
};
/* Cold setup budget: exhaustion refuses a descriptor, never approximates it.
 * Call before registration. Previously described types remain valid.
 */
int iw_gc_metadata_budget(size_t bytes);
const struct iw_gc_type *iw_gc_describe(const struct iw_gc_layout *layout);
void *iw_gc_new(const struct iw_gc_type *type, size_t length);

struct iw_gc_snapshot {
    uint64_t collections, total, live, sidecars, administration;
    size_t heap_bytes, free_bytes, reclaimed_since_collection;
    int disabled;
};

/* A failed identity/environment check does not initialize the engine.
 * GC_* environment overrides are refused, rather than changing this profile.
 */
int iw_gc_init(const char *configuration_sha256, size_t max_heap, unsigned retries);
const char *iw_gc_configuration(void);
void *iw_gc_allocate(enum iw_gc_fixture_kind kind, size_t bytes);

/* Scratch is caller-owned native storage, remains valid until replaced/removed,
 * and contains only allocation bases or null. No collector heap scratch allowed.
 * Remove roots with (NULL, 0) before freeing the native scratch.
 */
int iw_gc_roots(void **scratch, size_t count);
int iw_gc_collect(void);
struct iw_gc_snapshot iw_gc_snapshot(void);

#endif

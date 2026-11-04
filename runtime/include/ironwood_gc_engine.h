// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_ENGINE_H
#define IRONWOOD_GC_ENGINE_H

#include <stddef.h>
#include <stdint.h>

/* GC1 qualification only. Not part of the production runtime ABI.
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

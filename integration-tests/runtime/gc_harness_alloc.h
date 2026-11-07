// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_HARNESS_ALLOC_H
#define IRONWOOD_GC_HARNESS_ALLOC_H

/* Include before the instrumented runtime, after system headers. These wrappers
 * affect only that C translation unit, never the verified collector SDK. */
#include "gc.h"
#include "gc_mark.h"
#include "ironwood_gc_engine.h"
#include "ironwood_gc_configuration.h"

static uint64_t gc_language_attempts, gc_sidecar_attempts;
static int gc_fail_language;
static uint64_t gc_fail_sidecar_at;

static void *gc_harness_language_allocate(size_t bytes, int kind) {
    gc_language_attempts++;
    return gc_fail_language ? NULL : GC_generic_malloc(bytes, kind);
}

static void *gc_harness_sidecar_allocate(const struct iw_gc_type *type, size_t bytes) {
    gc_sidecar_attempts++;
    return gc_sidecar_attempts == gc_fail_sidecar_at ? NULL : iw_gc_new_storage(type, bytes);
}

#define GC_generic_malloc gc_harness_language_allocate
#define iw_gc_new_storage gc_harness_sidecar_allocate
#endif

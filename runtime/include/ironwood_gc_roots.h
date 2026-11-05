// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_ROOTS_H
#define IRONWOOD_GC_ROOTS_H

#include <stddef.h>
#include <stdint.h>

/* Development closure ABI, separate from the unchanged GC2 engine-2 SDK.
 * Immutable image metadata plus explicitly admitted native static base slots.
 * The compiler exports iw_gc_static_root_count/iw_gc_static_roots and the fixed
 * native component exports iw_gc_native_root_count/iw_gc_native_roots. Config
 * must match both immutable inventories exactly, including empty inventories.
 * The compiler also exports the optimized trace function catalog and emits a
 * root-contract.c map digest. Startup binds relocated map functions to those
 * identities and verifies all map bytes before interpreting root locations.
 * Version 1 retains its zero-owner gates. Version 2 requires the compiler's
 * exact owner coverage and an immutable runtime-1 protocol. This admits native
 * anchors, unwind wrappers and bounded emergency scopes, never foreign segments
 * or unwinding through JVM frames. Enumeration runs with collection disabled.
 */
enum { IW_GC_OWNER_UNWIND = 1, IW_GC_OWNER_EMERGENCY = 2, IW_GC_OWNER_ANCHOR = 4 };
struct iw_gc_owner_protocol {
    uint32_t version, coverage, foreign_segments, reserved;
    int (*enumerate)(int (*visit)(uintptr_t));
    void *const *immortal_bases;
    size_t immortal_count;
};
struct iw_gc_root_config {
    uint32_t version, pointer_bytes, little_endian, stackmap_version;
    void (*native_boundary)(void);
    void *const *static_slots;
    size_t static_count;
    void *const *native_slots;
    size_t native_count, scratch_capacity;
    uint32_t exception_owners, foreign_owners;
    const struct iw_gc_owner_protocol *owners;
};

int iw_gc_roots_start(const struct iw_gc_root_config *config);
void iw_gc_roots_stop(void);
/* Failure leaves collection disabled, performs no collection, and clears scratch.
 * Called directly by compiler statepoints, never through an unqualified shim.
 */
void iw_gc_experiment_collect(void);
size_t iw_gc_roots_last_count(void);
size_t iw_gc_roots_last_frames(void);
size_t iw_gc_roots_max_frames(void);

#endif

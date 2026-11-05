// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_ROOTS_H
#define IRONWOOD_GC_ROOTS_H

#include <stddef.h>
#include <stdint.h>

/* Development closure ABI, separate from the unchanged GC2 engine-2 SDK.
 * Immutable image metadata plus explicitly admitted native static base slots.
 * No foreign/exception owner callback can be installed through this interface.
 * The compiler exports iw_gc_static_root_count/iw_gc_static_roots and the fixed
 * native component exports iw_gc_native_root_count/iw_gc_native_roots. Config
 * must match both immutable inventories exactly, including empty inventories.
 * The compiler also exports the optimized trace function catalog and emits a
 * root-contract.c map digest. Startup binds relocated map functions to those
 * identities and verifies all map bytes before interpreting root locations.
 * exception_owners/foreign_owners are reserved extension gates, currently zero;
 * later lifetime/segment protocols need qualification before either is admitted.
 */
struct iw_gc_root_config {
    uint32_t version, pointer_bytes, little_endian, stackmap_version;
    void (*native_boundary)(void);
    void *const *static_slots;
    size_t static_count;
    void *const *native_slots;
    size_t native_count, scratch_capacity;
    uint32_t exception_owners, foreign_owners;
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

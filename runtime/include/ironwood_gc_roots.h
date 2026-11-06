// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_ROOTS_H
#define IRONWOOD_GC_ROOTS_H

#include <stddef.h>
#include <stdint.h>

/* Exact image root ABI. Normal images require ABI 2 and the engine-3 SDK.
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
 * or unwinding through JVM frames. Version 3 requires owner protocol 2 and
 * complete native slots/suspended segments. No version walks JVM frames.
 * Enumeration runs with collection disabled.
 */
enum { IW_GC_OWNER_UNWIND = 1, IW_GC_OWNER_EMERGENCY = 2, IW_GC_OWNER_ANCHOR = 4,
       IW_GC_OWNER_SEGMENT = 8 };
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
/* Cold runtime-1 setup only. The image-owned failure continuation must throw or
 * terminate. It runs after failed preparation has cleared/unregistered scratch,
 * with GC disabled and no reclamation. Invalid metadata never selects it.
 * ABI-1 callers retain fatal preparation refusal. No user cleanup is registered.
 */
int iw_gc_roots_set_preparation_oom(void (*raise)(void));
/* Failure leaves collection disabled, performs no collection, and clears scratch.
 * Called directly by compiler statepoints, never through an unqualified shim.
 */
void iw_gc_experiment_collect(void);
size_t iw_gc_roots_last_count(void);
size_t iw_gc_roots_last_frames(void);
size_t iw_gc_roots_max_frames(void);

/* ABI 3 / owner protocol 2. Caller-owned records live through one protected
 * native invocation. Only native entry/Java transition boundaries use these.
 * Suspended records save machine frame state, not conservative words or roots.
 * Root values are read from exact maps during collection. Failed enter/suspend
 * must prevent managed/Java execution respectively. External serialization,
 * without thread checks or TLS. Zero initialize the record before entry.
 */
struct iw_gc_saved_frame;
struct iw_gc_segment {
    struct iw_gc_segment *previous;
    struct iw_gc_saved_frame *frames;
    size_t count, capacity;
    uintptr_t boundary_sp, boundary_frame_sp;
    uint64_t state_hash;
    unsigned state;
};
/* Always inlined into the compiler-owned dispatcher. This is the machine stack
 * value reconstructed for that caller by the qualified platform unwinder.
 * arm64 uses SP. x86-64 forces and uses the dispatcher's RBP, independent of
 * temporary outgoing stack arguments; capture also saves its actual SP. */
__attribute__((always_inline)) static inline uintptr_t iw_gc_stack_boundary(void) {
#if defined(__APPLE__) && defined(__aarch64__) && !defined(__arm64e__)
    uintptr_t sp;
    __asm__ volatile("mov %0, sp" : "=r"(sp));
    return sp;
#elif defined(__linux__) && defined(__x86_64__)
    return (uintptr_t)__builtin_frame_address(0);
#else
    return 0;
#endif
}
int iw_gc_segment_enter(struct iw_gc_segment *segment, uintptr_t boundary_sp);
int iw_gc_segment_suspend(struct iw_gc_segment *segment, void (*transition)(void));
int iw_gc_segment_resume(struct iw_gc_segment *segment);
int iw_gc_segment_leave(struct iw_gc_segment *segment);
struct iw_gc_segment *iw_gc_segment_current(void);

/* Exact native slot addresses. Storage and inventory remain valid until close;
 * values may change through the actual typed C/IR fields without shadow writes.
 * Use at invocation/export/release boundaries only. ABI 1/2 reject registration.
 * Neither collection nor closing a root runs cleanup or releases JNI resources.
 */
struct iw_gc_foreign_root {
    struct iw_gc_foreign_root *previous, *next;
    void *const *slots;
    size_t count;
    unsigned active;
};
int iw_gc_foreign_root_open(struct iw_gc_foreign_root *root, void *const *slots, size_t count);
void iw_gc_foreign_root_close(struct iw_gc_foreign_root *root);
size_t iw_gc_foreign_root_count(void);
enum { IW_GC_SEGMENT_OK, IW_GC_SEGMENT_INVALID, IW_GC_SEGMENT_OOM };
int iw_gc_segment_failure(void);

#endif

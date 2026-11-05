// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_GC_LIFETIMES_H
#define IRONWOOD_GC_LIFETIMES_H

#include "ironwood_gc_engine.h"
#include "ironwood_gc_roots.h"

/* Experimental runtime-1, not a production or generated bridge ABI.
 * Single caller/external serialization. Native records never live in GC memory.
 * Callers own record storage until release; no callbacks run during enumeration.
 */
struct iw_gc_native_root {
    struct iw_gc_native_root *previous, *next;
    void *value;
    unsigned active;
};
int iw_gc_native_root_open(struct iw_gc_native_root *root, void *value);
int iw_gc_native_root_replace(struct iw_gc_native_root *root, void *value);
void iw_gc_native_root_close(struct iw_gc_native_root *root);
size_t iw_gc_active_native_roots(void);
extern const struct iw_gc_owner_protocol iw_gc_lifetime_owners;
extern void *const iw_gc_immortal_bases[1];

struct iw_gc_exception_metadata;
/* Matches the GC4 compiler projection of Throwable, including inherited fields.
 * metadata is a reference, never a tagged integer. Trace status lives inside it.
 */
struct iw_gc_throwable {
    const struct iw_gc_type *type;
    void *message;
    struct iw_gc_throwable *cause;
    struct iw_gc_exception_metadata *metadata;
    void *owned_message;
    _Bool cause_initialized;
};
/* Invocation-bound foreign carriers use exact next links. native_cookie is an
 * opaque resource identity, never traced or released by the collector. GC6 owns
 * generated API/JNI adoption, retirement and suspended foreign segments.
 */
struct iw_gc_carrier {
    const struct iw_gc_type *type;
    struct iw_gc_throwable *failure;
    struct iw_gc_carrier *next;
    uintptr_t native_cookie;
};
int iw_gc_lifetimes_start(void);
int iw_gc_lifetimes_stop(void);
const struct iw_gc_type *iw_gc_carrier_type(void);
/* Deterministic development failure injection. SIZE_MAX restores normal work.
 * Counts internal/native acquisitions, never language allocations.
 */
void iw_gc_lifetimes_budget(size_t successes);
void iw_gc_trace_capture(struct iw_gc_throwable *object);
size_t iw_gc_trace_count(const struct iw_gc_throwable *object);
int iw_gc_add_secondary(struct iw_gc_throwable *primary, struct iw_gc_throwable *secondary);
void iw_gc_add_secondary_required(struct iw_gc_throwable *primary, struct iw_gc_throwable *secondary);
size_t iw_gc_secondary_count(const struct iw_gc_throwable *object);
struct iw_gc_throwable *iw_gc_secondary_at(const struct iw_gc_throwable *object, size_t index);
_Noreturn void iw_gc_throw(struct iw_gc_throwable *object);
struct iw_gc_throwable *iw_gc_exception_take(void *unwind);
void iw_gc_exception_caught(struct iw_gc_throwable *object);
/* Emergency ownership is explicit and allocation-free. End after the associated
 * handling completes, never while a wrapper or cleanup still owns the failure.
 * The OOM object is a managed fixture base, rooted by this scope even on failure.
 */
int iw_gc_emergency_begin(struct iw_gc_throwable *oom);
struct iw_gc_throwable *iw_gc_oom_object(const struct iw_gc_type *type);
void iw_gc_emergency_end(void);
size_t iw_gc_unwind_wrapper_count(void);

/* Fixed caller-provided transaction storage. Reserve a slot before acquisition;
 * arm it immediately on success. Commit transfers native cleanup authority.
 * Releases are native, nonthrowing, noncollecting and allocation-free. They are
 * never source destructors, finalizers, user close methods or GC callbacks.
 */
struct iw_gc_acquisition { uintptr_t handle; void (*release)(uintptr_t); };
struct iw_gc_acquisitions { struct iw_gc_acquisition *slots; size_t count, capacity; };
struct iw_gc_acquisition *iw_gc_acquisition_reserve(struct iw_gc_acquisitions *owner);
void iw_gc_acquisition_commit(struct iw_gc_acquisition *slot);
void iw_gc_acquisitions_abort(struct iw_gc_acquisitions *owner);

#endif

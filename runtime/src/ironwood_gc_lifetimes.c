// SPDX-License-Identifier: MIT OR Apache-2.0
/* Runtime-only experimental ownership. No production allocator interposition. */
#include "ironwood_gc_lifetimes.h"
#include "gc.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unwind.h>

struct secondary {
    const struct iw_gc_type *type;
    struct iw_gc_throwable *object;
    struct secondary *next;
};
struct trace {
    const struct iw_gc_type *type;
    size_t count;
    uintptr_t pcs[96];
};
struct iw_gc_exception_metadata {
    const struct iw_gc_type *type;
    struct iw_gc_throwable *primary;
    struct secondary *first, *last;
    struct trace *trace;
    size_t secondary_count;
    unsigned trace_state;
};
struct wrapper {
    struct iw_gc_native_root root;
    unsigned emergency;
    struct _Unwind_Exception unwind;
};
static const struct iw_gc_type *metadata_type, *secondary_type, *trace_type, *carrier_type;
static struct iw_gc_native_root *roots;
static size_t root_count, wrappers, budget = SIZE_MAX;
static struct iw_gc_native_root emergency_root;
static struct wrapper emergency_wrapper;
static unsigned emergency_wrapper_active;
/* Emergency links are in native storage and explicitly enumerated. Only the
 * active emergency scope can own them; they never enter managed descriptors.
 * No immortal object points into a subsequently inactive emergency graph.
 */
static struct iw_gc_throwable *emergency_oom;
static struct iw_gc_throwable immortal_oom;
void *const iw_gc_immortal_bases[1] = {&immortal_oom};
static struct iw_gc_throwable *emergency_primary, *emergency_entries[64];
static size_t emergency_count;

static int permitted(void) {
    if (budget == SIZE_MAX) return 1;
    if (!budget) return 0;
    --budget;
    return 1;
}
void iw_gc_lifetimes_budget(size_t successes) { budget = successes; }
static int base(void *value) { return value == NULL || value == &immortal_oom || GC_base(value) == value; }

int iw_gc_native_root_open(struct iw_gc_native_root *root, void *value) {
    if (root == NULL || root->active || !base(value)) return 0;
    root->previous = NULL;
    root->next = roots;
    root->value = value;
    if (roots != NULL) roots->previous = root;
    roots = root;
    root->active = 1;
    ++root_count;
    return 1;
}
int iw_gc_native_root_replace(struct iw_gc_native_root *root, void *value) {
    if (root == NULL || !root->active || !base(value)) return 0;
    root->value = value;
    return 1;
}
void iw_gc_native_root_close(struct iw_gc_native_root *root) {
    if (root == NULL || !root->active) return;
    if (root->previous != NULL) root->previous->next = root->next;
    else roots = root->next;
    if (root->next != NULL) root->next->previous = root->previous;
    memset(root, 0, sizeof(*root));
    --root_count;
}
size_t iw_gc_active_native_roots(void) { return root_count; }
static int enumerate(int (*visit)(uintptr_t)) {
    size_t count = 0;
    for (struct iw_gc_native_root *root = roots; root != NULL; root = root->next) {
        if (++count > root_count || !root->active || !visit((uintptr_t)root->value)) return 0;
    }
    if (count != root_count || !visit((uintptr_t)emergency_primary)) return 0;
    for (size_t i = 0; i < emergency_count; ++i) if (!visit((uintptr_t)emergency_entries[i])) return 0;
    return visit((uintptr_t)immortal_oom.message) && visit((uintptr_t)immortal_oom.cause)
            && visit((uintptr_t)immortal_oom.metadata) && visit((uintptr_t)immortal_oom.owned_message);
}
const struct iw_gc_owner_protocol iw_gc_lifetime_owners = {
    1, IW_GC_OWNER_UNWIND | IW_GC_OWNER_EMERGENCY | IW_GC_OWNER_ANCHOR, 0, 0, enumerate, iw_gc_immortal_bases, 1
};

static const struct iw_gc_type *describe(size_t size, size_t count, const size_t *offsets) {
    struct iw_gc_layout layout = {1, IW_GC_FIXED, 0, 0, size, count, offsets};
    return iw_gc_describe(&layout);
}
int iw_gc_lifetimes_start(void) {
    if (metadata_type != NULL) return 0;
    static const size_t metadata_offsets[] = {
        offsetof(struct iw_gc_exception_metadata, primary), offsetof(struct iw_gc_exception_metadata, first),
        offsetof(struct iw_gc_exception_metadata, last), offsetof(struct iw_gc_exception_metadata, trace)
    };
    static const size_t secondary_offsets[] = {offsetof(struct secondary, object), offsetof(struct secondary, next)};
    static const size_t carrier_offsets[] = {offsetof(struct iw_gc_carrier, failure), offsetof(struct iw_gc_carrier, next)};
    metadata_type = describe(sizeof(struct iw_gc_exception_metadata), 4, metadata_offsets);
    secondary_type = describe(sizeof(struct secondary), 2, secondary_offsets);
    trace_type = describe(sizeof(struct trace), 0, NULL);
    carrier_type = describe(sizeof(struct iw_gc_carrier), 2, carrier_offsets);
    return metadata_type && secondary_type && trace_type && carrier_type;
}
int iw_gc_lifetimes_stop(void) {
    /* Descriptors are image-lifetime engine metadata, not managed root tables. */
    return roots == NULL && wrappers == 0 && emergency_oom == NULL;
}
const struct iw_gc_type *iw_gc_carrier_type(void) { return carrier_type; }
static struct iw_gc_exception_metadata *metadata(struct iw_gc_throwable *object) {
    if (object->metadata == NULL) {
        struct iw_gc_exception_metadata *result = permitted() ? iw_gc_new(metadata_type, 0) : NULL;
        if (result == NULL) return NULL;
        result->primary = object;
        object->metadata = result;
    }
    return object->metadata;
}
static _Unwind_Reason_Code trace_pc(struct _Unwind_Context *context, void *data) {
    struct trace *trace = data;
    if (trace->count == sizeof(trace->pcs) / sizeof(trace->pcs[0])) return _URC_END_OF_STACK;
    trace->pcs[trace->count++] = (uintptr_t)_Unwind_GetIP(context);
    return _URC_NO_REASON;
}
void iw_gc_trace_capture(struct iw_gc_throwable *object) {
    struct iw_gc_exception_metadata *state = metadata(object);
    if (state == NULL) return; /* Best-effort trace cannot replace the failure. */
    state->trace = NULL;
    state->trace_state = 2;
    struct trace *trace = permitted() ? iw_gc_new(trace_type, 0) : NULL;
    if (trace == NULL) return;
    (void)_Unwind_Backtrace(trace_pc, trace);
    state->trace = trace;
    state->trace_state = 1;
}
size_t iw_gc_trace_count(const struct iw_gc_throwable *object) {
    return object->metadata == NULL || object->metadata->trace == NULL ? 0 : object->metadata->trace->count;
}
static int append(struct iw_gc_exception_metadata *state, struct iw_gc_throwable *object) {
    if (state->secondary_count == INT32_MAX) return 0;
    struct secondary *entry = permitted() ? iw_gc_new(secondary_type, 0) : NULL;
    if (entry == NULL) return 0;
    entry->object = object;
    if (state->last == NULL) state->first = entry;
    else state->last->next = entry;
    state->last = entry;
    ++state->secondary_count;
    return 1;
}
int iw_gc_add_secondary(struct iw_gc_throwable *primary, struct iw_gc_throwable *secondary) {
    if (primary == NULL || secondary == NULL) abort();
    if (primary == secondary) return 1;
    struct iw_gc_exception_metadata *state = metadata(primary);
    struct secondary *old_last = state == NULL ? NULL : state->last;
    size_t old_count = state == NULL ? 0 : state->secondary_count;
    /* Snapshot the old length: cycles and shared tails cannot extend this walk
     * indefinitely as entries are appended. Preserve flat occurrence order.
     */
    struct iw_gc_exception_metadata *nested = secondary->metadata;
    size_t count = nested == NULL ? 0 : nested->secondary_count;
    struct secondary *entry = nested == NULL ? NULL : nested->first;
    if (state == NULL || !append(state, secondary)) goto emergency;
    for (size_t i = 0; i < count; ++i, entry = entry->next) {
        if (entry->object != primary && !append(state, entry->object)) goto emergency;
    }
    return 1;
emergency:
    // Failed internal construction abandons only newly allocated sidecars.
    // Restore the existing sequence and use bounded, allocation-free storage.
    if (state != NULL) {
        state->last = old_last;
        state->secondary_count = old_count;
        if (old_last != NULL) old_last->next = NULL;
        else state->first = NULL;
    }
    if (emergency_oom == NULL || (emergency_primary != NULL && emergency_primary != primary)
            || count >= 64 - emergency_count) return 0;
    emergency_primary = primary;
    emergency_entries[emergency_count++] = secondary;
    entry = nested == NULL ? NULL : nested->first;
    for (size_t i = 0; i < count; ++i, entry = entry->next) {
        if (entry->object != primary) emergency_entries[emergency_count++] = entry->object;
    }
    return 1;
}
void iw_gc_add_secondary_required(struct iw_gc_throwable *primary, struct iw_gc_throwable *secondary) {
    if (!iw_gc_add_secondary(primary, secondary)) abort(); /* Bounded association exhaustion. */
}
size_t iw_gc_secondary_count(const struct iw_gc_throwable *object) {
    return (object->metadata == NULL ? 0 : object->metadata->secondary_count) + (emergency_primary == object ? emergency_count : 0);
}
struct iw_gc_throwable *iw_gc_secondary_at(const struct iw_gc_throwable *object, size_t index) {
    size_t count = object->metadata == NULL ? 0 : object->metadata->secondary_count;
    if (index >= count && emergency_primary == object && index - count < emergency_count) return emergency_entries[index - count];
    if (index >= count) abort();
    struct secondary *entry = object->metadata->first;
    while (index--) entry = entry->next;
    return entry->object;
}
static void cleanup(_Unwind_Reason_Code reason, struct _Unwind_Exception *unwind) {
    (void)reason;
    struct wrapper *wrapper = (struct wrapper *)((char *)unwind - offsetof(struct wrapper, unwind));
    iw_gc_native_root_close(&wrapper->root);
    --wrappers;
    if (wrapper->emergency) {
        memset(wrapper, 0, sizeof(*wrapper));
        emergency_wrapper_active = 0;
    } else free(wrapper);
}
_Noreturn void iw_gc_throw(struct iw_gc_throwable *object) {
    if (object == NULL) abort();
    struct wrapper *wrapper = permitted() ? calloc(1, sizeof(*wrapper)) : NULL;
    if (wrapper == NULL) {
        if (emergency_wrapper_active) abort(); /* Bounded recursive delivery. */
        wrapper = &emergency_wrapper;
        wrapper->emergency = 1;
        emergency_wrapper_active = 1;
    }
    if (!iw_gc_native_root_open(&wrapper->root, object)) abort();
    ++wrappers;
    wrapper->unwind.exception_class = UINT64_C(0x49524f4e574f4f44);
    wrapper->unwind.exception_cleanup = cleanup;
    (void)_Unwind_RaiseException(&wrapper->unwind);
    _Unwind_DeleteException(&wrapper->unwind);
    abort();
}
struct iw_gc_throwable *iw_gc_exception_take(void *handle) {
    struct _Unwind_Exception *unwind = handle;
    if (unwind->exception_class != UINT64_C(0x49524f4e574f4f44)) abort();
    struct wrapper *wrapper = (struct wrapper *)((char *)unwind - offsetof(struct wrapper, unwind));
    struct iw_gc_throwable *object = wrapper->root.value;
    _Unwind_DeleteException(unwind);
    return object; /* Noncollecting transfer to compiler-described SSA storage. */
}
void iw_gc_exception_caught(struct iw_gc_throwable *object) { (void)object; }
size_t iw_gc_unwind_wrapper_count(void) { return wrappers; }
struct iw_gc_throwable *iw_gc_oom_object(const struct iw_gc_type *type) {
    if (immortal_oom.type != NULL && immortal_oom.type != type) abort();
    immortal_oom.type = type;
    return &immortal_oom;
}
int iw_gc_emergency_begin(struct iw_gc_throwable *oom) {
    if (emergency_oom != NULL || oom == NULL || !iw_gc_native_root_open(&emergency_root, oom)) return 0;
    emergency_oom = oom;
    return 1;
}
void iw_gc_emergency_end(void) {
    if (wrappers) abort();
    emergency_primary = NULL;
    memset(emergency_entries, 0, sizeof(emergency_entries));
    emergency_count = 0;
    const struct iw_gc_type *type = immortal_oom.type;
    memset(&immortal_oom, 0, sizeof(immortal_oom));
    immortal_oom.type = type;
    emergency_oom = NULL;
    iw_gc_native_root_close(&emergency_root);
}
struct iw_gc_acquisition *iw_gc_acquisition_reserve(struct iw_gc_acquisitions *owner) {
    if (owner->count == owner->capacity) return NULL;
    struct iw_gc_acquisition *slot = &owner->slots[owner->count++];
    *slot = (struct iw_gc_acquisition){0};
    return slot;
}
void iw_gc_acquisition_commit(struct iw_gc_acquisition *slot) { *slot = (struct iw_gc_acquisition){0}; }
void iw_gc_acquisitions_abort(struct iw_gc_acquisitions *owner) {
    while (owner->count) {
        struct iw_gc_acquisition *slot = &owner->slots[--owner->count];
        if (slot->release != NULL) slot->release(slot->handle);
        *slot = (struct iw_gc_acquisition){0};
    }
}

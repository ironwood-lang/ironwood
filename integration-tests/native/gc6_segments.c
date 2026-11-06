// SPDX-License-Identifier: MIT OR Apache-2.0
/* Actual JVM reentry through typed statepoint functions and the production root
 * reader. This stage qualifies segment reconstruction, not public facade APIs. */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "ironwood_gc_lifetimes.h"
#include "layouts.h"

#ifndef IW_ENTRY
#define IW_ENTRY Java_Gc6Segments_invoke
#endif

extern int64_t gc6_run(int64_t, int64_t, int64_t);
extern int64_t gc6_derived(int64_t, int64_t, int64_t);
extern int64_t gc6_phase(int64_t);
extern int64_t gc6_scalar(int64_t);
extern int64_t gc6_borrow(int64_t, int64_t, int64_t, void *);
extern int64_t gc6_transition(int64_t, int64_t);
extern const uint64_t iw_gc_static_root_count;
extern void *const iw_gc_static_roots[];
const uint64_t iw_gc_native_root_count = 0;
void *const iw_gc_native_roots[] = {NULL};
const struct iw_gc_type *iw_gc_types[IW_GC_TYPE_COUNT];
static jclass driver;
static jmethodID reenter;
static int initialized;
struct node { const struct iw_gc_type *type; struct node *link; int64_t payload; };
static int64_t allocation_seed, allocation_index;
static struct node *anchored;
static struct iw_gc_foreign_root anchor;
static void *const anchor_slots[] = {&anchored};
struct invocation { JNIEnv *env; int mode; };

static _Noreturn void refuse(const char *reason) {
    fprintf(stderr, "GC6 segment refused: %s\n", reason);
    exit(86);
}
void iw_gc_experiment_refuse(void) { refuse("allocation/initialization"); }
void ironwood_trace_register(const void *a, int b, const void *c, int d) {
    (void)a; (void)b; (void)c; (void)d;
    refuse("unexpected trace initialization");
}
void *iw_gc_experiment_new(int32_t type, uint64_t length) {
    if (type < 0 || type >= IW_GC_TYPE_COUNT) refuse("type index");
    void *result = iw_gc_new(iw_gc_types[type], (size_t)length);
    if (result == NULL) refuse("managed allocation");
    if (type == 2) ((struct node *)result)->payload = allocation_seed + allocation_index++;
    return result;
}
static int enumerate(int (*visit)(uintptr_t)) { return iw_gc_lifetime_owners.enumerate(visit); }
static const struct iw_gc_owner_protocol owners = {2, 15, 1, 0, enumerate, iw_gc_immortal_bases, 1};

__attribute__((noinline, disable_tail_calls)) static jlong native_entry(JNIEnv *env, int depth, jlong seed, int mode) {
    if (depth == -4) return (jlong)iw_gc_snapshot().collections;
    if (depth == -2) {
        iw_gc_foreign_root_close(&anchor);
        iw_gc_foreign_root_close(&anchor);
        anchored = NULL;
        return 0;
    }
    if (depth == -1) return gc6_scalar(seed);
    struct iw_gc_segment segment = {0};
    if (!iw_gc_segment_enter(&segment, iw_gc_stack_boundary())) refuse("native entry before managed execution");
    struct invocation invocation = {env, mode};
    allocation_seed = seed;
    allocation_index = 0;
    if (mode == 5 && depth > 0) {
        anchored = iw_gc_new(iw_gc_types[2], 0);
        if (anchored == NULL) refuse("anchor allocation");
        anchored->payload = seed;
        if (!iw_gc_foreign_root_open(&anchor, anchor_slots, 1)) refuse("anchor acquisition");
    }
    int64_t result = depth == -3 ? gc6_transition((int64_t)(uintptr_t)&invocation, seed) : depth == 0 ? gc6_phase(seed) : mode == 5
            ? gc6_borrow((int64_t)(uintptr_t)&invocation, depth, seed, anchored) : mode == 1
            ? gc6_derived((int64_t)(uintptr_t)&invocation, depth, seed)
            : gc6_run((int64_t)(uintptr_t)&invocation, depth, seed);
    if (depth == 0 && segment.previous == NULL && iw_gc_live_count() != 0) refuse("dropped segment retained roots");
    if (!iw_gc_segment_leave(&segment)) refuse("native entry completion");
    return result;
}

__attribute__((noinline, disable_tail_calls)) int64_t ironwood_bridge_callback_reenter(int64_t bits, int64_t depth, int64_t seed) {
    struct invocation *invocation = (void *)(uintptr_t)bits;
    struct iw_gc_segment *segment = iw_gc_segment_current();
    if (invocation->mode != 3 && !iw_gc_segment_suspend(segment, (void (*)(void))ironwood_bridge_callback_reenter))
        refuse("suspend before Java execution");
    if (invocation->mode == 2) segment->state_hash ^= 1;
    if (invocation->mode == 4) segment->boundary_sp += sizeof(void *);
    jlong result = (*invocation->env)->CallStaticLongMethod(invocation->env, driver, reenter, (jlong)(depth - 1), (jlong)(seed + 100));
    int pending = (*invocation->env)->ExceptionCheck(invocation->env);
    if (invocation->mode != 3 && !iw_gc_segment_resume(segment)) refuse("resume machine state");
    if (pending) refuse("unexpected Java failure");
    return result;
}

JNIEXPORT jlong JNICALL IW_ENTRY(JNIEnv *env, jclass owner, jclass target, jint depth, jlong seed, jint mode) {
    (void)owner;
    if (!initialized) {
        /* Bootstrap has no managed roots yet. The Java driver is already active. */
        driver = (*env)->NewGlobalRef(env, target);
        if (driver == NULL) return 0;
        reenter = (*env)->GetStaticMethodID(env, target, "reenter", "(JJ)J");
        if (reenter == NULL) return 0;
        if (!iw_gc_init(iw_gc_configuration(), 64 * 1024 * 1024, 0)) refuse("engine configuration");
        for (size_t i = 0; i < IW_GC_TYPE_COUNT; ++i) {
            iw_gc_types[i] = iw_gc_describe(layouts[i]);
            if (iw_gc_types[i] == NULL) refuse("exact layout");
        }
        if (!iw_gc_lifetimes_start()) refuse("runtime owners");
        const struct iw_gc_root_config config = {3, 8, 1, 3, (void (*)(void))native_entry,
            iw_gc_static_roots, (size_t)iw_gc_static_root_count, iw_gc_native_roots, 0, 8192, 3, 12, &owners};
        if (!iw_gc_roots_start(&config)) refuse("root protocol startup");
        initialized = 1;
    }
    return native_entry(env, depth, seed, mode);
}

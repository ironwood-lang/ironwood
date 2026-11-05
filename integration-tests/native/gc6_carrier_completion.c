// SPDX-License-Identifier: MIT OR Apache-2.0
/* Private JNI completion unit fixture, not foreign-segment qualification.
 * Generated completion runs against instrumented protected-operation doubles.
 * The separate normal compiler fixture verifies actual source carrier semantics.
 */
#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include "ironwood_gc_engine.h"
#include "ironwood_bridge.h"
#include "operations.h"

#ifndef IW_ENTRY
#define IW_ENTRY Java_Gc6CarrierCompletion_verify
#endif

struct carrier {
    const struct iw_gc_type *type;
    struct carrier *next;
    int64_t reference;
};
struct iw_callback_frame { JNIEnv *env; void *created_carriers; };
static const struct iw_gc_type *carrier_type;
static uint64_t acquired, released;
static uint64_t held_collections, held_total;
static unsigned take_calls, fail_take;
static void *roots[1];

static int32_t IW_CARRIER_NEXT(void *object, struct ironwood_bridge_result *result) {
    result->value.reference = ((struct carrier *)object)->next;
    return 0;
}
static int32_t IW_CARRIER_TAKE(void *object, struct ironwood_bridge_result *result) {
    if (++take_calls == fail_take) return 1;
    struct carrier *carrier = object;
    result->value.wide = carrier->reference;
    carrier->reference = 0;
    return 0;
}
static void tracked_delete(JNIEnv *env, jobject reference) {
    (*env)->DeleteGlobalRef(env, reference);
    ++released;
}

/* The driver instruments only DeleteGlobalRef in this generated fragment. */
#include "completion-instrumented.inc"

static void require(JNIEnv *env, int condition, const char *message) {
    if (!condition) (*env)->FatalError(env, message);
}

JNIEXPORT void JNICALL IW_ENTRY(JNIEnv *env, jclass owner,
        jthrowable first, jthrowable second, jint mode) {
    (void)owner;
    if (carrier_type == NULL) {
        require(env, iw_gc_init(iw_gc_configuration(), 32 * 1024 * 1024, 0), "collector startup");
        static const size_t offsets[] = {offsetof(struct carrier, next)};
        const struct iw_gc_layout layout = {1, IW_GC_FIXED, 1, 0, sizeof(struct carrier), 1, offsets};
        carrier_type = iw_gc_describe(&layout);
        require(env, carrier_type != NULL && iw_gc_roots(roots, 1), "exact carrier descriptor");
    }
    uint64_t before_acquired = acquired, before_released = released;
    struct carrier *head, *tail;
    if (mode == 4) {
        head = roots[0];
        require(env, head != NULL && acquired == released + 2, "missing image-owned retained chain");
        require(env, iw_gc_snapshot().collections == held_collections && iw_gc_total_count() == held_total,
                "another image changed collection/allocation counters");
        tail = head->next;
    } else {
        require(env, roots[0] == NULL && iw_gc_live_count() == 0 && acquired == released, "image state is not private");
        tail = iw_gc_new(carrier_type, 0);
        head = iw_gc_new(carrier_type, 0);
        require(env, head != NULL && tail != NULL, "carrier allocation");
        jobject tail_reference = (*env)->NewGlobalRef(env, second);
        require(env, tail_reference != NULL, "tail JNI acquisition");
        ++acquired;
        tail->reference = (int64_t)(uintptr_t)tail_reference;
        head->next = tail;
        jobject head_reference = (*env)->NewGlobalRef(env, first);
        require(env, head_reference != NULL, "head JNI acquisition");
        ++acquired;
        head->reference = (int64_t)(uintptr_t)head_reference;
        roots[0] = head;
    }
    require(env, iw_gc_collect() && iw_gc_live_count() == 2, "exact managed chain survival");
    require(env, head->reference != 0 && tail->reference != 0 && released == before_released,
            "collection released JNI ownership");
    if (mode == 3) {
        /* Hold this image's heap while the other image collects. */
        held_collections = iw_gc_snapshot().collections;
        held_total = iw_gc_total_count();
        return;
    }

    struct iw_callback_frame frame = {env, head};
    take_calls = 0;
    fail_take = mode == 2 ? 2u : 0u;
    if (mode == 1) require(env, (*env)->Throw(env, first) == JNI_OK, "pending original exception");
    uint64_t collections = iw_gc_snapshot().collections;
    iw_callback_release(&frame);
    if (mode == 2) {
        require(env, frame.created_carriers == tail && head->reference == 0 && tail->reference != 0
                && released == before_released + 1, "partial completion authority");
        fail_take = 0;
        iw_callback_release(&frame);
    }
    iw_callback_release(&frame);
    require(env, frame.created_carriers == NULL && head->reference == 0 && tail->reference == 0
            && head->next == tail, "completion destroyed or detached managed memory");
    require(env, acquired == before_acquired + (mode == 4 ? 0 : 2) && released == before_released + 2,
            "unbalanced JNI references or double release");
    require(env, iw_gc_snapshot().collections == collections, "completion collected");
    require(env, iw_gc_collect() && iw_gc_live_count() == 2 && head->next == tail,
            "completed carrier graph was not retained independently");
    roots[0] = NULL;
    require(env, iw_gc_collect() && iw_gc_live_count() == 0, "dropped carrier graph was not reclaimed");
    require(env, iw_gc_snapshot().disabled, "collector left enabled");
    require(env, !!(*env)->ExceptionCheck(env) == (mode == 1), "completion replaced original failure");
}

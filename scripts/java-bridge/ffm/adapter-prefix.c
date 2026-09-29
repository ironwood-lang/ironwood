// SPDX-License-Identifier: MIT OR Apache-2.0
#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include "ironwood_bridge.h"
extern void ironwood_bridge_bootstrap(void);
_Static_assert(sizeof(struct ironwood_bridge_result) == 288
    && offsetof(struct ironwood_bridge_result, exception) == 8
    && offsetof(struct ironwood_bridge_result, failure) == 16, "experiment result ABI");
static void failure(JNIEnv *env, const struct ironwood_bridge_result *frame, int32_t status) {
    const char *message = status == 1 && frame->failure.type_name != NULL
        ? frame->failure.type_name : "native snapshot unavailable";
    jclass error = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (error != NULL) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
}
static void java_failure(JNIEnv *env, jclass type, jlong frame, jint status) {
    (void)type; failure(env, (const struct ironwood_bridge_result *)(uintptr_t)frame, status);
}
static jlong allocations(JNIEnv *env, jclass type) {
    (void)env; (void)type; return ironwood_allocation_count();
}

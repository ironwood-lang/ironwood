// SPDX-License-Identifier: MIT OR Apache-2.0
#include <jni.h>
#include <stdint.h>

/* Independent minimal JNI baseline. Unsigned arithmetic preserves Java wrap.
 * The instance baseline loads a real native field through a private address;
 * no registry, native allocation or lifetime instrumentation is involved. */
static const struct { int64_t value; } instance = {17};

JNIEXPORT jlong JNICALL Java_PerformanceConsumer_bare(JNIEnv *env, jclass type, jlong seed) {
    (void)env; (void)type;
    return (jlong)((uint64_t)seed * UINT64_C(2862933555777941757) + UINT64_C(3037000493));
}

JNIEXPORT jlong JNICALL Java_PerformanceConsumer_address(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jlong)(uintptr_t)&instance;
}

JNIEXPORT jlong JNICALL Java_PerformanceConsumer_bareInstance(JNIEnv *env, jclass type, jlong address, jlong seed) {
    (void)env; (void)type;
    const int64_t *value = (const int64_t *)(uintptr_t)address;
    return (jlong)((uint64_t)seed + (uint64_t)*value);
}

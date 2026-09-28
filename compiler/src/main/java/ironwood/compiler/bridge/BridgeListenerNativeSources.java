// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;

/** Private ownership transport. Native slot attribution remains an independent admission gate. */
public final class BridgeListenerNativeSources {
    private BridgeListenerNativeSources() {}

    public static String generate(CompilationArtifact artifact, BridgeListenerProxyEntries entries, String listener) {
        if (!entries.matches(artifact)) throw new IllegalArgumentException("listener transport requires matching proxy entries");
        var operation = entries.operations().stream().filter(value -> value.listener().equals(listener)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("listener has no proved proxy operations"));
        return TEMPLATE.replace("@CREATE@", operation.create().linkageName()).replace("@DESTROY@", operation.destroy().linkageName());
    }

    private static final String TEMPLATE = """
            #include <stdlib.h>
            extern int32_t @CREATE@(int64_t, struct ironwood_bridge_result *);
            extern void @DESTROY@(void *);
            struct iw_listener {
                void *proxy;
                jobject reference;
                uint64_t uses;
                struct iw_listener *previous, *next;
            };
            static struct iw_listener *iw_listeners;
            // Each retained slot and suspended invocation owns one use. Neither
            // JNIEnv nor an invocation context can escape into this record.
            static int iw_listener_acquire(struct iw_listener *listener) {
                if (listener == NULL) return 1;
                if (listener->uses == UINT64_MAX) return 0;
                listener->uses++;
                return 1;
            }
            static void iw_listener_release(JNIEnv *env, struct iw_listener *listener) {
                if (listener == NULL || --listener->uses != 0) return;
                if (listener->previous != NULL) listener->previous->next = listener->next;
                else iw_listeners = listener->next;
                if (listener->next != NULL) listener->next->previous = listener->previous;
                @DESTROY@(listener->proxy);
                (*env)->DeleteGlobalRef(env, listener->reference);
                free(listener);
            }
            // Prepare before touching native slots. Status -1 preserves a JNI
            // failure; positive status carries a protected native failure.
            // Only successful preparation assigns the output ownership token.
            static int32_t iw_listener_prepare(JNIEnv *env, jobject value, jclass oom,
                    struct iw_listener **output, struct ironwood_bridge_result *result) {
                if (value == NULL) { *output = NULL; return 0; }
                // Identity conversion occurs only when preparing registration,
                // never on an invocation or callback. Include listeners kept
                // alive by suspended frames even after their last slot clears.
                for (struct iw_listener *existing = iw_listeners; existing != NULL; existing = existing->next) {
                    if (!(*env)->IsSameObject(env, existing->reference, value)) continue;
                    if (!iw_listener_acquire(existing)) {
                        (*env)->ThrowNew(env, oom, "listener ownership count exhausted");
                        return -1;
                    }
                    *output = existing;
                    return 0;
                }
                jobject reference = (*env)->NewGlobalRef(env, value);
                if (reference == NULL) {
                    if (!(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env, oom, "listener global reference allocation failed");
                    return -1;
                }
                struct iw_listener *listener = malloc(sizeof(*listener));
                if (listener == NULL) {
                    (*env)->DeleteGlobalRef(env, reference);
                    (*env)->ThrowNew(env, oom, "listener ownership allocation failed");
                    return -1;
                }
                int32_t status = @CREATE@((int64_t)(uintptr_t)reference, result);
                if (status != 0) {
                    (*env)->DeleteGlobalRef(env, reference);
                    free(listener);
                    return status;
                }
                listener->proxy = result->value.reference;
                listener->reference = reference;
                listener->uses = 1;
                listener->previous = NULL;
                listener->next = iw_listeners;
                if (iw_listeners != NULL) iw_listeners->previous = listener;
                iw_listeners = listener;
                *output = listener;
                return 0;
            }
            // Caller has proved complete slot attribution and reserved the new
            // ownership token before mutation. This commit cannot call Java or
            // raise; it also works with a pending callback exception.
            static void iw_listener_commit(JNIEnv *env, struct iw_listener **slot, struct iw_listener *prepared) {
                struct iw_listener *previous = *slot;
                *slot = prepared;
                iw_listener_release(env, previous);
            }
            """;
}

// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;

/** Cold callback failure transport. Retention policy and destruction are separate proofs. */
public final class BridgeCallbackCarrierNativeSources {
    private BridgeCallbackCarrierNativeSources() {}

    public static String generate(CompilationArtifact artifact, BridgeCallbackCarrierEntries entries) {
        if (!entries.matches(artifact)) throw new IllegalArgumentException("callback transport requires matching carrier entries");
        return SOURCE.replace("@CREATE@", entries.factory().linkageName())
                .replace("@REFERENCE@", entries.reference().linkageName())
                .replace("@EXHAUSTED@", entries.exhausted().linkageName());
    }

    private static final String SOURCE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            extern int32_t @CREATE@(int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t @REFERENCE@(void *, struct ironwood_bridge_result *);
            extern int32_t @EXHAUSTED@(struct ironwood_bridge_result *);
            struct iw_callback_frame {
                JNIEnv *env;
                void *created_carriers;
            };
            // Called only with a pending Java exception and within a protected
            // native invocation. No pending JNI exception crosses native handlers.
            static _Noreturn void iw_callback_capture(struct iw_callback_frame *frame) {
                JNIEnv *env = frame->env;
                jthrowable local = (*env)->ExceptionOccurred(env);
                (*env)->ExceptionClear(env);
                jobject global = (*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local);
                struct ironwood_bridge_result result = {0};
                if (global == NULL) {
                    // Resource exhaustion cannot promise the original identity.
                    // Raise native OOM, allowing ordinary native catch/translation.
                    (*env)->ExceptionClear(env);
                    @EXHAUSTED@(&result);
                    ironwood_throw(result.exception);
                }
                int32_t status = @CREATE@((int64_t)(uintptr_t)global,
                    (int64_t)(uintptr_t)frame->created_carriers, &result);
                if (status != 0) {
                    (*env)->DeleteGlobalRef(env, global);
                    ironwood_throw(result.exception);
                }
                // Only newly created carriers enter this invocation's chain.
                // Retained carriers from previous invocations never enter it.
                frame->created_carriers = result.value.reference;
                ironwood_throw(result.value.reference);
            }
            // Run after the outer protected entry returns, before ordinary native
            // exception translation. Inspect the actual outgoing object, never a
            // pending frame flag that could override native catch/replacement.
            static int iw_callback_restore(JNIEnv *env, struct ironwood_bridge_result *failure) {
                struct ironwood_bridge_result reference = {0};
                if (@REFERENCE@(failure->exception, &reference) != 0) {
                    *failure = reference;
                    return 0;
                }
                if (reference.value.wide == 0) return 0;
                (*env)->Throw(env, (jthrowable)(uintptr_t)reference.value.wide);
                return 1;
            }
            """;
}

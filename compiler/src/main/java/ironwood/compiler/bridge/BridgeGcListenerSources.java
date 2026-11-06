// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrType;
import java.util.*;

/** JNI ownership and exact managed linkage, with no managed destruction. */
final class BridgeGcListenerSources {
    private BridgeGcListenerSources() {}

    static String generate(CompilationArtifact artifact, BridgeObjectAdmission admission,
            BridgePermanentJavaSources.Sources java, BridgeGeneration generation, Map<IrType, Integer> facades) {

        var proof = admission.entries().gcCallbacks().orElseThrow();
        var callbacks = BridgeCallbackNativeSources.generateManaged(artifact, proof.listeners(), facades, generation);
        var text = new StringBuilder(supportWrappers(proof.carriers()))
                .append(supportAliases(proof.carriers(), BridgeCallbackCarrierNativeSources.generateManaged(artifact, proof.carriers())))
                .append(supportAliases(proof.carriers(), BridgeCallbackCarrierNativeSources.cleanup(artifact, proof.carriers(), admission.surface().roots(), proof.completion())))
                .append(callbacks.source()).append("static uint64_t iw_gc_callback_owners;\n")
                .append("static void iw_gc_listener_invocation_leave(JNIEnv *);\n").append(INVOCATION)
                .append(BridgeCallbackBatchSources.managedNativeCallbacks(admission.batching().orElseThrow()));
        text.append("static int iw_gc_registration_metadata_init(JNIEnv *, jclass *);\n")
                .append("static void iw_gc_registration_metadata_dispose(JNIEnv *);\n")
                .append("static int iw_callback_metadata_init(JNIEnv *env, jclass *classes) {\n")
                .append("    if (!iw_gc_registration_metadata_init(env, classes)) return 0;\n");
        int dispatch = java.declarations().generatedTypes().indexOf(BridgeCallbackDispatchSources.binaryName(generation));
        if (dispatch < 0) throw new IllegalArgumentException("missing managed Java callback dispatch");
        text.append("    iw_callback_dispatch = (*env)->NewGlobalRef(env, classes[").append(dispatch).append("]);\n")
                .append("    if (iw_callback_dispatch == NULL) return 0;\n");
        for (var method : callbacks.methods()) text.append("    ").append(method.methodField())
                .append(" = (*env)->GetStaticMethodID(env, iw_callback_dispatch, ")
                .append(BridgeBootstrapSources.cString(method.name())).append(", ")
                .append(BridgeBootstrapSources.cString(method.descriptor())).append(");\n")
                .append("    if (").append(method.methodField()).append(" == NULL) return 0;\n");
        text.append(BridgeCallbackBatchSources.metadata(admission.batching().orElseThrow(), dispatch).replace("goto failed", "return 0"));
        text.append("    return 1;\n}\nstatic void iw_callback_metadata_dispose(JNIEnv *env) {\n")
                .append("    iw_gc_registration_metadata_dispose(env);\n")
                .append("    if (iw_callback_dispatch != NULL) (*env)->DeleteGlobalRef(env, iw_callback_dispatch);\n")
                .append("    iw_callback_dispatch = NULL;\n}\n");
        var factories = proof.factories().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        for (var factory : factories) text.append("extern int32_t ").append(factory.getValue().linkageName())
                .append("(int64_t, int64_t);\n");
        text.append("static int32_t iw_gc_listener_create(int kind, int64_t handle, struct ironwood_bridge_result *result) {\n    switch (kind) {\n");
        for (int i = 0; i < factories.size(); i++) text.append("    case ").append(i).append(": return ")
                .append(factories.get(i).getValue().linkageName()).append("(handle, (int64_t)(uintptr_t)result);\n");
        text.append("    default: iw_gc_transition_refuse();\n    }\n}\n").append(LISTENERS);
        return text.toString();
    }

    static int kind(BridgeGcCallbacks proof, IrType type) {

        int result = proof.factories().keySet().stream().sorted().toList().indexOf(type.referenceName());
        if (result < 0) throw new IllegalArgumentException("unknown managed listener kind");
        return result;
    }

    static String supportAliases(BridgeCallbackCarrierEntries entries, String source) {

        for (var function : entries.functions()) source = source.replaceAll("\\b" + java.util.regex.Pattern.quote(function.linkageName()) + "\\b",
                "iw_gc_support_" + function.linkageName());
        return source;
    }

    private static String supportWrappers(BridgeCallbackCarrierEntries entries) {

        var text = new StringBuilder();
        for (var function : entries.functions()) {
            var typed = new ArrayList<String>();
            var formals = new ArrayList<String>();
            var arguments = new ArrayList<String>();
            for (int i = 0; i < function.parameters().size(); i++) {
                var type = function.parameters().get(i).value().type();
                boolean frame = i + 1 == function.parameters().size();
                String carrier = type.isReference() ? "void *" : BridgeValueNativeSources.cType(type);
                typed.add(carrier);
                formals.add((frame ? "struct ironwood_bridge_result *" : carrier) + " p" + i);
                arguments.add((frame ? "(int64_t)(uintptr_t)" : "") + "p" + i);
            }
            text.append("extern int32_t ").append(function.linkageName()).append('(').append(String.join(", ", typed)).append(");\n")
                    .append("int32_t iw_gc_support_").append(function.linkageName()).append('(').append(String.join(", ", formals))
                    .append(") { return ").append(function.linkageName()).append('(').append(String.join(", ", arguments)).append("); }\n");
        }
        return text.toString();
    }

    private static final String INVOCATION = """
            __attribute__((unused)) static void iw_gc_callback_prepare(struct iw_callback_frame *frame) {
                if (iw_gc_callback_owners == UINT64_MAX) iw_gc_transition_refuse();
                iw_gc_callback_owners++;
                frame->root_slot = &frame->created_carriers;
                if (!iw_gc_foreign_root_open(&frame->roots, &frame->root_slot, 1)) iw_gc_transition_refuse();
            }
            __attribute__((unused)) static void iw_gc_callback_finish(struct iw_callback_frame *frame) {
                iw_callback_release(frame);
                iw_gc_foreign_root_close(&frame->roots);
                iw_gc_listener_invocation_leave(frame->env);
            }
            """;

    private static final String LISTENERS = """
            struct iw_gc_listener {
                int kind;
                uint64_t uses;
                void *proxy;
                void *slot;
                struct iw_gc_foreign_root root;
                jobject reference;
                struct iw_gc_listener *next;
                struct iw_gc_listener *retired_next;
                unsigned retired;
            };
            static struct iw_gc_listener *iw_gc_listeners;
            static struct iw_gc_listener *iw_gc_retired_listeners;
            static void iw_gc_listener_drop(JNIEnv *env, struct iw_gc_listener *listener) {
                struct iw_gc_listener **cursor = &iw_gc_listeners;
                while (*cursor != NULL && *cursor != listener) cursor = &(*cursor)->next;
                if (*cursor != listener) iw_gc_transition_refuse();
                *cursor = listener->next;
                (*env)->DeleteGlobalRef(env, listener->reference);
                iw_gc_foreign_root_close(&listener->root);
                free(listener);
            }
            static void iw_gc_listener_release(JNIEnv *env, struct iw_gc_listener *listener) {
                if (listener == NULL || --listener->uses != 0) return;
                if (iw_gc_callback_owners != 0) {
                    if (!listener->retired) {
                        listener->retired = 1;
                        listener->retired_next = iw_gc_retired_listeners;
                        iw_gc_retired_listeners = listener;
                    }
                } else iw_gc_listener_drop(env, listener);
            }
            static void iw_gc_listener_invocation_leave(JNIEnv *env) {
                if (iw_gc_callback_owners == 0) iw_gc_transition_refuse();
                if (--iw_gc_callback_owners != 0) return;
                while (iw_gc_retired_listeners != NULL) {
                    struct iw_gc_listener *listener = iw_gc_retired_listeners;
                    iw_gc_retired_listeners = listener->retired_next;
                    listener->retired_next = NULL; listener->retired = 0;
                    if (listener->uses == 0) iw_gc_listener_drop(env, listener);
                }
            }
            __attribute__((unused)) static int iw_gc_listener_acquire(JNIEnv *env, struct iw_gc_listener *listener) {
                if (listener == NULL) return 1;
                if (listener->uses == UINT64_MAX) {
                    iw_exception_error(env, &iw_exceptions, 1, "listener ownership exhausted"); return 0;
                }
                listener->uses++; return 1;
            }
            struct iw_gc_listener_use { JNIEnv *env; struct iw_gc_listener *value; };
            __attribute__((unused)) static void iw_gc_listener_use_close(struct iw_gc_listener_use *use) {
                iw_gc_listener_release(use->env, use->value);
            }
            __attribute__((unused)) static int32_t iw_gc_listener_prepare(JNIEnv *env, int kind, jobject value,
                    struct iw_gc_listener **output, struct ironwood_bridge_result *result) {
                if (value == NULL) { *output = NULL; return 0; }
                for (struct iw_gc_listener *listener = iw_gc_listeners; listener != NULL; listener = listener->next) {
                    if (listener->kind != kind || !(*env)->IsSameObject(env, value, listener->reference)) continue;
                    if (listener->uses == UINT64_MAX) {
                        iw_exception_error(env, &iw_exceptions, 1, "listener ownership exhausted"); return -1;
                    }
                    listener->uses++; *output = listener; return 0;
                }
                struct iw_gc_listener *listener = calloc(1, sizeof(*listener));
                if (listener == NULL) { iw_exception_error(env, &iw_exceptions, 1, "listener reservation failed"); return -1; }
                listener->reference = (*env)->NewGlobalRef(env, value);
                if (!iw_gc_jni_acquired(env, listener->reference)) { free(listener); return -1; }
                int32_t status = iw_gc_listener_create(kind, (int64_t)(uintptr_t)listener->reference, result);
                if (status != 0) { (*env)->DeleteGlobalRef(env, listener->reference); free(listener); return status; }
                listener->proxy = result->value.reference; listener->slot = &listener->proxy;
                if (!iw_gc_foreign_root_open(&listener->root, &listener->slot, 1)) iw_gc_transition_refuse();
                listener->kind = kind; listener->uses = 1;
                listener->next = iw_gc_listeners; iw_gc_listeners = listener;
                *output = listener; return 0;
            }
            """;
}

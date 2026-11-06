// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Explicit image-owned anchors and facade epochs. Collection performs no JNI work. */
final class BridgeGcAnchorSources {
    record Sources(String source, List<BridgePermanentNativeSources.Adapter> adapters) {}
    private BridgeGcAnchorSources() {}

    static Sources generate(BridgePermanentJavaSources.Sources java, BridgeGeneration generation) {
        String support = generation.supportPackage();
        var text = new StringBuilder(TYPES.replace("@COUNT@", Integer.toString(Math.max(1, java.facades().size()))));
        text.append("static const char *const iw_gc_address_names[] = {");
        text.append(java.facades().isEmpty() ? "NULL" : java.facades().stream().map(f -> BridgeJavaSources.quote(f.addressField())).collect(Collectors.joining(", ")));
        text.append("};\nstatic const char *const iw_gc_state_names[] = {");
        text.append(java.facades().isEmpty() ? "NULL" : java.facades().stream().map(f -> BridgeJavaSources.quote(f.stateField())).collect(Collectors.joining(", ")));
        text.append("};\nstatic void iw_object_metadata_dispose(JNIEnv *env) {\n");
        if (!java.enums().isEmpty()) text.append("    iw_enum_metadata_dispose(env);\n");
        text.append("    for (size_t i = 0; i < ").append(java.facades().size()).append("; i++) {\n")
                .append("        if (iw_gc_types[i] != NULL) (*env)->DeleteGlobalRef(env, iw_gc_types[i]); iw_gc_types[i] = NULL;\n    }\n")
                .append("    if (iw_gc_handle_type != NULL) (*env)->DeleteGlobalRef(env, iw_gc_handle_type);\n")
                .append("    if (iw_gc_state_type != NULL) (*env)->DeleteGlobalRef(env, iw_gc_state_type);\n")
                .append("    iw_gc_handle_type = NULL; iw_gc_state_type = NULL;\n}\n")
                .append("static int iw_object_metadata_init(JNIEnv *env, jclass *classes) {\n");
        if (!java.enums().isEmpty()) text.append("    if (!iw_enum_metadata_init(env, classes)) goto failed;\n");
        for (int i = 0; i < java.facades().size(); i++) global(text, "iw_gc_types[" + i + "]", java.declarations().generatedTypes().indexOf(java.facades().get(i).binaryName()));
        global(text, "iw_gc_handle_type", java.declarations().generatedTypes().indexOf(support + ".RootHandle"));
        global(text, "iw_gc_state_type", java.declarations().generatedTypes().indexOf(support + ".RootState"));
        text.append("    iw_gc_handle_init = (*env)->GetMethodID(env, iw_gc_handle_type, \"<init>\", \"(Ljava/lang/Object;J)V\");\n")
                .append("    if (iw_gc_handle_init == NULL) goto failed;\n")
                .append("    iw_gc_handle_value = (*env)->GetFieldID(env, iw_gc_handle_type, \"value\", \"Ljava/lang/Object;\");\n")
                .append("    iw_gc_handle_anchor = (*env)->GetFieldID(env, iw_gc_handle_type, \"anchor\", \"J\");\n")
                .append("    if (iw_gc_handle_value == NULL || iw_gc_handle_anchor == NULL) goto failed;\n")
                .append("    iw_gc_state_init = (*env)->GetMethodID(env, iw_gc_state_type, \"<init>\", \"()V\");\n")
                .append("    if (iw_gc_state_init == NULL) goto failed;\n")
                .append("    iw_gc_state_status = (*env)->GetFieldID(env, iw_gc_state_type, \"status\", \"I\");\n")
                .append("    if (iw_gc_handle_init == NULL || iw_gc_state_init == NULL || iw_gc_state_status == NULL) goto failed;\n")
                .append("    return 1;\nfailed: iw_object_metadata_dispose(env); return 0;\n}\n")
                .append(CORE.replace("@STATE@", "L" + support.replace('.', '/') + "/RootState;"));
        var adapters = new ArrayList<BridgePermanentNativeSources.Adapter>();
        adapters.add(new BridgePermanentNativeSources.Adapter(new BridgeJavaSources.NativeDeclaration(support + ".RootHandle", "$release", "(J)V"), "iw_gc_handle_release"));
        for (int i = 0; i < java.facades().size(); i++) {
            String function = "iw_gc_retain_" + i;
            String name = "$retain$" + (i + 1);
            text.append("static jobject ").append(function).append("(JNIEnv *env, jclass type, jobject value) {\n")
                    .append("    (void)type; void *address = NULL; jobject state = NULL;\n")
                    .append("    if (!iw_root_input(env, ").append(i).append(", value, &address, &state)) return NULL;\n")
                    .append("    struct iw_gc_anchor_reservation reserved __attribute__((cleanup(iw_gc_anchor_discard))) = {0};\n")
                    .append("    if (!iw_gc_anchor_reserve(env, &reserved)) return NULL;\n")
                    .append("    return iw_gc_anchor_deliver(env, &reserved, ").append(i).append(", address);\n}\n");
            adapters.add(new BridgePermanentNativeSources.Adapter(new BridgeJavaSources.NativeDeclaration(support + ".Roots", name,
                    "(L" + java.facades().get(i).binaryName().replace('.', '/') + ";)L" + support.replace('.', '/') + "/RootHandle;"), function));
        }
        return new Sources(text.toString(), List.copyOf(adapters));
    }

    private static void global(StringBuilder text, String target, int index) {
        if (index < 0) throw new IllegalArgumentException("missing GC bootstrap class");
        text.append("    ").append(target).append(" = (*env)->NewGlobalRef(env, classes[").append(index).append("]);\n")
                .append("    if (").append(target).append(" == NULL) goto failed;\n");
    }

    private static final String TYPES = """
            static jclass iw_gc_types[@COUNT@], iw_gc_handle_type, iw_gc_state_type;
            static jfieldID iw_gc_addresses[@COUNT@], iw_gc_states[@COUNT@], iw_gc_state_status;
            static jmethodID iw_gc_constructors[@COUNT@], iw_gc_handle_init, iw_gc_state_init;
            static jfieldID iw_gc_handle_value, iw_gc_handle_anchor;
            struct iw_gc_facade {
                struct iw_gc_facade *next;
                void *value;
                void *slot;
                struct iw_gc_foreign_root root;
                jobject facade, state;
                size_t anchors;
            };
            struct iw_gc_anchor {
                struct iw_gc_anchor *next;
                struct iw_gc_facade *owner;
                uint64_t id;
            };
            struct iw_gc_anchor_reservation {
                JNIEnv *env;
                struct iw_gc_anchor *anchor;
                struct iw_gc_facade *candidate;
            };
            static struct iw_gc_facade *iw_gc_facades;
            static struct iw_gc_anchor *iw_gc_anchors;
            static uint64_t iw_gc_next_anchor = 1;
            """;

    private static final String CORE = """
            static int iw_gc_facade_ready(JNIEnv *env, int index) {
                if (iw_gc_constructors[index] != NULL) return 1;
                jfieldID address = (*env)->GetFieldID(env, iw_gc_types[index], iw_gc_address_names[index], "J");
                if (address == NULL) return 0;
                jfieldID state = (*env)->GetFieldID(env, iw_gc_types[index], iw_gc_state_names[index], "@STATE@");
                if (state == NULL) return 0;
                jmethodID constructor = (*env)->GetMethodID(env, iw_gc_types[index], "<init>", "(J@STATE@Ljava/lang/Void;)V");
                if (constructor == NULL) return 0;
                iw_gc_addresses[index] = address; iw_gc_states[index] = state; iw_gc_constructors[index] = constructor;
                return 1;
            }
            static void iw_gc_facade_drop(JNIEnv *env, struct iw_gc_facade *owner) {
                if (owner->anchors != 0) return;
                struct iw_gc_facade **cursor = &iw_gc_facades;
                while (*cursor != NULL && *cursor != owner) cursor = &(*cursor)->next;
                if (*cursor == owner) *cursor = owner->next;
                if (owner->state != NULL) {
                    jthrowable pending = (*env)->ExceptionOccurred(env);
                    if (pending != NULL) (*env)->ExceptionClear(env);
                    (*env)->SetIntField(env, owner->state, iw_gc_state_status, 1);
                    if (pending != NULL) { (*env)->Throw(env, pending); (*env)->DeleteLocalRef(env, pending); }
                    (*env)->DeleteGlobalRef(env, owner->state);
                }
                if (owner->facade != NULL) (*env)->DeleteGlobalRef(env, owner->facade);
                iw_gc_foreign_root_close(&owner->root);
                free(owner);
            }
            static void iw_gc_anchor_release(JNIEnv *env, uint64_t id) {
                struct iw_gc_anchor **cursor = &iw_gc_anchors;
                while (*cursor != NULL && (*cursor)->id != id) cursor = &(*cursor)->next;
                if (*cursor == NULL) return;
                struct iw_gc_anchor *anchor = *cursor; *cursor = anchor->next;
                struct iw_gc_facade *owner = anchor->owner;
                free(anchor);
                if (owner->anchors == 0) iw_gc_transition_refuse();
                owner->anchors--; iw_gc_facade_drop(env, owner);
            }
            static void iw_gc_anchor_discard(struct iw_gc_anchor_reservation *reservation) {
                if (reservation->anchor != NULL) {
                    if (reservation->anchor->owner != NULL) iw_gc_anchor_release(reservation->env, reservation->anchor->id);
                    else free(reservation->anchor);
                }
                if (reservation->candidate != NULL) iw_gc_facade_drop(reservation->env, reservation->candidate);
            }
            static int iw_gc_anchor_reserve(JNIEnv *env, struct iw_gc_anchor_reservation *reservation) {
                reservation->env = env;
                if (iw_gc_next_anchor == UINT64_MAX) {
                    iw_exception_error(env, &iw_exceptions, 1, "Ironwood anchor identity exhausted"); return 0;
                }
                reservation->anchor = calloc(1, sizeof(*reservation->anchor));
                reservation->candidate = calloc(1, sizeof(*reservation->candidate));
                if (reservation->anchor == NULL || reservation->candidate == NULL) {
                    iw_exception_error(env, &iw_exceptions, 1, "Ironwood anchor reservation failed"); return 0;
                }
                reservation->anchor->id = iw_gc_next_anchor++;
                return 1;
            }
            static int iw_root_input(JNIEnv *env, int index, jobject value, void **address, jobject *state) {
                if (value == NULL) return 1;
                if (!iw_gc_facade_ready(env, index)) return 0;
                if (!(*env)->IsInstanceOf(env, value, iw_gc_types[index])) {
                    iw_exception_error(env, &iw_exceptions, 0, "foreign image facade"); return 0;
                }
                *state = (*env)->GetObjectField(env, value, iw_gc_states[index]);
                if (*state == NULL || (*env)->GetIntField(env, *state, iw_gc_state_status) != 0) {
                    iw_exception_error(env, &iw_exceptions, 0, "closed native facade"); return 0;
                }
                *address = (void *)(uintptr_t)(*env)->GetLongField(env, value, iw_gc_addresses[index]);
                return !(*env)->ExceptionCheck(env);
            }
            static jobject iw_gc_anchor_deliver(JNIEnv *env, struct iw_gc_anchor_reservation *reservation, int index, void *address) {
                if (address == NULL) return NULL;
                struct iw_gc_facade *owner = iw_gc_facades;
                while (owner != NULL && owner->value != address) owner = owner->next;
                if (owner == NULL) {
                    owner = reservation->candidate; owner->value = address; owner->slot = &owner->value;
                    if (!iw_gc_foreign_root_open(&owner->root, &owner->slot, 1)) iw_gc_transition_refuse();
                    if (!iw_gc_facade_ready(env, index)) return NULL;
                    jobject state = (*env)->NewObject(env, iw_gc_state_type, iw_gc_state_init);
                    if (!iw_gc_jni_acquired(env, state)) return NULL;
                    owner->state = (*env)->NewGlobalRef(env, state);
                    if (!iw_gc_jni_acquired(env, owner->state)) { (*env)->DeleteLocalRef(env, state); return NULL; }
                    jobject value = (*env)->NewObject(env, iw_gc_types[index], iw_gc_constructors[index], (jlong)(uintptr_t)address, state, (jobject)NULL);
                    (*env)->DeleteLocalRef(env, state);
                    if (!iw_gc_jni_acquired(env, value)) return NULL;
                    owner->facade = (*env)->NewGlobalRef(env, value);
                    (*env)->DeleteLocalRef(env, value);
                    if (!iw_gc_jni_acquired(env, owner->facade)) return NULL;
                    struct iw_gc_facade *published = iw_gc_facades;
                    while (published != NULL && published->value != address) published = published->next;
                    if (published != NULL) {
                        iw_gc_facade_drop(env, owner); owner = published;
                    } else { owner->next = iw_gc_facades; iw_gc_facades = owner; }
                    reservation->candidate = NULL;
                }
                struct iw_gc_anchor *anchor = reservation->anchor;
                if (owner->anchors == SIZE_MAX) { iw_exception_error(env, &iw_exceptions, 1, "facade ownership exhausted"); return NULL; }
                anchor->owner = owner; anchor->next = iw_gc_anchors; iw_gc_anchors = anchor; owner->anchors++;
                jobject handle = (*env)->NewObject(env, iw_gc_handle_type, iw_gc_handle_init, owner->facade, (jlong)anchor->id);
                if (iw_gc_jni_acquired(env, handle)) reservation->anchor = NULL;
                return handle;
            }
            static void iw_gc_handle_release(JNIEnv *env, jclass type, jlong id) {
                (void)type; iw_gc_anchor_release(env, (uint64_t)id);
            }
            __attribute__((unused)) static void iw_gc_callback_close(JNIEnv *env, jobject handle) {
                if (handle == NULL) return;
                // Field access is a JNI leaf. Preserve any pending Java failure.
                jthrowable pending = (*env)->ExceptionOccurred(env);
                if (pending != NULL) (*env)->ExceptionClear(env);
                uint64_t anchor = (uint64_t)(*env)->GetLongField(env, handle, iw_gc_handle_anchor);
                (*env)->SetLongField(env, handle, iw_gc_handle_anchor, 0);
                iw_gc_anchor_release(env, anchor);
                (*env)->DeleteLocalRef(env, handle);
                if (pending != NULL) { (*env)->Throw(env, pending); (*env)->DeleteLocalRef(env, pending); }
            }
            __attribute__((unused)) static jobject iw_gc_callback_wrap(JNIEnv *env, int kind, void *value, jobject *handle) {
                if (value == NULL) return NULL;
                struct iw_gc_anchor_reservation reserved __attribute__((cleanup(iw_gc_anchor_discard))) = {0};
                if (!iw_gc_anchor_reserve(env, &reserved)) return NULL;
                *handle = iw_gc_anchor_deliver(env, &reserved, kind, value);
                return *handle == NULL ? NULL : (*env)->GetObjectField(env, *handle, iw_gc_handle_value);
            }
            """;
}

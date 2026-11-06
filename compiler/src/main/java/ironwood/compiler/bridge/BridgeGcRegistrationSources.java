// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import java.util.*;

/** Explicit registration tokens and prepared, rooted final-slot reconciliation. */
final class BridgeGcRegistrationSources {
    record Sources(String source, List<BridgePermanentNativeSources.Adapter> adapters) {}
    private BridgeGcRegistrationSources() {}

    static void declarations(StringBuilder text, BridgeObjectAdmission admission, BridgeCallableId id) {

        var callbacks = admission.entries().gcCallbacks().orElseThrow();
        if (!callbacks.retaining(id)) return;
        text.append("    struct iw_gc_registration_use registration __attribute__((cleanup(iw_gc_registration_use_close))) = {.env = env};\n")
                .append("    struct iw_gc_binding_reservations bindings __attribute__((cleanup(iw_gc_binding_reservations_close))) = {.env = env};\n");
    }

    static void acquire(StringBuilder text, BridgeObjectAdmission admission, BridgeCallableId id) {

        if (!admission.entries().gcCallbacks().orElseThrow().retaining(id)) return;
        text.append("    if (!iw_gc_registration_acquire(&registration, (uint64_t)registration_id)) goto preparation_failed;\n");
    }

    static void prepare(StringBuilder text, BridgeObjectAdmission admission, BridgeCallableId id) {

        var proof = admission.entries().gcCallbacks().orElseThrow();
        if (!proof.retaining(id)) return;
        var slots = proof.slots().get(id).slots();
        int candidates = slots.stream().mapToInt(slot -> slot.valueInputs().size()).sum();
        text.append("    if (!iw_gc_binding_reservations_prepare(&bindings, ").append(slots.size()).append(", ").append(candidates)
                .append(")) goto preparation_failed;\n");
        int candidate = 0;
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (slot.valueInputs().isEmpty()) continue;
            text.append("    bindings.bindings[").append(i).append("] = iw_gc_binding_reserve(env);\n")
                    .append("    if (bindings.bindings[").append(i).append("] == NULL) goto preparation_failed;\n");
            for (int input : slot.valueInputs().stream().sorted().toList()) text.append("    if (!iw_gc_listener_acquire(env, listener")
                    .append(input).append(".value)) goto preparation_failed;\n")
                    .append("    bindings.candidates[").append(candidate++).append("] = listener").append(input).append(".value;\n");
        }
        text.append("    if (registration.owner->closed) { iw_exception_error(env, &iw_exceptions, 0, \"closed listener registration\"); goto preparation_failed; }\n");
    }

    static void commit(StringBuilder text, BridgeObjectAdmission admission, BridgeCallableId id) {

        var proof = admission.entries().gcCallbacks().orElseThrow();
        var slots = proof.slots().get(id).slots();
        boolean retaining = proof.retaining(id);
        int candidate = 0;
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            text.append("    { struct iw_gc_listener **selected = NULL;\n");
            for (int input : slot.valueInputs().stream().sorted().toList()) {
                text.append("      if (bindings.candidates[").append(candidate).append("] != NULL && bindings.candidates[").append(candidate)
                        .append("]->proxy == retention_frame.slots[").append(i).append("].value) selected = &bindings.candidates[")
                        .append(candidate++).append("];\n");
            }
            text.append("      iw_gc_binding_commit(env, ").append(retaining ? "&registration, &bindings.bindings[" + i + "]" : "NULL, NULL")
                    .append(", retention_frame.slots[").append(i).append("].holder, ").append(proof.fields().indexOf(slot.field()))
                    .append(", retention_frame.slots[").append(i).append("].value, selected); }\n");
        }
        if (retaining) text.append("    if (registration.owner->closed) iw_gc_registration_clear(env, registration.owner);\n");
    }

    static Sources generate(BridgeGcCallbacks proof, BridgePermanentJavaSources.Sources java, BridgeGeneration generation) {

        var text = new StringBuilder();
        var fields = proof.fields();
        for (var field : fields) text.append("extern int32_t ").append(proof.detachers().get(field).linkageName()).append("(void *, void *);\n");
        text.append("__attribute__((unused)) static void iw_gc_binding_detach(int field, void *holder, void *proxy) {\n")
                .append("    (void)holder; (void)proxy;\n    switch (field) {\n");
        for (int i = 0; i < fields.size(); i++) text.append("    case ").append(i).append(": (void)")
                .append(proof.detachers().get(fields.get(i)).linkageName()).append("(holder, proxy); return;\n");
        text.append("    default: iw_gc_transition_refuse();\n    }\n}\n").append(CORE);
        int registration = java.declarations().generatedTypes().indexOf(generation.supportPackage() + ".ListenerRegistration");
        if (registration < 0) throw new IllegalArgumentException("missing generated listener registration");
        text.append("static int iw_gc_registration_metadata_init(JNIEnv *env, jclass *classes) {\n")
                .append("    iw_gc_registration_type = (*env)->NewGlobalRef(env, classes[").append(registration).append("]);\n")
                .append("    if (iw_gc_registration_type == NULL) return 0;\n")
                .append("    iw_gc_registration_init = (*env)->GetMethodID(env, iw_gc_registration_type, \"<init>\", \"(J)V\");\n")
                .append("    return iw_gc_registration_init != NULL;\n}\n")
                .append("static void iw_gc_registration_metadata_dispose(JNIEnv *env) {\n")
                .append("    if (iw_gc_registration_type != NULL) (*env)->DeleteGlobalRef(env, iw_gc_registration_type);\n")
                .append("    iw_gc_registration_type = NULL;\n}\n");
        return new Sources(text.toString(), List.of(
                new BridgePermanentNativeSources.Adapter(new BridgeJavaSources.NativeDeclaration(generation.supportPackage() + ".Listeners", "$open",
                        "()L" + generation.supportPackage().replace('.', '/') + "/ListenerRegistration;"), "iw_gc_registration_open"),
                new BridgePermanentNativeSources.Adapter(new BridgeJavaSources.NativeDeclaration(generation.supportPackage() + ".ListenerRegistration", "$close", "(J)V"),
                        "iw_gc_registration_close_native")));
    }

    private static final String CORE = """
            struct iw_gc_registration;
            struct iw_gc_binding {
                void *holder, *root_slot;
                struct iw_gc_foreign_root root;
                int field;
                uint64_t epoch;
                struct iw_gc_listener *listener;
                struct iw_gc_registration *owner;
                struct iw_gc_binding *previous, *next, *owner_previous, *owner_next;
            };
            struct iw_gc_registration {
                uint64_t id, active;
                unsigned closed;
                struct iw_gc_binding *bindings;
                struct iw_gc_registration *next;
            };
            static struct iw_gc_registration *iw_gc_registrations;
            static struct iw_gc_binding *iw_gc_bindings;
            static uint64_t iw_gc_next_registration = 1, iw_gc_next_registration_epoch = 1;
            static jclass iw_gc_registration_type;
            static jmethodID iw_gc_registration_init;
            static void iw_gc_binding_remove(JNIEnv *env, struct iw_gc_binding *binding) {
                if (binding->previous != NULL) binding->previous->next = binding->next;
                else iw_gc_bindings = binding->next;
                if (binding->next != NULL) binding->next->previous = binding->previous;
                if (binding->owner_previous != NULL) binding->owner_previous->owner_next = binding->owner_next;
                else binding->owner->bindings = binding->owner_next;
                if (binding->owner_next != NULL) binding->owner_next->owner_previous = binding->owner_previous;
                iw_gc_listener_release(env, binding->listener);
                iw_gc_foreign_root_close(&binding->root);
                free(binding);
            }
            static void iw_gc_registration_clear(JNIEnv *env, struct iw_gc_registration *owner) {
                while (owner->bindings != NULL) {
                    struct iw_gc_binding *binding = owner->bindings;
                    iw_gc_binding_detach(binding->field, binding->holder, binding->listener->proxy);
                    iw_gc_binding_remove(env, binding);
                }
            }
            static void iw_gc_registration_drop(struct iw_gc_registration *owner) {
                struct iw_gc_registration **cursor = &iw_gc_registrations;
                while (*cursor != NULL && *cursor != owner) cursor = &(*cursor)->next;
                if (*cursor != owner || owner->bindings != NULL || owner->active != 0) iw_gc_transition_refuse();
                *cursor = owner->next; free(owner);
            }
            static void iw_gc_registration_close_native(JNIEnv *env, jclass type, jlong id) {
                (void)type;
                struct iw_gc_registration *owner = iw_gc_registrations;
                while (owner != NULL && owner->id != (uint64_t)id) owner = owner->next;
                if (owner == NULL || owner->closed) return;
                owner->closed = 1;
                iw_gc_registration_clear(env, owner);
                if (owner->active == 0) iw_gc_registration_drop(owner);
            }
            static jobject iw_gc_registration_open(JNIEnv *env, jclass type) {
                (void)type;
                if (iw_gc_next_registration == UINT64_MAX) {
                    iw_exception_error(env, &iw_exceptions, 1, "registration identity exhausted"); return NULL;
                }
                struct iw_gc_registration *owner = calloc(1, sizeof(*owner));
                if (owner == NULL) { iw_exception_error(env, &iw_exceptions, 1, "registration reservation failed"); return NULL; }
                owner->id = iw_gc_next_registration++;
                owner->next = iw_gc_registrations; iw_gc_registrations = owner;
                jobject result = (*env)->NewObject(env, iw_gc_registration_type, iw_gc_registration_init, (jlong)owner->id);
                if (!iw_gc_jni_acquired(env, result)) iw_gc_registration_drop(owner);
                return result;
            }
            struct iw_gc_registration_use {
                JNIEnv *env;
                struct iw_gc_registration *owner;
                uint64_t epoch;
            };
            __attribute__((unused)) static int iw_gc_registration_acquire(struct iw_gc_registration_use *use, uint64_t id) {
                struct iw_gc_registration *owner = iw_gc_registrations;
                while (owner != NULL && owner->id != id) owner = owner->next;
                if (owner == NULL || owner->closed) {
                    iw_exception_error(use->env, &iw_exceptions, 0, "closed listener registration"); return 0;
                }
                if (owner->active == UINT64_MAX || iw_gc_next_registration_epoch == UINT64_MAX) {
                    iw_exception_error(use->env, &iw_exceptions, 1, "registration ownership exhausted"); return 0;
                }
                owner->active++; use->owner = owner; use->epoch = iw_gc_next_registration_epoch++;
                return 1;
            }
            __attribute__((unused)) static void iw_gc_registration_use_close(struct iw_gc_registration_use *use) {
                if (use->owner == NULL) return;
                if (use->owner->active == 0) iw_gc_transition_refuse();
                use->owner->active--;
                if (use->owner->closed && use->owner->active == 0) {
                    iw_gc_registration_clear(use->env, use->owner);
                    iw_gc_registration_drop(use->owner);
                }
            }
            __attribute__((unused)) static struct iw_gc_binding *iw_gc_binding_reserve(JNIEnv *env) {
                struct iw_gc_binding *binding = calloc(1, sizeof(*binding));
                if (binding == NULL) { iw_exception_error(env, &iw_exceptions, 1, "listener slot reservation failed"); return NULL; }
                binding->root_slot = &binding->holder;
                if (!iw_gc_foreign_root_open(&binding->root, &binding->root_slot, 1)) iw_gc_transition_refuse();
                return binding;
            }
            struct iw_gc_binding_reservations {
                JNIEnv *env;
                struct iw_gc_binding **bindings;
                size_t count;
                struct iw_gc_listener **candidates;
                size_t candidate_count;
                void **storage;
            };
            __attribute__((unused)) static int iw_gc_binding_reservations_prepare(struct iw_gc_binding_reservations *reserved,
                    size_t count, size_t candidates) {
                if (count > SIZE_MAX - candidates || count + candidates > SIZE_MAX / sizeof(void *)) {
                    iw_exception_error(reserved->env, &iw_exceptions, 1, "listener reservation extent"); return 0;
                }
                reserved->storage = calloc(count + candidates, sizeof(void *));
                if (reserved->storage == NULL) { iw_exception_error(reserved->env, &iw_exceptions, 1, "listener reservation failed"); return 0; }
                reserved->bindings = (struct iw_gc_binding **)reserved->storage;
                reserved->candidates = (struct iw_gc_listener **)(reserved->storage + count);
                reserved->count = count; reserved->candidate_count = candidates;
                return 1;
            }
            __attribute__((unused)) static void iw_gc_binding_reservations_close(struct iw_gc_binding_reservations *reserved) {
                for (size_t i = 0; i < reserved->candidate_count; i++) iw_gc_listener_release(reserved->env, reserved->candidates[i]);
                for (size_t i = 0; i < reserved->count; i++) if (reserved->bindings[i] != NULL) {
                    iw_gc_foreign_root_close(&reserved->bindings[i]->root);
                    free(reserved->bindings[i]);
                }
                free(reserved->storage);
            }
            __attribute__((unused)) static void iw_gc_binding_commit(JNIEnv *env, struct iw_gc_registration_use *use,
                    struct iw_gc_binding **reserved, void *holder, int field, void *proxy, struct iw_gc_listener **candidate) {
                if (holder == NULL) return;
                struct iw_gc_binding *previous = iw_gc_bindings;
                while (previous != NULL && (previous->holder != holder || previous->field != field)) previous = previous->next;
                if (proxy == NULL) {
                    if (previous != NULL) iw_gc_binding_remove(env, previous);
                    return;
                }
                // A matching binding installed by nested Java reentry takes
                // precedence over this older invocation's matching input.
                if (previous != NULL && previous->listener->proxy == proxy
                        && (use == NULL || previous->epoch > use->epoch || candidate == NULL)) return;
                if (use == NULL || candidate == NULL || *candidate == NULL || (*candidate)->proxy != proxy) iw_gc_transition_refuse();
                struct iw_gc_binding *binding = *reserved;
                if (binding == NULL) iw_gc_transition_refuse();
                *reserved = NULL;
                binding->holder = holder; binding->field = field; binding->epoch = use->epoch;
                binding->listener = *candidate; *candidate = NULL; binding->owner = use->owner;
                if (previous != NULL) iw_gc_binding_remove(env, previous);
                binding->next = iw_gc_bindings;
                if (iw_gc_bindings != NULL) iw_gc_bindings->previous = binding;
                iw_gc_bindings = binding;
                binding->owner_next = use->owner->bindings;
                if (use->owner->bindings != NULL) use->owner->bindings->owner_previous = binding;
                use->owner->bindings = binding;
            }
            """;
}

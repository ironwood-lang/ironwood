// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.ir.IrCallableKind;

import java.util.List;
import java.util.TreeSet;

/** Bounded JNI reconciliation derived only from the final typed entry's slot contract. */
final class BridgeRootRetentionSources {
    private BridgeRootRetentionSources() {}

    static List<BridgeRetentionContract.Slot> slots(BridgeObjectAdmission admission, BridgeCallableId callable) {
        return admission.roots().map(proof -> proof.protocol().entries().get(callable))
                .map(BridgeRetentionContract::slots).orElse(List.of());
    }

    static void prepare(StringBuilder text, BridgeObjectAdmission admission, BridgeCallableId callable, boolean instance) {
        var slots = slots(admission, callable);
        if (slots.isEmpty()) return;
        var inputs = new TreeSet<Integer>();
        slots.forEach(slot -> { inputs.add(slot.holderInput()); inputs.addAll(slot.valueInputs()); });
        int capacity = inputs.size() + slots.size();
        text.append("    struct iw_retention_root retention_roots[").append(capacity).append("] = {0}; int retention_count = 0;\n")
                .append("    struct iw_retention_change retention_changes[").append(slots.size()).append("] = {0}; int retention_changes_count = 0;\n")
                .append("    unsigned char retention_possible[").append(slots.size()).append("][").append(capacity).append("] = {0};\n")
                .append("    int retention_payload[").append(slots.size()).append("];\n")
                .append("    if ((*env)->EnsureLocalCapacity(env, ").append(slots.size() + 8).append(") != JNI_OK) goto preparation_failed;\n");
        for (int input : inputs) {
            text.append("    int retention_input").append(input).append(" = iw_retention_add(env, retention_roots, &retention_count, ")
                    .append(input == 0 && (instance || callable.kind() == IrCallableKind.CONSTRUCTOR) ? "root_state" : "root" + input).append(");\n");
        }
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            int field = admission.roots().orElseThrow().protocol().rootSlots().get(BridgeGenericDomain.storage(callable.parameters().get(slot.holderInput()))).indexOf(slot.field());
            if (field < 0) throw new IllegalArgumentException("retention slot missing from final root layout");
            text.append("    retention_payload[").append(index).append("] = -1;\n")
                    .append("    if (retention_input").append(slot.holderInput()).append(" >= 0) {\n")
                    .append("        int at = 0;\n        while (at < retention_changes_count && (retention_changes[at].holder != retention_input")
                    .append(slot.holderInput()).append(" || retention_changes[at].field != iw_root_dependencies[").append(field).append("])) at++;\n")
                    .append("        if (at == retention_changes_count) {\n")
                    .append("            jobject old = (*env)->GetObjectField(env, retention_roots[retention_input").append(slot.holderInput())
                    .append("].state, iw_root_dependencies[").append(field).append("]);\n")
                    .append("            if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n")
                    .append("            int prior = iw_retention_add(env, retention_roots, &retention_count, old);\n")
                    .append("            retention_changes[retention_changes_count++] = (struct iw_retention_change){retention_input")
                    .append(slot.holderInput()).append(", iw_root_dependencies[").append(field).append("], prior, prior};\n        }\n")
                    .append("        retention_payload[").append(index).append("] = at;\n");
            for (int value : slot.valueInputs().stream().sorted().toList()) {
                text.append("        if (retention_input").append(value).append(" >= 0) retention_possible[at][retention_input").append(value).append("] = 1;\n");
            }
            text.append("    }\n");
        }
        text.append("    for (int root = 0; root < retention_count; root++) {\n        int maximum = 0;\n")
                .append("        for (int slot = 0; slot < retention_changes_count; slot++) maximum += retention_possible[slot][root];\n")
                .append("        if (retention_roots[root].count > INT64_MAX - maximum) {\n")
                .append("            (*env)->ThrowNew(env, iw_root_lifetime, \"incoming root count headroom\"); goto preparation_failed;\n        }\n    }\n");
    }

    static void commit(StringBuilder text, BridgeObjectAdmission admission, BridgeCallableId callable, boolean instance) {
        var slots = slots(admission, callable);
        if (slots.isEmpty()) return;
        // Aliased holder arguments share one physical field. Its final value can originate
        // in another payload entry, so resolve against the complete admitted input set.
        var values = slots.stream().flatMap(slot -> slot.valueInputs().stream()).distinct().sorted().toList();
        for (int index = 0; index < slots.size(); index++) {
            text.append("    if (retention_payload[").append(index).append("] >= 0 && retention_frame.slots[").append(index).append("].holder != NULL) {\n")
                    .append("        struct iw_retention_change *change = &retention_changes[retention_payload[").append(index).append("]];\n")
                    .append("        void *value = retention_frame.slots[").append(index).append("].value;\n")
                    .append("        if (value == NULL) change->next = -1;\n");
            for (int value : values) {
                String address = value == 0 && instance ? "(void *)(uintptr_t)arg0" : "reference" + value;
                if (value == 0 && callable.kind() == IrCallableKind.CONSTRUCTOR) {
                    throw new IllegalArgumentException("new root cannot be its own acyclic retention value");
                }
                text.append("        else if (value == ").append(address).append(") change->next = retention_input").append(value).append(";\n");
            }
            // An unchanged slot can hold a borrowed child whose address differs from its root's address.
            // The final proof permits no other non-input value to enter this same slot.
            text.append("        else if (change->old < 0) abort();\n    }\n");
        }
        text.append("    iw_retention_commit(env, retention_roots, retention_changes, retention_changes_count);\n");
    }

    static final String HELPERS = """
            struct iw_retention_root { jobject state; jlong count; };
            struct iw_retention_change { int holder; jfieldID field; int old, next; };
            static int iw_retention_add(JNIEnv *env, struct iw_retention_root *roots, int *count, jobject state) {
                if (state == NULL) return -1;
                for (int index = 0; index < *count; index++) if ((*env)->IsSameObject(env, state, roots[index].state)) return index;
                int index = (*count)++;
                roots[index] = (struct iw_retention_root){state, (*env)->GetLongField(env, state, iw_root_incoming)};
                return index;
            }
            /* Prepared references and field IDs only. All increments precede every decrement. */
            static void iw_retention_commit(JNIEnv *env, struct iw_retention_root *roots, struct iw_retention_change *changes, int count) {
                for (int index = 0; index < count; index++) {
                    struct iw_retention_change *change = &changes[index];
                    if (change->next != change->old && change->next >= 0) {
                        struct iw_retention_root *root = &roots[change->next];
                        (*env)->SetLongField(env, root->state, iw_root_incoming, ++root->count);
                    }
                }
                for (int index = 0; index < count; index++) {
                    struct iw_retention_change *change = &changes[index];
                    if (change->next != change->old && change->old >= 0) {
                        struct iw_retention_root *root = &roots[change->old];
                        (*env)->SetLongField(env, root->state, iw_root_incoming, --root->count);
                    }
                }
                for (int index = 0; index < count; index++) {
                    struct iw_retention_change *change = &changes[index];
                    (*env)->SetObjectField(env, roots[change->holder].state, change->field,
                            change->next < 0 ? NULL : roots[change->next].state);
                }
            }
            """;
}

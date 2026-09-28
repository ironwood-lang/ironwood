// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.stream.Collectors;

/** Native registration and destruction, including proved fixed outgoing dependencies. */
public final class BridgeRootIndexSources {
    private BridgeRootIndexSources() {}

    public record Sources(String source, List<IrType> kinds) {
        public Sources { kinds = List.copyOf(kinds); }
    }

    public static Sources generate(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesObjects(artifact, admission) || admission.roots().isEmpty()) {
            throw new IllegalArgumentException("root index requires matching final root admission and generation");
        }
        var proof = admission.roots().orElseThrow();
        return generate(proof, admission.entries(), generation, java.util.Map.of(), false);
    }

    public static Sources generateOwnedCallbacks(ironwood.compiler.BridgeOwnedCallbackAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesOwnedCallbacks(admission)) {
            throw new IllegalArgumentException("callback root index requires matching native admission");
        }
        return generate(admission.lifetime(), admission.storage(), generation, admission.listenerFields(), true);
    }

    private static Sources generate(ironwood.compiler.BridgeFinalRootRetention proof, BridgeEntryModule module,
            BridgeGeneration generation, java.util.Map<IrType, List<ironwood.compiler.ir.IrField>> listenerFields, boolean callbacks) {
        if (!proof.matches(module, proof.program())) throw new IllegalArgumentException("root index lost final storage binding");
        var entries = module.destructions().stream().collect(Collectors.toMap(entry -> entry.contract().type(), entry -> entry));
        if (!entries.keySet().equals(proof.destruction().keySet())) {
            throw new IllegalArgumentException("root index requires every exact final destruction entry");
        }
        var kinds = entries.keySet().stream().sorted(java.util.Comparator.comparing(IrType::referenceName)).toList();
        var declarations = new StringBuilder(); var destruction = new StringBuilder();
        for (int index = 0; index < kinds.size(); index++) {
            String symbol = entries.get(kinds.get(index)).function().linkageName();
            declarations.append("extern void ").append(symbol).append("(void *);\n");
            destruction.append("        case ").append(index).append(": ").append(symbol).append("(record->address); break;\n");
        }
        int capacity = proof.protocol().rootSlots().values().stream().mapToInt(List::size).max().orElse(0);
        var metadata = new StringBuilder();
        if (capacity != 0) {
            declarations.append("static jfieldID iw_root_incoming, iw_root_dependencies[").append(capacity).append("];\n")
                    .append("static const int iw_root_slot_counts[] = {").append(kinds.stream()
                            .map(kind -> Integer.toString(proof.protocol().rootSlots().getOrDefault(kind, List.of()).size()))
                            .collect(Collectors.joining(", "))).append("};\n");
            metadata.append("    iw_root_incoming = (*env)->GetFieldID(env, state, \"incoming\", \"J\");\n")
                    .append("    if (iw_root_incoming == NULL) goto failed;\n");
            for (int index = 0; index < capacity; index++) {
                metadata.append("    iw_root_dependencies[").append(index).append("] = (*env)->GetFieldID(env, state, \"dependency")
                        .append(index).append("\", \"L").append(generation.supportPackage().replace('.', '/')).append("/RootState;\");\n")
                        .append("    if (iw_root_dependencies[").append(index).append("] == NULL) goto failed;\n");
            }
        }
        String prepare = capacity == 0 ? "" : """
                    int count = iw_root_slot_counts[record->kind];
                    jobject outgoing[@CAPACITY@] = {0};
                    if ((*env)->EnsureLocalCapacity(env, count + 8) != JNI_OK) return;
                    for (int index = 0; index < count; index++) {
                        outgoing[index] = (*env)->GetObjectField(env, record->state, iw_root_dependencies[index]);
                        if ((*env)->ExceptionCheck(env)) return;
                    }
                """.replace("@CAPACITY@", Integer.toString(capacity));
        String release = capacity == 0 ? "" : """
                    for (int index = 0; index < count; index++) {
                        if (outgoing[index] != NULL) {
                            jlong incoming = (*env)->GetLongField(env, outgoing[index], iw_root_incoming);
                            (*env)->SetLongField(env, outgoing[index], iw_root_incoming, incoming - 1);
                        }
                        (*env)->SetObjectField(env, record->state, iw_root_dependencies[index], NULL);
                    }
                """;
        int listenerCapacity = listenerFields.values().stream().mapToInt(List::size).max().orElse(0);
        if (callbacks) {
            declarations.append("static jfieldID iw_root_listener_owner;\nstatic const int iw_root_listener_counts[] = {")
                    .append(kinds.stream().map(kind -> Integer.toString(listenerFields.get(kind).size())).collect(Collectors.joining(", ")))
                    .append("};\n");
            metadata.append("    iw_root_listener_owner = (*env)->GetFieldID(env, state, \"listenerOwner\", \"J\");\n")
                    .append("    if (iw_root_listener_owner == NULL) goto failed;\n");
        }
        return new Sources(TEMPLATE.replace("@DECLARATIONS@", declarations).replace("@DESTRUCTION@", destruction)
                .replace("@SLOT_METADATA@", metadata).replace("@PREPARE_OUTGOING@", prepare).replace("@RELEASE_OUTGOING@", release)
                .replace("@LISTENER_FIELDS@", callbacks ? "struct iw_listener_owner callbacks; struct iw_listener_slot *listeners["
                        + Math.max(1, listenerCapacity) + "];" : "")
                .replace("@LISTENER_DISPOSE@", callbacks ? "iw_root_listener_owner = NULL;" : "")
                .replace("@LISTENER_PUBLISH@", callbacks ? "(*env)->SetLongField(env, record->state, iw_root_listener_owner, (jlong)(uintptr_t)record);" : "")
                .replace("@LISTENER_RELEASE@", callbacks ? "for (int index = 0; index < iw_root_listener_counts[record->kind]; index++) "
                        + "iw_listener_slot_release(env, record->listeners[index]);" : "")
                + (capacity == 0 ? "" : BridgeRootRetentionSources.HELPERS), kinds);
    }

    private static final String TEMPLATE = """
            /* SPDX-License-Identifier: MIT OR Apache-2.0 */
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>

            @DECLARATIONS@
            struct iw_root_record { void *address; jobject state; int kind; @LISTENER_FIELDS@ };
            static struct iw_root_record **iw_root_table;
            static size_t iw_root_capacity, iw_root_occupied;
            #define IW_ROOT_TOMBSTONE ((struct iw_root_record *)(uintptr_t)1)
            static jfieldID iw_root_address, iw_root_status;
            static jclass iw_root_oom, iw_root_argument;

            static void iw_root_metadata_dispose(JNIEnv *env) {
                if (iw_root_oom != NULL) (*env)->DeleteGlobalRef(env, iw_root_oom);
                if (iw_root_argument != NULL) (*env)->DeleteGlobalRef(env, iw_root_argument);
                iw_root_oom = NULL; iw_root_argument = NULL;
                iw_root_address = NULL; iw_root_status = NULL;
                @LISTENER_DISPOSE@
            }

            /* The bootstrap supplies the already identity-validated RootState class. */
            static int iw_root_metadata_init(JNIEnv *env, jclass state) {
                iw_root_address = (*env)->GetFieldID(env, state, "address", "J");
                if (iw_root_address == NULL) goto failed;
                iw_root_status = (*env)->GetFieldID(env, state, "status", "I");
                if (iw_root_status == NULL) goto failed;
            @SLOT_METADATA@
                jclass local = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (local == NULL) goto failed;
                iw_root_oom = (*env)->NewGlobalRef(env, local); (*env)->DeleteLocalRef(env, local);
                if (iw_root_oom == NULL) goto failed;
                local = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
                if (local == NULL) goto failed;
                iw_root_argument = (*env)->NewGlobalRef(env, local); (*env)->DeleteLocalRef(env, local);
                if (iw_root_argument == NULL) goto failed;
                return 1;
            failed:
                iw_root_metadata_dispose(env); return 0;
            }

            static size_t iw_root_bucket(void *address, size_t capacity) {
                uintptr_t value = (uintptr_t)address >> 3;
                value ^= value >> 29; value *= UINT64_C(0x9e3779b97f4a7c15); value ^= value >> 32;
                return (size_t)value & (capacity - 1);
            }

            static void iw_root_insert(struct iw_root_record **table, size_t capacity, struct iw_root_record *record) {
                size_t at = iw_root_bucket(record->address, capacity);
                while (table[at] != NULL && table[at] != IW_ROOT_TOMBSTONE) at = (at + 1) & (capacity - 1);
                table[at] = record;
            }

            static struct iw_root_record **iw_root_find(void *address) {
                if (address == NULL || iw_root_capacity == 0) return NULL;
                size_t at = iw_root_bucket(address, iw_root_capacity);
                for (size_t inspected = 0; inspected < iw_root_capacity; inspected++) {
                    if (iw_root_table[at] == NULL) return NULL;
                    if (iw_root_table[at] != IW_ROOT_TOMBSTONE && iw_root_table[at]->address == address) return &iw_root_table[at];
                    at = (at + 1) & (iw_root_capacity - 1);
                }
                return NULL;
            }

            static int iw_root_prepare_capacity(void) {
                if (iw_root_capacity != 0 && iw_root_occupied + 1 < iw_root_capacity / 2) return 1;
                if (iw_root_capacity > SIZE_MAX / (2 * sizeof(*iw_root_table))) return 0;
                size_t next = iw_root_capacity == 0 ? 8 : iw_root_capacity * 2;
                struct iw_root_record **replacement = calloc(next, sizeof(*iw_root_table));
                if (replacement == NULL) return 0;
                for (size_t index = 0; index < iw_root_capacity; index++) {
                    if (iw_root_table[index] != NULL && iw_root_table[index] != IW_ROOT_TOMBSTONE) {
                        iw_root_insert(replacement, next, iw_root_table[index]);
                    }
                }
                free(iw_root_table); iw_root_table = replacement; iw_root_capacity = next;
                return 1;
            }

            static void iw_root_discard(JNIEnv *env, struct iw_root_record *record) {
                if (record->state != NULL) (*env)->DeleteGlobalRef(env, record->state);
                free(record);
            }

            /* Confined calls reserve at most one result root. No callback can consume this capacity. */
            static struct iw_root_record *iw_root_reserve(JNIEnv *env, jobject state, int kind) {
                if ((*env)->EnsureLocalCapacity(env, 8) != JNI_OK) return NULL;
                struct iw_root_record *record = malloc(sizeof(*record));
                if (record == NULL) { (*env)->ThrowNew(env, iw_root_oom, "native root record allocation"); return NULL; }
                *record = (struct iw_root_record){.kind = kind};
                if (!iw_root_prepare_capacity()) {
                    iw_root_discard(env, record); (*env)->ThrowNew(env, iw_root_oom, "native root index growth"); return NULL;
                }
                record->state = (*env)->NewGlobalRef(env, state);
                if (record->state == NULL) {
                    iw_root_discard(env, record);
                    if (!(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env, iw_root_oom, "native root global reference");
                    return NULL;
                }
                return record;
            }

            /* Commit: reserved storage and a pre-resolved field, no allocation or Java helper. */
            static void iw_root_publish(JNIEnv *env, struct iw_root_record *record, void *address) {
                record->address = address; iw_root_insert(iw_root_table, iw_root_capacity, record); iw_root_occupied++;
                (*env)->SetLongField(env, record->state, iw_root_address, (jlong)(uintptr_t)address);
                @LISTENER_PUBLISH@
            }

            /* Only object conversion/free uses the index. Scalar entry adapters do not call here. */
            static struct iw_root_record **iw_root_resolve(JNIEnv *env, jobject state, void *address) {
                struct iw_root_record **found = iw_root_find(address);
                if (found == NULL || !(*env)->IsSameObject(env, (*found)->state, state)) {
                    (*env)->ThrowNew(env, iw_root_argument, "native root belongs to a different world or lifetime"); return NULL;
                }
                return found;
            }

            /* Eligibility and all preparation finish before this nonthrowing destruction commit. */
            static void iw_root_destroy(JNIEnv *env, struct iw_root_record **found) {
                struct iw_root_record *record = *found;
            @PREPARE_OUTGOING@
                (*env)->SetIntField(env, record->state, iw_root_status, 1);
                switch (record->kind) {
            @DESTRUCTION@        default: abort();
                }
            @RELEASE_OUTGOING@
                @LISTENER_RELEASE@
                (*env)->SetIntField(env, record->state, iw_root_status, 2);
                *found = IW_ROOT_TOMBSTONE; iw_root_occupied--; iw_root_discard(env, record);
            }
            """;
}

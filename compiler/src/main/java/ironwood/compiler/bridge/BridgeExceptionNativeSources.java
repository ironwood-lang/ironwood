// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;

import java.util.List;

/** Generated cold JNI snapshot transport. The enclosing adapter owns bootstrap and registration. */
public final class BridgeExceptionNativeSources {
    private BridgeExceptionNativeSources() {}

    public static String generate(CompilationArtifact artifact, BridgeExceptionProjection projection, BridgeExceptionEntries entries) {
        return generate(artifact, projection, entries, null);
    }

    public static String generate(CompilationArtifact artifact, BridgeExceptionProjection projection, BridgeExceptionEntries entries,
            BridgeCallbackCarrierEntries carriers) {
        if (!artifact.valid() || !projection.matches(artifact.program().orElseThrow()) || !entries.matches(projection)) {
            throw new IllegalArgumentException("native exception transport requires matching projected entries");
        }
        if (carriers != null && !carriers.matches(artifact)) {
            throw new IllegalArgumentException("callback exception graph requires matching carrier entries");
        }
        var custom = projection.customTypes().isEmpty() ? null : BridgeCustomSnapshotLayout.create(artifact, projection);
        var declarations = new StringBuilder();
        entries.accessors().entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getValue().linkageName()))
                .forEach(entry -> declarations.append("extern int32_t ").append(entry.getValue().linkageName())
                        .append(entry.getKey().name().equals("getSecondaryException")
                                ? "(void *, int32_t, int64_t);\n" : "(void *, int64_t);\n"));
        declarations.append("extern int32_t ").append(entries.trace().linkageName()).append("(void *, int64_t);\n");
        var descriptors = new StringBuilder();
        for (var type : projection.types()) {
            String first = property(type, "getFile", "getInput", "getParsedString");
            String second = property(type, "getOtherFile");
            String third = property(type, "getReason");
            if (type.nativeName().equals("ironwood.nio.file.InvalidPathException")) { second = third; third = ""; }
            String number = property(type, "getIndex", "getErrorIndex", "bytesTransferred");
            // Custom getters populate copied slots once; Java superclass
            // placeholders do not consume the built-in constructor carriers.
            if (projection.customTypes().containsKey(type.nativeName())) { first = ""; second = ""; third = ""; number = ""; }
            List<String> strings = List.of("getMessage", first, second, third);
            int ownership = 0;
            for (int i = 0; i < strings.size(); i++) {
                String name = strings.get(i);
                if (type.properties().stream().anyMatch(value -> value.name().equals(name) && value.ownedString())) ownership |= 1 << i;
            }
            descriptors.append("    { ").append(BridgeJavaSources.quote(type.nativeName())).append(", ")
                    .append(type.typeId()).append(", {");
            for (int i = 0; i < strings.size(); i++) {
                if (i != 0) descriptors.append(", ");
                descriptors.append(symbol(type, strings.get(i), entries));
            }
            descriptors.append("}, ").append(ownership).append(", ")
                    .append(type.nativeName().equals("ironwood.nio.file.DirectoryIteratorException") ? 1 : 0).append(", ")
                    .append(symbol(type, "getCause", entries)).append(", ")
                    .append(symbol(type, "getSecondaryExceptionCount", entries)).append(", ")
                    .append(symbol(type, "getSecondaryException", entries)).append(", ")
                    .append(symbol(type, number, entries)).append(" },\n");
        }
        return TEMPLATE.replace("@DECLARATIONS@", declarations).replace("@DESCRIPTORS@", descriptors)
                .replace("@CALLBACK_TYPE@", carriers == null ? "" : carrierType(carriers))
                .replace("@TRACE@", entries.trace().linkageName())
                .replace("@NODES@", Integer.toString(BridgeExceptionGraphSources.NODE_LIMIT))
                .replace("@SECONDARY@", Integer.toString(BridgeExceptionGraphSources.SECONDARY_LIMIT))
                .replace("@FRAMES@", Integer.toString(BridgeExceptionGraphSources.NATIVE_FRAME_LIMIT))
                .replace("@CALLBACK_DESCRIPTOR@", carriers == null ? "" : "[Ljava/lang/Throwable;")
                .replace("@CALLBACK_ARGUMENTS@", carriers == null ? "" : ", originals")
                .replace("@CALLBACK_ARRAY@", carriers == null ? "" : """
                        jobjectArray originals = (*env)->NewObjectArray(env, @LIMIT@, metadata->classes[IW_EX_THROWABLE], NULL);
                        if (originals == NULL) goto done;
                        """.replace("@LIMIT@", Integer.toString(BridgeExceptionGraphSources.NODE_LIMIT)))
                .replace("@CALLBACK_CAPTURE@", carriers == null ? "" : """
                        if (!iw_exception_status(env, metadata, @REFERENCE@(nodes[index], &result))) goto node_failure;
                        if (result.value.wide != 0) {
                            (*env)->SetObjectArrayElement(env, originals, index, (jobject)(uintptr_t)result.value.wide);
                            if ((*env)->ExceptionCheck(env)) goto node_failure;
                            if (!iw_exception_status(env, metadata, @UNCHANGED@(nodes[index], &result))) goto node_failure;
                            if (result.value.wide != 0) {
                                ids[index] = 0; numbers[index] = 0; causes[index] = -1; patch_message[index] = 0;
                                (*env)->PopLocalFrame(env, NULL);
                                continue;
                            }
                            type = &iw_callback_exception_type;
                        }
                        """.replace("@REFERENCE@", carriers.reference().linkageName())
                        .replace("@UNCHANGED@", carriers.unchangedReference().linkageName()))
                .replace("@CUSTOM_CLASSES@", custom == null ? "" : "IW_EX_LONG_ARRAY, IW_EX_STRING_ARRAY, ")
                .replace("@CUSTOM_NAMES@", custom == null ? "" : ", \"[J\", \"[Ljava/lang/String;\"")
                .replace("@CUSTOM_DESCRIPTOR@", custom == null ? "" : "[[J[[Ljava/lang/String;")
                .replace("@CUSTOM_HELPERS@", custom == null ? "" : BridgeCustomSnapshotNativeSources.generate(projection, custom, entries))
                .replace("@CUSTOM_ARRAYS@", custom == null ? "" : """
                        jobjectArray copied_numbers = (*env)->NewObjectArray(env, @LIMIT@, metadata->classes[IW_EX_LONG_ARRAY], NULL);
                        if (copied_numbers == NULL) goto done;
                        jobjectArray copied_texts = (*env)->NewObjectArray(env, @LIMIT@, metadata->classes[IW_EX_STRING_ARRAY], NULL);
                        if (copied_texts == NULL) goto done;
                        """.replace("@LIMIT@", Integer.toString(BridgeExceptionGraphSources.NODE_LIMIT)))
                .replace("@CUSTOM_CAPTURE@", custom == null ? "" : "if (!iw_exception_custom(env, metadata, ids[index], nodes[index], copied_numbers, copied_texts, index, &numbers[index])) goto node_failure;")
                .replace("@CUSTOM_ARGUMENTS@", custom == null ? "" : ", copied_numbers, copied_texts");
    }

    private static String carrierType(BridgeCallbackCarrierEntries carriers) {
        return """
                extern int32_t @REFERENCE@(void *, struct ironwood_bridge_result *);
                extern int32_t @UNCHANGED@(void *, struct ironwood_bridge_result *);
                extern int32_t @CAUSE@(void *, struct ironwood_bridge_result *);
                extern int32_t @COUNT@(void *, struct ironwood_bridge_result *);
                extern int32_t @SECONDARY@(void *, int32_t, struct ironwood_bridge_result *);
                static int32_t iw_callback_cause(void *object, int64_t frame) {
                    return @CAUSE@(object, (struct ironwood_bridge_result *)(uintptr_t)frame);
                }
                static int32_t iw_callback_secondary_count(void *object, int64_t frame) {
                    return @COUNT@(object, (struct ironwood_bridge_result *)(uintptr_t)frame);
                }
                static int32_t iw_callback_secondary(void *object, int32_t index, int64_t frame) {
                    return @SECONDARY@(object, index, (struct ironwood_bridge_result *)(uintptr_t)frame);
                }
                static const struct iw_exception_type iw_callback_exception_type = {
                    NULL, 0, {NULL, NULL, NULL, NULL}, 0, 0,
                    iw_callback_cause, iw_callback_secondary_count, iw_callback_secondary, NULL
                };
                """.replace("@REFERENCE@", carriers.reference().linkageName())
                .replace("@UNCHANGED@", carriers.unchangedReference().linkageName())
                .replace("@CAUSE@", carriers.cause().linkageName())
                .replace("@COUNT@", carriers.secondaryCount().linkageName())
                .replace("@SECONDARY@", carriers.secondary().linkageName());
    }

    private static String property(BridgeExceptionProjection.Type type, String... names) {
        for (String name : names) if (type.properties().stream().anyMatch(value -> value.name().equals(name))) return name;
        return "";
    }

    private static String symbol(BridgeExceptionProjection.Type type, String name, BridgeExceptionEntries entries) {
        if (name.isEmpty()) return "NULL";
        return entries.accessors().get(type.properties().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow()).linkageName();
    }

    private static final String TEMPLATE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>
            #include <string.h>
            #include <limits.h>
            #include <stdio.h>
            #include "ironwood_bridge.h"
            @DECLARATIONS@
            typedef int32_t (*iw_exception_getter)(void *, int64_t);
            struct iw_exception_type {
                const char *name;
                jint id;
                iw_exception_getter text[4];
                unsigned owned, patch_message;
                iw_exception_getter cause, secondary_count;
                int32_t (*secondary)(void *, int32_t, int64_t);
                iw_exception_getter number;
            };
            @CALLBACK_TYPE@
            static const struct iw_exception_type iw_exception_types[] = {
            @DESCRIPTORS@};
            enum { IW_EX_FACTORY, IW_EX_STRING, IW_EX_INT_ARRAY, IW_EX_FRAME, IW_EX_FRAME_ARRAY,
                   IW_EX_OOM, IW_EX_LINKAGE, IW_EX_THROWABLE, @CUSTOM_CLASSES@IW_EX_CLASS_COUNT };
            struct iw_exception_metadata {
                jclass classes[IW_EX_CLASS_COUNT];
                jmethodID graph, frame, array_failure;
                jfieldID message;
            };
            static void iw_exception_metadata_dispose(JNIEnv *env, struct iw_exception_metadata *metadata) {
                for (int i = 0; i < IW_EX_CLASS_COUNT; i++) {
                    if (metadata->classes[i] != NULL) (*env)->DeleteGlobalRef(env, metadata->classes[i]);
                }
                memset(metadata, 0, sizeof(*metadata));
            }
            // Called after identity preflight and before any user-native initialization.
            static int iw_exception_metadata_init(JNIEnv *env, jclass factory, struct iw_exception_metadata *metadata) {
                static const char *names[] = {NULL, "java/lang/String", "[I", "java/lang/StackTraceElement",
                    "[Ljava/lang/StackTraceElement;", "java/lang/OutOfMemoryError", "java/lang/LinkageError", "java/lang/Throwable"@CUSTOM_NAMES@};
                memset(metadata, 0, sizeof(*metadata));
                for (int i = 0; i < IW_EX_CLASS_COUNT; i++) {
                    jclass local = i == 0 ? factory : (*env)->FindClass(env, names[i]);
                    if (local == NULL) goto failure;
                    metadata->classes[i] = (jclass)(*env)->NewGlobalRef(env, local);
                    if (i != 0) (*env)->DeleteLocalRef(env, local);
                    if (metadata->classes[i] == NULL) goto failure;
                }
                metadata->graph = (*env)->GetStaticMethodID(env, factory, "graph",
                    "([I[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[I[[I[[Ljava/lang/StackTraceElement;@CUSTOM_DESCRIPTOR@@CALLBACK_DESCRIPTOR@)[Ljava/lang/Throwable;");
                if (metadata->graph == NULL) goto failure;
                metadata->array_failure = (*env)->GetStaticMethodID(env, factory, "arrayFailure",
                    "(Ljava/lang/Throwable;Ljava/lang/Throwable;I)Z");
                if (metadata->array_failure == NULL) goto failure;
                metadata->frame = (*env)->GetMethodID(env, metadata->classes[IW_EX_FRAME], "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
                if (metadata->frame == NULL) goto failure;
                metadata->message = (*env)->GetFieldID(env, metadata->classes[IW_EX_THROWABLE], "detailMessage", "Ljava/lang/String;");
                if (metadata->message == NULL) goto failure;
                return 1;
            failure:
                iw_exception_metadata_dispose(env, metadata);
                return 0;
            }
            static void iw_exception_error(JNIEnv *env, const struct iw_exception_metadata *metadata, int allocation, const char *message) {
                if (!(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env, metadata->classes[allocation ? IW_EX_OOM : IW_EX_LINKAGE], message);
            }
            __attribute__((unused, noinline)) static void iw_array_failure(JNIEnv *env,
                    const struct iw_exception_metadata *metadata, jthrowable primary, jthrowable failure, jint input) {
                jboolean reported = (*env)->CallStaticBooleanMethod(env, metadata->classes[IW_EX_FACTORY],
                    metadata->array_failure, primary, failure, input);
                if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); reported = JNI_FALSE; }
                if (!reported) fprintf(stderr, "Ironwood array copy-back failed at native parameter %d; diagnostics unavailable; original failure preserved\\n", input);
            }
            static int iw_exception_status(JNIEnv *env, const struct iw_exception_metadata *metadata, int32_t status) {
                if (status == 0) return 1;
                iw_exception_error(env, metadata, status == 2, "Ironwood exception snapshot extraction failed");
                return 0;
            }
            // Metadata uses ordinary UTF-8, not JNI modified UTF-8. A small stack
            // buffer covers common names; long metadata uses checked temporary storage.
            static jstring iw_exception_utf8(JNIEnv *env, const struct iw_exception_metadata *metadata, const char *bytes, size_t length) {
                if (length > INT32_MAX || length > SIZE_MAX / sizeof(jchar)) {
                    iw_exception_error(env, metadata, 1, "Ironwood exception metadata is too long"); return NULL;
                }
                jchar local[256];
                jchar *units = length <= 256 ? local : malloc(length * sizeof(jchar));
                if (units == NULL) { iw_exception_error(env, metadata, 1, "Ironwood exception metadata allocation failed"); return NULL; }
                size_t count = 0, index = 0;
                while (index < length) {
                    uint32_t value = (unsigned char)bytes[index++], minimum = 0;
                    unsigned trailing = 0;
                    if (value >= 0xc2 && value <= 0xdf) { value &= 31; trailing = 1; minimum = 0x80; }
                    else if (value >= 0xe0 && value <= 0xef) { value &= 15; trailing = 2; minimum = 0x800; }
                    else if (value >= 0xf0 && value <= 0xf4) { value &= 7; trailing = 3; minimum = 0x10000; }
                    else if (value >= 0x80) goto invalid;
                    if (length - index < trailing) goto invalid;
                    for (unsigned i = 0; i < trailing; i++) {
                        uint32_t part = (unsigned char)bytes[index++];
                        if ((part & 0xc0) != 0x80) goto invalid;
                        value = (value << 6) | (part & 63);
                    }
                    if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) goto invalid;
                    if (value >= 0x10000) { value -= 0x10000; units[count++] = (jchar)(0xd800 | (value >> 10)); value = 0xdc00 | (value & 1023); }
                    units[count++] = (jchar)value;
                }
                jstring result = (*env)->NewString(env, units, (jsize)count);
                if (units != local) free(units);
                return result;
            invalid:
                if (units != local) free(units);
                iw_exception_error(env, metadata, 0, "invalid UTF-8 in Ironwood exception metadata");
                return NULL;
            }
            static jobject iw_exception_frame(JNIEnv *env, const struct iw_exception_metadata *metadata,
                    const char *callable, const char *file, jint line) {
                const char *separator = strrchr(callable, '.');
                size_t owner_length = separator == NULL ? 0 : (size_t)(separator - callable);
                const char *method = separator == NULL ? callable : separator + 1;
                jstring owner = iw_exception_utf8(env, metadata, callable, owner_length);
                if ((*env)->ExceptionCheck(env)) return NULL;
                jstring name = iw_exception_utf8(env, metadata, method, strlen(method));
                if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, owner); return NULL; }
                jstring source = file == NULL ? NULL : iw_exception_utf8(env, metadata, file, strlen(file));
                jobject frame = NULL;
                if (!(*env)->ExceptionCheck(env)) frame = (*env)->NewObject(env, metadata->classes[IW_EX_FRAME], metadata->frame, owner, name, source, line);
                (*env)->DeleteLocalRef(env, owner); (*env)->DeleteLocalRef(env, name); (*env)->DeleteLocalRef(env, source);
                return frame;
            }
            static jobjectArray iw_exception_frames(JNIEnv *env, const struct iw_exception_metadata *metadata,
                    const struct ironwood_bridge_failure *failure) {
                if (failure->frame_count < 0 || failure->frame_count > IRONWOOD_BRIDGE_TRACE_CAPACITY) {
                    iw_exception_error(env, metadata, 0, "invalid Ironwood exception trace count"); return NULL;
                }
                int marker = failure->flags != 0;
                int count = failure->frame_count;
                if (marker && count >= @FRAMES@) count = @FRAMES@ - 1;
                jobjectArray frames = (*env)->NewObjectArray(env, count + marker, metadata->classes[IW_EX_FRAME], NULL);
                if (frames == NULL) return NULL;
                for (int i = 0; i < count + marker; i++) {
                    jobject frame;
                    if (i == count) frame = iw_exception_frame(env, metadata,
                        (failure->flags & IRONWOOD_BRIDGE_TRACE_TRUNCATED) ? "ironwood.bridge.Snapshot.nativeFramesTruncated"
                        : "ironwood.bridge.Snapshot.nativeFramesUnavailable", NULL, -1);
                    else {
                        const struct ironwood_trace_site *site = failure->frames[i];
                        if (site == NULL || site->callable == NULL) {
                            iw_exception_error(env, metadata, 0, "invalid Ironwood exception trace metadata"); return NULL;
                        }
                        frame = iw_exception_frame(env, metadata, site->callable, site->file, site->line);
                    }
                    if ((*env)->ExceptionCheck(env)) return NULL;
                    (*env)->SetObjectArrayElement(env, frames, i, frame);
                    (*env)->DeleteLocalRef(env, frame);
                    if ((*env)->ExceptionCheck(env)) return NULL;
                }
                return frames;
            }
            static jint iw_exception_index(void **nodes, int *count, void *value) {
                if (value == NULL) return -1;
                for (int i = 0; i < *count; i++) if (nodes[i] == value) return i;
                if (*count == @NODES@) return -2;
                nodes[*count] = value;
                return (*count)++;
            }
            @CUSTOM_HELPERS@
            // Cold and bounded. No native getters may bypass these protected entry calls.
            __attribute__((noinline)) static void iw_exception_translate(JNIEnv *env,
                    const struct iw_exception_metadata *metadata, void *root) {
                if ((*env)->ExceptionCheck(env)) return;
                if (root == NULL) { iw_exception_error(env, metadata, 0, "missing Ironwood exception"); return; }
                if ((*env)->PushLocalFrame(env, 32) < 0) return;
                void *nodes[@NODES@] = {root};
                jint ids[@NODES@], numbers[@NODES@], causes[@NODES@];
                unsigned patch_message[@NODES@];
                int count = 1;
                jobjectArray texts[4];
                for (int i = 0; i < 4; i++) {
                    texts[i] = (*env)->NewObjectArray(env, @NODES@, metadata->classes[IW_EX_STRING], NULL);
                    if (texts[i] == NULL) goto done;
                }
                jobjectArray secondary = (*env)->NewObjectArray(env, @NODES@, metadata->classes[IW_EX_INT_ARRAY], NULL);
                if (secondary == NULL) goto done;
                jobjectArray frames = (*env)->NewObjectArray(env, @NODES@, metadata->classes[IW_EX_FRAME_ARRAY], NULL);
                if (frames == NULL) goto done;
                @CUSTOM_ARRAYS@
                @CALLBACK_ARRAY@
                for (int index = 0; index < count; index++) {
                    if ((*env)->PushLocalFrame(env, 16) < 0) goto done;
                    struct ironwood_bridge_result result;
                    const struct iw_exception_type *type = NULL;
                    @CALLBACK_CAPTURE@
                    if (!iw_exception_status(env, metadata, @TRACE@(nodes[index], (int64_t)(uintptr_t)&result))) goto node_failure;
                    for (size_t i = 0; type == NULL && i < sizeof(iw_exception_types) / sizeof(iw_exception_types[0]); i++) {
                        if (result.failure.type_name != NULL && strcmp(result.failure.type_name, iw_exception_types[i].name) == 0) {
                            type = &iw_exception_types[i]; break;
                        }
                    }
                    if (type == NULL) { iw_exception_error(env, metadata, 0, "unmapped native Ironwood exception"); goto node_failure; }
                    ids[index] = type->id; numbers[index] = 0; patch_message[index] = type->patch_message;
                    jobjectArray trace = iw_exception_frames(env, metadata, &result.failure);
                    if ((*env)->ExceptionCheck(env)) goto node_failure;
                    (*env)->SetObjectArrayElement(env, frames, index, trace);
                    if ((*env)->ExceptionCheck(env)) goto node_failure;
                    for (int i = 0; i < 4; i++) {
                        if (type->text[i] == NULL) continue;
                        if (!iw_exception_status(env, metadata, type->text[i](nodes[index], (int64_t)(uintptr_t)&result))) goto node_failure;
                        const struct ironwood_string *string = result.value.reference;
                        jstring value = string == NULL ? NULL : (*env)->NewString(env, string->units, string->utf16_length);
                        if (type->owned & (1u << i)) ironwood_deallocate(result.value.reference);
                        if ((*env)->ExceptionCheck(env)) goto node_failure;
                        (*env)->SetObjectArrayElement(env, texts[i], index, value);
                        (*env)->DeleteLocalRef(env, value);
                        if ((*env)->ExceptionCheck(env)) goto node_failure;
                    }
                    if (type->number != NULL) {
                        if (!iw_exception_status(env, metadata, type->number(nodes[index], (int64_t)(uintptr_t)&result))) goto node_failure;
                        numbers[index] = result.value.integer;
                    }
                    @CUSTOM_CAPTURE@
                    if (!iw_exception_status(env, metadata, type->cause(nodes[index], (int64_t)(uintptr_t)&result))) goto node_failure;
                    causes[index] = iw_exception_index(nodes, &count, result.value.reference);
                    if (!iw_exception_status(env, metadata, type->secondary_count(nodes[index], (int64_t)(uintptr_t)&result))) goto node_failure;
                    jint secondary_count = result.value.integer;
                    if (secondary_count < 0) { iw_exception_error(env, metadata, 0, "invalid native secondary count"); goto node_failure; }
                    int copied = secondary_count > @SECONDARY@ ? @SECONDARY@ - 1 : secondary_count;
                    int total = secondary_count > @SECONDARY@ ? @SECONDARY@ : copied;
                    jint edges[@SECONDARY@];
                    for (int i = 0; i < copied; i++) {
                        if (!iw_exception_status(env, metadata, type->secondary(nodes[index], i, (int64_t)(uintptr_t)&result))) goto node_failure;
                        edges[i] = iw_exception_index(nodes, &count, result.value.reference);
                        if (edges[i] == -1) { iw_exception_error(env, metadata, 0, "missing native secondary exception"); goto node_failure; }
                    }
                    if (total > copied) edges[copied] = -2;
                    jintArray edge_array = (*env)->NewIntArray(env, total);
                    if (edge_array == NULL) goto node_failure;
                    (*env)->SetIntArrayRegion(env, edge_array, 0, total, edges);
                    if ((*env)->ExceptionCheck(env)) goto node_failure;
                    (*env)->SetObjectArrayElement(env, secondary, index, edge_array);
                    if ((*env)->ExceptionCheck(env)) goto node_failure;
                    (*env)->PopLocalFrame(env, NULL);
                    continue;
                node_failure:
                    (*env)->PopLocalFrame(env, NULL);
                    goto done;
                }
                // Exact type count bounds the already allocated snapshot arrays.
                jintArray primitives[3];
                jint *data[] = {ids, numbers, causes};
                for (int i = 0; i < 3; i++) {
                    primitives[i] = (*env)->NewIntArray(env, count);
                    if (primitives[i] == NULL) goto done;
                    (*env)->SetIntArrayRegion(env, primitives[i], 0, count, data[i]);
                    if ((*env)->ExceptionCheck(env)) goto done;
                }
                jobjectArray values = (jobjectArray)(*env)->CallStaticObjectMethod(env, metadata->classes[IW_EX_FACTORY], metadata->graph,
                    primitives[0], texts[0], texts[1], texts[2], texts[3], primitives[1], primitives[2], secondary, frames@CUSTOM_ARGUMENTS@@CALLBACK_ARGUMENTS@);
                if ((*env)->ExceptionCheck(env)) goto done;
                if (values == NULL || (*env)->GetArrayLength(env, values) != count) {
                    iw_exception_error(env, metadata, 0, "invalid Java exception graph result"); goto done;
                }
                for (int i = 0; i < count; i++) {
                    if (!patch_message[i]) continue;
                    jobject value = (*env)->GetObjectArrayElement(env, values, i);
                    if ((*env)->ExceptionCheck(env)) goto done;
                    jstring message = (jstring)(*env)->GetObjectArrayElement(env, texts[0], i);
                    if ((*env)->ExceptionCheck(env)) goto done;
                    (*env)->SetObjectField(env, value, metadata->message, message);
                    (*env)->DeleteLocalRef(env, value); (*env)->DeleteLocalRef(env, message);
                    if ((*env)->ExceptionCheck(env)) goto done;
                }
                jthrowable value = (jthrowable)(*env)->GetObjectArrayElement(env, values, 0);
                if (!(*env)->ExceptionCheck(env)) (*env)->Throw(env, value);
            done:
                (*env)->PopLocalFrame(env, NULL);
            }
            """;
}

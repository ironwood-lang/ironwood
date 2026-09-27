// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.List;
import java.util.stream.Collectors;

/** JNI facade conversion uses existing authoritative root records, never creates ownership state. */
final class BridgeRootFacadeNativeSources {
    private BridgeRootFacadeNativeSources() {}

    static void metadata(StringBuilder text, BridgePermanentJavaSources.Sources java, BridgeGeneration generation,
            List<BridgePermanentJavaSources.Facade> facades) {
        if (facades.isEmpty()) throw new IllegalArgumentException("root adapters require root facade metadata");
        int state = java.declarations().generatedTypes().indexOf(generation.supportPackage() + ".RootState");
        int refusal = java.declarations().generatedTypes().indexOf(generation.supportPackage() + ".BridgeLifetimeException");
        if (state < 0 || refusal < 0) throw new IllegalArgumentException("root support missing from complete class inventory");
        text.append("static jclass iw_root_types[").append(facades.size()).append("], iw_root_lifetime;\n")
                .append("static jfieldID iw_root_addresses[").append(facades.size()).append("], iw_root_owners[").append(facades.size()).append("];\n")
                .append("static jmethodID iw_root_constructors[").append(facades.size()).append("], iw_root_lookup, iw_root_remember;\n")
                .append("static const char *const iw_root_address_names[] = {")
                .append(facades.stream().map(value -> BridgeJavaSources.quote(value.addressField())).collect(Collectors.joining(", "))).append("};\n")
                .append("static const char *const iw_root_owner_names[] = {")
                .append(facades.stream().map(value -> BridgeJavaSources.quote(value.stateField())).collect(Collectors.joining(", "))).append("};\n")
                .append("static void iw_root_facade_dispose(JNIEnv *env) {\n")
                .append("    for (int index = 0; index < ").append(facades.size()).append("; index++) {\n")
                .append("        if (iw_root_types[index] != NULL) (*env)->DeleteGlobalRef(env, iw_root_types[index]);\n")
                .append("        iw_root_types[index] = NULL; iw_root_addresses[index] = NULL; iw_root_owners[index] = NULL; iw_root_constructors[index] = NULL;\n    }\n")
                .append("    if (iw_root_lifetime != NULL) (*env)->DeleteGlobalRef(env, iw_root_lifetime);\n")
                .append("    iw_root_lifetime = NULL; iw_root_lookup = NULL; iw_root_remember = NULL; iw_root_metadata_dispose(env);\n}\n")
                .append("static int iw_root_facade_init(JNIEnv *env, jclass *classes) {\n")
                .append("    if (!iw_root_metadata_init(env, classes[").append(state).append("])) return 0;\n")
                .append("    iw_root_lifetime = (*env)->NewGlobalRef(env, classes[").append(refusal).append("]);\n")
                .append("    if (iw_root_lifetime == NULL) goto failed;\n")
                .append("    iw_root_lookup = (*env)->GetMethodID(env, classes[").append(state).append("], \"lookup\", \"(J)Ljava/lang/Object;\");\n")
                .append("    if (iw_root_lookup == NULL) goto failed;\n")
                .append("    iw_root_remember = (*env)->GetMethodID(env, classes[").append(state).append("], \"remember\", \"(JLjava/lang/Object;)Ljava/lang/Object;\");\n")
                .append("    if (iw_root_remember == NULL) goto failed;\n");
        for (int index = 0; index < facades.size(); index++) {
            int type = java.declarations().generatedTypes().indexOf(facades.get(index).binaryName());
            if (type < 0) throw new IllegalArgumentException("root facade absent from complete class inventory");
            text.append("    iw_root_types[").append(index).append("] = (*env)->NewGlobalRef(env, classes[").append(type).append("]);\n")
                    .append("    if (iw_root_types[").append(index).append("] == NULL) goto failed;\n");
        }
        text.append("    return 1;\nfailed:\n    iw_root_facade_dispose(env); return 0;\n}\n")
                .append(TEMPLATE.replace("@STATE@", "L" + generation.supportPackage().replace('.', '/') + "/RootState;")
                        .replace("@CONSTRUCTOR@", facades.getFirst().constructorDescriptor()));
    }

    private static final String TEMPLATE = """
            __attribute__((unused)) static int iw_root_ready(JNIEnv *env, int index) {
                if (iw_root_addresses[index] != NULL) return 1;
                jfieldID address = (*env)->GetFieldID(env, iw_root_types[index], iw_root_address_names[index], "J");
                if (address == NULL) return 0;
                jfieldID state = (*env)->GetFieldID(env, iw_root_types[index], iw_root_owner_names[index], "@STATE@");
                if (state == NULL) return 0;
                jmethodID constructor = (*env)->GetMethodID(env, iw_root_types[index], "<init>", "@CONSTRUCTOR@");
                if (constructor == NULL) return 0;
                iw_root_owners[index] = state; iw_root_constructors[index] = constructor; iw_root_addresses[index] = address;
                return 1;
            }

            __attribute__((unused)) static int iw_root_input(JNIEnv *env, int index, jobject value, void **address, jobject *state) {
                if (value == NULL) { *address = NULL; *state = NULL; return 1; }
                if (!iw_root_ready(env, index)) return 0;
                *state = (*env)->GetObjectField(env, value, iw_root_owners[index]);
                if ((*env)->ExceptionCheck(env)) return 0;
                if ((*env)->GetIntField(env, *state, iw_root_status) != 0) {
                    (*env)->ThrowNew(env, iw_root_lifetime, "native root argument is not live"); return 0;
                }
                *address = (void *)(uintptr_t)(*env)->GetLongField(env, value, iw_root_addresses[index]);
                return 1;
            }

            __attribute__((unused)) static jobject iw_root_wrap(JNIEnv *env, int index, void *address, jobject state) {
                if (address == NULL) return NULL;
                void *owner = (void *)(uintptr_t)(*env)->GetLongField(env, state, iw_root_address);
                struct iw_root_record **found = iw_root_resolve(env, state, owner);
                if (found == NULL) return NULL;
                state = (*found)->state;
                jlong bits = (jlong)(uintptr_t)address;
                jobject existing = (*env)->CallObjectMethod(env, state, iw_root_lookup, bits);
                if ((*env)->ExceptionCheck(env)) return NULL;
                if (existing != NULL) return existing;
                if (!iw_root_ready(env, index)) return NULL;
                jobject created = (*env)->NewObject(env, iw_root_types[index], iw_root_constructors[index], bits, state, (jobject)NULL);
                if ((*env)->ExceptionCheck(env) || created == NULL) return NULL;
                jobject result = (*env)->CallObjectMethod(env, state, iw_root_remember, bits, created);
                int failed = (*env)->ExceptionCheck(env);
                (*env)->DeleteLocalRef(env, created); return failed ? NULL : result;
            }
            """;
}

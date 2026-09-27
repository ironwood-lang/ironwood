// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

/** Image-specific JNI binding. All identity checks and anchors are load-time work. */
public final class BridgeBootstrapSources {
    private BridgeBootstrapSources() {}
    private static final String BOOTSTRAP = "bootstrap(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

    /** Append to the matching generated value source; exports only the JNI hooks. */
    public static String generate(BridgeGeneration generation, BridgeGeneration.NativeBuild build,
            BridgeJavaSources java, BridgeValueNativeSources values) {
        if (!java.facadeRegistrations().isEmpty() || !java.rootDestructions().isEmpty()) throw new IllegalArgumentException("value bootstrap cannot register object helpers");
        if (!build.generation().equals(generation.identity()) || !build.api().equals(generation.apiIdentity())) {
            throw new IllegalArgumentException("bootstrap native build identity mismatch");
        }
        String support = generation.supportPackage() + ".Support", factory = generation.supportPackage() + ".ExceptionFactory";
        var types = java.generatedTypes();
        if (!types.contains(support) || !types.contains(factory) || new HashSet<>(types).size() != types.size()
                || types.size() > Integer.MAX_VALUE - 32) throw new IllegalArgumentException("bootstrap requires complete unique generated classes");
        var adapters = values.adapters().stream().collect(Collectors.toMap(BridgeValueNativeSources.Adapter::entrySymbol, value -> value));
        if (!adapters.keySet().equals(java.bindings().stream().map(BridgeJavaSources.Binding::entrySymbol).collect(Collectors.toSet()))) {
            throw new IllegalArgumentException("bootstrap Java/native binding set mismatch");
        }
        for (var binding : java.bindings()) {
            var adapter = adapters.get(binding.entrySymbol());
            if (!types.contains(binding.binaryName()) || !adapter.descriptor().equals(binding.descriptor())
                    || !binding.method().target().orElseThrow().equals(adapter.callable())) {
                throw new IllegalArgumentException("bootstrap Java/native signature mismatch");
            }
        }
        var functions = java.bindings().stream().collect(Collectors.toMap(binding -> new BridgeJavaSources.NativeDeclaration(
                binding.binaryName(), binding.nativeName(), binding.descriptor()), binding -> adapters.get(binding.entrySymbol()).functionName()));
        return generate(generation, build, java, functions, false);
    }

    public static String generate(BridgeGeneration generation, BridgeGeneration.NativeBuild build,
            BridgeJavaSources java, BridgePermanentNativeSources objects) {
        if (!objects.matches(java, generation)) throw new IllegalArgumentException("object bootstrap requires matching proved native and Java declarations");
        return generate(generation, build, java, objects.adapters().stream().collect(Collectors.toMap(
                BridgePermanentNativeSources.Adapter::declaration, BridgePermanentNativeSources.Adapter::functionName)), true);
    }

    private static String generate(BridgeGeneration generation, BridgeGeneration.NativeBuild build,
            BridgeJavaSources java, Map<BridgeJavaSources.NativeDeclaration, String> functions, boolean permanent) {
        if (!build.generation().equals(generation.identity()) || !build.api().equals(generation.apiIdentity())) {
            throw new IllegalArgumentException("bootstrap native build identity mismatch");
        }
        String support = generation.supportPackage() + ".Support", factory = generation.supportPackage() + ".ExceptionFactory";
        var types = java.generatedTypes();
        if (!types.contains(support) || !types.contains(factory) || new HashSet<>(types).size() != types.size()
                || types.size() > Integer.MAX_VALUE - 32) throw new IllegalArgumentException("bootstrap requires complete unique generated classes");
        var natives = java.nativeDeclarations();
        if (new HashSet<>(natives).size() != natives.size() || !functions.keySet().equals(new HashSet<>(natives))
                || natives.stream().anyMatch(binding -> !types.contains(binding.binaryName()))) {
            throw new IllegalArgumentException("bootstrap requires exact unique native declarations");
        }
        var declarations = new StringBuilder();
        var descriptors = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            var bindings = natives.stream().filter(binding -> binding.binaryName().equals(type)).toList();
            var signatures = new ArrayList<String>();
            if (!bindings.isEmpty()) {
                declarations.append("static JNINativeMethod iw_methods_").append(i).append("[] = {\n");
                for (var binding : bindings) {
                    declarations.append("    {").append(cString(binding.nativeName())).append(", ").append(cString(binding.descriptor()))
                            .append(", (void *)").append(functions.get(binding)).append("},\n");
                    signatures.add(binding.nativeName() + binding.descriptor());
                }
                declarations.append("};\n");
            }
            if (type.equals(support)) signatures.add(BOOTSTRAP);
            if (!signatures.isEmpty()) declarations.append("static const char *const iw_signatures_").append(i).append("[] = {")
                    .append(signatures.stream().map(BridgeBootstrapSources::cString).collect(Collectors.joining(", "))).append("};\n");
            descriptors.append("    {").append(cString(type)).append(", ").append(bindings.isEmpty() ? "NULL" : "iw_methods_" + i)
                    .append(", ").append(bindings.size()).append(", ").append(signatures.isEmpty() ? "NULL" : "iw_signatures_" + i)
                    .append(", ").append(signatures.size()).append("},\n");
        }
        return TEMPLATE.replace("@DECLARATIONS@", declarations).replace("@CLASSES@", descriptors)
                .replace("@COUNT@", Integer.toString(types.size())).replace("@SUPPORT@", Integer.toString(types.indexOf(support)))
                .replace("@FACTORY@", Integer.toString(types.indexOf(factory)))
                .replace("@SYMBOL@", "Java_" + generation.supportPackage().replace('.', '_') + "_Support_bootstrap")
                .replace("@GENERATION@", generation.identity()).replace("@SCHEMA@", BridgeGeneration.SCHEMA)
                .replace("@API@", generation.apiIdentity()).replace("@BUILD@", build.identity())
                .replace("@OBJECT_INIT@", permanent ? "if (!iw_permanent_metadata_init(env, validated)) goto unbound_failure;" : "")
                .replace("@OBJECT_DISPOSE@", permanent ? "iw_permanent_metadata_dispose(env);" : "");
    }

    /** JNI names use modified UTF-8, including supplementary identifier code units. */
    private static String cString(String value) {
        var bytes = new ArrayList<Integer>();
        for (char unit : value.toCharArray()) {
            if (unit >= 1 && unit <= 127) bytes.add((int)unit);
            else if (unit <= 2047) { bytes.add(0xc0 | unit >> 6); bytes.add(0x80 | unit & 0x3f); }
            else { bytes.add(0xe0 | unit >> 12); bytes.add(0x80 | unit >> 6 & 0x3f); bytes.add(0x80 | unit & 0x3f); }
        }
        return "\"" + bytes.stream().map(unit -> String.format("\\%03o", unit)).collect(Collectors.joining()) + "\"";
    }

    private static final String TEMPLATE = """
            extern void ironwood_bridge_bootstrap(void);
            @DECLARATIONS@
            struct iw_binding_class {
                const char *name;
                JNINativeMethod *methods;
                jint method_count;
                const char *const *signatures;
                jint signature_count;
            };
            static const struct iw_binding_class iw_binding_classes[] = {
            @CLASSES@};
            static jobject iw_loader_anchor;
            static jobjectArray iw_class_anchor;
            static int iw_bound, iw_ready;

            static void iw_boot_error(JNIEnv *env, const char *type, const char *message) {
                if ((*env)->ExceptionCheck(env)) return;
                jclass error = (*env)->FindClass(env, type);
                if (error != NULL) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
            }
            static int iw_boot_matches(JNIEnv *env, jstring actual, const char *expected) {
                if (actual == NULL) return 0;
                const char *bytes = (*env)->GetStringUTFChars(env, actual, NULL);
                if (bytes == NULL) return 0;
                int matches = strcmp(bytes, expected) == 0;
                (*env)->ReleaseStringUTFChars(env, actual, bytes);
                return matches;
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)vm; (void)reserved;
                return iw_bound ? JNI_ERR : JNI_VERSION_1_8;
            }
            JNIEXPORT void JNICALL @SYMBOL@(JNIEnv *env, jclass support, jobject loader, jobjectArray classes,
                    jstring generation, jstring schema, jstring api, jstring build) {
                if (!iw_boot_matches(env, generation, "@GENERATION@") || !iw_boot_matches(env, schema, "@SCHEMA@")
                        || !iw_boot_matches(env, api, "@API@") || !iw_boot_matches(env, build, "@BUILD@")) {
                    iw_boot_error(env, "java/lang/LinkageError", "Ironwood native generation/schema/API/build mismatch; expected @GENERATION@/@SCHEMA@/@API@/@BUILD@");
                    return;
                }
                if (loader == NULL || classes == NULL || (*env)->GetArrayLength(env, classes) != @COUNT@) {
                    iw_boot_error(env, "java/lang/LinkageError", "invalid Ironwood preflight class set"); return;
                }
                if (iw_bound && (!iw_ready || !(*env)->IsSameObject(env, loader, iw_loader_anchor))) {
                    iw_boot_error(env, "java/lang/LinkageError", "Ironwood mapped artifact already bound or failed"); return;
                }
                if ((*env)->PushLocalFrame(env, @COUNT@ + 32) < 0) return;
                jclass *validated = calloc(@COUNT@, sizeof(jclass));
                if (validated == NULL) {
                    iw_boot_error(env, "java/lang/OutOfMemoryError", "Ironwood bootstrap class storage allocation failed");
                    (*env)->PopLocalFrame(env, NULL); return;
                }
                jobject new_loader = NULL;
                jobjectArray new_anchor = NULL;
                jclass string_type = (*env)->FindClass(env, "java/lang/String");
                if (string_type == NULL) goto done;
                jmethodID verify = (*env)->GetStaticMethodID(env, support, "nativePreflight",
                    "(Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V");
                if (verify == NULL) goto done;
                for (jint index = 0; index < @COUNT@; index++) {
                    validated[index] = (*env)->GetObjectArrayElement(env, classes, index);
                    if (validated[index] == NULL) {
                        iw_boot_error(env, "java/lang/LinkageError", "missing Ironwood preflight class"); goto done;
                    }
                    if (index == @SUPPORT@ && !(*env)->IsSameObject(env, support, validated[index])) {
                        iw_boot_error(env, "java/lang/LinkageError", "Ironwood bootstrap support class mismatch"); goto done;
                    }
                    if (iw_bound) {
                        jobject original = (*env)->GetObjectArrayElement(env, iw_class_anchor, index);
                        int same = (*env)->IsSameObject(env, original, validated[index]);
                        (*env)->DeleteLocalRef(env, original);
                        if (!same) { iw_boot_error(env, "java/lang/LinkageError", "Ironwood repeated bootstrap class mismatch"); goto done; }
                        continue;
                    }
                    const struct iw_binding_class *binding = &iw_binding_classes[index];
                    jstring name = (*env)->NewStringUTF(env, binding->name);
                    if (name == NULL) goto done;
                    jobjectArray signatures = (*env)->NewObjectArray(env, binding->signature_count, string_type, NULL);
                    if (signatures == NULL) goto done;
                    for (jint method = 0; method < binding->signature_count; method++) {
                        jstring signature = (*env)->NewStringUTF(env, binding->signatures[method]);
                        if (signature == NULL) goto done;
                        (*env)->SetObjectArrayElement(env, signatures, method, signature);
                        (*env)->DeleteLocalRef(env, signature);
                        if ((*env)->ExceptionCheck(env)) goto done;
                    }
                    (*env)->CallStaticVoidMethod(env, support, verify, loader, validated[index], name, generation, signatures);
                    if ((*env)->ExceptionCheck(env)) goto done;
                    (*env)->DeleteLocalRef(env, signatures); (*env)->DeleteLocalRef(env, name);
                }
                if (iw_bound) goto done;
                if (!iw_exception_metadata_init(env, validated[@FACTORY@], &iw_exceptions)) goto done;
                @OBJECT_INIT@
                new_loader = (*env)->NewGlobalRef(env, loader);
                if (new_loader == NULL) goto unbound_failure;
                jclass class_type = (*env)->FindClass(env, "java/lang/Class");
                if (class_type == NULL) goto unbound_failure;
                jobjectArray copy = (*env)->NewObjectArray(env, @COUNT@, class_type, NULL);
                if (copy == NULL) goto unbound_failure;
                for (jint index = 0; index < @COUNT@; index++) {
                    (*env)->SetObjectArrayElement(env, copy, index, validated[index]);
                    if ((*env)->ExceptionCheck(env)) goto unbound_failure;
                }
                new_anchor = (*env)->NewGlobalRef(env, copy);
                if (new_anchor == NULL) goto unbound_failure;
                iw_loader_anchor = new_loader; iw_class_anchor = new_anchor; iw_bound = 1;
                for (jint index = 0; index < @COUNT@; index++) {
                    const struct iw_binding_class *binding = &iw_binding_classes[index];
                    if (binding->method_count == 0) continue;
                    if ((*env)->RegisterNatives(env, validated[index], binding->methods, binding->method_count) != 0) {
                        jthrowable pending = (*env)->ExceptionOccurred(env);
                        (*env)->ExceptionClear(env);
                        // Include the failing class: JNI may have bound a prefix of its methods.
                        // Every class here passed this image's complete preflight.
                        for (jint previous = 0; previous <= index; previous++) {
                            if (iw_binding_classes[previous].method_count == 0) continue;
                            (*env)->UnregisterNatives(env, validated[previous]);
                            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        }
                        if (pending != NULL) (*env)->Throw(env, pending);
                        else iw_boot_error(env, "java/lang/LinkageError", "Ironwood native registration failed");
                        goto done;
                    }
                }
                ironwood_bridge_bootstrap(); iw_ready = 1;
                goto done;
            unbound_failure:
                if (new_loader != NULL) (*env)->DeleteGlobalRef(env, new_loader);
                if (new_anchor != NULL) (*env)->DeleteGlobalRef(env, new_anchor);
                iw_exception_metadata_dispose(env, &iw_exceptions);
                @OBJECT_DISPOSE@
            done:
                free(validated);
                (*env)->PopLocalFrame(env, NULL);
            }
            """;
}

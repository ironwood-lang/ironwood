// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

/** Private P0 lifecycle fixtures. Public packaging/generation remains a later phase. */
final class BridgeLoaderFixtureSources {
    private BridgeLoaderFixtureSources() {}

    static final String SUPPORT = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package loaderfixture.@ID@;
            import java.lang.annotation.*;
            import java.lang.reflect.*;
            import java.util.*;
            @Support.Identity("@GEN@")
            public final class Support {
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                @Identity("@GEN@") public @interface Identity { String value(); }
                private static final String GENERATION = "@GEN@";
                private static final String[] ALL_TYPES = {@ALL_TYPES@};
                private static final String[] FACADES = {@FACADES@};
                private static Class<?>[] checked;
                private static boolean ready;
                private static LinkageError failed;
                private static native void bootstrap(ClassLoader loader, Class<?>[] classes, String generation);
                public static synchronized void ensure() {
                    if (ready) return;
                    if (failed != null) throw failed;
                    ClassLoader loader = Support.class.getClassLoader();
                    try {
                        Map<String, Class<?>> classes = new HashMap<>();
                        for (String name : ALL_TYPES) {
                            Class<?> type = Class.forName(name, false, loader);
                            Identity identity = type.getDeclaredAnnotation(Identity.class);
                            String observed = identity == null ? "missing identity" : identity.value();
                            if (type.getClassLoader() != loader || !GENERATION.equals(observed)) {
                                throw new LinkageError("artifact " + GENERATION + " type " + name + " expected "
                                        + GENERATION + " observed " + observed);
                            }
                            classes.put(name, type);
                        }
                        checked = new Class<?>[FACADES.length];
                        for (int index = 0; index < FACADES.length; index++) {
                            Class<?> type = classes.get(FACADES[index]);
                            Method[] natives = Arrays.stream(type.getDeclaredMethods())
                                    .filter(method -> Modifier.isNative(method.getModifiers())).toArray(Method[]::new);
                            if (natives.length != 1 || !natives[0].getName().equals("value")
                                    || !Modifier.isStatic(natives[0].getModifiers()) || natives[0].getReturnType() != int.class
                                    || !Arrays.equals(natives[0].getParameterTypes(), new Class<?>[]{int.class, int.class})) {
                                throw new LinkageError("artifact " + GENERATION + " native descriptor mismatch: " + type.getName());
                            }
                            checked[index] = type;
                        }
                        System.load(System.getProperty("bridge.fixture.@ID@"));
                        bootstrap(loader, checked, GENERATION);
                        ready = true;
                    } catch (ClassNotFoundException problem) {
                        failed = new LinkageError("artifact " + GENERATION + " missing class: " + problem.getMessage(), problem);
                        throw failed;
                    } catch (LinkageError problem) {
                        failed = problem;
                        throw problem;
                    }
                }
                // Test-only direct repeated/different-loader bootstrap, after complete preflight.
                public static void repeat(ClassLoader loader) { bootstrap(loader, checked, GENERATION); }
            }
            """;

    static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>
            #include <string.h>
            #include "ironwood_bridge.h"
            extern void ironwood_bridge_bootstrap(void);
            extern int32_t @ENTRY@(int32_t, int32_t, int64_t);
            static jobject loader_anchor;
            static int bound, ready, onloads, registrations, unregistrations, native_calls;
            static void linkage(JNIEnv *env, const char *message) {
                jclass type = (*env)->FindClass(env, "java/lang/LinkageError");
                if (type != NULL) { (*env)->ThrowNew(env, type, message); (*env)->DeleteLocalRef(env, type); }
            }
            static jint call(JNIEnv *env, jclass type, jint a, jint b) {
                (void)type;
                // Counters are confined to this P0 test adapter, never production lowering.
                native_calls++;
                struct ironwood_bridge_result result;
                int32_t status = @ENTRY@(a, b, (int64_t)(uintptr_t)&result);
                if (status != 0) { linkage(env, "unexpected scalar failure"); return 0; }
                return result.value.integer;
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)vm; (void)reserved; onloads++;
                return bound ? JNI_ERR : JNI_VERSION_1_8;
            }
            JNIEXPORT void JNICALL Java_loaderfixture_@ID@_Support_bootstrap(JNIEnv *env, jclass support,
                    jobject loader, jobjectArray classes, jstring generation) {
                (void)support;
                if (bound) {
                    if (!ready || !(*env)->IsSameObject(env, loader, loader_anchor)) linkage(env, "mapped artifact already bound");
                    return;
                }
                const char *actual = (*env)->GetStringUTFChars(env, generation, NULL);
                if (actual == NULL) return;
                int matches = strcmp(actual, "@GEN@") == 0;
                (*env)->ReleaseStringUTFChars(env, generation, actual);
                if (!matches) { linkage(env, "native generation mismatch"); return; }
                if (loader == NULL || classes == NULL || (*env)->GetArrayLength(env, classes) != @COUNT@) {
                    linkage(env, "invalid preflight class set"); return;
                }
                jclass class_type = (*env)->FindClass(env, "java/lang/Class");
                if (class_type == NULL) return;
                jmethodID get_loader = (*env)->GetMethodID(env, class_type, "getClassLoader", "()Ljava/lang/ClassLoader;");
                if (get_loader == NULL) { (*env)->DeleteLocalRef(env, class_type); return; }
                jclass validated[@COUNT@] = {0};
                for (int index = 0; index < @COUNT@; index++) {
                    validated[index] = (*env)->GetObjectArrayElement(env, classes, index);
                    if (validated[index] == NULL) { linkage(env, "missing validated class"); goto cleanup; }
                    jobject owner = (*env)->CallObjectMethod(env, validated[index], get_loader);
                    if ((*env)->ExceptionCheck(env)) goto cleanup;
                    int same = (*env)->IsSameObject(env, loader, owner);
                    (*env)->DeleteLocalRef(env, owner);
                    if (!same) { linkage(env, "preflight class loader mismatch"); goto cleanup; }
                }
                loader_anchor = (*env)->NewGlobalRef(env, loader);
                if (loader_anchor == NULL) goto cleanup;
                bound = 1;
                JNINativeMethod method = {"value", "(II)I", (void *)call};
                int registered = 0;
                for (; registered < @COUNT@; registered++) {
                    registrations++;
                    JNINativeMethod absent = {"missing", "()V", (void *)call};
                    int inject = registered == 1 && strcmp("@ID@", "a") == 0
                        && getenv("IRONWOOD_BRIDGE_FAIL_REGISTRATION") != NULL;
                    if ((*env)->RegisterNatives(env, validated[registered], inject ? &absent : &method, 1) != 0) {
                        // Only this preflighted artifact's completed registrations are eligible.
                        jthrowable pending = (*env)->ExceptionOccurred(env);
                        (*env)->ExceptionClear(env);
                        for (int index = 0; index < registered; index++) {
                            unregistrations++;
                            (*env)->UnregisterNatives(env, validated[index]);
                            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        }
                        if (pending != NULL) { (*env)->Throw(env, pending); (*env)->DeleteLocalRef(env, pending); }
                        else linkage(env, "native registration failed");
                        goto cleanup;
                    }
                }
                ironwood_bridge_bootstrap();
                ready = 1;
            cleanup:
                for (int index = 0; index < @COUNT@; index++) if (validated[index] != NULL) (*env)->DeleteLocalRef(env, validated[index]);
                (*env)->DeleteLocalRef(env, class_type);
            }
            JNIEXPORT jint bridge_fixture_count(JNIEnv *env, int index) {
                if (index == 0) return onloads;
                if (index == 1) return registrations;
                if (index == 2) return native_calls;
                if (index == 4) return unregistrations;
                return loader_anchor != NULL && (*env)->GetObjectRefType(env, loader_anchor) == JNIGlobalRefType;
            }
            """;

    static final String INSPECTOR = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <dlfcn.h>
            static JavaVM *fixture_vm;
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved; fixture_vm = vm; return JNI_VERSION_1_8;
            }
            static void *image(JNIEnv *env, jstring path) {
                const char *text = (*env)->GetStringUTFChars(env, path, NULL);
                if (text == NULL) return NULL;
                // Intentionally retain OS image references for the stale-image hook experiment.
                void *handle = dlopen(text, RTLD_NOW | RTLD_LOCAL);
                (*env)->ReleaseStringUTFChars(env, path, text);
                if (handle == NULL) {
                    jclass error = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
                    if (error != NULL) { (*env)->ThrowNew(env, error, dlerror()); (*env)->DeleteLocalRef(env, error); }
                }
                return handle;
            }
            JNIEXPORT jint JNICALL Java_BridgeLoaderDriver_count(JNIEnv *env, jclass type, jstring path, jint index) {
                (void)type; void *handle = image(env, path); if (handle == NULL) return -1;
                jint (*query)(JNIEnv *, int) = (jint (*)(JNIEnv *, int))dlsym(handle, "bridge_fixture_count");
                return query == NULL ? -1 : query(env, index);
            }
            JNIEXPORT jint JNICALL Java_BridgeLoaderDriver_reloadHook(JNIEnv *env, jclass type, jstring path) {
                (void)type; void *handle = image(env, path); if (handle == NULL) return 0;
                jint (*load)(JavaVM *, void *) = (jint (*)(JavaVM *, void *))dlsym(handle, "JNI_OnLoad");
                return load == NULL ? 0 : load(fixture_vm, NULL);
            }
            """;
}

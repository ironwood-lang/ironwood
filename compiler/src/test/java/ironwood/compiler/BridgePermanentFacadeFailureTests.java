// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Host failure injection is confined to labeled test copies of generated artifacts. */
final class BridgePermanentFacadeFailureTests {
    static final String NAME = "Java Bridge permanent facade delivery failures preserve storage and preparation cleanup";
    private BridgePermanentFacadeFailureTests() {}

    static void failures() throws Exception {
        String source = BridgePermanentFacadeNativeTests.SOURCE.replace("public Box pick(Box value) { return value; }", """
                public Box pick(Box value) { return value; }
                private static int entered;
                public static int entered() { return entered; }
                public Box withText(String text, Box value) { entered++; return value; }
                public static int strings(String first, String second) { entered++; return first.length() + second.length(); }
                """);
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Box.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("permanentnative"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("failures.jar", artifact, admission, producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var adapters = BridgePermanentNativeSources.generate(artifact, admission, generation, java);
        String cachePath = generation.supportPackage().replace('.', '/') + "/PermanentCache.java";
        String originalCache = java.declarations().sources().get(cachePath);
        String cache = originalCache.replace("private static int size;", "private static int size; private static boolean fixtureFail = Boolean.getBoolean(\"ironwood.fixture.cache\");")
                .replace("Entry entry = new Entry(address, facade);", "if (fixtureFail) { fixtureFail = false; throw new OutOfMemoryError(\"injected cache entry\"); } Entry entry = new Entry(address, facade);");
        check(!cache.equals(originalCache) && cache.contains("throw new OutOfMemoryError(\"injected cache entry\")"), "cache injection anchors changed");
        Path base = Path.of("workspace/java-bridge/evidence/p3b/permanent-host-failures").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll"); String llvmText = new LlvmEmitter().emit(admission.program()); Files.writeString(llvm, llvmText);
        Files.writeString(directory.resolve("Box.iron"), source);
        Files.writeString(directory.resolve("scope.txt"), "test-only JNI/Java allocation failures and resource counters; no production fault hooks\n"
                + "llvm=" + digest(llvmText) + "\noriginal-adapters=" + digest(adapters.source()) + "\ncache=" + digest(cache)
                + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var tools = discovery.toolchain().orElseThrow();
        var javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString());
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "injected-permanent-host-failures", "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "cache", digest(cache), "injection", digest(INJECTION), "optimization", level.toString()));
            String original = adapters.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), adapters);
            String injected = original.replace("(*env)->NewGlobalRef(env,", "fixture_global(env,")
                    .replace("(*env)->DeleteGlobalRef(env,", "fixture_delete_global(env,")
                    .replace("iw_permanent_cache = fixture_global(env,", "iw_permanent_cache = fixture_fail_global(env,")
                    .replace("(*env)->GetStringChars(env,", "fixture_chars(env,")
                    .replace("(*env)->ReleaseStringChars(env,", "fixture_release(env,")
                    .replace("(*env)->NewString(env,", "fixture_string(env,")
                    .replace("(*env)->GetFieldID(env, iw_permanent_types[index],", "fixture_field(env, iw_permanent_types[index],")
                    .replace("(*env)->NewObject(env, iw_permanent_types[index], iw_permanent_constructors[index], bits, (jobject)NULL)",
                            "fixture_object(env, iw_permanent_types[index], iw_permanent_constructors[index], bits, (jobject)NULL)");
            for (String anchor : List.of("fixture_global(env,", "fixture_delete_global(env,", "fixture_fail_global(env,", "fixture_chars(env,",
                    "fixture_release(env,", "fixture_string(env,", "fixture_field(env,", "fixture_object(env,")) {
                check(injected.contains(anchor), "missing injection: " + anchor);
            }
            injected = INJECTION + injected;
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, tools, level, generation, build, java.declarations(), injected, Map.of(cachePath, cache));
            Files.writeString(folder.resolve("adapter.sha256"), digest(injected) + "\n");
            Path consumer = folder.resolve("PermanentFailureConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (String scenario : List.of("object", "cache", "copy", "borrow", "chars", "field", "global")) {
                String site = scenario.equals("copy") || scenario.equals("borrow") ? "string" : scenario;
                var command = new ArrayList<>(List.of("/usr/bin/env", "IRONWOOD_FIXTURE_FAILURE=" + site,
                        javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m"));
                if (scenario.equals("cache")) command.add("-Dironwood.fixture.cache=true");
                command.addAll(List.of("-cp", jar + System.getProperty("path.separator") + folder, "PermanentFailureConsumer", scenario));
                String result = BridgeEntryTests.run(folder, command, "consumer-" + scenario);
                check(result.equals("permanent-host-failure-ok:" + scenario + "\n"), result);
            }
        }
        System.out.println("permanent host failure evidence: " + directory);
    }

    private static String digest(String source) { return BridgeGeneration.bytesDigest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String INJECTION = """
            /* Test-only JNI allocation and resource-accounting hooks. */
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>
            #include <string.h>
            #include "ironwood_runtime.h"
            static int fixture_used, fixture_attempts, fixture_acquired, fixture_released, fixture_globals;
            static int fixture_mode(const char *site) {
                const char *mode = getenv("IRONWOOD_FIXTURE_FAILURE");
                return mode != NULL && strcmp(mode, site) == 0;
            }
            static int fixture_failure(JNIEnv *env, const char *site) {
                if (fixture_used || !fixture_mode(site)) return 0;
                fixture_used = 1;
                jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (error != NULL) { (*env)->ThrowNew(env, error, "injected host allocation"); (*env)->DeleteLocalRef(env, error); }
                return 1;
            }
            static jobject fixture_global(JNIEnv *env, jobject value) {
                jobject result = (*env)->NewGlobalRef(env, value);
                if (result != NULL) fixture_globals++;
                return result;
            }
            static void fixture_delete_global(JNIEnv *env, jobject value) {
                if (value != NULL) fixture_globals--;
                (*env)->DeleteGlobalRef(env, value);
            }
            static jobject fixture_fail_global(JNIEnv *env, jobject value) {
                return fixture_failure(env, "global") ? NULL : fixture_global(env, value);
            }
            static const jchar *fixture_chars(JNIEnv *env, jstring value, jboolean *copy) {
                fixture_attempts++;
                if (fixture_attempts == 3 && fixture_failure(env, "chars")) return NULL;
                const jchar *result = (*env)->GetStringChars(env, value, copy);
                if (result != NULL) fixture_acquired++;
                return result;
            }
            static void fixture_release(JNIEnv *env, jstring value, const jchar *chars) {
                fixture_released++; (*env)->ReleaseStringChars(env, value, chars);
            }
            static jstring fixture_string(JNIEnv *env, const jchar *chars, jsize length) {
                return fixture_failure(env, "string") ? NULL : (*env)->NewString(env, chars, length);
            }
            static jfieldID fixture_field(JNIEnv *env, jclass type, const char *name, const char *signature) {
                return fixture_failure(env, "field") ? NULL : (*env)->GetFieldID(env, type, name, signature);
            }
            static jobject fixture_object(JNIEnv *env, jclass type, jmethodID constructor, jlong address, jobject marker) {
                return fixture_failure(env, "object") ? NULL : (*env)->NewObject(env, type, constructor, address, marker);
            }
            JNIEXPORT jlong JNICALL Java_PermanentFailureConsumer_metric(JNIEnv *env, jclass type, jint kind) {
                (void)env; (void)type;
                if (kind == 0) return (jlong)ironwood_allocation_count();
                if (kind == 1) return fixture_globals;
                if (kind == 2) return fixture_acquired;
                if (kind == 3) return fixture_released;
                return fixture_used;
            }
            """;

    private static final String CONSUMER = """
            import permanentnative.Box;
            public final class PermanentFailureConsumer {
                private static native long metric(int kind);
                private static void failure(Runnable action) {
                    try { action.run(); throw new AssertionError("missing injected failure"); }
                    catch (OutOfMemoryError expected) { check(expected.getMessage().contains("injected")); }
                }
                public static void main(String[] args) {
                    String scenario = args[0];
                    if (scenario.equals("global")) {
                        failure(() -> Box.recover());
                        check(metric(0) == 0 && metric(1) == 0 && metric(4) == 1);
                    } else if (scenario.equals("object") || scenario.equals("cache")) {
                        check(Box.recover() == 23);
                        long before = Box.allocations();
                        failure(() -> Box.cold());
                        check(Box.allocations() == before + 1);
                        Box.Child recovered = Box.cold();
                        check(recovered.number() == 31 && Box.cold() == recovered && Box.allocations() == before + 1 && Box.recover() == 23);
                    } else {
                        Box box = new Box(17, "label");
                        long live = Box.live(), before = Box.allocations(), acquired = metric(2), released = metric(3);
                        int entered = Box.entered();
                        if (scenario.equals("copy")) {
                            failure(() -> box.copy("fresh"));
                            check(Box.live() == live && box.copy("again").equals("again"));
                        } else if (scenario.equals("borrow")) {
                            failure(() -> box.text());
                            check(Box.live() == live && box.text().equals("label"));
                        } else if (scenario.equals("chars")) {
                            failure(() -> Box.strings("abc", "de"));
                            check(Box.allocations() == before && Box.entered() == entered && metric(2) == acquired + 1 && metric(3) == released + 1);
                            check(Box.strings("abc", "de") == 5 && Box.entered() == entered + 1);
                        } else if (scenario.equals("field")) {
                            failure(() -> box.withText("abc", box));
                            check(Box.allocations() == before && Box.entered() == entered && metric(2) == acquired + 1 && metric(3) == released + 1);
                            check(box.withText("abc", box) == box && Box.entered() == entered + 1);
                        } else throw new AssertionError(scenario);
                        check(metric(2) == metric(3) && metric(4) == 1 && Box.live() == live && box.number() == 17 && Box.recover() == 23);
                    }
                    System.out.println("permanent-host-failure-ok:" + scenario);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}

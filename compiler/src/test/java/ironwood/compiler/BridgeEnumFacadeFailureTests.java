// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Enum boundary failures use labeled generated-artifact copies, never production fault hooks. */
final class BridgeEnumFacadeFailureTests {
    static final String NAME = "Java Bridge enum host failures preserve preparation and delivery cleanup";
    private BridgeEnumFacadeFailureTests() {}

    static void failures() throws Exception {
        String probe = BridgeEnumFacadeNativeTests.PROBE.replace("private Probe() {}", """
                private Probe() {}
                public static Mode select(Mode value, String text) { entries++; return value; }
                """);
        var inputs = List.of(SourceFile.of("Mode.iron", BridgeEnumFacadeNativeTests.MODE), SourceFile.of("Probe.iron", probe),
                SourceFile.of("Box.iron", BridgeEnumFacadeNativeTests.BOX));
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(inputs);
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("enumjava"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("enum-failures.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var adapters = BridgePermanentNativeSources.generate(artifact, admission, generation, java);
        Path base = Path.of("workspace/java-bridge/evidence/p3b/enum-host-failures").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        for (var input : inputs) Files.writeString(directory.resolve(input.path()), input.content());
        String llvm = new LlvmEmitter().emit(admission.program()); Path program = directory.resolve("program.ll"); Files.writeString(program, llvm);
        Files.writeString(directory.resolve("scope.txt"), "test-only JNI failure/resource hooks; no production hooks\nllvm=" + digest(llvm)
                + "\noriginal-adapters=" + digest(adapters.source()) + "\ninjection=" + digest(INJECTION)
                + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow(); Path javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString());
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "injected-enum-host-failures", "llvm", digest(llvm),
                    "adapters", digest(adapters.source()), "injection", digest(INJECTION), "optimization", level.toString()));
            String original = adapters.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), adapters);
            String injected = original.replace("(*env)->NewGlobalRef(env,", "fixture_global(env,")
                    .replace("(*env)->DeleteGlobalRef(env,", "fixture_delete_global(env,")
                    .replace("iw_enum_types[1] = fixture_global(env,", "iw_enum_types[1] = fixture_fail_global(env,")
                    .replace("(*env)->GetStringChars(env,", "fixture_chars(env,")
                    .replace("(*env)->ReleaseStringChars(env,", "fixture_release(env,")
                    .replace("(*env)->GetFieldID(env, iw_enum_types[", "fixture_field(env, iw_enum_types[")
                    .replace("(*env)->GetIntField(env, value, iw_enum_tokens[", "fixture_token(env, value, iw_enum_tokens[")
                    .replace("(*env)->GetStaticFieldID(env, iw_enum_types[", "fixture_constant(env, iw_enum_types[")
                    .replace("(*env)->GetStaticObjectField(env, iw_enum_types[", "fixture_value(env, iw_enum_types[");
            // Primitive transport removes the fallible JNI field reads. Retain
            // preparation-failure coverage at the same acquired-buffer boundary.
            for (int index = 0; index < java.declarations().bindings().size(); index++) {
                var binding = java.declarations().bindings().get(index);
                if (!binding.binaryName().equals("enumjava.Probe") || !List.of("receive", "select").contains(binding.method().name())) continue;
                int start = injected.indexOf(" iw_permanent_" + index + "(");
                int end = injected.indexOf("\n}", start);
                check(start >= 0 && end > start, "missing prepared enum adapter");
                String body = injected.substring(start, end);
                String replacement = body.replaceAll("enum(\\d+) = arg\\1;", "if (!fixture_transport(env, arg$1, &enum$1)) goto preparation_failed;");
                check(!replacement.equals(body), "missing prepared enum token assignment");
                injected = injected.substring(0, start) + replacement + injected.substring(end);
            }
            check(injected.contains("fixture_transport(env, arg"), "missing paired token preparation injection");
            for (String hook : List.of("global", "delete_global", "fail_global", "chars", "release", "field", "token", "constant", "value")) {
                check(injected.contains("fixture_" + hook + "(env,"), "missing enum injection: " + hook);
            }
            injected = INJECTION + injected;
            Path jar = BridgeGeneratedJarTests.build(folder, program, toolchain, level, generation, build, java.declarations(), injected, Map.of());
            Files.writeString(folder.resolve("adapter.sha256"), digest(injected) + "\n");
            Path consumer = folder.resolve("EnumFailureConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (String scenario : List.of("global", "field", "token", "constant", "value")) {
                String output = BridgeEntryTests.run(folder, List.of("/usr/bin/env", "IRONWOOD_FIXTURE_FAILURE=" + scenario,
                        javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp", jar + System.getProperty("path.separator") + folder,
                        "EnumFailureConsumer", scenario), "consumer-" + scenario);
                check(output.equals("enum-host-failure-ok:" + scenario + "\n"), output);
            }
        }
        System.out.println("enum host failure evidence: " + directory);
    }

    private static String digest(String text) { return BridgeGeneration.bytesDigest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String INJECTION = """
            /* Test-only host metadata failures and resource counters. */
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>
            #include <string.h>
            #include "ironwood_runtime.h"
            static int fixture_used, fixture_globals, fixture_acquired, fixture_released;
            static int fixture_failure(JNIEnv *env, const char *site) {
                const char *mode = getenv("IRONWOOD_FIXTURE_FAILURE");
                if (fixture_used || mode == NULL || strcmp(mode, site) != 0) return 0;
                fixture_used = 1;
                jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (error != NULL) { (*env)->ThrowNew(env, error, "injected enum host failure"); (*env)->DeleteLocalRef(env, error); }
                return 1;
            }
            static jobject fixture_global(JNIEnv *env, jobject value) {
                jobject result = (*env)->NewGlobalRef(env, value); if (result != NULL) fixture_globals++; return result;
            }
            static int fixture_transport(JNIEnv *env, jint token, int32_t *value) {
                if (fixture_failure(env, "field") || fixture_failure(env, "token")) return 0;
                *value = token; return 1;
            }
            static void fixture_delete_global(JNIEnv *env, jobject value) {
                if (value != NULL) fixture_globals--; (*env)->DeleteGlobalRef(env, value);
            }
            static jobject fixture_fail_global(JNIEnv *env, jobject value) {
                return fixture_failure(env, "global") ? NULL : fixture_global(env, value);
            }
            static const jchar *fixture_chars(JNIEnv *env, jstring value, jboolean *copy) {
                const jchar *result = (*env)->GetStringChars(env, value, copy); if (result != NULL) fixture_acquired++; return result;
            }
            static void fixture_release(JNIEnv *env, jstring value, const jchar *chars) {
                fixture_released++; (*env)->ReleaseStringChars(env, value, chars);
            }
            static jfieldID fixture_field(JNIEnv *env, jclass type, const char *name, const char *signature) {
                return fixture_failure(env, "field") ? NULL : (*env)->GetFieldID(env, type, name, signature);
            }
            static jint fixture_token(JNIEnv *env, jobject value, jfieldID field) {
                return fixture_failure(env, "token") ? 0 : (*env)->GetIntField(env, value, field);
            }
            static jfieldID fixture_constant(JNIEnv *env, jclass type, const char *name, const char *signature) {
                return fixture_failure(env, "constant") ? NULL : (*env)->GetStaticFieldID(env, type, name, signature);
            }
            static jobject fixture_value(JNIEnv *env, jclass type, jfieldID field) {
                return fixture_failure(env, "value") ? NULL : (*env)->GetStaticObjectField(env, type, field);
            }
            JNIEXPORT jlong JNICALL Java_EnumFailureConsumer_metric(JNIEnv *env, jclass type, jint kind) {
                (void)env; (void)type;
                if (kind == 0) return (jlong)ironwood_allocation_count();
                if (kind == 1) return fixture_globals;
                if (kind == 2) return fixture_acquired;
                if (kind == 3) return fixture_released;
                return fixture_used;
            }
            """;
    private static final String CONSUMER = """
            import enumjava.Mode;
            import enumjava.Probe;
            public final class EnumFailureConsumer {
                private static native long metric(int kind);
                private static void failure(Runnable action) {
                    try { action.run(); throw new AssertionError("missing injected failure"); }
                    catch (OutOfMemoryError expected) { check(expected.getMessage().contains("injected enum")); }
                }
                public static void main(String[] args) {
                    String scenario = args[0];
                    if (scenario.equals("global")) {
                        failure(() -> Probe.entered());
                        check(metric(0) == 0 && metric(1) == 0 && metric(4) == 1);
                    } else {
                        check(Probe.entered() == 0);
                        long before = Probe.allocations(), live = Probe.live(), acquired = metric(2), released = metric(3);
                        if (scenario.equals("field") || scenario.equals("token")) {
                            failure(() -> Probe.receive(Mode.SELL, "copied"));
                            check(Probe.entered() == 0 && Probe.allocations() == before && Probe.live() == live);
                            check(metric(2) == acquired + 1 && metric(3) == released + 1);
                            check(Probe.receive(Mode.SELL, "copied") == 35 && Probe.entered() == 1);
                        } else {
                            failure(() -> Probe.select(Mode.SELL, "copied"));
                            check(Probe.entered() == 1 && Probe.live() == live);
                            check(metric(2) == acquired + 1 && metric(3) == released + 1);
                            check(Probe.select(Mode.SELL, "copied") == Mode.SELL && Probe.entered() == 2);
                        }
                        check(metric(2) == metric(3) && metric(4) == 1 && Probe.live() == live && Mode.SELL.index() == 29);
                    }
                    System.out.println("enum-host-failure-ok:" + scenario);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}

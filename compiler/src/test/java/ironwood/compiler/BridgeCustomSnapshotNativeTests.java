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

final class BridgeCustomSnapshotNativeTests {
    static final String NAME = "Java Bridge generated custom snapshots contain exact getter capture and failures";
    private BridgeCustomSnapshotNativeTests() {}

    static void snapshots() throws Exception {
        String source = BridgeCustomExceptionTests.SOURCE
                .replace("private Cases() {}", """
                        private static Detail detail = new Detail("original");
                        private static Unchecked unchecked = new Unchecked();
                        private static IllegalStateException getterFailure = new IllegalStateException("getter failed");
                        private static int codes;
                        private static int copies;
                        private Cases() {}
                        public static int fail(int kind) throws Base {
                            if (kind == 0) throw detail;
                            if (kind == 1) throw unchecked;
                            detail.fail = true;
                            throw detail;
                        }
                        public static int codes() { return codes; }
                        public static int copies() { return copies; }
                        public static long live() { return System.liveAllocationCount(); }
                        public static void recover() { detail.fail = false; }
                        """)
                .replace("return 29;", "Cases.codes++; return 29;")
                .replace("return new String(\"copy\");", "Cases.copies++; char[] units = {'A', (char) 0, (char) 0xd800, 'B'}; try { return new String(units); } finally { free units; }")
                .replace("throw null;", "throw Cases.getterFailure;")
                .replace("return 1.25;", "return -0.0;")
                .replace("public Unchecked getCause() { return null; }", "public Unchecked getCause() { return Cases.unchecked; }");
        Path base = Path.of("workspace/java-bridge/evidence/p3b/custom-native").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"); Files.writeString(directory.resolve("Cases.iron"), source);
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Cases.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("customsnap")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("snapshots.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var adapters = BridgePermanentNativeSources.generate(artifact, admission, generation, projected);
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        String llvm = new LlvmEmitter().emit(admission.program()); Path program = directory.resolve("program.ll"); Files.writeString(program, llvm);
        Files.writeString(directory.resolve("identity.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvm)
                + "\nadapters=" + digest(adapters.source()) + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "custom-snapshots", "llvm", digest(llvm),
                    "adapters", digest(adapters.source()), "optimization", level.toString()));
            Path jar = BridgeGeneratedJarTests.build(folder, program, toolchain, level, generation, build, projected.declarations(),
                    adapters.source() + BridgeBootstrapSources.generate(generation, build, projected.declarations(), adapters), Map.of());
            Path consumer = folder.resolve("CustomNativeConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (String scenario : List.of("normal", "getter", "budget3", "budget4")) {
                var command = new ArrayList<String>();
                if (scenario.startsWith("budget")) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + scenario.substring(6)));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp",
                        jar + java.io.File.pathSeparator + folder, "CustomNativeConsumer", scenario));
                String output = BridgeEntryTests.run(folder, command, "consumer-" + scenario);
                check(output.endsWith("custom-native-ok:" + scenario + "\n") && !output.contains("WARNING") && !output.contains("FATAL"), output);
            }
            BridgeEntryTests.run(folder, List.of(toolchain.clang().resolveSibling("llvm-objdump").toString(), "--disassemble",
                    folder.resolve(BridgeGeneratedJarTests.imageName()).toString()), "disassembly");
            Path faults = folder.resolve("host-failures");
            int copiedSlot = BridgeCustomSnapshotLayout.create(artifact, admission.lifetime().exceptions().projection())
                    .slot("getCopy", ironwood.compiler.ir.IrType.reference("ironwood.lang.String")).index();
            String injected = adapters.source().replace("(*env)->NewLongArray(env,", "fixture_numbers(env,")
                    .replace("(*env)->SetLongArrayRegion(env,", "fixture_bits(env,")
                    .replace("(*env)->NewString(env, string" + copiedSlot + "->", "fixture_string(env, string" + copiedSlot + "->")
                    .replace("(*env)->SetObjectArrayElement(env, texts, " + copiedSlot + ",", "fixture_text(env, texts, " + copiedSlot + ",")
                    .replace("(*env)->CallStaticObjectMethod(env, metadata->classes[IW_EX_FACTORY],", "fixture_graph(env, metadata->classes[IW_EX_FACTORY],");
            for (String hook : List.of("numbers", "bits", "string", "text", "graph")) check(injected.contains("fixture_" + hook + "(env,"), "missing host hook " + hook);
            var faultBuild = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "custom-snapshot-host-failures", "llvm", digest(llvm),
                    "adapters", digest(adapters.source()), "injected", digest(INJECTION + injected), "optimization", level.toString()));
            Path faultJar = BridgeGeneratedJarTests.build(faults, program, toolchain, level, generation, faultBuild, projected.declarations(),
                    INJECTION + injected + BridgeBootstrapSources.generate(generation, faultBuild, projected.declarations(), adapters), Map.of());
            Files.writeString(faults.resolve("scope.txt"), "test-only JNI failure injection, same final LLVM as production fixture\noriginal-adapters="
                    + digest(adapters.source()) + "\ninjected=" + digest(INJECTION + injected) + "\n");
            Path faultConsumer = faults.resolve("CustomNativeConsumer.java"); Files.writeString(faultConsumer, CONSUMER);
            BridgeEntryTests.run(faults, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", faultJar.toString(), faultConsumer.toString()), "consumer-javac");
            for (String scenario : List.of("numbers", "bits", "string", "text", "graph")) {
                String output = BridgeEntryTests.run(faults, List.of("/usr/bin/env", "IRONWOOD_FIXTURE_FAILURE=" + scenario,
                        javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp", faultJar + java.io.File.pathSeparator + faults,
                        "CustomNativeConsumer", "host"), "consumer-" + scenario);
                check(output.equals("custom-native-ok:host\n"), output);
            }
        }
        System.out.println("custom native snapshot evidence: " + directory);
    }

    private static String digest(String text) { return BridgeGeneration.bytesDigest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String CONSUMER = """
            import customsnap.Cases;
            public final class CustomNativeConsumer {
                public static void main(String[] args) throws Exception {
                    check(Cases.ping() == 42);
                    long live = Cases.live();
                    if (args[0].startsWith("budget")) {
                        try { Cases.fail(0); throw new AssertionError("allocation failure missing"); }
                        catch (OutOfMemoryError expected) { check(expected.getMessage().contains("extraction failed")); }
                        check(Cases.codes() == 1 && Cases.copies() == 1 && Cases.live() == live && Cases.ping() == 42);
                        System.out.println("custom-native-ok:" + args[0]);
                        return;
                    }
                    if (args[0].equals("getter")) {
                        try { Cases.fail(2); throw new AssertionError("throwing getter escaped fallback"); }
                        catch (LinkageError expected) { check(expected.getMessage().contains("extraction failed")); }
                        Cases.recover();
                    }
                    if (args[0].equals("host")) {
                        try { Cases.fail(0); throw new AssertionError("host failure missing"); }
                        catch (OutOfMemoryError expected) { check(expected.getMessage().equals("injected custom snapshot failure")); }
                        check(Cases.live() == live && Cases.ping() == 42);
                    }
                    int codes = Cases.codes(), copies = Cases.copies();
                    Cases.Base saved = null;
                    for (int i = 0; i < 3; i++) {
                        try { Cases.fail(0); throw new AssertionError("missing custom exception"); }
                        catch (Cases.Base expected) {
                            saved = expected;
                            var detail = (Cases.Detail) expected;
                            check(detail.getMessage().equals("detail") && detail.getBorrowed().equals("detail"));
                            check(detail.getCode() == 29 && detail.getCopy().equals("A\\u0000\\uD800B"));
                            check(Double.doubleToRawLongBits(detail.getMagnitude()) == Long.MIN_VALUE);
                            var cause = detail.getCause();
                            check(cause.isReady() && cause.getByte() == -2 && cause.getShort() == -3 && cause.getUnit() == 'x');
                            check(cause.getLong() == 12345678901L && cause.getFloat() == 1.5f);
                            check(java.util.Arrays.stream(detail.getStackTrace()).anyMatch(frame -> frame.getFileName() != null && frame.getFileName().equals("Cases.iron")));
                        }
                        check(Cases.codes() == codes + i + 1 && Cases.copies() == copies + i + 1 && Cases.live() == live);
                    }
                    for (int i = 0; i < 100; i++) check(saved.getCode() == 29);
                    check(Cases.codes() == codes + 3);
                    try { Cases.fail(1); throw new AssertionError(); }
                    catch (RuntimeException expected) { check(expected instanceof Cases.Unchecked); }
                    check(Cases.live() == live && Cases.ping() == 42);
                    System.out.println("custom-native-ok:" + args[0]);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
    private static final String INJECTION = """
            /* Test-only copied-slot JNI failures; no production fault hooks. */
            #include <jni.h>
            #include <stdlib.h>
            #include <string.h>
            #include <stdarg.h>
            static int fixture_used;
            static int fixture_failure(JNIEnv *env, const char *site) {
                const char *mode = getenv("IRONWOOD_FIXTURE_FAILURE");
                if (fixture_used || mode == NULL || strcmp(mode, site) != 0) return 0;
                fixture_used = 1;
                jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (type != NULL) { (*env)->ThrowNew(env, type, "injected custom snapshot failure"); (*env)->DeleteLocalRef(env, type); }
                return 1;
            }
            static jlongArray fixture_numbers(JNIEnv *env, jsize count) {
                return fixture_failure(env, "numbers") ? NULL : (*env)->NewLongArray(env, count);
            }
            static void fixture_bits(JNIEnv *env, jlongArray values, jsize start, jsize count, const jlong *data) {
                if (!fixture_failure(env, "bits")) (*env)->SetLongArrayRegion(env, values, start, count, data);
            }
            static jstring fixture_string(JNIEnv *env, const jchar *units, jsize count) {
                return fixture_failure(env, "string") ? NULL : (*env)->NewString(env, units, count);
            }
            static void fixture_text(JNIEnv *env, jobjectArray values, jsize index, jobject value) {
                if (!fixture_failure(env, "text")) (*env)->SetObjectArrayElement(env, values, index, value);
            }
            static jobject fixture_graph(JNIEnv *env, jclass type, jmethodID method, ...) {
                if (fixture_failure(env, "graph")) return NULL;
                va_list arguments; va_start(arguments, method);
                jobject value = (*env)->CallStaticObjectMethodV(env, type, method, arguments);
                va_end(arguments); return value;
            }
            """;
}

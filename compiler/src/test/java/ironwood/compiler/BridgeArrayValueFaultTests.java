// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Failure-only instrumentation is isolated from the production performance payload. */
final class BridgeArrayValueFaultTests {
    static final String NAME = "Java Bridge array copy-back faults preserve primary failures and reclaim results";
    private BridgeArrayValueFaultTests() {}

    static void failures() throws Exception {
        String source = """
                package arrayvaluefault;
                public final class Values {
                    private Values() {}
                    public static long live() { return System.liveAllocationCount(); }
                    public static void raise() { throw new IllegalStateException("original"); }
                    public static void write(int[] first, int[] second, boolean fail) {
                        first[0] = 11; second[0] = 22;
                        if (fail) raise();
                    }
                    public static int[] fresh(int[] first, int[] second) {
                        write(first, second, false); return new int[]{33};
                    }
                    public static String text(int[] first, String value, int[] second) {
                        write(first, second, false); return value;
                    }
                    public static String freshText(int[] first, String value, int[] second) {
                        write(first, second, false); return new String(value);
                    }
                }
                """;
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Values.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selection = BridgeExportSurface.staticValues(artifact, List.of("arrayvaluefault"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        var surface = selection.surface().orElseThrow();
        var module = BridgeEntryModule.stringValues(artifact, surface.roots());
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        check(closure.contract().isPresent(), closure.reason());
        var snapshot = closure.contract().orElseThrow();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.create("array-value-faults.jar", artifact, surface,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgeJavaSources.generate(artifact, surface, generation, module, snapshot.projection());
        var nativeSource = BridgeValueNativeSources.generate(artifact, module, snapshot.projection(), snapshot.entries());
        String injected = nativeSource.source().replace("void *elements = malloc(", "void *elements = fixture_malloc(")
                .replace("free((void *)array_state", "fixture_free((void *)array_state")
                .replace("(*env)->SetIntArrayRegion(env, arg", "fixture_copy(env, arg")
                .replace("(*env)->SetIntArrayRegion(env, copied,", "fixture_result(env, copied,")
                .replace("(*env)->NewIntArray(env, (jsize)value->length)", "fixture_new(env, (jsize)value->length)");
        check(!injected.equals(nativeSource.source()), "copy-back injection anchor missing");
        String factoryPath = generation.supportPackage().replace('.', '/') + "/ExceptionFactory.java";
        String factory = java.sources().get(factoryPath).replace("Throwable diagnostic = new IllegalStateException(",
                "if (System.getenv(\"IW_DIAGNOSTIC_OOM\") != null) throw new OutOfMemoryError(\"diagnostic injection\");\n"
                        + "            Throwable diagnostic = new IllegalStateException(");
        check(!factory.equals(java.sources().get(factoryPath)), "aggregation injection anchor missing");
        var found = LlvmToolchain.discover(null); check(found.successful(), found.error());
        Path base = Path.of("workspace/java-bridge/evidence/p7b/array-value-faults").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(snapshot.entries().program()))));
        var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "array-value-faults",
                "injection", BridgeGeneration.bytesDigest((FAULTS + injected + factory).getBytes(StandardCharsets.UTF_8))));
        Path jar = BridgeGeneratedJarTests.build(directory.resolve("O3-faults"), llvm, found.toolchain().orElseThrow(), OptimizationLevel.O3,
                generation, build, java, FAULTS + injected + BridgeBootstrapSources.generate(generation, build, java, nativeSource), Map.of(factoryPath, factory));
        Path consumer = directory.resolve("ArrayValueFaultConsumer.java"); Files.writeString(consumer, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-cp", jar.toString(),
                consumer.toString()), "javac");
        for (String mode : List.of("normal", "copy-1", "copy-2", "copy-all", "new", "result", "new-null")) {
            for (String action : mode.startsWith("copy") || mode.equals("normal") ? List.of("write", "throw", "fresh", "alias", "text", "freshText") : List.of("fresh")) {
                for (boolean diagnostics : mode.startsWith("copy") ? List.of(true, false) : List.of(true)) {
                    var command = new java.util.ArrayList<>(List.of("/usr/bin/env", "IW_ARRAY_FAULT=" + mode));
                    if (!diagnostics) command.add("IW_DIAGNOSTIC_OOM=1");
                    command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + ":" + directory,
                            "ArrayValueFaultConsumer", mode, action, Boolean.toString(diagnostics)));
                    String output = BridgeEntryTests.run(directory, command, "consumer-" + mode + "-" + action + "-" + diagnostics);
                    check(output.contains("array-value-fault-ok\narray-staging-live:0\n"), output);
                    check(!output.contains("WARNING") && !output.contains("FATAL ERROR"), output);
                    int failures = mode.equals("copy-all") && !action.equals("alias") ? 2
                            : mode.startsWith("copy") && !(mode.equals("copy-2") && action.equals("alias")) ? 1 : 0;
                    check(output.lines().filter(line -> line.contains("diagnostics unavailable; original failure preserved")).count()
                            == (diagnostics ? 0 : failures), output);
                }
            }
        }
        System.out.println("array value fault evidence: " + directory);
    }

    private static final String FAULTS = """
            #include <jni.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            static int fixture_copies, fixture_live;
            static int fixture_mode(const char *value) { const char *mode = getenv("IW_ARRAY_FAULT"); return mode && strcmp(mode, value) == 0; }
            static void fixture_error(JNIEnv *env, const char *message) {
                jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (error != NULL) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
            }
            static void *fixture_malloc(size_t size) { void *p = malloc(size); if (p) fixture_live++; return p; }
            static void fixture_free(void *p) { if (p) fixture_live--; free(p); }
            static void fixture_copy(JNIEnv *env, jintArray array, jsize start, jsize length, const jint *buffer) {
                ++fixture_copies;
                if (fixture_mode("copy-all") || (fixture_copies == 1 && fixture_mode("copy-1"))
                        || (fixture_copies == 2 && fixture_mode("copy-2"))) fixture_error(env, fixture_copies == 1 ? "copy-1" : "copy-2");
                else (*env)->SetIntArrayRegion(env, array, start, length, buffer);
            }
            static jintArray fixture_new(JNIEnv *env, jsize length) {
                if (fixture_mode("new")) { fixture_error(env, "new"); return NULL; }
                if (fixture_mode("new-null")) return NULL;
                return (*env)->NewIntArray(env, length);
            }
            static void fixture_result(JNIEnv *env, jintArray array, jsize start, jsize length, const jint *buffer) {
                if (fixture_mode("result")) fixture_error(env, "result");
                else (*env)->SetIntArrayRegion(env, array, start, length, buffer);
            }
            __attribute__((destructor)) static void fixture_done(void) {
                if (fixture_live != 0) abort(); printf("array-staging-live:0\\n");
            }
            """;

    private static final String CONSUMER = """
            import arrayvaluefault.Values;
            public final class ArrayValueFaultConsumer {
                private static void check(boolean b) { if (!b) throw new AssertionError(); }
                public static void main(String[] args) {
                    String mode = args[0], action = args[1]; boolean diagnostics = Boolean.parseBoolean(args[2]);
                    int[] first = {1}, second = action.equals("alias") ? first : new int[]{2};
                    long live = Values.live();
                    // Thrown native exceptions retain their existing process lifetime.
                    // A matching scalar failure measures that storage separately.
                    long exceptionStorage = 0;
                    if (action.equals("throw")) {
                        try { Values.raise(); } catch (IllegalStateException expected) { }
                        exceptionStorage = Values.live() - live;
                        live = Values.live();
                    }
                    int failures = mode.equals("copy-all") && first != second ? 2
                            : mode.startsWith("copy") && !(mode.equals("copy-2") && first == second) ? 1 : 0;
                    Throwable caught = null;
                    try {
                        if (action.equals("fresh")) check(Values.fresh(first, second)[0] == 33);
                        else if (action.equals("text")) check(Values.text(first, "payload", second).equals("payload"));
                        else if (action.equals("freshText")) check(Values.freshText(first, "payload", second).equals("payload"));
                        else Values.write(first, second, action.equals("throw"));
                    } catch (Throwable failure) { caught = failure; }
                    if (action.equals("throw")) check(caught instanceof IllegalStateException && caught.getMessage().equals("original"));
                    else if (failures > 0 || mode.equals("new") || mode.equals("new-null") || mode.equals("result")) check(caught instanceof OutOfMemoryError);
                    else check(caught == null);
                    if (caught != null) {
                        check(caught.getSuppressed().length == (diagnostics ? failures : 0));
                        if (diagnostics && failures > 0) {
                            int secondIndex = action.equals("text") || action.equals("freshText") ? 2 : 1;
                            int firstIndex = mode.equals("copy-2") ? secondIndex : 0;
                            check(caught.getSuppressed()[0].getMessage().endsWith(" " + firstIndex));
                            if (failures == 2) check(caught.getSuppressed()[1].getMessage().endsWith(" " + secondIndex));
                        }
                    }
                    boolean firstFailed = mode.equals("copy-1") || mode.equals("copy-all");
                    check(first[0] == (firstFailed ? 1 : first == second ? 22 : 11));
                    if (first != second) check(second[0] == (mode.equals("copy-2") || mode.equals("copy-all") ? 2 : 22));
                    check(Values.live() == live + exceptionStorage);
                    System.out.println("array-value-fault-ok");
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

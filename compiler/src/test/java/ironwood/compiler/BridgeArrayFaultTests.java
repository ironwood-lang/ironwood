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

/** Separate instrumented image; never evidence for production artifact performance. */
final class BridgeArrayFaultTests {
    static final String NAME = "Java Bridge array staging failures preserve inputs and release every acquired buffer";
    private BridgeArrayFaultTests() {}

    static void failures() throws Exception {
        String source = """
                package arrayfault;
                public final class Values {
                    private Values() {}
                    private static int entered;
                    public static int calls() { return entered; }
                    public static long live() { return System.liveAllocationCount(); }
                    public static int read(int[] first, int[] second) { entered++; return first[0] + second[0]; }
                }
                """;
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Values.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selection = BridgeExportSurface.staticValues(artifact, List.of("arrayfault"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        var surface = selection.surface().orElseThrow();
        var module = BridgeEntryModule.stringValues(artifact, surface.roots());
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        check(closure.contract().isPresent(), closure.reason());
        var snapshot = closure.contract().orElseThrow();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.create("array-faults.jar", artifact, surface,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgeJavaSources.generate(artifact, surface, generation, module, snapshot.projection());
        var nativeSource = BridgeValueNativeSources.generate(artifact, module, snapshot.projection(), snapshot.entries());
        String injected = nativeSource.source().replace("void *elements = malloc(", "void *elements = fixture_malloc(")
                .replace("free((void *)array_state", "fixture_free((void *)array_state")
                .replace("(*env)->GetIntArrayRegion(env,", "fixture_region(env,");
        check(!injected.equals(nativeSource.source()), "fault injection anchor missing");
        var found = LlvmToolchain.discover(null); check(found.successful(), found.error());
        var toolchain = found.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p7b/array-faults").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(snapshot.entries().program()))));
        var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "array-acquisition-faults",
                "injection", BridgeGeneration.bytesDigest((FAULTS + injected).getBytes(StandardCharsets.UTF_8))));
        Path jar = BridgeGeneratedJarTests.build(directory.resolve("O3-faults"), llvm, toolchain, OptimizationLevel.O3,
                generation, build, java, FAULTS + injected + BridgeBootstrapSources.generate(generation, build, java, nativeSource), Map.of());
        Path consumer = directory.resolve("ArrayFaultConsumer.java"); Files.writeString(consumer, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-cp", jar.toString(),
                consumer.toString()), "javac");
        for (String mode : List.of("normal", "allocate-1", "allocate-2", "region-1", "region-2")) {
            String output = BridgeEntryTests.run(directory, List.of("/usr/bin/env", "IW_ARRAY_FAULT=" + mode,
                    javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + ":" + directory,
                    "ArrayFaultConsumer", mode), "consumer-" + mode);
            check(output.equals("array-fault-ok:" + mode + "\narray-staging-live:0\n"), output);
        }
        System.out.println("array fault evidence: " + directory);
    }

    private static final String FAULTS = """
            #include <jni.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            static int fixture_allocations, fixture_regions, fixture_live;
            static int fixture_fails(const char *kind, int attempt) {
                const char *mode = getenv("IW_ARRAY_FAULT");
                return mode != NULL && strncmp(mode, kind, strlen(kind)) == 0 && atoi(mode + strlen(kind)) == attempt;
            }
            static void *fixture_malloc(size_t size) {
                if (fixture_fails("allocate-", ++fixture_allocations)) return NULL;
                void *value = malloc(size); if (value != NULL) fixture_live++; return value;
            }
            static void fixture_free(void *value) { if (value != NULL) fixture_live--; free(value); }
            static void fixture_region(JNIEnv *env, jintArray array, jsize start, jsize length, jint *buffer) {
                if (fixture_fails("region-", ++fixture_regions)) {
                    jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                    if (error != NULL) { (*env)->ThrowNew(env, error, "injected array acquisition"); (*env)->DeleteLocalRef(env, error); }
                } else (*env)->GetIntArrayRegion(env, array, start, length, buffer);
            }
            __attribute__((destructor)) static void fixture_done(void) {
                if (fixture_live != 0) abort();
                printf("array-staging-live:0\\n");
            }
            """;

    private static final String CONSUMER = """
            import arrayfault.Values;
            public final class ArrayFaultConsumer {
                public static void main(String[] args) {
                    int[] first = {7}, second = {11}; long live = Values.live();
                    try {
                        if (Values.read(first, second) != 18 || !args[0].equals("normal")) throw new AssertionError();
                    } catch (OutOfMemoryError expected) { if (args[0].equals("normal")) throw expected; }
                    if (first[0] != 7 || second[0] != 11 || Values.live() != live
                            || Values.calls() != (args[0].equals("normal") ? 1 : 0)) throw new AssertionError();
                    System.out.println("array-fault-ok:" + args[0]);
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** D201 fixture depths are evidence for these frames, not a general recursion guarantee. */
final class BridgeStackTests {
    private BridgeStackTests() {}

    private static final String SOURCE = """
            package stackfixture;
            final class Engine {
                // Squaring the recursive result keeps depth and seed live across a non-tail call.
                // Overflow is deliberate Java-compatible integer arithmetic, with no allocation.
                static long recurse(int depth, long seed, boolean fail) {
                    if (depth == 0) {
                        if (fail) throw null;
                        return seed + 7L;
                    }
                    long value = recurse(depth - 1, seed + 3L, fail);
                    return (value * value + depth) ^ seed;
                }
            }
            """;

    static void envelope() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("test/BridgeStack.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("stackfixture.Engine")
                        && function.sourceName().equals("recurse")).map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.scalars(artifact, roots);
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p0b/stack").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("BridgeStack.iron"), SOURCE);
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, ADAPTER.replace("ENTRY_SYMBOL", module.entries().getFirst().function().linkageName()));
        Path java = directory.resolve("BridgeStackConsumer.java");
        Files.writeString(java, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Files.writeString(directory.resolve("environment.txt"), "os=" + System.getProperty("os.name")
                + "\narch=" + System.getProperty("os.arch") + "\njava=" + System.getProperty("java.runtime.version")
                + "\njava.home=" + javaHome + "\nllvm=" + toolchain.version() + "\n");
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(),
                "-XX:+PrintFlagsFinal", "-version"), "jvm-default-flags");
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", java.toString()), "javac");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("stack-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), hash + "  " + image.getFileName() + "\n");
            String disassembly = BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            String linkage = roots.roots().getFirst().callable().linkage();
            // Both the source call and the entry call must remain in the actual target machine code.
            long calls = disassembly.lines().filter(line -> line.matches(".*\\b(bl|callq?)\\s.*") && line.contains(linkage)).count();
            check(calls >= 2, "recursive source call was lost; inspect " + directory + ": " + linkage);
            List<String> command = new ArrayList<>(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                    directory.toString(), "BridgeStackConsumer", image.toString(), "bounded"));
            String output = BridgeEntryTests.run(directory, command, "bounded-" + level);
            check(output.lines().filter(line -> line.startsWith("bounded:")).count() == 8
                    && output.endsWith("stack-envelope-ok\n"), "bounded reference failed: " + output);
            for (String size : List.of("512k", "1m")) probe(directory, javaHome, image, level, size);
        }
        System.out.println("bridge stack evidence: " + directory);
    }

    private static void probe(Path directory, Path javaHome, Path image, OptimizationLevel level, String size) throws Exception {
        int lastSuccess = 0;
        for (int depth = 512; depth <= 1048576; depth *= 2) {
            String name = "limit-" + level + "-" + size + "-" + depth;
            List<String> command = List.of("/bin/sh", "-c", "ulimit -c 0; exec \"$@\"", "bridge-stack-probe",
                    javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xss" + size,
                    "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + directory.resolve(name + "-hs_err.log"),
                    "-cp", directory.toString(), "BridgeStackConsumer", image.toString(), Integer.toString(depth));
            Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command) + "\n");
            Path log = directory.resolve(name + ".log");
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("diagnostic child timed out: " + name);
            }
            String output = Files.readString(log);
            Files.writeString(directory.resolve(name + ".exit.txt"), process.exitValue() + "\n");
            if (process.exitValue() != 0) {
                String kind = output.contains("probe-start:" + depth) ? "child-failure" : "JVM-startup-refusal";
                Files.writeString(directory.resolve("limit-summary-" + level + "-" + size + ".txt"),
                        "last-success=" + lastSuccess + "\nfirst-unsuccessful=" + depth + "\nclassification=" + kind
                                + "\nexit=" + process.exitValue() + "\nDiagnostic only; inspect child and hs_err logs.\n");
                return;
            }
            check(output.contains("probe-ok:" + depth), "probe exited without completion: " + output);
            lastSuccess = depth;
        }
        Files.writeString(directory.resolve("limit-summary-" + level + "-" + size + ".txt"),
                "last-success=" + lastSuccess + "\nclassification=no-failure-within-probe-cap\n");
    }

    private static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include <stdio.h>
            #include "ironwood_bridge.h"
            extern void ironwood_bridge_bootstrap(void);
            extern int32_t ENTRY_SYMBOL(int32_t, int64_t, uint8_t, int64_t);
            static jlong call(JNIEnv *env, jclass type, jint depth, jlong seed, jboolean fail) {
                (void)type;
                struct ironwood_bridge_result result;
                int32_t status = ENTRY_SYMBOL(depth, seed, fail, (int64_t)(uintptr_t)&result);
                if (status != 0) {
                    char message[512];
                    if (status == 1 && result.failure.frame_count > 0) {
                        const struct ironwood_trace_site *site = result.failure.frames[0];
                        snprintf(message, sizeof(message), "%s at %s(%s:%d)", result.failure.type_name,
                            site->callable, site->file, site->line);
                    } else snprintf(message, sizeof(message), "snapshot unavailable");
                    jclass error = (*env)->FindClass(env, "java/lang/RuntimeException");
                    if (error != NULL) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
                    return 0;
                }
                return result.value.wide;
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved;
                JNIEnv *env;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass type = (*env)->FindClass(env, "BridgeStackConsumer");
                if (type == NULL) return JNI_ERR;
                JNINativeMethod method = {"recurse", "(IJZ)J", (void *)call};
                if ((*env)->RegisterNatives(env, type, &method, 1) != 0) return JNI_ERR;
                (*env)->DeleteLocalRef(env, type);
                ironwood_bridge_bootstrap();
                return JNI_VERSION_1_8;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class BridgeStackConsumer {
                private static native long recurse(int depth, long seed, boolean fail);
                private static long expected(int depth, long seed) {
                    long value = seed + 3L * depth + 7L;
                    for (int level = 1; level <= depth; level++) {
                        value = (value * value + level) ^ (seed + 3L * (depth - level));
                    }
                    return value;
                }
                private static long enter(int javaDepth, int nativeDepth) {
                    if (javaDepth > 0) {
                        long live = javaDepth * 17L;
                        return enter(javaDepth - 1, nativeDepth) + live;
                    }
                    long result = recurse(nativeDepth, 19L, false);
                    if (result != expected(nativeDepth, 19L)) throw new AssertionError("checksum");
                    try { recurse(nativeDepth, 19L, true); throw new AssertionError("missing exception"); }
                    catch (RuntimeException failure) {
                        if (!failure.getMessage().contains("stackfixture.Engine.recurse(BridgeStack.iron:7)")) {
                            throw new AssertionError(failure.getMessage());
                        }
                    }
                    if (recurse(1, 19L, false) != expected(1, 19L)) throw new AssertionError("post-catch call");
                    return result;
                }
                public static void main(String[] args) {
                    System.load(args[0]);
                    if (args[1].equals("bounded")) {
                        for (int javaDepth : new int[]{0, 64}) {
                            for (int nativeDepth : new int[]{1, 8, 32, 64}) {
                                long result = enter(javaDepth, nativeDepth);
                                if (result != expected(nativeDepth, 19L) + 17L * javaDepth * (javaDepth + 1L) / 2L) {
                                    throw new AssertionError("Java live frames");
                                }
                                System.out.println("bounded:" + javaDepth + ":" + nativeDepth + ":" + result);
                            }
                        }
                        System.out.println("stack-envelope-ok");
                    } else {
                        int depth = Integer.parseInt(args[1]);
                        System.out.println("probe-start:" + depth);
                        long result = enter(0, depth);
                        System.out.println("probe-ok:" + depth + ":" + result);
                    }
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

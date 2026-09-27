// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fixed private JNI bindings exercise compiler-generated protected entries. */
final class BridgeEntryTests {
    private static final String SOURCE = """
            package entryfixture;
            final class Scalar {
                static int add(int first, int second) { return first + second; }
                static boolean invert(boolean value) { return !value; }
                static long wide(long value) { return value ^ 1234567890123L; }
                static double real(double value) { return value * 1.5; }
                static int fail() { throw null; }
                static Scalar object(Scalar value) { return value; }
                static int recursive(int depth) { return depth == 0 ? 0 : recursive(depth - 1); }
            }
            """;

    private BridgeEntryTests() {}

    private static CompilationArtifact artifact() {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("test/BridgeScalar.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeRootSet roots(CompilationArtifact artifact, Set<String> names) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("entryfixture.Scalar")
                        && names.contains(function.sourceName())).map(BridgeCallableId::of).toList());
    }

    static void typedEntries() {
        var artifact = artifact();
        var roots = roots(artifact, Set.of("add", "invert", "wide", "real", "fail"));
        var module = BridgeEntryModule.scalars(artifact, roots);
        for (var entry : module.entries()) {
            check(entry.function().blocks().stream().filter(block -> block.terminator() instanceof IrInvokeTerminator)
                    .map(block -> (IrInvokeTerminator) block.terminator()).allMatch(invoke -> invoke.unwindTarget().equals("failure")),
                    "unprotected raising operation");
            var failure = entry.function().blocks().stream().filter(block -> block.label().equals("failure"))
                    .findFirst().orElseThrow();
            check(failure.instructions().get(0) instanceof IrExceptionLandingPadInstruction
                    && failure.instructions().get(1) instanceof IrExceptionCaughtInstruction,
                    "ordinary catch/implicit-failure cleanup missing");
            var renamer = new IrCfgRenamer(value -> new IrValueReference(value.id() + 100, value.type(), value.sourceSpan()),
                    label -> "renamed." + label);
            var original = (IrBridgeResultStoreInstruction) failure.instructions().getLast();
            var renamed = (IrBridgeResultStoreInstruction) renamer.instruction(original);
            check(((IrValueReference) renamed.frameAddress()).id() == ((IrValueReference) original.frameAddress()).id() + 100,
                    "result frame SSA renaming lost");
        }
        var emitter = new LlvmEmitter();
        String ordinary = emitter.emit(artifact.program().orElseThrow());
        String llvm = emitter.emit(module);
        check(llvm.contains("define hidden i32 @\"ironwood_bridge_entry_"), "foreign symbols missing");
        check(llvm.contains("zext i1") && llvm.contains("icmp ne i8"), "boolean ABI not normalized");
        check(ordinary.equals(emitter.emit(artifact.program().orElseThrow())), "bridge state changed ordinary emission");
        var initialized = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("test/Initialized.iron", """
                package entryfixture;
                final class Initialized {
                    static int value = make();
                    static int make() { return 7; }
                    static int read() { return value; }
                }
                """)));
        check(initialized.valid(), initialized.diagnostics().toString());
        var initializedProgram = initialized.program().orElseThrow();
        var initializerModule = BridgeEntryModule.scalars(initialized, BridgeRootSet.resolve(initializedProgram,
                initializedProgram.functions().stream().filter(function -> function.ownerClass().equals("entryfixture.Initialized")
                        && function.sourceName().equals("read"))
                        .map(BridgeCallableId::of).toList()));
        var first = initializerModule.entries().getFirst().function().blocks().getFirst().terminator();
        check(first instanceof IrInvokeTerminator invoke && invoke.call() instanceof IrEnsureTypeInitializedInstruction
                && invoke.unwindTarget().equals("failure"), "real initializer work lost its protected edge");
        check(BridgeEntryModule.scalars(artifact, roots(artifact, Set.of("recursive"))).entries().size() == 1,
                "proved scalar recursion rejected");
        try {
            BridgeEntryModule.scalars(artifact, roots(artifact, Set.of("object")));
            throw new AssertionError("unimplemented reference capability admitted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("does not admit"), expected.toString());
        }
        var ordinaryArtifact = new CompilationArtifact(artifact.program(), artifact.llvmIr(), artifact.diagnostics());
        try {
            BridgeEntryModule.scalars(ordinaryArtifact, roots);
            throw new AssertionError("disabled proof option admitted native entry");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("semantic analysis"), expected.toString());
        }
    }

    static void nativeScalars() throws Exception {
        var artifact = artifact();
        var module = BridgeEntryModule.scalars(artifact, roots(artifact, Set.of("add", "invert", "wide", "real", "fail")));
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path evidenceRoot = Path.of("workspace/java-bridge/evidence/p0b/scalar-entries").toAbsolutePath();
        Files.createDirectories(evidenceRoot);
        Path directory = Files.createTempDirectory(evidenceRoot, "run-");
        Path llvm = directory.resolve("scalar.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module));
        Path java = directory.resolve("BridgeScalarConsumer.java");
        Files.writeString(java, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", java.toString()), "javac");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("scalar-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            String output = run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                    directory.toString(), "BridgeScalarConsumer", image.toString()), "consumer-" + level);
            check(output.matches("measured:100000:5004150000:[0-9]+\nhandwritten:100000:5004150000:[0-9]+\ncaught\nscalar-ok\n"),
                    "unexpected JNI output: " + output);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), hash + "  " + image.getFileName() + "\n");
            System.out.println("bridge scalar " + level + ": " + image + " sha256=" + hash);
        }
    }

    private static String adapter(BridgeEntryModule module) {
        StringBuilder source = new StringBuilder("""
                // SPDX-License-Identifier: MIT OR Apache-2.0
                #include <jni.h>
                #include <stdint.h>
                #include <stddef.h>
                union value { int32_t integer; uint8_t boolean; int64_t wide; double real; };
                struct result { union value value; void *exception; };
                _Static_assert(sizeof(struct result) == 16 && offsetof(struct result, exception) == 8, "result ABI");
                extern void ironwood_bridge_bootstrap(void);
                extern int64_t ironwood_allocation_count(void);
                static jlong count(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
                static jint handwritten(JNIEnv *env, jclass type, jint a, jint b) { (void)env; (void)type; return a + b; }
                static void failure(JNIEnv *env) {
                    jclass type = (*env)->FindClass(env, "java/lang/RuntimeException");
                    if (type != NULL) { (*env)->ThrowNew(env, type, "native failure"); (*env)->DeleteLocalRef(env, type); }
                }
                """);
        for (var entry : module.entries()) {
            String name = entry.root().callable().name();
            String symbol = entry.function().linkageName();
            String returnType = switch (name) { case "invert" -> "jboolean"; case "wide" -> "jlong"; case "real" -> "jdouble"; default -> "jint"; };
            String args = switch (name) { case "add" -> "jint a, jint b"; case "invert" -> "jboolean a"; case "wide" -> "jlong a"; case "real" -> "jdouble a"; default -> ""; };
            String values = name.equals("add") ? "a, b, " : args.isEmpty() ? "" : "a, ";
            String field = switch (name) { case "invert" -> "boolean"; case "wide" -> "wide"; case "real" -> "real"; default -> "integer"; };
            source.append("extern int32_t ").append(symbol).append('(').append(args).append(args.isEmpty() ? "" : ", ")
                    .append("int64_t frame);\nstatic ").append(returnType).append(" call_").append(name)
                    .append("(JNIEnv *env, jclass type").append(args.isEmpty() ? "" : ", " + args).append(") {\n")
                    .append(" (void)type; struct result result; if (").append(symbol).append('(').append(values)
                    .append("(int64_t)(uintptr_t)&result) != 0) { failure(env); return 0; }\n return result.value.")
                    .append(field).append(";\n}\n");
        }
        source.append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {\n (void)reserved; JNIEnv *env;\n")
                .append(" if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;\n")
                .append(" jclass type = (*env)->FindClass(env, \"BridgeScalarConsumer\"); if (type == NULL) return JNI_ERR;\n")
                .append(" JNINativeMethod methods[] = {\n {\"allocations\", \"()J\", (void *)count},\n")
                .append(" {\"handwrittenAdd\", \"(II)I\", (void *)handwritten},\n");
        for (var entry : module.entries()) {
            String name = entry.root().callable().name();
            String signature = switch (name) { case "add" -> "(II)I"; case "invert" -> "(Z)Z"; case "wide" -> "(J)J"; case "real" -> "(D)D"; default -> "()I"; };
            source.append(" {\"").append(name).append("\", \"").append(signature).append("\", (void *)call_").append(name).append("},\n");
        }
        return source.append(" };\n if ((*env)->RegisterNatives(env, type, methods, sizeof(methods)/sizeof(methods[0])) != 0) return JNI_ERR;\n")
                .append(" (*env)->DeleteLocalRef(env, type); ironwood_bridge_bootstrap(); ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;\n}\n").toString();
    }

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class BridgeScalarConsumer {
                private static native int add(int a, int b);
                private static native boolean invert(boolean value);
                private static native long wide(long value);
                private static native double real(double value);
                private static native int fail();
                private static native long allocations();
                private static native int handwrittenAdd(int a, int b);
                public static void main(String[] args) {
                    System.load(args[0]);
                    long before = allocations();
                    for (int i = 0; i < 10000; i++) {
                        if (add(-17, 59) != 42 || handwrittenAdd(-17, 59) != 42 || invert(true) || !invert(false)
                                || wide(-9999999999L) != (-9999999999L ^ 1234567890123L)
                                || real(3.25) != 4.875) throw new AssertionError("scalar ABI");
                    }
                    long checksum = 0;
                    long start = System.nanoTime();
                    for (int i = 0; i < 100000; i++) checksum += add(i, 42);
                    long elapsed = System.nanoTime() - start;
                    if (checksum != 5004150000L || allocations() != before) throw new AssertionError("scalar allocation/checksum");
                    System.out.println("measured:100000:" + checksum + ":" + elapsed);
                    checksum = 0;
                    start = System.nanoTime();
                    for (int i = 0; i < 100000; i++) checksum += handwrittenAdd(i, 42);
                    elapsed = System.nanoTime() - start;
                    if (checksum != 5004150000L || allocations() != before) throw new AssertionError("baseline allocation/checksum");
                    System.out.println("handwritten:100000:" + checksum + ":" + elapsed);
                    try { fail(); throw new AssertionError("missing native exception"); }
                    catch (RuntimeException expected) { System.out.println("caught"); }
                    if (add(20, 22) != 42) throw new AssertionError("post-catch call");
                    System.out.println("scalar-ok");
                }
            }
            """;

    private static String run(Path directory, List<String> command, String name) throws Exception {
        Path log = directory.resolve(name + ".log");
        Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command) + "\n");
        var process = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("child timed out: " + command);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        check(process.exitValue() == 0, command + ": " + output);
        return output;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

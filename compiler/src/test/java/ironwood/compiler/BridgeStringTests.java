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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class BridgeStringTests {
    private BridgeStringTests() {}

    private static final String SOURCE = """
            package stringfixture;
            final class Engine {
                static int calls;
                static int checksum(String value) {
                    if (value == null) return -7;
                    int result = value.length();
                    for (int index = 0; index < value.length(); index++) result = result * 31 + value.charAt(index);
                    return result;
                }
                static int first(String first, String second) { calls++; return checksum(first) ^ checksum(second); }
                static int second(String first, String second) { calls++; return checksum(second) ^ checksum(first); }
                static int fail(String first, String second) { calls++; throw null; }
                static int ping() { return 42; }
                static int calls() { return calls; }
            }
            final class Initialized {
                static int value = make();
                static int make() { int[] data = new int[1]; free data; throw null; }
                static int initialize(String first, String second) { return value; }
            }
            """;
    private static final Set<String> EXPORTS = Set.of("first", "second", "fail", "ping", "calls", "initialize");

    private static BridgeRootSet roots(CompilationArtifact artifact, Set<String> names) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().startsWith("stringfixture.") && names.contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
    }

    private static CompilationArtifact artifact(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    static void proofs() throws Exception {
        for (UnfreedMode mode : UnfreedMode.values()) {
            var artifact = artifact(List.of(SourceFile.of("test/BridgeStrings.iron", SOURCE)), mode);
            var module = BridgeEntryModule.copiedStrings(artifact, roots(artifact, EXPORTS));
            for (var entry : module.entries()) {
                int copies = (int) entry.function().blocks().stream().filter(block -> block.terminator() instanceof IrInvokeTerminator invoke
                        && invoke.call() instanceof IrBridgeStringCopyInstruction).count();
                if (copies == 0) continue;
                check(copies == 2, "missing conversion");
                for (int prefix = 0; prefix <= copies; prefix++) {
                    String label = "failure." + prefix;
                    var failure = entry.function().blocks().stream().filter(block -> block.label().equals(label)).findFirst().orElseThrow();
                    check(failure.instructions().get(1) instanceof IrExceptionCaughtInstruction,
                            "implicit failure must be cleared before cleanup/extraction");
                    check(failure.instructions().stream().filter(IrRawDeallocateInstruction.class::isInstance).count() == prefix,
                            "partial conversion has the wrong cleanup prefix");
                }
            }
            String llvm = new LlvmEmitter().emit(module);
            check(llvm.contains("invoke ptr @ironwood_bridge_copy_string")
                    && llvm.contains("ptr @\"ironwood.immortal.implicit allocation failure\""), "unprotected/null-context conversion");
            for (String body : List.of("saved = value; return 0;", "free value; return 0;", "return value;",
                    "System.out.println(value); return 0;")) {
                String type = body.equals("return value;") ? "String" : "int";
                var unsafe = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("test/BadCopy.iron",
                        "package stringfixture; final class Unsafe { static String saved; static " + type
                                + " bad(String value) { " + body + " } }")));
                if (!unsafe.valid()) continue; // Ordinary mandatory safety remains authoritative.
                try {
                    BridgeEntryModule.copiedStrings(unsafe, roots(unsafe, Set.of("bad")));
                    throw new AssertionError("unproved copy admitted: " + body);
                } catch (IllegalArgumentException expected) {
                    check(!expected.getMessage().isBlank(), "missing refusal diagnostic");
                }
            }
        }
        Path directory = Files.createTempDirectory("bridge string artifacts ");
        try {
            Path source = directory.resolve("Engine.iron");
            Files.writeString(source, SOURCE);
            var expected = BridgeEntryModule.copiedStrings(artifact(List.of(SourceFile.read(source)), UnfreedMode.OFF),
                    roots(artifact(List.of(SourceFile.read(source)), UnfreedMode.OFF), EXPORTS)).program();
            Path classes = directory.resolve("classes");
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0, output.toString());
            Path archive = directory.resolve("strings.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0, output.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("stringfixture/Engine.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("stringfixture.Engine"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var reconstructed = artifact(loaded.sources(), UnfreedMode.OFF);
                check(expected.equals(BridgeEntryModule.copiedStrings(reconstructed, roots(reconstructed, EXPORTS)).program()),
                        "copy contract/lowering changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void nativeCopies() throws Exception {
        boolean faults = "1".equals(System.getenv("IRONWOOD_BRIDGE_FAULT_TEST"));
        var artifact = artifact(List.of(SourceFile.of("test/BridgeStrings.iron", SOURCE)), UnfreedMode.OFF);
        var module = BridgeEntryModule.copiedStrings(artifact, roots(artifact, EXPORTS));
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p0b/string-copies").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("BridgeStrings.iron"), SOURCE);
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module, faults));
        Files.writeString(directory.resolve("instrumentation.txt"), faults
                ? "test-only runtime inspection/snapshot and JNI acquisition fault injection\n"
                : "unmodified production runtime; no fault injection\n");
        Path java = directory.resolve("BridgeStringConsumer.java");
        Files.writeString(java, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", java.toString()), "javac");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("strings-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            for (String budget : List.of("normal", "0", "1", "2")) {
                String name = "consumer-" + level + "-" + budget;
                List<String> command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "BridgeStringConsumer", image.toString(), budget, faults ? "faults" : "production"));
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command) + "\nIRONWOOD_ALLOCATION_LIMIT=" + budget + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (!budget.equals("normal")) builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", budget);
                else builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                var process = builder.start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("string child timeout"); }
                String result = Files.readString(directory.resolve(name + ".log"));
                String expected = budget.equals("normal") ? "strings-benchmark:50000:-?[0-9]+:[0-9]+\nstrings-ok:normal\n"
                        : "strings-ok:" + budget + "\n";
                check(process.exitValue() == 0 && result.matches(expected), "string child failed: " + result);
            }
        }
        System.out.println("bridge copied string evidence: " + directory);
        if (!faults) {
            Path runtimeHome = directory.resolve("fault-runtime");
            try (var paths = Files.walk(Path.of("runtime"))) {
                for (Path path : paths.toList()) {
                    Path destination = runtimeHome.resolve(path);
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else Files.copy(path, destination);
                }
            }
            Path runtime = runtimeHome.resolve("runtime/src/ironwood_runtime.c");
            String original = Files.readString(runtime);
            String sizeCheck = "if (encoded_length > INT32_MAX) raise_allocation_failure(allocation_failure);";
            check(original.contains(sizeCheck), "bridge byte-limit injection target changed");
            Files.writeString(runtime, "#define ironwood_bridge_snapshot_failure ironwood_bridge_original_snapshot_failure\n"
                    + "static int bridge_test_utf8_limit;\n"
                    + original.replace(sizeCheck, "if (encoded_length > (bridge_test_utf8_limit ? 4 : INT32_MAX)) raise_allocation_failure(allocation_failure);")
                    + FAULT_RUNTIME);
            List<String> command = List.of(javaHome.resolve("bin/java").toString(), "-ea", "-cp", System.getProperty("java.class.path"),
                    "ironwood.compiler.CompilerTests", "--test", "Java Bridge copied strings contain repeated allocation failures");
            Files.writeString(directory.resolve("fault-child.command.txt"), String.join("\n", command)
                    + "\nIRONWOOD_RUNTIME_HOME=" + runtimeHome + "\nIRONWOOD_BRIDGE_FAULT_TEST=1\n");
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("fault-child.log").toFile());
            builder.environment().put("IRONWOOD_RUNTIME_HOME", runtimeHome.toString());
            builder.environment().put("IRONWOOD_BRIDGE_FAULT_TEST", "1");
            var process = builder.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("fault child timeout"); }
            check(process.exitValue() == 0, Files.readString(directory.resolve("fault-child.log")));
        }
    }

    private static String adapter(BridgeEntryModule module, boolean faults) {
        StringBuilder source = new StringBuilder(ADAPTER);
        if (faults) source.append(FAULT_ADAPTER);
        for (var entry : module.entries()) {
            String name = entry.root().callable().name();
            boolean strings = entry.root().callable().parameters().size() == 2;
            source.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(strings ? "int64_t, int32_t, int64_t, int32_t, " : "").append("int64_t);\n")
                    .append("static jint call_").append(name).append("(JNIEnv *env, jclass type")
                    .append(strings ? ", jstring first, jstring second" : "").append(") {\n (void)type;\n")
                    .append(strings ? " const jchar *a = NULL, *b = NULL; jsize na = -1, nb = -1;\n"
                            + " if (first != NULL) { na = (*env)->GetStringLength(env, first); a = acquire(env, first, 1); if (a == NULL) return 0; }\n"
                            + " if (second != NULL) { nb = (*env)->GetStringLength(env, second); b = acquire(env, second, 2); if (b == NULL) { if (a != NULL) release(env, first, a); return 0; } }\n" : "")
                    .append(" struct ironwood_bridge_result result; int32_t status = ").append(entry.function().linkageName()).append('(')
                    .append(strings ? "(int64_t)(uintptr_t)a, na, (int64_t)(uintptr_t)b, nb, " : "")
                    .append("(int64_t)(uintptr_t)&result);\n")
                    .append(strings ? " if (b != NULL) release(env, second, b);\n if (a != NULL) release(env, first, a);\n" : "")
                    .append(" if (status != 0) { failure(env, &result, status); return 0; } return result.value.integer;\n}\n");
        }
        source.append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) { (void)reserved; JNIEnv *env;\n")
                .append(" if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;\n")
                .append(" jclass type = (*env)->FindClass(env, \"BridgeStringConsumer\"); if (type == NULL) return JNI_ERR;\n")
                .append(" JNINativeMethod methods[] = { {\"live\", \"()J\", (void *)live}, {\"allocations\", \"()J\", (void *)allocations},\n");
        if (faults) source.append(" {\"state\", \"()I\", (void *)state}, {\"buffers\", \"()I\", (void *)buffers}, {\"fault\", \"(I)V\", (void *)fault},\n");
        for (var entry : module.entries()) {
            source.append(" {\"").append(entry.root().callable().name()).append("\", \"")
                    .append(entry.root().callable().parameters().size() == 2 ? "(Ljava/lang/String;Ljava/lang/String;)I" : "()I")
                    .append("\", (void *)call_").append(entry.root().callable().name()).append("},\n");
        }
        return source.append(" }; if ((*env)->RegisterNatives(env, type, methods, sizeof(methods)/sizeof(methods[0])) != 0) return JNI_ERR;\n")
                .append(" (*env)->DeleteLocalRef(env, type); ironwood_bridge_bootstrap(); return JNI_VERSION_1_8; }\n").toString();
    }

    private static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include <string.h>
            #include "ironwood_bridge.h"
            extern void ironwood_bridge_bootstrap(void);
            #define acquire(env, text, index) (*(env))->GetStringChars(env, text, NULL)
            #define release(env, text, chars) (*(env))->ReleaseStringChars(env, text, chars)
            static jlong live(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_live_allocation_count(); }
            static jlong allocations(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
            static void failure(JNIEnv *env, const struct ironwood_bridge_result *result, int32_t status) {
                const char *name = status == 1 ? result->failure.type_name : "snapshot unavailable";
                const char *java_name = strstr(name, "OutOfMemoryError") != NULL || status == 2
                    ? "java/lang/OutOfMemoryError" : "java/lang/RuntimeException";
                jclass error = (*env)->FindClass(env, java_name);
                if (error != NULL) { (*env)->ThrowNew(env, error, name); (*env)->DeleteLocalRef(env, error); }
            }
            """;

    private static final String FAULT_RUNTIME = """

            /* Test-only appendage in an ignored runtime copy, never production. */
            #undef ironwood_bridge_snapshot_failure
            static int bridge_test_snapshot_fault;
            int32_t ironwood_bridge_test_state(void) {
                return (active_implicit_failure != NULL ? 1 : 0) | (emergency_exception_in_use ? 2 : 0);
            }
            void ironwood_bridge_test_fault(int32_t mode) {
                bridge_test_snapshot_fault = mode == 3; bridge_test_utf8_limit = mode == 4;
            }
            void ironwood_bridge_snapshot_failure(const void *object, struct ironwood_bridge_result *result) {
                if (bridge_test_snapshot_fault) {
                    /* Inject only while snapshotting the actual implicit OOM object. */
                    if (strcmp(object_type_name(object), "ironwood.lang.OutOfMemoryError") != 0) abort();
                    void *unexpected = ironwood_allocate(SIZE_MAX, NULL, (void *)object);
                    ironwood_deallocate(unexpected);
                    abort();
                }
                ironwood_bridge_original_snapshot_failure(object, result);
            }
            """;

    private static final String FAULT_ADAPTER = """
            #undef acquire
            #undef release
            extern int32_t ironwood_bridge_test_state(void);
            extern void ironwood_bridge_test_fault(int32_t);
            static int acquisition_fault, acquired_buffers;
            static jint state(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_bridge_test_state(); }
            static jint buffers(JNIEnv *env, jclass type) { (void)env; (void)type; return acquired_buffers; }
            static void fault(JNIEnv *env, jclass type, jint mode) {
                (void)env; (void)type; acquisition_fault = mode; ironwood_bridge_test_fault(mode);
            }
            static const jchar *acquire(JNIEnv *env, jstring text, int index) {
                if (acquisition_fault == index) {
                    jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                    if (error != NULL) { (*env)->ThrowNew(env, error, "test acquisition failure"); (*env)->DeleteLocalRef(env, error); }
                    return NULL;
                }
                const jchar *result = (*env)->GetStringChars(env, text, NULL);
                if (result != NULL) acquired_buffers++;
                return result;
            }
            static void release(JNIEnv *env, jstring text, const jchar *characters) {
                (*env)->ReleaseStringChars(env, text, characters); acquired_buffers--;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class BridgeStringConsumer {
                private static native int first(String a, String b);
                private static native int second(String a, String b);
                private static native int fail(String a, String b);
                private static native int initialize(String a, String b);
                private static native int calls();
                private static native int ping();
                private static native long live();
                private static native long allocations();
                private static native int state();
                private static native int buffers();
                private static native void fault(int mode);
                private static void clean(boolean instrumented) {
                    if (instrumented && (state() != 0 || buffers() != 0)) throw new AssertionError("native/JNI state still active");
                }
                private static int checksum(String value) {
                    if (value == null) return -7;
                    int result = value.length();
                    for (int i = 0; i < value.length(); i++) result = result * 31 + value.charAt(i);
                    return result;
                }
                public static void main(String[] args) {
                    System.load(args[0]);
                    boolean instrumented = args[2].equals("faults");
                    long baseline = live();
                    if (args[1].equals("normal")) {
                        String[] values = {null, "", "plain", "a\\u0000b", "\\ud800", "\\udc00", "\\ud83d\\ude00"};
                        for (String a : values) for (String b : values) {
                            long before = allocations();
                            if (first(a, b) != (checksum(a) ^ checksum(b)) || second(a, b) != (checksum(a) ^ checksum(b))) {
                                throw new AssertionError("UTF-16 copy");
                            }
                            if (live() != baseline) throw new AssertionError("normal copy leak");
                            if (allocations() - before != 2L * ((a == null ? 0 : 1) + (b == null ? 0 : 1))) {
                                throw new AssertionError("copy must allocate exactly once per non-null String");
                            }
                            clean(instrumented);
                        }
                        long before = allocations();
                        long checksum = 0;
                        long start = System.nanoTime();
                        for (int i = 0; i < 50000; i++) checksum += first("alpha", "omega");
                        long elapsed = System.nanoTime() - start;
                        if (checksum != 50000L * (checksum("alpha") ^ checksum("omega"))
                                || allocations() - before != 100000L || live() != baseline) throw new AssertionError("copy benchmark");
                        System.out.println("strings-benchmark:50000:" + checksum + ":" + elapsed);
                        // Isolate the implicit throwable's allocation from temporary String cleanup.
                        try { fail(null, null); } catch (RuntimeException expected) { }
                        baseline = live();
                        for (int i = 0; i < 4; i++) {
                            try { fail("first", "second"); throw new AssertionError("missing target failure"); }
                            catch (RuntimeException expected) { }
                            // A new explicit null-throw throwable is retained by the current P0 runtime.
                            if (live() != baseline + i + 1L) throw new AssertionError("exceptional copy leak");
                            clean(instrumented);
                        }
                        if (instrumented) {
                            int beforeCalls = calls();
                            long beforeLive = live();
                            for (int mode = 1; mode <= 2; mode++) {
                                fault(mode);
                                try { first("first", "second"); throw new AssertionError("missing acquisition failure"); }
                                catch (OutOfMemoryError expected) { }
                                clean(true);
                                if (calls() != beforeCalls || live() != beforeLive) throw new AssertionError("acquisition entered native target");
                            }
                            // Lower only the private test runtime's byte limit to exercise the exact overflow branch.
                            fault(4);
                            for (int i = 0; i < 2; i++) {
                                try { first("abcdef", null); throw new AssertionError("missing byte-limit failure"); }
                                catch (OutOfMemoryError expected) { }
                                clean(true);
                                if (calls() != beforeCalls || live() != beforeLive) throw new AssertionError("byte-limit cleanup");
                            }
                            fault(0);
                        }
                    } else {
                        int budget = Integer.parseInt(args[1]);
                        if (budget == 2) {
                            try { initialize("first", "second"); throw new AssertionError("missing initializer OOM"); }
                            catch (OutOfMemoryError expected) { }
                            if (live() != baseline) throw new AssertionError("initializer conversion leak");
                            clean(instrumented);
                            for (int i = 0; i < 2; i++) {
                                try { initialize(null, null); throw new AssertionError("failed initializer reentered"); }
                                catch (OutOfMemoryError expected) { }
                                clean(instrumented);
                            }
                        }
                        for (int i = 0; i < 8; i++) {
                            try {
                                if (i % 2 == 0) first("first", "second"); else second("first", "second");
                                throw new AssertionError("missing conversion OOM");
                            } catch (OutOfMemoryError expected) { }
                            if (calls() != 0 || live() != baseline) throw new AssertionError("target entered or temporary leaked");
                            if (ping() != 42) throw new AssertionError("post-failure scalar");
                            clean(instrumented);
                        }
                        if (instrumented) {
                            fault(3);
                            for (int i = 0; i < 4; i++) {
                                try {
                                    if (i % 2 == 0) first("first", "second"); else second("first", "second");
                                    throw new AssertionError("missing snapshot failure");
                                } catch (OutOfMemoryError expected) {
                                    if (!expected.getMessage().equals("snapshot unavailable")) throw new AssertionError("fallback not exercised");
                                }
                                clean(true);
                                if (live() != baseline || calls() != 0 || ping() != 42) throw new AssertionError("snapshot fallback state");
                            }
                            fault(0);
                        }
                    }
                    if (ping() != 42) throw new AssertionError("post-catch call");
                    System.out.println("strings-ok:" + args[1]);
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class BridgeStringResultNativeTests {
    private BridgeStringResultNativeTests() {}

    private static final String SOURCE = """
            package resultnative;
            final class Values {
                static int entered;
                static String alias(String first, String second, boolean choose) { entered++; return choose ? first : second; }
                static String fresh(String first, String second, boolean choose) { entered++; return new String(choose ? first : second); }
                static String concat(String first, String second, boolean choose) { entered++; return choose ? first + second : second + first; }
                static String literal(String first, String second, boolean choose) { entered++; return choose ? "literal" : null; }
                static String fail(String first, String second, boolean choose) { entered++; if (choose) throw null; return null; }
                static String constant() { return "literal"; }
                static int calls() { return entered; }
                static int ping() { return 42; }
            }
            """;

    static void results() throws Exception {
        var analyzed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Values.iron", SOURCE)));
        check(analyzed.valid(), analyzed.diagnostics().toString());
        var original = analyzed.program().orElseThrow();
        var roots = BridgeRootSet.resolve(original, original.functions().stream()
                .filter(function -> function.ownerClass().equals("resultnative.Values")
                        && function.kind() == ironwood.compiler.ir.IrCallableKind.METHOD)
                .map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.stringValues(analyzed, roots);
        var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program()));
        check(BridgeRootSet.resolve(program, module.entries().stream()
                .map(entry -> BridgeCallableId.of(entry.function())).toList()).resolved(), "native optimization changed entries");
        var found = LlvmToolchain.discover(null);
        check(found.successful(), found.error());
        var toolchain = found.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p2/string-results").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(program));
        Files.writeString(directory.resolve("Values.iron"), SOURCE);
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module));
        Path consumer = directory.resolve("StringResults.java");
        Files.writeString(consumer, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch")
                + "\nllvm=" + toolchain.version() + "\nprivate test bindings; production runtime; injected JNI delivery failure\n");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("results-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (String budget : List.of("normal", "0", "1", "2", "concat")) {
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "StringResults", image.toString(), budget);
                String name = "consumer-" + level + "-" + budget;
                String limit = budget.equals("concat") ? "2" : budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + limit + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget.equals("normal")) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", limit);
                var process = builder.start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("String result child timed out"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), process.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(process.exitValue() == 0 && output.matches(budget.equals("normal")
                        ? "result-ns:[0-9]+:[0-9]+\\nresults-ok:normal\\n" : "results-ok:" + budget + "\\n"), name + ": " + output);
            }
        }
        System.out.println("String result evidence: " + directory);
    }

    private static String adapter(BridgeEntryModule module) {
        var source = new StringBuilder("""
                // SPDX-License-Identifier: MIT OR Apache-2.0
                #include <jni.h>
                #include <stdint.h>
                #include <string.h>
                #include "ironwood_bridge.h"
                extern void ironwood_bridge_bootstrap(void);
                static jlong live(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_live_allocation_count(); }
                static jlong allocations(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
                __attribute__((noinline)) static void error(JNIEnv *env, const char *type) {
                    jclass failure = (*env)->FindClass(env, type);
                    if (failure != NULL) { (*env)->ThrowNew(env, failure, "String result fixture"); (*env)->DeleteLocalRef(env, failure); }
                }
                """);
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            boolean strings = !id.parameters().isEmpty();
            boolean resultString = module.stringResults().containsKey(id);
            source.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(strings ? "int64_t, int32_t, int64_t, int32_t, uint8_t, " : "").append("int64_t);\n")
                    .append("static ").append(resultString ? "jstring" : "jint").append(" call_").append(id.name())
                    .append("(JNIEnv *env, jclass type").append(strings ? ", jstring first, jstring second, jboolean choose, jboolean delivery" : "")
                    .append(") { (void)type;\n")
                    .append(strings ? "const jchar *a = NULL, *b = NULL; jsize na = -1, nb = -1;\n"
                            + "if (first != NULL) { na = (*env)->GetStringLength(env, first); a = (*env)->GetStringChars(env, first, NULL); if (a == NULL) return 0; }\n"
                            + "if (second != NULL) { nb = (*env)->GetStringLength(env, second); b = (*env)->GetStringChars(env, second, NULL); if (b == NULL) { if (a != NULL) (*env)->ReleaseStringChars(env, first, a); return 0; } }\n" : "")
                    .append("struct ironwood_bridge_result result; int32_t status = ").append(entry.function().linkageName()).append('(')
                    .append(strings ? "(int64_t)(uintptr_t)a, na, (int64_t)(uintptr_t)b, nb, choose, " : "")
                    .append("(int64_t)(uintptr_t)&result);\n")
                    .append(strings ? "if (b != NULL) (*env)->ReleaseStringChars(env, second, b);\nif (a != NULL) (*env)->ReleaseStringChars(env, first, a);\n" : "")
                    .append("if (status != 0) { error(env, status == 2 || strstr(result.failure.type_name, \"OutOfMemoryError\") != NULL ? \"java/lang/OutOfMemoryError\" : \"java/lang/RuntimeException\"); return 0; }\n");
            if (resultString) {
                source.append("const struct ironwood_string *text = result.value.reference; jstring copied = NULL;\n");
                if (strings) source.append("if (delivery) error(env, \"java/lang/OutOfMemoryError\"); else ");
                source.append("if (text != NULL) copied = (*env)->NewString(env, text->units, text->utf16_length);\n");
                if (module.stringResults().get(id).kind() != BridgeStringResultContract.Kind.IMMORTAL) {
                    source.append("ironwood_deallocate(result.value.reference);\n");
                }
                source.append("return copied;\n}\n");
            } else source.append("return result.value.integer;\n}\n");
        }
        source.append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) { (void)reserved; JNIEnv *env;\n")
                .append("if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;\n")
                .append("jclass type = (*env)->FindClass(env, \"StringResults\"); if (type == NULL) return JNI_ERR;\n")
                .append("JNINativeMethod methods[] = {{\"live\", \"()J\", (void *)live}, {\"allocations\", \"()J\", (void *)allocations},\n");
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            source.append("{\"").append(id.name()).append("\", \"").append(id.parameters().isEmpty() ? "()"
                    : "(Ljava/lang/String;Ljava/lang/String;ZZ)")
                    .append(module.stringResults().containsKey(id) ? "Ljava/lang/String;" : "I")
                    .append("\", (void *)call_").append(id.name()).append("},\n");
        }
        return source.append("}; if ((*env)->RegisterNatives(env, type, methods, sizeof(methods)/sizeof(methods[0])) != 0) return JNI_ERR;\n")
                .append("(*env)->DeleteLocalRef(env, type); ironwood_bridge_bootstrap(); return JNI_VERSION_1_8; }\n").toString();
    }

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class StringResults {
                private static native String alias(String a, String b, boolean choose, boolean delivery);
                private static native String fresh(String a, String b, boolean choose, boolean delivery);
                private static native String concat(String a, String b, boolean choose, boolean delivery);
                private static native String literal(String a, String b, boolean choose, boolean delivery);
                private static native String fail(String a, String b, boolean choose, boolean delivery);
                private static native String constant();
                private static native int calls();
                private static native int ping();
                private static native long live();
                private static native long allocations();
                public static void main(String[] args) {
                    System.load(args[0]);
                    long baseline = live();
                    if (!args[1].equals("normal")) {
                        for (int i = 0; i < 3; i++) {
                            try {
                                if (args[1].equals("concat")) concat("a", "b", true, false);
                                else fresh("a", "b", true, false);
                                throw new AssertionError("allocation failure absent");
                            }
                            catch (OutOfMemoryError expected) {}
                            if (live() != baseline || ping() != 42 || !constant().equals("literal")) throw new AssertionError("failure cleanup");
                        }
                        if (calls() != (args[1].equals("2") || args[1].equals("concat") ? 1 : 0)) throw new AssertionError("wrong preparation/target boundary");
                    } else {
                        String[] values = {null, "", "a\\0b", "" + (char)0xd800 + 'x' + (char)0xdc00, "plain"};
                        for (String a : values) for (String b : values) for (boolean choose : new boolean[]{false, true}) {
                            String expected = choose ? a : b;
                            long before = allocations();
                            String actual = alias(a, b, choose, false);
                            if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("alias content");
                            if (allocations() - before != (a == null ? 0 : 1) + (b == null ? 0 : 1)) throw new AssertionError("alias extra allocation");
                            if (expected != null && !fresh(a, b, choose, false).equals(expected)) throw new AssertionError("fresh content");
                            before = allocations();
                            if (!concat(a, b, choose, false).equals(choose ? a + b : b + a)) throw new AssertionError("concat content");
                            if (allocations() - before != (a == null ? 0 : 1) + (b == null ? 0 : 1) + 1) throw new AssertionError("concat extra allocation");
                            try { concat(a, b, choose, true); throw new AssertionError("concat delivery failure absent"); }
                            catch (OutOfMemoryError expectedFailure) {}
                            if (!java.util.Objects.equals(literal(a, b, choose, false), choose ? "literal" : null)) throw new AssertionError("literal content");
                            try { alias(a, b, choose, true); throw new AssertionError("delivery failure absent"); }
                            catch (OutOfMemoryError expectedFailure) {}
                            if (expected != null) {
                                try { fresh(a, b, choose, true); throw new AssertionError("fresh delivery failure absent"); }
                                catch (OutOfMemoryError expectedFailure) {}
                            }
                            if (live() != baseline) throw new AssertionError("result leaked: " + live() + "/" + baseline);
                        }
                        try { fail("a", "b", true, false); throw new AssertionError("native failure absent"); }
                        catch (RuntimeException expected) {}
                        // throw null creates one native exception. This private translator
                        // has no ownership grant to reclaim it; both input copies must go.
                        if (live() != baseline + 1 || ping() != 42) throw new AssertionError("target failure cleanup");
                        baseline = live();
                        long before = allocations(), start = System.nanoTime();
                        for (int i = 0; i < 50000; i++) if (!alias("first", "second", true, false).equals("first")) throw new AssertionError("benchmark");
                        System.out.println("result-ns:" + (System.nanoTime() - start) + ":" + (allocations() - before));
                        if (live() != baseline || allocations() - before != 100000L) throw new AssertionError("benchmark allocation count");
                    }
                    System.out.println("results-ok:" + args[1]);
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

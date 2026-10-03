// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Private extraction harness; custom Java snapshot generation remains separately gated. */
final class BridgeCustomExceptionNativeTests {
    static final String NAME = "Java Bridge custom getters preserve native scalar data and contain extraction failures";
    private static final String SOURCE = """
            package customnative;
            public final class Errors {
                private Errors() {}
                private static int mode;
                public static void setMode(int value) { mode = value; }
                public static int ping() { return 42; }
                public static int fail() throws Problem { throw new Problem(); }
                public static final class Problem extends Exception {
                    public Problem() { super("problem"); }
                    // A fresh copy is released after Java delivery; the message remains borrowed.
                    public String getCopy() { if (mode == 1) throw null; return new String("copy"); }
                    public boolean isReady() { return true; }
                    public byte getByte() { return (byte) -2; }
                    public short getShort() { return (short) -3; }
                    public char getUnit() { return '\\uD800'; }
                    public int getCode() { return 29; }
                    public long getLong() { return 12345678901L; }
                    public float getFloat() { return 1.5f; }
                    public double getDouble() { return -0.0; }
                }
            }
            """;

    private BridgeCustomExceptionNativeTests() {}

    static void getters() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Errors.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(original, original.functions().stream().filter(function -> function.ownerClass().equals("customnative.Errors")
                && function.kind() == IrCallableKind.METHOD).map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.scalars(artifact, roots);
        var proof = BridgeExceptionProjection.snapshots(artifact, List.of("customnative.Errors$Problem"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var projection = proof.contract().orElseThrow();
        var entries = BridgeExceptionEntries.attach(artifact, module, projection);
        var problem = projection.types().stream().filter(type -> type.nativeName().endsWith("$Problem")).findFirst().orElseThrow();
        var declarations = new StringBuilder();
        for (var entry : module.entries()) {
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(entry.function().parameters().stream().map(parameter -> parameter.value().type().equals(IrType.I32) ? "int32_t" : "int64_t")
                            .collect(java.util.stream.Collectors.joining(", "))).append(");\n#define ")
                    .append(entry.root().callable().name()).append(' ').append(entry.function().linkageName()).append('\n');
        }
        for (var property : problem.properties()) {
            String symbol = entries.accessors().get(property).linkageName();
            declarations.append("extern int32_t ").append(symbol).append(property.name().equals("getSecondaryException")
                    ? "(void *, int32_t, int64_t);\n" : "(void *, int64_t);\n")
                    .append("#define ").append(property.name()).append(' ').append(symbol).append('\n');
        }
        check(problem.properties().stream().filter(property -> property.name().equals("getCopy")).findFirst().orElseThrow().ownedString(),
                "fresh getter cleanup not proved");
        var found = LlvmToolchain.discover(null);
        check(found.successful(), found.error());
        var toolchain = found.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p3a/custom-getters").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll"), adapter = directory.resolve("adapter.c");
        Files.writeString(llvm, new LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(entries.program()))));
        Files.writeString(directory.resolve("Errors.iron"), SOURCE);
        Files.writeString(adapter, "#include <jni.h>\n#include <stdint.h>\n#include <math.h>\n#include <string.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + ADAPTER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("CustomGetters.java");
        Files.writeString(consumer, """
                public final class CustomGetters {
                    static native void exercise(int budget);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(Integer.parseInt(args[1]));
                        System.out.println("custom-getters-ok:" + args[1]);
                    }
                }
                """);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch") + "\nllvm=" + toolchain.version()
                + "\nprivate extraction harness; no generated custom Java exception or jar qualification\n");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"), "-I" + Path.of("runtime/include").toAbsolutePath(),
                    "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("getters-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(), "--disassemble",
                    "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (int budget : List.of(-1, 0, 1, 2)) {
                var command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "CustomGetters", image.toString(), Integer.toString(budget)));
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (budget < 0 ? "unset" : budget) + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget < 0) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(budget));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("custom getter child timeout"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), child.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.equals("custom-getters-ok:" + budget + "\n"), name + ": " + output);
            }
        }
        for (String name : List.of("program.ll", "Errors.iron", "adapter.c", "CustomGetters.java", "CustomGetters.class")) {
            Files.writeString(directory.resolve(name + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(directory.resolve(name))) + "\n");
        }
        System.out.println("custom getter evidence: " + directory);
    }

    private static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            #define FRAME ((int64_t)(uintptr_t)&f)
            #define CHECK(condition) do { if (!(condition)) { jclass error = (*env)->FindClass(env, "java/lang/AssertionError"); \
                if (error != NULL) { (*env)->ThrowNew(env, error, #condition); (*env)->DeleteLocalRef(env, error); } return; } } while (0)
            JNIEXPORT void JNICALL Java_CustomGetters_exercise(JNIEnv *env, jclass type, jint budget) {
                (void)type; ironwood_bridge_bootstrap(); struct ironwood_bridge_result f = {0};
                uint64_t baseline = ironwood_live_allocation_count();
                CHECK(fail(FRAME) == 1);
                if (budget == 0) {
                    CHECK(strstr(f.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(ping(FRAME) == 0 && f.value.integer == 42); return;
                }
                CHECK(strstr(f.failure.type_name, "Problem") != NULL);
                void *problem = f.exception;
                CHECK(ironwood_live_allocation_count() == baseline + 1);
                uint64_t count = ironwood_allocation_count();
                CHECK(isReady(problem, FRAME) == 0 && f.value.boolean == 1);
                CHECK(getByte(problem, FRAME) == 0 && f.value.byte == -2);
                CHECK(getShort(problem, FRAME) == 0 && f.value.short_integer == -3);
                CHECK(getUnit(problem, FRAME) == 0 && f.value.character == 0xd800);
                CHECK(getCode(problem, FRAME) == 0 && f.value.integer == 29);
                CHECK(getLong(problem, FRAME) == 0 && f.value.wide == INT64_C(12345678901));
                CHECK(getFloat(problem, FRAME) == 0 && f.value.single == 1.5f);
                CHECK(getDouble(problem, FRAME) == 0 && f.value.real == 0.0 && signbit(f.value.real));
                CHECK(getMessage(problem, FRAME) == 0 && ((struct ironwood_string *)f.value.reference)->utf16_length == 7);
                CHECK(ironwood_allocation_count() == count);
                if (budget == 1) {
                    CHECK(getCopy(problem, FRAME) == 2);
                    CHECK(getCopy(problem, FRAME) == 2);
                    CHECK(ironwood_live_allocation_count() == baseline + 1);
                    CHECK(getCode(problem, FRAME) == 0 && f.value.integer == 29);
                    CHECK(ping(FRAME) == 0 && f.value.integer == 42); return;
                }
                CHECK(getCopy(problem, FRAME) == 0);
                struct ironwood_string *copy = f.value.reference;
                CHECK(copy->utf16_length == 4 && copy->units[0] == 'c' && copy->units[3] == 'y');
                jstring text = (*env)->NewString(env, copy->units, copy->utf16_length);
                ironwood_deallocate(copy);
                if ((*env)->ExceptionCheck(env)) return;
                CHECK(text != NULL && (*env)->GetStringLength(env, text) == 4);
                (*env)->DeleteLocalRef(env, text);
                CHECK(ironwood_live_allocation_count() == baseline + 1);
                if (budget == 2) {
                    CHECK(getCopy(problem, FRAME) == 2);
                    CHECK(ironwood_live_allocation_count() == baseline + 1);
                } else {
                    CHECK(setMode(1, FRAME) == 0 && getCopy(problem, FRAME) == 3);
                    CHECK(ironwood_live_allocation_count() == baseline + 2);
                    CHECK(setMode(0, FRAME) == 0 && getCode(problem, FRAME) == 0 && f.value.integer == 29);
                }
                CHECK(ping(FRAME) == 0 && f.value.integer == 42);
                /* The original producer exception and any throwing-getter exception remain native allocations. */
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

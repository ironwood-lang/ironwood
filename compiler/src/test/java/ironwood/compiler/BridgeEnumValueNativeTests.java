// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class BridgeEnumValueNativeTests {
    static final String NAME = "Java Bridge enum value entries preserve exact dispatch initialization and cleanup";
    private static final String SOURCE = """
            package enumvalues;
            public final class Protocol {
                private static int initialized;
                private static int entered;
                private static boolean fail;
                private Protocol() {}
                public static int initializations() { return initialized; }
                public static int calls() { return entered; }
                public static int ping() { return 42; }
                public static void setFailure(boolean value) { fail = value; }
                public static int select(Mode value, String input) {
                    entered++; return (value == null ? -1 : value.code()) + (input == null ? -1 : input.length());
                }
                public static boolean empty(Empty value) { entered++; return value == null; }
                public enum Empty { ; }
                public enum Mode {
                    FIRST(11) { @Override public int code() { entered++; return raw(); } },
                    SECOND(29) { @Override public int code() { entered++; return raw() + 1; } };
                    private final int number;
                    Mode(int number) { initialized++; this.number = number; if (fail) throw null; }
                    public final int raw() { return number; }
                    public abstract int code();
                    public String alias(String input) { entered++; return input; }
                    public String fresh(String input) { entered++; return new String(input); }
                    public int both(String first, String second) { entered++; return first.length() + second.length(); }
                }
            }
            """;

    private BridgeEnumValueNativeTests() {}

    static void entries() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Protocol.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var mode = IrType.reference("enumvalues.Protocol$Mode");
        var constants = BridgeEnumConstants.prove(artifact, Map.of(mode, Map.of("FIRST", 41, "SECOND", 7),
                IrType.reference("enumvalues.Protocol$Empty"), Map.of()));
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var dispatches = facts.types().get(mode.referenceName()).callables().stream()
                .filter(method -> Set.of("code", "alias", "fresh", "both").contains(method.name()))
                .map(method -> BridgeEnumDispatch.prove(artifact, mode, method, constants)).toList();
        var statics = facts.types().get("enumvalues.Protocol").callables().stream()
                .filter(method -> Set.of("initializations", "calls", "ping", "setFailure", "select", "empty").contains(method.name()))
                .map(method -> method.target().orElseThrow()).toList();
        var proof = BridgeEnumInvocation.prove(artifact, constants, dispatches, statics);
        var module = BridgeEntryModule.enumValues(artifact, proof);
        var finished = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program()));
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p3a/enum-values").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(finished));
        Files.writeString(directory.resolve("Protocol.iron"), SOURCE);
        var declarations = new StringBuilder();
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            String name = id.name().equals("code") ? proof.parameters().get(id).getFirst().constants().getFirst().field().name() + "Code" : id.name();
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(entry.function().parameters().stream().map(parameter -> cType(parameter.value().type()))
                            .collect(java.util.stream.Collectors.joining(", "))).append(");\n#define ")
                    .append(name).append(' ').append(entry.function().linkageName()).append('\n');
        }
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, "#include <jni.h>\n#include <stdint.h>\n#include <stdio.h>\n#include <string.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + ADAPTER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("EnumValues.java");
        Files.writeString(consumer, """
                public final class EnumValues {
                    static native void exercise(int scenario);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(Integer.parseInt(args[1]));
                        System.out.println("enum-values-ok:" + args[1]);
                    }
                }
                """);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch")
                + "\nllvm=" + toolchain.version() + "\nprivate proof-authorized host harness; unmodified runtime\n");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("enum-values-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (int scenario = 0; scenario <= 6; scenario++) {
                int limit = scenario == 3 ? 0 : scenario == 4 || scenario == 6 ? 1 : -1;
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "EnumValues", image.toString(), Integer.toString(scenario));
                String name = "consumer-" + level + "-" + scenario;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (limit < 0 ? "unset" : limit) + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (limit < 0) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(limit));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("enum value child timeout"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), child.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.matches(scenario == 0
                        ? "enum-copy-benchmark:10000:350000:[0-9]+\\nenum-scalar-benchmark:100000:1100000:[0-9]+\\nenum-values-ok:0\\n"
                        : "enum-values-ok:" + scenario + "\\n"), name + ": " + output);
            }
        }
        for (String file : List.of("program.ll", "Protocol.iron", "adapter.c", "EnumValues.java", "EnumValues.class")) {
            Files.writeString(directory.resolve(file + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(directory.resolve(file))) + "\n");
        }
        System.out.println("enum value evidence: " + directory);
    }

    private static String cType(IrType type) {
        if (type.equals(IrType.I8)) return "uint8_t";
        if (type.equals(IrType.I32)) return "int32_t";
        if (type.equals(IrType.I64)) return "int64_t";
        throw new AssertionError(type);
    }

    private static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            #define ADDRESS(value) ((int64_t)(uintptr_t)(value))
            #define FRAME ADDRESS(&f)
            #define CHECK(condition) do { if (!(condition)) { jclass error = (*env)->FindClass(env, "java/lang/AssertionError"); \
                if (error != NULL) { (*env)->ThrowNew(env, error, #condition); (*env)->DeleteLocalRef(env, error); } return; } } while (0)
            static const uint16_t units[] = {0, 'x', 0xd83d, 0xde00, 0xd800};
            static int matches(void *pointer) {
                const struct ironwood_string *value = pointer;
                return value != NULL && value->utf16_length == 5 && memcmp(value->units, units, sizeof(units)) == 0;
            }
            JNIEXPORT void JNICALL Java_EnumValues_exercise(JNIEnv *env, jclass type, jint scenario) {
                (void)type; ironwood_bridge_bootstrap(); struct ironwood_bridge_result f = {0};
                uint64_t baseline = ironwood_live_allocation_count();
                CHECK(initializations(FRAME) == 0 && f.value.integer == 0);
                if (scenario == 1) {
                    CHECK(FIRSTCode(7, FRAME) == 3);
                    CHECK(alias(999, ADDRESS(units), 5, FRAME) == 3);
                    CHECK(alias(-1, ADDRESS(units), 5, FRAME) == 3);
                    CHECK(both(999, ADDRESS(units), 5, ADDRESS(units), 5, FRAME) == 3);
                    CHECK(select(999, ADDRESS(units), 5, FRAME) == 3);
                    CHECK(empty(0, FRAME) == 3);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(calls(FRAME) == 0 && f.value.integer == 0);
                    CHECK(initializations(FRAME) == 0 && f.value.integer == 0);
                } else if (scenario == 2) {
                    CHECK(select(-1, ADDRESS(units), 5, FRAME) == 0 && f.value.integer == 4);
                    CHECK(empty(-1, FRAME) == 0 && f.value.boolean == 1);
                    CHECK(initializations(FRAME) == 0 && f.value.integer == 0);
                    CHECK(ironwood_live_allocation_count() == baseline);
                } else if (scenario == 3 || scenario == 4 || scenario == 6) {
                    int status = scenario == 3 ? alias(41, ADDRESS(units), 5, FRAME)
                        : scenario == 4 ? fresh(41, ADDRESS(units), 5, FRAME)
                        : both(41, ADDRESS(units), 5, ADDRESS(units), 5, FRAME);
                    CHECK(status == 1 && strstr(f.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(initializations(FRAME) == 0 && f.value.integer == (scenario == 4 ? 2 : 0));
                    CHECK(calls(FRAME) == 0 && f.value.integer == (scenario == 4 ? 1 : 0));
                    CHECK(alias(41, 0, -1, FRAME) == 0 && f.value.reference == NULL);
                    CHECK(ping(FRAME) == 0 && f.value.integer == 42); return;
                } else if (scenario == 5) {
                    CHECK(setFailure(1, FRAME) == 0);
                    CHECK(alias(41, ADDRESS(units), 5, FRAME) == 1);
                    CHECK(strstr(f.failure.type_name, "NullPointerException") != NULL);
                    void *failure = f.exception;
                    uint64_t held = ironwood_live_allocation_count(), count = ironwood_allocation_count();
                    CHECK(initializations(FRAME) == 0 && f.value.integer == 1);
                    CHECK(calls(FRAME) == 0 && f.value.integer == 0);
                    CHECK(alias(41, ADDRESS(units), 5, FRAME) == 1 && f.exception == failure);
                    CHECK(ironwood_live_allocation_count() == held && ironwood_allocation_count() == count + 1);
                    CHECK(setFailure(0, FRAME) == 0);
                    CHECK(FIRSTCode(41, FRAME) == 1 && f.exception == failure);
                    CHECK(initializations(FRAME) == 0 && f.value.integer == 1);
                    CHECK(ping(FRAME) == 0 && f.value.integer == 42); return;
                }
                CHECK(FIRSTCode(41, FRAME) == 0 && f.value.integer == 11);
                CHECK(SECONDCode(7, FRAME) == 0 && f.value.integer == 30);
                CHECK(initializations(FRAME) == 0 && f.value.integer == 2);
                if (scenario != 0) return;
                CHECK(alias(7, ADDRESS(units), 5, FRAME) == 0 && matches(f.value.reference));
                ironwood_deallocate(f.value.reference);
                CHECK(fresh(41, ADDRESS(units), 5, FRAME) == 0 && matches(f.value.reference));
                ironwood_deallocate(f.value.reference);
                CHECK(both(7, ADDRESS(units), 5, ADDRESS(units), 5, FRAME) == 0 && f.value.integer == 10);
                CHECK(alias(41, 0, -1, FRAME) == 0 && f.value.reference == NULL);
                uint64_t count = ironwood_allocation_count(); int64_t sum = 0, start = ironwood_nano_time();
                for (int index = 0; index < 10000; index++) {
                    CHECK(select(7, ADDRESS(units), 5, FRAME) == 0); sum += f.value.integer;
                }
                CHECK(sum == 350000 && ironwood_allocation_count() == count + 10000);
                printf("enum-copy-benchmark:10000:%lld:%lld\\n", (long long)sum, (long long)(ironwood_nano_time() - start));
                count = ironwood_allocation_count(); sum = 0; start = ironwood_nano_time();
                for (int index = 0; index < 100000; index++) {
                    CHECK(FIRSTCode(41, FRAME) == 0); sum += f.value.integer;
                }
                CHECK(sum == 1100000 && ironwood_allocation_count() == count);
                printf("enum-scalar-benchmark:100000:%lld:%lld\\n", (long long)sum, (long long)(ironwood_nano_time() - start));
                fflush(stdout);
                CHECK(ironwood_live_allocation_count() == baseline);
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

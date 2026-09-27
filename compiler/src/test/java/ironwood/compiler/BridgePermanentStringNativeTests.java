// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Exercises the permanent module's shared String lowering without public facade admission. */
final class BridgePermanentStringNativeTests {
    static final String NAME = "Java Bridge permanent String entries preserve publication and conversion cleanup";
    private static final String SOURCE = """
            package permanentstrings;
            public final class Catalog {
                private final String label;
                private static Catalog saved;
                public Catalog(String input) { label = new String(input); }
                destructor { free label; }
                public String text() { saved = this; return label; }
                public String alias(String input) { saved = this; return input; }
                public String copied(String input) { saved = this; return new String(input); }
                public int length(String input) { saved = this; return input == null ? -1 : input.length(); }
                public static Catalog unknown() { return saved; }
                public static int ping() { return 42; }
            }
            """;

    private BridgePermanentStringNativeTests() {}

    static void entries() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Catalog.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var surface = BridgeExportSurface.concreteObjects(artifact, List.of("permanentstrings"));
        check(surface.surface().isPresent(), surface.diagnostics().toString());
        var module = BridgeEntryModule.permanentObjects(artifact, surface.surface().orElseThrow().roots());
        check(module.destructions().isEmpty() && module.rootRetention().isEmpty(), "permanent module gained free capability");
        var finalLifetime = BridgeFinalNonReclamation.prove(artifact, module);
        check(finalLifetime.status() == BridgeProof.Status.PROVED, finalLifetime.reason());
        var contract = finalLifetime.contract().orElseThrow();
        var finished = contract.program();
        check(contract.matches(module, finished), "native payload does not match final lifetime proof");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p3a/permanent-strings").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(finished));
        Files.writeString(directory.resolve("final-lifetime.txt"), "permanent=" + contract.references().keySet()
                + "\nexport-roots=" + finished.exportRoots().stream().sorted().toList()
                + "\nexceptions=" + contract.exceptions().projection().types().stream().map(BridgeExceptionProjection.Type::nativeName).sorted().toList()
                + "\nllvm-sha256=" + BridgeGeneration.bytesDigest(Files.readAllBytes(llvm)) + "\n");
        Files.writeString(directory.resolve("Catalog.iron"), SOURCE);
        var declarations = new StringBuilder();
        for (var entry : module.entries()) {
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(entry.function().parameters().stream().map(parameter -> cType(parameter.value().type()))
                            .collect(java.util.stream.Collectors.joining(", "))).append(");\n#define ")
                    .append(entry.root().callable().kind() == IrCallableKind.CONSTRUCTOR ? "create" : entry.root().callable().name())
                    .append(' ').append(entry.function().linkageName()).append('\n');
        }
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, "#include <jni.h>\n#include <stdint.h>\n#include <string.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + ADAPTER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("PermanentStrings.java");
        Files.writeString(consumer, """
                public final class PermanentStrings {
                    static native void exercise(int budget);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(Integer.parseInt(args[1]));
                        System.out.println("permanent-strings-ok:" + args[1]);
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
            Path image = directory.resolve("permanent-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (int budget : List.of(-1, 0, 1, 2, 3, 4)) {
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "PermanentStrings", image.toString(), Integer.toString(budget));
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (budget < 0 ? "unset" : budget) + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget < 0) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(budget));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("permanent/String child timeout"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), child.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.equals("permanent-strings-ok:" + budget + "\n"), name + ": " + output);
            }
        }
        for (String file : List.of("program.ll", "Catalog.iron", "adapter.c", "PermanentStrings.java", "PermanentStrings.class")) {
            Files.writeString(directory.resolve(file + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(directory.resolve(file))) + "\n");
        }
        System.out.println("permanent String evidence: " + directory);
    }

    private static String cType(IrType type) {
        if (type.isReference()) return "void *";
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
            JNIEXPORT void JNICALL Java_PermanentStrings_exercise(JNIEnv *env, jclass type, jint budget) {
                (void)type; ironwood_bridge_bootstrap(); struct ironwood_bridge_result f = {0};
                uint64_t baseline = ironwood_live_allocation_count();
                int status = create(ADDRESS(units), 5, FRAME);
                if (budget >= 0 && budget < 3) {
                    CHECK(status == 1 && f.value.reference == NULL);
                    CHECK(strstr(f.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(unknown(FRAME) == 0 && f.value.reference == NULL);
                    CHECK(ping(FRAME) == 0 && f.value.integer == 42); return;
                }
                CHECK(status == 0); void *holder = f.value.reference;
                CHECK(ironwood_live_allocation_count() == baseline + 2);
                CHECK(text(holder, FRAME) == 0 && matches(f.value.reference));
                void *borrowed = f.value.reference;
                CHECK(unknown(FRAME) == 0 && f.value.reference == holder);
                if (budget >= 0) {
                    status = copied(holder, ADDRESS(units), 5, FRAME);
                    CHECK(status == 1 && strstr(f.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline + 2);
                    CHECK(text(holder, FRAME) == 0 && f.value.reference == borrowed);
                    CHECK(unknown(FRAME) == 0 && f.value.reference == holder);
                    CHECK(ping(FRAME) == 0 && f.value.integer == 42); return;
                }
                uint64_t allocations = ironwood_allocation_count();
                for (int index = 0; index < 1000; index++) {
                    CHECK(text(holder, FRAME) == 0 && f.value.reference == borrowed);
                    CHECK(unknown(FRAME) == 0 && f.value.reference == holder);
                }
                CHECK(ironwood_allocation_count() == allocations);
                CHECK(alias(holder, ADDRESS(units), 5, FRAME) == 0 && matches(f.value.reference));
                ironwood_deallocate(f.value.reference);
                CHECK(copied(holder, ADDRESS(units), 5, FRAME) == 0 && matches(f.value.reference));
                ironwood_deallocate(f.value.reference);
                CHECK(length(holder, ADDRESS(units), 5, FRAME) == 0 && f.value.integer == 5);
                CHECK(alias(holder, 0, -1, FRAME) == 0 && f.value.reference == NULL);
                CHECK(length(holder, 0, -1, FRAME) == 0 && f.value.integer == -1);
                CHECK(ironwood_live_allocation_count() == baseline + 2);
                CHECK(ironwood_allocation_count() == allocations + 4);
                CHECK(text(holder, FRAME) == 0 && f.value.reference == borrowed);
                CHECK(ping(FRAME) == 0 && f.value.integer == 42);
                /* The published Catalog and its owned String intentionally live until process exit. */
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

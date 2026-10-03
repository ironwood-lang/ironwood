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

/** Private host transport verifies the shared typed entries before public object adapters. */
final class BridgeRootStringNativeTests {
    static final String NAME = "Java Bridge root String entries preserve copy cleanup rollback and final slots";
    private static final String SOURCE = """
            package rootstrings;
            public final class Roots {
            private Roots() {}
            public static final class Item {
                private int number;
                public Item(int number) { this.number = number; }
                public int number() { return number; }
            }
            public static final class Label {
                private final String label;
                private Item retained;
                private static int entered;
                private static int destroyed;
                public Label(String first, String second, boolean fail) {
                    entered++;
                    label = new String(first);
                    if (fail) throw null;
                }
                destructor { free label; destroyed++; }
                public String text() { return label; }
                public String alias(String first, String second, boolean choose) {
                    entered++; return choose ? first : second;
                }
                public String fresh(String input) { entered++; return new String(input); }
                public int lengths(String first, String second) {
                    entered++;
                    return (first == null ? -1 : first.length()) + (second == null ? -1 : second.length());
                }
                public void store(Item item, String first, String second, boolean fail) {
                    entered++; retained = item;
                    if (fail) throw null;
                }
                public int retainedValue() { return retained == null ? -1 : retained.number(); }
                public static int calls() { return entered; }
                public static int destroyedCount() { return destroyed; }
                public static int ping() { return 42; }
            }
            public static final class Broken {
                private static int value = explode();
                private static int explode() { throw null; }
                public Broken(String input) {}
            }
            }
            """;

    private BridgeRootStringNativeTests() {}

    static void entries() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Roots.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selected = BridgeExportSurface.concreteObjects(artifact, List.of("rootstrings"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        var module = BridgeEntryModule.rootObjects(artifact, selected.surface().orElseThrow().roots());
        check(module.stringResults().size() == 3, "missing result ownership");
        var finalRoot = BridgeFinalRootRetention.prove(artifact, module);
        check(finalRoot.status() == BridgeProof.Status.PROVED, finalRoot.reason());
        var contract = finalRoot.contract().orElseThrow();
        var finished = contract.program();
        check(contract.matches(module, finished), "root/String payload differs from its final proof");
        check(BridgeRootSet.resolve(finished, module.entries().stream().map(entry -> BridgeCallableId.of(entry.function()))
                .toList()).resolved(), "final optimization changed entry roots");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p3a/root-strings").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(finished));
        Files.writeString(directory.resolve("final-root.txt"), "roots=" + contract.destruction().keySet()
                + "\nrollback=" + contract.rollback().keySet()
                + "\nexports=" + finished.exportRoots().stream().sorted().toList()
                + "\nllvm-sha256=" + BridgeGeneration.bytesDigest(Files.readAllBytes(llvm)) + "\n");
        Files.writeString(directory.resolve("Roots.iron"), SOURCE);
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module));
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("RootStrings.java");
        Files.writeString(consumer, """
                public final class RootStrings {
                    static native void exercise(int budget);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(Integer.parseInt(args[1]));
                        System.out.println("root-strings-ok:" + args[1]);
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
            Path image = directory.resolve("roots-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            // 100 + limit selects retained-slot failure after the initial roots exist.
            for (int budget : List.of(-1, 0, 1, 2, 3, 4, 5, 6, 105, 106, 107)) {
                int limit = budget >= 100 ? budget - 100 : budget;
                var command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "RootStrings", image.toString(), Integer.toString(budget)));
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (budget < 0 ? "unset" : limit) + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget < 0) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(limit));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("root/String child timeout"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), child.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.matches(budget < 0
                        ? "root-string-benchmark:10000:70000:[0-9]+\\nroot-borrowed-benchmark:100000:[0-9]+\\nroot-strings-ok:-1\\n"
                        : "root-strings-ok:" + budget + "\\n"), name + ": " + output);
            }
        }
        for (String file : List.of("program.ll", "Roots.iron", "adapter.c", "RootStrings.java", "RootStrings.class")) {
            Files.writeString(directory.resolve(file + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(directory.resolve(file))) + "\n");
        }
        System.out.println("root String evidence: " + directory);
    }

    private static String adapter(BridgeEntryModule module) {
        var declarations = new StringBuilder();
        String body = ADAPTER;
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            String name = id.kind() == IrCallableKind.CONSTRUCTOR ? "new" + simpleName(id.owner()) : id.name();
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(entry.function().parameters().stream().map(parameter -> cType(parameter.value().type()))
                            .collect(java.util.stream.Collectors.joining(", "))).append(");\n#define ")
                    .append(name).append(' ').append(entry.function().linkageName()).append('\n');
            var slots = module.rootRetention().orElseThrow().entries().get(id).slots();
            for (int index = 0; index < slots.size(); index++) {
                var slot = slots.get(index);
                body = body.replace("${" + name + ":" + slot.holderInput() + ":" + slot.field().name() + "}", Integer.toString(index));
            }
        }
        for (var destruction : module.destructions()) {
            String name = destruction.contract().type().referenceName();
            declarations.append("extern void ").append(destruction.function().linkageName()).append("(void *);\n#define free")
                    .append(simpleName(name)).append(' ').append(destruction.function().linkageName()).append('\n');
        }
        check(!body.contains("${"), "unresolved retention slot");
        return "#include <jni.h>\n#include <stdint.h>\n#include <stdio.h>\n#include <string.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + body;
    }

    private static String cType(IrType type) {
        if (type.isReference()) return "void *";
        if (type.equals(IrType.I8)) return "uint8_t";
        if (type.equals(IrType.I32)) return "int32_t";
        if (type.equals(IrType.I64)) return "int64_t";
        throw new AssertionError(type);
    }

    private static String simpleName(String binary) {
        return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
    }

    private static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            struct frame { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[4]; };
            #define ADDRESS(value) ((int64_t)(uintptr_t)(value))
            #define FRAME ADDRESS(&f)
            #define CHECK(condition) do { if (!(condition)) { jclass error = (*env)->FindClass(env, "java/lang/AssertionError"); \
                if (error != NULL) { (*env)->ThrowNew(env, error, #condition); (*env)->DeleteLocalRef(env, error); } return; } } while (0)
            static const uint16_t first[] = {0, 'x', 0xd83d, 0xde00};
            static const uint16_t second[] = {'b', 0xd800, 'c'};
            static int matches(void *pointer, const uint16_t *units, int length) {
                const struct ironwood_string *value = pointer;
                return value != NULL && value->utf16_length == length
                    && memcmp(value->units, units, (size_t)length * sizeof(uint16_t)) == 0;
            }
            JNIEXPORT void JNICALL Java_RootStrings_exercise(JNIEnv *env, jclass type, jint budget) {
                (void)type; ironwood_bridge_bootstrap();
                struct frame f = {0}; uint64_t baseline = ironwood_live_allocation_count();
                int status = newLabel(ADDRESS(first), 4, ADDRESS(second), 3, 0, FRAME);
                if (budget >= 0 && budget < 4) {
                    CHECK(status == 1 && f.result.value.reference == NULL);
                    CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(calls(FRAME) == 0 && f.result.value.integer == (budget == 3 ? 1 : 0));
                    CHECK(destroyedCount(FRAME) == 0 && f.result.value.integer == 0);
                    CHECK(ping(FRAME) == 0 && f.result.value.integer == 42);
                    return;
                }
                CHECK(status == 0); void *holder = f.result.value.reference;
                CHECK(ironwood_live_allocation_count() == baseline + 2);
                CHECK(text(holder, FRAME) == 0 && matches(f.result.value.reference, first, 4));
                void *borrowed = f.result.value.reference;
                if (budget >= 100) {
                    int limit = budget - 100;
                    CHECK(newItem(29, FRAME) == 0); void *item = f.result.value.reference;
                    CHECK(store(holder, item, 0, -1, 0, -1, 0, FRAME) == 0);
                    CHECK(store(holder, NULL, ADDRESS(first), 4, ADDRESS(second), 3, 1, FRAME) == 1);
                    CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(f.slots[${store:0:retained}].holder == holder);
                    CHECK(f.slots[${store:0:retained}].value == (limit < 7 ? item : NULL));
                    CHECK(calls(FRAME) == 0 && f.result.value.integer == (limit < 7 ? 2 : 3));
                    CHECK(ironwood_live_allocation_count() == baseline + 3);
                    CHECK(retainedValue(holder, FRAME) == 0 && f.result.value.integer == (limit < 7 ? 29 : -1));
                    CHECK(store(holder, NULL, 0, -1, 0, -1, 0, FRAME) == 0);
                    freeLabel(holder); freeItem(item);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    return;
                }
                if (budget >= 0) {
                    status = lengths(holder, ADDRESS(first), 4, ADDRESS(second), 3, FRAME);
                    CHECK(status == (budget < 6 ? 1 : 0));
                    if (status == 1) CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    else CHECK(f.result.value.integer == 7);
                    CHECK(ironwood_live_allocation_count() == baseline + 2);
                    CHECK(calls(FRAME) == 0 && f.result.value.integer == (budget < 6 ? 1 : 2));
                    CHECK(matches(borrowed, first, 4)); freeLabel(holder);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(destroyedCount(FRAME) == 0 && f.result.value.integer == 1);
                    return;
                }
                CHECK(lengths(holder, 0, -1, ADDRESS(second), 3, FRAME) == 0 && f.result.value.integer == 2);
                CHECK(alias(holder, ADDRESS(first), 4, ADDRESS(second), 3, 1, FRAME) == 0);
                CHECK(matches(f.result.value.reference, first, 4) && ironwood_live_allocation_count() == baseline + 3);
                ironwood_deallocate(f.result.value.reference);
                CHECK(alias(holder, ADDRESS(first), 4, ADDRESS(second), 3, 0, FRAME) == 0);
                CHECK(matches(f.result.value.reference, second, 3)); ironwood_deallocate(f.result.value.reference);
                CHECK(alias(holder, 0, -1, ADDRESS(second), 3, 1, FRAME) == 0 && f.result.value.reference == NULL);
                CHECK(fresh(holder, ADDRESS(second), 3, FRAME) == 0 && matches(f.result.value.reference, second, 3));
                ironwood_deallocate(f.result.value.reference);
                CHECK(ironwood_live_allocation_count() == baseline + 2 && matches(borrowed, first, 4));
                CHECK(newItem(29, FRAME) == 0); void *item = f.result.value.reference;
                CHECK(store(holder, item, ADDRESS(first), 4, ADDRESS(second), 3, 1, FRAME) == 1);
                CHECK(f.slots[${store:0:retained}].holder == holder && f.slots[${store:0:retained}].value == item);
                CHECK(strstr(f.result.failure.type_name, "NullPointerException") != NULL);
                CHECK(ironwood_live_allocation_count() == baseline + 4);
                CHECK(retainedValue(holder, FRAME) == 0 && f.result.value.integer == 29);
                CHECK(newLabel(ADDRESS(first), 4, ADDRESS(second), 3, 1, FRAME) == 1 && f.result.value.reference == NULL);
                CHECK(ironwood_live_allocation_count() == baseline + 5);
                CHECK(destroyedCount(FRAME) == 0 && f.result.value.integer == 0);
                for (int repeat = 0; repeat < 3; repeat++) {
                    CHECK(newBroken(ADDRESS(first), 4, FRAME) == 1 && f.result.value.reference == NULL);
                    CHECK(strstr(f.result.failure.type_name, "NullPointerException") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline + 6);
                }
                CHECK(ping(FRAME) == 0 && f.result.value.integer == 42);
                uint64_t allocations = ironwood_allocation_count(); int64_t checksum = 0, start = ironwood_nano_time();
                for (int iteration = 0; iteration < 10000; iteration++) {
                    CHECK(lengths(holder, ADDRESS(first), 4, ADDRESS(second), 3, FRAME) == 0);
                    checksum += f.result.value.integer;
                }
                int64_t elapsed = ironwood_nano_time() - start;
                CHECK(checksum == 70000 && ironwood_allocation_count() == allocations + 20000);
                CHECK(ironwood_live_allocation_count() == baseline + 6);
                printf("root-string-benchmark:10000:70000:%lld\\n", (long long)elapsed);
                allocations = ironwood_allocation_count(); start = ironwood_nano_time();
                for (int iteration = 0; iteration < 100000; iteration++) {
                    CHECK(text(holder, FRAME) == 0 && f.result.value.reference == borrowed);
                }
                elapsed = ironwood_nano_time() - start;
                CHECK(ironwood_allocation_count() == allocations);
                printf("root-borrowed-benchmark:100000:%lld\\n", (long long)elapsed); fflush(stdout);
                CHECK(store(holder, NULL, 0, -1, 0, -1, 0, FRAME) == 0);
                CHECK(f.slots[${store:0:retained}].holder == holder && f.slots[${store:0:retained}].value == NULL);
                freeLabel(holder); freeItem(item);
                /* Three source NPE snapshots remain live in this private transport. */
                CHECK(ironwood_live_allocation_count() == baseline + 3);
                CHECK(destroyedCount(FRAME) == 0 && f.result.value.integer == 1);
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

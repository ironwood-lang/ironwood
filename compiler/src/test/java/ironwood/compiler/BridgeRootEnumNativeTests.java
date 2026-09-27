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

/** Exercises mixed enum conversion, retained slots and unpublished rollback in isolated JVMs. */
final class BridgeRootEnumNativeTests {
    static final String NAME = "Java Bridge root enum entries preserve initialization failure deltas and destruction";
    private static final String SOURCE = """
            package rootenumvalues;
            public final class Catalog {
                // The holder owns its copied label and only borrows the retained Item.
                private final String label;
                private Side side;
                private Item retained;
                private static int failure;
                private static int initialized;
                private static int constructed;
                private static int destroyed;
                public Catalog(Item item, Side side, String input) {
                    constructed++; retained = item; this.side = side; label = new String(input);
                    if (failure == 2) throw null;
                }
                destructor { destroyed++; free label; }
                public static void setFailure(int value) { failure = value; }
                public static int initializations() { return initialized; }
                public static int constructions() { return constructed; }
                public static int destructions() { return destroyed; }
                public Side side() { return side; }
                public String text() { return label; }
                public void store(Item item, Side value, String input, boolean fail) {
                    retained = item; side = value;
                    if (fail) throw null;
                }
                public int read() { return retained == null ? -1 : retained.number(); }
                public static int ping() { return 42; }
                public enum Side {
                    SELL(29), BUY(11);
                    private final int number;
                    private final String caption = "side";
                    Side(int number) { initialized++; this.number = number; if (failure == 1) throw null; }
                    public final int code() { return number; }
                    public String caption() { return caption; }
                }
                public static final class Item { public Item() {} public int number() { return 17; } }
            }
            """;

    private BridgeRootEnumNativeTests() {}

    static void entries() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Catalog.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var surface = BridgeExportSurface.objectValues(artifact, List.of("rootenumvalues"));
        check(surface.surface().isPresent(), surface.diagnostics().toString());
        var selected = surface.surface().orElseThrow();
        var conversions = BridgeEnumConversions.forSurface(artifact, selected);
        var module = BridgeEntryModule.rootObjects(artifact, selected.roots(), conversions);
        check(module.destructions().size() == 2 && module.rootRetention().isPresent(), "mixed root capabilities missing");
        var finalRoot = BridgeFinalRootRetention.prove(artifact, module);
        check(finalRoot.status() == BridgeProof.Status.PROVED, finalRoot.reason());
        var contract = finalRoot.contract().orElseThrow();
        var finished = contract.program();
        check(contract.matches(module, finished), "root/enum payload differs from its final proof");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p3a/root-enums").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(finished));
        Files.writeString(directory.resolve("final-root.txt"), "roots=" + contract.destruction().keySet()
                + "\nrollback=" + contract.rollback().keySet()
                + "\npermanent=" + contract.lifetime().references().keySet()
                + "\nexports=" + finished.exportRoots().stream().sorted().toList()
                + "\nllvm-sha256=" + BridgeGeneration.bytesDigest(Files.readAllBytes(llvm)) + "\n");
        Files.writeString(directory.resolve("Catalog.iron"), SOURCE);
        var declarations = new StringBuilder();
        String body = ADAPTER;
        for (var entry : module.entries()) {
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(entry.function().parameters().stream().map(parameter -> cType(parameter.value().type()))
                            .collect(java.util.stream.Collectors.joining(", "))).append(");\n#define ")
                    .append(entry.root().callable().kind() == IrCallableKind.CONSTRUCTOR
                            ? (entry.root().callable().owner().endsWith("$Item") ? "newItem" : "create") : entry.root().callable().name())
                    .append(' ').append(entry.function().linkageName()).append('\n');
        }
        for (var entry : module.entries()) {
            var slots = module.rootRetention().orElseThrow().entries().get(entry.root().callable()).slots();
            for (int index = 0; index < slots.size(); index++) {
                if (entry.root().callable().name().equals("store")) body = body.replace("${store-slot}", Integer.toString(index));
            }
        }
        for (var destruction : module.destructions()) {
            declarations.append("extern void ").append(destruction.function().linkageName()).append("(void *);\n#define ")
                    .append(destruction.contract().type().referenceName().endsWith("$Item") ? "freeItem" : "freeCatalog")
                    .append(' ').append(destruction.function().linkageName()).append('\n');
        }
        check(!body.contains("${"), "unresolved retained slot");
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, "#include <jni.h>\n#include <stdint.h>\n#include <stdio.h>\n#include <string.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + body);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("RootEnums.java");
        Files.writeString(consumer, """
                public final class RootEnums {
                    static native void exercise(int budget);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(Integer.parseInt(args[1]));
                        System.out.println("root-enums-ok:" + args[1]);
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
            Path image = directory.resolve("root-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (int budget : List.of(-1, 0, 1, 2, 3, 4, 5, 6, -2, -3, -4, -5, -6)) {
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "RootEnums", image.toString(), Integer.toString(budget));
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (budget < 0 ? "unset" : budget) + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget < 0) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(budget));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("root/enum child timeout"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), child.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.matches(budget == -1 ? "root-enum-benchmark:100000:100000:[0-9]+\\nroot-enums-ok:-1\\n"
                        : "root-enums-ok:" + budget + "\\n"), name + ": " + output);
            }
        }
        for (String file : List.of("program.ll", "Catalog.iron", "adapter.c", "RootEnums.java", "RootEnums.class")) {
            Files.writeString(directory.resolve(file + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(directory.resolve(file))) + "\n");
        }
        System.out.println("root enum evidence: " + directory);
    }

    private static String cType(IrType type) {
        if (type.isReference()) return "void *";
        if (type.equals(IrType.I8)) return "uint8_t";
        if (type.equals(IrType.I32)) return "int32_t";
        if (type.equals(IrType.I64)) return "int64_t";
        throw new AssertionError(type);
    }

    private static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            struct frame { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[2]; };
            #define ADDRESS(value) ((int64_t)(uintptr_t)(value))
            #define FRAME ADDRESS(&f)
            #define CHECK(condition) do { if (!(condition)) { jclass error = (*env)->FindClass(env, "java/lang/AssertionError"); \
                if (error != NULL) { (*env)->ThrowNew(env, error, #condition); (*env)->DeleteLocalRef(env, error); } return; } } while (0)
            static const uint16_t units[] = {0, 'x', 0xd83d, 0xde00, 0xd800};
            static int matches(void *pointer) {
                const struct ironwood_string *value = pointer;
                return value != NULL && value->utf16_length == 5 && memcmp(value->units, units, sizeof(units)) == 0;
            }
            JNIEXPORT void JNICALL Java_RootEnums_exercise(JNIEnv *env, jclass type, jint budget) {
                (void)type; ironwood_bridge_bootstrap(); struct frame f = {0};
                uint64_t baseline = ironwood_live_allocation_count();
                if (budget == -2) {
                    CHECK(setFailure(1, FRAME) == 0);
                    CHECK(create(NULL, 1, ADDRESS(units), 5, FRAME) == 1 && f.result.value.reference == NULL);
                    CHECK(strstr(f.result.failure.type_name, "NullPointerException") != NULL);
                    void *failure = f.result.exception;
                    uint64_t held = ironwood_live_allocation_count(), count = ironwood_allocation_count();
                    CHECK(constructions(FRAME) == 0 && f.result.value.integer == 0);
                    CHECK(create(NULL, 1, ADDRESS(units), 5, FRAME) == 1 && f.result.exception == failure);
                    CHECK(ironwood_live_allocation_count() == held && ironwood_allocation_count() == count + 1);
                    CHECK(initializations(FRAME) == 0 && f.result.value.integer == 1);
                    CHECK(ping(FRAME) == 0 && f.result.value.integer == 42); return;
                }
                if (budget == -3) {
                    CHECK(setFailure(2, FRAME) == 0);
                    uint64_t before = ironwood_allocation_count();
                    CHECK(create(NULL, 1, ADDRESS(units), 5, FRAME) == 1 && f.result.value.reference == NULL);
                    /* Only the source exception remains; unpublished holder, label and copy are gone. */
                    CHECK(ironwood_allocation_count() == before + 4 && ironwood_live_allocation_count() == baseline + 1);
                    CHECK(destructions(FRAME) == 0 && f.result.value.integer == 0);
                    baseline++;
                    CHECK(setFailure(0, FRAME) == 0);
                }
                if (budget == -5) {
                    f.result.value.reference = (void *)(uintptr_t)1;
                    CHECK(create(NULL, 999, ADDRESS(units), 5, FRAME) == 3 && f.result.value.reference == NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(constructions(FRAME) == 0 && f.result.value.integer == 0);
                }
                int status = create(NULL, budget == -4 || budget == -6 ? -1 : 1, ADDRESS(units), 5, FRAME);
                if (budget >= 0 && budget < 3) {
                    CHECK(status == 1 && f.result.value.reference == NULL);
                    CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    CHECK(initializations(FRAME) == 0 && f.result.value.integer == (budget == 0 ? 0 : 2));
                    CHECK(constructions(FRAME) == 0 && f.result.value.integer == (budget == 2 ? 1 : 0));
                    CHECK(ping(FRAME) == 0 && f.result.value.integer == 42); return;
                }
                CHECK(status == 0); void *holder = f.result.value.reference;
                CHECK(ironwood_live_allocation_count() == baseline + 2);
                CHECK(side(holder, FRAME) == 0 && f.result.value.integer == (budget == -4 || budget == -6 ? -1 : 1));
                CHECK(initializations(FRAME) == 0 && f.result.value.integer == (budget == -4 || budget == -6 ? 0 : 2));
                CHECK(text(holder, FRAME) == 0 && matches(f.result.value.reference));
                status = newItem(FRAME);
                if (budget == 3) {
                    CHECK(status == 1 && f.result.value.reference == NULL);
                    freeCatalog(holder); CHECK(ironwood_live_allocation_count() == baseline); return;
                }
                CHECK(status == 0); void *item = f.result.value.reference;
                CHECK(store(holder, item, -1, 0, -1, 0, FRAME) == 0);
                CHECK(f.slots[${store-slot}].holder == holder && f.slots[${store-slot}].value == item);
                if (budget == -6) {
                    CHECK(setFailure(1, FRAME) == 0);
                    CHECK(store(holder, NULL, 1, ADDRESS(units), 5, 0, FRAME) == 1);
                    CHECK(f.slots[${store-slot}].holder == holder && f.slots[${store-slot}].value == item);
                    void *failure = f.result.exception;
                    uint64_t count = ironwood_allocation_count();
                    CHECK(store(holder, NULL, 1, ADDRESS(units), 5, 0, FRAME) == 1 && f.result.exception == failure);
                    CHECK(ironwood_allocation_count() == count + 1 && ironwood_live_allocation_count() == baseline + 4);
                    CHECK(read(holder, FRAME) == 0 && f.result.value.integer == 17);
                    CHECK(store(holder, NULL, -1, 0, -1, 0, FRAME) == 0);
                    freeCatalog(holder); freeItem(item);
                    CHECK(ironwood_live_allocation_count() == baseline + 1); return;
                }
                status = store(holder, NULL, 0, ADDRESS(units), 5, 1, FRAME);
                CHECK(status == 1 && f.slots[${store-slot}].holder == holder);
                CHECK(f.slots[${store-slot}].value == (budget == 4 ? item : NULL));
                CHECK(strstr(f.result.failure.type_name, budget == 4 || budget == 5 ? "OutOfMemoryError" : "NullPointerException") != NULL);
                uint64_t exceptions = budget == 4 || budget == 5 ? 0 : 1;
                CHECK(ironwood_live_allocation_count() == baseline + 3 + exceptions);
                if (budget < 0) {
                    CHECK(store(holder, item, 1, 0, -1, 0, FRAME) == 0);
                    CHECK(side(holder, FRAME) == 0 && f.result.value.integer == 1);
                    CHECK(code(0, FRAME) == 0 && f.result.value.integer == 11);
                    CHECK(code(1, FRAME) == 0 && f.result.value.integer == 29);
                    uint64_t count = ironwood_allocation_count();
                    CHECK(caption(1, FRAME) == 0 && ((struct ironwood_string *)f.result.value.reference)->utf16_length == 4);
                    CHECK(ironwood_allocation_count() == count);
                }
                if (budget == -1) {
                    uint64_t allocations = ironwood_allocation_count(); int64_t sum = 0, start = ironwood_nano_time();
                    for (int index = 0; index < 100000; index++) {
                        CHECK(side(holder, FRAME) == 0); sum += f.result.value.integer;
                        CHECK(read(holder, FRAME) == 0 && f.result.value.integer == 17);
                    }
                    CHECK(sum == 100000 && ironwood_allocation_count() == allocations);
                    printf("root-enum-benchmark:100000:%lld:%lld\\n", (long long)sum, (long long)(ironwood_nano_time() - start));
                    fflush(stdout);
                }
                CHECK(store(holder, NULL, -1, 0, -1, 0, FRAME) == 0);
                CHECK(f.slots[${store-slot}].holder == holder && f.slots[${store-slot}].value == NULL);
                freeCatalog(holder); freeItem(item);
                CHECK(ironwood_live_allocation_count() == baseline + exceptions);
                CHECK(destructions(FRAME) == 0 && f.result.value.integer == 1);
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

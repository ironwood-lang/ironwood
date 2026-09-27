// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Private host transport exercises exactly the final proved mixed lifetime payload. */
final class BridgeMixedLifetimeNativeTests {
    static final String NAME = "Java Bridge mixed lifetime native entries preserve permanent storage and root deltas";

    private BridgeMixedLifetimeNativeTests() {}

    static void entries() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("Holder.iron", BridgeMixedLifetimeTests.SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selected = BridgeExportSurface.objectValues(artifact, List.of("mixedlife"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        var values = BridgePermanentValues.prove(artifact, surface.roots(), Set.of(IrType.reference("mixedlife.Holder$Catalog")),
                BridgeEnumConversions.forSurface(artifact, surface));
        var module = BridgeEntryModule.rootObjects(artifact, surface.roots(), values);
        var proof = BridgeFinalRootRetention.prove(artifact, module, surface);
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var contract = proof.contract().orElseThrow();
        check(contract.matches(module, contract.program()) && contract.destruction().size() == 2,
                "mixed native payload lacks its exact final proof");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p3a/mixed-lifetime").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(contract.program()));
        Files.writeString(directory.resolve("Holder.iron"), BridgeMixedLifetimeTests.SOURCE);
        Files.writeString(directory.resolve("final-root.txt"), "roots=" + contract.destruction().keySet()
                + "\nrollback=" + contract.rollback().keySet() + "\npermanent=" + contract.lifetime().references().keySet()
                + "\nexports=" + contract.program().exportRoots().stream().sorted().toList()
                + "\nllvm-sha256=" + BridgeGeneration.bytesDigest(Files.readAllBytes(llvm)) + "\n");
        var declarations = new StringBuilder();
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            String owner = id.owner().substring(id.owner().lastIndexOf('$') + 1);
            if (owner.contains(".")) owner = "Holder";
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(entry.function().parameters().stream().map(parameter -> cType(parameter.value().type()))
                            .collect(java.util.stream.Collectors.joining(", "))).append(");\n#define ")
                    .append(owner).append('_').append(id.kind() == IrCallableKind.CONSTRUCTOR ? "create" : id.name())
                    .append(' ').append(entry.function().linkageName()).append('\n');
            var slots = module.rootRetention().orElseThrow().entries().get(id).slots();
            if (!slots.isEmpty()) check(slots.size() == 1 && slots.getFirst().field().name().equals("item"), "mixed slot layout changed");
        }
        for (var entry : module.destructions()) declarations.append("extern void ").append(entry.function().linkageName())
                .append("(void *);\n#define ").append(entry.contract().type().referenceName().endsWith("$Item") ? "Item_free" : "Holder_free")
                .append(' ').append(entry.function().linkageName()).append('\n');
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, "#include <jni.h>\n#include <stdint.h>\n#include <stdio.h>\n#include <string.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + ADAPTER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("MixedLifetime.java");
        Files.writeString(consumer, """
                public final class MixedLifetime {
                    static native void exercise(int budget);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(Integer.parseInt(args[1]));
                        System.out.println("mixed-lifetime-ok:" + args[1]);
                    }
                }
                """);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch")
                + "\nllvm=" + toolchain.version() + "\nprivate host harness; unmodified runtime\n");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("mixed-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (int budget : List.of(-1, 0, 1, 2, 3, 4, 5, 6, 7)) {
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "MixedLifetime", image.toString(), Integer.toString(budget));
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (budget < 0 ? "unset" : budget) + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget < 0) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(budget));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("mixed lifetime child timeout"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), child.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.matches(budget < 0
                        ? "mixed-lifetime-benchmark:100000:100000:[0-9]+\\nmixed-lifetime-ok:-1\\n"
                        : "mixed-lifetime-ok:" + budget + "\\n"), name + ": " + output);
            }
        }
        for (String file : List.of("program.ll", "Holder.iron", "adapter.c", "MixedLifetime.java", "MixedLifetime.class")) {
            Files.writeString(directory.resolve(file + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(directory.resolve(file))) + "\n");
        }
        System.out.println("mixed lifetime evidence: " + directory);
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
            struct frame { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[1]; };
            #define ADDRESS(value) ((int64_t)(uintptr_t)(value))
            #define FRAME ADDRESS(&f)
            #define CHECK(condition) do { if (!(condition)) { jclass error = (*env)->FindClass(env, "java/lang/AssertionError"); \
                if (error != NULL) { (*env)->ThrowNew(env, error, #condition); (*env)->DeleteLocalRef(env, error); } return; } } while (0)
            static const uint16_t units[] = {'a', 0, 0xd800};
            JNIEXPORT void JNICALL Java_MixedLifetime_exercise(JNIEnv *env, jclass type, jint budget) {
                (void)type; ironwood_bridge_bootstrap(); struct frame f = {0};
                uint64_t baseline = ironwood_live_allocation_count();
                int status = Catalog_create(FRAME);
                if (budget == 0 || budget == 1) {
                    CHECK(status == 1 && f.result.value.reference == NULL);
                    CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline);
                    /* Named token 1 is SELL, independently of its source ordinal. */
                    CHECK(Side_code(1, FRAME) == 0 && f.result.value.integer == 11); return;
                }
                CHECK(status == 0); void *catalog = f.result.value.reference;
                CHECK(ironwood_live_allocation_count() == baseline + 2);
                CHECK(Catalog_remember(catalog, FRAME) == 0 && f.result.value.reference == catalog);
                CHECK(Catalog_connect(catalog, catalog, FRAME) == 0);
                status = Item_create(FRAME);
                if (budget == 2) {
                    CHECK(status == 1 && f.result.value.reference == NULL);
                    CHECK(ironwood_live_allocation_count() == baseline + 2);
                    CHECK(Catalog_remember(catalog, FRAME) == 0 && f.result.value.reference == catalog); return;
                }
                CHECK(status == 0); void *item = f.result.value.reference;
                status = Holder_create(item, catalog, 1, ADDRESS(units), 3, FRAME);
                if (budget >= 3 && budget <= 5) {
                    CHECK(status == 1 && f.result.value.reference == NULL);
                    CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(ironwood_live_allocation_count() == baseline + 3);
                    Item_free(item); CHECK(Catalog_remember(catalog, FRAME) == 0 && f.result.value.reference == catalog);
                    CHECK(ironwood_live_allocation_count() == baseline + 2); return;
                }
                CHECK(status == 0); void *holder = f.result.value.reference;
                CHECK(f.slots[0].holder == holder && f.slots[0].value == item);
                CHECK(ironwood_live_allocation_count() == baseline + 5);
                CHECK(Holder_catalog(holder, FRAME) == 0 && f.result.value.reference == catalog);
                CHECK(Holder_side(holder, FRAME) == 0 && f.result.value.integer == 1);
                CHECK(Holder_text(holder, FRAME) == 0);
                const struct ironwood_string *label = f.result.value.reference;
                CHECK(label->utf16_length == 3 && memcmp(label->units, units, sizeof(units)) == 0);
                CHECK(Holder_fail(holder, NULL, catalog, 0, FRAME) == 1);
                CHECK(f.slots[0].holder == holder && f.slots[0].value == NULL);
                uint64_t exceptions = budget == 6 ? 0 : 1;
                CHECK(strstr(f.result.failure.type_name, budget == 6 ? "OutOfMemoryError" : "NullPointerException") != NULL);
                CHECK(Holder_change(holder, item, catalog, 1, FRAME) == 0 && f.slots[0].value == item);
                if (budget < 0) {
                    uint64_t allocations = ironwood_allocation_count(); int64_t sum = 0, start = ironwood_nano_time();
                    for (int index = 0; index < 100000; index++) {
                        CHECK(Holder_catalog(holder, FRAME) == 0); sum += f.result.value.reference == catalog;
                        CHECK(Catalog_text(catalog, FRAME) == 0);
                    }
                    CHECK(sum == 100000 && ironwood_allocation_count() == allocations);
                    printf("mixed-lifetime-benchmark:100000:%lld:%lld\\n", (long long)sum, (long long)(ironwood_nano_time() - start)); fflush(stdout);
                }
                CHECK(Holder_clear(holder, FRAME) == 0 && f.slots[0].value == NULL);
                Holder_free(holder); Item_free(item);
                CHECK(ironwood_live_allocation_count() == baseline + 2 + exceptions);
                CHECK(Catalog_remember(catalog, FRAME) == 0 && f.result.value.reference == catalog);
                CHECK(Catalog_text(catalog, FRAME) == 0);
                CHECK(((struct ironwood_string *)f.result.value.reference)->utf16_length == 7);
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
